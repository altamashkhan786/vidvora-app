package com.example.ui.screens.admin

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AppInstallStats
import com.example.data.model.AppUpdateConfig
import com.example.data.model.CreatorEmailChangeResult
import com.example.data.model.CreatorReferralSummary
import com.example.data.model.EarningSummary
import com.example.data.model.FirestoreAccessLink
import com.example.data.model.FirestoreAppInstall
import com.example.data.model.FirestoreCreatorEvent
import com.example.data.model.FirestoreMember
import com.example.data.model.FirestoreNotification
import com.example.data.model.FirestoreSupportChat
import com.example.data.model.FirestoreSupportMessage
import com.example.data.model.FirestoreVideo
import com.example.data.model.FirestoreWithdrawalRequest
import com.example.data.model.MemberOption
import com.example.data.model.MemberRole
import com.example.data.model.MemberStats
import com.example.data.model.SupportSenderRole
import com.example.data.model.TelegramCategoryItem
import com.example.data.model.TelegramConfig
import com.example.data.model.WithdrawalStatus
import com.example.data.model.computeAppInstallStats
import com.example.data.model.filterRealAppInstalls
import com.example.data.model.isEmulatorDeviceLabel
import com.example.data.repository.FirebaseAuthAdminRepository
import com.example.data.repository.FirestoreVideoRepository
import com.example.data.repository.VideoCreationResult
import com.example.data.repository.VideoRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AdminViewModel(
  private val firestoreVideoRepository: FirestoreVideoRepository,
  private val localVideoRepository: VideoRepository,
  private val adminAuthRepository: FirebaseAuthAdminRepository
) : ViewModel() {

  private fun millisToTimestamp(millis: Long): Timestamp {
    val safeMs = millis.coerceAtLeast(0L)
    val seconds = safeMs / 1000L
    val nanos = ((safeMs % 1000L) * 1_000_000L).toInt()
    return Timestamp(seconds, nanos)
  }

  // Robust observation: Combines Firestore live updates with local Room database entries
  val videos: StateFlow<List<FirestoreVideo>> = combine(
    firestoreVideoRepository.getAllVideosFlow().catch { emit(emptyList()) },
    localVideoRepository.allVideosFlow
  ) { firestoreList, localList ->
    val firestoreIds = firestoreList.map { it.videoId }.toSet()
    val mappedLocalOnly = localList
      .filter { it.id !in firestoreIds && !it.id.startsWith("seed_") }
      .map { local ->
        FirestoreVideo(
          videoId = local.id,
          title = local.title,
          description = local.description,
          thumbnailPath = local.posterUrl,
          storagePath = local.streamUrl,
          createdAt = millisToTimestamp(local.createdAt),
          isActive = local.isActive,
          totalViews = local.viewCount.toLong(),
          uploadedBy = local.uploadedBy,
          uploaderName = local.uploaderName
        )
      }
    val combined = if (firestoreList.isNotEmpty()) {
      mappedLocalOnly + firestoreList
    } else {
      localList.map { local ->
        FirestoreVideo(
          videoId = local.id,
          title = local.title,
          description = local.description,
          thumbnailPath = local.posterUrl,
          storagePath = local.streamUrl,
          createdAt = millisToTimestamp(local.createdAt),
          isActive = local.isActive,
          totalViews = local.viewCount.toLong(),
          uploadedBy = local.uploadedBy,
          uploaderName = local.uploaderName
        )
      }
    }
    FirestoreVideoRepository.sortVideosNewestFirst(combined)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val accessLinks: StateFlow<List<FirestoreAccessLink>> = combine(
    firestoreVideoRepository.getAllAccessLinksFlow().catch { emit(emptyList()) },
    localVideoRepository.allVideosFlow
  ) { firestoreLinks, localList ->
    val firestoreVideoIds = firestoreLinks.map { it.videoId }.toSet()
    val localOnlyLinks = localList
      .filter { it.id !in firestoreVideoIds && !it.id.startsWith("seed_") }
      .map { local ->
        FirestoreAccessLink(
          linkId = "link_" + local.token.lowercase().replace("-", "_"),
          videoId = local.id,
          tokenHash = firestoreVideoRepository.hashToken(local.token),
          isActive = local.isActive,
          createdAt = Timestamp(local.createdAt / 1000, 0),
          expiresAt = null
        )
      }
    if (firestoreLinks.isNotEmpty()) {
      localOnlyLinks + firestoreLinks
    } else {
      localList.map { local ->
        FirestoreAccessLink(
          linkId = "link_" + local.token.lowercase().replace("-", "_"),
          videoId = local.id,
          tokenHash = firestoreVideoRepository.hashToken(local.token),
          isActive = local.isActive,
          createdAt = Timestamp(local.createdAt / 1000, 0),
          expiresAt = null
        )
      }
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val currentAdminUser: StateFlow<FirebaseUser?> = adminAuthRepository.currentAdminUser
  val isAdminAuthenticated: StateFlow<Boolean> = adminAuthRepository.isAdminAuthenticated
  val adminRole: StateFlow<String?> = adminAuthRepository.adminRole
  val currentMember: StateFlow<FirestoreMember?> = adminAuthRepository.currentMember
  val isLoading: StateFlow<Boolean> = adminAuthRepository.isLoading
  val hasCustomAdminMasterKey: StateFlow<Boolean> = adminAuthRepository.hasCustomAdminMasterKey

  val isSuperAdmin: StateFlow<Boolean> = combine(adminRole, currentMember) { role, member ->
    member == null && (role == null || role == "admin" || role == "superadmin" || role == "admin (dev)")
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

  init {
    viewModelScope.launch {
      try {
        firestoreVideoRepository.getSuperadminMasterKeyHash()?.let { remoteHash ->
          adminAuthRepository.cacheSuperadminKeyHash(remoteHash)
        }
      } catch (_: Exception) {
      }
    }
  }

  val members: StateFlow<List<FirestoreMember>> = firestoreVideoRepository.getAllMembersFlow()
    .catch { emit(emptyList()) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val liveCurrentMember: StateFlow<FirestoreMember?> = combine(currentMember, members) { loggedMember, memberList ->
    if (loggedMember == null) null
    else memberList.firstOrNull { it.memberId == loggedMember.memberId } ?: loggedMember
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val isDelegatedAdmin: StateFlow<Boolean> = combine(adminRole, liveCurrentMember) { role, member ->
    member != null && (member.isDelegatedAdmin() || role == "delegated_admin")
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

  val isCurrentMember: StateFlow<Boolean> = combine(adminRole, liveCurrentMember) { role, member ->
    member != null && !member.isDelegatedAdmin() && role != "delegated_admin"
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

  val effectiveAllowedOptions: StateFlow<Set<String>> = combine(
    isAdminAuthenticated,
    isSuperAdmin,
    liveCurrentMember
  ) { authenticated, superAdmin, member ->
    when {
      !authenticated -> emptySet()
      superAdmin -> MemberOption.allKeys().toSet()
      member != null && member.isActive -> member.allowedOptions.toSet()
      else -> emptySet()
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

  private fun canPerform(option: MemberOption): Boolean {
    if (!isAdminAuthenticated.value) return false
    if (isSuperAdmin.value) return true
    val active = liveCurrentMember.value ?: currentMember.value ?: return false
    return active.isActive && active.hasOption(option)
  }

  val allWithdrawalRequests: StateFlow<List<FirestoreWithdrawalRequest>> =
    firestoreVideoRepository.getAllWithdrawalRequestsFlow()
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val pendingPayoutsCount: StateFlow<Int> = allWithdrawalRequests
    .map { list ->
      list.count {
        it.status == WithdrawalStatus.PENDING || it.status == WithdrawalStatus.PROCESSING
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

  val myWithdrawalRequests: StateFlow<List<FirestoreWithdrawalRequest>> =
    combine(allWithdrawalRequests, liveCurrentMember) { requests, member ->
      if (member == null) emptyList()
      else requests.filter { it.memberId == member.memberId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val myEarningSummary: StateFlow<EarningSummary> =
    combine(liveCurrentMember, myWithdrawalRequests) { member, myRequests ->
      computeEarningSummary(
        totalEarning = member?.totalEarning ?: 0.0,
        requests = myRequests,
        referralBonusEarning = member?.referralBonusEarning ?: 0.0
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EarningSummary())

  val myReferralSummary: StateFlow<CreatorReferralSummary> =
    combine(liveCurrentMember, currentMember, members) { live, auth, allMembers ->
      FirestoreVideoRepository.buildCreatorReferralSummary(live ?: auth, allMembers)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CreatorReferralSummary())

  val memberStatsList: StateFlow<List<MemberStats>> =
    combine(members, videos, allWithdrawalRequests) { memberList, videoList, requests ->
      FirestoreVideoRepository.sortMembersNewestFirst(memberList).map { m ->
        val mVideos = FirestoreVideoRepository.sortVideosNewestFirst(
          videoList.filter { it.uploadedBy == m.memberId }
        )
        val totalViews = mVideos.sumOf { it.totalViews }
        val mRequests = requests.filter { it.memberId == m.memberId }
        val viewsLast24h = FirestoreVideoRepository.computeViewsLast24Hours(m.hourlyViews)
        MemberStats(
          member = m,
          videoCount = mVideos.size,
          totalViews = totalViews,
          videos = mVideos,
          earningSummary = computeEarningSummary(
            totalEarning = m.totalEarning,
            requests = mRequests,
            referralBonusEarning = m.referralBonusEarning
          ),
          viewsLast24Hours = viewsLast24h
        )
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val myMemberVideos: StateFlow<List<FirestoreVideo>> =
    combine(videos, liveCurrentMember, currentMember) { videoList, liveMember, authMember ->
      val active = liveMember ?: authMember
      if (active != null) {
        FirestoreVideoRepository.sortVideosNewestFirst(
          videoList.filter { it.uploadedBy == active.memberId }
        )
      } else {
        emptyList()
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val myTotalViews: StateFlow<Long> = myMemberVideos
    .map { it.sumOf { v -> v.totalViews } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

  val myCreditedViews: StateFlow<Long> = liveCurrentMember
    .map { it?.creditedViews ?: 0L }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

  private val _isUploading = MutableStateFlow(false)
  val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

  private val _uploadProgress = MutableStateFlow(0f)
  val uploadProgress: StateFlow<Float> = _uploadProgress.asStateFlow()

  private val _lastCreatedResult = MutableStateFlow<VideoCreationResult?>(null)
  val lastCreatedResult: StateFlow<VideoCreationResult?> = _lastCreatedResult.asStateFlow()

  private val _toastMessage = MutableStateFlow<String?>(null)
  val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

  val telegramConfig: StateFlow<TelegramConfig> = firestoreVideoRepository.getTelegramConfigFlow()
    .catch { emit(TelegramConfig(mainLink = FirestoreVideoRepository.DEFAULT_TELEGRAM_LINK)) }
    .stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5000),
      TelegramConfig(mainLink = FirestoreVideoRepository.DEFAULT_TELEGRAM_LINK)
    )

  val telegramLink: StateFlow<String> = telegramConfig
    .map { it.mainLink }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FirestoreVideoRepository.DEFAULT_TELEGRAM_LINK)

  val telegramCreatorBotLink: StateFlow<String> = telegramConfig
    .map { it.creatorBotLink }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

  val telegramFinanceBotLink: StateFlow<String> = telegramConfig
    .map { it.financeBotLink }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

  val shareLinkBaseUrl: StateFlow<String> = telegramConfig
    .map { it.shareLinkBaseUrl }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

  val telegramCategoryLinks: StateFlow<Map<String, String>> = telegramConfig
    .map { it.categoryLinks }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

  val telegramCategoryNames: StateFlow<Map<String, String>> = telegramConfig
    .map { it.categoryNames }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

  val telegramDeletedCategoryKeys: StateFlow<List<String>> = telegramConfig
    .map { it.deletedCategoryKeys }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val telegramCustomCategories: StateFlow<List<TelegramCategoryItem>> = telegramConfig
    .map { it.customCategories }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val allTelegramCategories: StateFlow<List<TelegramCategoryItem>> = telegramConfig
    .map { it.allCategories() }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TelegramConfig().allCategories())

  fun updateTelegramConfig(
    mainLink: String,
    categoryLinks: Map<String, String>,
    customCategories: List<TelegramCategoryItem> = emptyList(),
    categoryNames: Map<String, String> = emptyMap(),
    deletedCategoryKeys: List<String> = emptyList(),
    creatorBotLink: String = "",
    financeBotLink: String = "",
    shareLinkBaseUrl: String = "",
    onComplete: (Boolean, String?) -> Unit
  ) {
    if (!canPerform(MemberOption.MANAGE_TELEGRAM)) {
      onComplete(false, "You do not have permission to update Telegram links.")
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.updateTelegramConfig(
        mainLink = mainLink,
        categoryLinks = categoryLinks,
        customCategories = customCategories,
        categoryNames = categoryNames,
        deletedCategoryKeys = deletedCategoryKeys,
        creatorBotLink = creatorBotLink,
        financeBotLink = financeBotLink,
        shareLinkBaseUrl = shareLinkBaseUrl
      )
      if (result.isSuccess) {
        onComplete(true, null)
      } else {
        onComplete(false, result.exceptionOrNull()?.message ?: "Failed to update Telegram links")
      }
    }
  }

  fun updateTelegramLink(newLink: String, onComplete: (Boolean, String?) -> Unit) {
    if (!canPerform(MemberOption.MANAGE_TELEGRAM)) {
      onComplete(false, "You do not have permission to update the Telegram link.")
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.updateTelegramLink(newLink)
      if (result.isSuccess) {
        onComplete(true, null)
      } else {
        onComplete(false, result.exceptionOrNull()?.message ?: "Failed to update Telegram link")
      }
    }
  }

  val appUpdateConfig: StateFlow<AppUpdateConfig> = firestoreVideoRepository.getAppUpdateConfigFlow()
    .catch { emit(AppUpdateConfig()) }
    .stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5000),
      AppUpdateConfig()
    )

  fun updateAppUpdateConfig(
    isEnabled: Boolean,
    latestVersionCode: Int,
    latestVersionName: String,
    title: String,
    message: String,
    apkUrl: String,
    isForceUpdate: Boolean,
    onComplete: (Boolean, String?) -> Unit
  ) {
    if (!isSuperAdmin.value && !isDelegatedAdmin.value && !canPerform(MemberOption.MANAGE_TELEGRAM)) {
      onComplete(false, "Only Admins can update App Update settings.")
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.updateAppUpdateConfig(
        isEnabled = isEnabled,
        latestVersionCode = latestVersionCode,
        latestVersionName = latestVersionName,
        title = title,
        message = message,
        apkUrl = apkUrl,
        isForceUpdate = isForceUpdate
      )
      if (result.isSuccess) {
        _toastMessage.value = if (isEnabled) {
          "In-app update v${latestVersionName.trim()} (Build $latestVersionCode) published!"
        } else {
          "In-app update popup disabled."
        }
        onComplete(true, null)
      } else {
        onComplete(false, result.exceptionOrNull()?.message ?: "Failed to save update settings")
      }
    }
  }

  fun authenticateWithPasskey(passkey: String, email: String? = null): Boolean {
    return adminAuthRepository.authenticateWithPasskey(passkey, email)
  }

  suspend fun authenticateMemberOrAdmin(email: String, passkey: String): Boolean {
    return adminAuthRepository.authenticateMemberOrAdmin(email, passkey, firestoreVideoRepository)
  }

  suspend fun directSignupAndLogin(
    name: String,
    email: String,
    password: String,
    confirmPassword: String,
    referralCode: String = ""
  ): Result<FirestoreMember> {
    val result = firestoreVideoRepository.directSignupMember(
      name = name,
      email = email,
      password = password,
      confirmPassword = confirmPassword,
      referralCode = referralCode
    )
    result.getOrNull()?.let { member ->
      adminAuthRepository.signInMemberDirectly(member)
    }
    return result
  }

  suspend fun sendSignupOtp(name: String, email: String, password: String): Result<String> {
    return firestoreVideoRepository.sendSignupOtp(name, email, password)
  }

  suspend fun verifySignupOtpAndLogin(
    name: String,
    email: String,
    password: String,
    otp: String,
    referralCode: String = ""
  ): Result<FirestoreMember> {
    val result = firestoreVideoRepository.verifySignupOtpAndCreateMember(
      name = name,
      email = email,
      password = password,
      otp = otp,
      referralCode = referralCode
    )
    result.getOrNull()?.let { member ->
      adminAuthRepository.signInMemberDirectly(member)
    }
    return result
  }

  suspend fun sendResetPasswordOtp(email: String): Result<String> {
    return firestoreVideoRepository.sendResetPasswordOtp(email)
  }

  suspend fun verifyResetPasswordOtpAndLogin(
    email: String,
    otp: String,
    newPassword: String
  ): Result<FirestoreMember> {
    val result = firestoreVideoRepository.verifyResetPasswordOtp(email, otp, newPassword)
    result.getOrNull()?.let { member ->
      adminAuthRepository.signInMemberDirectly(member)
    }
    return result
  }

  suspend fun signInWithGoogle(context: Context): Result<FirebaseUser> {
    return adminAuthRepository.signInWithGoogle(context)
  }

  val notifications: StateFlow<List<FirestoreNotification>> =
    firestoreVideoRepository.getNotificationsFlow()
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val creatorEvents: StateFlow<List<FirestoreCreatorEvent>> =
    firestoreVideoRepository.getCreatorEventsFlow()
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val activeCreatorEvents: StateFlow<List<FirestoreCreatorEvent>> = creatorEvents
    .map { list -> list.filter { it.isActive } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val rawAppInstalls: StateFlow<List<FirestoreAppInstall>> =
    firestoreVideoRepository.getAllAppInstallsFlow()
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val appInstalls: StateFlow<List<FirestoreAppInstall>> =
    rawAppInstalls
      .map { list -> filterRealAppInstalls(list) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val emulatorInstallsCount: StateFlow<Int> =
    rawAppInstalls
      .map { list -> list.count { isEmulatorDeviceLabel(it.deviceLabel) } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

  val supportChats: StateFlow<List<FirestoreSupportChat>> =
    firestoreVideoRepository.observeAllSupportChats()
      .catch { emit(emptyList()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val unreadSupportChatsCount: StateFlow<Int> = supportChats
    .map { list -> list.count { it.unreadByAdminCount > 0L } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

  fun observeSupportChatMessages(chatId: String): Flow<List<FirestoreSupportMessage>> =
    firestoreVideoRepository.observeSupportMessages(chatId).catch { emit(emptyList()) }

  val myCreatorSupportChatId: StateFlow<String> = combine(liveCurrentMember, isCurrentMember) { member, isCreator ->
    if (member != null && isCreator && member.isActive) {
      FirestoreVideoRepository.buildCreatorSupportChatId(member.memberId)
    } else {
      ""
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

  @OptIn(ExperimentalCoroutinesApi::class)
  val myCreatorSupportChat: StateFlow<FirestoreSupportChat?> = myCreatorSupportChatId
    .flatMapLatest { chatId ->
      if (chatId.isBlank()) flowOf(null)
      else firestoreVideoRepository.observeSupportChat(chatId)
    }
    .catch { emit(null) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  @OptIn(ExperimentalCoroutinesApi::class)
  val myCreatorSupportMessages: StateFlow<List<FirestoreSupportMessage>> = myCreatorSupportChatId
    .flatMapLatest { chatId ->
      if (chatId.isBlank()) flowOf(emptyList())
      else firestoreVideoRepository.observeSupportMessages(chatId)
    }
    .catch { emit(emptyList()) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  fun sendCreatorSupportMessage(
    text: String,
    onResult: (Boolean, String?) -> Unit
  ) {
    val member = liveCurrentMember.value ?: currentMember.value
    if (member == null || !member.isActive || member.isDelegatedAdmin()) {
      onResult(false, "Only active Creators can send a support request here.")
      return
    }
    val chatId = FirestoreVideoRepository.buildCreatorSupportChatId(member.memberId)
    if (chatId.isBlank()) {
      onResult(false, "Creator session not found.")
      return
    }
    val creatorDisplayName = "${member.name.trim().ifBlank { "Creator" }} (Creator)".take(80)
    val creatorDeviceLabel = "Creator • ${member.emailOrPhone.trim().ifBlank { member.memberId }}".take(100)
    viewModelScope.launch {
      val result = firestoreVideoRepository.sendSupportMessage(
        chatId = chatId,
        senderRole = SupportSenderRole.USER,
        senderName = creatorDisplayName,
        text = text,
        deviceLabelOverride = creatorDeviceLabel
      )
      if (result.isSuccess) {
        onResult(true, null)
      } else {
        onResult(false, result.exceptionOrNull()?.message ?: "Failed to send support message")
      }
    }
  }

  fun markCreatorSupportChatRead() {
    val chatId = myCreatorSupportChatId.value
    if (chatId.isBlank()) return
    viewModelScope.launch {
      firestoreVideoRepository.markSupportChatReadByUser(chatId)
    }
  }

  fun sendAdminSupportReply(
    chatId: String,
    text: String,
    onResult: (Boolean, String?) -> Unit
  ) {
    if (!canPerform(MemberOption.MANAGE_SUPPORT_CHAT)) {
      onResult(false, "You do not have permission to reply to support chats.")
      return
    }
    val adminLabel = FirestoreVideoRepository.SUPPORT_TEAM_NAME
    viewModelScope.launch {
      val result = firestoreVideoRepository.sendSupportMessage(
        chatId = chatId,
        senderRole = SupportSenderRole.ADMIN,
        senderName = adminLabel,
        text = text
      )
      if (result.isSuccess) {
        onResult(true, null)
      } else {
        onResult(false, result.exceptionOrNull()?.message ?: "Failed to send reply")
      }
    }
  }

  fun markSupportChatReadByAdmin(chatId: String) {
    if (!canPerform(MemberOption.MANAGE_SUPPORT_CHAT)) return
    viewModelScope.launch {
      firestoreVideoRepository.markSupportChatReadByAdmin(chatId)
    }
  }

  fun updateSupportChatStatus(
    chatId: String,
    status: String,
    onResult: (Boolean, String?) -> Unit = { _, _ -> }
  ) {
    if (!canPerform(MemberOption.MANAGE_SUPPORT_CHAT)) {
      onResult(false, "You do not have permission to update support status.")
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.updateSupportChatStatus(chatId, status)
      if (result.isSuccess) {
        _toastMessage.value = "Support chat marked $status"
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to update support status"
        onResult(false, err)
      }
    }
  }

  fun deleteSupportChat(chatId: String) {
    if (!canPerform(MemberOption.MANAGE_SUPPORT_CHAT)) {
      _toastMessage.value = "You do not have permission to delete support chats."
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.deleteSupportChat(chatId)
      if (result.isSuccess) {
        _toastMessage.value = "Support chat deleted"
      } else {
        _toastMessage.value = "Failed to delete support chat"
      }
    }
  }

  private val _inactivityWindowDays = MutableStateFlow(14)
  val inactivityWindowDays: StateFlow<Int> = _inactivityWindowDays.asStateFlow()

  val appInstallStats: StateFlow<AppInstallStats> =
    combine(appInstalls, inactivityWindowDays) { installs, days ->
      computeAppInstallStats(installs = installs, inactiveDaysThreshold = days)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppInstallStats())

  fun setInactivityWindowDays(days: Int) {
    _inactivityWindowDays.value = days.coerceIn(1, 365)
  }

  fun toggleInstallMarkedUninstalled(installId: String, markUninstalled: Boolean) {
    if (!canPerform(MemberOption.VIEW_APP_STATS)) {
      _toastMessage.value = "You do not have permission to update install records."
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.setInstallMarkedUninstalled(installId, markUninstalled)
      if (result.isSuccess) {
        _toastMessage.value = if (markUninstalled) "Marked as uninstalled" else "Restored as active install"
      } else {
        _toastMessage.value = "Failed to update install record"
      }
    }
  }

  fun deleteAppInstallRecord(installId: String) {
    if (!canPerform(MemberOption.VIEW_APP_STATS)) {
      _toastMessage.value = "You do not have permission to remove install records."
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.deleteAppInstallRecord(installId)
      if (result.isSuccess) {
        _toastMessage.value = "Install record removed"
      } else {
        _toastMessage.value = "Failed to remove install record"
      }
    }
  }

  fun cleanupEmulatorInstallRecords() {
    if (!canPerform(MemberOption.VIEW_APP_STATS)) {
      _toastMessage.value = "You do not have permission to clean up install records."
      return
    }
    val emulatorIds = rawAppInstalls.value
      .filter { isEmulatorDeviceLabel(it.deviceLabel) }
      .map { it.installId }
      .filter { it.isNotBlank() }
    if (emulatorIds.isEmpty()) {
      _toastMessage.value = "No emulator test records found"
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.deleteAppInstallRecordsBatch(emulatorIds)
      if (result.isSuccess) {
        val removed = result.getOrDefault(0)
        _toastMessage.value = "Cleaned $removed emulator test record${if (removed == 1) "" else "s"}"
      } else {
        _toastMessage.value = "Failed to clean emulator records"
      }
    }
  }

  fun createCreatorEvent(
    title: String,
    description: String,
    giftLabel: String = "",
    imageUri: Uri? = null,
    imageUrl: String = "",
    isActive: Boolean = true,
    onResult: (Boolean, String?) -> Unit
  ) {
    if (!canPerform(MemberOption.MANAGE_EVENTS)) {
      onResult(false, "You do not have permission to create Creator Events.")
      return
    }
    val senderLabel = when {
      isSuperAdmin.value -> "Superadmin"
      else -> (liveCurrentMember.value ?: currentMember.value)?.name?.ifBlank { "Admin" } ?: "Admin"
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.createCreatorEvent(
        title = title,
        description = description,
        giftLabel = giftLabel,
        imageUri = imageUri,
        imageUrlInput = imageUrl,
        isActive = isActive,
        createdBy = senderLabel
      )
      if (result.isSuccess) {
        _toastMessage.value = "Creator Event '${title.trim()}' published!"
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to create event"
        onResult(false, err)
      }
    }
  }

  fun updateCreatorEvent(
    eventId: String,
    title: String,
    description: String,
    giftLabel: String = "",
    imageUri: Uri? = null,
    imageUrl: String = "",
    isActive: Boolean = true,
    onResult: (Boolean, String?) -> Unit
  ) {
    if (!canPerform(MemberOption.MANAGE_EVENTS)) {
      onResult(false, "You do not have permission to edit Creator Events.")
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.updateCreatorEvent(
        eventId = eventId,
        title = title,
        description = description,
        giftLabel = giftLabel,
        imageUri = imageUri,
        imageUrlInput = imageUrl,
        isActive = isActive
      )
      if (result.isSuccess) {
        _toastMessage.value = "Creator Event updated!"
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to update event"
        onResult(false, err)
      }
    }
  }

  fun deleteCreatorEvent(eventId: String) {
    if (!canPerform(MemberOption.MANAGE_EVENTS)) {
      _toastMessage.value = "You do not have permission to delete Creator Events."
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.deleteCreatorEvent(eventId)
      if (result.isSuccess) {
        _toastMessage.value = "Creator Event deleted"
      } else {
        _toastMessage.value = "Failed to delete Creator Event"
      }
    }
  }

  fun addNewMember(
    name: String,
    emailOrPhone: String,
    passkey: String,
    role: String = MemberRole.CREATOR,
    allowedOptions: List<String> = MemberOption.defaultKeysForRole(role),
    referralCode: String = "",
    onResult: (Boolean, String?) -> Unit
  ) {
    if (!canPerform(MemberOption.MANAGE_MEMBERS)) {
      onResult(false, "You do not have permission to add members.")
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.addMember(
        name = name,
        emailOrPhone = emailOrPhone,
        passkey = passkey,
        role = role,
        allowedOptions = allowedOptions,
        referralCode = referralCode
      )
      if (result.isSuccess) {
        val roleLabel = MemberRole.displayLabel(role)
        _toastMessage.value = "$roleLabel '${name.trim()}' added successfully!"
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to add member"
        _toastMessage.value = "Error: $err"
        onResult(false, err)
      }
    }
  }

  fun updateMemberDetails(
    memberId: String,
    name: String,
    emailOrPhone: String,
    passkey: String,
    isActive: Boolean,
    role: String? = null,
    allowedOptions: List<String>? = null,
    onResult: (Boolean, String?) -> Unit
  ) {
    if (!canPerform(MemberOption.MANAGE_MEMBERS)) {
      onResult(false, "You do not have permission to edit member details.")
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.updateMemberDetails(
        memberId = memberId,
        name = name,
        emailOrPhone = emailOrPhone,
        passkey = passkey,
        isActive = isActive,
        role = role,
        allowedOptions = allowedOptions
      )
      val updated = result.getOrNull()
      if (result.isSuccess && updated != null) {
        if (currentMember.value?.memberId == updated.memberId) {
          adminAuthRepository.updateCurrentMember(updated)
        }
        _toastMessage.value = "Member '${name.trim()}' updated successfully!"
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to update member details"
        _toastMessage.value = "Error: $err"
        onResult(false, err)
      }
    }
  }

  fun changeCreatorEmail(
    sourceMemberId: String,
    newEmail: String,
    onResult: (Boolean, String?, CreatorEmailChangeResult?) -> Unit
  ) {
    if (!canPerform(MemberOption.MANAGE_MEMBERS)) {
      onResult(false, "You do not have permission to change member email.", null)
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.changeCreatorEmail(
        sourceMemberId = sourceMemberId,
        newEmail = newEmail
      )
      val summary = result.getOrNull()
      if (result.isSuccess && summary != null) {
        if (currentMember.value?.memberId == summary.member.memberId) {
          adminAuthRepository.updateCurrentMember(summary.member)
        }
        _toastMessage.value = if (summary.mergedExistingAccount) {
          "Purana data (${summary.migratedVideosCount} videos) & earning ${summary.updatedEmail} par transfer ho gaya!"
        } else {
          "Email updated to ${summary.updatedEmail} — all videos, views & earnings intact!"
        }
        onResult(true, null, summary)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to change email"
        _toastMessage.value = "Error: $err"
        onResult(false, err, null)
      }
    }
  }

  fun changeOwnMasterKey(
    currentPasskey: String,
    newPasskey: String,
    confirmPasskey: String,
    onResult: (Boolean, String?) -> Unit
  ) {
    if (!isAdminAuthenticated.value) {
      onResult(false, "Please sign in first.")
      return
    }
    if (isSuperAdmin.value) {
      viewModelScope.launch {
        val result = firestoreVideoRepository.changeSuperadminMasterKey(
          currentPasskey = currentPasskey,
          newPasskey = newPasskey,
          confirmPasskey = confirmPasskey,
          localFallbackHash = adminAuthRepository.getCachedSuperadminKeyHash()
        )
        val newHash = result.getOrNull()
        if (result.isSuccess && newHash != null) {
          adminAuthRepository.cacheSuperadminKeyHash(newHash)
          _toastMessage.value = "Admin Password updated successfully!"
          onResult(true, null)
        } else {
          val err = result.exceptionOrNull()?.message ?: "Failed to update Admin Password"
          onResult(false, err)
        }
      }
      return
    }

    val member = liveCurrentMember.value ?: currentMember.value
    if (member == null || !canPerform(MemberOption.CHANGE_MASTER_KEY)) {
      onResult(false, "You do not have permission to change Password.")
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.changeMemberPasskey(
        memberId = member.memberId,
        currentPasskey = currentPasskey,
        newPasskey = newPasskey,
        confirmPasskey = confirmPasskey
      )
      val updated = result.getOrNull()
      if (result.isSuccess && updated != null) {
        adminAuthRepository.updateCurrentMember(updated)
        _toastMessage.value = "Password updated successfully!"
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to update Password"
        onResult(false, err)
      }
    }
  }

  fun sendBroadcastNotification(
    title: String,
    message: String,
    imageUri: Uri? = null,
    imageUrl: String = "",
    actionUrl: String = "",
    onResult: (Boolean, String?) -> Unit
  ) {
    if (!canPerform(MemberOption.SEND_NOTIFICATIONS)) {
      onResult(false, "You do not have permission to send app notifications.")
      return
    }
    val senderLabel = liveCurrentMember.value?.name?.takeIf { it.isNotBlank() }
      ?: currentAdminUser.value?.email
      ?: "Admin"
    viewModelScope.launch {
      val result = firestoreVideoRepository.sendBroadcastNotification(
        title = title,
        message = message,
        imageUri = imageUri,
        imageUrlInput = imageUrl,
        actionUrl = actionUrl,
        sentBy = senderLabel
      )
      if (result.isSuccess) {
        _toastMessage.value = "Notification sent to all VidVora downloaders!"
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to send notification"
        onResult(false, err)
      }
    }
  }

  fun deleteNotification(notificationId: String) {
    if (!canPerform(MemberOption.SEND_NOTIFICATIONS)) {
      _toastMessage.value = "You do not have permission to delete notifications."
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.deleteNotification(notificationId)
      if (result.isSuccess) {
        _toastMessage.value = "Notification deleted"
      } else {
        _toastMessage.value = "Failed to delete notification"
      }
    }
  }

  fun toggleMemberStatus(memberId: String, isActive: Boolean) {
    if (!canPerform(MemberOption.MANAGE_MEMBERS)) {
      _toastMessage.value = "You do not have permission to manage members."
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.toggleMemberActive(memberId, isActive)
      if (result.isSuccess) {
        _toastMessage.value = if (isActive) "Member activated" else "Member deactivated"
      }
    }
  }

  fun deleteMember(memberId: String) {
    if (!canPerform(MemberOption.MANAGE_MEMBERS)) {
      _toastMessage.value = "You do not have permission to delete members."
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.deleteMember(memberId)
      if (result.isSuccess) {
        _toastMessage.value = "Member deleted"
      } else {
        _toastMessage.value = "Failed to delete member: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
      }
    }
  }

  fun updateCreatorEarning(
    memberId: String,
    amount: Double,
    isAddition: Boolean,
    onResult: (Boolean, String?) -> Unit
  ) {
    if (!canPerform(MemberOption.MANAGE_PAYOUTS)) {
      onResult(false, "You do not have permission to update earnings.")
      return
    }
    val member = members.value.firstOrNull { it.memberId == memberId }
    val base = member?.totalEarning ?: 0.0
    val newTotal = if (isAddition) (base + amount) else amount
    if (newTotal < 0.0) {
      onResult(false, "Earning cannot be negative")
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.updateMemberEarning(memberId, newTotal)
      if (result.isSuccess) {
        val refBonus = result.getOrNull()
        _toastMessage.value = if (refBonus != null && refBonus.second > 0.0) {
          "Creator earning updated! +${com.example.data.model.formatInr(refBonus.second)} (7% Admin bonus) → ${refBonus.first}"
        } else {
          "Creator earning updated!"
        }
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to update earning"
        _toastMessage.value = "Error: $err"
        onResult(false, err)
      }
    }
  }

  fun creditCreatorViews(
    memberId: String,
    views: Long,
    ratePer1000: Double,
    isAddition: Boolean,
    onResult: (Boolean, String?) -> Unit
  ) {
    if (!canPerform(MemberOption.MANAGE_PAYOUTS)) {
      onResult(false, "You do not have permission to add views or earnings.")
      return
    }
    if (views < 0L || (isAddition && views <= 0L)) {
      onResult(false, "Please enter a valid views count.")
      return
    }
    if (ratePer1000 < 0.0) {
      onResult(false, "Rate per 1,000 views cannot be negative.")
      return
    }
    val previousCommission = members.value.firstOrNull { it.memberId == memberId }?.referralCommissionGenerated ?: 0.0
    viewModelScope.launch {
      val result = firestoreVideoRepository.creditCreatorViewsAndEarning(
        memberId = memberId,
        viewsInput = views,
        ratePer1000 = ratePer1000,
        isAddition = isAddition
      )
      val updated = result.getOrNull()
      if (result.isSuccess && updated != null) {
        if (currentMember.value?.memberId == updated.memberId) {
          adminAuthRepository.updateCurrentMember(updated)
        }
        val calculatedInr = FirestoreVideoRepository.calculateViewsEarning(views, ratePer1000)
        val bonusAdded = (updated.referralCommissionGenerated - previousCommission).coerceAtLeast(0.0)
        val refSuffix = if (bonusAdded > 0.0 && updated.referredByName.isNotBlank()) {
          " • +${com.example.data.model.formatInr(bonusAdded)} (7% Admin bonus → ${updated.referredByName})"
        } else ""
        _toastMessage.value = if (isAddition) {
          "+$views views (+${com.example.data.model.formatInr(calculatedInr)}) credited!$refSuffix"
        } else {
          "Creator views set to $views (${com.example.data.model.formatInr(calculatedInr)})!$refSuffix"
        }
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to credit views"
        _toastMessage.value = "Error: $err"
        onResult(false, err)
      }
    }
  }

  fun updateDailyCreatorViews(
    memberId: String,
    views: Long,
    ratePer1000: Double,
    isAddition: Boolean,
    cycleKey: String,
    onResult: (Boolean, String?) -> Unit
  ) {
    if (!isSuperAdmin.value) {
      onResult(false, "Sirf Superadmin hi daily views update kar sakta hai.")
      return
    }
    if (views < 0L) {
      onResult(false, "Please enter a valid views count (0 or more).")
      return
    }
    if (ratePer1000 < 0.0) {
      onResult(false, "Rate per 1,000 views cannot be negative.")
      return
    }
    val resolvedCycleKey = cycleKey.trim().ifBlank { DailyViewsResetClock.currentCycleKeyIst() }
    val updatedAtMs = System.currentTimeMillis()
    val previousCommission = members.value.firstOrNull { it.memberId == memberId }?.referralCommissionGenerated ?: 0.0
    viewModelScope.launch {
      val result = firestoreVideoRepository.creditCreatorViewsAndEarning(
        memberId = memberId,
        viewsInput = views,
        ratePer1000 = ratePer1000,
        isAddition = isAddition,
        dailyViewsCycleKey = resolvedCycleKey,
        dailyViewsUpdatedAtMs = updatedAtMs,
        allowZeroAddition = true
      )
      val updated = result.getOrNull()
      if (result.isSuccess && updated != null) {
        if (currentMember.value?.memberId == updated.memberId) {
          adminAuthRepository.updateCurrentMember(updated)
        }
        val calculatedInr = FirestoreVideoRepository.calculateViewsEarning(views, ratePer1000)
        val displayName = updated.name.ifBlank { "Member" }
        val bonusAdded = (updated.referralCommissionGenerated - previousCommission).coerceAtLeast(0.0)
        val refSuffix = if (bonusAdded > 0.0 && updated.referredByName.isNotBlank()) {
          " • +${com.example.data.model.formatInr(bonusAdded)} (7% Admin bonus → ${updated.referredByName})"
        } else ""
        _toastMessage.value = when {
          isAddition && views == 0L ->
            "$displayName done mark ho gaya (11:50 PM IST par wapas show hoga)"
          isAddition ->
            "$displayName: +$views views (+${com.example.data.model.formatInr(calculatedInr)}) updated!$refSuffix"
          else ->
            "$displayName: Views set to $views (${com.example.data.model.formatInr(calculatedInr)})!$refSuffix"
        }
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to update daily views"
        _toastMessage.value = "Error: $err"
        onResult(false, err)
      }
    }
  }

  fun reopenDailyViewsMember(memberId: String, memberName: String) {
    if (!isSuperAdmin.value) {
      _toastMessage.value = "Sirf Superadmin hi profile re-open kar sakta hai."
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.resetMemberDailyViewsCycle(memberId)
      if (result.isSuccess) {
        val label = memberName.ifBlank { "Profile" }
        _toastMessage.value = "$label wapas pending list me show ho gaya!"
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to re-open profile"
        _toastMessage.value = "Error: $err"
      }
    }
  }

  fun requestCreatorWithdrawal(
    amount: Double,
    upiId: String,
    onResult: (Boolean, String?) -> Unit
  ) {
    val member = liveCurrentMember.value ?: currentMember.value
    if (member == null || !canPerform(MemberOption.REQUEST_WITHDRAWAL)) {
      onResult(false, "You do not have permission to request withdrawals.")
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.submitWithdrawalRequest(member, amount, upiId)
      if (result.isSuccess) {
        _toastMessage.value = "Withdrawal request submitted!"
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to submit withdrawal request"
        _toastMessage.value = err
        onResult(false, err)
      }
    }
  }

  fun updateWithdrawalStatus(
    requestId: String,
    newStatus: String,
    adminNote: String,
    onResult: (Boolean, String?) -> Unit = { _, _ -> }
  ) {
    if (!canPerform(MemberOption.MANAGE_PAYOUTS)) {
      onResult(false, "You do not have permission to manage payouts.")
      return
    }
    viewModelScope.launch {
      val result = firestoreVideoRepository.updateWithdrawalStatus(requestId, newStatus, adminNote)
      if (result.isSuccess) {
        val label = when (newStatus) {
          WithdrawalStatus.PROCESSING -> "Marked as Payment Processing"
          WithdrawalStatus.DONE -> "Marked as Payment Done!"
          WithdrawalStatus.REJECTED -> "Withdrawal request rejected"
          else -> "Status updated"
        }
        _toastMessage.value = label
        onResult(true, null)
      } else {
        val err = result.exceptionOrNull()?.message ?: "Failed to update status"
        _toastMessage.value = "Error: $err"
        onResult(false, err)
      }
    }
  }

  private fun computeEarningSummary(
    totalEarning: Double,
    requests: List<FirestoreWithdrawalRequest>,
    referralBonusEarning: Double = 0.0
  ): EarningSummary {
    val safeTotal = totalEarning.coerceAtLeast(0.0)
    val safeReferralBonus = referralBonusEarning.coerceAtLeast(0.0).coerceAtMost(safeTotal)
    val paid = requests
      .filter { it.status == WithdrawalStatus.DONE }
      .sumOf { it.amount }
    val pendingOrProcessing = requests
      .filter { it.status == WithdrawalStatus.PENDING || it.status == WithdrawalStatus.PROCESSING }
      .sumOf { it.amount }
    val available = (safeTotal - paid - pendingOrProcessing).coerceAtLeast(0.0)
    return EarningSummary(
      totalEarning = safeTotal,
      pendingOrProcessingAmount = pendingOrProcessing,
      paidAmount = paid,
      availableBalance = available,
      referralBonusEarning = safeReferralBonus
    )
  }

  fun uploadVideoAndCreateRecord(
    title: String,
    description: String,
    videoUri: Uri?,
    thumbnailUri: Uri?,
    manualStreamUrl: String?,
    customToken: String?,
    customUploaderId: String? = null,
    customUploaderName: String? = null,
    onComplete: (VideoCreationResult) -> Unit
  ) {
    if (!canPerform(MemberOption.UPLOAD_VIDEOS)) {
      _toastMessage.value = "You do not have permission to upload videos."
      return
    }
    val member = liveCurrentMember.value ?: currentMember.value
    val finalUploaderId = customUploaderId ?: member?.memberId ?: "admin"
    val finalUploaderName = customUploaderName ?: member?.name ?: "Admin"

    viewModelScope.launch {
      _isUploading.value = true
      _uploadProgress.value = 0f
      try {
        // Attempt cloud upload to Firebase Storage & Firestore
        val result = firestoreVideoRepository.uploadAndCreateVideo(
          title = title,
          description = description,
          videoUri = videoUri,
          thumbnailUri = thumbnailUri,
          manualStreamUrl = manualStreamUrl,
          customToken = customToken,
          uploadedBy = finalUploaderId,
          uploaderName = finalUploaderName,
          onProgress = { progress ->
            _uploadProgress.value = progress
          }
        )

        // Cache created record in local Room database using matching videoId
        try {
          localVideoRepository.createVideoToken(
            title = title,
            description = description,
            streamUrl = result.video.storagePath,
            posterUrl = result.video.thumbnailPath,
            customToken = result.rawToken,
            customId = result.video.videoId
          )
        } catch (_: Exception) {}

        if (finalUploaderId != "admin") {
          adminAuthRepository.recordCreatorUploadedVideoId(result.video.videoId)
        }

        _lastCreatedResult.value = result
        _toastMessage.value = "Video uploaded and shareable link generated!"
        _isUploading.value = false
        onComplete(result)
      } catch (e: Exception) {
        // Offline/local fallback: persist the actual selected video/URL locally instead of falling back to sample media
        try {
          val fallbackVideoId = "vid_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
          val localVideoPath = if (videoUri != null) {
            firestoreVideoRepository.saveLocalMediaCopy("$fallbackVideoId.mp4", videoUri)
          } else {
            null
          }
          val resolvedFallbackUrl = manualStreamUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: localVideoPath
            ?: throw IllegalStateException("Upload failed: ${e.message ?: "Unable to access video source"}")
          val localThumbPath = firestoreVideoRepository.resolveFallbackVideoThumbnail(
            videoId = fallbackVideoId,
            videoUri = videoUri,
            remoteStreamUrl = resolvedFallbackUrl,
            thumbnailUri = thumbnailUri
          )

          val localToken = localVideoRepository.createVideoToken(
            title = title,
            description = description,
            streamUrl = resolvedFallbackUrl,
            posterUrl = localThumbPath,
            customToken = customToken,
            customId = fallbackVideoId
          )

          val fallbackResult = VideoCreationResult(
            video = FirestoreVideo(
              videoId = localToken.id,
              title = localToken.title,
              description = localToken.description,
              thumbnailPath = localToken.posterUrl,
              storagePath = localToken.streamUrl,
              createdAt = Timestamp.now(),
              isActive = true,
              totalViews = 0L,
              uploadedBy = finalUploaderId,
              uploaderName = finalUploaderName
            ),
            accessLink = FirestoreAccessLink(
              linkId = "link_" + localToken.token.lowercase().replace("-", "_"),
              videoId = localToken.id,
              tokenHash = firestoreVideoRepository.hashToken(localToken.token),
              isActive = true,
              createdAt = Timestamp.now(),
              expiresAt = null
            ),
            rawToken = localToken.token,
            shareableLink = FirestoreVideoRepository.buildShareableVideoUrl(localToken.token, shareLinkBaseUrl.value)
          )

          if (finalUploaderId != "admin") {
            adminAuthRepository.recordCreatorUploadedVideoId(localToken.id)
          }

          _lastCreatedResult.value = fallbackResult
          _toastMessage.value = "Video saved locally and link generated!"
          _isUploading.value = false
          onComplete(fallbackResult)
        } catch (localError: Exception) {
          _isUploading.value = false
          _toastMessage.value = "Upload failed: ${e.message ?: localError.message}"
        }
      }
    }
  }

  fun editVideo(videoId: String, newTitle: String, newDescription: String) {
    if (!canPerform(MemberOption.MANAGE_VIDEOS)) {
      _toastMessage.value = "You do not have permission to edit videos."
      return
    }
    viewModelScope.launch {
      try {
        firestoreVideoRepository.updateVideoMetadata(videoId, newTitle, newDescription)
        _toastMessage.value = "Video metadata updated"
      } catch (e: Exception) {
        _toastMessage.value = "Failed to update: ${e.message}"
      }
    }
  }

  fun toggleVideoStatus(video: FirestoreVideo) {
    if (!canPerform(MemberOption.MANAGE_VIDEOS)) {
      _toastMessage.value = "You do not have permission to update video status."
      return
    }
    viewModelScope.launch {
      try {
        val newStatus = !video.isActive
        try {
          firestoreVideoRepository.toggleVideoActive(video.videoId, newStatus)
        } catch (_: Exception) {}
        try {
          localVideoRepository.toggleActiveStatus(video.videoId, newStatus)
        } catch (_: Exception) {}
        _toastMessage.value = if (newStatus) "Video activated" else "Video deactivated"
      } catch (e: Exception) {
        _toastMessage.value = "Error updating status: ${e.message}"
      }
    }
  }

  fun toggleLinkStatus(link: FirestoreAccessLink) {
    if (!canPerform(MemberOption.MANAGE_VIDEOS)) {
      _toastMessage.value = "You do not have permission to update access links."
      return
    }
    viewModelScope.launch {
      try {
        val newStatus = !link.isActive
        try {
          firestoreVideoRepository.toggleLinkActive(link.linkId, newStatus)
        } catch (_: Exception) {}
        try {
          localVideoRepository.toggleActiveStatus(link.videoId, newStatus)
        } catch (_: Exception) {}
        _toastMessage.value = if (newStatus) "Access link activated" else "Access link deactivated"
      } catch (e: Exception) {
        _toastMessage.value = "Error updating link: ${e.message}"
      }
    }
  }

  fun deleteVideo(videoId: String) {
    if (!isSuperAdmin.value && !(isDelegatedAdmin.value && canPerform(MemberOption.MANAGE_VIDEOS))) {
      _toastMessage.value = "You do not have permission to delete videos."
      return
    }
    viewModelScope.launch {
      try {
        try {
          firestoreVideoRepository.deleteVideoAndAssociatedData(videoId)
        } catch (_: Exception) {}
        try {
          localVideoRepository.deleteVideo(videoId)
        } catch (_: Exception) {}
        _toastMessage.value = "Video and associated data deleted"
      } catch (e: Exception) {
        _toastMessage.value = "Failed to delete: ${e.message}"
      }
    }
  }

  fun deleteLink(linkId: String) {
    if (!canPerform(MemberOption.MANAGE_VIDEOS)) {
      _toastMessage.value = "You do not have permission to delete access links."
      return
    }
    viewModelScope.launch {
      try {
        firestoreVideoRepository.deleteLink(linkId)
        _toastMessage.value = "Access link deleted"
      } catch (e: Exception) {
        _toastMessage.value = "Failed to delete link: ${e.message}"
      }
    }
  }

  fun prepareVideoForPlayback(
    video: FirestoreVideo,
    onError: (String) -> Unit,
    onSuccess: (com.example.data.model.VideoToken) -> Unit
  ) {
    viewModelScope.launch {
      val viewerMemberId = if (isCurrentMember.value) {
        liveCurrentMember.value?.memberId
          ?: currentMember.value?.memberId
          ?: adminAuthRepository.getActiveCreatorMemberId()
      } else {
        adminAuthRepository.getActiveCreatorMemberId()
      }
      when (val result = firestoreVideoRepository.resolveDirectVideoForPlayback(video, viewerMemberId)) {
        is com.example.data.model.TokenValidationResult.Success -> {
          try {
            localVideoRepository.recordWatchHistory(result.video)
          } catch (_: Exception) {}
          onSuccess(result.video)
        }
        is com.example.data.model.TokenValidationResult.Error -> {
          onError(result.message)
        }
        is com.example.data.model.TokenValidationResult.TokenDeactivated -> {
          onError("This video is currently deactivated.")
        }
        is com.example.data.model.TokenValidationResult.TokenNotFound -> {
          onError("Video source not found.")
        }
      }
    }
  }

  fun clearLastCreatedResult() {
    _lastCreatedResult.value = null
  }

  fun clearToast() {
    _toastMessage.value = null
  }

  fun logout() {
    adminAuthRepository.signOut()
  }
}
