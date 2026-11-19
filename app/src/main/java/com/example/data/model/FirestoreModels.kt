package com.example.data.model

import com.google.firebase.Timestamp
import java.text.NumberFormat
import java.util.Locale

data class FirestoreVideo(
  val videoId: String = "",
  val title: String = "",
  val description: String = "",
  val thumbnailPath: String? = null,
  val storagePath: String = "",
  val createdAt: Timestamp = Timestamp.now(),
  val isActive: Boolean = true,
  val totalViews: Long = 0L,
  val uploadedBy: String = "admin",
  val uploaderName: String = "Admin"
) {
  fun toVideoToken(streamUrl: String, token: String): VideoToken = VideoToken(
    id = videoId,
    token = token,
    title = title,
    description = description,
    streamUrl = streamUrl,
    posterUrl = thumbnailPath,
    quality = "1080p FHD",
    isActive = isActive,
    viewCount = totalViews.toInt(),
    createdAt = createdAt.toDate().time,
    uploadedBy = uploadedBy,
    uploaderName = uploaderName
  )
}

data class FirestoreAccessLink(
  val linkId: String = "",
  val videoId: String = "",
  val tokenHash: String = "",
  val isActive: Boolean = true,
  val createdAt: Timestamp = Timestamp.now(),
  val expiresAt: Timestamp? = null
) {
  val isExpired: Boolean
    get() = expiresAt != null && expiresAt.toDate().time < System.currentTimeMillis()
}

data class FirestoreAdmin(
  val uid: String = "",
  val role: String = "admin",
  val isActive: Boolean = true
)

object MemberRole {
  const val CREATOR = "member"
  const val ADMIN = "admin"

  fun normalize(raw: String?): String {
    val clean = raw?.trim()?.lowercase()
    return if (clean == ADMIN || clean == "subadmin" || clean == "delegated_admin") ADMIN else CREATOR
  }

  fun displayLabel(role: String?): String {
    return if (normalize(role) == ADMIN) "Admin" else "Creator"
  }
}

enum class MemberOption(
  val key: String,
  val label: String,
  val description: String,
  val recommendedForCreator: Boolean,
  val recommendedForAdmin: Boolean
) {
  UPLOAD_VIDEOS(
    key = "UPLOAD_VIDEOS",
    label = "Upload Videos",
    description = "Upload videos or add stream links",
    recommendedForCreator = true,
    recommendedForAdmin = true
  ),
  MANAGE_VIDEOS(
    key = "MANAGE_VIDEOS",
    label = "Manage Videos & Links",
    description = "View, edit, toggle active status or copy share links",
    recommendedForCreator = true,
    recommendedForAdmin = true
  ),
  VIEW_EARNINGS(
    key = "VIEW_EARNINGS",
    label = "View My Earnings",
    description = "See total & available INR balance",
    recommendedForCreator = true,
    recommendedForAdmin = false
  ),
  REQUEST_WITHDRAWAL(
    key = "REQUEST_WITHDRAWAL",
    label = "Request UPI Withdrawal",
    description = "Withdraw earnings via UPI when eligible",
    recommendedForCreator = true,
    recommendedForAdmin = false
  ),
  MANAGE_MEMBERS(
    key = "MANAGE_MEMBERS",
    label = "Manage Members",
    description = "Add, edit, activate or delete Creators & Admins",
    recommendedForCreator = false,
    recommendedForAdmin = true
  ),
  MANAGE_PAYOUTS(
    key = "MANAGE_PAYOUTS",
    label = "Manage Payouts & Earnings",
    description = "Process withdrawal requests & credit creator earnings",
    recommendedForCreator = false,
    recommendedForAdmin = true
  ),
  SEND_NOTIFICATIONS(
    key = "SEND_NOTIFICATIONS",
    label = "Send Notifications",
    description = "Send broadcast text, image & link alerts to users",
    recommendedForCreator = false,
    recommendedForAdmin = true
  ),
  MANAGE_TELEGRAM(
    key = "MANAGE_TELEGRAM",
    label = "Telegram Categories & Links",
    description = "Add, edit, delete category channels & Telegram links",
    recommendedForCreator = false,
    recommendedForAdmin = true
  ),
  VIEW_APP_STATS(
    key = "VIEW_APP_STATS",
    label = "App Install Analytics",
    description = "View total downloads, active installs & uninstalled devices",
    recommendedForCreator = false,
    recommendedForAdmin = true
  ),
  MANAGE_SUPPORT_CHAT(
    key = "MANAGE_SUPPORT_CHAT",
    label = "Support Chat Reply",
    description = "Admin only: View user & creator support chats and send VidVora Support Team replies",
    recommendedForCreator = false,
    recommendedForAdmin = true
  ),
  MANAGE_EVENTS(
    key = "MANAGE_EVENTS",
    label = "Creator Events & Gifts",
    description = "Admin only: Publish, edit & delete gift/event announcements for Creators",
    recommendedForCreator = false,
    recommendedForAdmin = true
  ),
  CHANGE_MASTER_KEY(
    key = "CHANGE_MASTER_KEY",
    label = "Change Own Password",
    description = "Update their own login Password",
    recommendedForCreator = true,
    recommendedForAdmin = true
  );

  companion object {
    fun fromKey(key: String?): MemberOption? = entries.firstOrNull { it.key == key?.trim() }

    fun allKeys(): List<String> = entries.map { it.key }

    fun defaultKeysForRole(role: String): List<String> {
      val normalized = MemberRole.normalize(role)
      return entries.filter { option ->
        if (normalized == MemberRole.ADMIN) option.recommendedForAdmin
        else option.recommendedForCreator
      }.map { it.key }
    }

    fun sanitizeKeys(keys: Collection<String>): List<String> {
      val requested = keys.map { it.trim() }.toSet()
      return entries.filter { it.key in requested }.map { it.key }
    }
  }
}

data class FirestoreMember(
  val memberId: String = "",
  val name: String = "",
  val emailOrPhone: String = "",
  val passkey: String = "",
  val role: String = MemberRole.CREATOR,
  val isActive: Boolean = true,
  val createdAt: Timestamp = Timestamp.now(),
  val totalEarning: Double = 0.0,
  val creditedViews: Long = 0L,
  val viewRatePer1000: Double = 190.0,
  val allowedOptions: List<String> = MemberOption.defaultKeysForRole(MemberRole.CREATOR),
  val hourlyViews: Map<String, Long> = emptyMap(),
  val lastViewsUpdateCycleKey: String = "",
  val lastViewsUpdatedAtMs: Long = 0L
) {
  fun isPasskeyMatch(inputKey: String): Boolean {
    return isActive && passkey.trim().equals(inputKey.trim(), ignoreCase = false)
  }

  fun isDelegatedAdmin(): Boolean {
    return MemberRole.normalize(role) == MemberRole.ADMIN
  }

  fun hasOption(option: MemberOption): Boolean = option.key in allowedOptions

  fun hasOptionKey(key: String): Boolean = key in allowedOptions
}

object WithdrawalStatus {
  const val PENDING = "PENDING"
  const val PROCESSING = "PROCESSING"
  const val DONE = "DONE"
  const val REJECTED = "REJECTED"
}

data class FirestoreWithdrawalRequest(
  val requestId: String = "",
  val memberId: String = "",
  val creatorName: String = "",
  val creatorEmail: String = "",
  val amount: Double = 0.0,
  val upiId: String = "",
  val status: String = WithdrawalStatus.PENDING,
  val adminNote: String = "",
  val requestedAt: Timestamp = Timestamp.now(),
  val updatedAt: Timestamp? = null
)

data class EarningSummary(
  val totalEarning: Double = 0.0,
  val pendingOrProcessingAmount: Double = 0.0,
  val paidAmount: Double = 0.0,
  val availableBalance: Double = 0.0
)

data class MemberStats(
  val member: FirestoreMember,
  val videoCount: Int,
  val totalViews: Long,
  val videos: List<FirestoreVideo>,
  val earningSummary: EarningSummary = EarningSummary(
    totalEarning = member.totalEarning,
    availableBalance = member.totalEarning
  ),
  val viewsLast24Hours: Long = 0L
)

data class CreatorEmailChangeResult(
  val member: FirestoreMember,
  val previousEmail: String,
  val updatedEmail: String,
  val migratedVideosCount: Int = 0,
  val syncedWithdrawalsCount: Int = 0,
  val mergedExistingAccount: Boolean = false
)

data class FirestoreNotification(
  val notificationId: String = "",
  val title: String = "",
  val message: String = "",
  val imageUrl: String = "",
  val actionUrl: String = "",
  val sentBy: String = "Admin",
  val createdAt: Timestamp = Timestamp.now(),
  val isActive: Boolean = true
)

data class FirestoreCreatorEvent(
  val eventId: String = "",
  val title: String = "",
  val description: String = "",
  val giftLabel: String = "",
  val imageUrl: String = "",
  val createdBy: String = "Superadmin",
  val createdAt: Timestamp = Timestamp.now(),
  val updatedAt: Timestamp = Timestamp.now(),
  val isActive: Boolean = true
)

data class FirestoreAppInstall(
  val installId: String = "",
  val firstInstalledAt: Timestamp = Timestamp.now(),
  val lastSeenAt: Timestamp = Timestamp.now(),
  val appVersion: String = "1.0",
  val deviceLabel: String = "Android Device",
  val androidVersion: String = "Android",
  val sessionCount: Long = 1L,
  val isMarkedUninstalled: Boolean = false
)

data class AppInstallStats(
  val totalDownloads: Int = 0,
  val currentlyInstalledCount: Int = 0,
  val uninstalledCount: Int = 0,
  val downloadsToday: Int = 0,
  val activeLast24hCount: Int = 0,
  val retentionPercent: Int = 0,
  val inactivityWindowDays: Int = 14
)

data class TelegramCategoryItem(
  val id: String = "",
  val name: String = "",
  val link: String = "",
  val isBuiltIn: Boolean = false
)

enum class TelegramChannelCategory(
  val key: String,
  val number: Int,
  val label: String
) {
  PAKISTANI_DRAMA("pakistani_drama", 1, "Pakistani Drama"),
  SOUTH_MOVIE("south_movie", 2, "South Movie"),
  ULLU_TV_SERIES("ullu_tv_series", 3, "Ullu Tv Series"),
  BHOJPURI_MOVIE("bhojpuri_movie", 4, "Bhojpuri Movie"),
  ADULT_CONTENT("adult_content", 5, "Adult Content");

  companion object {
    fun fromKey(key: String?): TelegramChannelCategory? =
      entries.firstOrNull { it.key == key?.trim() }

    fun emptyCategoryMap(): Map<String, String> =
      entries.associate { it.key to "" }

    fun defaultItems(links: Map<String, String> = emptyMap()): List<TelegramCategoryItem> =
      entries.map { cat ->
        TelegramCategoryItem(
          id = cat.key,
          name = cat.label,
          link = links[cat.key].orEmpty(),
          isBuiltIn = true
        )
      }
  }
}

data class TelegramConfig(
  val mainLink: String = "https://t.me/VidVoraApp",
  val creatorBotLink: String = "",
  val financeBotLink: String = "",
  val shareLinkBaseUrl: String = "",
  val categoryLinks: Map<String, String> = TelegramChannelCategory.emptyCategoryMap(),
  val categoryNames: Map<String, String> = emptyMap(),
  val deletedCategoryKeys: List<String> = emptyList(),
  val customCategories: List<TelegramCategoryItem> = emptyList()
) {
  fun activeBuiltInCategories(): List<TelegramCategoryItem> {
    val deletedSet = deletedCategoryKeys.map { it.trim() }.toSet()
    return TelegramChannelCategory.entries
      .filter { it.key !in deletedSet }
      .map { cat ->
        val customName = categoryNames[cat.key]?.trim().takeIf { !it.isNullOrBlank() } ?: cat.label
        TelegramCategoryItem(
          id = cat.key,
          name = customName,
          link = categoryLinks[cat.key].orEmpty(),
          isBuiltIn = true
        )
      }
  }

  fun allCategories(): List<TelegramCategoryItem> =
    activeBuiltInCategories() + customCategories.map { it.copy(isBuiltIn = false) }
}

data class AppUpdateConfig(
  val isEnabled: Boolean = false,
  val latestVersionCode: Int = 1,
  val latestVersionName: String = "1.0",
  val title: String = "New VidVora Update Available",
  val message: String = "Faster video playback, cleaner share links, and performance improvements are ready. Update now!",
  val apkUrl: String = "https://d1mn8mrvlzisg5.cloudfront.net/vidvora.apk",
  val isForceUpdate: Boolean = false
) {
  fun shouldPromptUpdate(currentVersionCode: Int): Boolean {
    return isEnabled &&
      latestVersionCode > currentVersionCode &&
      apkUrl.trim().let { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
  }
}

object SupportSenderRole {
  const val USER = "user"
  const val ADMIN = "admin"
}

object SupportChatStatus {
  const val OPEN = "OPEN"
  const val RESOLVED = "RESOLVED"
}

data class FirestoreSupportChat(
  val chatId: String = "",
  val userName: String = "User",
  val deviceLabel: String = "Android Device",
  val lastMessage: String = "",
  val lastSenderRole: String = SupportSenderRole.USER,
  val lastMessageAt: Timestamp = Timestamp.now(),
  val createdAt: Timestamp = Timestamp.now(),
  val unreadByAdminCount: Long = 0L,
  val unreadByUserCount: Long = 0L,
  val status: String = SupportChatStatus.OPEN
)

data class FirestoreSupportMessage(
  val messageId: String = "",
  val chatId: String = "",
  val senderRole: String = SupportSenderRole.USER,
  val senderName: String = "User",
  val text: String = "",
  val createdAt: Timestamp = Timestamp.now()
)

fun isEstimatedUninstalled(
  install: FirestoreAppInstall,
  inactiveDaysThreshold: Int = 14,
  nowMs: Long = System.currentTimeMillis()
): Boolean {
  if (install.isMarkedUninstalled) return true
  val safeDays = inactiveDaysThreshold.coerceAtLeast(1)
  val thresholdMs = safeDays * 86_400_000L
  val lastSeenMs = install.lastSeenAt.toDate().time
  return (nowMs - lastSeenMs) > thresholdMs
}

fun computeAppInstallStats(
  installs: List<FirestoreAppInstall>,
  inactiveDaysThreshold: Int = 14,
  nowMs: Long = System.currentTimeMillis()
): AppInstallStats {
  val totalDownloads = installs.size
  val safeDays = inactiveDaysThreshold.coerceAtLeast(1)
  val dayMs = 86_400_000L
  val uninstalledCount = installs.count { isEstimatedUninstalled(it, safeDays, nowMs) }
  val currentlyInstalledCount = (totalDownloads - uninstalledCount).coerceAtLeast(0)
  val downloadsToday = installs.count {
    val age = nowMs - it.firstInstalledAt.toDate().time
    age in 0L..dayMs
  }
  val activeLast24hCount = installs.count {
    !it.isMarkedUninstalled && (nowMs - it.lastSeenAt.toDate().time) in 0L..dayMs
  }
  val retentionPercent = if (totalDownloads > 0) {
    ((currentlyInstalledCount * 100.0) / totalDownloads).toInt().coerceIn(0, 100)
  } else {
    0
  }
  return AppInstallStats(
    totalDownloads = totalDownloads,
    currentlyInstalledCount = currentlyInstalledCount,
    uninstalledCount = uninstalledCount,
    downloadsToday = downloadsToday,
    activeLast24hCount = activeLast24hCount,
    retentionPercent = retentionPercent,
    inactivityWindowDays = safeDays
  )
}

fun formatInr(amount: Double): String {
  val safeAmount = amount.coerceAtLeast(0.0)
  val formatter = NumberFormat.getNumberInstance(Locale("en", "IN"))
  return if (safeAmount % 1.0 == 0.0) {
    formatter.maximumFractionDigits = 0
    "₹${formatter.format(safeAmount.toLong())}"
  } else {
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 2
    "₹${formatter.format(safeAmount)}"
  }
}
