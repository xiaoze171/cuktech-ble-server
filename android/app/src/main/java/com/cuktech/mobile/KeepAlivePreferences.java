package com.cuktech.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent policy for restoring the foreground charger service. */
public final class KeepAlivePreferences {
    private static final String PREFS = "charger_preferences";
    private static final String KEY_ENABLED = "keep_alive_enabled";

    private KeepAlivePreferences() { }

    public static SharedPreferences get(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return context == null || isEnabled(get(context));
    }

    public static boolean isEnabled(SharedPreferences preferences) {
        return preferences == null || preferences.getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        setEnabled(get(context), enabled);
    }

    public static void setEnabled(SharedPreferences preferences, boolean enabled) {
        if (preferences != null) preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }
}
