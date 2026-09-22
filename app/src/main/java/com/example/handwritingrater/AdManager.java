package com.example.handwritingrater;

import android.app.Activity;
import androidx.annotation.NonNull;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

/** AdMob RewardedAd wrapper. Grants one extra try on completion. */
public final class AdManager {
    private static final String UNIT_ID = BuildConfig.REWARDED_AD_UNIT_ID;

    private RewardedAd ad;

    public interface RewardCallback {
        void onEarned();
        void onFailed();
    }

    public void init(Activity activity) {
        MobileAds.initialize(activity, status -> {});
        load(activity);
    }

    public void load(Activity activity) {
        RewardedAd.load(activity, UNIT_ID, new AdRequest.Builder().build(),
                new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull RewardedAd a) { ad = a; }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError e) { ad = null; }
                });
    }

    public boolean isReady() {
        return ad != null;
    }

    public void show(Activity activity, RewardCallback cb) {
        if (ad == null) {
            load(activity);
            cb.onFailed();
            return;
        }
        ad.show(activity, (OnUserEarnedRewardListener) reward -> cb.onEarned());
        ad = null;
        load(activity);
    }
}
