package com.example.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.runtime.rememberCoroutineScope
import com.example.util.VideoShareHelper
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.ads.AdManager
import com.example.data.model.VideoToken
import com.example.data.repository.FirestoreVideoRepository
import com.example.player.VideoPlaybackState
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaRedAccent
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaSurfaceVariant
import com.example.ui.theme.MasalaTextMuted
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class GestureOverlayType {
  NONE,
  VOLUME,
  BRIGHTNESS,
  ZOOM
}

private data class VideoQualityOption(
  val label: String,
  val subLabel: String,
  val maxWidth: Int? = null,
  val maxHeight: Int? = null,
  val trackGroup: Tracks.Group? = null,
  val trackIndex: Int? = null
)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
  video: VideoToken,
  adManager: AdManager? = null,
  allowExitAd: Boolean = true,
  shareLinkBaseUrl: String = "",
  onNavigateBack: () -> Unit
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val activity = context as? Activity
  val configuration = LocalConfiguration.current
  val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

  // Strictly guard ad state: NEVER show ads over active video playback
  DisposableEffect(adManager) {
    adManager?.isPlayerActive = true
    onDispose {
      adManager?.isPlayerActive = false
    }
  }

  val audioManager = remember {
    context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
  }
  val maxStreamVolume = remember(audioManager) {
    (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
  }

  var isPlaying by remember { mutableStateOf(false) }
  var isBuffering by remember { mutableStateOf(true) }
  var isPlaybackEnded by remember { mutableStateOf(false) }
  var currentPosition by remember { mutableLongStateOf(0L) }
  var duration by remember { mutableLongStateOf(0L) }
  var volume by remember { mutableFloatStateOf(1f) }
  var isMuted by remember { mutableStateOf(false) }
  var isControlsVisible by remember { mutableStateOf(true) }
  var isScrubbing by remember { mutableStateOf(false) }
  var scrubPosition by remember { mutableFloatStateOf(0f) }
  var playbackError by remember { mutableStateOf<String?>(null) }
  var useSoftwareDecoderFallback by remember(video.streamUrl) {
    mutableStateOf(false)
  }
  var retryTrigger by remember(video.streamUrl) {
    mutableIntStateOf(0)
  }
  var resumePositionMs by remember(video.streamUrl) {
    mutableLongStateOf(0L)
  }

  // Video Quality states
  var selectedQualityLabel by remember { mutableStateOf("Auto") }
  var currentStreamResolution by remember { mutableStateOf("") }
  var detectedStreamQualities by remember { mutableStateOf<List<VideoQualityOption>>(emptyList()) }
  var showQualityDialog by remember { mutableStateOf(false) }

  // Gesture HUD & 2-finger Zoom states (Left swipe = Brightness, Right swipe = Volume, 2-finger pinch = Zoom)
  var activeGestureType by remember { mutableStateOf(GestureOverlayType.NONE) }
  var gesturePercentage by remember { mutableIntStateOf(0) }
  var isGestureDragging by remember { mutableStateOf(false) }
  var videoScale by remember(video.streamUrl) { mutableFloatStateOf(1f) }
  var videoOffset by remember(video.streamUrl) { mutableStateOf(Offset.Zero) }

  // Double-tap seek HUD state (Left double-tap = -5s, Right double-tap = +5s)
  var seekHudTrigger by remember { mutableIntStateOf(0) }
  var seekHudSeconds by remember { mutableIntStateOf(0) }
  var seekHudIsRight by remember { mutableStateOf(true) }

  // Auto-hide double-tap seek HUD
  LaunchedEffect(seekHudTrigger) {
    if (seekHudTrigger > 0) {
      delay(750)
      seekHudSeconds = 0
    }
  }

  // Clamp zoom pan offset whenever orientation or container dimensions change
  LaunchedEffect(isLandscape) {
    if (videoScale <= 1.02f) {
      videoScale = 1f
      videoOffset = Offset.Zero
    } else {
      videoOffset = Offset.Zero
    }
  }

  // Auto-hide gesture HUD after drag/pinch finishes
  LaunchedEffect(isGestureDragging, activeGestureType, gesturePercentage, videoScale) {
    if (!isGestureDragging && activeGestureType != GestureOverlayType.NONE) {
      delay(900)
      activeGestureType = GestureOverlayType.NONE
    }
  }

  // Auto-hide controls timer (stays visible while quality dialog is open or video ended)
  LaunchedEffect(isControlsVisible, isPlaying, showQualityDialog, isPlaybackEnded) {
    if (isControlsVisible && isPlaying && !isScrubbing && !showQualityDialog && !isPlaybackEnded) {
      delay(3800)
      isControlsVisible = false
    }
  }

  // Build shareable URL for friends
  val shareableVideoUrl = remember(video, shareLinkBaseUrl) {
    val rawIdentifier = video.token.trim().ifBlank { video.id.trim() }
    if (rawIdentifier.isNotBlank()) {
      FirestoreVideoRepository.buildShareableVideoUrl(rawIdentifier, shareLinkBaseUrl)
    } else {
      video.streamUrl.trim()
    }
  }

  val resolvedPlaybackUri = remember(video.streamUrl) {
    val raw = video.streamUrl.trim()
    when {
      raw.startsWith("/data/") || raw.startsWith("/storage/") -> "file://$raw"
      else -> raw
    }
  }

  // Validate local file:// existence before preparing ExoPlayer
  LaunchedEffect(resolvedPlaybackUri) {
    if (resolvedPlaybackUri.startsWith("file://", ignoreCase = true)) {
      val path = android.net.Uri.parse(resolvedPlaybackUri).path
      if (path.isNullOrBlank() || !java.io.File(path).exists()) {
        isBuffering = false
        playbackError = "Video file not found on this device. Please re-upload or use a public Video Stream URL (https://...)."
      }
    }
  }

  // Track selector for runtime video quality switching (allow exceeding device portrait bounds)
  val trackSelector = remember(resolvedPlaybackUri, useSoftwareDecoderFallback, retryTrigger) {
    DefaultTrackSelector(context).apply {
      setParameters(
        buildUponParameters()
          .setExceedRendererCapabilitiesIfNecessary(true)
          .setExceedVideoConstraintsIfNecessary(true)
          .setExceedAudioConstraintsIfNecessary(true)
          .setAllowVideoMixedMimeTypeAdaptiveness(true)
          .setAllowVideoNonSeamlessAdaptiveness(true)
      )
    }
  }

  // Initialize Media3 ExoPlayer with decoder fallback + automatic software codec retry
  val exoPlayer = remember(resolvedPlaybackUri, useSoftwareDecoderFallback, retryTrigger) {
    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
      .setAllowCrossProtocolRedirects(true)
      .setConnectTimeoutMs(15_000)
      .setReadTimeoutMs(25_000)
      .setUserAgent("VidVoraPlayer/1.0 (Android)")
    val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    val renderersFactory = DefaultRenderersFactory(context)
      .setEnableDecoderFallback(true)
      .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
      .apply {
        if (useSoftwareDecoderFallback) {
          setMediaCodecSelector(
            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
              val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
              )
              if (mimeType.startsWith("video/", ignoreCase = true)) {
                val softwareDecoders = all.filter { info ->
                  info.softwareOnly ||
                    !info.hardwareAccelerated ||
                    info.name.startsWith("c2.android.", ignoreCase = true) ||
                    info.name.startsWith("OMX.google.", ignoreCase = true)
                }
                softwareDecoders.ifEmpty { all }
              } else {
                all
              }
            }
          )
        }
      }

    val loadControl = DefaultLoadControl.Builder()
      .setBufferDurationsMs(
        /* minBufferMs = */ 8_000,
        /* maxBufferMs = */ 30_000,
        /* bufferForPlaybackMs = */ 150,
        /* bufferForPlaybackAfterRebufferMs = */ 600
      )
      .setPrioritizeTimeOverSizeThresholds(true)
      .build()

    ExoPlayer.Builder(context, renderersFactory)
      .setLoadControl(loadControl)
      .setTrackSelector(trackSelector)
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
      .build()
      .apply {
        if (resolvedPlaybackUri.isNotBlank()) {
          val mediaItem = MediaItem.fromUri(android.net.Uri.parse(resolvedPlaybackUri))
          setMediaItem(mediaItem)
          if (resumePositionMs > 0L) {
            seekTo(resumePositionMs)
          }
          prepare()
          playWhenReady = true
        }
      }
  }

  // Build available quality choices (dynamic adaptive tracks when available + standard presets)
  val qualityOptions = remember(detectedStreamQualities) {
    val autoOption = VideoQualityOption(
      label = "Auto",
      subLabel = "Recommended • Adjusts to internet speed"
    )
    if (detectedStreamQualities.size > 1) {
      listOf(autoOption) + detectedStreamQualities
    } else {
      listOf(
        autoOption,
        VideoQualityOption("1080p", "Full HD • High Quality", maxWidth = 1920, maxHeight = 1080),
        VideoQualityOption("720p", "HD • Balanced Quality", maxWidth = 1280, maxHeight = 720),
        VideoQualityOption("480p", "SD • Standard Quality", maxWidth = 854, maxHeight = 480),
        VideoQualityOption("360p", "Data Saver", maxWidth = 640, maxHeight = 360),
        VideoQualityOption("240p", "Low Data Usage", maxWidth = 426, maxHeight = 240)
      )
    }
  }

  fun applyVideoQuality(option: VideoQualityOption) {
    selectedQualityLabel = option.label
    val builder = trackSelector.buildUponParameters()
      .clearOverridesOfType(C.TRACK_TYPE_VIDEO)

    when {
      option.trackGroup != null && option.trackIndex != null -> {
        builder.clearVideoSizeConstraints()
          .addOverride(
            TrackSelectionOverride(option.trackGroup.mediaTrackGroup, option.trackIndex)
          )
      }
      option.maxWidth != null && option.maxHeight != null -> {
        builder.setMaxVideoSize(option.maxWidth, option.maxHeight)
      }
      else -> {
        builder.clearVideoSizeConstraints()
      }
    }

    trackSelector.parameters = builder.build()
    showQualityDialog = false
    val msg = if (option.label == "Auto") {
      "Video quality set to Auto"
    } else {
      "Video quality set to ${option.label}"
    }
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
  }

  fun exitPlayerWithOptionalAd() {
    exoPlayer.stop()
    exoPlayer.release()
    adManager?.isPlayerActive = false
    if (allowExitAd && adManager != null && activity != null) {
      adManager.showPostPlayerAd(activity) {
        onNavigateBack()
      }
    } else {
      onNavigateBack()
    }
  }

  fun replayVideo() {
    fun startReplayPlayback() {
      isPlaybackEnded = false
      playbackError = null
      currentPosition = 0L
      exoPlayer.seekTo(0L)
      exoPlayer.playWhenReady = true
      exoPlayer.play()
    }

    if (allowExitAd && adManager != null && activity != null && adManager.isFullScreenCooldownElapsed()) {
      adManager.isPlayerActive = false
      adManager.showInterstitialAtTransition(activity, forceShow = true) {
        adManager.isPlayerActive = true
        startReplayPlayback()
      }
    } else {
      startReplayPlayback()
    }
  }

  // Handle Back press: close quality dialog, exit fullscreen, or release player
  BackHandler {
    when {
      showQualityDialog -> showQualityDialog = false
      isLandscape -> activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      else -> exitPlayerWithOptionalAd()
    }
  }

  // Lifecycle observer and player listener
  DisposableEffect(exoPlayer) {
    val listener = object : Player.Listener {
      override fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
        if (playing) {
          isPlaybackEnded = false
        }
      }

      override fun onPlaybackStateChanged(state: Int) {
        when (state) {
          Player.STATE_BUFFERING -> {
            isBuffering = true
          }
          Player.STATE_READY -> {
            isBuffering = false
            isPlaybackEnded = false
            duration = exoPlayer.duration.coerceAtLeast(0L)
            playbackError = null
          }
          Player.STATE_ENDED -> {
            isBuffering = false
            isPlaying = false
            isPlaybackEnded = true
            isControlsVisible = true
            if (duration > 0L) {
              currentPosition = duration
            }
          }
          Player.STATE_IDLE -> {
            // Idle state
          }
        }
      }

      override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width > 0 && videoSize.height > 0) {
          currentStreamResolution = "${videoSize.height}p (${videoSize.width}×${videoSize.height})"
        }
      }

      override fun onTracksChanged(tracks: Tracks) {
        val found = mutableListOf<VideoQualityOption>()
        val seenHeights = mutableSetOf<Int>()
        for (group in tracks.groups) {
          if (group.type == C.TRACK_TYPE_VIDEO) {
            for (i in 0 until group.length) {
              if (!group.isTrackSupported(i)) continue
              val format = group.getTrackFormat(i)
              val h = format.height
              val w = format.width
              if (h > 0 && seenHeights.add(h)) {
                val tag = when {
                  h >= 2160 -> "4K"
                  h >= 1080 -> "Full HD"
                  h >= 720 -> "HD"
                  h >= 480 -> "SD"
                  else -> "Data Saver"
                }
                val bitrateMbps = if (format.bitrate > 0) {
                  " • ${(format.bitrate / 1000)} kbps"
                } else {
                  ""
                }
                found.add(
                  VideoQualityOption(
                    label = "${h}p",
                    subLabel = "$tag (${w}×${h})$bitrateMbps",
                    maxWidth = w.takeIf { it > 0 },
                    maxHeight = h,
                    trackGroup = group,
                    trackIndex = i
                  )
                )
              }
            }
          }
        }
        detectedStreamQualities = found.sortedByDescending { it.maxHeight ?: 0 }
      }

      override fun onPlayerError(error: PlaybackException) {
        val errorMsg = error.message.orEmpty()
        val causeMsg = error.cause?.message.orEmpty()
        val isDecoderOrCapabilityError =
          error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            errorMsg.contains("MediaCodecVideoRenderer", ignoreCase = true) ||
            errorMsg.contains("NO_EXCEEDS_CAPABILITIES", ignoreCase = true) ||
            causeMsg.contains("NO_EXCEEDS_CAPABILITIES", ignoreCase = true)

        if (isDecoderOrCapabilityError && !useSoftwareDecoderFallback) {
          resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
          playbackError = null
          isBuffering = true
          useSoftwareDecoderFallback = true
          return
        }

        isBuffering = false
        isPlaying = false
        playbackError = "Playback error: ${error.message ?: "Unable to stream video"}"
      }
    }

    exoPlayer.addListener(listener)
    isPlaying = exoPlayer.isPlaying
    isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING ||
      (exoPlayer.playbackState == Player.STATE_IDLE && resolvedPlaybackUri.isNotBlank() && playbackError == null)

    onDispose {
      exoPlayer.removeListener(listener)
      exoPlayer.stop()
      exoPlayer.release()
      // Restore system auto-brightness & orientation when exiting player
      activity?.window?.let { win ->
        val lp = win.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        win.attributes = lp
      }
      activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
  }

  // Coroutine to poll playback position
  LaunchedEffect(exoPlayer) {
    while (isActive) {
      if (exoPlayer.isPlaying && !isScrubbing) {
        currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
        duration = exoPlayer.duration.coerceAtLeast(0L)
      }
      delay(250)
    }
  }

  fun toggleOrientation() {
    if (activity != null) {
      if (isLandscape) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      }
    }
  }

  fun seekBy(deltaMs: Long) {
    val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, duration.coerceAtLeast(1L))
    isPlaybackEnded = false
    exoPlayer.seekTo(target)
    currentPosition = target
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
      .clipToBounds()
      .pointerInput(exoPlayer) {
        detectTapGestures(
          onTap = { isControlsVisible = !isControlsVisible },
          onDoubleTap = { offset ->
            val isRightSide = offset.x >= (size.width / 2f)
            val deltaSeconds = if (isRightSide) 5 else -5
            seekBy(deltaSeconds * 1000L)
            seekHudSeconds = if (seekHudSeconds != 0 && seekHudIsRight == isRightSide) {
              seekHudSeconds + deltaSeconds
            } else {
              deltaSeconds
            }
            seekHudIsRight = isRightSide
            seekHudTrigger++
          }
        )
      }
      .pointerInput(exoPlayer, audioManager, maxStreamVolume) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
          val firstDown = awaitFirstDown(requireUnconsumed = false)
          var isMultiTouch = false
          var isVerticalSwipeActive = false
          var isLeftSide = firstDown.position.x < (size.width / 2f)
          var startPos = firstDown.position
          var lastY = firstDown.position.y
          var currentBrightness = 0.5f
          var currentVolumeFraction = 0.5f

          while (true) {
            val event = awaitPointerEvent()
            val pressedChanges = event.changes.filter { it.pressed }
            if (pressedChanges.isEmpty()) {
              break
            }

            if (pressedChanges.size >= 2) {
              // 2-finger pinch to zoom in/out & pan
              isMultiTouch = true
              isVerticalSwipeActive = false
              isGestureDragging = true

              val zoomChange = event.calculateZoom()
              val panChange = event.calculatePan()

              val rawScale = videoScale * zoomChange
              val nextScale = if (rawScale <= 1.02f && zoomChange <= 1f) {
                1f
              } else {
                rawScale.coerceIn(1f, 4f)
              }

              videoScale = nextScale
              if (nextScale <= 1.01f) {
                videoOffset = Offset.Zero
              } else {
                val maxOffsetX = ((nextScale - 1f) * size.width) / 2f
                val maxOffsetY = ((nextScale - 1f) * size.height) / 2f
                val candidate = videoOffset + panChange
                videoOffset = Offset(
                  x = candidate.x.coerceIn(-maxOffsetX, maxOffsetX),
                  y = candidate.y.coerceIn(-maxOffsetY, maxOffsetY)
                )
              }

              activeGestureType = GestureOverlayType.ZOOM
              gesturePercentage = (videoScale * 100f).roundToInt().coerceIn(100, 400)
              event.changes.forEach { it.consume() }
            } else if (!isMultiTouch && pressedChanges.size == 1) {
              val primary = pressedChanges.first()
              val totalDx = primary.position.x - startPos.x
              val totalDy = primary.position.y - startPos.y

              if (!isVerticalSwipeActive && abs(totalDy) > touchSlop && abs(totalDy) > abs(totalDx)) {
                isVerticalSwipeActive = true
                isGestureDragging = true
                isLeftSide = startPos.x < (size.width / 2f)
                lastY = primary.position.y

                if (isLeftSide) {
                  val windowAttr = activity?.window?.attributes?.screenBrightness
                    ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                  currentBrightness = if (windowAttr in 0f..1f) {
                    windowAttr
                  } else {
                    val sysBrightness = try {
                      Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        128
                      )
                    } catch (_: Exception) {
                      128
                    }
                    (sysBrightness / 255f).coerceIn(0.05f, 1f)
                  }
                  activeGestureType = GestureOverlayType.BRIGHTNESS
                  gesturePercentage = (currentBrightness * 100f).roundToInt().coerceIn(1, 100)
                } else {
                  val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                  currentVolumeFraction = (currentVol.toFloat() / maxStreamVolume.toFloat()).coerceIn(0f, 1f)
                  activeGestureType = GestureOverlayType.VOLUME
                  gesturePercentage = (currentVolumeFraction * 100f).roundToInt().coerceIn(0, 100)
                }
              }

              if (isVerticalSwipeActive) {
                val dragAmount = primary.position.y - lastY
                lastY = primary.position.y
                val sensitivityPx = (size.height * 0.65f).coerceAtLeast(150f)
                val delta = -dragAmount / sensitivityPx
                if (isLeftSide) {
                  currentBrightness = (currentBrightness + delta).coerceIn(0.01f, 1f)
                  activity?.window?.let { win ->
                    val lp = win.attributes
                    lp.screenBrightness = currentBrightness
                    win.attributes = lp
                  }
                  activeGestureType = GestureOverlayType.BRIGHTNESS
                  gesturePercentage = (currentBrightness * 100f).roundToInt().coerceIn(1, 100)
                } else {
                  currentVolumeFraction = (currentVolumeFraction + delta).coerceIn(0f, 1f)
                  val targetIndex = (currentVolumeFraction * maxStreamVolume).roundToInt().coerceIn(0, maxStreamVolume)
                  audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
                  if (currentVolumeFraction > 0f && isMuted) {
                    isMuted = false
                    exoPlayer.volume = volume.coerceAtLeast(1f)
                  }
                  activeGestureType = GestureOverlayType.VOLUME
                  gesturePercentage = (currentVolumeFraction * 100f).roundToInt().coerceIn(0, 100)
                }
                primary.consume()
              }
            } else {
              // After multi-touch pinch drops to 1 finger, wait until all fingers lift before new single-finger gesture
              event.changes.forEach { it.consume() }
            }
          }

          if (isMultiTouch && videoScale < 1.04f) {
            videoScale = 1f
            videoOffset = Offset.Zero
          }
          isGestureDragging = false
        }
      }
      .testTag("player_screen_root"),
    contentAlignment = Alignment.Center
  ) {
    // Media3 PlayerView (TextureView) hosting the video surface with smooth pinch-to-zoom transform
    AndroidView(
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
          scaleX = videoScale
          scaleY = videoScale
          translationX = videoOffset.x
          translationY = videoOffset.y
        },
      factory = { ctx ->
        (LayoutInflater.from(ctx).inflate(R.layout.player_texture_view, null, false) as PlayerView).apply {
          player = exoPlayer
          useController = false
          layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
        }
      },
      update = { playerView ->
        if (playerView.player !== exoPlayer) {
          playerView.player = exoPlayer
        }
      }
    )

    // Buffering loading progress indicator
    if (isBuffering && playbackError == null) {
      Box(
        modifier = Modifier
          .size(68.dp)
          .clip(CircleShape)
          .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
      ) {
        CircularProgressIndicator(
          color = MasalaRedPrimary,
          strokeWidth = 3.5.dp,
          modifier = Modifier.size(44.dp)
        )
      }
    }

    // Double-Tap Seek Feedback Badge (Left = -5s, Right = +5s)
    AnimatedVisibility(
      visible = seekHudSeconds != 0,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier
        .align(if (seekHudIsRight) Alignment.CenterEnd else Alignment.CenterStart)
        .padding(horizontal = 36.dp)
    ) {
      Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.6f)),
        modifier = Modifier.testTag("player_double_tap_seek_hud")
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(
            imageVector = if (seekHudIsRight) Icons.Default.FastForward else Icons.Default.FastRewind,
            contentDescription = if (seekHudIsRight) "Forward 5 seconds" else "Rewind 5 seconds",
            tint = MasalaGold,
            modifier = Modifier.size(28.dp)
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = if (seekHudSeconds > 0) "+${seekHudSeconds}s" else "${seekHudSeconds}s",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }

    // Gesture Brightness / Volume HUD Overlay
    AnimatedVisibility(
      visible = activeGestureType != GestureOverlayType.NONE,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.align(Alignment.Center)
    ) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MasalaSurface.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, MasalaCardBorder),
        modifier = Modifier
          .width(210.dp)
          .testTag("player_gesture_hud")
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          val isBrightness = activeGestureType == GestureOverlayType.BRIGHTNESS
          val isZoom = activeGestureType == GestureOverlayType.ZOOM
          val hudIcon = when {
            isZoom -> Icons.Default.ZoomIn
            isBrightness && gesturePercentage >= 70 -> Icons.Default.BrightnessHigh
            isBrightness && gesturePercentage >= 30 -> Icons.Default.BrightnessMedium
            isBrightness -> Icons.Default.BrightnessLow
            gesturePercentage == 0 -> Icons.Default.VolumeOff
            gesturePercentage < 45 -> Icons.Default.VolumeDown
            else -> Icons.Default.VolumeUp
          }
          val hudAccent = if (isBrightness || isZoom) MasalaGold else MasalaRedAccent
          val hudText = when (activeGestureType) {
            GestureOverlayType.BRIGHTNESS -> "Brightness $gesturePercentage%"
            GestureOverlayType.VOLUME -> "Volume $gesturePercentage%"
            GestureOverlayType.ZOOM -> String.format(Locale.US, "Zoom %.1fx", videoScale)
            GestureOverlayType.NONE -> ""
          }
          val hudProgress = when (activeGestureType) {
            GestureOverlayType.ZOOM -> ((videoScale - 1f) / 3f).coerceIn(0f, 1f)
            else -> (gesturePercentage / 100f).coerceIn(0f, 1f)
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
          ) {
            Icon(
              imageVector = hudIcon,
              contentDescription = when {
                isZoom -> "Zoom"
                isBrightness -> "Brightness"
                else -> "Volume"
              },
              tint = hudAccent,
              modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = hudText,
              color = Color.White,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          LinearProgressIndicator(
            progress = { hudProgress },
            color = hudAccent,
            trackColor = Color.White.copy(alpha = 0.2f),
            modifier = Modifier
              .fillMaxWidth()
              .height(6.dp)
              .clip(RoundedCornerShape(3.dp))
          )
        }
      }
    }

    // Custom Video Controls Overlay (Fade In / Out)
    AnimatedVisibility(
      visible = isControlsVisible,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.fillMaxSize()
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              listOf(
                Color.Black.copy(alpha = 0.75f),
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = 0.85f)
              )
            )
          )
      ) {
        // TOP BAR: Back button, Video Title, Share, Mute, Fullscreen
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(
            onClick = {
              if (isLandscape) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
              } else {
                exitPlayerWithOptionalAd()
              }
            },
            modifier = Modifier.testTag("player_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
              tint = Color.White
            )
          }

          Spacer(modifier = Modifier.width(8.dp))

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = video.title,
              color = Color.White,
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1
            )
            val qualityBadge = if (selectedQualityLabel == "Auto" && currentStreamResolution.isNotBlank()) {
              "Auto • $currentStreamResolution"
            } else {
              selectedQualityLabel
            }
            Text(
              text = "$qualityBadge • VidVora",
              color = MasalaGold,
              fontSize = 11.sp,
              fontWeight = FontWeight.Medium
            )
          }

          // Video Quality selector button
          IconButton(
            onClick = { showQualityDialog = true },
            modifier = Modifier.testTag("player_quality_button")
          ) {
            Icon(
              imageVector = Icons.Default.HighQuality,
              contentDescription = "Video Quality",
              tint = Color.White
            )
          }

          // Share video link + thumbnail preview with friends
          IconButton(
            onClick = {
              if (shareableVideoUrl.isNotBlank()) {
                coroutineScope.launch {
                  VideoShareHelper.shareVideo(
                    context = context,
                    title = video.title,
                    videoId = video.id,
                    thumbnailPath = video.posterUrl,
                    storagePath = video.streamUrl,
                    shareUrl = shareableVideoUrl,
                    hasDynamicOgPreview = shareLinkBaseUrl.isNotBlank()
                  )
                }
              } else {
                Toast.makeText(context, "Share link unavailable for this video", Toast.LENGTH_SHORT).show()
              }
            },
            modifier = Modifier.testTag("player_share_button")
          ) {
            Icon(
              imageVector = Icons.Default.Share,
              contentDescription = "Share Video",
              tint = MasalaGold
            )
          }

          // Volume mute / quick toggle
          IconButton(
            onClick = {
              isMuted = !isMuted
              exoPlayer.volume = if (isMuted) 0f else volume
            },
            modifier = Modifier.testTag("player_mute_button")
          ) {
            Icon(
              imageVector = if (isMuted || volume == 0f) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
              contentDescription = "Volume",
              tint = Color.White
            )
          }

          // Fullscreen toggle
          IconButton(
            onClick = { toggleOrientation() },
            modifier = Modifier.testTag("player_fullscreen_button")
          ) {
            Icon(
              imageVector = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
              contentDescription = if (isLandscape) "Exit Fullscreen" else "Enter Fullscreen",
              tint = Color.White
            )
          }
        }

        // CENTER CONTROLS: 10s Rewind, Play/Pause/Replay, 10s Forward
        if (playbackError == null) {
          Column(
            modifier = Modifier
              .align(Alignment.Center)
              .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp)
          ) {
            // 10s Rewind
            IconButton(
              onClick = { seekBy(-10000L) },
              modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .testTag("player_rewind_10s")
            ) {
              Icon(
                imageVector = Icons.Default.Replay10,
                contentDescription = "Rewind 10 seconds",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
              )
            }

            // Play / Pause / Replay (shows active spinner while buffering so user sees loading state)
            IconButton(
              onClick = {
                when {
                  isPlaybackEnded || exoPlayer.playbackState == Player.STATE_ENDED -> replayVideo()
                  isPlaying -> exoPlayer.pause()
                  else -> {
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                  }
                }
              },
              modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(MasalaRedPrimary)
                .testTag("player_play_pause_button")
            ) {
              if (isBuffering && !isPlaying && !isPlaybackEnded && playbackError == null) {
                CircularProgressIndicator(
                  color = Color.White,
                  strokeWidth = 3.5.dp,
                  modifier = Modifier.size(34.dp)
                )
              } else {
                Icon(
                  imageVector = when {
                    isPlaybackEnded -> Icons.Default.Replay
                    isPlaying -> Icons.Default.Pause
                    else -> Icons.Default.PlayArrow
                  },
                  contentDescription = when {
                    isPlaybackEnded -> "Replay Video"
                    isPlaying -> "Pause"
                    else -> "Play"
                  },
                  tint = Color.White,
                  modifier = Modifier.size(38.dp)
                )
              }
            }

            // 10s Forward
            IconButton(
              onClick = { seekBy(10000L) },
              modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .testTag("player_forward_10s")
            ) {
              Icon(
                imageVector = Icons.Default.Forward10,
                contentDescription = "Forward 10 seconds",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
              )
            }
          }

          if (isPlaybackEnded) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
              shape = RoundedCornerShape(999.dp),
              color = Color.Black.copy(alpha = 0.72f),
              border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.6f)),
              modifier = Modifier
                .clickable { replayVideo() }
                .testTag("player_replay_pill")
            ) {
              Text(
                text = "Video Finished • Tap to Replay",
                color = MasalaGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
              )
            }
          }
          }
        }

        // BOTTOM CONTROLS: Timeline Slider, Time Elapsed / Total, Volume Slider
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
          // Time elapsed / total + quick Reset Zoom chip when zoomed in
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            val displayCurrent = if (isScrubbing) (scrubPosition * duration).toLong() else currentPosition
            Text(
              text = "${VideoPlaybackState.formatTime(displayCurrent)} / ${VideoPlaybackState.formatTime(duration)}",
              color = Color.White,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold
            )

            if (videoScale > 1.03f) {
              Surface(
                shape = RoundedCornerShape(999.dp),
                color = MasalaSurface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.55f)),
                modifier = Modifier
                  .clickable {
                    videoScale = 1f
                    videoOffset = Offset.Zero
                  }
                  .testTag("player_reset_zoom_chip")
              ) {
                Text(
                  text = String.format(Locale.US, "%.1fx • Reset", videoScale),
                  color = MasalaGold,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
              }
            }
          }

          // Scrubbing Timeline Slider
          val sliderValue = if (isScrubbing) {
            scrubPosition
          } else {
            if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
          }

          Slider(
            value = sliderValue,
            onValueChange = {
              isScrubbing = true
              scrubPosition = it
            },
            onValueChangeFinished = {
              isScrubbing = false
              isPlaybackEnded = false
              val targetMs = (scrubPosition * duration).toLong().coerceIn(0L, duration)
              exoPlayer.seekTo(targetMs)
              currentPosition = targetMs
              if (exoPlayer.playbackState == Player.STATE_ENDED || !exoPlayer.isPlaying) {
                exoPlayer.playWhenReady = true
                exoPlayer.play()
              }
            },
            colors = SliderDefaults.colors(
              thumbColor = MasalaRedAccent,
              activeTrackColor = MasalaRedPrimary,
              inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            ),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("player_seek_slider")
          )
        }
      }
    }

    // Playback error banner (rendered above controls so play/skip icons never overlap)
    if (playbackError != null) {
      Box(
        modifier = Modifier
          .align(Alignment.Center)
          .fillMaxWidth()
          .padding(horizontal = 24.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(Color.Black.copy(alpha = 0.90f))
          .border(1.dp, MasalaCardBorder, RoundedCornerShape(14.dp))
          .padding(20.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = playbackError ?: "Error loading stream",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
          )
          Spacer(modifier = Modifier.height(14.dp))
          androidx.compose.material3.Button(
            onClick = {
              playbackError = null
              isBuffering = true
              useSoftwareDecoderFallback = true
              retryTrigger++
            },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
              containerColor = MasalaRedPrimary
            )
          ) {
            Text("Retry Video", fontWeight = FontWeight.Bold)
          }
        }
      }
    }

    // Video Quality Selector Overlay Dialog
    if (showQualityDialog) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.72f))
          .clickable { showQualityDialog = false },
        contentAlignment = Alignment.Center
      ) {
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = MasalaSurface,
          border = BorderStroke(1.dp, MasalaCardBorder),
          modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .clickable(enabled = false) {}
            .testTag("player_quality_dialog")
        ) {
          Column(
            modifier = Modifier
              .padding(18.dp)
              .verticalScroll(rememberScrollState())
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column {
                Text(
                  text = "Video Quality",
                  color = Color.White,
                  fontSize = 17.sp,
                  fontWeight = FontWeight.Bold
                )
                if (currentStreamResolution.isNotBlank()) {
                  Text(
                    text = "Current stream: $currentStreamResolution",
                    color = MasalaGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                  )
                }
              }

              IconButton(
                onClick = { showQualityDialog = false },
                modifier = Modifier.testTag("close_quality_dialog_button")
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Close Quality Menu",
                  tint = MasalaTextMuted
                )
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            qualityOptions.forEach { option ->
              val isSelected = selectedQualityLabel == option.label
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) MasalaRedPrimary.copy(alpha = 0.18f) else MasalaSurfaceVariant,
                border = BorderStroke(
                  1.dp,
                  if (isSelected) MasalaRedPrimary else MasalaCardBorder
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 4.dp)
                  .clickable { applyVideoQuality(option) }
                  .testTag("quality_option_${option.label}")
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = option.label,
                      color = if (isSelected) MasalaGold else Color.White,
                      fontSize = 14.sp,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = option.subLabel,
                      color = MasalaTextMuted,
                      fontSize = 11.sp
                    )
                  }

                  if (isSelected) {
                    Icon(
                      imageVector = Icons.Default.Check,
                      contentDescription = "Selected",
                      tint = MasalaGold,
                      modifier = Modifier.size(20.dp)
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
