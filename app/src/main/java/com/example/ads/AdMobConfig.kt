package com.example.ads

import android.content.Context
import com.example.R

/**
 * Centralized Google AdMob configuration.
 *
 * Official Google TEST IDs:
 * - App ID: ca-app-pub-3940256099942544~3347511713
 * - Banner: ca-app-pub-3940256099942544/6300978111
 * - Interstitial: ca-app-pub-3940256099942544/1033173712
 * - App Open: ca-app-pub-3940256099942544/9257395921
 */
object AdMobConfig {

  const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
  const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
  const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"
  const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
  const val TEST_APP_OPEN_AD_UNIT_ID = "ca-app-pub-3940256099942544/9257395921"
  const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

  // Interstitial frequency capping: Show after 2 navigation actions with at least 25s cooldown
  const val INTERSTITIAL_ACTION_THRESHOLD = 2
  const val MIN_INTERSTITIAL_COOLDOWN_MS = 25_000L

  // App Open Ad cooldown between foreground resumes (30 seconds)
  const val MIN_APP_OPEN_COOLDOWN_MS = 30_000L

  // Optional Rewarded Ad VIP duration (10 minutes ad-free before ads resume)
  const val AD_FREE_REWARD_DURATION_MS = 10 * 60_000L

  /**
   * Runtime switch for App Open Ads.
   * Enabled as requested.
   * Can be toggled at runtime or configured via resources.
   */
  var isAppOpenAdEnabled: Boolean = true

  fun isTestAdUnitId(adUnitId: String?): Boolean {
    val clean = adUnitId?.trim().orEmpty()
    return clean.contains("3940256099942544")
  }

  fun isValidProductionAdUnitId(adUnitId: String?): Boolean {
    val clean = adUnitId?.trim().orEmpty()
    return clean.startsWith("ca-app-pub-") && clean.contains("/") && !isTestAdUnitId(clean)
  }

  fun getAppId(context: Context): String {
    return try {
      context.getString(R.string.admob_app_id).trim()
    } catch (_: Exception) {
      ""
    }
  }

  fun getBannerAdUnitId(context: Context): String {
    return try {
      context.getString(R.string.admob_banner_ad_unit_id).trim()
    } catch (_: Exception) {
      ""
    }
  }

  fun getNativeAdUnitId(context: Context): String {
    return try {
      context.getString(R.string.admob_native_ad_unit_id).trim()
    } catch (_: Exception) {
      ""
    }
  }

  fun getInterstitialAdUnitId(context: Context): String {
    return try {
      context.getString(R.string.admob_interstitial_ad_unit_id).trim()
    } catch (_: Exception) {
      ""
    }
  }

  fun getAppOpenAdUnitId(context: Context): String {
    return try {
      context.getString(R.string.admob_app_open_ad_unit_id).trim()
    } catch (_: Exception) {
      ""
    }
  }

  fun getRewardedAdUnitId(context: Context): String {
    return try {
      context.getString(R.string.admob_rewarded_ad_unit_id).trim()
    } catch (_: Exception) {
      ""
    }
  }

  fun getSecondaryRewardedAdUnitId(context: Context): String? {
    return try {
      context.getString(R.string.admob_rewarded_secondary_ad_unit_id).trim().takeIf { it.isNotBlank() }
    } catch (_: Exception) {
      null
    }
  }

  fun isAppOpenConfigEnabled(context: Context): Boolean {
    return try {
      (isAppOpenAdEnabled || context.resources.getBoolean(R.bool.app_open_ads_enabled)) &&
        isValidProductionAdUnitId(getAppOpenAdUnitId(context))
    } catch (_: Exception) {
      false
    }
  }

  fun isUsingTestIds(context: Context): Boolean {
    return listOf(
      getBannerAdUnitId(context),
      getNativeAdUnitId(context),
      getInterstitialAdUnitId(context),
      getAppOpenAdUnitId(context),
      getRewardedAdUnitId(context)
    ).any { isTestAdUnitId(it) }
  }
}
