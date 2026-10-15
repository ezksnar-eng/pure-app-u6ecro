package com.jeron.app;

import android.content.Context;

/** نسخة بدون نموذج محلي (تُستخدم إذا withLlm=false): تخلي البناء آمن 100%. */
interface LlmCallback {
    void onToken(String token);

    void onDone(String error);
}

interface DlCallback {
    void onProgress(int pct);

    void onFinish(String error);
}

final class LlmEngine {
    private LlmEngine() {}

    static String status(Context c) {
        return "{\"supported\":false,\"ready\":false,\"downloading\":false,\"pct\":0,\"loading\":false,\"size\":0}";
    }

    static void download(Context c, String url, DlCallback cb) {
        cb.onFinish("غير مدعوم بهذي النسخة");
    }

    static void cancelDownload() {}

    static void delete(Context c) {}

    static void generate(Context c, String system, String historyJson, String prompt, LlmCallback cb) {
        cb.onDone("unsupported");
    }
}
