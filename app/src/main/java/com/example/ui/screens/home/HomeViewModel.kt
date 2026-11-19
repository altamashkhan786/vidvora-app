package com.example.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.WatchHistoryEntity
import com.example.data.model.AppUpdateConfig
import com.example.data.model.FirestoreNotification
import com.example.data.model.FirestoreSupportChat
import com.example.data.model.FirestoreSupportMessage
import com.example.data.model.FirestoreVideo
import com.example.data.model.SupportSenderRole
import com.example.data.model.TelegramCategoryItem
import com.example.data.model.TelegramConfig
import com.example.data.model.TokenValidationResult
import com.example.data.model.VideoToken
import com.example.data.repository.FirebaseAuthAdminRepository
import com.example.data.repository.FirestoreVideoRepository
import com.example.data.repository.VideoRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
  private val firestoreVideoRepository: FirestoreVideoRepository,
  private val localVideoRepository: VideoRepository,
  private val adminAuthRepository: FirebaseAuthAdminRepository
) : ViewModel() {

  private val _inputUrl = MutableStateFlow("")
  val inputUrl: StateFlow<String> = _inputUrl.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _errorMessage = MutableStateFlow<String?>(null)
  val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

  val isAdminAuthenticated: StateFlow<Boolean> = adminAuthRepository.isAdminAuthenticated
  val adminRole: StateFlow<String?> = adminAuthRepository.adminRole
  val currentMember: StateFlow<com.example.data.model.FirestoreMember?> = adminAuthRepository.currentMember

  val allVideos: StateFlow<List<VideoToken>> = localVideoRepository.allVideosFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val watchHistory: StateFlow<List<WatchHistoryEntity>> = localVideoRepository.watchHistoryFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val serverRefreshedTelegramConfig = MutableSharedFlow<TelegramConfig>(extraBufferCapacity = 1)

  val telegramConfig: StateFlow<TelegramConfig> = merge(
    firestoreVideoRepository.getTelegramConfigFlow()
      .catch { emit(TelegramConfig(mainLink = FirestoreVideoRepository.DEFAULT_TELEGRAM_LINK)) },
    serverRefreshedTelegramConfig
  ).stateIn(
    viewModelScope,
    SharingStarted.Eagerly,
    TelegramConfig(mainLink = FirestoreVideoRepository.DEFAULT_TELEGRAM_LINK)
  )

  fun refreshTelegramConfigFromServer() {
    viewModelScope.launch {
      val fresh = firestoreVideoRepository.fetchTelegramConfigFromServer()
      if (fresh != null) {
        serverRefreshedTelegramConfig.tryEmit(fresh)
      }
    }
  }

  private val serverRefreshedAppUpdateConfig = MutableSharedFlow<AppUpdateConfig>(extraBufferCapacity = 1)

  val appUpdateConfig: StateFlow<AppUpdateConfig> = merge(
    firestoreVideoRepository.getAppUpdateConfigFlow()
      .catch { emit(AppUpdateConfig()) },
    serverRefreshedAppUpdateConfig
  ).stateIn(
    viewModelScope,
    SharingStarted.Eagerly,
    AppUpdateConfig()
  )

  private val _dismissedUpdateVersionCode = MutableStateFlow<Int?>(null)
  val dismissedUpdateVersionCode: StateFlow<Int?> = _dismissedUpdateVersionCode.asStateFlow()

  fun refreshAppUpdateConfigFromServer() {
    viewModelScope.launch {
      val fresh = firestoreVideoRepository.fetchAppUpdateConfigFromServer()
      if (fresh != null) {
        serverRefreshedAppUpdateConfig.tryEmit(fresh)
      }
    }
  }

  fun dismissOptionalUpdate(versionCode: Int) {
    _dismissedUpdateVersionCode.value = versionCode
  }

  val telegramLink: StateFlow<String> = telegramConfig
    .map { it.mainLink }
    .stateIn(viewModelScope, SharingStarted.Eagerly, FirestoreVideoRepository.DEFAULT_TELEGRAM_LINK)

  val telegramCategoryLinks: StateFlow<Map<String, String>> = telegramConfig
    .map { it.categoryLinks }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

  val telegramCategories: StateFlow<List<TelegramCategoryItem>> = telegramConfig
    .map { it.allCategories() }
    .stateIn(viewModelScope, SharingStarted.Eagerly, TelegramConfig().allCategories())

  private val _supportChatId = MutableStateFlow("")
  val supportChatId: StateFlow<String> = _supportChatId.asStateFlow()

  private val _supportUserName = MutableStateFlow("")
  val supportUserName: StateFlow<String> = _supportUserName.asStateFlow()

  val supportChat: StateFlow<FirestoreSupportChat?> = _supportChatId
    .flatMapLatest { chatId ->
      if (chatId.isBlank()) flowOf(null)
      else firestoreVideoRepository.observeSupportChat(chatId)
    }
    .catch { emit(null) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val supportMessages: StateFlow<List<FirestoreSupportMessage>> = _supportChatId
    .flatMapLatest { chatId ->
      if (chatId.isBlank()) flowOf(emptyList())
      else firestoreVideoRepository.observeSupportMessages(chatId)
    }
    .catch { emit(emptyList()) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val notifications: StateFlow<List<FirestoreNotification>> = firestoreVideoRepository.getNotificationsFlow()
    .catch { emit(emptyList()) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  init {
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
      try {
        localVideoRepository.initializeSeedsIfEmpty()
      } catch (_: Exception) {}
    }
  }

  fun onInputChange(text: String) {
    _inputUrl.value = text
    if (_errorMessage.value != null) {
      _errorMessage.value = null
    }
  }

  fun pasteText(text: String) {
    _inputUrl.value = text
    _errorMessage.value = null
  }

  fun validateAndPlay(
    input: String = _inputUrl.value,
    onError: (String) -> Unit = {},
    onSuccess: (VideoToken) -> Unit
  ) {
    if (input.isBlank()) {
      val msg = "Please enter or paste your video link."
      _errorMessage.value = msg
      onError(msg)
      return
    }

    viewModelScope.launch {
      _isLoading.value = true
      _errorMessage.value = null

      try {
        val activeCreatorId = adminAuthRepository.getActiveCreatorMemberId()

        // First try Firestore Cloud accessLinks / videos / direct URL
        val firestoreResult = firestoreVideoRepository.validateAndGetVideo(
          rawInput = input,
          currentMemberId = activeCreatorId
        )
        when (firestoreResult) {
          is TokenValidationResult.Success -> {
            _isLoading.value = false
            localVideoRepository.recordWatchHistory(firestoreResult.video)
            onSuccess(firestoreResult.video)
            return@launch
          }
          is TokenValidationResult.TokenDeactivated -> {
            _isLoading.value = false
            val msg = "This video link has been deactivated by the administrator."
            _errorMessage.value = msg
            onError(msg)
            return@launch
          }
          is TokenValidationResult.Error -> {
            // Check local if network error
          }
          is TokenValidationResult.TokenNotFound -> {
            // Check local database fallback
          }
        }

        // Check local database cache
        when (
          val localResult = localVideoRepository.validateAndGetVideo(
            rawInput = input,
            skipViewIncrementVideoIds = adminAuthRepository.getCreatorUploadedVideoIds()
          )
        ) {
          is TokenValidationResult.Success -> {
            _isLoading.value = false
            localVideoRepository.recordWatchHistory(localResult.video)
            onSuccess(localResult.video)
          }
          is TokenValidationResult.TokenNotFound -> {
            _isLoading.value = false
            val msg = "Invalid video link or video not found."
            _errorMessage.value = msg
            onError(msg)
          }
          is TokenValidationResult.TokenDeactivated -> {
            _isLoading.value = false
            val msg = "This video link has been deactivated by the administrator."
            _errorMessage.value = msg
            onError(msg)
          }
          is TokenValidationResult.Error -> {
            _isLoading.value = false
            _errorMessage.value = localResult.message
            onError(localResult.message)
          }
        }
      } catch (e: Exception) {
        _isLoading.value = false
        val msg = "Link error: ${e.message ?: "Failed to connect"}"
        _errorMessage.value = msg
        onError(msg)
      }
    }
  }

  fun openNotificationActionUrl(
    context: Context,
    rawUrl: String,
    onPlayVideo: (VideoToken) -> Unit,
    onFallbackOpened: () -> Unit = {},
    onError: (String) -> Unit = {}
  ) {
    val cleanUrl = FirestoreVideoRepository.normalizeNotificationActionUrl(rawUrl)
    if (cleanUrl.isBlank()) {
      onError("Notification link is empty.")
      return
    }

    if (FirestoreVideoRepository.isVidvoraVideoUrl(cleanUrl)) {
      _inputUrl.value = cleanUrl
      validateAndPlay(
        input = cleanUrl,
        onError = onError,
        onSuccess = onPlayVideo
      )
      return
    }

    viewModelScope.launch {
      _isLoading.value = true
      val matchedVideo = try {
        firestoreVideoRepository.findActiveVideoByStoragePath(cleanUrl)
      } catch (_: Exception) {
        null
      }
      _isLoading.value = false

      if (matchedVideo != null) {
        localVideoRepository.recordWatchHistory(matchedVideo)
        onPlayVideo(matchedVideo)
      } else {
        val opened = com.example.notifications.AppNotificationHelper.openExternalUrl(context, cleanUrl)
        if (opened) {
          onFallbackOpened()
        } else {
          onError("Could not open link")
        }
      }
    }
  }

  fun playHistoryItem(item: WatchHistoryEntity, onSuccess: (VideoToken) -> Unit) {
    val video = item.toVideoToken()
    viewModelScope.launch {
      localVideoRepository.recordWatchHistory(video)
    }
    onSuccess(video)
  }

  fun clearHistory() {
    viewModelScope.launch {
      localVideoRepository.clearWatchHistory()
    }
  }

  fun deleteHistoryItem(videoId: String) {
    viewModelScope.launch {
      localVideoRepository.removeWatchHistoryItem(videoId)
    }
  }

  fun authenticateAdminPasskey(passkey: String, email: String? = null): Boolean {
    return adminAuthRepository.authenticateWithPasskey(passkey, email)
  }

  suspend fun authenticateMemberOrAdmin(email: String, passkey: String): Boolean {
    return adminAuthRepository.authenticateMemberOrAdmin(email, passkey, firestoreVideoRepository)
  }

  suspend fun sendSignupOtp(name: String, email: String, password: String): Result<String> {
    return firestoreVideoRepository.sendSignupOtp(name, email, password)
  }

  suspend fun verifySignupOtpAndLogin(
    name: String,
    email: String,
    password: String,
    otp: String
  ): Result<com.example.data.model.FirestoreMember> {
    val result = firestoreVideoRepository.verifySignupOtpAndCreateMember(name, email, password, otp)
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
  ): Result<com.example.data.model.FirestoreMember> {
    val result = firestoreVideoRepository.verifyResetPasswordOtp(email, otp, newPassword)
    result.getOrNull()?.let { member ->
      adminAuthRepository.signInMemberDirectly(member)
    }
    return result
  }

  suspend fun signInAdminWithGoogle(context: Context): Result<Unit> {
    return adminAuthRepository.signInWithGoogle(context).map { }
  }

  fun initSupportChat(context: Context) {
    if (_supportChatId.value.isNotBlank()) return
    val id = firestoreVideoRepository.getOrCreateSupportChatId(context)
    val savedName = firestoreVideoRepository.getSavedSupportUserName(context)
    _supportChatId.value = id
    _supportUserName.value = savedName
  }

  fun sendUserSupportMessage(
    context: Context,
    userName: String,
    text: String,
    onResult: (Boolean, String?) -> Unit
  ) {
    val chatId = _supportChatId.value.ifBlank {
      firestoreVideoRepository.getOrCreateSupportChatId(context).also { _supportChatId.value = it }
    }
    val cleanName = userName.trim().ifBlank { "Guest User" }
    firestoreVideoRepository.saveSupportUserName(cleanName, context)
    _supportUserName.value = cleanName

    viewModelScope.launch {
      val result = firestoreVideoRepository.sendSupportMessage(
        chatId = chatId,
        senderRole = SupportSenderRole.USER,
        senderName = cleanName,
        text = text
      )
      if (result.isSuccess) {
        onResult(true, null)
      } else {
        onResult(false, result.exceptionOrNull()?.message ?: "Failed to send message")
      }
    }
  }

  fun markSupportChatRead() {
    val chatId = _supportChatId.value
    if (chatId.isBlank()) return
    viewModelScope.launch {
      firestoreVideoRepository.markSupportChatReadByUser(chatId)
    }
  }

  fun clearError() {
    _errorMessage.value = null
  }
}
