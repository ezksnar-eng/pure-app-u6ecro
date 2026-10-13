package com.jeron.app;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * خدمة بالخلفية:
 * 1) تفحص رسائل جديدة كل 45 ثانية وتطلع إشعار حقيقي (بدون محتوى لأن الرسائل مشفرة) لما التطبيق مسكّر.
 * 2) تشغّل شبكة Mesh القريبة (Nearby) إذا المستخدم فعّلها.
 */
public class CoreService extends Service {
    static volatile CoreService inst;
    private static final int FG_ID = 2001;

    private Thread poller;
    private volatile boolean run = false;
    private MeshManager mesh;
    private MeshStore store;

    static void start(Context c) {
        try {
            ContextCompat.startForegroundService(c, new Intent(c, CoreService.class));
        } catch (Exception ignored) {
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, CoreService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        Notifier.channels(this);
        store = MeshStore.get(this);
        mesh = new MeshManager(this, store, new MeshManager.Sink() {
            @Override
            public void onDeliver(String envJson) {
                MainActivity.deliver(envJson);
            }
        });
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean meshWanted = Prefs.sp(this).getBoolean("mesh", false) && meshPermsOk(this);
        startFg(meshWanted);
        applyConfig(meshWanted);
        return START_STICKY;
    }

    private void startFg(boolean withDevice) {
        Notification n = Notifier.service(this, withDevice ? "الاستلام بالخلفية + الشبكة القريبة شغّالة" : "الاستلام بالخلفية شغّال");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (withDevice) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                try {
                    startForeground(FG_ID, n, type);
                } catch (Exception e) {
                    try {
                        startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } catch (Exception e2) {
                        startForeground(FG_ID, n);
                    }
                }
            } else {
                startForeground(FG_ID, n);
            }
        } catch (Exception ignored) {
        }
    }

    private void applyConfig(boolean meshWanted) {
        SharedPreferences sp = Prefs.sp(this);
        if (meshWanted) {
            try {
                mesh.start(sp.getString("myHash", ""));
            } catch (Exception ignored) {
            }
        } else {
            mesh.stop();
        }
        if (!run) {
            run = true;
            poller = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (run) {
                        try {
                            pollOnce();
                        } catch (Exception ignored) {
                        }
                        try {
                            Thread.sleep(45000);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            });
            poller.setDaemon(true);
            poller.start();
        }
    }

    @Override
    public void onDestroy() {
        run = false;
        if (poller != null) poller.interrupt();
        if (mesh != null) mesh.stop();
        inst = null;
        super.onDestroy();
    }

    void meshPush(JSONObject env) {
        if (mesh != null) mesh.push(env);
    }

    String meshStatus() {
        return mesh == null ? "{}" : mesh.status();
    }

    static boolean meshPermsOk(Context c) {
        if (Build.VERSION.SDK_INT >= 31) {
            boolean ok = has(c, Manifest.permission.BLUETOOTH_SCAN) && has(c, Manifest.permission.BLUETOOTH_ADVERTISE) && has(c, Manifest.permission.BLUETOOTH_CONNECT);
            if (Build.VERSION.SDK_INT >= 33) ok = ok && has(c, Manifest.permission.NEARBY_WIFI_DEVICES);
            else ok = ok && has(c, Manifest.permission.ACCESS_FINE_LOCATION);
            return ok;
        }
        return has(c, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private static boolean has(Context c, String p) {
        return ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------- فحص الرسائل الجديدة بالخلفية ----------
    private void pollOnce() throws Exception {
        SharedPreferences sp = Prefs.sp(this);
        String url = sp.getString("url", ""), key = sp.getString("key", ""), token = sp.getString("token", ""), uid = sp.getString("uid", "");
        if (url.length() == 0 || token.length() == 0 || uid.length() == 0) return;
        // إذا التطبيق شغّال وقدامك، أو الويب حي ويرسل إشعاراته بنفسه، ما نكرر الإشعار
        if (MainActivity.visible || System.currentTimeMillis() - MainActivity.lastBeat < 60000) return;
        String since = sp.getString("since", "");
        if (since.length() == 0) {
            sp.edit().putString("since", isoNow()).apply();
            return;
        }
        String q = url + "/rest/v1/messages?select=id,sender,gid,created_at&receiver=eq." + uid + "&sender=neq." + uid
                + "&read_at=is.null&deleted=eq.false&created_at=gt." + URLEncoder.encode(since, "UTF-8") + "&order=created_at.asc&limit=50";
        String body = get(q, key, token);
        if (body == null) return;
        JSONArray arr = new JSONArray(body);
        if (arr.length() == 0) return;
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> kindOf = new HashMap<>();
        Set<String> uids = new LinkedHashSet<>();
        Set<String> gids = new LinkedHashSet<>();
        String maxAt = since;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.getJSONObject(i);
            String gid = m.optString("gid", "");
            if (gid.equals("null")) gid = "";
            String sender = m.optString("sender");
            String tag = gid.length() > 0 ? "g:" + gid : sender;
            counts.put(tag, counts.containsKey(tag) ? counts.get(tag) + 1 : 1);
            if (gid.length() > 0) gids.add(gid);
            else uids.add(sender);
            kindOf.put(tag, gid.length() > 0 ? "g" : "u");
            String at = m.optString("created_at", since);
            if (at.compareTo(maxAt) > 0) maxAt = at;
        }
        Map<String, String> nameOf = new HashMap<>();
        if (!uids.isEmpty()) {
            String r = get(url + "/rest/v1/profiles?select=id,name&id=in.(" + join(uids) + ")", key, token);
            if (r != null) {
                JSONArray a = new JSONArray(r);
                for (int i = 0; i < a.length(); i++) nameOf.put(a.getJSONObject(i).getString("id"), a.getJSONObject(i).optString("name", ""));
            }
        }
        if (!gids.isEmpty()) {
            String r = get(url + "/rest/v1/groups?select=id,name&id=in.(" + join(gids) + ")", key, token);
            if (r != null) {
                JSONArray a = new JSONArray(r);
                for (int i = 0; i < a.length(); i++) nameOf.put("g:" + a.getJSONObject(i).getString("id"), a.getJSONObject(i).optString("name", ""));
            }
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String tag = e.getKey();
            String name = "g".equals(kindOf.get(tag)) ? nameOf.get(tag) : nameOf.get(tag);
            if (name == null || name.length() == 0) name = "جيرون";
            int n = e.getValue();
            Notifier.message(this, name, n == 1 ? "رسالة جديدة" : n + " رسائل جديدة", tag, null);
        }
        sp.edit().putString("since", maxAt).apply();
    }

    private static String join(Set<String> s) {
        StringBuilder b = new StringBuilder();
        for (String x : s) {
            if (b.length() > 0) b.append(',');
            b.append(x);
        }
        return b.toString();
    }

    private static String isoNow() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date());
    }

    private String get(String u, String key, String token) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("apikey", key);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code == 401 || code == 403) {
                Prefs.sp(this).edit().remove("token").apply();
                return null;
            }
            if (code < 200 || code >= 300) return null;
            InputStream in = c.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
