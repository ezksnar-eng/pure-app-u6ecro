package com.jeron.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * شبكة Mesh عبر Nearby Connections (بلوتوث + Wi-Fi Direct) بوضع P2P_CLUSTER.
 * الأجهزة تلتقي تلقائيًا بدون رمز اقتران، وتتبادل رسائل مشفرة (Store & Forward).
 * الرسائل مشفرة من طرف لطرف، والجهاز الوسيط يشوف بس معرف مجهول للمستلم.
 */
final class MeshManager {
    interface Sink {
        void onDeliver(String envJson);
    }

    private static final String SERVICE_ID = "com.jeron.app.mesh";
    private static final int CHUNK_CHARS = 14000;
    private static final long CONNECTION_LIFETIME_MS = 25000;
    private static final long REFRESH_MS = 60000;

    private final Context ctx;
    private final MeshStore store;
    private final Sink sink;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Set<String> pending = new HashSet<>();
    private final Set<String> connected = new HashSet<>();
    private final Map<String, String> names = new HashMap<>();
    private final String nodeName = randomName();

    private ConnectionsClient client;
    private String myHash = "";
    private boolean running = false;
    private int peersSeen = 0;
    private int delivered = 0;

    MeshManager(Context c, MeshStore s, Sink k) {
        ctx = c.getApplicationContext();
        store = s;
        sink = k;
    }

    private static String randomName() {
        String a = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random r = new Random();
        StringBuilder sb = new StringBuilder("j");
        for (int i = 0; i < 9; i++) sb.append(a.charAt(r.nextInt(a.length())));
        return sb.toString();
    }

    synchronized void start(String hash) {
        myHash = hash == null ? "" : hash;
        if (running) return;
        running = true;
        client = Nearby.getConnectionsClient(ctx);
        begin();
        h.postDelayed(refresh, REFRESH_MS);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                client.stopDiscovery();
            } catch (Exception ignored) {
            }
            begin();
            h.postDelayed(this, REFRESH_MS);
        }
    };

    private void begin() {
        try {
            AdvertisingOptions ao = new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
            client.startAdvertising(nodeName, SERVICE_ID, lifecycle, ao);
        } catch (Exception ignored) {
        }
        try {
            DiscoveryOptions dop = new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
            client.startDiscovery(SERVICE_ID, discovery, dop);
        } catch (Exception ignored) {
        }
    }

    synchronized void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        try {
            if (client != null) {
                client.stopAdvertising();
                client.stopDiscovery();
                client.stopAllEndpoints();
            }
        } catch (Exception ignored) {
        }
        pending.clear();
        connected.clear();
    }

    boolean isRunning() {
        return running;
    }

    String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running);
            o.put("peers", connected.size());
            o.put("seen", peersSeen);
            o.put("carry", store.carryCount());
            o.put("delivered", delivered);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** رسالة جديدة من هذا الجهاز: ندفعها فورًا لأي جار متصل. */
    void push(JSONObject env) {
        if (!running) return;
        List<JSONObject> one = new ArrayList<>();
        one.add(env);
        for (String id : new ArrayList<>(connected)) sendEnvs(id, one);
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String id, @NonNull DiscoveredEndpointInfo info) {
            names.put(id, info.getEndpointName());
            if (connected.contains(id) || pending.contains(id)) return;
            // الجهاز اللي اسمه أصغر هو اللي يطلب الاتصال حتى ما يصير تضارب
            if (nodeName.compareTo(info.getEndpointName()) < 0) {
                pending.add(id);
                client.requestConnection(nodeName, id, lifecycle).addOnFailureListener(e -> pending.remove(id));
            }
        }

        @Override
        public void onEndpointLost(@NonNull String id) {
            names.remove(id);
            pending.remove(id);
        }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String id, @NonNull ConnectionInfo info) {
            names.put(id, info.getEndpointName());
            client.acceptConnection(id, payloads);
        }

        @Override
        public void onConnectionResult(@NonNull String id, @NonNull ConnectionResolution r) {
            pending.remove(id);
            if (r.getStatus().isSuccess()) {
                connected.add(id);
                peersSeen++;
                sendHello(id);
                h.postDelayed(() -> {
                    try {
                        client.disconnectFromEndpoint(id);
                    } catch (Exception ignored) {
                    }
                    connected.remove(id);
                }, CONNECTION_LIFETIME_MS);
            }
        }

        @Override
        public void onDisconnected(@NonNull String id) {
            connected.remove(id);
        }
    };

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String id, @NonNull Payload p) {
            if (p.getType() != Payload.Type.BYTES || p.asBytes() == null) return;
            try {
                handle(id, new String(p.asBytes(), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String id, @NonNull PayloadTransferUpdate u) {
        }
    };

    private void send(String id, JSONObject o) {
        try {
            client.sendPayload(id, Payload.fromBytes(o.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
        }
    }

    private void sendHello(String id) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "hi");
            o.put("have", new JSONArray(store.haveIds()));
            o.put("ack", new JSONArray(store.ackList()));
            send(id, o);
        } catch (Exception ignored) {
        }
    }

    private void sendEnvs(String id, List<JSONObject> list) {
        try {
            JSONArray items = new JSONArray();
            int size = 0;
            for (JSONObject e : list) {
                String s = e.toString();
                if (size + s.length() > CHUNK_CHARS && items.length() > 0) {
                    flushItems(id, items);
                    items = new JSONArray();
                    size = 0;
                }
                items.put(e);
                size += s.length();
            }
            if (items.length() > 0) flushItems(id, items);
        } catch (Exception ignored) {
        }
    }

    private void flushItems(String id, JSONArray items) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", "env");
        o.put("items", items);
        send(id, o);
    }

    private void handle(String id, String s) throws Exception {
        JSONObject o = new JSONObject(s);
        String t = o.optString("t");
        if ("hi".equals(t)) {
            JSONArray ack = o.optJSONArray("ack");
            if (ack != null) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < ack.length(); i++) l.add(ack.getString(i));
                store.ack(l);
            }
            Set<String> have = new HashSet<>();
            JSONArray hv = o.optJSONArray("have");
            if (hv != null) for (int i = 0; i < hv.length(); i++) have.add(hv.getString(i));
            sendEnvs(id, store.missingFor(have));
        } else if ("env".equals(t)) {
            JSONArray items = o.optJSONArray("items");
            if (items == null) return;
            List<String> done = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject e = items.getJSONObject(i);
                String to = e.optString("to", "");
                if (myHash.length() > 0 && to.equals(myHash)) {
                    if (store.addInbox(e)) {
                        delivered++;
                        sink.onDeliver(e.toString());
                    }
                    done.add(e.optString("id"));
                } else {
                    store.addCarry(e);
                }
            }
            if (!done.isEmpty()) {
                store.ack(done);
                JSONObject a = new JSONObject();
                a.put("t", "ack");
                a.put("ids", new JSONArray(done));
                send(id, a);
            }
        } else if ("ack".equals(t)) {
            JSONArray ids = o.optJSONArray("ids");
            if (ids != null) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < ids.length(); i++) l.add(ids.getString(i));
                store.ack(l);
            }
        }
    }
}
