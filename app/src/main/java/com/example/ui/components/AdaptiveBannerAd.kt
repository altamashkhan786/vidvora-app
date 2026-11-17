package com.example.ui.components

import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ads.AdManager
import com.example.ads.AdMobConfig
import com.example.ui.theme.MasalaSuccess
import com.example.ui.theme.MasalaSurface
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import kotlinx.coroutines.delay

private const val BANNER_TAG = "AdaptiveBannerAd"
private const val MAX_BANNER_FAST_RETRIES = 4

@Composable
fun AdaptiveBannerAd(
  isAdFree: Boolean,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val bannerAdUnitId = remember(context) { AdMobConfig.getBannerAdUnitId(context) }
  val isProductionUnit = remember(bannerAdUnitId) {
    AdMobConfig.isValidProductionAdUnitId(bannerAdUnitId)
  }
  val isSdkReady by AdManager.isSdkReadyFlow.collectAsState()
  var isAdLoaded by remember { mutableStateOf(false) }
  var adFailedToLoad by remember { mutableStateOf(false) }
  var retryAttempt by remember { mutableIntStateOf(0) }

  LaunchedEffect(adFailedToLoad, isSdkReady, retryAttempt) {
    if (isSdkReady && adFailedToLoad) {
      val delayMs = if (retryAttempt < MAX_BANNER_FAST_RETRIES) 6_000L else 35_000L
      delay(delayMs)
      isAdLoaded = false
      adFailedToLoad = false
      retryAttempt++
    }
  }

  // If user earned Ad-Free VIP window, display the VIP badge rather than commercial ads
  if (isAdFree) {
    Box(
      modifier = modifier
        .fillMaxWidth()
        .height(48.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(MasalaSurface)
        .border(1.dp, MasalaSuccess.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
        .padding(horizontal = 14.dp),
      contentAlignment = Alignment.Center
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.VerifiedUser,
          contentDescription = null,
          tint = MasalaSuccess,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "10-Min Ad-Free VIP Active • VidVora",
          color = MasalaSuccess,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }
    return
  }

  if (!isProductionUnit) {
    return
  }

  BoxWithConstraints(
    modifier = modifier.fillMaxWidth()
  ) {
    val availableWidthDp = maxWidth.value.toInt().coerceAtLeast(320)
    val adaptiveAdSize = remember(availableWidthDp) {
      try {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, availableWidthDp)
      } catch (_: Throwable) {
        AdSize.BANNER
      }
    }

    val adHeightDp = adaptiveAdSize.getHeightInPixels(context).let { px ->
      if (px > 0) (px / context.resources.displayMetrics.density).dp else 54.dp
    }

    // Reserve full unobstructed height while loading or loaded so AdMob never rejects for insufficient space
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(adHeightDp)
        .testTag("adaptive_banner_container"),
      contentAlignment = Alignment.Center
    ) {
      if (isSdkReady && !adFailedToLoad) {
        key(retryAttempt, availableWidthDp) {
          AndroidView(
            modifier = Modifier
              .fillMaxWidth()
              .height(adHeightDp),
            factory = { ctx ->
              try {
                AdView(ctx).apply {
                  layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                  )
                  setAdSize(adaptiveAdSize)
                  adUnitId = bannerAdUnitId
                  adListener = object : AdListener() {
                    override fun onAdLoaded() {
                      isAdLoaded = true
                      adFailedToLoad = false
                      Log.i(BANNER_TAG, "Banner ad loaded successfully ($bannerAdUnitId)")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                      isAdLoaded = false
                      adFailedToLoad = true
                      Log.w(BANNER_TAG, "Banner failed to load [code=${error.code}]: ${error.message}")
                    }
                  }
                  loadAd(AdRequest.Builder().build())
                }
              } catch (t: Throwable) {
                Log.w(BANNER_TAG, "Banner factory failed: ${t.message}")
                adFailedToLoad = true
                View(ctx)
              }
            },
            onRelease = { view ->
              try {
                (view as? AdView)?.destroy()
              } catch (_: Throwable) {}
            }
          )
        }
      }
    }
  }
}
