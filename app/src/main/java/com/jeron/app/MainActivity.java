package com.jeron.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    static volatile MainActivity inst;
    static volatile boolean visible = false;
    static volatile long lastBeat = 0;
    static volatile boolean webReady = false;

    private static final String START = "https://appassets.androidplatform.net/assets/index.html";
    private static final int RQ_FILE = 11, RQ_AUDIO = 12, RQ_GEO = 13, RQ_BRIDGE = 14;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private Uri camUri;
    private PermissionRequest pendingPerm;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;
    private String pendingOpen;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        Notifier.channels(this);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setGeolocationEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " JeronApp/1.0");

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                webReady = true;
                if (pendingOpen != null) {
                    openChatJs(pendingOpen);
                    pendingOpen = null;
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        boolean needAudio = false;
                        for (String res : r.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) needAudio = true;
                        if (needAudio && !granted(Manifest.permission.RECORD_AUDIO)) {
                            pendingPerm = r;
                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.RECORD_AUDIO}, RQ_AUDIO);
                        } else {
                            r.grant(r.getResources());
                        }
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    cb.invoke(origin, true, false);
                } else {
                    pendingGeo = cb;
                    pendingGeoOrigin = origin;
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, RQ_GEO);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    String[] types = p.getAcceptTypes();
                    boolean img = types != null && types.length > 0 && types[0] != null && types[0].startsWith("image");
                    if (p.isCaptureEnabled() && img) {
                        File f = File.createTempFile("cam", ".jpg", getCacheDir());
                        camUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                        Intent c = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        c.putExtra(MediaStore.EXTRA_OUTPUT, camUri);
                        c.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivityForResult(c, RQ_FILE);
                    } else {
                        camUri = null;
                        startActivityForResult(p.createIntent(), RQ_FILE);
                    }
                    return true;
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "JeronNative");
        web.loadUrl(START);
        handleIntent(getIntent());
    }

    private boolean granted(String p) {
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        String tag = i.getStringExtra("open_tag");
        if (tag != null && tag.length() > 0) {
            if (webReady) openChatJs(tag);
            else pendingOpen = tag;
            i.removeExtra("open_tag");
        }
    }

    private void openChatJs(String tag) {
        web.evaluateJavascript("window.__openChat&&window.__openChat(" + JSONObject.quote(tag) + ")", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        web.onResume();
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        webReady = false;
        if (inst == this) inst = null;
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(function(){return window.__back?window.__back():false})()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (!"true".equals(v)) moveTaskToBack(true);
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RQ_FILE && fileCb != null) {
            Uri[] out = null;
            if (res == RESULT_OK) {
                if (camUri != null) out = new Uri[]{camUri};
                else out = WebChromeClient.FileChooserParams.parseResult(res, data);
            }
            fileCb.onReceiveValue(out);
            fileCb = null;
            camUri = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (req == RQ_AUDIO && pendingPerm != null) {
            if (ok) pendingPerm.grant(pendingPerm.getResources());
            else pendingPerm.deny();
            pendingPerm = null;
        } else if (req == RQ_GEO && pendingGeo != null) {
            pendingGeo.invoke(pendingGeoOrigin, ok, false);
            pendingGeo = null;
        } else if (req == RQ_BRIDGE) {
            web.evaluateJavascript("window.__perm&&window.__perm(" + JSONObject.quote(new Bridge().permStatus()) + ")", null);
        }
    }

    /** تسليم رسالة Mesh واصلة للتطبيق (لو الويب شغّال). */
    static void deliver(final String envJson) {
        final MainActivity a = inst;
        if (a == null || !webReady) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                a.web.evaluateJavascript("window.__meshIn&&window.__meshIn(" + JSONObject.quote(envJson) + ")", null);
            }
        });
    }

    // ---------------- الجسر بين التطبيق (JS) والأندرويد ----------------
    class Bridge {
        @JavascriptInterface
        public String info() {
            return "{\"native\":true,\"sdk\":" + Build.VERSION.SDK_INT + ",\"ver\":1}";
        }

        @JavascriptInterface
        public void beat() {
            lastBeat = System.currentTimeMillis();
        }

        @JavascriptInterface
        public void notifyMsg(String title, String body, String tag, String group) {
            Notifier.message(getApplicationContext(), title, body, tag, group);
        }

        @JavascriptInterface
        public void setSession(String url, String key, String token, String uid) {
            SharedPreferences sp = Prefs.sp(MainActivity.this);
            SharedPreferences.Editor e = sp.edit();
            e.putString("url", url).putString("key", key).putString("token", token).putString("uid", uid);
            if (!sp.contains("since")) e.putString("since", "");
            e.apply();
            if (sp.getBoolean("bg", false)) CoreService.start(MainActivity.this);
        }

        @JavascriptInterface
        public void markSeen(String iso) {
            if (iso != null && iso.length() > 0) Prefs.sp(MainActivity.this).edit().putString("since", iso).apply();
        }

        @JavascriptInterface
        public void setBackground(boolean on) {
            Prefs.sp(MainActivity.this).edit().putBoolean("bg", on).apply();
            if (on) CoreService.start(MainActivity.this);
            else if (!Prefs.sp(MainActivity.this).getBoolean("mesh", false)) CoreService.stop(MainActivity.this);
        }

        @JavascriptInterface
        public void requestPermissions(final String what) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    List<String> l = new ArrayList<>();
                    if ("notifications".equals(what) || "all".equals(what)) {
                        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS);
                    }
                    if ("mesh".equals(what) || "all".equals(what)) {
                        if (Build.VERSION.SDK_INT >= 31) {
                            l.add(Manifest.permission.BLUETOOTH_SCAN);
                            l.add(Manifest.permission.BLUETOOTH_ADVERTISE);
                            l.add(Manifest.permission.BLUETOOTH_CONNECT);
                            if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.NEARBY_WIFI_DEVICES);
                            else l.add(Manifest.permission.ACCESS_FINE_LOCATION);
                        } else {
                            l.add(Manifest.permission.ACCESS_FINE_LOCATION);
                        }
                    }
                    List<String> need = new ArrayList<>();
                    for (String p : l) if (!granted(p)) need.add(p);
                    if (need.isEmpty()) {
                        web.evaluateJavascript("window.__perm&&window.__perm(" + JSONObject.quote(permStatus()) + ")", null);
                    } else {
                        ActivityCompat.requestPermissions(MainActivity.this, need.toArray(new String[0]), RQ_BRIDGE);
                    }
                }
            });
        }

        @JavascriptInterface
        public String permStatus() {
            boolean notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS);
            return "{\"notifications\":" + notif + ",\"mesh\":" + CoreService.meshPermsOk(MainActivity.this) + "}";
        }

        @JavascriptInterface
        public void meshStart(String myHash) {
            Prefs.sp(MainActivity.this).edit().putBoolean("mesh", true).putString("myHash", myHash == null ? "" : myHash).apply();
            CoreService.start(MainActivity.this);
        }

        @JavascriptInterface
        public void meshStop() {
            Prefs.sp(MainActivity.this).edit().putBoolean("mesh", false).apply();
            if (Prefs.sp(MainActivity.this).getBoolean("bg", false)) CoreService.start(MainActivity.this);
            else CoreService.stop(MainActivity.this);
        }

        @JavascriptInterface
        public void meshPut(String envJson) {
            try {
                JSONObject env = new JSONObject(envJson);
                MeshStore.get(MainActivity.this).addCarry(env);
                CoreService s = CoreService.inst;
                if (s != null) s.meshPush(env);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public String meshPull() {
            return MeshStore.get(MainActivity.this).inboxJson();
        }

        @JavascriptInterface
        public void meshAck(String id) {
            MeshStore.get(MainActivity.this).removeInbox(id);
        }

        @JavascriptInterface
        public String meshStatus() {
            CoreService s = CoreService.inst;
            try {
                JSONObject o = new JSONObject(s == null ? "{}" : s.meshStatus());
                o.put("enabled", Prefs.sp(MainActivity.this).getBoolean("mesh", false));
                o.put("perms", CoreService.meshPermsOk(MainActivity.this));
                o.put("carry", MeshStore.get(MainActivity.this).carryCount());
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String b64) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                String safe = name == null || name.length() == 0 ? "jeron-file" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.length() == 0 ? "application/octet-stream" : mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Jeron");
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (u == null) throw new Exception("insert");
                    OutputStream o = getContentResolver().openOutputStream(u);
                    o.write(bytes);
                    o.close();
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, safe);
                    java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                    o.write(bytes);
                    o.close();
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "تم الحفظ بمجلد التنزيلات/Jeron", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "تعذر الحفظ", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    }
}
