package com.example.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import java.util.Date

private const val TAG = "AppOpenAdManager"

/**
 * Manages Google Mobile Ads App Open Ads.
 *
 * Requirements:
 * - Kept DISABLED initially (controlled by AdMobConfig.isAppOpenConfigEnabled).
 * - Configuration switch available to enable later.
 * - NEVER shows an ad when active video playback is occurring.
 */
class AppOpenAdManager(
  private val context: Context,
  private val isVideoPlayerActive: () -> Boolean = { false },
  private val isAdFreeActive: () -> Boolean = { false },
  private val onAdShownOrDismissed: (shownTimeMs: Long) -> Unit = {}
) : DefaultLifecycleObserver {

  private var appOpenAd: AppOpenAd? = null
  private var isLoadingAd = false
  private var isShowingAd = false
  private var loadTime: Long = 0L
  private var lastAdShownMs: Long = 0L

  private var currentActivity: Activity? = null
  private var isForeground = false
  private var isAppInBackground = false
  private var attachedAtMs: Long = 0L
  private var hasAttemptedColdLaunchAd = false
  private var suppressColdLaunchAd = false

  fun attachActivity(activity: Activity) {
    this.currentActivity = activity
    if (attachedAtMs == 0L) {
      attachedAtMs = System.currentTimeMillis()
    }
    if (activity is LifecycleOwner) {
      activity.lifecycle.removeObserver(this)
      activity.lifecycle.addObserver(this)
    }
  }

  fun detachActivity(activity: Activity) {
    if (this.currentActivity == activity) {
      if (activity is LifecycleOwner) {
        activity.lifecycle.removeObserver(this)
      }
      this.currentActivity = null
    }
  }

  /**
   * Call when an incoming deep link will immediately open video playback + pre-roll ad
   * so cold-launch App Open Ad does not stack on top of the pre-play ad.
   */
  fun suppressNextColdLaunchAd() {
    suppressColdLaunchAd = true
    hasAttemptedColdLaunchAd = true
  }

  override fun onStart(owner: LifecycleOwner) {
    super.onStart(owner)
    isForeground = true
    val activity = currentActivity
    if (activity != null) {
      if (isAppInBackground) {
        Log.d(TAG, "App returned to foreground. Attempting to display App Open Ad if eligible.")
        isAppInBackground = false
        showAdIfAvailable(activity)
      } else if (!hasAttemptedColdLaunchAd && !suppressColdLaunchAd && isAdAvailable()) {
        hasAttemptedColdLaunchAd = true
        showAdIfAvailable(activity)
      }
    }
  }

  override fun onStop(owner: LifecycleOwner) {
    super.onStop(owner)
    isForeground = false
    isAppInBackground = true
    Log.d(TAG, "App moved to background. Preloading App Open Ad so it is ready on return to foreground.")
    if (!isAdAvailable()) {
      loadAd()
    }
  }

  /**
   * Request an ad. Preloads App Open ad only if the feature is enabled.
   * Shows automatically on cold launch if loaded within the initial launch window.
   */
  fun loadAd(onLoaded: (() -> Unit)? = null) {
    if (!AdMobConfig.isAppOpenConfigEnabled(context)) {
      Log.d(TAG, "App Open Ads are currently DISABLED by configuration.")
      return
    }

    if (isLoadingAd || isAdAvailable()) {
      return
    }

    if (!AdManager.isSdkReady()) {
      return
    }

    val adUnitId = AdMobConfig.getAppOpenAdUnitId(context)
    if (!AdMobConfig.isValidProductionAdUnitId(adUnitId)) {
      Log.d(TAG, "App Open Ad skipped: no production Ad Unit ID configured.")
      return
    }

    isLoadingAd = true
    try {
      val request = AdRequest.Builder().build()

      AppOpenAd.load(
        context,
        adUnitId,
        request,
        object : AppOpenAd.AppOpenAdLoadCallback() {
          override fun onAdLoaded(ad: AppOpenAd) {
            appOpenAd = ad
            isLoadingAd = false
            loadTime = Date().time
            Log.d(TAG, "App Open Ad loaded successfully.")
            onLoaded?.invoke()

            val activity = currentActivity
            val withinLaunchWindow = attachedAtMs > 0L &&
              (System.currentTimeMillis() - attachedAtMs) <= 12_000L
            if (activity != null &&
              isForeground &&
              withinLaunchWindow &&
              !hasAttemptedColdLaunchAd &&
              !suppressColdLaunchAd &&
              !isVideoPlayerActive() &&
              !isAdFreeActive()
            ) {
              hasAttemptedColdLaunchAd = true
              showAdIfAvailable(activity)
            }
          }

          override fun onAdFailedToLoad(loadAdError: LoadAdError) {
            isLoadingAd = false
            appOpenAd = null
            Log.w(TAG, "App Open Ad failed to load: ${loadAdError.message}")
          }
        }
      )
    } catch (t: Throwable) {
      isLoadingAd = false
      appOpenAd = null
      Log.w(TAG, "App Open Ad load skipped due to error: ${t.message}")
    }
  }

  private fun wasLoadTimeLessThan4HoursAgo(): Boolean {
    val dateDifference: Long = Date().time - loadTime
    val numMilliSecondsPerHour: Long = 3600000
    return dateDifference < numMilliSecondsPerHour * 4
  }

  fun isAdAvailable(): Boolean {
    return appOpenAd != null && wasLoadTimeLessThan4HoursAgo()
  }

  fun isCooldownElapsed(nowMs: Long = System.currentTimeMillis()): Boolean {
    return lastAdShownMs == 0L || (nowMs - lastAdShownMs) >= AdMobConfig.MIN_APP_OPEN_COOLDOWN_MS
  }

  /**
   * Shows App Open Ad if available, enabled, cooldown elapsed, and no video is playing.
   */
  fun showAdIfAvailable(activity: Activity, onAdDismissed: (() -> Unit)? = null) {
    // 1. Guard: Check if switch is enabled
    if (!AdMobConfig.isAppOpenConfigEnabled(context)) {
      onAdDismissed?.invoke()
      return
    }

    // 2. Guard: NEVER show ad over active video playback or during VIP ad-free session
    if (isVideoPlayerActive() || isAdFreeActive()) {
      Log.d(TAG, "Suppressing App Open Ad: Video playback or VIP session is active.")
      onAdDismissed?.invoke()
      return
    }

    // 3. Guard: Activity validity & cooldown
    if (activity.isFinishing || activity.isDestroyed || !isCooldownElapsed()) {
      onAdDismissed?.invoke()
      return
    }

    // 4. Guard: Already showing
    if (isShowingAd) {
      Log.d(TAG, "App Open Ad is already showing.")
      return
    }

    // 5. Guard: Check availability
    if (!isAdAvailable()) {
      Log.d(TAG, "App Open Ad is not ready yet. Preloading for next launch.")
      loadAd()
      onAdDismissed?.invoke()
      return
    }

    val ad = appOpenAd ?: return
    hasAttemptedColdLaunchAd = true
    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
      override fun onAdDismissedFullScreenContent() {
        appOpenAd = null
        isShowingAd = false
        lastAdShownMs = System.currentTimeMillis()
        onAdShownOrDismissed(lastAdShownMs)
        Log.d(TAG, "App Open Ad dismissed.")
        loadAd()
        onAdDismissed?.invoke()
      }

      override fun onAdFailedToShowFullScreenContent(adError: AdError) {
        appOpenAd = null
        isShowingAd = false
        Log.w(TAG, "App Open Ad failed to show: ${adError.message}")
        loadAd()
        onAdDismissed?.invoke()
      }

      override fun onAdShowedFullScreenContent() {
        isShowingAd = true
        lastAdShownMs = System.currentTimeMillis()
        onAdShownOrDismissed(lastAdShownMs)
        Log.d(TAG, "App Open Ad displayed.")
      }
    }

    isShowingAd = true
    try {
      ad.show(activity)
    } catch (t: Throwable) {
      appOpenAd = null
      isShowingAd = false
      Log.w(TAG, "App Open Ad show failed: ${t.message}")
      onAdDismissed?.invoke()
    }
  }
}
