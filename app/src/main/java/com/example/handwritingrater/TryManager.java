package com.example.handwritingrater;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Daily free-try counter backed by SharedPreferences. No backend. */
public final class TryManager {
    private static final String PREFS = "tries";
    private static final String KEY_DATE = "date";
    private static final String KEY_LEFT = "left";
    public static final int DAILY_FREE_TRIES = 6;

    private final SharedPreferences prefs;

    public TryManager(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String today = today();
        if (!today.equals(prefs.getString(KEY_DATE, ""))) {
            prefs.edit().putString(KEY_DATE, today).putInt(KEY_LEFT, DAILY_FREE_TRIES).apply();
        }
    }

    public int left() {
        return prefs.getInt(KEY_LEFT, DAILY_FREE_TRIES);
    }

    public boolean use() {
        int l = left();
        if (l <= 0) return false;
        prefs.edit().putInt(KEY_LEFT, l - 1).apply();
        return true;
    }

    public void grant(int n) {
        prefs.edit().putInt(KEY_LEFT, left() + n).apply();
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}