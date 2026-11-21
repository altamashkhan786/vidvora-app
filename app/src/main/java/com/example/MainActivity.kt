package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ads.AdManager
import com.example.ads.ConsentManager
import com.example.data.local.VideoDatabase
import com.example.data.model.VideoToken
import com.example.data.repository.FirebaseAuthAdminRepository
import com.example.data.repository.FirestoreVideoRepository
import com.example.data.repository.VideoRepository
import com.example.ui.navigation.AppScreen
import com.example.ui.screens.admin.AdminPanelScreen
import com.example.ui.screens.admin.AdminViewModel
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.home.HomeViewModel
import com.example.ui.screens.player.PlayerScreen
import com.example.ui.theme.MasalaBlack
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaTextPrimary
import com.example.ui.theme.MyApplicationTheme
import com.example.util.VideoShareModeDialogHost
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private lateinit var localVideoRepository: VideoRepository
  private lateinit var firestoreVideoRepository: FirestoreVideoRepository
  private lateinit var firebaseAuthAdminRepository: FirebaseAuthAdminRepository
  private lateinit var consentManager: ConsentManager
  private lateinit var adManager: AdManager
  private val pendingDeepLinkState = mutableStateOf<String?>(null)
  private val pendingOpenAdminSupportState = mutableStateOf<Pair<Int, String?>?>(null)
  private var supportNotificationRequestCounter = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Ensure FirebaseApp is initialized before App Check / Firestore
    try {
      if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
        com.google.firebase.FirebaseApp.initializeApp(this)
      }
      val appCheck = FirebaseAppCheck.getInstance()
      appCheck.installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance()
      )
    } catch (e: Throwable) {
      Log.w("MainActivity", "Firebase init: ${e.message}")
    }

    val database = VideoDatabase.getDatabase(applicationContext)
    localVideoRepository = VideoRepository(database.videoDao(), database.watchHistoryDao())
    firestoreVideoRepository = FirestoreVideoRepository(applicationContext)
    firebaseAuthAdminRepository = FirebaseAuthAdminRepository(applicationContext)

    // Prepare managers without blocking initial UI frame
    consentManager = ConsentManager(this)
    adManager = AdManager(this)
    adManager.appOpenAdManager.attachActivity(this)
    com.example.notifications.AppNotificationHelper.ensureNotificationChannel(applicationContext)

    // Register initial app install or send throttled active heartbeat without blocking startup
    lifecycleScope.launch(Dispatchers.IO) {
      firestoreVideoRepository.registerOrHeartbeatAppInstall(applicationContext)
    }

    val initialDeepLink = extractTokenFromIntent(intent)
    if (!initialDeepLink.isNullOrBlank()) {
      adManager.appOpenAdManager.suppressNextColdLaunchAd()
    }
    pendingDeepLinkState.value = initialDeepLink
    handleAdminSupportIntent(intent)

    setContent {
      MyApplicationTheme {
        // Initialize AdMob immediately when consent allows, and refresh UMP consent right away
        LaunchedEffect(Unit) {
          if (consentManager.canRequestAds) {
            adManager.initialize()
          }
          consentManager.gatherConsent(this@MainActivity) { canRequestAds ->
            if (canRequestAds) {
              adManager.initialize()
            }
          }
        }

        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MasalaBlack)
        ) {
          VidVoraApp(
            firestoreVideoRepository = firestoreVideoRepository,
            localVideoRepository = localVideoRepository,
            adminAuthRepository = firebaseAuthAdminRepository,
            deepLinkToken = pendingDeepLinkState.value,
            onDeepLinkConsumed = { pendingDeepLinkState.value = null },
            pendingOpenAdminSupport = pendingOpenAdminSupportState.value,
            onOpenAdminSupportConsumed = { pendingOpenAdminSupportState.value = null },
            adManager = adManager
          )
          VideoShareModeDialogHost()
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val extracted = extractTokenFromIntent(intent)
    if (!extracted.isNullOrBlank()) {
      if (::adManager.isInitialized) {
        adManager.appOpenAdManager.suppressNextColdLaunchAd()
      }
      pendingDeepLinkState.value = extracted
    }
    handleAdminSupportIntent(intent)
  }

  private fun handleAdminSupportIntent(intent: Intent?) {
    val openSupport = intent?.getBooleanExtra(
      com.example.notifications.AppNotificationHelper.EXTRA_OPEN_ADMIN_SUPPORT,
      false
    ) == true
    if (openSupport) {
      if (::adManager.isInitialized) {
        adManager.appOpenAdManager.suppressNextColdLaunchAd()
      }
      val chatId = intent?.getStringExtra(
        com.example.notifications.AppNotificationHelper.EXTRA_SUPPORT_CHAT_ID
      )?.trim()?.takeIf { it.isNotBlank() }
      supportNotificationRequestCounter += 1
      pendingOpenAdminSupportState.value = supportNotificationRequestCounter to chatId
      intent?.removeExtra(com.example.notifications.AppNotificationHelper.EXTRA_OPEN_ADMIN_SUPPORT)
      intent?.removeExtra(com.example.notifications.AppNotificationHelper.EXTRA_SUPPORT_CHAT_ID)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    if (::adManager.isInitialized) {
      adManager.appOpenAdManager.detachActivity(this)
    }
  }

  private fun extractTokenFromIntent(intent: Intent?): String? {
    val explicitLink = intent?.getStringExtra(
      com.example.notifications.AppNotificationHelper.EXTRA_NOTIFICATION_VIDEO_LINK
    )?.trim()
    if (!explicitLink.isNullOrBlank()) {
      return explicitLink
    }
    val data: Uri? = intent?.data
    if (data != null) {
      if (FirestoreVideoRepository.isDirectMediaUri(data)) {
        return data.toString()
      }
      val param = data.getQueryParameter("link")
        ?: data.getQueryParameter("v")
        ?: data.getQueryParameter("video")
        ?: data.getQueryParameter("token")
      if (!param.isNullOrBlank()) {
        return param.trim()
      }
      val lastSegment = data.lastPathSegment
      if (!lastSegment.isNullOrBlank() &&
        !lastSegment.equals("watch", ignoreCase = true) &&
        !lastSegment.equals("share", ignoreCase = true) &&
        !lastSegment.equals("VidVora-apk", ignoreCase = true) &&
        !lastSegment.equals("index.html", ignoreCase = true) &&
        (lastSegment.startsWith("GM-", ignoreCase = true) ||
          lastSegment.startsWith("link_", ignoreCase = true) ||
          lastSegment.startsWith("vid_", ignoreCase = true))
      ) {
        return lastSegment.trim()
      }
      if (FirestoreVideoRepository.isVidvoraVideoUrl(data.toString())) {
        return data.toString()
      }
    }
    return null
  }
}

@Composable
fun VidVoraApp(
  firestoreVideoRepository: FirestoreVideoRepository,
  localVideoRepository: VideoRepository,
  adminAuthRepository: FirebaseAuthAdminRepository,
  deepLinkToken: String?,
  onDeepLinkConsumed: () -> Unit = {},
  pendingOpenAdminSupport: Pair<Int, String?>? = null,
  onOpenAdminSupportConsumed: () -> Unit = {},
  adManager: AdManager? = null
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val hostActivity = context as? android.app.Activity
  var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
  var returnScreenAfterPlayer by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
  var isOpeningDeepLink by remember { mutableStateOf(!deepLinkToken.isNullOrBlank()) }
  var activeDeepLinkRequestId by remember { mutableIntStateOf(0) }
  var activeOpenSupportRequest by remember { mutableStateOf<Pair<Int, String?>?>(null) }

  // Keep ad manager updated on player status to strictly prevent ads during video playback
  LaunchedEffect(currentScreen) {
    adManager?.isPlayerActive = (currentScreen is AppScreen.Player)
  }

  val homeViewModel = remember {
    HomeViewModel(firestoreVideoRepository, localVideoRepository, adminAuthRepository)
  }

  val adminViewModel = remember {
    AdminViewModel(firestoreVideoRepository, localVideoRepository, adminAuthRepository)
  }

  LaunchedEffect(Unit) {
    adminAuthRepository.restorePersistedSession(firestoreVideoRepository)
  }

  // Global Admin Support Message Alert Watcher:
  // Notifies Superadmin & Support-enabled Delegated Admins immediately upon login AND live whenever
  // any Creator or User sends a new support message (regardless of which screen Admin is viewing).
  val isAdminAuthenticated by adminAuthRepository.isAdminAuthenticated.collectAsState()
  val adminRole by adminAuthRepository.adminRole.collectAsState()
  val authMember by adminAuthRepository.currentMember.collectAsState()
  val liveMember by adminViewModel.liveCurrentMember.collectAsState()
  val loginEventCounter by adminAuthRepository.loginEventCounter.collectAsState()
  val activeMemberForAlerts = liveMember ?: authMember
  val canReceiveAdminSupportAlerts = isAdminAuthenticated && (
    (activeMemberForAlerts == null && (adminRole == "admin" || adminRole == "superadmin" || adminRole == "admin (dev)")) ||
      (activeMemberForAlerts != null &&
        activeMemberForAlerts.isActive &&
        activeMemberForAlerts.isDelegatedAdmin() &&
        activeMemberForAlerts.hasOption(com.example.data.model.MemberOption.MANAGE_SUPPORT_CHAT))
    )

  LaunchedEffect(canReceiveAdminSupportAlerts, loginEventCounter) {
    if (!canReceiveAdminSupportAlerts) return@LaunchedEffect
    var handledInitialLoginBurst = false
    firestoreVideoRepository.observeAllSupportChats().collect { chats ->
      val shouldForceOnLogin = !handledInitialLoginBurst
      com.example.notifications.AppNotificationHelper.notifyAdminSupportMessages(
        context = context,
        chats = chats,
        forceNotifyUnreadOnLogin = shouldForceOnLogin
      )
      if (chats.isNotEmpty() && com.example.notifications.AppNotificationHelper.hasNotificationPermission(context)) {
        handledInitialLoginBurst = true
      }
    }
  }

  // Route taps on Admin Support notifications directly into Admin Panel -> Support tab
  LaunchedEffect(pendingOpenAdminSupport) {
    if (pendingOpenAdminSupport != null) {
      activeOpenSupportRequest = pendingOpenAdminSupport
      currentScreen = AppScreen.Admin
      onOpenAdminSupportConsumed()
    }
  }

  // Handle incoming deep link on launch or onNewIntent (monetized with quick Pre-Play Ad check)
  LaunchedEffect(deepLinkToken) {
    val token = deepLinkToken?.trim()
    if (!token.isNullOrBlank()) {
      val requestId = ++activeDeepLinkRequestId
      isOpeningDeepLink = true
      adManager?.appOpenAdManager?.suppressNextColdLaunchAd()
      if (currentScreen !is AppScreen.Home) {
        currentScreen = AppScreen.Home
      }
      homeViewModel.onInputChange(token)
      homeViewModel.validateAndPlay(
        input = token,
        onError = { err ->
          if (activeDeepLinkRequestId == requestId) {
            isOpeningDeepLink = false
          }
          android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
        }
      ) { video ->
        if (activeDeepLinkRequestId != requestId) return@validateAndPlay
        if (adManager != null && hostActivity != null) {
          adManager.showPrePlayAd(hostActivity, maxWaitMs = 2400L) {
            if (activeDeepLinkRequestId == requestId) {
              returnScreenAfterPlayer = AppScreen.Home
              currentScreen = AppScreen.Player(video)
              isOpeningDeepLink = false
            }
          }
        } else {
          returnScreenAfterPlayer = AppScreen.Home
          currentScreen = AppScreen.Player(video)
          isOpeningDeepLink = false
        }
      }
      onDeepLinkConsumed()
    }
  }

  val showDeepLinkOverlay =
    (isOpeningDeepLink || !deepLinkToken.isNullOrBlank()) && currentScreen is AppScreen.Home

  Box(modifier = Modifier.fillMaxSize()) {
    when (val screen = currentScreen) {
      is AppScreen.Home -> {
        HomeScreen(
          viewModel = homeViewModel,
          adManager = adManager,
          isAutoOpeningDeepLink = showDeepLinkOverlay,
          onNavigateToPlayer = { video ->
            returnScreenAfterPlayer = AppScreen.Home
            currentScreen = AppScreen.Player(video)
          },
          onNavigateToAdmin = {
            currentScreen = AppScreen.Admin
          }
        )
      }

      is AppScreen.Player -> {
        val liveTelegramConfig by homeViewModel.telegramConfig.collectAsState()
        androidx.compose.runtime.key(screen.video.id, screen.video.streamUrl) {
          PlayerScreen(
            video = screen.video,
            adManager = adManager,
            allowExitAd = (returnScreenAfterPlayer != AppScreen.Admin),
            shareLinkBaseUrl = liveTelegramConfig.shareLinkBaseUrl,
            onNavigateBack = {
              currentScreen = returnScreenAfterPlayer
            }
          )
        }
      }

      is AppScreen.Admin -> {
        AdminPanelScreen(
          viewModel = adminViewModel,
          openSupportRequest = activeOpenSupportRequest,
          onOpenSupportRequestConsumed = { activeOpenSupportRequest = null },
          onNavigateBack = {
            currentScreen = AppScreen.Home
          },
          onPlayVideo = { linkOrId ->
            homeViewModel.validateAndPlay(
              input = linkOrId,
              onError = { err ->
                android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
              }
            ) { video ->
              returnScreenAfterPlayer = AppScreen.Admin
              currentScreen = AppScreen.Player(video)
            }
          },
          onPlayVideoItem = { firestoreVideo ->
            adminViewModel.prepareVideoForPlayback(
              video = firestoreVideo,
              onError = { err ->
                android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
              }
            ) { playableVideo ->
              returnScreenAfterPlayer = AppScreen.Admin
              currentScreen = AppScreen.Player(playableVideo)
            }
          }
        )
      }
    }

    if (showDeepLinkOverlay) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(MasalaBlack.copy(alpha = 0.94f))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {}
          )
          .testTag("deep_link_opening_overlay"),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          CircularProgressIndicator(
            color = MasalaRedPrimary,
            strokeWidth = 3.dp,
            modifier = Modifier.size(44.dp)
          )
          Text(
            text = "Opening video...",
            color = MasalaTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
      }
    }
  }
}
