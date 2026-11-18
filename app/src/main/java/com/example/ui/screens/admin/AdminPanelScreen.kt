package com.example.ui.screens.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import com.example.BuildConfig
import com.example.data.repository.FirebaseAuthAdminRepository
import com.example.util.VideoShareHelper
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.FirestoreAccessLink
import com.example.data.model.FirestoreCreatorEvent
import com.example.data.model.FirestoreMember
import com.example.data.model.FirestoreNotification
import com.example.data.model.FirestoreVideo
import com.example.data.model.FirestoreWithdrawalRequest
import com.example.data.model.MemberOption
import com.example.data.model.MemberRole
import com.example.data.model.MemberStats
import com.example.data.model.SupportChatStatus
import com.example.data.model.TelegramCategoryItem
import com.example.data.model.TelegramChannelCategory
import com.example.data.model.WithdrawalStatus
import com.example.data.model.formatInr
import com.example.data.repository.FirestoreVideoRepository
import com.example.data.repository.VideoCreationResult
import com.example.ui.theme.MasalaBlack
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaError
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaRedAccent
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaSuccess
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaSurfaceVariant
import com.example.ui.theme.MasalaTextMuted
import com.example.ui.theme.MasalaTextPrimary
import com.example.ui.theme.MasalaTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPanelScreen(
  viewModel: AdminViewModel,
  onNavigateBack: () -> Unit,
  onPlayVideo: (String) -> Unit,
  onPlayVideoItem: ((FirestoreVideo) -> Unit)? = null
) {
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  DisposableEffect(Unit) {
    onDispose {
      focusManager.clearFocus()
      keyboardController?.hide()
    }
  }

  BackHandler {
    focusManager.clearFocus()
    keyboardController?.hide()
    onNavigateBack()
  }

  val context = LocalContext.current
  val videos by viewModel.videos.collectAsState()
  val accessLinks by viewModel.accessLinks.collectAsState()
  val adminUser by viewModel.currentAdminUser.collectAsState()
  val adminRole by viewModel.adminRole.collectAsState()
  val isAdminAuthenticated by viewModel.isAdminAuthenticated.collectAsState()
  val currentMember by viewModel.currentMember.collectAsState()
  val liveCurrentMember by viewModel.liveCurrentMember.collectAsState()
  val activeMember = liveCurrentMember ?: currentMember
  val isCurrentMember by viewModel.isCurrentMember.collectAsState()
  val isDelegatedAdmin by viewModel.isDelegatedAdmin.collectAsState()
  val isSuperAdmin by viewModel.isSuperAdmin.collectAsState()
  val effectiveAllowedOptions by viewModel.effectiveAllowedOptions.collectAsState()
  val memberStatsList by viewModel.memberStatsList.collectAsState()
  val myMemberVideos by viewModel.myMemberVideos.collectAsState()
  val myTotalViews by viewModel.myTotalViews.collectAsState()
  val myCreditedViews by viewModel.myCreditedViews.collectAsState()
  val allWithdrawalRequests by viewModel.allWithdrawalRequests.collectAsState()
  val myWithdrawalRequests by viewModel.myWithdrawalRequests.collectAsState()
  val myEarningSummary by viewModel.myEarningSummary.collectAsState()
  val pendingPayoutsCount by viewModel.pendingPayoutsCount.collectAsState()
  val notifications by viewModel.notifications.collectAsState()
  val creatorEvents by viewModel.creatorEvents.collectAsState()
  val activeCreatorEvents by viewModel.activeCreatorEvents.collectAsState()
  val appInstalls by viewModel.appInstalls.collectAsState()
  val appInstallStats by viewModel.appInstallStats.collectAsState()
  val inactivityWindowDays by viewModel.inactivityWindowDays.collectAsState()

  val isLoading by viewModel.isLoading.collectAsState()
  val isUploading by viewModel.isUploading.collectAsState()
  val uploadProgress by viewModel.uploadProgress.collectAsState()
  val lastCreatedResult by viewModel.lastCreatedResult.collectAsState()
  val toastMessage by viewModel.toastMessage.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }

  var selectedTab by remember { mutableIntStateOf(0) }
  var installStatusFilter by remember { mutableStateOf("ALL") }
  var showUploadDialog by remember { mutableStateOf(false) }
  var showAddMemberDialog by remember { mutableStateOf(false) }
  var editingMember by remember { mutableStateOf<FirestoreMember?>(null) }
  var changingEmailMemberStats by remember { mutableStateOf<MemberStats?>(null) }
  var showChangeMasterKeyDialog by remember { mutableStateOf(false) }
  var showWithdrawDialog by remember { mutableStateOf(false) }
  var editingEarningMember by remember { mutableStateOf<MemberStats?>(null) }
  var statusUpdateTarget by remember { mutableStateOf<Pair<FirestoreWithdrawalRequest, String>?>(null) }
  var payoutFilter by remember { mutableStateOf("ALL") }
  var editingVideo by remember { mutableStateOf<FirestoreVideo?>(null) }
  var deletingVideoId by remember { mutableStateOf<String?>(null) }
  var deletingMemberId by remember { mutableStateOf<String?>(null) }
  var memberSearchQuery by remember { mutableStateOf("") }
  var showLoginDialogOnRestricted by remember { mutableStateOf(false) }
  val telegramLink by viewModel.telegramLink.collectAsState()
  val telegramCreatorBotLink by viewModel.telegramCreatorBotLink.collectAsState()
  val telegramFinanceBotLink by viewModel.telegramFinanceBotLink.collectAsState()
  val shareLinkBaseUrl by viewModel.shareLinkBaseUrl.collectAsState()
  val hasCustomAdminMasterKey by viewModel.hasCustomAdminMasterKey.collectAsState()
  val telegramCategoryLinks by viewModel.telegramCategoryLinks.collectAsState()
  val telegramCategoryNames by viewModel.telegramCategoryNames.collectAsState()
  val telegramDeletedCategoryKeys by viewModel.telegramDeletedCategoryKeys.collectAsState()
  val telegramCustomCategories by viewModel.telegramCustomCategories.collectAsState()
  val allTelegramCategories by viewModel.allTelegramCategories.collectAsState()
  val supportChats by viewModel.supportChats.collectAsState()
  val unreadSupportChatsCount by viewModel.unreadSupportChatsCount.collectAsState()
  val myCreatorSupportChat by viewModel.myCreatorSupportChat.collectAsState()
  val myCreatorSupportMessages by viewModel.myCreatorSupportMessages.collectAsState()
  val appUpdateConfig by viewModel.appUpdateConfig.collectAsState()
  var showTelegramDialog by remember { mutableStateOf(false) }
  var showAppUpdateDialog by remember { mutableStateOf(false) }
  var showCreatorSupportDialog by remember { mutableStateOf(false) }
  var showCreatorEventsDialog by remember { mutableStateOf(false) }
  var showCreateEventDialog by remember { mutableStateOf(false) }
  var editingCreatorEvent by remember { mutableStateOf<FirestoreCreatorEvent?>(null) }
  var deletingCreatorEventId by remember { mutableStateOf<String?>(null) }
  var supportStatusFilter by remember { mutableStateOf("ALL") }
  var activeSupportChatId by remember { mutableStateOf<String?>(null) }
  val listState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  var lastKnownVideosHeaderIndex by remember { androidx.compose.runtime.mutableIntStateOf(-1) }

  LaunchedEffect(toastMessage) {
    toastMessage?.let {
      snackbarHostState.showSnackbar(it)
      viewModel.clearToast()
    }
  }

  LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo.visibleItemsInfo.size) {
    val visibleHeader = listState.layoutInfo.visibleItemsInfo.firstOrNull {
      it.key == "creator_videos_header" || it.key == "admin_videos_header"
    }
    if (visibleHeader != null) {
      lastKnownVideosHeaderIndex = visibleHeader.index
    }
  }

  LaunchedEffect(lastCreatedResult?.video?.videoId) {
    if (lastCreatedResult != null) {
      val visibleHeaderIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull {
        it.key == "creator_videos_header" || it.key == "admin_videos_header"
      }?.index
      val targetIndex = visibleHeaderIndex ?: lastKnownVideosHeaderIndex.takeIf { it >= 0 }
      if (targetIndex != null && listState.firstVisibleItemIndex >= targetIndex) {
        listState.animateScrollToItem(targetIndex)
      } else if (visibleHeaderIndex != null) {
        listState.animateScrollToItem(visibleHeaderIndex)
      }
    }
  }

  val hasAccess = isAdminAuthenticated && (
    adminRole == "admin" ||
      adminRole == "superadmin" ||
      adminRole == "admin (dev)" ||
      adminRole == "member" ||
      adminRole == "delegated_admin"
    )

  // 1. Loading state while checking backend role
  if (isLoading) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(MasalaBlack),
      contentAlignment = Alignment.Center
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
          color = MasalaRedPrimary,
          strokeWidth = 3.dp,
          modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = "Verifying Portal Privileges...",
          color = MasalaTextPrimary,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Authenticating with VidVora Backend",
          color = MasalaGold,
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 4.dp)
        )
      }
    }
    return
  }

  // 2. Access Restricted Gate: Component checks for user roles before rendering dashboard
  if (!hasAccess) {
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text("Admin & Creator Portal", color = MasalaTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
          },
          navigationIcon = {
            IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("restricted_back_button")) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MasalaTextPrimary)
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = MasalaSurface)
        )
      },
      containerColor = MasalaBlack
    ) { padding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding)
          .padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MasalaSurface),
          border = androidx.compose.foundation.BorderStroke(1.dp, MasalaError.copy(alpha = 0.5f)),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_role_restricted_card")
        ) {
          Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Box(
              modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(MasalaError.copy(alpha = 0.15f))
                .border(1.dp, MasalaError.copy(alpha = 0.4f), CircleShape),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.Security, contentDescription = null, tint = MasalaError, modifier = Modifier.size(32.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
              text = "Access Verification Required",
              color = MasalaTextPrimary,
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
              text = "Please enter your Email and Password to access the video upload and analytics dashboard.",
              color = MasalaTextSecondary,
              fontSize = 13.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
              lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Account status banner
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MasalaSurfaceVariant)
                .padding(12.dp)
            ) {
              Text(
                text = "CURRENT SESSION",
                color = MasalaGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = adminUser?.email ?: if (adminUser != null) "UID: ${adminUser?.uid}" else "Status: Not Signed In",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
              )
              Text(
                text = "Role: ${adminRole ?: "None / Unassigned"}",
                color = if (hasAccess) MasalaSuccess else MasalaError,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
              onClick = { showLoginDialogOnRestricted = true },
              colors = ButtonDefaults.buttonColors(containerColor = MasalaRedPrimary),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("admin_restricted_signin_button")
            ) {
              Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Sign In or Unlock", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
              onClick = onNavigateBack,
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextSecondary),
              border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("admin_restricted_back_button")
            ) {
              Text("Return to Home")
            }
          }
        }
      }
    }

    if (showLoginDialogOnRestricted) {
      AdminLoginDialog(
        onDismiss = { showLoginDialogOnRestricted = false },
        onAuthenticatePasskey = { email, passkey ->
          val success = viewModel.authenticateMemberOrAdmin(email, passkey)
          if (success) {
            showLoginDialogOnRestricted = false
          }
          success
        },
        onSignInWithGoogle = {
          viewModel.signInWithGoogle(context).map {
            showLoginDialogOnRestricted = false
          }
        }
      )
    }
    return
  }

  // 3. User Role Verified: Render Dashboard with exact granted options
  val activeVideosCount = videos.count { it.isActive }
  val activeLinksCount = accessLinks.count { it.isActive }
  val totalViews = videos.sumOf { it.totalViews }

  val canUploadVideos = isSuperAdmin || MemberOption.UPLOAD_VIDEOS.key in effectiveAllowedOptions
  val canManageVideos = isSuperAdmin || MemberOption.MANAGE_VIDEOS.key in effectiveAllowedOptions
  val canViewEarnings = MemberOption.VIEW_EARNINGS.key in effectiveAllowedOptions
  val canRequestWithdrawal = MemberOption.REQUEST_WITHDRAWAL.key in effectiveAllowedOptions
  val canManageMembers = isSuperAdmin || MemberOption.MANAGE_MEMBERS.key in effectiveAllowedOptions
  val canManagePayouts = isSuperAdmin || MemberOption.MANAGE_PAYOUTS.key in effectiveAllowedOptions
  val canSendNotifications = isSuperAdmin || MemberOption.SEND_NOTIFICATIONS.key in effectiveAllowedOptions
  val canManageTelegram = isSuperAdmin || MemberOption.MANAGE_TELEGRAM.key in effectiveAllowedOptions
  val canViewAppStats = isSuperAdmin || MemberOption.VIEW_APP_STATS.key in effectiveAllowedOptions
  val canManageSupportChat = isSuperAdmin || MemberOption.MANAGE_SUPPORT_CHAT.key in effectiveAllowedOptions
  val canManageEvents = isSuperAdmin || MemberOption.MANAGE_EVENTS.key in effectiveAllowedOptions
  val canChangeMasterKey = isSuperAdmin ||
    (activeMember != null && (MemberOption.CHANGE_MASTER_KEY.key in effectiveAllowedOptions))

  val hasAdminTabs = isSuperAdmin || isDelegatedAdmin ||
    canManageMembers || canManagePayouts || canSendNotifications || canManageTelegram || canViewAppStats || canManageSupportChat || canManageEvents

  val visibleTabs = buildList {
    if (isSuperAdmin || canUploadVideos || canManageVideos || canManageTelegram) {
      add(AdminSectionTab.VIDEOS)
    }
    if (isSuperAdmin || canManageMembers) {
      add(AdminSectionTab.MEMBERS)
    }
    if (isSuperAdmin || canManagePayouts) {
      add(AdminSectionTab.PAYOUTS)
    }
    if (isSuperAdmin || canSendNotifications) {
      add(AdminSectionTab.NOTIFY)
    }
    if (isSuperAdmin || canViewAppStats) {
      add(AdminSectionTab.STATS)
    }
    if (isSuperAdmin || canManageSupportChat) {
      add(AdminSectionTab.SUPPORT)
    }
    if (isSuperAdmin || canManageEvents) {
      add(AdminSectionTab.EVENTS)
    }
  }
  val safeTabIndex = if (visibleTabs.isEmpty()) 0 else selectedTab.coerceIn(0, visibleTabs.lastIndex)
  val currentAdminTab = visibleTabs.getOrNull(safeTabIndex)

  val showFab = canUploadVideos && (
    !hasAdminTabs ||
      currentAdminTab == AdminSectionTab.VIDEOS ||
      (isCurrentMember && visibleTabs.isEmpty())
    )

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = when {
                isSuperAdmin -> "Administrator Panel"
                isDelegatedAdmin -> "Admin Portal: ${activeMember?.name ?: "Admin"}"
                else -> "Creator Portal: ${activeMember?.name ?: "Member"}"
              },
              color = MasalaTextPrimary,
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = when {
                isDelegatedAdmin -> "Role: Admin • ${effectiveAllowedOptions.size} Option(s) Enabled"
                isCurrentMember -> "Role: Creator • ${effectiveAllowedOptions.size} Option(s) Enabled"
                adminUser != null -> "Verified: ${adminUser?.email ?: adminUser?.uid?.take(10)}"
                else -> "Superadmin Full Access"
              },
              color = MasalaGold,
              fontSize = 11.sp
            )
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.testTag("admin_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back to Home",
              tint = MasalaTextPrimary
            )
          }
        },
        actions = {
          if (canChangeMasterKey) {
            IconButton(
              onClick = { showChangeMasterKeyDialog = true },
              modifier = Modifier.testTag("topbar_change_master_key_button")
            ) {
              Icon(
                imageVector = Icons.Default.Key,
                contentDescription = "Change Password",
                tint = MasalaGold
              )
            }
          }
          OutlinedButton(
            onClick = {
              viewModel.logout()
              onNavigateBack()
            },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextSecondary),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
              .padding(end = 8.dp)
              .testTag("admin_logout_button")
          ) {
            Text("Logout", fontSize = 12.sp)
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MasalaSurface
        )
      )
    },
    floatingActionButton = {
      if (showFab) {
        FloatingActionButton(
          onClick = { showUploadDialog = true },
          containerColor = MasalaRedPrimary,
          contentColor = Color.White,
          modifier = Modifier.testTag("upload_video_fab")
        ) {
          Icon(Icons.Default.CloudUpload, contentDescription = "Upload Video")
        }
      }
    },
    containerColor = MasalaBlack
  ) { paddingValues ->
    LazyColumn(
      state = listState,
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      item {
        Spacer(modifier = Modifier.height(4.dp))

        // Role & Session Banner
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaGold.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = when {
              isDelegatedAdmin -> Icons.Default.AdminPanelSettings
              isCurrentMember -> Icons.Default.Person
              else -> Icons.Default.Security
            },
            contentDescription = null,
            tint = MasalaGold,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = when {
              isDelegatedAdmin -> "Logged in as Admin: ${activeMember?.name} (${activeMember?.emailOrPhone})"
              isCurrentMember -> "Logged in as Creator: ${activeMember?.name} (${activeMember?.emailOrPhone})"
              else -> "Role: ${adminRole ?: "admin"} • Superadmin Full Management Mode"
            },
            color = MasalaGold,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
      }

      if (!isSuperAdmin && activeMember != null && !activeMember.isActive) {
        item {
          Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MasalaSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaError.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Icon(Icons.Default.Error, contentDescription = null, tint = MasalaError, modifier = Modifier.size(40.dp))
              Spacer(modifier = Modifier.height(10.dp))
              Text("Account Paused", color = MasalaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Your account has been temporarily deactivated by Superadmin.",
                color = MasalaTextSecondary,
                fontSize = 12.sp
              )
            }
          }
        }
      } else {
        if (!isSuperAdmin && effectiveAllowedOptions.isEmpty()) {
          item {
            Card(
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = MasalaSurface),
              border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("no_assigned_options_card")
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MasalaTextMuted, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Text("No Options Assigned", color = MasalaTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "Superadmin has not enabled any panel options for this account yet.",
                  color = MasalaTextSecondary,
                  fontSize = 12.sp
                )
              }
            }
          }
        }

        // Personal Change Master Key card (shown whenever CHANGE_MASTER_KEY option is enabled for Admin/Creator)
        if (canChangeMasterKey) {
          item {
            Card(
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = MasalaSurface),
              border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.4f)),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("creator_master_key_card")
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.weight(1f)
                ) {
                  Box(
                    modifier = Modifier
                      .size(38.dp)
                      .clip(CircleShape)
                      .background(MasalaGold.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.Default.Key,
                      contentDescription = null,
                      tint = MasalaGold,
                      modifier = Modifier.size(20.dp)
                    )
                  }
                  Spacer(modifier = Modifier.width(12.dp))
                  Column {
                    Text(
                      text = when {
                        isSuperAdmin -> "Superadmin Password"
                        isDelegatedAdmin -> "Admin Password"
                        else -> "Creator Password"
                      },
                      color = MasalaTextPrimary,
                      fontSize = 13.sp,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = when {
                        isSuperAdmin && hasCustomAdminMasterKey -> "Custom Admin Password active • Tap to update anytime"
                        isSuperAdmin -> "Update your Admin login Password anytime"
                        else -> "Update your login Password anytime"
                      },
                      color = MasalaTextSecondary,
                      fontSize = 11.sp
                    )
                  }
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                  onClick = { showChangeMasterKeyDialog = true },
                  shape = RoundedCornerShape(8.dp),
                  contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                  border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.65f)),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
                  modifier = Modifier.testTag("creator_change_master_key_button")
                ) {
                  Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Change Password", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
              }
            }
          }
        }

        // Self-Hosted In-App Update Control Card (front & center for Superadmin & Admins)
        if (isSuperAdmin || isDelegatedAdmin || canManageTelegram) {
          item {
            AdminAppUpdateCard(
              config = appUpdateConfig,
              currentAppVersionCode = BuildConfig.VERSION_CODE,
              currentAppVersionName = BuildConfig.VERSION_NAME,
              onEditClick = { showAppUpdateDialog = true }
            )
          }
        }

        // Creator Chat Support card: lets Creator message VidVora Support Team directly (cannot reply to others)
        if (isCurrentMember && !canManageSupportChat && activeMember != null) {
          item {
            CreatorSupportBannerCard(
              chat = myCreatorSupportChat,
              onOpenChat = {
                viewModel.markCreatorSupportChatRead()
                showCreatorSupportDialog = true
              }
            )
          }
        }

        // Creator Events & Gift Announcements card (all active Creators can view gift announcements)
        if (isCurrentMember && activeMember != null) {
          item {
            CreatorEventsBannerCard(
              activeEvents = activeCreatorEvents,
              onOpenEvents = { showCreatorEventsDialog = true }
            )
          }
        }

        // Telegram Auto-Upload Bot Quick Card (for Creators & Admins with Upload privilege)
        if (canUploadVideos || canManageTelegram) {
          item {
            TelegramUploadBotCard(
              botLink = telegramCreatorBotLink,
              memberPasskey = activeMember?.passkey,
              canConfigureBot = canManageTelegram,
              onCopyLoginCommand = { cmd ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Bot Login Command", cmd))
                Toast.makeText(context, "Copied /login command! Paste it in Telegram Bot.", Toast.LENGTH_SHORT).show()
              },
              onOpenBot = { cleanBotUrl ->
                try {
                  context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cleanBotUrl)))
                } catch (e: Exception) {
                  Toast.makeText(context, "Could not open Telegram: ${e.message}", Toast.LENGTH_SHORT).show()
                }
              },
              onConfigureBot = { showTelegramDialog = true }
            )
          }
        }

        // Telegram Creator Finance Bot Quick Card (for Creators checking earnings/withdrawals & Admins managing payouts)
        if (canViewEarnings || canRequestWithdrawal || canManagePayouts || canManageTelegram) {
          item {
            TelegramFinanceBotCard(
              botLink = telegramFinanceBotLink,
              memberPasskey = activeMember?.passkey,
              isSuperAdmin = isSuperAdmin,
              canConfigureBot = canManageTelegram,
              onCopyLoginCommand = { cmd ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Finance Bot Login Command", cmd))
                Toast.makeText(context, "Copied /login command! Paste it in Finance Bot.", Toast.LENGTH_SHORT).show()
              },
              onOpenBot = { cleanBotUrl ->
                try {
                  context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cleanBotUrl)))
                } catch (e: Exception) {
                  Toast.makeText(context, "Could not open Telegram: ${e.message}", Toast.LENGTH_SHORT).show()
                }
              },
              onConfigureBot = { showTelegramDialog = true }
            )
          }
        }

        // Personal Earnings & Withdrawal section (shown whenever VIEW_EARNINGS or REQUEST_WITHDRAWAL is granted)
        if (!isSuperAdmin && (canViewEarnings || canRequestWithdrawal)) {
          item {
            CreatorEarningsSection(
              summary = myEarningSummary,
              requests = myWithdrawalRequests,
              onRequestWithdrawClick = { showWithdrawDialog = true },
              showWithdrawButton = canRequestWithdrawal,
              creditedViews = if (isCurrentMember) myCreditedViews else null
            )
          }
        }

        // ==========================================================
        // SECTION A: PURE CREATOR PORTAL (when no Admin-level tabs are granted)
        // ==========================================================
        if (isCurrentMember && !hasAdminTabs) {
          if (canUploadVideos || canManageVideos) {
            item {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                StatCard(
                  title = "MY VIDEOS",
                  value = "${myMemberVideos.size}",
                  color = MasalaTextPrimary,
                  modifier = Modifier.weight(1f)
                )
                StatCard(
                  title = "MY TOTAL VIEWS",
                  value = "$myCreditedViews",
                  color = MasalaRedAccent,
                  modifier = Modifier.weight(1f)
                )
                StatCard(
                  title = "ACTIVE VIDEOS",
                  value = "${myMemberVideos.count { it.isActive }}",
                  color = MasalaSuccess,
                  modifier = Modifier.weight(1f)
                )
              }
            }
          }

          if (canUploadVideos && !canManageVideos) {
            item {
              Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MasalaSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                  horizontalAlignment = Alignment.CenterHorizontally
                ) {
                  Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(34.dp))
                  Spacer(modifier = Modifier.height(8.dp))
                  Text("Upload Videos Enabled", color = MasalaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = "Tap the Upload button below to upload videos and generate shareable links.",
                    color = MasalaTextSecondary,
                    fontSize = 12.sp
                  )
                }
              }
            }
          }

          if (canManageVideos) {
            item(key = "creator_videos_header") {
              Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                  text = "My Uploaded Videos (${myMemberVideos.size})",
                  color = MasalaTextPrimary,
                  fontSize = 16.sp,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = "Manage your uploaded videos and shareable active links",
                  color = MasalaTextMuted,
                  fontSize = 11.sp
                )
              }
            }

            if (myMemberVideos.isEmpty()) {
              item {
                Card(
                  shape = RoundedCornerShape(14.dp),
                  colors = CardDefaults.cardColors(containerColor = MasalaSurface),
                  border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                  ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MasalaTextMuted, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("No videos uploaded yet", color = MasalaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                      text = if (canUploadVideos) "Tap the upload button below to upload your first video."
                      else "Videos uploaded to your account will appear here.",
                      color = MasalaTextSecondary,
                      fontSize = 12.sp
                    )
                  }
                }
              }
            } else {
              items(myMemberVideos, key = { it.videoId }) { video ->
                val associatedLinks = accessLinks.filter { it.videoId == video.videoId }
                AdminVideoCard(
                  video = video,
                  links = associatedLinks,
                  canDelete = false,
                  showViewCount = false,
                  shareLinkBaseUrl = shareLinkBaseUrl,
                  onToggleVideoActive = { viewModel.toggleVideoStatus(video) },
                  onToggleLinkActive = { link -> viewModel.toggleLinkStatus(link) },
                  onEditVideo = { editingVideo = video },
                  onDeleteVideo = { },
                  onDeleteLink = { linkId -> viewModel.deleteLink(linkId) },
                  onCopyLink = { rawOrLinkId ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val shareUrl = FirestoreVideoRepository.buildShareableVideoUrl(rawOrLinkId, shareLinkBaseUrl)
                    val clip = ClipData.newPlainText("VidVora Link", shareUrl)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied video link to clipboard", Toast.LENGTH_SHORT).show()
                  },
                  onPlay = { linkOrVideoId ->
                    if (onPlayVideoItem != null) {
                      onPlayVideoItem(video)
                    } else {
                      onPlayVideo(linkOrVideoId)
                    }
                  }
                )
              }
            }
          }
        } else if (visibleTabs.isNotEmpty()) {
          // ==========================================================
          // SECTION B: ADMIN / PERMISSION-GATED MULTI-TAB PANEL
          // ==========================================================
          if (visibleTabs.size > 1) {
            item {
              val tabContent: @Composable () -> Unit = {
                visibleTabs.forEachIndexed { index, tab ->
                  when (tab) {
                    AdminSectionTab.VIDEOS -> Tab(
                      selected = safeTabIndex == index,
                      onClick = { selectedTab = index },
                      icon = { Icon(Icons.Default.VideoFile, contentDescription = null, modifier = Modifier.size(16.dp)) },
                      text = {
                        val countText = if (canManageVideos) {
                          val listSize = if (isCurrentMember) myMemberVideos.size else videos.size
                          "Videos ($listSize)"
                        } else {
                          "Upload"
                        }
                        Text(countText, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, softWrap = false)
                      },
                      selectedContentColor = MasalaRedPrimary,
                      unselectedContentColor = MasalaTextSecondary
                    )
                    AdminSectionTab.MEMBERS -> Tab(
                      selected = safeTabIndex == index,
                      onClick = { selectedTab = index },
                      icon = { Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(16.dp)) },
                      text = {
                        Text("Members (${memberStatsList.size})", fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, softWrap = false)
                      },
                      selectedContentColor = MasalaRedPrimary,
                      unselectedContentColor = MasalaTextSecondary
                    )
                    AdminSectionTab.PAYOUTS -> Tab(
                      selected = safeTabIndex == index,
                      onClick = { selectedTab = index },
                      icon = { Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(16.dp)) },
                      text = {
                        Text(
                          text = if (pendingPayoutsCount > 0) "Payout ($pendingPayoutsCount)" else "Payouts",
                          fontWeight = FontWeight.Bold,
                          fontSize = 10.sp,
                          maxLines = 1,
                          softWrap = false
                        )
                      },
                      selectedContentColor = MasalaRedPrimary,
                      unselectedContentColor = MasalaTextSecondary
                    )
                    AdminSectionTab.NOTIFY -> Tab(
                      selected = safeTabIndex == index,
                      onClick = { selectedTab = index },
                      icon = { Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp)) },
                      text = {
                        Text("Notify", fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, softWrap = false)
                      },
                      selectedContentColor = MasalaRedPrimary,
                      unselectedContentColor = MasalaTextSecondary,
                      modifier = Modifier.testTag("admin_tab_notify")
                    )
                    AdminSectionTab.STATS -> Tab(
                      selected = safeTabIndex == index,
                      onClick = { selectedTab = index },
                      icon = { Icon(Icons.Default.Smartphone, contentDescription = null, modifier = Modifier.size(16.dp)) },
                      text = {
                        Text(
                          text = "Installs (${appInstallStats.totalDownloads})",
                          fontWeight = FontWeight.Bold,
                          fontSize = 10.sp,
                          maxLines = 1,
                          softWrap = false
                        )
                      },
                      selectedContentColor = MasalaRedPrimary,
                      unselectedContentColor = MasalaTextSecondary,
                      modifier = Modifier.testTag("admin_tab_installs")
                    )
                    AdminSectionTab.SUPPORT -> Tab(
                      selected = safeTabIndex == index,
                      onClick = { selectedTab = index },
                      icon = { Icon(Icons.Default.SupportAgent, contentDescription = null, modifier = Modifier.size(16.dp)) },
                      text = {
                        Text(
                          text = if (unreadSupportChatsCount > 0) "Support ($unreadSupportChatsCount)" else "Support",
                          fontWeight = FontWeight.Bold,
                          fontSize = 10.sp,
                          maxLines = 1,
                          softWrap = false
                        )
                      },
                      selectedContentColor = MasalaRedPrimary,
                      unselectedContentColor = MasalaTextSecondary,
                      modifier = Modifier.testTag("admin_tab_support")
                    )
                    AdminSectionTab.EVENTS -> Tab(
                      selected = safeTabIndex == index,
                      onClick = { selectedTab = index },
                      icon = { Icon(Icons.Default.CardGiftcard, contentDescription = null, modifier = Modifier.size(16.dp)) },
                      text = {
                        Text(
                          text = if (activeCreatorEvents.isNotEmpty()) "Events (${activeCreatorEvents.size})" else "Events",
                          fontWeight = FontWeight.Bold,
                          fontSize = 10.sp,
                          maxLines = 1,
                          softWrap = false
                        )
                      },
                      selectedContentColor = MasalaRedPrimary,
                      unselectedContentColor = MasalaTextSecondary,
                      modifier = Modifier.testTag("admin_tab_events")
                    )
                  }
                }
              }

              if (visibleTabs.size > 4) {
                ScrollableTabRow(
                  selectedTabIndex = safeTabIndex,
                  edgePadding = 4.dp,
                  containerColor = MasalaSurface,
                  contentColor = MasalaRedPrimary,
                  indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                      modifier = Modifier.tabIndicatorOffset(tabPositions[safeTabIndex]),
                      color = MasalaRedPrimary
                    )
                  },
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
                ) {
                  tabContent()
                }
              } else {
                TabRow(
                  selectedTabIndex = safeTabIndex,
                  containerColor = MasalaSurface,
                  contentColor = MasalaRedPrimary,
                  indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                      modifier = Modifier.tabIndicatorOffset(tabPositions[safeTabIndex]),
                      color = MasalaRedPrimary
                    )
                  },
                  modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
                ) {
                  tabContent()
                }
              }
            }
          }

          when (currentAdminTab) {
            AdminSectionTab.VIDEOS -> {
              if (canManageVideos) {
                val displayedVideos = if (isCurrentMember) myMemberVideos else videos
                val displayedViews = if (isCurrentMember) myCreditedViews else totalViews
                val displayedActiveLinks = if (isCurrentMember) {
                  val ids = myMemberVideos.map { it.videoId }.toSet()
                  accessLinks.count { it.videoId in ids && it.isActive }
                } else {
                  activeLinksCount
                }

                item {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                  ) {
                    StatCard(
                      title = if (isCurrentMember) "MY VIDEOS" else "TOTAL VIDEOS",
                      value = "${displayedVideos.size}",
                      color = MasalaTextPrimary,
                      modifier = Modifier.weight(1f)
                    )
                    StatCard(
                      title = if (isCurrentMember) "MY VIEWS" else "TOTAL VIEWS",
                      value = "$displayedViews",
                      color = MasalaRedAccent,
                      modifier = Modifier.weight(1f)
                    )
                    StatCard(
                      title = "ACTIVE LINKS",
                      value = "$displayedActiveLinks",
                      color = MasalaSuccess,
                      modifier = Modifier.weight(1f)
                    )
                    if (isSuperAdmin || canManageMembers) {
                      StatCard(
                        title = "MEMBERS",
                        value = "${memberStatsList.size}",
                        color = MasalaGold,
                        modifier = Modifier.weight(1f)
                      )
                    }
                  }
                }
              }

              if (canViewAppStats) {
                item {
                  val statsIndex = visibleTabs.indexOf(AdminSectionTab.STATS)
                  AdminAppInstallsOverviewBanner(
                    stats = appInstallStats,
                    onOpenDetails = { if (statsIndex >= 0) selectedTab = statsIndex }
                  )
                }
              }

              if (canManagePayouts) {
                item {
                  val payoutsIndex = visibleTabs.indexOf(AdminSectionTab.PAYOUTS)
                  AdminWithdrawalSummarySection(
                    requests = allWithdrawalRequests,
                    onOpenPayoutsTab = { if (payoutsIndex >= 0) selectedTab = payoutsIndex },
                    onMarkProcessing = { req -> statusUpdateTarget = req to WithdrawalStatus.PROCESSING },
                    onMarkDone = { req -> statusUpdateTarget = req to WithdrawalStatus.DONE }
                  )
                }
              }

              if (canManageSupportChat) {
                item {
                  val supportIndex = visibleTabs.indexOf(AdminSectionTab.SUPPORT)
                  val openSupportCount = supportChats.count { it.status == SupportChatStatus.OPEN }
                  AdminSupportOverviewBanner(
                    totalChats = supportChats.size,
                    unreadChatsCount = unreadSupportChatsCount,
                    openChatsCount = openSupportCount,
                    onOpenSupportTab = { if (supportIndex >= 0) selectedTab = supportIndex }
                  )
                }
              }

              if (canManageTelegram) {
                item {
                  val configuredCategories = allTelegramCategories.count { it.link.isNotBlank() }
                  val totalCategories = allTelegramCategories.size
                  Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
                    modifier = Modifier
                      .fillMaxWidth()
                      .testTag("admin_telegram_config_card")
                  ) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                      ) {
                        Box(
                          modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF229ED9).copy(alpha = 0.2f)),
                          contentAlignment = Alignment.Center
                        ) {
                          Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = Color(0xFF229ED9),
                            modifier = Modifier.size(20.dp)
                          )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                          Text(
                            text = "Telegram & Share Link Previews",
                            color = MasalaTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                          )
                          Text(
                            text = "$telegramLink • $configuredCategories/$totalCategories categories",
                            color = MasalaGold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                          )
                          Text(
                            text = if (shareLinkBaseUrl.isBlank()) {
                              "Thumbnail + Link Ready • Add Share Preview URL below for automatic link cards"
                            } else {
                              "HD Link Thumbnails Active: $shareLinkBaseUrl"
                            },
                            color = if (shareLinkBaseUrl.isBlank()) MasalaTextSecondary else MasalaSuccess,
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                          )
                        }
                      }

                      Spacer(modifier = Modifier.width(8.dp))

                      OutlinedButton(
                        onClick = { showTelegramDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
                        modifier = Modifier.testTag("edit_telegram_link_button")
                      ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit / Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                      }
                    }
                  }
                }
              }

              if (canUploadVideos && !canManageVideos) {
                item {
                  Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                      horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                      Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(34.dp))
                      Spacer(modifier = Modifier.height(8.dp))
                      Text("Upload Videos Enabled", color = MasalaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                      Spacer(modifier = Modifier.height(4.dp))
                      Text(
                        text = "Tap the Upload button below to upload videos and generate share links.",
                        color = MasalaTextSecondary,
                        fontSize = 12.sp
                      )
                    }
                  }
                }
              }

              if (canManageVideos) {
                val displayedVideos = if (isCurrentMember) myMemberVideos else videos
                item(key = "admin_videos_header") {
                  Text(
                    text = if (isCurrentMember) "My Videos (${displayedVideos.size})" else "All Videos (${displayedVideos.size})",
                    color = MasalaTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                  )
                }

                items(displayedVideos, key = { it.videoId }) { video ->
                  val associatedLinks = accessLinks.filter { it.videoId == video.videoId }
                  AdminVideoCard(
                    video = video,
                    links = associatedLinks,
                    canDelete = isSuperAdmin || (isDelegatedAdmin && canManageVideos),
                    showViewCount = !isCurrentMember,
                    shareLinkBaseUrl = shareLinkBaseUrl,
                    onToggleVideoActive = { viewModel.toggleVideoStatus(video) },
                    onToggleLinkActive = { link -> viewModel.toggleLinkStatus(link) },
                    onEditVideo = { editingVideo = video },
                    onDeleteVideo = { deletingVideoId = video.videoId },
                    onDeleteLink = { linkId -> viewModel.deleteLink(linkId) },
                    onCopyLink = { rawOrLinkId ->
                      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                      val shareUrl = FirestoreVideoRepository.buildShareableVideoUrl(rawOrLinkId, shareLinkBaseUrl)
                      val clip = ClipData.newPlainText("VidVora Link", shareUrl)
                      clipboard.setPrimaryClip(clip)
                      Toast.makeText(context, "Copied video link to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    onPlay = { linkOrVideoId ->
                      if (onPlayVideoItem != null) {
                        onPlayVideoItem(video)
                      } else {
                        onPlayVideo(linkOrVideoId)
                      }
                    }
                  )
                }
              }
            }

            AdminSectionTab.MEMBERS -> {
              // ==========================================================
              // MEMBERS MANAGEMENT TAB
              // ==========================================================
              item {
                val totalMemberVideos = memberStatsList.sumOf { it.videoCount }
                val totalMemberViews = memberStatsList.sumOf { it.totalViews }
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                  StatCard(
                    title = "MEMBERS",
                    value = "${memberStatsList.size}",
                    color = MasalaGold,
                    modifier = Modifier.weight(1f)
                  )
                  StatCard(
                    title = "MEMBER UPLOADS",
                    value = "$totalMemberVideos",
                    color = MasalaTextPrimary,
                    modifier = Modifier.weight(1f)
                  )
                  StatCard(
                    title = "MEMBER VIEWS",
                    value = "$totalMemberViews",
                    color = MasalaRedAccent,
                    modifier = Modifier.weight(1f)
                  )
                }
              }

              item {
                Column(modifier = Modifier.fillMaxWidth()) {
                  Text(
                    text = "Admins & Creators (${memberStatsList.size})",
                    color = MasalaTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = "Add Creators or Admins and choose exact options each user can see",
                    color = MasalaTextMuted,
                    fontSize = 11.sp
                  )
                  Spacer(modifier = Modifier.height(12.dp))
                  Button(
                    onClick = { showAddMemberDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MasalaGold, contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                      .fillMaxWidth()
                      .height(48.dp)
                      .testTag("add_member_button")
                  ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Member (Admin / Creator)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                  }

                  Spacer(modifier = Modifier.height(10.dp))

                  OutlinedTextField(
                    value = memberSearchQuery,
                    onValueChange = { memberSearchQuery = it },
                    placeholder = {
                      Text(
                        text = "Search creator by name or Gmail...",
                        fontSize = 13.sp,
                        color = MasalaTextMuted
                      )
                    },
                    leadingIcon = {
                      Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search creators",
                        tint = MasalaGold
                      )
                    },
                    trailingIcon = {
                      if (memberSearchQuery.isNotEmpty()) {
                        IconButton(
                          onClick = { memberSearchQuery = "" },
                          modifier = Modifier.testTag("clear_member_search_button")
                        ) {
                          Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = MasalaTextSecondary
                          )
                        }
                      }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = defaultTextFieldColors(),
                    modifier = Modifier
                      .fillMaxWidth()
                      .testTag("member_search_input")
                  )

                  if (memberSearchQuery.isNotBlank()) {
                    val matchCount = memberStatsList.count {
                      FirestoreVideoRepository.matchesMemberQuery(it.member, memberSearchQuery)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                      text = "Showing $matchCount of ${memberStatsList.size} creators",
                      color = MasalaGold,
                      fontSize = 11.sp,
                      fontWeight = FontWeight.SemiBold,
                      modifier = Modifier.testTag("member_search_count")
                    )
                  }
                }
              }

              val filteredMemberStatsList = if (memberSearchQuery.isBlank()) {
                memberStatsList
              } else {
                memberStatsList.filter { stats ->
                  FirestoreVideoRepository.matchesMemberQuery(stats.member, memberSearchQuery)
                }
              }

              if (memberStatsList.isEmpty()) {
                item {
                  Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                      horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                      Icon(Icons.Default.Group, contentDescription = null, tint = MasalaTextMuted, modifier = Modifier.size(44.dp))
                      Spacer(modifier = Modifier.height(10.dp))
                      Text("No Upload Members Yet", color = MasalaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                      Spacer(modifier = Modifier.height(6.dp))
                      Text(
                        text = "Tap 'Add Member' above to add a Creator or Sub-Admin with custom options.",
                        color = MasalaTextSecondary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                      )
                    }
                  }
                }
              } else if (filteredMemberStatsList.isEmpty()) {
                item {
                  Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
                    modifier = Modifier
                      .fillMaxWidth()
                      .testTag("member_search_empty_card")
                  ) {
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                      horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                      Icon(Icons.Default.Search, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(38.dp))
                      Spacer(modifier = Modifier.height(10.dp))
                      Text(
                        text = "No Creator Found",
                        color = MasalaTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                      )
                      Spacer(modifier = Modifier.height(6.dp))
                      Text(
                        text = "No creator matches \"${memberSearchQuery.trim()}\". Try another name or Gmail address.",
                        color = MasalaTextSecondary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                      )
                      Spacer(modifier = Modifier.height(12.dp))
                      OutlinedButton(
                        onClick = { memberSearchQuery = "" },
                        border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
                        shape = RoundedCornerShape(8.dp)
                      ) {
                        Text("Clear Search", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                      }
                    }
                  }
                }
              } else {
                items(filteredMemberStatsList, key = { it.member.memberId }) { stats ->
                  MemberCard(
                    stats = stats,
                    canManageEarning = canManagePayouts,
                    onToggleActive = { viewModel.toggleMemberStatus(stats.member.memberId, !stats.member.isActive) },
                    onEditMember = { editingMember = stats.member },
                    onChangeEmail = { changingEmailMemberStats = stats },
                    onSetEarning = { editingEarningMember = stats },
                    onDelete = { deletingMemberId = stats.member.memberId },
                    onCopyPasskey = {
                      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                      val clip = ClipData.newPlainText("Password", stats.member.passkey)
                      clipboard.setPrimaryClip(clip)
                      Toast.makeText(context, "Password copied for ${stats.member.name}", Toast.LENGTH_SHORT).show()
                    }
                  )
                }
              }
            }

            AdminSectionTab.PAYOUTS -> {
              // ==========================================================
              // PAYOUTS & CREATOR EARNINGS TAB
              // ==========================================================
              item {
                val totalPendingOrProcessing = allWithdrawalRequests
                  .filter { it.status == WithdrawalStatus.PENDING || it.status == WithdrawalStatus.PROCESSING }
                  .sumOf { it.amount }
                val totalPaidAll = allWithdrawalRequests
                  .filter { it.status == WithdrawalStatus.DONE }
                  .sumOf { it.amount }

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                  StatCard(
                    title = "OPEN REQUESTS",
                    value = "$pendingPayoutsCount",
                    color = MasalaGold,
                    modifier = Modifier.weight(1f)
                  )
                  StatCard(
                    title = "IN QUEUE",
                    value = formatInr(totalPendingOrProcessing),
                    color = MasalaRedAccent,
                    modifier = Modifier.weight(1f)
                  )
                  StatCard(
                    title = "TOTAL PAID",
                    value = formatInr(totalPaidAll),
                    color = MasalaSuccess,
                    modifier = Modifier.weight(1f)
                  )
                }
              }

              item {
                Column(modifier = Modifier.fillMaxWidth()) {
                  Text(
                    text = "Creators Who Requested Withdrawal (${allWithdrawalRequests.size})",
                    color = MasalaTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = "Creator name, UPI ID, amount and live payment status",
                    color = MasalaTextMuted,
                    fontSize = 11.sp
                  )
                  Spacer(modifier = Modifier.height(8.dp))
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    listOf(
                      "ALL" to "All",
                      WithdrawalStatus.PENDING to "Pending",
                      WithdrawalStatus.PROCESSING to "Processing",
                      WithdrawalStatus.DONE to "Done"
                    ).forEach { (key, label) ->
                      val selected = payoutFilter == key
                      androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = { payoutFilter = key },
                        label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                          selectedContainerColor = MasalaRedPrimary,
                          selectedLabelColor = Color.White,
                          containerColor = MasalaSurface,
                          labelColor = MasalaTextSecondary
                        )
                      )
                    }
                  }
                }
              }

              val filteredWithdrawals = if (payoutFilter == "ALL") {
                allWithdrawalRequests
              } else {
                allWithdrawalRequests.filter { it.status == payoutFilter }
              }

              if (filteredWithdrawals.isEmpty()) {
                item {
                  Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                      horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                      Icon(Icons.Default.Payments, contentDescription = null, tint = MasalaTextMuted, modifier = Modifier.size(40.dp))
                      Spacer(modifier = Modifier.height(10.dp))
                      Text("No Withdrawal Requests", color = MasalaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                      Spacer(modifier = Modifier.height(4.dp))
                      Text(
                        text = "Creators with ₹500+ available balance can request UPI payouts from their dashboard.",
                        color = MasalaTextSecondary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                      )
                    }
                  }
                }
              } else {
                items(filteredWithdrawals, key = { it.requestId }) { request ->
                  AdminWithdrawalRequestCard(
                    request = request,
                    onMarkProcessing = { statusUpdateTarget = request to WithdrawalStatus.PROCESSING },
                    onMarkDone = { statusUpdateTarget = request to WithdrawalStatus.DONE },
                    onReject = { statusUpdateTarget = request to WithdrawalStatus.REJECTED }
                  )
                }
              }

              if (memberStatsList.isNotEmpty()) {
                item {
                  Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                      Text(
                        text = "Creator Earnings Control",
                        color = MasalaTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                      )
                      Text(
                        text = "Tap any creator to credit or update the earning shown on their dashboard",
                        color = MasalaTextMuted,
                        fontSize = 11.sp
                      )
                      Spacer(modifier = Modifier.height(10.dp))
                      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        memberStatsList.forEach { stats ->
                          Row(
                            modifier = Modifier
                              .fillMaxWidth()
                              .clip(RoundedCornerShape(10.dp))
                              .background(MasalaSurfaceVariant)
                              .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                          ) {
                            Column(modifier = Modifier.weight(1f)) {
                              Text(
                                text = stats.member.name,
                                color = MasalaTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                              )
                              Text(
                                text = "Real: ${stats.totalViews} • Panel: ${stats.member.creditedViews} views",
                                color = MasalaTextSecondary,
                                fontSize = 10.sp
                              )
                              Text(
                                text = "Total: ${formatInr(stats.earningSummary.totalEarning)} • Avail: ${formatInr(stats.earningSummary.availableBalance)}",
                                color = MasalaGold,
                                fontSize = 11.sp
                              )
                            }
                            OutlinedButton(
                              onClick = { editingEarningMember = stats },
                              shape = RoundedCornerShape(8.dp),
                              border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.6f)),
                              colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
                              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                              Icon(Icons.Default.CurrencyRupee, contentDescription = null, modifier = Modifier.size(13.dp))
                              Spacer(modifier = Modifier.width(4.dp))
                              Text("Views & ₹", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }

            AdminSectionTab.NOTIFY -> {
              // ==========================================================
              // BROADCAST NOTIFICATIONS TAB
              // ==========================================================
              item {
                val pickableVideos = remember(videos, myMemberVideos, isCurrentMember) {
                  (if (isCurrentMember) myMemberVideos else videos).filter { it.isActive }
                }
                AdminBroadcastComposerCard(
                  availableVideos = pickableVideos,
                  accessLinks = accessLinks,
                  showVideoViewCounts = !isCurrentMember,
                  shareLinkBaseUrl = shareLinkBaseUrl,
                  onSend = { title, message, imageUri, imageUrl, actionUrl, onDone ->
                    viewModel.sendBroadcastNotification(
                      title = title,
                      message = message,
                      imageUri = imageUri,
                      imageUrl = imageUrl,
                      actionUrl = actionUrl,
                      onResult = onDone
                    )
                  }
                )
              }

              item {
                Text(
                  text = "Sent Notifications (${notifications.size})",
                  color = MasalaTextPrimary,
                  fontSize = 16.sp,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.fillMaxWidth()
                )
              }

              if (notifications.isEmpty()) {
                item {
                  Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                      horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                      Icon(Icons.Default.Campaign, contentDescription = null, tint = MasalaTextMuted, modifier = Modifier.size(40.dp))
                      Spacer(modifier = Modifier.height(10.dp))
                      Text("No Broadcasts Sent Yet", color = MasalaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                      Spacer(modifier = Modifier.height(4.dp))
                      Text(
                        text = "Notifications sent from here appear on the phones and home screens of everyone who downloaded VidVora.",
                        color = MasalaTextSecondary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                      )
                    }
                  }
                }
              } else {
                items(notifications, key = { it.notificationId }) { notice ->
                  AdminSentNotificationCard(
                    notification = notice,
                    onOpenLink = { url ->
                      val clean = FirestoreVideoRepository.normalizeNotificationActionUrl(url)
                      if (FirestoreVideoRepository.isVidvoraVideoUrl(clean)) {
                        onPlayVideo(clean)
                      } else if (!com.example.notifications.AppNotificationHelper.openNotificationUrl(context, clean)) {
                        Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                      }
                    },
                    onDelete = { viewModel.deleteNotification(notice.notificationId) }
                  )
                }
              }
            }

            AdminSectionTab.STATS -> {
              adminAppInstallsTabContent(
                stats = appInstallStats,
                installs = appInstalls,
                inactivityWindowDays = inactivityWindowDays,
                statusFilter = installStatusFilter,
                onSelectWindowDays = { days -> viewModel.setInactivityWindowDays(days) },
                onSelectStatusFilter = { filter -> installStatusFilter = filter },
                onToggleMarkedUninstalled = { installId, markUninstalled ->
                  viewModel.toggleInstallMarkedUninstalled(installId, markUninstalled)
                },
                onDeleteInstallRecord = { installId ->
                  viewModel.deleteAppInstallRecord(installId)
                }
              )
            }

            AdminSectionTab.SUPPORT -> {
              adminSupportTabContent(
                chats = supportChats,
                unreadCount = unreadSupportChatsCount,
                statusFilter = supportStatusFilter,
                onSelectStatusFilter = { filter -> supportStatusFilter = filter },
                onOpenConversation = { chat ->
                  viewModel.markSupportChatReadByAdmin(chat.chatId)
                  activeSupportChatId = chat.chatId
                },
                onToggleStatus = { chat ->
                  val nextStatus = if (chat.status == SupportChatStatus.RESOLVED) {
                    SupportChatStatus.OPEN
                  } else {
                    SupportChatStatus.RESOLVED
                  }
                  viewModel.updateSupportChatStatus(chat.chatId, nextStatus)
                },
                onDeleteChat = { chatId ->
                  if (activeSupportChatId == chatId) {
                    activeSupportChatId = null
                  }
                  viewModel.deleteSupportChat(chatId)
                }
              )
            }

            AdminSectionTab.EVENTS -> {
              adminEventsTabContent(
                events = creatorEvents,
                onCreateEventClick = { showCreateEventDialog = true },
                onEditEventClick = { event -> editingCreatorEvent = event },
                onToggleEventActive = { event ->
                  viewModel.updateCreatorEvent(
                    eventId = event.eventId,
                    title = event.title,
                    description = event.description,
                    giftLabel = event.giftLabel,
                    imageUri = null,
                    imageUrl = event.imageUrl,
                    isActive = !event.isActive
                  ) { _, _ -> }
                },
                onDeleteEventClick = { event -> deletingCreatorEventId = event.eventId }
              )
            }

            null -> Unit
          }
        }
      }

      item {
        Spacer(modifier = Modifier.height(72.dp))
      }
    }
  }

  // Upload Video Dialog
  if (showUploadDialog && canUploadVideos) {
    UploadVideoDialog(
      isUploading = isUploading,
      uploadProgress = uploadProgress,
      isMember = activeMember != null,
      memberName = activeMember?.name,
      onDismiss = { if (!isUploading) showUploadDialog = false },
      onUpload = { title, desc, videoUri, thumbUri, streamUrl, customToken ->
        viewModel.uploadVideoAndCreateRecord(
          title = title,
          description = desc,
          videoUri = videoUri,
          thumbnailUri = thumbUri,
          manualStreamUrl = streamUrl,
          customToken = customToken
        ) {
          showUploadDialog = false
        }
      }
    )
  }

  // Add Member Dialog (Choose Admin or Creator + Exact Options)
  if (showAddMemberDialog && canManageMembers) {
    AddMemberDialog(
      onDismiss = { showAddMemberDialog = false },
      onAddMember = { name, emailOrPhone, passkey, role, allowedOptions, onDone ->
        viewModel.addNewMember(
          name = name,
          emailOrPhone = emailOrPhone,
          passkey = passkey,
          role = role,
          allowedOptions = allowedOptions
        ) { success, err ->
          onDone(success, err)
          if (success) {
            memberSearchQuery = ""
            showAddMemberDialog = false
            coroutineScope.launch {
              listState.animateScrollToItem(0)
            }
          }
        }
      }
    )
  }

  // Edit Member Details Dialog (Role + Options + Details)
  if (canManageMembers) {
    editingMember?.let { memberToEdit ->
      EditMemberDialog(
        member = memberToEdit,
        onDismiss = { editingMember = null },
        onSave = { name, emailOrPhone, passkey, isActive, role, allowedOptions, onDone ->
          viewModel.updateMemberDetails(
            memberId = memberToEdit.memberId,
            name = name,
            emailOrPhone = emailOrPhone,
            passkey = passkey,
            isActive = isActive,
            role = role,
            allowedOptions = allowedOptions
          ) { success, err ->
            onDone(success, err)
            if (success) {
              editingMember = null
            }
          }
        }
      )
    }

    changingEmailMemberStats?.let { statsToUpdate ->
      ChangeCreatorEmailDialog(
        memberStats = statsToUpdate,
        allMembers = memberStatsList,
        onDismiss = { changingEmailMemberStats = null },
        onConfirm = { newEmail, onDone ->
          viewModel.changeCreatorEmail(
            sourceMemberId = statsToUpdate.member.memberId,
            newEmail = newEmail
          ) { success, err, _ ->
            onDone(success, err)
            if (success) {
              changingEmailMemberStats = null
            }
          }
        }
      )
    }
  }

  // Change Own Master Key Dialog (for Superadmin, Creator, or Delegated Admin)
  if (showChangeMasterKeyDialog && canChangeMasterKey) {
    ChangeMasterKeyDialog(
      isSuperAdminMode = isSuperAdmin,
      onDismiss = { showChangeMasterKeyDialog = false },
      onSubmit = { currentKey, newKey, confirmKey, onDone ->
        viewModel.changeOwnMasterKey(currentKey, newKey, confirmKey) { success, err ->
          onDone(success, err)
          if (success) {
            showChangeMasterKeyDialog = false
          }
        }
      }
    )
  }

  // Generated Access Link Success Dialog
  lastCreatedResult?.let { result ->
    AccessLinkGeneratedDialog(
      result = result,
      hasDynamicOgPreview = shareLinkBaseUrl.isNotBlank(),
      onDismiss = { viewModel.clearLastCreatedResult() }
    )
  }

  // Edit Video Dialog
  editingVideo?.let { video ->
    EditVideoDialog(
      video = video,
      onDismiss = { editingVideo = null },
      onSave = { newTitle, newDesc ->
        viewModel.editVideo(video.videoId, newTitle, newDesc)
        editingVideo = null
      }
    )
  }

  // Confirm Delete Video Dialog
  deletingVideoId?.let { id ->
    AlertDialog(
      onDismissRequest = { deletingVideoId = null },
      title = { Text("Delete Video and Share Links?", color = MasalaTextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          text = "This will permanently remove this video and all associated shareable links.",
          color = MasalaTextSecondary,
          fontSize = 13.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.deleteVideo(id)
            deletingVideoId = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = MasalaError)
        ) {
          Text("Delete Permanently")
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { deletingVideoId = null }) {
          Text("Cancel")
        }
      },
      containerColor = MasalaSurface,
      shape = RoundedCornerShape(16.dp)
    )
  }

  // Confirm Delete Member Dialog
  deletingMemberId?.let { id ->
    AlertDialog(
      onDismissRequest = { deletingMemberId = null },
      title = { Text("Delete Creator Member?", color = MasalaTextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          text = "This member will lose access to upload videos. Previously uploaded videos will remain saved.",
          color = MasalaTextSecondary,
          fontSize = 13.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.deleteMember(id)
            deletingMemberId = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = MasalaError)
        ) {
          Text("Delete Member")
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { deletingMemberId = null }) {
          Text("Cancel")
        }
      },
      containerColor = MasalaSurface,
      shape = RoundedCornerShape(16.dp)
    )
  }

  // Edit Main & Category-wise Telegram Channel Links Dialog (Add, Edit Name/Link, or Delete ANY Category + Creator Bot)
  if (showTelegramDialog) {
    EditTelegramLinkDialog(
      currentLink = telegramLink,
      currentCreatorBotLink = telegramCreatorBotLink,
      currentFinanceBotLink = telegramFinanceBotLink,
      currentShareLinkBaseUrl = shareLinkBaseUrl,
      currentCategoryLinks = telegramCategoryLinks,
      currentCategoryNames = telegramCategoryNames,
      currentDeletedCategoryKeys = telegramDeletedCategoryKeys,
      currentCustomCategories = telegramCustomCategories,
      onDismiss = { showTelegramDialog = false },
      onSave = { newMainLink, newBotLink, newFinanceBotLink, newShareLinkBaseUrl, newCategoryLinks, newCustomCategories, newCategoryNames, newDeletedKeys, closeAfterSave, statusMsg ->
        viewModel.updateTelegramConfig(
          mainLink = newMainLink,
          categoryLinks = newCategoryLinks,
          customCategories = newCustomCategories,
          categoryNames = newCategoryNames,
          deletedCategoryKeys = newDeletedKeys,
          creatorBotLink = newBotLink,
          financeBotLink = newFinanceBotLink,
          shareLinkBaseUrl = newShareLinkBaseUrl
        ) { success, err ->
          if (success) {
            Toast.makeText(
              context,
              statusMsg ?: "Telegram & Share Preview settings synced to all users!",
              Toast.LENGTH_SHORT
            ).show()
            if (closeAfterSave) {
              showTelegramDialog = false
            }
          } else {
            Toast.makeText(context, "Failed: $err", Toast.LENGTH_LONG).show()
          }
        }
      }
    )
  }

  // Self-Hosted In-App Update Configuration Dialog
  if (showAppUpdateDialog) {
    EditAppUpdateDialog(
      currentConfig = appUpdateConfig,
      currentAppVersionCode = BuildConfig.VERSION_CODE,
      currentAppVersionName = BuildConfig.VERSION_NAME,
      onDismiss = { showAppUpdateDialog = false },
      onSave = { updatedConfig, onDone ->
        viewModel.updateAppUpdateConfig(
          isEnabled = updatedConfig.isEnabled,
          latestVersionCode = updatedConfig.latestVersionCode,
          latestVersionName = updatedConfig.latestVersionName,
          title = updatedConfig.title,
          message = updatedConfig.message,
          apkUrl = updatedConfig.apkUrl,
          isForceUpdate = updatedConfig.isForceUpdate
        ) { success, err ->
          onDone(success, err)
          if (success) {
            showAppUpdateDialog = false
          }
        }
      }
    )
  }

  // Admin Live Support Reply Dialog
  val activeSupportChat = activeSupportChatId?.let { id ->
    supportChats.firstOrNull { it.chatId == id }
  }
  if (activeSupportChat != null && canManageSupportChat) {
    val activeMessages by remember(activeSupportChat.chatId) {
      viewModel.observeSupportChatMessages(activeSupportChat.chatId)
    }.collectAsState(initial = emptyList())

    AdminSupportConversationDialog(
      chat = activeSupportChat,
      messages = activeMessages,
      onSendReply = { text, onDone ->
        viewModel.sendAdminSupportReply(activeSupportChat.chatId, text, onDone)
      },
      onToggleStatus = {
        val nextStatus = if (activeSupportChat.status == SupportChatStatus.RESOLVED) {
          SupportChatStatus.OPEN
        } else {
          SupportChatStatus.RESOLVED
        }
        viewModel.updateSupportChatStatus(activeSupportChat.chatId, nextStatus)
      },
      onDismiss = { activeSupportChatId = null }
    )
  }

  // Creator Ask-for-Support Dialog (Creator messages VidVora Support Team; only Admin replies)
  if (showCreatorSupportDialog && isCurrentMember && !canManageSupportChat && activeMember != null) {
    CreatorSupportChatDialog(
      creatorName = activeMember.name,
      creatorEmail = activeMember.emailOrPhone,
      chat = myCreatorSupportChat,
      messages = myCreatorSupportMessages,
      onSendMessage = { text, onDone ->
        viewModel.sendCreatorSupportMessage(text, onDone)
      },
      onMarkRead = { viewModel.markCreatorSupportChatRead() },
      onDismiss = { showCreatorSupportDialog = false }
    )
  }

  // Creator Events & Gift Announcements Dialog (read-only showcase for Creators)
  if (showCreatorEventsDialog) {
    CreatorEventsDialog(
      events = activeCreatorEvents,
      onDismiss = { showCreatorEventsDialog = false }
    )
  }

  // Admin Create Creator Event Dialog
  if (showCreateEventDialog && canManageEvents) {
    AdminCreatorEventEditorDialog(
      existingEvent = null,
      onDismiss = { showCreateEventDialog = false },
      onSave = { title, description, giftLabel, imageUri, imageUrl, isActive, onDone ->
        viewModel.createCreatorEvent(
          title = title,
          description = description,
          giftLabel = giftLabel,
          imageUri = imageUri,
          imageUrl = imageUrl,
          isActive = isActive
        ) { success, err ->
          onDone(success, err)
          if (success) {
            showCreateEventDialog = false
          }
        }
      }
    )
  }

  // Admin Edit Existing Creator Event Dialog
  if (canManageEvents) {
    editingCreatorEvent?.let { eventToEdit ->
      AdminCreatorEventEditorDialog(
        existingEvent = eventToEdit,
        onDismiss = { editingCreatorEvent = null },
        onSave = { title, description, giftLabel, imageUri, imageUrl, isActive, onDone ->
          viewModel.updateCreatorEvent(
            eventId = eventToEdit.eventId,
            title = title,
            description = description,
            giftLabel = giftLabel,
            imageUri = imageUri,
            imageUrl = imageUrl,
            isActive = isActive
          ) { success, err ->
            onDone(success, err)
            if (success) {
              editingCreatorEvent = null
            }
          }
        }
      )
    }
  }

  // Admin Confirm Delete Creator Event Dialog
  deletingCreatorEventId?.let { eventId ->
    AlertDialog(
      onDismissRequest = { deletingCreatorEventId = null },
      title = { Text("Delete Creator Event?", color = MasalaTextPrimary, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          text = "This will remove this gift/event announcement from Admin and Creator panels.",
          color = MasalaTextSecondary,
          fontSize = 13.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.deleteCreatorEvent(eventId)
            deletingCreatorEventId = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = MasalaError)
        ) {
          Text("Delete Event")
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { deletingCreatorEventId = null }) {
          Text("Cancel")
        }
      },
      containerColor = MasalaSurface,
      shape = RoundedCornerShape(16.dp)
    )
  }

  // Creator UPI Withdrawal Dialog
  if (showWithdrawDialog) {
    val lastSavedUpi = remember(myWithdrawalRequests) {
      myWithdrawalRequests.firstOrNull { it.upiId.isNotBlank() }?.upiId.orEmpty()
    }
    CreatorWithdrawalDialog(
      availableBalance = myEarningSummary.availableBalance,
      savedUpiId = lastSavedUpi,
      onDismiss = { showWithdrawDialog = false },
      onSubmit = { amount, upiId, onDone ->
        viewModel.requestCreatorWithdrawal(amount, upiId, onDone)
      }
    )
  }

  // Admin Manage Creator Views & Earning Dialog
  editingEarningMember?.let { stats ->
    ManageCreatorEarningDialog(
      memberStats = stats,
      onDismiss = { editingEarningMember = null },
      onSaveByViews = { views, ratePer1000, isAddition, onDone ->
        viewModel.creditCreatorViews(stats.member.memberId, views, ratePer1000, isAddition, onDone)
      },
      onSaveManual = { amount, isAddition, onDone ->
        viewModel.updateCreatorEarning(stats.member.memberId, amount, isAddition, onDone)
      }
    )
  }

  // Admin Payout Status Confirmation Dialog (Processing / Done / Reject)
  statusUpdateTarget?.let { (request, targetStatus) ->
    AdminPayoutStatusDialog(
      request = request,
      targetStatus = targetStatus,
      onDismiss = { statusUpdateTarget = null },
      onConfirm = { note, onDone ->
        viewModel.updateWithdrawalStatus(request.requestId, targetStatus, note, onDone)
      }
    )
  }
}

@Composable
private fun StatCard(
  title: String,
  value: String,
  color: Color,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder)
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = title,
        color = MasalaTextMuted,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = value,
        color = color,
        fontSize = 18.sp,
        fontWeight = FontWeight.Black
      )
    }
  }
}

@Composable
private fun MemberCard(
  stats: MemberStats,
  canManageEarning: Boolean = true,
  onToggleActive: () -> Unit,
  onEditMember: () -> Unit,
  onChangeEmail: () -> Unit,
  onSetEarning: () -> Unit,
  onDelete: () -> Unit,
  onCopyPasskey: () -> Unit
) {
  var isExpanded by remember { mutableStateOf(false) }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("member_card_${stats.member.memberId}"),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (stats.member.isActive) MasalaSurface else MasalaSurfaceVariant.copy(alpha = 0.6f)
    ),
    border = androidx.compose.foundation.BorderStroke(
      1.dp,
      if (stats.member.isActive) MasalaCardBorder else MasalaError.copy(alpha = 0.3f)
    )
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          val isAdminAccount = stats.member.isDelegatedAdmin()
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(if (isAdminAccount) MasalaRedPrimary.copy(alpha = 0.2f) else MasalaGold.copy(alpha = 0.15f))
              .border(1.dp, if (isAdminAccount) MasalaRedAccent.copy(alpha = 0.5f) else MasalaGold.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = if (isAdminAccount) Icons.Default.AdminPanelSettings else Icons.Default.Person,
              contentDescription = null,
              tint = if (isAdminAccount) MasalaRedAccent else MasalaGold,
              modifier = Modifier.size(22.dp)
            )
          }

          Spacer(modifier = Modifier.width(10.dp))

          Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = stats.member.name,
                color = MasalaTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
              )
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(5.dp))
                  .background(if (isAdminAccount) MasalaRedPrimary.copy(alpha = 0.22f) else MasalaGold.copy(alpha = 0.16f))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text(
                  text = if (isAdminAccount) "ADMIN" else "CREATOR",
                  color = if (isAdminAccount) MasalaRedAccent else MasalaGold,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.ExtraBold
                )
              }
            }
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Text(
                text = stats.member.emailOrPhone.ifBlank { "No email set" },
                color = MasalaTextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
              )
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(6.dp))
                  .background(MasalaGold.copy(alpha = 0.14f))
                  .border(1.dp, MasalaGold.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                  .clickable(onClick = onChangeEmail)
                  .padding(horizontal = 7.dp, vertical = 3.dp)
                  .testTag("change_email_button_${stats.member.memberId}")
              ) {
                Text(
                  text = "Change Email",
                  color = MasalaGold,
                  fontSize = 9.5.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }

        // Active/Inactive Switch
        Switch(
          checked = stats.member.isActive,
          onCheckedChange = { onToggleActive() },
          colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MasalaSuccess,
            uncheckedThumbColor = MasalaTextMuted,
            uncheckedTrackColor = MasalaSurfaceVariant
          )
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      val grantedLabels = stats.member.allowedOptions.mapNotNull { MemberOption.fromKey(it)?.label }
      Text(
        text = if (grantedLabels.isEmpty()) {
          "Allowed Options: None"
        } else {
          "Options (${grantedLabels.size}): ${grantedLabels.joinToString(", ")}"
        },
        color = MasalaTextSecondary,
        fontSize = 10.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(10.dp))

      // Member Stats Pills: Videos, Real Views (Admin-only), Creator Panel Views, & Earning
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(MasalaSurfaceVariant)
              .border(1.dp, MasalaCardBorder, RoundedCornerShape(8.dp))
              .padding(vertical = 8.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text("VIDEOS", color = MasalaTextMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
              Text("${stats.videoCount}", color = MasalaTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Black)
            }
          }

          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(MasalaSurfaceVariant)
              .border(1.dp, MasalaRedAccent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
              .padding(vertical = 8.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text("REAL VIEWS (ADMIN)", color = MasalaRedAccent, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
              Text("${stats.totalViews}", color = MasalaRedAccent, fontSize = 15.sp, fontWeight = FontWeight.Black)
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(MasalaSurfaceVariant)
              .border(1.dp, MasalaSuccess.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
              .padding(vertical = 8.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text("CREATOR VIEWS", color = MasalaSuccess, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
              Text("${stats.member.creditedViews}", color = MasalaSuccess, fontSize = 15.sp, fontWeight = FontWeight.Black)
            }
          }

          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(MasalaSurfaceVariant)
              .border(1.dp, MasalaGold.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
              .padding(vertical = 8.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text("EARNING", color = MasalaGold, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
              Text(
                text = formatInr(stats.earningSummary.totalEarning),
                color = MasalaGold,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Member Passkey & Copy
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .background(MasalaBlack.copy(alpha = 0.4f))
          .border(1.dp, MasalaCardBorder, RoundedCornerShape(8.dp))
          .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Icon(Icons.Default.Key, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Password: ${stats.member.passkey}",
            color = MasalaGold,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
          )
        }

        IconButton(onClick = onCopyPasskey, modifier = Modifier.size(28.dp)) {
          Icon(Icons.Default.ContentCopy, contentDescription = "Copy Password", tint = MasalaTextSecondary, modifier = Modifier.size(15.dp))
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Actions row: Expand uploaded videos, Edit details, Set Earning, & Delete member
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.weight(1f)
        ) {
          OutlinedButton(
            onClick = { isExpanded = !isExpanded },
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextSecondary),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
          ) {
            Text(
              text = if (isExpanded) "Hide" else "${stats.videoCount} Videos",
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1
            )
          }

          OutlinedButton(
            onClick = onEditMember,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaTextSecondary.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextPrimary),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.testTag("edit_member_button_${stats.member.memberId}")
          ) {
            Icon(Icons.Default.Edit, contentDescription = "Edit Member", modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(3.dp))
            Text("Edit", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }

          if (canManageEarning) {
            OutlinedButton(
              onClick = onSetEarning,
              shape = RoundedCornerShape(8.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.6f)),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
              modifier = Modifier.testTag("set_earning_button_${stats.member.memberId}")
            ) {
              Icon(Icons.Default.CurrencyRupee, contentDescription = null, modifier = Modifier.size(13.dp))
              Spacer(modifier = Modifier.width(2.dp))
              Text("Views & ₹", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
          }
        }

        IconButton(
          onClick = onDelete,
          modifier = Modifier
            .size(48.dp)
            .testTag("delete_member_button_${stats.member.memberId}")
        ) {
          Icon(Icons.Default.Delete, contentDescription = "Delete Member", tint = MasalaError.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
        }
      }

      // Expanded video list for this member
      if (isExpanded) {
        Spacer(modifier = Modifier.height(10.dp))
        if (stats.videos.isEmpty()) {
          Text("This member has not uploaded any videos yet.", color = MasalaTextMuted, fontSize = 11.sp)
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            stats.videos.forEach { v ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .background(MasalaSurfaceVariant)
                  .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(v.title, color = MasalaTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                  Text(
                    text = "ID: ${v.videoId} • ${if (v.isActive) "Active" else "Inactive"}",
                    color = if (v.isActive) MasalaSuccess else MasalaError,
                    fontSize = 10.sp
                  )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.Visibility, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(13.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("${v.totalViews} views", color = MasalaGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AdminVideoCard(
  video: FirestoreVideo,
  links: List<FirestoreAccessLink>,
  canDelete: Boolean = true,
  showViewCount: Boolean = true,
  shareLinkBaseUrl: String = "",
  onToggleVideoActive: () -> Unit,
  onToggleLinkActive: (FirestoreAccessLink) -> Unit,
  onEditVideo: () -> Unit,
  onDeleteVideo: () -> Unit,
  onDeleteLink: (String) -> Unit,
  onCopyLink: (String) -> Unit,
  onPlay: (String) -> Unit
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("admin_video_card_${video.videoId}"),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (video.isActive) MasalaSurface else MasalaSurfaceVariant.copy(alpha = 0.6f)
    ),
    border = androidx.compose.foundation.BorderStroke(
      1.dp,
      if (video.isActive) MasalaCardBorder else MasalaError.copy(alpha = 0.3f)
    )
  ) {
    Column(
      modifier = Modifier.padding(14.dp)
    ) {
      // Header row with status badge, admin-only views, and video toggle switch
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .background(
                if (video.isActive) MasalaSuccess.copy(alpha = 0.15f) else MasalaError.copy(alpha = 0.15f),
                RoundedCornerShape(6.dp)
              )
              .border(
                1.dp,
                if (video.isActive) MasalaSuccess.copy(alpha = 0.4f) else MasalaError.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp)
              )
              .padding(horizontal = 8.dp, vertical = 3.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = if (video.isActive) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (video.isActive) MasalaSuccess else MasalaError,
                modifier = Modifier.size(12.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = if (video.isActive) "VIDEO ACTIVE" else "VIDEO INACTIVE",
                color = if (video.isActive) MasalaSuccess else MasalaError,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }

          if (showViewCount) {
            Spacer(modifier = Modifier.width(10.dp))

            // Real view count (visible ONLY in Admin Panel)
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Visibility, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(13.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "${video.totalViews} views",
                color = MasalaGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
          }
        }

        // Master Video Active/Inactive switch
        Switch(
          checked = video.isActive,
          onCheckedChange = { onToggleVideoActive() },
          colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MasalaSuccess,
            uncheckedThumbColor = MasalaTextMuted,
            uncheckedTrackColor = MasalaSurfaceVariant
          )
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Thumbnail (stored OR auto-extracted from video) + Title & Description
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
      ) {
        VideoThumbnailBox(
          thumbnailPath = video.thumbnailPath,
          storagePath = video.storagePath,
          videoKey = video.videoId,
          contentDescription = "Thumbnail for ${video.title}",
          cornerRadius = 10.dp,
          showPlayBadge = true,
          playBadgeSize = 28.dp,
          onClick = { onPlay(links.firstOrNull()?.linkId ?: video.videoId) },
          modifier = Modifier
            .width(104.dp)
            .height(68.dp)
            .testTag("video_thumbnail_${video.videoId}")
        )
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = video.title,
            color = MasalaTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
          )
          if (video.description.isNotBlank()) {
            Text(
              text = video.description,
              color = MasalaTextMuted,
              fontSize = 12.sp,
              maxLines = 2,
              modifier = Modifier.padding(top = 2.dp)
            )
          }

          // Uploader attribution badge
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
          ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "Uploaded by: ${video.uploaderName}",
              color = MasalaGold,
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Storage path tag
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(13.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = video.storagePath,
          color = MasalaTextMuted,
          fontSize = 11.sp,
          maxLines = 1
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Associated Video Share Links
      Text(
        text = "VIDEO SHARE LINKS (${links.size})",
        color = MasalaGold,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp
      )

      Spacer(modifier = Modifier.height(6.dp))

      if (links.isEmpty()) {
        Text(
          text = "Video link: ${FirestoreVideoRepository.buildShareableVideoUrl(video.videoId, shareLinkBaseUrl)}",
          color = MasalaTextMuted,
          fontSize = 11.sp
        )
      } else {
        links.forEach { link ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 3.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(MasalaSurfaceVariant)
              .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = FirestoreVideoRepository.buildShareableVideoUrl(link.linkId, shareLinkBaseUrl),
                color = MasalaTextPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
              Text(
                text = if (link.isActive) "Active Link" else "Deactivated Link",
                color = if (link.isActive) MasalaSuccess else MasalaError,
                fontSize = 10.sp
              )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
              // Individual link toggle switch
              Switch(
                checked = link.isActive,
                onCheckedChange = { onToggleLinkActive(link) },
                colors = SwitchDefaults.colors(
                  checkedThumbColor = Color.White,
                  checkedTrackColor = MasalaSuccess,
                  uncheckedThumbColor = MasalaTextMuted,
                  uncheckedTrackColor = MasalaCardBorder
                ),
                modifier = Modifier.size(36.dp)
              )

              IconButton(
                onClick = {
                  val shareUrl = FirestoreVideoRepository.buildShareableVideoUrl(link.linkId, shareLinkBaseUrl)
                  coroutineScope.launch {
                    VideoShareHelper.shareVideo(
                      context = context,
                      title = video.title,
                      videoId = video.videoId,
                      thumbnailPath = video.thumbnailPath,
                      storagePath = video.storagePath,
                      shareUrl = shareUrl,
                      hasDynamicOgPreview = shareLinkBaseUrl.isNotBlank()
                    )
                  }
                },
                modifier = Modifier.size(32.dp)
              ) {
                Icon(Icons.Default.Share, contentDescription = "Share Link", tint = MasalaTextPrimary, modifier = Modifier.size(16.dp))
              }

              IconButton(
                onClick = { onCopyLink(link.linkId) },
                modifier = Modifier.size(32.dp)
              ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Link", tint = MasalaGold, modifier = Modifier.size(16.dp))
              }

              IconButton(
                onClick = { onDeleteLink(link.linkId) },
                modifier = Modifier.size(32.dp)
              ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Link", tint = MasalaError.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Bottom Row: Watch, Edit, Delete
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = { onPlay(links.firstOrNull()?.linkId ?: video.videoId) },
            colors = ButtonDefaults.buttonColors(
              containerColor = MasalaRedPrimary,
              contentColor = Color.White
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
          ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Watch", fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }

          OutlinedButton(
            onClick = onEditVideo,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextPrimary),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
          ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Edit", fontSize = 12.sp)
          }
        }

        if (canDelete) {
          IconButton(
            onClick = onDeleteVideo,
            modifier = Modifier.size(36.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Delete,
              contentDescription = "Delete Video",
              tint = MasalaError.copy(alpha = 0.8f),
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }
    }
  }
}

private fun resolveVideoDisplayFileName(context: Context, uri: Uri): String? {
  try {
    context.contentResolver.query(
      uri,
      arrayOf(OpenableColumns.DISPLAY_NAME),
      null,
      null,
      null
    )?.use { cursor ->
      if (cursor.moveToFirst()) {
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0) {
          val displayName = cursor.getString(index)?.trim()
          if (!displayName.isNullOrBlank()) {
            return displayName
          }
        }
      }
    }
  } catch (_: Exception) {
  }

  val rawSegment = uri.lastPathSegment ?: return null
  val decoded = Uri.decode(rawSegment)
  return decoded
    .substringAfterLast('/')
    .substringAfterLast(':')
    .trim()
    .takeIf { it.isNotBlank() }
}

internal fun formatFileNameAsTitle(rawFileName: String?): String {
  val clean = rawFileName
    ?.trim()
    ?.substringAfterLast('/')
    ?.trim()
    .orEmpty()
  if (clean.isBlank()) return ""
  val withoutExtension = clean.substringBeforeLast('.').trim()
  return if (withoutExtension.isNotBlank()) withoutExtension else clean
}

@Composable
private fun UploadVideoDialog(
  isUploading: Boolean,
  uploadProgress: Float,
  isMember: Boolean = false,
  memberName: String? = null,
  onDismiss: () -> Unit,
  onUpload: (title: String, desc: String, videoUri: Uri?, thumbUri: Uri?, streamUrl: String?, customToken: String?) -> Unit
) {
  val context = LocalContext.current
  var videoTitle by remember { mutableStateOf("") }
  var hasManualTitleEdit by remember { mutableStateOf(false) }
  var manualStreamUrl by remember { mutableStateOf("") }
  var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
  var selectedThumbnailUri by remember { mutableStateOf<Uri?>(null) }
  var selectedFileName by remember { mutableStateOf<String?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  // Zero-permission Android Photo Picker for optional custom thumbnail image
  val thumbPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
  ) { uri: Uri? ->
    if (uri != null) {
      selectedThumbnailUri = uri
    }
  }

  // Zero-permission Android SAF video picker preserving original file display name
  val videoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    selectedVideoUri = uri
    if (uri != null) {
      try {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
      } catch (_: Exception) {
      }
      val resolvedName = resolveVideoDisplayFileName(context, uri)
      selectedFileName = resolvedName
      val cleanTitle = formatFileNameAsTitle(resolvedName)
      if (!hasManualTitleEdit || videoTitle.isBlank()) {
        if (cleanTitle.isNotBlank()) {
          videoTitle = cleanTitle
        }
      }
    } else {
      selectedFileName = null
    }
    errorMessage = null
  }

  Dialog(onDismissRequest = { if (!isUploading) onDismiss() }) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = MasalaSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("upload_video_dialog")
    ) {
      Column(
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .padding(20.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = "Upload Video",
              color = MasalaTextPrimary,
              fontSize = 17.sp,
              fontWeight = FontWeight.Bold
            )
            if (isMember && !memberName.isNullOrBlank()) {
              Text(
                text = "Attributed to: $memberName",
                color = MasalaGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
          }

          Box(
            modifier = Modifier
              .background(MasalaRedPrimary.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
              .padding(horizontal = 8.dp, vertical = 4.dp)
          ) {
            Text(
              text = if (isMember) "CREATOR UPLOAD" else "VIDEO UPLOAD",
              color = MasalaRedAccent,
              fontSize = 9.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Device Video Picker Button
        OutlinedButton(
          onClick = { videoPickerLauncher.launch(arrayOf("video/*")) },
          enabled = !isUploading,
          colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (selectedVideoUri != null) MasalaSuccess else MasalaTextPrimary
          ),
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selectedVideoUri != null) MasalaSuccess else MasalaCardBorder
          ),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
        ) {
          Icon(Icons.Default.VideoFile, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = if (selectedVideoUri != null) {
              "Selected: ${selectedFileName ?: "Video File"}"
            } else {
              "Select Video File from Device"
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Auto-populated Video Name from selected file (editable)
        OutlinedTextField(
          value = videoTitle,
          onValueChange = {
            videoTitle = it
            hasManualTitleEdit = it.isNotBlank()
            errorMessage = null
          },
          label = { Text("Video Name (auto from file)") },
          placeholder = { Text("Uses your file name automatically") },
          singleLine = true,
          enabled = !isUploading,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("upload_video_title_input"),
          colors = defaultTextFieldColors()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Or remote Stream URL
        OutlinedTextField(
          value = manualStreamUrl,
          onValueChange = { url ->
            manualStreamUrl = url
            if (!hasManualTitleEdit && selectedVideoUri == null) {
              val urlFileName = url.trim()
                .substringAfterLast('/')
                .substringBefore('?')
              val cleanUrlTitle = formatFileNameAsTitle(Uri.decode(urlFileName))
              if (cleanUrlTitle.isNotBlank()) {
                videoTitle = cleanUrlTitle
              }
            }
            errorMessage = null
          },
          label = { Text("Or Paste Video Stream URL") },
          placeholder = { Text("https://example.com/video.mp4") },
          singleLine = true,
          enabled = !isUploading,
          modifier = Modifier.fillMaxWidth(),
          colors = defaultTextFieldColors()
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "Tip: Use a public Video Stream URL (https://...) so viewers on WhatsApp can play it on any phone.",
          color = MasalaTextMuted,
          fontSize = 11.sp,
          lineHeight = 15.sp
        )

        // Live Video Thumbnail Preview + Optional Custom Thumbnail Picker
        val previewSource = selectedVideoUri?.toString()
          ?: manualStreamUrl.trim().takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
          }
          ?: ""
        if (selectedThumbnailUri != null || previewSource.isNotBlank()) {
          Spacer(modifier = Modifier.height(12.dp))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(MasalaSurfaceVariant)
              .border(1.dp, MasalaCardBorder, RoundedCornerShape(10.dp))
              .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            VideoThumbnailBox(
              thumbnailPath = selectedThumbnailUri?.toString(),
              storagePath = previewSource,
              videoKey = "upload_preview_${selectedThumbnailUri}_${previewSource.hashCode()}",
              contentDescription = "Video thumbnail preview",
              cornerRadius = 8.dp,
              showPlayBadge = selectedThumbnailUri == null,
              playBadgeSize = 24.dp,
              modifier = Modifier
                .width(92.dp)
                .height(58.dp)
                .testTag("upload_dialog_thumbnail_preview")
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = if (selectedThumbnailUri != null) "Custom Thumbnail Selected" else "Auto Video Thumbnail",
                color = MasalaTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = if (selectedThumbnailUri != null) "Shown on your video list card" else "Auto-captured from video frame",
                color = MasalaTextSecondary,
                fontSize = 10.sp
              )
              Spacer(modifier = Modifier.height(6.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                  onClick = {
                    thumbPickerLauncher.launch(
                      PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                  },
                  enabled = !isUploading,
                  shape = RoundedCornerShape(7.dp),
                  border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.6f)),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
                  contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                  modifier = Modifier
                    .height(30.dp)
                    .testTag("upload_pick_custom_thumbnail_button")
                ) {
                  Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(13.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = if (selectedThumbnailUri != null) "Change" else "Custom Thumb",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
                if (selectedThumbnailUri != null) {
                  OutlinedButton(
                    onClick = { selectedThumbnailUri = null },
                    enabled = !isUploading,
                    shape = RoundedCornerShape(7.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaError.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaError),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                  ) {
                    Text("Reset", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                  }
                }
              }
            }
          }
        }

        if (errorMessage != null) {
          Text(
            text = errorMessage ?: "",
            color = MasalaError,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
          )
        }

        if (isUploading) {
          Spacer(modifier = Modifier.height(14.dp))
          Text(
            text = "Uploading Video: ${uploadProgress.toInt()}%",
            color = MasalaGold,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(6.dp))
          LinearProgressIndicator(
            progress = { uploadProgress / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = MasalaRedPrimary,
            trackColor = MasalaCardBorder
          )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            enabled = !isUploading,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextSecondary),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
          ) {
            Text("Cancel")
          }

          Button(
            onClick = {
              if (selectedVideoUri == null && manualStreamUrl.isBlank()) {
                errorMessage = "Please select a video file or enter a video URL"
                return@Button
              }
              val autoTitle = videoTitle.trim()
                .takeIf { it.isNotBlank() }
                ?: formatFileNameAsTitle(selectedFileName)
                  .takeIf { it.isNotBlank() }
                ?: formatFileNameAsTitle(selectedVideoUri?.let { resolveVideoDisplayFileName(context, it) })
                  .takeIf { it.isNotBlank() }
                ?: formatFileNameAsTitle(
                  Uri.decode(
                    manualStreamUrl.trim()
                      .substringAfterLast('/')
                      .substringBefore('?')
                  )
                ).takeIf { it.isNotBlank() }
                ?: "Video ${java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"

              onUpload(autoTitle, "", selectedVideoUri, selectedThumbnailUri, manualStreamUrl.ifBlank { null }, null)
            },
            enabled = !isUploading,
            colors = ButtonDefaults.buttonColors(
              containerColor = MasalaRedPrimary,
              contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
          ) {
            Text(if (isUploading) "Uploading..." else "Upload & Get Link", fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

private enum class AdminSectionTab {
  VIDEOS,
  MEMBERS,
  PAYOUTS,
  NOTIFY,
  STATS,
  SUPPORT,
  EVENTS
}

@Composable
private fun MemberRoleAndOptionsSelector(
  selectedRole: String,
  selectedOptions: Set<String>,
  enabled: Boolean = true,
  onSelectRole: (String) -> Unit,
  onToggleOption: (String) -> Unit,
  onApplyRoleDefaults: () -> Unit,
  onSelectAllOptions: () -> Unit
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = "Choose Role *",
      color = MasalaTextPrimary,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(6.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      val isCreatorSelected = MemberRole.normalize(selectedRole) == MemberRole.CREATOR
      val isAdminSelected = MemberRole.normalize(selectedRole) == MemberRole.ADMIN

      Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
          containerColor = if (isCreatorSelected) MasalaGold.copy(alpha = 0.16f) else MasalaSurfaceVariant
        ),
        border = androidx.compose.foundation.BorderStroke(
          width = if (isCreatorSelected) 1.5.dp else 1.dp,
          color = if (isCreatorSelected) MasalaGold else MasalaCardBorder
        ),
        modifier = Modifier
          .weight(1f)
          .heightIn(min = 56.dp)
          .clickable(enabled = enabled) { onSelectRole(MemberRole.CREATOR) }
          .testTag("role_option_creator")
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "Creator Role",
            tint = if (isCreatorSelected) MasalaGold else MasalaTextMuted,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Column {
            Text(
              text = "Creator",
              color = if (isCreatorSelected) MasalaGold else MasalaTextPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Creator portal",
              color = MasalaTextMuted,
              fontSize = 10.sp
            )
          }
        }
      }

      Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
          containerColor = if (isAdminSelected) MasalaRedPrimary.copy(alpha = 0.18f) else MasalaSurfaceVariant
        ),
        border = androidx.compose.foundation.BorderStroke(
          width = if (isAdminSelected) 1.5.dp else 1.dp,
          color = if (isAdminSelected) MasalaRedAccent else MasalaCardBorder
        ),
        modifier = Modifier
          .weight(1f)
          .heightIn(min = 56.dp)
          .clickable(enabled = enabled) { onSelectRole(MemberRole.ADMIN) }
          .testTag("role_option_admin")
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.AdminPanelSettings,
            contentDescription = "Admin Role",
            tint = if (isAdminSelected) MasalaRedAccent else MasalaTextMuted,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Column {
            Text(
              text = "Admin",
              color = if (isAdminSelected) MasalaRedAccent else MasalaTextPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Sub-admin panel",
              color = MasalaTextMuted,
              fontSize = 10.sp
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "Allowed Options (${selectedOptions.size}/${MemberOption.entries.size}) *",
        color = MasalaTextPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Default",
          color = MasalaGold,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MasalaGold.copy(alpha = 0.14f))
            .clickable(enabled = enabled) { onApplyRoleDefaults() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Text(
          text = "Select All",
          color = MasalaTextPrimary,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MasalaSurfaceVariant)
            .clickable(enabled = enabled) { onSelectAllOptions() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
        )
      }
    }

    Text(
      text = "Only the selected options below will be shown to this user after login.",
      color = MasalaTextMuted,
      fontSize = 10.sp,
      modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      MemberOption.entries.forEach { option ->
        val isChecked = option.key in selectedOptions
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isChecked) MasalaGold.copy(alpha = 0.08f) else MasalaSurfaceVariant)
            .border(
              1.dp,
              if (isChecked) MasalaGold.copy(alpha = 0.45f) else MasalaCardBorder,
              RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled) { onToggleOption(option.key) }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("option_checkbox_${option.key}"),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Checkbox(
            checked = isChecked,
            onCheckedChange = if (enabled) {
              { onToggleOption(option.key) }
            } else null,
            colors = CheckboxDefaults.colors(
              checkedColor = MasalaGold,
              uncheckedColor = MasalaTextMuted,
              checkmarkColor = Color.Black
            ),
            modifier = Modifier.size(32.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = option.label,
              color = if (isChecked) MasalaTextPrimary else MasalaTextSecondary,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = option.description,
              color = MasalaTextMuted,
              fontSize = 10.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AddMemberDialog(
  onDismiss: () -> Unit,
  onAddMember: (
    name: String,
    emailOrPhone: String,
    passkey: String,
    role: String,
    allowedOptions: List<String>,
    onDone: (Boolean, String?) -> Unit
  ) -> Unit
) {
  var name by remember { mutableStateOf("") }
  var emailOrPhone by remember { mutableStateOf("") }
  var passkey by remember { mutableStateOf("") }
  var selectedRole by remember { mutableStateOf(MemberRole.CREATOR) }
  var selectedOptions by remember {
    mutableStateOf(MemberOption.defaultKeysForRole(MemberRole.CREATOR).toSet())
  }
  var isSaving by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MasalaSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.5f)),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("add_member_dialog")
    ) {
      Column(
        modifier = Modifier
          .padding(18.dp)
          .verticalScroll(rememberScrollState())
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(MasalaGold.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(20.dp))
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text("Add Admin or Creator", color = MasalaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Choose role & exact options they can see", color = MasalaTextMuted, fontSize = 11.sp)
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
          value = name,
          onValueChange = { name = it; errorMessage = null },
          label = { Text("Name *") },
          singleLine = true,
          enabled = !isSaving,
          modifier = Modifier.fillMaxWidth(),
          colors = defaultTextFieldColors()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = emailOrPhone,
          onValueChange = { emailOrPhone = it; errorMessage = null },
          label = { Text("Email Address *") },
          singleLine = true,
          enabled = !isSaving,
          modifier = Modifier.fillMaxWidth(),
          colors = defaultTextFieldColors()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = passkey,
          onValueChange = { passkey = it; errorMessage = null },
          label = { Text("Password *") },
          singleLine = true,
          enabled = !isSaving,
          modifier = Modifier.fillMaxWidth(),
          colors = defaultTextFieldColors()
        )

        Spacer(modifier = Modifier.height(14.dp))

        MemberRoleAndOptionsSelector(
          selectedRole = selectedRole,
          selectedOptions = selectedOptions,
          enabled = !isSaving,
          onSelectRole = { newRole ->
            selectedRole = newRole
            selectedOptions = MemberOption.defaultKeysForRole(newRole).toSet()
            errorMessage = null
          },
          onToggleOption = { key ->
            selectedOptions = if (key in selectedOptions) selectedOptions - key else selectedOptions + key
            errorMessage = null
          },
          onApplyRoleDefaults = {
            selectedOptions = MemberOption.defaultKeysForRole(selectedRole).toSet()
            errorMessage = null
          },
          onSelectAllOptions = {
            selectedOptions = MemberOption.allKeys().toSet()
            errorMessage = null
          }
        )

        if (errorMessage != null) {
          Text(
            text = errorMessage ?: "",
            color = MasalaError,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            enabled = !isSaving,
            modifier = Modifier.weight(1f)
          ) {
            Text("Cancel")
          }
          Button(
            onClick = {
              val validationErr = FirestoreVideoRepository.validateMemberInput(name, emailOrPhone, passkey)
              if (validationErr != null) {
                errorMessage = validationErr
                return@Button
              }
              if (selectedOptions.isEmpty()) {
                errorMessage = "Please select at least 1 option for this user"
                return@Button
              }
              isSaving = true
              val orderedOptions = MemberOption.sanitizeKeys(selectedOptions)
              onAddMember(
                name.trim(),
                emailOrPhone.trim(),
                passkey.trim(),
                selectedRole,
                orderedOptions
              ) { success, err ->
                isSaving = false
                if (!success) {
                  errorMessage = err ?: "Failed to add member"
                }
              }
            },
            enabled = !isSaving,
            colors = ButtonDefaults.buttonColors(containerColor = MasalaGold, contentColor = Color.Black),
            modifier = Modifier
              .weight(1f)
              .testTag("confirm_add_member_button")
          ) {
            Text(
              text = when {
                isSaving -> "Adding..."
                selectedRole == MemberRole.ADMIN -> "Add Admin"
                else -> "Add Creator"
              },
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AccessLinkGeneratedDialog(
  result: VideoCreationResult,
  hasDynamicOgPreview: Boolean = false,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = MasalaSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("access_link_generated_dialog")
    ) {
      Column(
        modifier = Modifier.padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Box(
          modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(MasalaSuccess.copy(alpha = 0.2f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(Icons.Default.CloudDone, contentDescription = null, tint = MasalaSuccess, modifier = Modifier.size(30.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = "Video Link Ready!",
          color = MasalaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )

        Text(
          text = "Share this video link so viewers can paste or open it to watch immediately.",
          color = MasalaTextMuted,
          fontSize = 12.sp,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Shareable Link Box
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaCardBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
        ) {
          Text("SHAREABLE VIDEO LINK", color = MasalaGold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = result.shareableLink,
            color = Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3
          )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = {
              coroutineScope.launch {
                VideoShareHelper.shareVideo(
                  context = context,
                  title = result.video.title,
                  videoId = result.video.videoId,
                  thumbnailPath = result.video.thumbnailPath,
                  storagePath = result.video.storagePath,
                  shareUrl = result.shareableLink,
                  hasDynamicOgPreview = hasDynamicOgPreview
                )
              }
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
          ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Share", fontSize = 12.sp)
          }

          Button(
            onClick = {
              val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              val clip = ClipData.newPlainText("VidVora Video Link", result.shareableLink)
              clipboard.setPrimaryClip(clip)
              Toast.makeText(context, "Copied Video Link!", Toast.LENGTH_SHORT).show()
              onDismiss()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MasalaRedPrimary),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy Link", fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

@Composable
private fun EditVideoDialog(
  video: FirestoreVideo,
  onDismiss: () -> Unit,
  onSave: (newTitle: String, newDesc: String) -> Unit
) {
  var title by remember { mutableStateOf(video.title) }
  var description by remember { mutableStateOf(video.description) }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MasalaSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("edit_video_dialog")
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        Text("Edit Video Metadata", color = MasalaTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Video Title") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          colors = defaultTextFieldColors()
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = description,
          onValueChange = { description = it },
          label = { Text("Description") },
          maxLines = 3,
          modifier = Modifier.fillMaxWidth(),
          colors = defaultTextFieldColors()
        )

        Spacer(modifier = Modifier.height(18.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
            Text("Cancel")
          }
          Button(
            onClick = { onSave(title, description) },
            colors = ButtonDefaults.buttonColors(containerColor = MasalaRedPrimary),
            modifier = Modifier.weight(1f)
          ) {
            Text("Save Changes", fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

@Composable
private fun defaultTextFieldColors() = OutlinedTextFieldDefaults.colors(
  focusedBorderColor = MasalaRedPrimary,
  unfocusedBorderColor = MasalaCardBorder,
  focusedTextColor = MasalaTextPrimary,
  unfocusedTextColor = MasalaTextPrimary,
  focusedContainerColor = MasalaSurfaceVariant,
  unfocusedContainerColor = MasalaSurfaceVariant,
  focusedLabelColor = MasalaGold,
  unfocusedLabelColor = MasalaTextMuted
)

@Composable
private fun TelegramUploadBotCard(
  botLink: String,
  memberPasskey: String?,
  canConfigureBot: Boolean,
  onCopyLoginCommand: (String) -> Unit,
  onOpenBot: (String) -> Unit,
  onConfigureBot: () -> Unit
) {
  val cleanBotUrl = FirestoreVideoRepository.normalizeTelegramUrl(botLink)
  val isBotConfigured = cleanBotUrl.isNotBlank()
  val telegramBlue = Color(0xFF229ED9)

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = androidx.compose.foundation.BorderStroke(1.dp, telegramBlue.copy(alpha = 0.45f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("telegram_upload_bot_card")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(telegramBlue.copy(alpha = 0.2f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            tint = telegramBlue,
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Telegram Upload Bot (5s Clip + Link)",
            color = MasalaTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Send video to Bot → Auto-upload + instant 5-sec clip & VidVora watch link",
            color = MasalaTextSecondary,
            fontSize = 11.sp
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        if (!memberPasskey.isNullOrBlank()) {
          OutlinedButton(
            onClick = { onCopyLoginCommand("/login ${memberPasskey.trim()}") },
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.65f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
            modifier = Modifier
              .weight(1f)
              .testTag("copy_bot_login_cmd_button")
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text("Copy /login", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }
        }

        if (isBotConfigured) {
          Button(
            onClick = { onOpenBot(cleanBotUrl) },
            colors = ButtonDefaults.buttonColors(containerColor = telegramBlue, contentColor = Color.White),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
              .weight(1f)
              .testTag("open_creator_upload_bot_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text("Open Bot", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }
        } else if (canConfigureBot) {
          OutlinedButton(
            onClick = onConfigureBot,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, telegramBlue.copy(alpha = 0.7f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = telegramBlue),
            modifier = Modifier
              .weight(1f)
              .testTag("configure_upload_bot_button")
          ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text("Set Bot Link", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }
        }
      }
    }
  }
}

@Composable
private fun TelegramFinanceBotCard(
  botLink: String,
  memberPasskey: String?,
  isSuperAdmin: Boolean,
  canConfigureBot: Boolean,
  onCopyLoginCommand: (String) -> Unit,
  onOpenBot: (String) -> Unit,
  onConfigureBot: () -> Unit
) {
  val cleanBotUrl = FirestoreVideoRepository.normalizeTelegramUrl(botLink)
  val isBotConfigured = cleanBotUrl.isNotBlank()
  val financeAccent = MasalaSuccess

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = androidx.compose.foundation.BorderStroke(1.dp, financeAccent.copy(alpha = 0.45f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("telegram_finance_bot_card")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(financeAccent.copy(alpha = 0.18f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.AccountBalanceWallet,
            contentDescription = null,
            tint = financeAccent,
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Telegram Finance Bot (Earnings & UPI Payout)",
            color = MasalaTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = if (isSuperAdmin) {
              "Creators: /balance, /withdraw, /history • Admin: /pending, /approve"
            } else {
              "Check live ₹ balance, payout history & request ₹500+ UPI withdrawals directly on Telegram"
            },
            color = MasalaTextSecondary,
            fontSize = 11.sp
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        if (!memberPasskey.isNullOrBlank()) {
          OutlinedButton(
            onClick = { onCopyLoginCommand("/login ${memberPasskey.trim()}") },
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.65f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
            modifier = Modifier
              .weight(1f)
              .testTag("copy_finance_bot_login_cmd_button")
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text("Copy /login", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }
        }

        if (isBotConfigured) {
          Button(
            onClick = { onOpenBot(cleanBotUrl) },
            colors = ButtonDefaults.buttonColors(containerColor = financeAccent, contentColor = Color.White),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
              .weight(1f)
              .testTag("open_finance_bot_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text("Open Finance Bot", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }
        } else if (canConfigureBot) {
          OutlinedButton(
            onClick = onConfigureBot,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, financeAccent.copy(alpha = 0.7f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = financeAccent),
            modifier = Modifier
              .weight(1f)
              .testTag("configure_finance_bot_button")
          ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text("Set Finance Bot", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }
        }
      }
    }
  }
}

@Composable
private fun EditTelegramLinkDialog(
  currentLink: String,
  currentCreatorBotLink: String,
  currentFinanceBotLink: String,
  currentShareLinkBaseUrl: String,
  currentCategoryLinks: Map<String, String>,
  currentCategoryNames: Map<String, String>,
  currentDeletedCategoryKeys: List<String>,
  currentCustomCategories: List<TelegramCategoryItem>,
  onDismiss: () -> Unit,
  onSave: (
    mainLink: String,
    creatorBotLink: String,
    financeBotLink: String,
    shareLinkBaseUrl: String,
    categoryLinks: Map<String, String>,
    customCategories: List<TelegramCategoryItem>,
    categoryNames: Map<String, String>,
    deletedCategoryKeys: List<String>,
    closeAfterSave: Boolean,
    statusMessage: String?
  ) -> Unit
) {
  var linkInput by remember { mutableStateOf(currentLink) }
  var creatorBotLinkInput by remember { mutableStateOf(currentCreatorBotLink) }
  var financeBotLinkInput by remember { mutableStateOf(currentFinanceBotLink) }
  var sharePreviewBaseInput by remember { mutableStateOf(currentShareLinkBaseUrl) }
  var deletedBuiltInKeys by remember { mutableStateOf(currentDeletedCategoryKeys.toSet()) }
  val categoryInputs = remember {
    mutableStateMapOf<String, String>().apply {
      TelegramChannelCategory.entries.forEach { cat ->
        put(cat.key, currentCategoryLinks[cat.key].orEmpty())
      }
    }
  }
  val builtInNameInputs = remember {
    mutableStateMapOf<String, String>().apply {
      TelegramChannelCategory.entries.forEach { cat ->
        val customName = currentCategoryNames[cat.key]?.trim().takeIf { !it.isNullOrBlank() } ?: cat.label
        put(cat.key, customName)
      }
    }
  }
  var customCategories by remember { mutableStateOf(currentCustomCategories) }
  var showAddForm by remember { mutableStateOf(false) }
  var newCategoryName by remember { mutableStateOf("") }
  var newCategoryLink by remember { mutableStateOf("") }
  var formError by remember { mutableStateOf<String?>(null) }

  val activeBuiltInCategories = TelegramChannelCategory.entries.filter { it.key !in deletedBuiltInKeys }
  val activeTotalCount = activeBuiltInCategories.size + customCategories.size

  fun activeBuiltInNames(): List<String> = activeBuiltInCategories.map { cat ->
    builtInNameInputs[cat.key]?.trim().takeIf { !it.isNullOrBlank() } ?: cat.label
  }

  fun validateAndDispatchSave(
    closeAfterSave: Boolean,
    statusMessage: String? = null,
    overrideDeletedKeys: Set<String> = deletedBuiltInKeys,
    overrideCustomCategories: List<TelegramCategoryItem> = customCategories
  ): Boolean {
    val activeBuiltIn = TelegramChannelCategory.entries.filter { it.key !in overrideDeletedKeys }
    val seen = mutableSetOf<String>()
    for (cat in activeBuiltIn) {
      val n = builtInNameInputs[cat.key]?.trim().orEmpty()
      if (n.isBlank()) {
        formError = "Category name cannot be empty."
        return false
      }
      if (!seen.add(n.lowercase())) {
        formError = "Duplicate category name '$n'."
        return false
      }
    }
    for (item in overrideCustomCategories) {
      val n = item.name.trim()
      if (n.isBlank()) {
        formError = "Category name cannot be empty."
        return false
      }
      if (!seen.add(n.lowercase())) {
        formError = "Duplicate category name '$n'."
        return false
      }
    }
    val mainValue = linkInput.trim().ifBlank { FirestoreVideoRepository.DEFAULT_TELEGRAM_LINK }
    val cleanNamesMap = buildMap {
      activeBuiltIn.forEach { cat ->
        val n = builtInNameInputs[cat.key]?.trim().orEmpty()
        if (n.isNotBlank() && n != cat.label) {
          put(cat.key, n)
        }
      }
    }
    val cleanBotValue = FirestoreVideoRepository.normalizeTelegramUrl(creatorBotLinkInput)
    val cleanFinanceBotValue = FirestoreVideoRepository.normalizeTelegramUrl(financeBotLinkInput)
    val cleanSharePreviewBase = FirestoreVideoRepository.normalizeShareLinkBaseUrl(sharePreviewBaseInput)
    formError = null
    onSave(
      mainValue,
      cleanBotValue,
      cleanFinanceBotValue,
      cleanSharePreviewBase,
      categoryInputs.toMap(),
      overrideCustomCategories,
      cleanNamesMap,
      overrideDeletedKeys.toList(),
      closeAfterSave,
      statusMessage
    )
    return true
  }

  fun addPendingCategoryIfValid(): Boolean {
    val validation = FirestoreVideoRepository.validateCustomCategoryName(
      name = newCategoryName,
      existingCategoryNames = customCategories.map { it.name },
      activeBuiltInNames = activeBuiltInNames()
    )
    if (validation != null) {
      formError = validation
      return false
    }
    if (customCategories.size >= FirestoreVideoRepository.MAX_CUSTOM_TELEGRAM_CATEGORIES) {
      formError = "Maximum ${FirestoreVideoRepository.MAX_CUSTOM_TELEGRAM_CATEGORIES} custom categories allowed."
      return false
    }
    val cleanName = newCategoryName.trim()
    val cleanLink = newCategoryLink.trim()
    val newItem = TelegramCategoryItem(
      id = FirestoreVideoRepository.buildCustomCategoryId(cleanName),
      name = cleanName,
      link = cleanLink,
      isBuiltIn = false
    )
    val updatedList = customCategories + newItem
    customCategories = updatedList
    newCategoryName = ""
    newCategoryLink = ""
    formError = null
    showAddForm = false
    validateAndDispatchSave(
      closeAfterSave = false,
      statusMessage = "Added '$cleanName' & synced to users!",
      overrideCustomCategories = updatedList
    )
    return true
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
  ) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MasalaSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 20.dp)
        .testTag("edit_telegram_link_dialog")
    ) {
      Column(
        modifier = Modifier.padding(20.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
              modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFF229ED9).copy(alpha = 0.2f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = Color(0xFF229ED9),
                modifier = Modifier.size(20.dp)
              )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text(
                text = "Telegram Channel Links",
                color = MasalaTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "Add, edit name/link, or delete categories",
                color = MasalaGold,
                fontSize = 11.sp
              )
            }
          }

          OutlinedButton(
            onClick = {
              showAddForm = !showAddForm
              formError = null
            },
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.7f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.testTag("add_telegram_category_button")
          ) {
            Icon(
              imageVector = if (showAddForm) Icons.Default.Close else Icons.Default.Add,
              contentDescription = null,
              modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = if (showAddForm) "Close" else "Add Category",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 430.dp)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          if (showAddForm) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MasalaSurfaceVariant)
                .border(1.dp, MasalaGold.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .padding(12.dp)
                .testTag("add_category_inline_card"),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Text(
                text = "NEW CATEGORY (${activeTotalCount + 1})",
                color = MasalaGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp
              )

              OutlinedTextField(
                value = newCategoryName,
                onValueChange = {
                  newCategoryName = it.take(FirestoreVideoRepository.MAX_CATEGORY_NAME_LENGTH)
                  if (formError != null) formError = null
                },
                label = { Text("Category Name (e.g. Bollywood Movie)") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = MasalaGold,
                  unfocusedBorderColor = MasalaCardBorder,
                  focusedTextColor = MasalaTextPrimary,
                  unfocusedTextColor = MasalaTextPrimary,
                  focusedContainerColor = MasalaSurface,
                  unfocusedContainerColor = MasalaSurface,
                  focusedLabelColor = MasalaGold,
                  unfocusedLabelColor = MasalaTextMuted
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("new_category_name_input")
              )

              OutlinedTextField(
                value = newCategoryLink,
                onValueChange = { newCategoryLink = it },
                label = { Text("Telegram Channel Link (https://t.me/...)") },
                placeholder = { Text("https://t.me/...", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = Color(0xFF229ED9),
                  unfocusedBorderColor = MasalaCardBorder,
                  focusedTextColor = MasalaTextPrimary,
                  unfocusedTextColor = MasalaTextPrimary,
                  focusedContainerColor = MasalaSurface,
                  unfocusedContainerColor = MasalaSurface,
                  focusedLabelColor = Color(0xFF229ED9),
                  unfocusedLabelColor = MasalaTextMuted
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("new_category_link_input")
              )

              if (formError != null) {
                Text(
                  text = formError!!,
                  color = MasalaError,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Medium
                )
              }

              Button(
                onClick = { addPendingCategoryIfValid() },
                colors = ButtonDefaults.buttonColors(
                  containerColor = MasalaGold,
                  contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("confirm_add_category_button")
              ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Category to List", fontWeight = FontWeight.Bold, fontSize = 12.sp)
              }
            }
          }

          OutlinedTextField(
            value = linkInput,
            onValueChange = { linkInput = it },
            label = { Text("Main Official Channel (https://t.me/...)") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = Color(0xFF229ED9),
              unfocusedBorderColor = MasalaCardBorder,
              focusedTextColor = MasalaTextPrimary,
              unfocusedTextColor = MasalaTextPrimary,
              focusedContainerColor = MasalaSurfaceVariant,
              unfocusedContainerColor = MasalaSurfaceVariant,
              focusedLabelColor = Color(0xFF229ED9),
              unfocusedLabelColor = MasalaTextMuted
            ),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("telegram_link_input")
          )

          OutlinedTextField(
            value = creatorBotLinkInput,
            onValueChange = { creatorBotLinkInput = it },
            label = { Text("Creator Upload Bot Link (Optional)") },
            placeholder = { Text("https://t.me/YourVidVoraBot", fontSize = 12.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = Color(0xFF229ED9),
              unfocusedBorderColor = MasalaCardBorder,
              focusedTextColor = MasalaTextPrimary,
              unfocusedTextColor = MasalaTextPrimary,
              focusedContainerColor = MasalaSurfaceVariant,
              unfocusedContainerColor = MasalaSurfaceVariant,
              focusedLabelColor = Color(0xFF229ED9),
              unfocusedLabelColor = MasalaTextMuted
            ),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("telegram_creator_bot_input")
          )

          OutlinedTextField(
            value = financeBotLinkInput,
            onValueChange = { financeBotLinkInput = it },
            label = { Text("Creator Finance Bot Link (Earnings & UPI)") },
            placeholder = { Text("https://t.me/YourVidVoraFinanceBot", fontSize = 12.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MasalaSuccess,
              unfocusedBorderColor = MasalaCardBorder,
              focusedTextColor = MasalaTextPrimary,
              unfocusedTextColor = MasalaTextPrimary,
              focusedContainerColor = MasalaSurfaceVariant,
              unfocusedContainerColor = MasalaSurfaceVariant,
              focusedLabelColor = MasalaSuccess,
              unfocusedLabelColor = MasalaTextMuted
            ),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("telegram_finance_bot_input")
          )

          OutlinedTextField(
            value = sharePreviewBaseInput,
            onValueChange = { sharePreviewBaseInput = it },
            label = { Text("Share Preview Server URL (Thumbnail Cards)") },
            placeholder = { Text("https://your-bot.onrender.com/share?link=", fontSize = 12.sp) },
            supportingText = {
              Text(
                text = "Render/Firebase preview server URL dalne par WhatsApp, Telegram, FB, X sab jagah video thumbnail show hoga.",
                color = MasalaTextMuted,
                fontSize = 10.sp
              )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MasalaGold,
              unfocusedBorderColor = MasalaCardBorder,
              focusedTextColor = MasalaTextPrimary,
              unfocusedTextColor = MasalaTextPrimary,
              focusedContainerColor = MasalaSurfaceVariant,
              unfocusedContainerColor = MasalaSurfaceVariant,
              focusedLabelColor = MasalaGold,
              unfocusedLabelColor = MasalaTextMuted
            ),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("share_preview_base_url_input")
          )

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "CATEGORY CHANNELS ($activeTotalCount TOTAL)",
              color = MasalaTextMuted,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 0.8.sp
            )
            if (deletedBuiltInKeys.isNotEmpty()) {
              Text(
                text = "Restore Defaults",
                color = MasalaGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                  .clip(RoundedCornerShape(6.dp))
                  .background(MasalaGold.copy(alpha = 0.14f))
                  .clickable {
                    deletedBuiltInKeys = emptySet()
                    formError = null
                    validateAndDispatchSave(
                      closeAfterSave = false,
                      statusMessage = "Default categories restored & synced!",
                      overrideDeletedKeys = emptySet()
                    )
                  }
                  .padding(horizontal = 8.dp, vertical = 4.dp)
                  .testTag("restore_default_categories_button")
              )
            }
          }

          if (activeTotalCount == 0) {
            Text(
              text = "All categories removed. Tap '+ Add Category' above to add one.",
              color = MasalaTextMuted,
              fontSize = 12.sp,
              modifier = Modifier.padding(vertical = 8.dp)
            )
          }

          activeBuiltInCategories.forEachIndexed { idx, category ->
            val number = idx + 1
            val currentName = builtInNameInputs[category.key].orEmpty()
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MasalaSurfaceVariant)
                .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
                .padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "Category #$number",
                  color = MasalaGold,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold
                )
                IconButton(
                  onClick = {
                    val updatedDeleted = deletedBuiltInKeys + category.key
                    deletedBuiltInKeys = updatedDeleted
                    categoryInputs[category.key] = ""
                    formError = null
                    validateAndDispatchSave(
                      closeAfterSave = false,
                      statusMessage = "Category removed & synced to users!",
                      overrideDeletedKeys = updatedDeleted
                    )
                  },
                  modifier = Modifier
                    .size(48.dp)
                    .testTag("delete_category_${category.key}")
                ) {
                  Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete $currentName",
                    tint = MasalaError,
                    modifier = Modifier.size(18.dp)
                  )
                }
              }

              OutlinedTextField(
                value = currentName,
                onValueChange = { updatedName ->
                  builtInNameInputs[category.key] = updatedName.take(FirestoreVideoRepository.MAX_CATEGORY_NAME_LENGTH)
                  if (formError != null) formError = null
                },
                label = { Text("$number. Category Name") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = MasalaGold,
                  unfocusedBorderColor = MasalaCardBorder,
                  focusedTextColor = MasalaTextPrimary,
                  unfocusedTextColor = MasalaTextPrimary,
                  focusedContainerColor = MasalaSurface,
                  unfocusedContainerColor = MasalaSurface,
                  focusedLabelColor = MasalaGold,
                  unfocusedLabelColor = MasalaTextMuted
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("telegram_cat_name_${category.key}")
              )

              OutlinedTextField(
                value = categoryInputs[category.key].orEmpty(),
                onValueChange = { categoryInputs[category.key] = it },
                label = { Text("$number. ${currentName.ifBlank { category.label }} Link") },
                placeholder = { Text("https://t.me/...", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = Color(0xFF229ED9),
                  unfocusedBorderColor = MasalaCardBorder,
                  focusedTextColor = MasalaTextPrimary,
                  unfocusedTextColor = MasalaTextPrimary,
                  focusedContainerColor = MasalaSurface,
                  unfocusedContainerColor = MasalaSurface,
                  focusedLabelColor = Color(0xFF229ED9),
                  unfocusedLabelColor = MasalaTextMuted
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("telegram_cat_input_${category.key}")
              )
            }
          }

          customCategories.forEachIndexed { idx, item ->
            val number = activeBuiltInCategories.size + idx + 1
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MasalaSurfaceVariant)
                .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
                .padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "Category #$number",
                  color = MasalaGold,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold
                )
                IconButton(
                  onClick = {
                    val updatedCustom = customCategories.filterNot { it.id == item.id }
                    customCategories = updatedCustom
                    formError = null
                    validateAndDispatchSave(
                      closeAfterSave = false,
                      statusMessage = "Category '${item.name}' removed & synced to users!",
                      overrideCustomCategories = updatedCustom
                    )
                  },
                  modifier = Modifier
                    .size(48.dp)
                    .testTag("delete_custom_category_${item.id}")
                ) {
                  Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove ${item.name}",
                    tint = MasalaError,
                    modifier = Modifier.size(18.dp)
                  )
                }
              }

              OutlinedTextField(
                value = item.name,
                onValueChange = { updatedName ->
                  val trimmed = updatedName.take(FirestoreVideoRepository.MAX_CATEGORY_NAME_LENGTH)
                  customCategories = customCategories.map {
                    if (it.id == item.id) it.copy(name = trimmed) else it
                  }
                  if (formError != null) formError = null
                },
                label = { Text("$number. Category Name") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = MasalaGold,
                  unfocusedBorderColor = MasalaCardBorder,
                  focusedTextColor = MasalaTextPrimary,
                  unfocusedTextColor = MasalaTextPrimary,
                  focusedContainerColor = MasalaSurface,
                  unfocusedContainerColor = MasalaSurface,
                  focusedLabelColor = MasalaGold,
                  unfocusedLabelColor = MasalaTextMuted
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("telegram_custom_cat_name_${item.id}")
              )

              OutlinedTextField(
                value = item.link,
                onValueChange = { updatedLink ->
                  customCategories = customCategories.map {
                    if (it.id == item.id) it.copy(link = updatedLink) else it
                  }
                },
                label = { Text("$number. ${item.name.ifBlank { "Category" }} Link") },
                placeholder = { Text("https://t.me/...", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = Color(0xFF229ED9),
                  unfocusedBorderColor = MasalaCardBorder,
                  focusedTextColor = MasalaTextPrimary,
                  unfocusedTextColor = MasalaTextPrimary,
                  focusedContainerColor = MasalaSurface,
                  unfocusedContainerColor = MasalaSurface,
                  focusedLabelColor = Color(0xFF229ED9),
                  unfocusedLabelColor = MasalaTextMuted
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("telegram_cat_input_${item.id}")
              )
            }
          }
        }

        if (!showAddForm && formError != null) {
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = formError!!,
            color = MasalaError,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextSecondary),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
          ) {
            Text("Cancel")
          }

          Button(
            onClick = {
              if (showAddForm && (newCategoryName.isNotBlank() || newCategoryLink.isNotBlank())) {
                if (!addPendingCategoryIfValid()) return@Button
              }
              validateAndDispatchSave(closeAfterSave = true)
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFF229ED9),
              contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .weight(1f)
              .testTag("save_telegram_link_button")
          ) {
            Text("Save & Sync Users", fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

@Composable
private fun AdminBroadcastComposerCard(
  availableVideos: List<FirestoreVideo> = emptyList(),
  accessLinks: List<FirestoreAccessLink> = emptyList(),
  showVideoViewCounts: Boolean = true,
  shareLinkBaseUrl: String = "",
  onSend: (
    title: String,
    message: String,
    imageUri: Uri?,
    imageUrl: String,
    actionUrl: String,
    onDone: (Boolean, String?) -> Unit
  ) -> Unit
) {
  var title by remember { mutableStateOf("") }
  var message by remember { mutableStateOf("") }
  var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
  var imageUrl by remember { mutableStateOf("") }
  var actionUrl by remember { mutableStateOf("") }
  var isSending by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var showVideoPickerDialog by remember { mutableStateOf(false) }

  val imagePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
  ) { uri: Uri? ->
    if (uri != null) {
      selectedImageUri = uri
      imageUrl = ""
      errorMessage = null
    }
  }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.45f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("admin_broadcast_composer_card")
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MasalaGold.copy(alpha = 0.2f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.NotificationsActive,
            contentDescription = null,
            tint = MasalaGold,
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
          Text(
            text = "Send App Notification",
            color = MasalaTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Text, image & link delivered to all VidVora users",
            color = MasalaTextSecondary,
            fontSize = 11.sp
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      OutlinedTextField(
        value = title,
        onValueChange = {
          if (it.length <= 120) {
            title = it
            errorMessage = null
          }
        },
        label = { Text("Notification Title *") },
        placeholder = { Text("e.g., New Exclusive Video Live!") },
        singleLine = true,
        enabled = !isSending,
        colors = defaultTextFieldColors(),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("broadcast_title_input")
      )

      Spacer(modifier = Modifier.height(10.dp))

      OutlinedTextField(
        value = message,
        onValueChange = {
          if (it.length <= 500) {
            message = it
            errorMessage = null
          }
        },
        label = { Text("Message for Downloaders *") },
        placeholder = { Text("Write the announcement message that all app users will see...") },
        minLines = 3,
        maxLines = 5,
        enabled = !isSending,
        colors = defaultTextFieldColors(),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("broadcast_message_input")
      )

      Text(
        text = "${message.length}/500 characters",
        color = MasalaTextMuted,
        fontSize = 10.sp,
        modifier = Modifier
          .align(Alignment.End)
          .padding(top = 4.dp)
      )

      Spacer(modifier = Modifier.height(10.dp))

      // Optional Image Attachment (Photo Picker or Image URL)
      Text(
        text = "Notification Image (Optional)",
        color = MasalaTextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        OutlinedButton(
          onClick = {
            imagePickerLauncher.launch(
              PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
          },
          enabled = !isSending,
          colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (selectedImageUri != null) MasalaSuccess else MasalaGold
          ),
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selectedImageUri != null) MasalaSuccess else MasalaGold.copy(alpha = 0.5f)
          ),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .testTag("broadcast_pick_image_button")
        ) {
          Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(17.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = if (selectedImageUri != null) "Change Image" else "Pick Image from Gallery",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        if (selectedImageUri != null || imageUrl.isNotBlank()) {
          OutlinedButton(
            onClick = {
              selectedImageUri = null
              imageUrl = ""
              errorMessage = null
            },
            enabled = !isSending,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaError),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaError.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .height(44.dp)
              .testTag("broadcast_clear_image_button")
          ) {
            Icon(Icons.Default.Close, contentDescription = "Remove image", modifier = Modifier.size(16.dp))
          }
        }
      }

      if (selectedImageUri != null) {
        Spacer(modifier = Modifier.height(8.dp))
        AsyncImage(
          model = selectedImageUri,
          contentDescription = "Selected notification image preview",
          contentScale = ContentScale.Crop,
          modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MasalaCardBorder, RoundedCornerShape(10.dp))
            .testTag("broadcast_image_preview")
        )
      } else {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = imageUrl,
          onValueChange = {
            imageUrl = it
            errorMessage = null
          },
          label = { Text("Or Image URL (Optional)") },
          placeholder = { Text("https://example.com/banner.jpg") },
          leadingIcon = {
            Icon(Icons.Default.Image, contentDescription = null, tint = MasalaTextMuted, modifier = Modifier.size(18.dp))
          },
          singleLine = true,
          enabled = !isSending,
          colors = defaultTextFieldColors(),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("broadcast_image_url_input")
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Optional Clickable URL
      OutlinedTextField(
        value = actionUrl,
        onValueChange = {
          actionUrl = it
          errorMessage = null
        },
        label = { Text("Action Link / URL (Optional)") },
        placeholder = { Text("https://... or video link / token") },
        leadingIcon = {
          Icon(Icons.Default.Link, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(18.dp))
        },
        trailingIcon = if (actionUrl.isNotBlank()) {
          {
            IconButton(
              onClick = {
                actionUrl = ""
                errorMessage = null
              }
            ) {
              Icon(Icons.Default.Close, contentDescription = "Clear URL", tint = MasalaTextMuted, modifier = Modifier.size(16.dp))
            }
          }
        } else null,
        singleLine = true,
        enabled = !isSending,
        colors = defaultTextFieldColors(),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("broadcast_action_url_input")
      )

      if (availableVideos.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedButton(
          onClick = { showVideoPickerDialog = true },
          enabled = !isSending,
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
          border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.45f)),
          shape = RoundedCornerShape(10.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("broadcast_pick_video_button")
        ) {
          Icon(Icons.Default.VideoFile, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Attach My Video Link (${availableVideos.size})",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
      }

      if (errorMessage != null) {
        Text(
          text = errorMessage ?: "",
          color = MasalaError,
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 6.dp)
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      Button(
        onClick = {
          val err = FirestoreVideoRepository.validateNotificationInput(
            title = title,
            message = message,
            imageUrl = if (selectedImageUri != null) "" else imageUrl,
            actionUrl = actionUrl
          )
          if (err != null) {
            errorMessage = err
            return@Button
          }
          isSending = true
          onSend(
            title.trim(),
            message.trim(),
            selectedImageUri,
            imageUrl.trim(),
            actionUrl.trim()
          ) { success, serverErr ->
            isSending = false
            if (success) {
              title = ""
              message = ""
              selectedImageUri = null
              imageUrl = ""
              actionUrl = ""
              errorMessage = null
            } else {
              errorMessage = serverErr ?: "Failed to send notification"
            }
          }
        },
        enabled = !isSending,
        colors = ButtonDefaults.buttonColors(
          containerColor = MasalaRedPrimary,
          contentColor = Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .testTag("send_broadcast_notification_button")
      ) {
        if (isSending) {
          CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Sending...", fontWeight = FontWeight.Bold)
        } else {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Send Notification to All Users", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
      }
    }
  }

  if (showVideoPickerDialog) {
    AlertDialog(
      onDismissRequest = { showVideoPickerDialog = false },
      containerColor = MasalaSurface,
      titleContentColor = MasalaTextPrimary,
      title = {
        Text(
          text = "Select Video Link",
          fontSize = 17.sp,
          fontWeight = FontWeight.Bold
        )
      },
      text = {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(availableVideos, key = { it.videoId }) { video ->
            val activeLink = accessLinks.firstOrNull { it.videoId == video.videoId && it.isActive }
              ?: accessLinks.firstOrNull { it.videoId == video.videoId }
            val resolvedLink = FirestoreVideoRepository.buildShareableVideoUrl(
              activeLink?.linkId ?: video.videoId,
              shareLinkBaseUrl
            )
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MasalaSurfaceVariant)
                .border(1.dp, MasalaCardBorder, RoundedCornerShape(10.dp))
                .clickable {
                  actionUrl = resolvedLink
                  if (title.isBlank()) {
                    title = video.title.take(120)
                  }
                  errorMessage = null
                  showVideoPickerDialog = false
                }
                .padding(12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              VideoThumbnailBox(
                thumbnailPath = video.thumbnailPath,
                storagePath = video.storagePath,
                videoKey = "picker_${video.videoId}",
                contentDescription = video.title,
                cornerRadius = 6.dp,
                showPlayBadge = true,
                playBadgeSize = 20.dp,
                modifier = Modifier
                  .width(56.dp)
                  .height(38.dp)
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = video.title.ifBlank { "Untitled Video" },
                  color = MasalaTextPrimary,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = if (showVideoViewCounts) "${video.totalViews} views • Tap to attach" else "Tap to attach share link",
                  color = MasalaTextMuted,
                  fontSize = 11.sp
                )
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = { showVideoPickerDialog = false },
          colors = ButtonDefaults.buttonColors(containerColor = MasalaSurfaceVariant, contentColor = MasalaTextPrimary)
        ) {
          Text("Cancel")
        }
      }
    )
  }
}

@Composable
private fun AdminSentNotificationCard(
  notification: FirestoreNotification,
  onOpenLink: (String) -> Unit,
  onDelete: () -> Unit
) {
  val context = LocalContext.current
  val imageModel = remember(notification.imageUrl) {
    com.example.notifications.AppNotificationHelper.decodeImageModel(notification.imageUrl)
  }

  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("sent_notification_card_${notification.notificationId}")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.Top
    ) {
      Box(
        modifier = Modifier
          .size(34.dp)
          .clip(CircleShape)
          .background(MasalaGold.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(Icons.Default.Notifications, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(18.dp))
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = notification.title,
          color = MasalaTextPrimary,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = notification.message,
          color = MasalaTextSecondary,
          fontSize = 12.sp,
          lineHeight = 17.sp
        )
        if (imageModel != null) {
          Spacer(modifier = Modifier.height(8.dp))
          AsyncImage(
            model = imageModel,
            contentDescription = "Notification Image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .fillMaxWidth()
              .height(110.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(MasalaSurfaceVariant)
          )
        }
        if (notification.actionUrl.isNotBlank()) {
          Spacer(modifier = Modifier.height(8.dp))
          Row(
            modifier = Modifier
              .clip(RoundedCornerShape(8.dp))
              .background(MasalaGold.copy(alpha = 0.14f))
              .clickable { onOpenLink(notification.actionUrl) }
              .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.OpenInNew,
              contentDescription = null,
              tint = MasalaGold,
              modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
              text = notification.actionUrl,
              color = MasalaGold,
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
        Spacer(modifier = Modifier.height(6.dp))
        val formattedTime = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
          .format(notification.createdAt.toDate())
        Text(
          text = "Sent $formattedTime • Visible to all app users",
          color = MasalaGold,
          fontSize = 10.sp
        )
      }

      IconButton(
        onClick = onDelete,
        modifier = Modifier
          .size(32.dp)
          .testTag("delete_notification_${notification.notificationId}")
      ) {
        Icon(
          imageVector = Icons.Default.Delete,
          contentDescription = "Delete Notification",
          tint = MasalaError.copy(alpha = 0.85f),
          modifier = Modifier.size(18.dp)
        )
      }
    }
  }
}

@Composable
private fun EditMemberDialog(
  member: FirestoreMember,
  onDismiss: () -> Unit,
  onSave: (
    name: String,
    emailOrPhone: String,
    passkey: String,
    isActive: Boolean,
    role: String,
    allowedOptions: List<String>,
    onDone: (Boolean, String?) -> Unit
  ) -> Unit
) {
  var name by remember(member.memberId) { mutableStateOf(member.name) }
  var emailOrPhone by remember(member.memberId) { mutableStateOf(member.emailOrPhone) }
  var passkey by remember(member.memberId) { mutableStateOf(member.passkey) }
  var isActive by remember(member.memberId) { mutableStateOf(member.isActive) }
  var selectedRole by remember(member.memberId) { mutableStateOf(MemberRole.normalize(member.role)) }
  var selectedOptions by remember(member.memberId) { mutableStateOf(member.allowedOptions.toSet()) }
  var isSaving by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MasalaSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.5f)),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("edit_member_dialog")
    ) {
      Column(
        modifier = Modifier
          .padding(18.dp)
          .verticalScroll(rememberScrollState())
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(MasalaGold.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(20.dp))
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text("Edit Member Details", color = MasalaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Update role, allowed options, credentials & status", color = MasalaTextMuted, fontSize = 11.sp)
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
          value = name,
          onValueChange = { name = it; errorMessage = null },
          label = { Text("Member Name *") },
          singleLine = true,
          enabled = !isSaving,
          colors = defaultTextFieldColors(),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("edit_member_name_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = emailOrPhone,
          onValueChange = { emailOrPhone = it; errorMessage = null },
          label = { Text("Email Address *") },
          singleLine = true,
          enabled = !isSaving,
          colors = defaultTextFieldColors(),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("edit_member_email_input")
        )

        if (!emailOrPhone.trim().equals(member.emailOrPhone.trim(), ignoreCase = true)) {
          Text(
            text = "Email change karne par sabhi purane videos, views, ₹ earnings & payout history new email par automatic dikhne lagenge.",
            color = MasalaGold,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            modifier = Modifier
              .padding(top = 4.dp)
              .testTag("edit_member_email_note")
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = passkey,
          onValueChange = { passkey = it; errorMessage = null },
          label = { Text("Password *") },
          singleLine = true,
          enabled = !isSaving,
          colors = defaultTextFieldColors(),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("edit_member_passkey_input")
        )

        Spacer(modifier = Modifier.height(12.dp))

        MemberRoleAndOptionsSelector(
          selectedRole = selectedRole,
          selectedOptions = selectedOptions,
          enabled = !isSaving,
          onSelectRole = { newRole ->
            if (newRole != selectedRole) {
              selectedRole = newRole
              selectedOptions = MemberOption.defaultKeysForRole(newRole).toSet()
            }
            errorMessage = null
          },
          onToggleOption = { key ->
            selectedOptions = if (key in selectedOptions) selectedOptions - key else selectedOptions + key
            errorMessage = null
          },
          onApplyRoleDefaults = {
            selectedOptions = MemberOption.defaultKeysForRole(selectedRole).toSet()
            errorMessage = null
          },
          onSelectAllOptions = {
            selectedOptions = MemberOption.allKeys().toSet()
            errorMessage = null
          }
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MasalaSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text("Account Active", color = MasalaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
              text = if (isActive) "Member can sign in & use assigned options" else "Login temporarily paused",
              color = MasalaTextMuted,
              fontSize = 10.sp
            )
          }
          Switch(
            checked = isActive,
            onCheckedChange = { if (!isSaving) isActive = it },
            colors = SwitchDefaults.colors(
              checkedThumbColor = Color.White,
              checkedTrackColor = MasalaSuccess
            )
          )
        }

        if (errorMessage != null) {
          Text(
            text = errorMessage ?: "",
            color = MasalaError,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            enabled = !isSaving,
            modifier = Modifier.weight(1f)
          ) {
            Text("Cancel")
          }
          Button(
            onClick = {
              val validationErr = FirestoreVideoRepository.validateMemberInput(name, emailOrPhone, passkey)
              if (validationErr != null) {
                errorMessage = validationErr
                return@Button
              }
              if (selectedOptions.isEmpty()) {
                errorMessage = "Please select at least 1 option for this user"
                return@Button
              }
              isSaving = true
              onSave(
                name.trim(),
                emailOrPhone.trim(),
                passkey.trim(),
                isActive,
                selectedRole,
                MemberOption.sanitizeKeys(selectedOptions)
              ) { success, err ->
                isSaving = false
                if (!success) {
                  errorMessage = err ?: "Failed to save member details"
                }
              }
            },
            enabled = !isSaving,
            colors = ButtonDefaults.buttonColors(containerColor = MasalaGold, contentColor = Color.Black),
            modifier = Modifier
              .weight(1f)
              .testTag("save_member_details_button")
          ) {
            Text(if (isSaving) "Saving..." else "Save Changes", fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

@Composable
private fun ChangeMasterKeyDialog(
  isSuperAdminMode: Boolean = false,
  onDismiss: () -> Unit,
  onSubmit: (
    currentKey: String,
    newKey: String,
    confirmKey: String,
    onDone: (Boolean, String?) -> Unit
  ) -> Unit
) {
  var currentKey by remember { mutableStateOf("") }
  var newKey by remember { mutableStateOf("") }
  var confirmKey by remember { mutableStateOf("") }
  var showKeys by remember { mutableStateOf(false) }
  var isSubmitting by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val visualTransform = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()

  Dialog(onDismissRequest = { if (!isSubmitting) onDismiss() }) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MasalaSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.5f)),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("change_master_key_dialog")
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MasalaGold.copy(alpha = 0.2f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.Key, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = if (isSuperAdminMode) "Change Admin Password" else "Change Password",
              color = MasalaTextPrimary,
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold
            )
          }

          IconButton(onClick = { showKeys = !showKeys }) {
            Icon(
              imageVector = if (showKeys) Icons.Default.VisibilityOff else Icons.Default.Visibility,
              contentDescription = if (showKeys) "Hide passwords" else "Show passwords",
              tint = MasalaTextSecondary
            )
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = if (isSuperAdminMode) {
            "Enter your current Admin Password and set a new password (min 4 chars) for ${FirebaseAuthAdminRepository.ADMIN_EMAIL}. Syncs across Admin Portal & Telegram Bot."
          } else {
            "Enter your current Password and set a new Password (minimum 4 characters). Use the new password next time you sign in."
          },
          color = MasalaTextSecondary,
          fontSize = 12.sp,
          lineHeight = 16.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
          value = currentKey,
          onValueChange = { currentKey = it; errorMessage = null },
          label = { Text("Current Password *") },
          singleLine = true,
          enabled = !isSubmitting,
          visualTransformation = visualTransform,
          colors = defaultTextFieldColors(),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("creator_current_key_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = newKey,
          onValueChange = { newKey = it; errorMessage = null },
          label = { Text("New Password *") },
          singleLine = true,
          enabled = !isSubmitting,
          visualTransformation = visualTransform,
          colors = defaultTextFieldColors(),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("creator_new_key_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
          value = confirmKey,
          onValueChange = { confirmKey = it; errorMessage = null },
          label = { Text("Confirm New Password *") },
          singleLine = true,
          enabled = !isSubmitting,
          visualTransformation = visualTransform,
          colors = defaultTextFieldColors(),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("creator_confirm_key_input")
        )

        if (errorMessage != null) {
          Text(
            text = errorMessage ?: "",
            color = MasalaError,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            enabled = !isSubmitting,
            modifier = Modifier.weight(1f)
          ) {
            Text("Cancel")
          }
          Button(
            onClick = {
              if (currentKey.trim().isBlank()) {
                errorMessage = "Please enter your current Password."
                return@Button
              }
              if (newKey.trim().length < FirestoreVideoRepository.MIN_PASSKEY_LENGTH) {
                errorMessage = "New Password must be at least ${FirestoreVideoRepository.MIN_PASSKEY_LENGTH} characters."
                return@Button
              }
              if (newKey.trim() == currentKey.trim()) {
                errorMessage = "New Password must be different from current Password."
                return@Button
              }
              if (newKey.trim() != confirmKey.trim()) {
                errorMessage = "New Password and Confirm Password do not match."
                return@Button
              }
              isSubmitting = true
              onSubmit(currentKey.trim(), newKey.trim(), confirmKey.trim()) { success, err ->
                isSubmitting = false
                if (!success) {
                  errorMessage = err ?: "Failed to change Password"
                }
              }
            },
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(containerColor = MasalaGold, contentColor = Color.Black),
            modifier = Modifier
              .weight(1f)
              .testTag("creator_save_key_button")
          ) {
            Text(if (isSubmitting) "Updating..." else "Update Password", fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}
