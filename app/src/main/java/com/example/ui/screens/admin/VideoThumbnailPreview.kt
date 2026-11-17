package com.example.ui.screens.admin

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.notifications.AppNotificationHelper
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaSurfaceVariant
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * In-memory cache of extracted video frame bitmaps so scrolling through video lists stays smooth.
 */
object VideoThumbnailCache {
  private val bitmapCache = object : LruCache<String, Bitmap>(48) {}
  private val failedKeys: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

  fun get(key: String): Bitmap? = bitmapCache.get(key)

  fun put(key: String, bitmap: Bitmap) {
    failedKeys.remove(key)
    bitmapCache.put(key, bitmap)
  }

  fun isMarkedFailed(key: String): Boolean = failedKeys.contains(key)

  fun markFailed(key: String) {
    failedKeys.add(key)
  }
}

suspend fun extractVideoThumbnailBitmap(
  context: Context,
  sourcePathOrUri: String,
  cacheKey: String = sourcePathOrUri
): Bitmap? {
  val clean = sourcePathOrUri.trim()
  if (clean.isBlank()) return null

  VideoThumbnailCache.get(cacheKey)?.let { return it }
  if (VideoThumbnailCache.isMarkedFailed(cacheKey)) return null

  val timeoutMs = if (clean.startsWith("http://", ignoreCase = true) ||
    clean.startsWith("https://", ignoreCase = true)
  ) {
    4000L
  } else {
    3000L
  }

  val extracted = withTimeoutOrNull(timeoutMs) {
    withContext(Dispatchers.IO) {
      val retriever = MediaMetadataRetriever()
      try {
        when {
          clean.startsWith("content://", ignoreCase = true) ||
            clean.startsWith("file://", ignoreCase = true) -> {
            retriever.setDataSource(context, Uri.parse(clean))
          }
          clean.startsWith("http://", ignoreCase = true) ||
            clean.startsWith("https://", ignoreCase = true) -> {
            retriever.setDataSource(clean, emptyMap())
          }
          clean.startsWith("/") -> {
            retriever.setDataSource(clean)
          }
          else -> return@withContext null
        }

        val rawFrame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
          retriever.getScaledFrameAtTime(
            1_000_000L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            1080,
            1080
          ) ?: retriever.getScaledFrameAtTime(
            0L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            1080,
            1080
          )
        } else {
          retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }

        rawFrame?.let { frame ->
          val maxDim = 1080
          val scale = minOf(
            1f,
            maxDim.toFloat() / frame.width.coerceAtLeast(1),
            maxDim.toFloat() / frame.height.coerceAtLeast(1)
          )
          if (scale < 1f) {
            Bitmap.createScaledBitmap(
              frame,
              (frame.width * scale).toInt().coerceAtLeast(1),
              (frame.height * scale).toInt().coerceAtLeast(1),
              true
            ).also {
              if (it !== frame) frame.recycle()
            }
          } else {
            frame
          }
        }
      } catch (_: Throwable) {
        null
      } finally {
        try {
          retriever.release()
        } catch (_: Exception) {
        }
      }
    }
  }

  if (extracted != null) {
    VideoThumbnailCache.put(cacheKey, extracted)
  } else {
    VideoThumbnailCache.markFailed(cacheKey)
  }
  return extracted
}

@Composable
fun VideoThumbnailBox(
  thumbnailPath: String?,
  storagePath: String,
  videoKey: String,
  contentDescription: String,
  modifier: Modifier = Modifier,
  cornerRadius: Dp = 10.dp,
  showPlayBadge: Boolean = true,
  playBadgeSize: Dp = 28.dp,
  onClick: (() -> Unit)? = null
) {
  val context = LocalContext.current
  val decodedModel = remember(thumbnailPath) {
    thumbnailPath?.takeIf { it.isNotBlank() }?.let { AppNotificationHelper.decodeImageModel(it) }
  }
  var imageLoadFailed by remember(thumbnailPath) { mutableStateOf(false) }

  val shouldExtractFromVideo = decodedModel == null || imageLoadFailed
  val cacheKey = "${videoKey}_${storagePath.hashCode()}"
  var extractedBitmap by remember(cacheKey) {
    mutableStateOf(VideoThumbnailCache.get(cacheKey))
  }

  LaunchedEffect(shouldExtractFromVideo, storagePath, cacheKey) {
    if (shouldExtractFromVideo && storagePath.isNotBlank() && extractedBitmap == null) {
      extractedBitmap = extractVideoThumbnailBitmap(
        context = context,
        sourcePathOrUri = storagePath,
        cacheKey = cacheKey
      )
    }
  }

  val shape = RoundedCornerShape(cornerRadius)
  val boxModifier = modifier
    .clip(shape)
    .background(
      Brush.linearGradient(
        colors = listOf(MasalaSurfaceVariant, MasalaSurface, MasalaRedPrimary.copy(alpha = 0.2f))
      )
    )
    .border(1.dp, MasalaCardBorder, shape)
    .let { base ->
      if (onClick != null) base.clickable(onClick = onClick) else base
    }

  Box(
    modifier = boxModifier,
    contentAlignment = Alignment.Center
  ) {
    when {
      decodedModel != null && !imageLoadFailed -> {
        AsyncImage(
          model = decodedModel,
          contentDescription = contentDescription,
          contentScale = ContentScale.Crop,
          onError = { imageLoadFailed = true },
          modifier = Modifier.fillMaxSize()
        )
      }
      extractedBitmap != null -> {
        Image(
          bitmap = extractedBitmap!!.asImageBitmap(),
          contentDescription = contentDescription,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
        )
      }
      else -> {
        Icon(
          imageVector = Icons.Default.Movie,
          contentDescription = contentDescription,
          tint = MasalaGold.copy(alpha = 0.55f),
          modifier = Modifier.size(playBadgeSize * 0.9f)
        )
      }
    }

    if (showPlayBadge) {
      Box(
        modifier = Modifier
          .size(playBadgeSize)
          .clip(CircleShape)
          .background(Color.Black.copy(alpha = 0.62f))
          .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(playBadgeSize * 0.64f)
        )
      }
    }
  }
}
