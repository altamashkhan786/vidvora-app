package com.example.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.example.R
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaTextMuted
import com.example.ui.theme.MasalaTextPrimary
import com.example.ui.theme.MasalaTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ShareVideoRequest(
  val videoId: String,
  val thumbnailPath: String?,
  val storagePath: String?,
  val shareUrl: String
)

object VideoShareHelper {
  private const val TAG = "VideoShareHelper"

  private val _pendingShareRequest = MutableStateFlow<ShareVideoRequest?>(null)
  val pendingShareRequest: StateFlow<ShareVideoRequest?> = _pendingShareRequest.asStateFlow()

  /**
   * Builds the exact text shared to external apps.
   * Never includes video title/filename or extra text — outputs ONLY the clean URL
   * so links stay concise, clickable, and private across every platform.
   */
  fun buildShareText(shareUrl: String): String = shareUrl.trim()

  fun dismissShareDialog() {
    _pendingShareRequest.value = null
  }

  /**
   * Shares a VidVora video without sending any video title:
   * - If dynamic Open Graph share preview server (`shareLinkBaseUrl`) is active, directly shares
   *   pure clickable URL (`text/plain`) so every platform fetches the crisp OG thumbnail card.
   * - If still using static CloudFront S3 (no dynamic OG server configured), opens a fast 2-tap
   *   share chooser letting the user pick "Thumbnail + Link" (attaches crisp JPEG + pure URL +
   *   copies URL to clipboard) or "Pure Clickable Link (All Platforms)".
   */
  suspend fun shareVideo(
    context: Context,
    @Suppress("UNUSED_PARAMETER") title: String,
    videoId: String,
    thumbnailPath: String?,
    storagePath: String?,
    shareUrl: String,
    hasDynamicOgPreview: Boolean
  ) {
    val cleanUrl = buildShareText(shareUrl)
    if (cleanUrl.isBlank()) {
      Toast.makeText(context, "Share link unavailable for this video", Toast.LENGTH_SHORT).show()
      return
    }

    if (hasDynamicOgPreview) {
      shareClickableLinkOnly(context, cleanUrl)
      return
    }

    _pendingShareRequest.value = ShareVideoRequest(
      videoId = videoId,
      thumbnailPath = thumbnailPath,
      storagePath = storagePath,
      shareUrl = cleanUrl
    )
  }

  fun shareClickableLinkOnly(context: Context, shareUrl: String) {
    val cleanUrl = buildShareText(shareUrl)
    if (cleanUrl.isBlank()) return
    copyUrlToClipboard(context, cleanUrl)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, cleanUrl)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Video Link"))
  }

  suspend fun shareWithThumbnailAndLink(
    context: Context,
    request: ShareVideoRequest
  ) {
    val cleanUrl = buildShareText(request.shareUrl)
    if (cleanUrl.isBlank()) return

    copyUrlToClipboard(context, cleanUrl)

    val thumbUri = resolveCachedShareThumbUri(
      context = context,
      videoId = request.videoId,
      thumbnailPath = request.thumbnailPath,
      storagePath = request.storagePath
    )

    if (thumbUri != null) {
      val richIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, thumbUri)
        putExtra(Intent.EXTRA_TEXT, cleanUrl)
        clipData = ClipData.newUri(context.contentResolver, "VidVora", thumbUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      try {
        Toast.makeText(
          context,
          "Link copied to clipboard too — paste if app hides caption",
          Toast.LENGTH_SHORT
        ).show()
        context.startActivity(Intent.createChooser(richIntent, "Share Thumbnail & Link"))
        return
      } catch (e: Exception) {
        Log.w(TAG, "Rich thumbnail share fallback to link-only: ${e.message}")
      }
    }

    shareClickableLinkOnly(context, cleanUrl)
  }

  private fun copyUrlToClipboard(context: Context, cleanUrl: String) {
    runCatching {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      clipboard?.setPrimaryClip(ClipData.newPlainText("VidVora", cleanUrl))
    }
  }

  private suspend fun resolveCachedShareThumbUri(
    context: Context,
    videoId: String,
    thumbnailPath: String?,
    storagePath: String?
  ): Uri? = withContext(Dispatchers.IO) {
    try {
      val dir = File(context.cacheDir, "shared_thumbs").apply { mkdirs() }
      val safeName = videoId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "vidvora_share" }
      val outFile = File(dir, "${safeName}_share.jpg")

      val bitmap = loadThumbBitmap(context, thumbnailPath, storagePath)
        ?: BitmapFactory.decodeResource(context.resources, R.drawable.ic_vidvora_icon)
        ?: return@withContext null

      FileOutputStream(outFile).use { fos ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        fos.flush()
      }
      if (!outFile.exists() || outFile.length() <= 0L) return@withContext null

      FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        outFile
      )
    } catch (e: Exception) {
      Log.w(TAG, "Failed to prepare share thumbnail file: ${e.message}")
      null
    }
  }

  private suspend fun loadThumbBitmap(
    context: Context,
    thumbnailPath: String?,
    storagePath: String?
  ): Bitmap? {
    val thumb = thumbnailPath?.trim().orEmpty()
    if (thumb.startsWith("data:image/", ignoreCase = true)) {
      val base64Part = thumb.substringAfter("base64,", "")
      if (base64Part.isNotBlank()) {
        runCatching {
          val bytes = Base64.decode(base64Part, Base64.DEFAULT)
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.let { return it }
      }
    }

    if (thumb.startsWith("file://", ignoreCase = true) || thumb.startsWith("content://", ignoreCase = true)) {
      runCatching {
        context.contentResolver.openInputStream(Uri.parse(thumb))?.use { input ->
          BitmapFactory.decodeStream(input)
        }
      }.getOrNull()?.let { return it }
    }

    if (thumb.startsWith("http://", ignoreCase = true) || thumb.startsWith("https://", ignoreCase = true)) {
      val downloaded = withTimeoutOrNull(3500L) {
        runCatching {
          val conn = (URL(thumb).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 3000
            instanceFollowRedirects = true
          }
          try {
            if (conn.responseCode in 200..299) {
              conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } else null
          } finally {
            conn.disconnect()
          }
        }.getOrNull()
      }
      if (downloaded != null) return downloaded
    }

    val media = storagePath?.trim().orEmpty()
    if (media.isNotBlank()) {
      return withTimeoutOrNull(3500L) {
        val retriever = MediaMetadataRetriever()
        try {
          when {
            media.startsWith("content://", true) || media.startsWith("file://", true) -> {
              retriever.setDataSource(context, Uri.parse(media))
            }
            media.startsWith("http://", true) || media.startsWith("https://", true) -> {
              retriever.setDataSource(media, emptyMap())
            }
            media.startsWith("/") -> retriever.setDataSource(media)
            else -> return@withTimeoutOrNull null
          }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 1280)
              ?: retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 1280)
          } else {
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
              ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          }
        } catch (_: Throwable) {
          null
        } finally {
          runCatching { retriever.release() }
        }
      }
    }
    return null
  }
}

@Composable
fun VideoShareModeDialogHost() {
  val request by VideoShareHelper.pendingShareRequest.collectAsState()
  val activeRequest = request ?: return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var isPreparingThumb by remember { mutableStateOf(false) }

  Dialog(onDismissRequest = { if (!isPreparingThumb) VideoShareHelper.dismissShareDialog() }) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MasalaSurface),
      border = BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("video_share_mode_dialog")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Share,
            contentDescription = null,
            tint = MasalaGold,
            modifier = Modifier.size(22.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Share Video Link",
            color = MasalaTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "Video title share nahi hoga. Choose karein aap kaise share karna chahte hain:",
          color = MasalaTextSecondary,
          fontSize = 12.sp,
          lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
          onClick = {
            if (!isPreparingThumb) {
              isPreparingThumb = true
              scope.launch {
                try {
                  VideoShareHelper.shareWithThumbnailAndLink(context, activeRequest)
                } finally {
                  isPreparingThumb = false
                  VideoShareHelper.dismissShareDialog()
                }
              }
            }
          },
          enabled = !isPreparingThumb,
          colors = ButtonDefaults.buttonColors(containerColor = MasalaRedPrimary),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("share_thumbnail_and_link_button")
        ) {
          if (isPreparingThumb) {
            CircularProgressIndicator(
              color = Color.White,
              strokeWidth = 2.dp,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Preparing Thumbnail...", fontWeight = FontWeight.Bold, fontSize = 13.sp)
          } else {
            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share Thumbnail + Link", fontWeight = FontWeight.Bold, fontSize = 13.sp)
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
          onClick = {
            VideoShareHelper.dismissShareDialog()
            VideoShareHelper.shareClickableLinkOnly(context, activeRequest.shareUrl)
          },
          enabled = !isPreparingThumb,
          border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.7f)),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("share_link_only_button")
        ) {
          Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Share Clickable Link (All Platforms)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
          text = "Tip: Automatic link-card thumbnails on every platform ke liye Admin Panel -> Share Preview Server URL set karein.",
          color = MasalaTextMuted,
          fontSize = 10.sp,
          lineHeight = 14.sp
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          TextButton(
            onClick = { if (!isPreparingThumb) VideoShareHelper.dismissShareDialog() },
            enabled = !isPreparingThumb
          ) {
            Text("Cancel", color = MasalaTextMuted, fontSize = 12.sp)
          }
        }
      }
    }
  }
}
