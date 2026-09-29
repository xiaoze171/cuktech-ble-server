package com.cuktech.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import static org.junit.Assert.*;

public class KeepAlivePreferencesTest {
    @Test public void defaultsToEnabled() {
        assertTrue(KeepAlivePreferences.isEnabled((android.content.SharedPreferences) null));
    }

    @Test public void readsAndWritesPersistentValue() {
        FakePreferences preferences = new FakePreferences();
        KeepAlivePreferences.setEnabled(preferences, false);
        assertFalse(KeepAlivePreferences.isEnabled(preferences));
        KeepAlivePreferences.setEnabled(preferences, true);
        assertTrue(KeepAlivePreferences.isEnabled(preferences));
    }

    private static final class FakePreferences implements SharedPreferences {
        private boolean value = true;
        @Override public boolean getBoolean(String key, boolean fallback) { return value; }
        @Override public Editor edit() { return new Editor() {
            @Override public Editor putBoolean(String key, boolean next) { value = next; return this; }
            @Override public boolean commit() { return true; }
            @Override public void apply() { }
            @Override public Editor clear() { return this; }
            @Override public Editor remove(String key) { return this; }
            @Override public Editor putString(String key, String value) { return this; }
            @Override public Editor putStringSet(String key, java.util.Set<String> value) { return this; }
            @Override public Editor putInt(String key, int value) { return this; }
            @Override public Editor putLong(String key, long value) { return this; }
            @Override public Editor putFloat(String key, float value) { return this; }
        }; }
        @Override public java.util.Map<String, ?> getAll() { return java.util.Collections.emptyMap(); }
        @Override public String getString(String key, String fallback) { return fallback; }
        @Override public java.util.Set<String> getStringSet(String key, java.util.Set<String> fallback) { return fallback; }
        @Override public int getInt(String key, int fallback) { return fallback; }
        @Override public long getLong(String key, long fallback) { return fallback; }
        @Override public float getFloat(String key, float fallback) { return fallback; }
        @Override public boolean contains(String key) { return false; }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    }
}
