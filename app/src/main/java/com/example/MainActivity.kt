package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

    pendingDeepLinkState.value = extractTokenFromIntent(intent)

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
      pendingDeepLinkState.value = extracted
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
  adManager: AdManager? = null
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val hostActivity = context as? android.app.Activity
  var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
  var returnScreenAfterPlayer by remember { mutableStateOf<AppScreen>(AppScreen.Home) }

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

  // Handle incoming deep link on launch or onNewIntent (monetized with Pre-Play Ad)
  LaunchedEffect(deepLinkToken) {
    if (!deepLinkToken.isNullOrBlank()) {
      adManager?.appOpenAdManager?.suppressNextColdLaunchAd()
      if (currentScreen !is AppScreen.Home) {
        currentScreen = AppScreen.Home
      }
      homeViewModel.onInputChange(deepLinkToken)
      homeViewModel.validateAndPlay(
        input = deepLinkToken,
        onError = { err ->
          android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
        }
      ) { video ->
        if (adManager != null && hostActivity != null) {
          adManager.showPrePlayAd(hostActivity) {
            returnScreenAfterPlayer = AppScreen.Home
            currentScreen = AppScreen.Player(video)
          }
        } else {
          returnScreenAfterPlayer = AppScreen.Home
          currentScreen = AppScreen.Player(video)
        }
      }
      onDeepLinkConsumed()
    }
  }

  when (val screen = currentScreen) {
    is AppScreen.Home -> {
      HomeScreen(
        viewModel = homeViewModel,
        adManager = adManager,
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
}
