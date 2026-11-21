package com.example.ads

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AdManager"

/**
 * Centralized AdMob manager handling Banner, Interstitial, Rewarded, and App Open ads.
 *
 * Requirements enforced:
 * - Single background-thread initialization to prevent startup ANRs.
 * - Frequency capping for Interstitial ads (~4-5 meaningful navigation actions).
 * - NEVER shows an ad or banner over active video playback.
 * - App Open ads integrated and guarded by configuration.
 * - Graceful fallback: Normal navigation always resumes if WebView/AdMob fails to load.
 */
class AdManager(private val context: Context) {

  private val appContext = context.applicationContext
  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mainHandler = Handler(Looper.getMainLooper())

  private var interstitialAd: InterstitialAd? = null
  private var rewardedAd: RewardedAd? = null

  private var isInterstitialLoading = false
  private var isRewardedLoading = false

  // Frequency capping & request-hygiene counters
  private var navigationActionCount = 0
  private var lastInterstitialTimeMs = 0L
  private var lastFullscreenAdTimeMs = 0L
  private var interstitialLoadedAtMs = 0L
  private var rewardedLoadedAtMs = 0L
  private var lastInterstitialFailMs = 0L
  private var lastRewardedFailMs = 0L
  private var interstitialRetryAttempt = 0
  private var rewardedRetryAttempt = 0

  // Active video player safety guard: NEVER display an ad over video playback
  var isPlayerActive: Boolean = false
  private var isFullscreenAdShowing: Boolean = false

  // User reward: Watching rewarded ad unlocks timed Ad-Free VIP session (10 min)
  private var adFreeUntilMs: Long = 0L
  private val _isAdFreeSessionActive = MutableStateFlow(false)
  val isAdFreeSessionActive: StateFlow<Boolean> = _isAdFreeSessionActive.asStateFlow()

  private val _isRewardedReadyFlow = MutableStateFlow(false)
  val isRewardedReadyFlow: StateFlow<Boolean> = _isRewardedReadyFlow.asStateFlow()

  // App Open Ad Manager
  val appOpenAdManager: AppOpenAdManager = AppOpenAdManager(
    context = appContext,
    isVideoPlayerActive = { isPlayerActive || isFullscreenAdShowing },
    isAdFreeActive = {
      refreshAdFreeStatus()
      _isAdFreeSessionActive.value
    },
    onAdShownOrDismissed = { shownAtMs ->
      lastFullscreenAdTimeMs = maxOf(lastFullscreenAdTimeMs, shownAtMs)
    }
  )

  private val adFreeExpiryRunnable = Runnable {
    refreshAdFreeStatus()
  }

  fun refreshAdFreeStatus(nowMs: Long = System.currentTimeMillis()): Boolean {
    val active = adFreeUntilMs > nowMs
    if (_isAdFreeSessionActive.value != active) {
      _isAdFreeSessionActive.value = active
      if (!active) {
        adFreeUntilMs = 0L
        preloadInterstitial()
      }
    }
    return active
  }

  private fun isAdStillFresh(loadedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
    return loadedAtMs > 0L && (nowMs - loadedAtMs) < AD_FRESHNESS_MAX_MS
  }

  fun isFullScreenCooldownElapsed(nowMs: Long = System.currentTimeMillis()): Boolean {
    val latestShown = maxOf(lastInterstitialTimeMs, lastFullscreenAdTimeMs)
    return latestShown == 0L || (nowMs - latestShown) >= AdMobConfig.MIN_INTERSTITIAL_COOLDOWN_MS
  }

  companion object {
    private const val AD_FRESHNESS_MAX_MS = 50 * 60_000L // Discard unused ads before 60-min expiry
    private const val FAILED_LOAD_COOLDOWN_MS = 45_000L // Prevent no-fill retry spam that hurts Match Rate
    private const val MAX_AUTO_RETRIES = 1

    private val isMobileAdsInitialized = AtomicBoolean(false)
    private val isMobileAdsSdkReady = AtomicBoolean(false)
    private val _isSdkReadyFlow = MutableStateFlow(false)
    val isSdkReadyFlow: StateFlow<Boolean> = _isSdkReadyFlow.asStateFlow()

    fun isSdkReady(): Boolean = isMobileAdsSdkReady.get()

    fun isRunningOnEmulator(): Boolean {
      return Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
        Build.MODEL.contains("google_sdk") ||
        Build.MODEL.contains("Emulator") ||
        Build.MODEL.contains("Android SDK built for x86") ||
        Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
        Build.MODEL.contains("cuttlefish", ignoreCase = true) ||
        Build.MANUFACTURER.contains("Genymotion") ||
        (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
        Build.PRODUCT == "google_sdk" ||
        Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) ||
        Build.PRODUCT.contains("emulator", ignoreCase = true) ||
        Build.PRODUCT.contains("aosp_cf", ignoreCase = true) ||
        Build.HARDWARE.contains("ranchu") ||
        Build.HARDWARE.contains("goldfish") ||
        Build.HARDWARE.contains("cutf", ignoreCase = true) ||
        Build.DEVICE.contains("vsoc", ignoreCase = true) ||
        Build.DEVICE.contains("emu64", ignoreCase = true)
    }
  }

  fun initialize(onInitialized: () -> Unit = {}) {
    if (isMobileAdsInitialized.compareAndSet(false, true)) {
      ioScope.launch {
        try {
          MobileAds.initialize(appContext) {
            isMobileAdsSdkReady.set(true)
            _isSdkReadyFlow.value = true
            Log.i(TAG, "Google Mobile Ads SDK initialized (production ads only).")
            mainHandler.post {
              preloadInterstitial()
              // Only preload App Open Ad on normal launches (never on deep links where Pre-Play Ad runs)
              if (AdMobConfig.isAppOpenConfigEnabled(appContext) && !appOpenAdManager.isColdLaunchSuppressed()) {
                appOpenAdManager.loadAd()
              }
              // Preload Rewarded slightly later on normal launches so Interstitial gets priority without request bursts
              mainHandler.postDelayed({ preloadRewarded() }, 2_500L)
              onInitialized()
            }
          }
        } catch (t: Throwable) {
          isMobileAdsInitialized.set(false)
          Log.e(TAG, "AdMob initialization failed", t)
          mainHandler.post { onInitialized() }
        }
      }
    } else if (isMobileAdsSdkReady.get()) {
      mainHandler.post {
        preloadInterstitial()
        if (AdMobConfig.isAppOpenConfigEnabled(appContext) && !appOpenAdManager.isColdLaunchSuppressed()) {
          appOpenAdManager.loadAd()
        }
        onInitialized()
      }
    } else {
      onInitialized()
    }
  }

  fun preloadInterstitial(forceBypassCooldown: Boolean = false) {
    refreshAdFreeStatus()
    val nowMs = System.currentTimeMillis()
    if (interstitialAd != null && !isAdStillFresh(interstitialLoadedAtMs, nowMs)) {
      interstitialAd = null
      interstitialLoadedAtMs = 0L
    }
    if (interstitialAd != null || isInterstitialLoading || _isAdFreeSessionActive.value) return
    if (!forceBypassCooldown && lastInterstitialFailMs > 0L && (nowMs - lastInterstitialFailMs) < FAILED_LOAD_COOLDOWN_MS) {
      return
    }
    val adUnitId = AdMobConfig.getInterstitialAdUnitId(appContext)
    if (!AdMobConfig.isValidProductionAdUnitId(adUnitId)) {
      Log.d(TAG, "Interstitial ad skipped: no production Interstitial Ad Unit ID configured.")
      return
    }
    isInterstitialLoading = true

    try {
      val adRequest = AdRequest.Builder().build()

      InterstitialAd.load(
        appContext,
        adUnitId,
        adRequest,
        object : InterstitialAdLoadCallback() {
          override fun onAdLoaded(ad: InterstitialAd) {
            interstitialAd = ad
            interstitialLoadedAtMs = System.currentTimeMillis()
            isInterstitialLoading = false
            interstitialRetryAttempt = 0
            lastInterstitialFailMs = 0L
            Log.d(TAG, "Interstitial ad loaded successfully")
          }

          override fun onAdFailedToLoad(loadAdError: LoadAdError) {
            interstitialAd = null
            interstitialLoadedAtMs = 0L
            isInterstitialLoading = false
            lastInterstitialFailMs = System.currentTimeMillis()
            Log.w(TAG, "Interstitial failed to load: ${loadAdError.message}")
            if (interstitialRetryAttempt < MAX_AUTO_RETRIES) {
              interstitialRetryAttempt++
              mainHandler.postDelayed({ preloadInterstitial(forceBypassCooldown = true) }, 30_000L)
            }
          }
        }
      )
    } catch (t: Throwable) {
      isInterstitialLoading = false
      Log.w(TAG, "Interstitial load skipped due to error: ${t.message}")
    }
  }

  /**
   * Shows interstitial ad during suitable navigation transitions with frequency capping.
   *
   * Criteria:
   * - Never interrupts active video playback.
   * - Shows every 2 navigation actions once the 25s cooldown has elapsed.
   * - Resumes normal navigation immediately if ad is unavailable.
   */
  fun showInterstitialAtTransition(
    activity: Activity,
    forceShow: Boolean = false,
    onAdFinished: () -> Unit
  ) {
    refreshAdFreeStatus()
    // 1. Guard: Never show over active video playback or active ad-free session
    if (isPlayerActive || _isAdFreeSessionActive.value || isFullscreenAdShowing || activity.isFinishing || activity.isDestroyed) {
      onAdFinished()
      return
    }

    // 2. Frequency capping check: Count navigation actions & check unified cooldown
    navigationActionCount++
    val nowMs = System.currentTimeMillis()
    val meetsActionThreshold = navigationActionCount >= AdMobConfig.INTERSTITIAL_ACTION_THRESHOLD
    val meetsCooldown = isFullScreenCooldownElapsed(nowMs)

    val shouldShow = forceShow || (meetsActionThreshold && meetsCooldown)

    if (!shouldShow) {
      val remainingMs = (AdMobConfig.MIN_INTERSTITIAL_COOLDOWN_MS - (nowMs - maxOf(lastInterstitialTimeMs, lastFullscreenAdTimeMs))).coerceAtLeast(0L)
      Log.d(TAG, "Interstitial skipped by frequency cap (actions: $navigationActionCount, cooldown remaining: ${remainingMs}ms)")
      preloadInterstitial()
      onAdFinished()
      return
    }

    val ad = interstitialAd?.takeIf { isAdStillFresh(interstitialLoadedAtMs, nowMs) }
    if (ad != null) {
      interstitialAd = null
      interstitialLoadedAtMs = 0L
      navigationActionCount = 0
      lastInterstitialTimeMs = nowMs
      lastFullscreenAdTimeMs = nowMs
      showLoadedInterstitial(activity, ad, onAdFinished)
    } else {
      interstitialAd = null
      // Ad unavailable: Resume normal navigation immediately and queue reload if cooldown passed
      preloadInterstitial()
      onAdFinished()
    }
  }

  /**
   * Shows an interstitial ad when the viewer exits the video player back to Home,
   * provided the fullscreen ad cooldown has elapsed since the pre-play ad.
   */
  fun showPostPlayerAd(
    activity: Activity,
    onAdFinished: () -> Unit
  ) {
    isPlayerActive = false
    refreshAdFreeStatus()
    if (_isAdFreeSessionActive.value || isFullscreenAdShowing || activity.isFinishing || activity.isDestroyed) {
      onAdFinished()
      return
    }

    val nowMs = System.currentTimeMillis()
    if (!isFullScreenCooldownElapsed(nowMs)) {
      preloadInterstitial()
      onAdFinished()
      return
    }

    val ad = interstitialAd?.takeIf { isAdStillFresh(interstitialLoadedAtMs, nowMs) }
    if (ad != null) {
      interstitialAd = null
      interstitialLoadedAtMs = 0L
      navigationActionCount = 0
      lastInterstitialTimeMs = nowMs
      lastFullscreenAdTimeMs = nowMs
      showLoadedInterstitial(activity, ad, onAdFinished)
    } else {
      interstitialAd = null
      preloadInterstitial()
      onAdFinished()
    }
  }

  private fun showLoadedInterstitial(
    activity: Activity,
    ad: InterstitialAd,
    onAdFinished: () -> Unit
  ) {
    val completed = AtomicBoolean(false)
    fun finishOnce() {
      isFullscreenAdShowing = false
      lastFullscreenAdTimeMs = maxOf(lastFullscreenAdTimeMs, System.currentTimeMillis())
      if (completed.compareAndSet(false, true)) {
        onAdFinished()
      }
    }

    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
      override fun onAdShowedFullScreenContent() {
        isFullscreenAdShowing = true
        lastFullscreenAdTimeMs = System.currentTimeMillis()
      }

      override fun onAdDismissedFullScreenContent() {
        interstitialAd = null
        lastInterstitialTimeMs = System.currentTimeMillis()
        preloadInterstitial()
        finishOnce()
      }

      override fun onAdFailedToShowFullScreenContent(adError: AdError) {
        Log.w(TAG, "Interstitial failed to show: ${adError.message}")
        interstitialAd = null
        preloadInterstitial()
        finishOnce()
      }
    }
    try {
      isFullscreenAdShowing = true
      ad.show(activity)
    } catch (t: Throwable) {
      Log.w(TAG, "Interstitial show failed: ${t.message}")
      interstitialAd = null
      finishOnce()
    }
  }

  private fun showLoadedRewardedForPrePlay(
    activity: Activity,
    ad: RewardedAd,
    onAdFinished: () -> Unit
  ) {
    val completed = AtomicBoolean(false)
    fun finishOnce() {
      isFullscreenAdShowing = false
      lastFullscreenAdTimeMs = maxOf(lastFullscreenAdTimeMs, System.currentTimeMillis())
      if (completed.compareAndSet(false, true)) {
        onAdFinished()
      }
    }

    rewardedAd = null
    _isRewardedReadyFlow.value = false
    lastFullscreenAdTimeMs = System.currentTimeMillis()
    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
      override fun onAdShowedFullScreenContent() {
        isFullscreenAdShowing = true
        lastFullscreenAdTimeMs = System.currentTimeMillis()
      }

      override fun onAdDismissedFullScreenContent() {
        preloadRewarded()
        finishOnce()
      }

      override fun onAdFailedToShowFullScreenContent(adError: AdError) {
        Log.w(TAG, "Pre-play rewarded ad failed to show: ${adError.message}")
        preloadRewarded()
        finishOnce()
      }
    }
    try {
      isFullscreenAdShowing = true
      ad.show(activity) {
        Log.d(TAG, "Pre-play rewarded ad completed")
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Pre-play rewarded show failed: ${t.message}")
      preloadRewarded()
      finishOnce()
    }
  }

  /**
   * Shows a pre-roll ad (Rewarded first, then Interstitial fallback) every time the user
   * triggers Watch & Play or opens a deep-link video, unless an Ad-Free VIP window is active.
   * Waits briefly for a pending ad load before gracefully proceeding to playback.
   */
  fun showPrePlayAd(
    activity: Activity,
    maxWaitMs: Long = 4500L,
    onAdFinished: () -> Unit
  ) {
    refreshAdFreeStatus()
    if (isPlayerActive || _isAdFreeSessionActive.value || isFullscreenAdShowing || activity.isFinishing || activity.isDestroyed) {
      onAdFinished()
      return
    }

    fun tryShowReadyAd(): Boolean {
      if (isPlayerActive || _isAdFreeSessionActive.value || activity.isFinishing || activity.isDestroyed) {
        onAdFinished()
        return true
      }
      val nowMs = System.currentTimeMillis()
      val readyRewarded = rewardedAd?.takeIf { isAdStillFresh(rewardedLoadedAtMs, nowMs) }
      if (readyRewarded != null) {
        showLoadedRewardedForPrePlay(activity, readyRewarded, onAdFinished)
        return true
      }
      val readyInterstitial = interstitialAd?.takeIf { isAdStillFresh(interstitialLoadedAtMs, nowMs) }
      if (readyInterstitial != null) {
        interstitialAd = null
        interstitialLoadedAtMs = 0L
        lastInterstitialTimeMs = nowMs
        lastFullscreenAdTimeMs = nowMs
        showLoadedInterstitial(activity, readyInterstitial, onAdFinished)
        return true
      }
      return false
    }

    if (tryShowReadyAd()) return

    // Ensure SDK and single fast Interstitial load are active (avoid firing duplicate Rewarded+Interstitial simultaneously)
    initialize()
    preloadInterstitial(forceBypassCooldown = true)

    val startTimeMs = System.currentTimeMillis()
    val pollRunnable = object : Runnable {
      override fun run() {
        if (tryShowReadyAd()) return
        val elapsed = System.currentTimeMillis() - startTimeMs
        if (elapsed >= maxWaitMs) {
          Log.d(TAG, "Pre-play ad wait timed out after ${elapsed}ms; proceeding to video playback.")
          onAdFinished()
        } else {
          mainHandler.postDelayed(this, 120L)
        }
      }
    }
    mainHandler.postDelayed(pollRunnable, 120L)
  }

  fun preloadRewarded(useSecondaryFallback: Boolean = false, forceBypassCooldown: Boolean = false) {
    refreshAdFreeStatus()
    val nowMs = System.currentTimeMillis()
    if (rewardedAd != null && !isAdStillFresh(rewardedLoadedAtMs, nowMs)) {
      rewardedAd = null
      rewardedLoadedAtMs = 0L
      _isRewardedReadyFlow.value = false
    }
    if (rewardedAd != null || (isRewardedLoading && !useSecondaryFallback)) return
    if (!forceBypassCooldown && !useSecondaryFallback && lastRewardedFailMs > 0L && (nowMs - lastRewardedFailMs) < FAILED_LOAD_COOLDOWN_MS) {
      return
    }

    val adUnitId = if (useSecondaryFallback) {
      AdMobConfig.getSecondaryRewardedAdUnitId(appContext)
    } else {
      AdMobConfig.getRewardedAdUnitId(appContext)
    }
    if (!AdMobConfig.isValidProductionAdUnitId(adUnitId)) {
      isRewardedLoading = false
      Log.d(TAG, "Rewarded ad skipped: invalid or test ad unit ID.")
      return
    }
    isRewardedLoading = true

    try {
      val adRequest = AdRequest.Builder().build()

      RewardedAd.load(
        appContext,
        adUnitId!!,
        adRequest,
        object : RewardedAdLoadCallback() {
          override fun onAdLoaded(ad: RewardedAd) {
            rewardedAd = ad
            rewardedLoadedAtMs = System.currentTimeMillis()
            isRewardedLoading = false
            rewardedRetryAttempt = 0
            lastRewardedFailMs = 0L
            _isRewardedReadyFlow.value = true
            Log.i(TAG, "Rewarded ad loaded successfully ($adUnitId)")
          }

          override fun onAdFailedToLoad(loadAdError: LoadAdError) {
            rewardedAd = null
            rewardedLoadedAtMs = 0L
            _isRewardedReadyFlow.value = false
            Log.w(TAG, "Rewarded ad failed to load ($adUnitId) [code=${loadAdError.code}]: ${loadAdError.message}")
            // Only try secondary fallback once if primary returned no-fill (code 3), never on format/config errors
            if (!useSecondaryFallback && loadAdError.code == 3 && AdMobConfig.getSecondaryRewardedAdUnitId(appContext) != null) {
              preloadRewarded(useSecondaryFallback = true, forceBypassCooldown = true)
            } else {
              isRewardedLoading = false
              lastRewardedFailMs = System.currentTimeMillis()
              if (rewardedRetryAttempt < MAX_AUTO_RETRIES) {
                rewardedRetryAttempt++
                mainHandler.postDelayed({ preloadRewarded(forceBypassCooldown = true) }, 45_000L)
              }
            }
          }
        }
      )
    } catch (t: Throwable) {
      isRewardedLoading = false
      _isRewardedReadyFlow.value = false
      Log.w(TAG, "Rewarded load skipped due to error: ${t.message}")
    }
  }

  /**
   * Shows optional rewarded ad when user explicitly chooses to watch for timed VIP Ad-Free viewing.
   */
  fun showRewardedAd(
    activity: Activity,
    onRewardEarned: (rewardAmount: Int, rewardType: String) -> Unit,
    onAdClosed: () -> Unit,
    onNotReady: (() -> Unit)? = null
  ) {
    val ad = rewardedAd
    if (ad != null) {
      rewardedAd = null
      _isRewardedReadyFlow.value = false
      ad.fullScreenContentCallback = object : FullScreenContentCallback() {
        override fun onAdShowedFullScreenContent() {
          isFullscreenAdShowing = true
          lastFullscreenAdTimeMs = System.currentTimeMillis()
        }

        override fun onAdDismissedFullScreenContent() {
          isFullscreenAdShowing = false
          lastFullscreenAdTimeMs = System.currentTimeMillis()
          preloadRewarded()
          onAdClosed()
        }

        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
          isFullscreenAdShowing = false
          Log.w(TAG, "Rewarded ad failed to show: ${adError.message}")
          preloadRewarded()
          onAdClosed()
        }
      }

      try {
        isFullscreenAdShowing = true
        ad.show(activity) { rewardItem ->
          adFreeUntilMs = System.currentTimeMillis() + AdMobConfig.AD_FREE_REWARD_DURATION_MS
          _isAdFreeSessionActive.value = true
          mainHandler.removeCallbacks(adFreeExpiryRunnable)
          mainHandler.postDelayed(adFreeExpiryRunnable, AdMobConfig.AD_FREE_REWARD_DURATION_MS + 250L)
          onRewardEarned(rewardItem.amount, rewardItem.type)
        }
      } catch (t: Throwable) {
        isFullscreenAdShowing = false
        Log.w(TAG, "Rewarded ad show failed: ${t.message}")
        onAdClosed()
      }
    } else {
      preloadRewarded()
      onNotReady?.invoke() ?: onAdClosed()
    }
  }

  val isRewardedAdReady: Boolean
    get() = rewardedAd != null
}
