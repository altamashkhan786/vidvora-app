package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.FirestoreNotification
import com.example.data.repository.FirestoreVideoRepository
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AppNotificationHelper {
  private const val TAG = "AppNotificationHelper"
  private const val PREFS_NAME = "vidvora_notifications_prefs"
  private const val KEY_SHOWN_IDS = "shown_notification_ids"
  private const val KEY_DISMISSED_IDS = "dismissed_banner_ids"
  const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
  const val EXTRA_NOTIFICATION_VIDEO_LINK = "extra_notification_video_link"
  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channelId = context.getString(R.string.notification_channel_id)
      val channelName = context.getString(R.string.notification_channel_name)
      val channelDesc = context.getString(R.string.notification_channel_description)

      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        ?: return
      if (manager.getNotificationChannel(channelId) == null) {
        val channel = NotificationChannel(
          channelId,
          channelName,
          NotificationManager.IMPORTANCE_HIGH
        ).apply {
          description = channelDesc
          enableLights(true)
          enableVibration(true)
        }
        manager.createNotificationChannel(channel)
      }
    }
  }

  fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  /**
   * Returns a Coil-compatible image model (ByteArray for inline base64 data URIs, or URL String).
   */
  fun decodeImageModel(imageUrl: String): Any? {
    val clean = imageUrl.trim()
    if (clean.isBlank()) return null
    if (clean.startsWith("data:image/", ignoreCase = true) && clean.contains(";base64,")) {
      return try {
        val base64Part = clean.substringAfter(";base64,")
        Base64.decode(base64Part, Base64.DEFAULT)
      } catch (_: Exception) {
        null
      }
    }
    return clean
  }

  private fun decodeNotificationBitmap(imageUrl: String): Bitmap? {
    val clean = imageUrl.trim()
    if (clean.isBlank()) return null
    return try {
      if (clean.startsWith("data:image/", ignoreCase = true) && clean.contains(";base64,")) {
        val bytes = Base64.decode(clean.substringAfter(";base64,"), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      } else if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
        val conn = (URL(clean).openConnection() as HttpURLConnection).apply {
          connectTimeout = 4000
          readTimeout = 4000
          instanceFollowRedirects = true
        }
        conn.inputStream.use { stream ->
          BitmapFactory.decodeStream(stream)
        }
      } else {
        null
      }
    } catch (e: Exception) {
      Log.w(TAG, "Notification image load skipped: ${e.message}")
      null
    }
  }

  private fun buildContentIntent(context: Context, notice: FirestoreNotification): PendingIntent {
    val normalizedActionUrl = FirestoreVideoRepository.normalizeNotificationActionUrl(notice.actionUrl)
    val launchIntent = if (normalizedActionUrl.isNotBlank()) {
      if (FirestoreVideoRepository.isVidvoraVideoUrl(normalizedActionUrl)) {
        Intent(context, MainActivity::class.java).apply {
          action = Intent.ACTION_VIEW
          data = Uri.parse(normalizedActionUrl)
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
          putExtra(EXTRA_NOTIFICATION_ID, notice.notificationId)
          putExtra(EXTRA_NOTIFICATION_VIDEO_LINK, normalizedActionUrl)
        }
      } else {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedActionUrl)).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (viewIntent.resolveActivity(context.packageManager) != null) {
          viewIntent
        } else {
          Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
              Intent.FLAG_ACTIVITY_CLEAR_TOP or
              Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NOTIFICATION_ID, notice.notificationId)
          }
        }
      }
    } else {
      Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
          Intent.FLAG_ACTIVITY_CLEAR_TOP or
          Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_NOTIFICATION_ID, notice.notificationId)
      }
    }

    val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    return PendingIntent.getActivity(
      context,
      notice.notificationId.hashCode(),
      launchIntent,
      pendingIntentFlags
    )
  }

  @SuppressLint("MissingPermission")
  fun notifyUnseenBroadcasts(
    context: Context,
    notifications: List<FirestoreNotification>
  ) {
    if (notifications.isEmpty()) return
    val appContext = context.applicationContext
    ensureNotificationChannel(appContext)

    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val alreadyShown = prefs.getStringSet(KEY_SHOWN_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

    val unseen = notifications
      .filter { it.isActive && it.notificationId.isNotBlank() && it.notificationId !in alreadyShown }
      .take(3) // Avoid flooding tray if many notices queued

    if (unseen.isEmpty()) return

    if (!hasNotificationPermission(appContext)) {
      // Permission not yet granted; do not mark shown yet so it notifies once granted,
      // while HomeScreen still shows the in-app announcement banner.
      return
    }

    unseen.forEach { alreadyShown.add(it.notificationId) }
    prefs.edit().putStringSet(KEY_SHOWN_IDS, alreadyShown).apply()

    val channelId = appContext.getString(R.string.notification_channel_id)
    val notificationManager = NotificationManagerCompat.from(appContext)

    ioScope.launch {
      unseen.forEach { notice ->
        try {
          val pendingIntent = buildContentIntent(appContext, notice)
          val bitmap = if (notice.imageUrl.isNotBlank()) {
            decodeNotificationBitmap(notice.imageUrl)
          } else {
            null
          }

          val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_vidvora_icon)
            .setContentTitle(notice.title)
            .setContentText(notice.message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

          if (bitmap != null) {
            builder.setLargeIcon(bitmap)
            builder.setStyle(
              NotificationCompat.BigPictureStyle()
                .bigPicture(bitmap)
                .bigLargeIcon(null as Bitmap?)
                .setSummaryText(notice.message)
            )
          } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(notice.message))
          }

          if (notice.actionUrl.isNotBlank()) {
            builder.addAction(0, "Open Link", pendingIntent)
          }

          notificationManager.notify(notice.notificationId.hashCode(), builder.build())
        } catch (e: Exception) {
          Log.w(TAG, "Failed to post system notification ${notice.notificationId}: ${e.message}")
        }
      }
    }
  }

  fun openNotificationUrl(context: Context, rawUrl: String): Boolean {
    val cleanUrl = FirestoreVideoRepository.normalizeNotificationActionUrl(rawUrl)
    if (cleanUrl.isBlank()) return false
    if (FirestoreVideoRepository.isVidvoraVideoUrl(cleanUrl)) {
      return try {
        val intent = Intent(context, MainActivity::class.java).apply {
          action = Intent.ACTION_VIEW
          data = Uri.parse(cleanUrl)
          addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
              Intent.FLAG_ACTIVITY_CLEAR_TOP or
              Intent.FLAG_ACTIVITY_SINGLE_TOP
          )
          putExtra(EXTRA_NOTIFICATION_VIDEO_LINK, cleanUrl)
        }
        context.startActivity(intent)
        true
      } catch (e: Exception) {
        Log.w(TAG, "Failed to open VidVora video notification URL ($cleanUrl): ${e.message}")
        false
      }
    }
    return openExternalUrl(context, cleanUrl)
  }

  fun openExternalUrl(context: Context, rawUrl: String): Boolean {
    val cleanUrl = FirestoreVideoRepository.normalizeNotificationActionUrl(rawUrl)
    if (cleanUrl.isBlank()) return false
    return try {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      true
    } catch (e: Exception) {
      Log.w(TAG, "Failed to open notification URL ($cleanUrl): ${e.message}")
      false
    }
  }

  fun getDismissedBannerIds(context: Context): Set<String> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getStringSet(KEY_DISMISSED_IDS, emptySet())?.toSet() ?: emptySet()
  }

  fun dismissBannerId(context: Context, notificationId: String) {
    if (notificationId.isBlank()) return
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val updated = prefs.getStringSet(KEY_DISMISSED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
    updated.add(notificationId)
    prefs.edit().putStringSet(KEY_DISMISSED_IDS, updated).apply()
  }
}
