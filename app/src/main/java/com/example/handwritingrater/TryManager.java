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
    private static final String KEY_PREMIUM = "premium";
    public static final int DAILY_FREE_TRIES = 6;
    public static final int SIGN_IN_BONUS_TRIES = 6;

    private final SharedPreferences prefs;
    private boolean premium;

    public TryManager(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        premium = prefs.getBoolean(KEY_PREMIUM, false);
        String today = today();
        if (!today.equals(prefs.getString(KEY_DATE, ""))) {
            prefs.edit().putString(KEY_DATE, today).putInt(KEY_LEFT, max()).apply();
        }
    }

    /** Daily allowance for the current sign-in state: free users get base, signed-in users get more. */
    public int max() {
        return DAILY_FREE_TRIES + (premium ? SIGN_IN_BONUS_TRIES : 0);
    }

    public boolean isPremium() {
        return premium;
    }

    /** Enables/disables the signed-in bonus. On upgrade mid-day, tops up tries to the new allowance. */
    public void setPremium(boolean p) {
        boolean was = premium;
        premium = p;
        prefs.edit().putBoolean(KEY_PREMIUM, p).apply();
        if (p && !was) {
            int extra = max() - left();
            if (extra > 0) prefs.edit().putInt(KEY_LEFT, left() + extra).apply();
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
