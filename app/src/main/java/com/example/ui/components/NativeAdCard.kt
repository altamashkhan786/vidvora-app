package com.example.ui.components

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.ads.AdManager
import com.example.ads.AdMobConfig
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaSurface
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import kotlinx.coroutines.delay

private const val NATIVE_TAG = "NativeAdCard"

@Composable
fun NativeAdCard(
  isAdFree: Boolean,
  modifier: Modifier = Modifier,
  slotTag: String = "native_ad_card"
) {
  if (isAdFree) return

  val context = LocalContext.current
  val isSdkReady by AdManager.isSdkReadyFlow.collectAsState()
  var nativeAd by remember(slotTag) { mutableStateOf<NativeAd?>(null) }
  var hasFailed by remember(slotTag) { mutableStateOf(false) }
  var retryAttempt by remember(slotTag) { mutableIntStateOf(0) }

  LaunchedEffect(hasFailed, isSdkReady, retryAttempt, slotTag) {
    if (isSdkReady && hasFailed && nativeAd == null && retryAttempt < 4) {
      val delayMs = if (retryAttempt < 2) 10_000L else 25_000L
      delay(delayMs)
      hasFailed = false
      retryAttempt++
    }
  }

  DisposableEffect(context, isSdkReady, retryAttempt, slotTag) {
    if (!isSdkReady) {
      return@DisposableEffect onDispose {}
    }
    val adUnitId = AdMobConfig.getNativeAdUnitId(context)
    if (!AdMobConfig.isValidProductionAdUnitId(adUnitId)) {
      return@DisposableEffect onDispose {}
    }
    var currentLoadedAd: NativeAd? = null
    try {
      val adLoader = AdLoader.Builder(context, adUnitId)
        .forNativeAd { ad ->
          currentLoadedAd?.destroy()
          currentLoadedAd = ad
          nativeAd = ad
          hasFailed = false
          Log.i(NATIVE_TAG, "Native ad ($slotTag) loaded successfully")
        }
        .withAdListener(object : AdListener() {
          override fun onAdFailedToLoad(error: LoadAdError) {
            hasFailed = true
            Log.w(NATIVE_TAG, "Native ad ($slotTag) failed to load [code=${error.code}]: ${error.message}")
          }
        })
        .withNativeAdOptions(
          NativeAdOptions.Builder()
            .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
            .build()
        )
        .build()

      adLoader.loadAd(AdRequest.Builder().build())
    } catch (t: Throwable) {
      Log.w(NATIVE_TAG, "Native ad ($slotTag) load skipped: ${t.message}")
    }

    onDispose {
      try {
        currentLoadedAd?.destroy()
      } catch (_: Throwable) {}
      nativeAd = null
    }
  }

  AnimatedVisibility(
    visible = nativeAd != null,
    enter = fadeIn()
  ) {
    val loadedAd = nativeAd ?: return@AnimatedVisibility
    Card(
      modifier = modifier
        .fillMaxWidth()
        .testTag(slotTag),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MasalaSurface),
      border = BorderStroke(1.dp, MasalaCardBorder)
    ) {
      AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
          LayoutInflater.from(ctx).inflate(R.layout.native_ad_layout, null, false) as NativeAdView
        },
        update = { adView ->
          try {
            populateNativeAdView(loadedAd, adView)
          } catch (_: Throwable) {}
        }
      )
    }
  }
}

private fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
  val headlineView = adView.findViewById<TextView>(R.id.ad_headline)
  val advertiserView = adView.findViewById<TextView>(R.id.ad_advertiser)
  val bodyView = adView.findViewById<TextView>(R.id.ad_body)
  val ctaView = adView.findViewById<Button>(R.id.ad_call_to_action)
  val iconView = adView.findViewById<ImageView>(R.id.ad_app_icon)
  val mediaView = adView.findViewById<MediaView>(R.id.ad_media)

  adView.headlineView = headlineView
  adView.advertiserView = advertiserView
  adView.bodyView = bodyView
  adView.callToActionView = ctaView
  adView.iconView = iconView
  adView.mediaView = mediaView

  headlineView.text = nativeAd.headline ?: ""

  if (nativeAd.advertiser.isNullOrBlank()) {
    advertiserView.visibility = View.GONE
  } else {
    advertiserView.visibility = View.VISIBLE
    advertiserView.text = nativeAd.advertiser
  }

  if (nativeAd.body.isNullOrBlank()) {
    bodyView.visibility = View.GONE
  } else {
    bodyView.visibility = View.VISIBLE
    bodyView.text = nativeAd.body
  }

  if (nativeAd.callToAction.isNullOrBlank()) {
    ctaView.visibility = View.GONE
  } else {
    ctaView.visibility = View.VISIBLE
    ctaView.text = nativeAd.callToAction
  }

  val icon = nativeAd.icon
  if (icon == null) {
    iconView.visibility = View.GONE
  } else {
    iconView.visibility = View.VISIBLE
    iconView.setImageDrawable(icon.drawable)
  }

  mediaView.mediaContent = nativeAd.mediaContent
  adView.setNativeAd(nativeAd)
}
