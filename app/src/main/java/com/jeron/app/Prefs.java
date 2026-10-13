package com.jeron.app;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("jeron_cfg", Context.MODE_PRIVATE);
    }
}
