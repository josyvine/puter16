package com.puter.unofficial.ads;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.puter.unofficial.ActionReportLogger;
import com.puter.unofficial.AppConstants;
import com.puter.unofficial.logging.LiveLogManager;

/**
 * Dedicated AdMob Banner Manager for Puter Unofficial.
 * 
 * Manages SDK initialization, programmatic AdView binding to the footer,
 * adaptive visibility handling, and automatic AndroidX lifecycle observing.
 */
public class AdMobBannerManager {

    private static volatile boolean isSdkInitialized = false;

    private final Activity activity;
    private final ViewGroup adContainer;
    private AdView adView;

    public AdMobBannerManager(Activity activity, ViewGroup adContainer) {
        this.activity = activity;
        this.adContainer = adContainer;
    }

    /**
     * Initializes the Mobile Ads SDK and mounts the footer banner ad.
     */
    public void loadBanner() {
        if (activity == null || adContainer == null) return;

        // Container starts hidden so it consumes zero pixels until an ad actually loads
        adContainer.setVisibility(View.GONE);

        if (!isSdkInitialized) {
            MobileAds.initialize(activity, initializationStatus -> {
                isSdkInitialized = true;
                LiveLogManager.getInstance().ok("ADMOB", "SDK Initialized", "Google Mobile Ads ready.");
                activity.runOnUiThread(this::setupAndRequestAd);
            });
        } else {
            setupAndRequestAd();
        }

        bindLifecycle();
    }

    private void setupAndRequestAd() {
        try {
            if (adView != null) {
                adView.destroy();
                adContainer.removeAllViews();
            }

            adView = new AdView(activity);
            adView.setAdUnitId(AppConstants.ADMOB_BANNER_AD_UNIT_ID);
            adView.setAdSize(AdSize.BANNER);

            adContainer.removeAllViews();
            adContainer.addView(adView);

            adView.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    super.onAdLoaded();
                    // Show footer container only when ad is fully ready
                    adContainer.setVisibility(View.VISIBLE);
                    LiveLogManager.getInstance().ok("ADMOB", "Banner Loaded", "AdMob banner rendering in footer.");
                    ActionReportLogger.logAction("ADMOB_BANNER_LOADED", "Footer banner active.");
                }

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    super.onAdFailedToLoad(loadAdError);
                    // Collapse container so zero space is wasted if ad fails
                    adContainer.setVisibility(View.GONE);
                    LiveLogManager.getInstance().warn("ADMOB", "Banner Failed", "Code: " + loadAdError.getCode() + " | " + loadAdError.getMessage());
                    ActionReportLogger.logError("ADMOB_BANNER_FAIL", loadAdError.getMessage());
                }
            });

            AdRequest adRequest = new AdRequest.Builder().build();
            adView.loadAd(adRequest);

        } catch (Exception e) {
            LiveLogManager.getInstance().error("ADMOB", "Banner Setup Error", e.getMessage());
            adContainer.setVisibility(View.GONE);
        }
    }

    /**
     * Automatically hooks into the Activity's lifecycle to pause, resume,
     * and destroy the AdView without cluttering MainActivity.
     */
    private void bindLifecycle() {
        if (activity instanceof AppCompatActivity) {
            ((AppCompatActivity) activity).getLifecycle().addObserver(new DefaultLifecycleObserver() {
                @Override
                public void onResume(@NonNull LifecycleOwner owner) {
                    if (adView != null) adView.resume();
                }

                @Override
                public void onPause(@NonNull LifecycleOwner owner) {
                    if (adView != null) adView.pause();
                }

                @Override
                public void onDestroy(@NonNull LifecycleOwner owner) {
                    if (adView != null) {
                        adView.destroy();
                        adView = null;
                    }
                    if (adContainer != null) {
                        adContainer.removeAllViews();
                    }
                }
            });
        }
    }
}