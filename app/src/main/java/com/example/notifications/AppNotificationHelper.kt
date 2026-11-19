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
import com.example.data.model.FirestoreSupportChat
import com.example.data.repository.FirestoreVideoRepository
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class SupportAlertDecision(
  val chatsToNotify: List<FirestoreSupportChat>,
  val updatedSignatures: Set<String>
)

object AppNotificationHelper {
  private const val TAG = "AppNotificationHelper"
  private const val PREFS_NAME = "vidvora_notifications_prefs"
  private const val KEY_SHOWN_IDS = "shown_notification_ids"
  private const val KEY_DISMISSED_IDS = "dismissed_banner_ids"
  private const val KEY_ADMIN_SUPPORT_SIGNATURES = "admin_support_notified_signatures_v1"
  const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
  const val EXTRA_NOTIFICATION_VIDEO_LINK = "extra_notification_video_link"
  const val EXTRA_OPEN_ADMIN_SUPPORT = "extra_open_admin_support"
  const val EXTRA_SUPPORT_CHAT_ID = "extra_support_chat_id"
  const val ADMIN_SUPPORT_CHANNEL_ID = "vidvora_admin_support_alerts"
  private const val ADMIN_SUPPORT_SUMMARY_NOTIFICATION_ID = 90421
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
      if (manager.getNotificationChannel(ADMIN_SUPPORT_CHANNEL_ID) == null) {
        val supportChannel = NotificationChannel(
          ADMIN_SUPPORT_CHANNEL_ID,
          "Admin Support Messages",
          NotificationManager.IMPORTANCE_HIGH
        ).apply {
          description = "Instant alerts when creators or users send support messages"
          enableLights(true)
          enableVibration(true)
          setShowBadge(true)
        }
        manager.createNotificationChannel(supportChannel)
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

  /**
   * Builds a deterministic signature representing the latest unread message state of a support chat.
   */
  fun buildSupportChatAlertSignature(chat: FirestoreSupportChat): String {
    val cleanId = chat.chatId.trim()
    val cleanMsg = chat.lastMessage.trim()
    return "$cleanId|${chat.lastMessageAt.seconds}_${chat.lastMessageAt.nanoseconds}|${chat.unreadByAdminCount}|${cleanMsg.hashCode()}"
  }

  /**
   * Pure decision helper for live and login-time Admin support notifications.
   * - When [forceNotifyAllUnreadOnLogin] is true (right when Admin logs in), returns all currently unread chats.
   * - When [forceNotifyAllUnreadOnLogin] is false (live polling while Admin is logged in), returns only newly arrived or updated unread chats.
   */
  fun selectSupportChatsToNotify(
    chats: List<FirestoreSupportChat>,
    previouslyNotifiedSignatures: Set<String>,
    forceNotifyAllUnreadOnLogin: Boolean
  ): SupportAlertDecision {
    val unreadChats = chats
      .filter { it.chatId.isNotBlank() && it.unreadByAdminCount > 0L }
      .sortedWith(
        compareByDescending<FirestoreSupportChat> { it.lastMessageAt.seconds }
          .thenByDescending { it.lastMessageAt.nanoseconds }
          .thenBy { it.chatId }
      )
    val currentUnreadSignatures = unreadChats.map { buildSupportChatAlertSignature(it) }.toSet()
    val toNotify = if (forceNotifyAllUnreadOnLogin) {
      unreadChats
    } else {
      unreadChats.filter { buildSupportChatAlertSignature(it) !in previouslyNotifiedSignatures }
    }
    return SupportAlertDecision(
      chatsToNotify = toNotify,
      updatedSignatures = currentUnreadSignatures
    )
  }

  private fun buildAdminSupportIntent(context: Context, chatId: String?): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP
      putExtra(EXTRA_OPEN_ADMIN_SUPPORT, true)
      if (!chatId.isNullOrBlank()) {
        putExtra(EXTRA_SUPPORT_CHAT_ID, chatId)
      }
    }
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    val requestCode = if (chatId.isNullOrBlank()) {
      ADMIN_SUPPORT_SUMMARY_NOTIFICATION_ID
    } else {
      ("admin_support_req_$chatId").hashCode()
    }
    return PendingIntent.getActivity(context, requestCode, intent, flags)
  }

  /**
   * Posts immediate high-priority Android notifications for Admin when a Creator or User sends a support message,
   * both live while logged in and immediately upon Admin login for any waiting unread messages.
   * Returns the number of chats notified (0 if none or permission not yet granted).
   */
  @SuppressLint("MissingPermission")
  fun notifyAdminSupportMessages(
    context: Context,
    chats: List<FirestoreSupportChat>,
    forceNotifyUnreadOnLogin: Boolean = false
  ): Int {
    val appContext = context.applicationContext
    ensureNotificationChannel(appContext)

    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val previousSignatures = prefs.getStringSet(KEY_ADMIN_SUPPORT_SIGNATURES, emptySet())?.toSet() ?: emptySet()
    val decision = selectSupportChatsToNotify(
      chats = chats,
      previouslyNotifiedSignatures = previousSignatures,
      forceNotifyAllUnreadOnLogin = forceNotifyUnreadOnLogin
    )

    if (decision.chatsToNotify.isEmpty()) {
      if (decision.updatedSignatures != previousSignatures) {
        prefs.edit().putStringSet(KEY_ADMIN_SUPPORT_SIGNATURES, decision.updatedSignatures).apply()
      }
      return 0
    }

    if (!hasNotificationPermission(appContext)) {
      // Keep signatures unrecorded so pending unread alerts fire immediately once permission is granted
      return 0
    }

    prefs.edit().putStringSet(KEY_ADMIN_SUPPORT_SIGNATURES, decision.updatedSignatures).commit()

    val manager = NotificationManagerCompat.from(appContext)
    try {
      if (forceNotifyUnreadOnLogin && decision.chatsToNotify.size > 1) {
        val totalUnreadCount = decision.chatsToNotify.sumOf { it.unreadByAdminCount.coerceAtLeast(1L) }
        val senderNames = decision.chatsToNotify.take(3).joinToString(", ") {
          it.userName.trim().ifBlank { "User" }
        }
        val title = "${decision.chatsToNotify.size} Unread Support Messages"
        val summaryLine = "$totalUnreadCount new message(s) from $senderNames"
        val inboxStyle = NotificationCompat.InboxStyle()
          .setBigContentTitle(title)
          .setSummaryText("Tap to open Support Chat")
        decision.chatsToNotify.take(5).forEach { chat ->
          val sender = chat.userName.trim().ifBlank { "User" }
          val preview = chat.lastMessage.trim().ifBlank { "New support message" }
          inboxStyle.addLine("$sender: $preview")
        }
        val notification = NotificationCompat.Builder(appContext, ADMIN_SUPPORT_CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_vidvora_icon)
          .setContentTitle(title)
          .setContentText(summaryLine)
          .setStyle(inboxStyle)
          .setPriority(NotificationCompat.PRIORITY_HIGH)
          .setDefaults(NotificationCompat.DEFAULT_ALL)
          .setCategory(NotificationCompat.CATEGORY_MESSAGE)
          .setAutoCancel(true)
          .setContentIntent(buildAdminSupportIntent(appContext, chatId = null))
          .build()
        manager.notify(ADMIN_SUPPORT_SUMMARY_NOTIFICATION_ID, notification)
      } else {
        decision.chatsToNotify.take(4).forEach { chat ->
          val senderName = chat.userName.trim().ifBlank { "Creator / User" }
          val preview = chat.lastMessage.trim().ifBlank { "Sent you a new support message" }
          val title = if (forceNotifyUnreadOnLogin) {
            "Unread Support • $senderName"
          } else {
            "New Support Message • $senderName"
          }
          val subText = if (chat.unreadByAdminCount > 1L) {
            "${chat.unreadByAdminCount} unread messages"
          } else {
            "Creator / User Support"
          }
          val notification = NotificationCompat.Builder(appContext, ADMIN_SUPPORT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vidvora_icon)
            .setContentTitle(title)
            .setContentText(preview)
            .setSubText(subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(buildAdminSupportIntent(appContext, chatId = chat.chatId))
            .build()
          manager.notify(("admin_support_${chat.chatId}").hashCode(), notification)
        }
      }
      return decision.chatsToNotify.size
    } catch (e: Exception) {
      Log.w(TAG, "Failed to post admin support alert notification: ${e.message}")
      return 0
    }
  }

  fun clearAdminSupportNotifiedState(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().remove(KEY_ADMIN_SUPPORT_SIGNATURES).commit()
  }
}
