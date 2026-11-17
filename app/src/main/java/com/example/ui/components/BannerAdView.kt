package com.example.ui.components

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ads.AdManager
import com.example.ads.AdMobConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

@Composable
fun BannerAdView(
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val bannerAdUnitId = remember(context) { AdMobConfig.getBannerAdUnitId(context) }
  if (!AdMobConfig.isValidProductionAdUnitId(bannerAdUnitId)) return

  val isSdkReady by AdManager.isSdkReadyFlow.collectAsState()
  var adFailedToLoad by remember { mutableStateOf(false) }

  if (adFailedToLoad) return

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(54.dp)
      .testTag("banner_ad_container"),
    contentAlignment = Alignment.Center
  ) {
    if (isSdkReady) {
      AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
          try {
            AdView(ctx).apply {
              layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
              )
              setAdSize(AdSize.BANNER)
              adUnitId = bannerAdUnitId
              adListener = object : AdListener() {
                override fun onAdLoaded() {
                  adFailedToLoad = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                  adFailedToLoad = true
                }
              }
              loadAd(AdRequest.Builder().build())
            }
          } catch (_: Throwable) {
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
