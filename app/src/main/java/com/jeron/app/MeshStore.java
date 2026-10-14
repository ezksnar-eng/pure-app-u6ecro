package com.jeron.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** تخزين الرسائل المشفرة المحمولة (Store & Forward). كل شي مشفر، والجهاز الوسيط ما يكدر يقرأ المحتوى. */
final class MeshStore {
    private static MeshStore inst;
    private static final int MAX_CARRY = 300;
    private static final int MAX_ACKS = 3000;
    private static final int MAX_ENV_CHARS = 12000;

    private final SharedPreferences sp;
    private final LinkedHashMap<String, JSONObject> carry = new LinkedHashMap<>();
    private final LinkedHashSet<String> acks = new LinkedHashSet<>();
    private final LinkedHashMap<String, JSONObject> inbox = new LinkedHashMap<>();

    static synchronized MeshStore get(Context c) {
        if (inst == null) inst = new MeshStore(c.getApplicationContext());
        return inst;
    }

    private MeshStore(Context c) {
        sp = c.getSharedPreferences("mesh_store", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(sp.getString("carry", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                carry.put(o.getString("id"), o);
            }
            JSONArray k = new JSONArray(sp.getString("acks", "[]"));
            for (int i = 0; i < k.length(); i++) acks.add(k.getString(i));
            JSONArray n = new JSONArray(sp.getString("inbox", "[]"));
            for (int i = 0; i < n.length(); i++) {
                JSONObject o = n.getJSONObject(i);
                inbox.put(o.getString("id"), o);
            }
        } catch (Exception ignored) {
        }
        purgeExpired();
    }

    private void persist() {
        try {
            JSONArray a = new JSONArray();
            for (JSONObject o : carry.values()) a.put(o);
            JSONArray k = new JSONArray();
            for (String s : acks) k.put(s);
            JSONArray n = new JSONArray();
            for (JSONObject o : inbox.values()) n.put(o);
            sp.edit().putString("carry", a.toString()).putString("acks", k.toString()).putString("inbox", n.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    synchronized void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, JSONObject>> it = carry.entrySet().iterator();
        boolean ch = false;
        while (it.hasNext()) {
            JSONObject o = it.next().getValue();
            if (o.optLong("exp", 0) < now) {
                it.remove();
                ch = true;
            }
        }
        if (ch) persist();
    }

    /** إضافة رسالة للحمل. ترجع true لو انضافت جديدة. */
    synchronized boolean addCarry(JSONObject env) {
        try {
            String id = env.optString("id", "");
            if (id.length() == 0 || acks.contains(id) || carry.containsKey(id)) return false;
            if (env.toString().length() > MAX_ENV_CHARS) return false;
            if (env.optLong("exp", 0) < System.currentTimeMillis()) return false;
            while (carry.size() >= MAX_CARRY) {
                String first = carry.keySet().iterator().next();
                carry.remove(first);
            }
            carry.put(id, env);
            persist();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    synchronized int carryCount() {
        return carry.size();
    }

    synchronized List<String> haveIds() {
        return new ArrayList<>(carry.keySet());
    }

    synchronized List<String> ackList() {
        List<String> l = new ArrayList<>(acks);
        int from = Math.max(0, l.size() - 500);
        return new ArrayList<>(l.subList(from, l.size()));
    }

    synchronized List<JSONObject> missingFor(Set<String> have) {
        List<JSONObject> out = new ArrayList<>();
        for (Map.Entry<String, JSONObject> e : carry.entrySet()) {
            if (!have.contains(e.getKey()) && !acks.contains(e.getKey())) out.add(e.getValue());
        }
        return out;
    }

    synchronized void ack(Collection<String> ids) {
        boolean ch = false;
        for (String id : ids) {
            if (acks.add(id)) ch = true;
            if (carry.remove(id) != null) ch = true;
        }
        while (acks.size() > MAX_ACKS) {
            String first = acks.iterator().next();
            acks.remove(first);
        }
        if (ch) persist();
    }

    /** رسالة وصلت لهذا الجهاز: تنحفظ بالوارد لين يستلمها التطبيق. ترجع true لو جديدة. */
    synchronized List<String> ackedAmong(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) if (acks.contains(id)) out.add(id);
        return out;
    }

    synchronized boolean addInbox(JSONObject env) {
        String id = env.optString("id", "");
        if (id.length() == 0 || inbox.containsKey(id)) return false;
        inbox.put(id, env);
        persist();
        return true;
    }

    synchronized void removeInbox(String id) {
        if (inbox.remove(id) != null) persist();
        ack(java.util.Collections.singletonList(id));
    }

    synchronized String inboxJson() {
        JSONArray a = new JSONArray();
        for (JSONObject o : inbox.values()) a.put(o);
        return a.toString();
    }
}
