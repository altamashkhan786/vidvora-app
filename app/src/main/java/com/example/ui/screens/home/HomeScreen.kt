package com.example.ui.screens.home

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import com.example.util.VideoShareHelper
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Whatshot
import com.example.data.repository.FirestoreVideoRepository
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import com.example.ads.AdManager
import com.example.data.model.FirestoreNotification
import com.example.data.model.VideoToken
import com.example.notifications.AppNotificationHelper
import com.example.ui.components.AdaptiveBannerAd
import com.example.ui.components.VidVoraHeader
import com.example.ui.components.NativeAdCard
import com.example.ui.screens.admin.AdminLoginDialog
import com.example.ui.theme.MasalaBlack
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaError
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaRedAccent
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaRedVariant
import com.example.ui.theme.MasalaSuccess
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaSurfaceVariant
import com.example.ui.theme.MasalaTextMuted
import com.example.ui.theme.MasalaTextPrimary
import com.example.ui.theme.MasalaTextSecondary
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun HomeScreen(
  viewModel: HomeViewModel,
  adManager: AdManager? = null,
  onNavigateToPlayer: (VideoToken) -> Unit,
  onNavigateToAdmin: () -> Unit
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val activity = context as? Activity
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  // Ensure any active ActionMode or keyboard is cleanly dismissed when leaving HomeScreen
  DisposableEffect(Unit) {
    onDispose {
      focusManager.clearFocus()
      keyboardController?.hide()
    }
  }

  val inputUrl by viewModel.inputUrl.collectAsState()
  val isLoading by viewModel.isLoading.collectAsState()
  val errorMessage by viewModel.errorMessage.collectAsState()
  val isAdminAuthenticated by viewModel.isAdminAuthenticated.collectAsState()
  val watchHistory by viewModel.watchHistory.collectAsState()
  val telegramConfig by viewModel.telegramConfig.collectAsState()
  val telegramLink by viewModel.telegramLink.collectAsState()
  val telegramCategories by viewModel.telegramCategories.collectAsState()
  val supportChat by viewModel.supportChat.collectAsState()
  val supportMessages by viewModel.supportMessages.collectAsState()
  val supportUserName by viewModel.supportUserName.collectAsState()
  val notifications by viewModel.notifications.collectAsState()
  val isAdFreeSession by (adManager?.isAdFreeSessionActive ?: remember { MutableStateFlow(false) }).collectAsState()
  val isRewardedReady by (adManager?.isRewardedReadyFlow ?: remember { MutableStateFlow(false) }).collectAsState()

  var showAdminLoginDialog by remember { mutableStateOf(false) }
  var showAllNoticesDialog by remember { mutableStateOf(false) }
  var showTelegramMenuDialog by remember { mutableStateOf(false) }
  var showSupportChatDialog by remember { mutableStateOf(false) }
  var isPreparingPrePlayAd by remember { mutableStateOf(false) }
  var dismissedBannerIds by remember { mutableStateOf(AppNotificationHelper.getDismissedBannerIds(context)) }
  var notificationPermissionGranted by remember {
    mutableStateOf(AppNotificationHelper.hasNotificationPermission(context))
  }

  LaunchedEffect(Unit) {
    viewModel.initSupportChat(context)
  }

  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    notificationPermissionGranted = granted
    if (granted) {
      AppNotificationHelper.notifyUnseenBroadcasts(context, notifications)
    }
  }

  // Request POST_NOTIFICATIONS once after startup frame settles (Android 13+)
  LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionGranted) {
      kotlinx.coroutines.delay(1200L)
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  // Fire local system notifications whenever Superadmin pushes a new broadcast
  LaunchedEffect(notifications, notificationPermissionGranted) {
    if (notifications.isNotEmpty() && notificationPermissionGranted) {
      AppNotificationHelper.notifyUnseenBroadcasts(context, notifications)
    }
  }

  val unreadAdminReplies = (supportChat?.unreadByUserCount ?: 0L).toInt().coerceAtLeast(0)

  Scaffold(
    containerColor = MasalaBlack,
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = {
          focusManager.clearFocus()
          keyboardController?.hide()
          viewModel.markSupportChatRead()
          showSupportChatDialog = true
        },
        containerColor = MasalaRedPrimary,
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
          .padding(bottom = 4.dp)
          .testTag("support_chat_fab")
      ) {
        BadgedBox(
          badge = {
            if (unreadAdminReplies > 0) {
              Badge(
                containerColor = MasalaGold,
                contentColor = MasalaBlack
              ) {
                Text(
                  text = if (unreadAdminReplies > 9) "9+" else "$unreadAdminReplies",
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Black
                )
              }
            }
          }
        ) {
          Icon(
            imageVector = Icons.Default.SupportAgent,
            contentDescription = "Chat Support",
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Support",
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp
        )
      }
    },
    bottomBar = {
      // Anchored adaptive banner ad fixed cleanly above system navigation bar
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(MasalaBlack)
          .navigationBarsPadding()
          .padding(horizontal = 6.dp, vertical = 4.dp)
      ) {
        AdaptiveBannerAd(isAdFree = isAdFreeSession)
      }
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
        .pointerInput(Unit) {
          detectTapGestures(onTap = {
            focusManager.clearFocus()
            keyboardController?.hide()
          })
        },
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Brand Header with Top-Left 3-dot Telegram Menu, Centered Logo and Top-Right Admin lock
      VidVoraHeader(
        isAdmin = isAdminAuthenticated,
        hasTelegramCategoryLinks = telegramCategories.any { it.link.isNotBlank() },
        onTelegramMenuClick = {
          focusManager.clearFocus()
          keyboardController?.hide()
          if (adManager != null && activity != null) {
            adManager.showInterstitialAtTransition(activity) {
              viewModel.refreshTelegramConfigFromServer()
              showTelegramMenuDialog = true
            }
          } else {
            viewModel.refreshTelegramConfigFromServer()
            showTelegramMenuDialog = true
          }
        },
        onAdminClick = {
          focusManager.clearFocus()
          keyboardController?.hide()
          if (isAdminAuthenticated) {
            onNavigateToAdmin()
          } else {
            showAdminLoginDialog = true
          }
        }
      )

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
          .widthIn(max = 600.dp)
          .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
      ) {
        val latestActiveNotice = notifications.firstOrNull {
          it.isActive && it.notificationId !in dismissedBannerIds
        }
        if (latestActiveNotice != null) {
          item(key = "admin_notice_banner_${latestActiveNotice.notificationId}") {
            Spacer(modifier = Modifier.height(6.dp))
            AdminBroadcastBannerCard(
              notification = latestActiveNotice,
              totalCount = notifications.size,
              onOpenAll = {
                if (adManager != null && activity != null) {
                  adManager.showInterstitialAtTransition(activity) {
                    showAllNoticesDialog = true
                  }
                } else {
                  showAllNoticesDialog = true
                }
              },
              onOpenActionUrl = { actionUrl ->
                viewModel.openNotificationActionUrl(
                  context = context,
                  rawUrl = actionUrl,
                  onPlayVideo = { video ->
                    if (adManager != null && activity != null) {
                      adManager.showInterstitialAtTransition(activity) {
                        onNavigateToPlayer(video)
                      }
                    } else {
                      onNavigateToPlayer(video)
                    }
                  },
                  onError = { err ->
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                  }
                )
              },
              onDismiss = {
                AppNotificationHelper.dismissBannerId(context, latestActiveNotice.notificationId)
                dismissedBannerIds = dismissedBannerIds + latestActiveNotice.notificationId
              }
            )
          }
        } else if (notifications.isNotEmpty()) {
          item(key = "admin_notices_chip") {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.End
            ) {
              OutlinedButton(
                onClick = {
                  if (adManager != null && activity != null) {
                    adManager.showInterstitialAtTransition(activity) {
                      showAllNoticesDialog = true
                    }
                  } else {
                    showAllNoticesDialog = true
                  }
                },
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.testTag("open_notices_chip")
              ) {
                Icon(
                  imageVector = Icons.Default.Notifications,
                  contentDescription = "View Admin Notices",
                  modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "Notices (${notifications.size})",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }

        item {
          Spacer(modifier = Modifier.height(6.dp))
          // Cinematic Hero Card
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .testTag("hero_banner_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MasalaSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder)
          ) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  Brush.verticalGradient(
                    listOf(
                      MasalaRedPrimary.copy(alpha = 0.08f),
                      MasalaSurface
                    )
                  )
                )
                .padding(20.dp)
            ) {
              Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(
                    modifier = Modifier
                      .size(28.dp)
                      .clip(CircleShape)
                      .background(MasalaRedPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.Default.Whatshot,
                      contentDescription = null,
                      tint = MasalaRedAccent,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = "NO LOGIN REQUIRED",
                    color = MasalaGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                  )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                  text = "Stream Video Instantly",
                  color = MasalaTextPrimary,
                  fontSize = 22.sp,
                  fontWeight = FontWeight.Black,
                  lineHeight = 28.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                  text = "Paste your video link below. Instant high-speed streaming starts immediately without logging in.",
                  color = MasalaTextSecondary,
                  fontSize = 13.sp,
                  lineHeight = 18.sp
                )
              }
            }
          }
        }

        item {
          // Input Section
          Column(
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = "VIDEO LINK",
              color = MasalaTextMuted,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.sp,
              modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            // URL Input Field
            OutlinedTextField(
              value = inputUrl,
              onValueChange = { viewModel.onInputChange(it) },
              placeholder = {
                Text(
                  text = "Paste Your Video Link",
                  color = MasalaTextMuted,
                  fontSize = 14.sp
                )
              },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Default.Link,
                  contentDescription = null,
                  tint = MasalaGold
                )
              },
              trailingIcon = {
                if (inputUrl.isNotBlank()) {
                  IconButton(
                    onClick = { viewModel.onInputChange("") },
                    modifier = Modifier.testTag("clear_url_button")
                  ) {
                    Icon(
                      imageVector = Icons.Default.Close,
                      contentDescription = "Clear input",
                      tint = MasalaTextMuted
                    )
                  }
                } else {
                  IconButton(
                    onClick = {
                      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                      val clip = clipboard.primaryClip
                      if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).text?.toString() ?: ""
                        if (text.isNotBlank()) {
                          viewModel.pasteText(text)
                        }
                      }
                    },
                    modifier = Modifier.testTag("paste_clipboard_button")
                  ) {
                    Icon(
                      imageVector = Icons.Default.ContentPaste,
                      contentDescription = "Paste from clipboard",
                      tint = MasalaTextSecondary
                    )
                  }
                }
              },
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MasalaRedPrimary,
                unfocusedBorderColor = MasalaCardBorder,
                focusedTextColor = MasalaTextPrimary,
                unfocusedTextColor = MasalaTextPrimary,
                focusedContainerColor = MasalaSurface,
                unfocusedContainerColor = MasalaSurface,
                cursorColor = MasalaRedAccent
              ),
              shape = RoundedCornerShape(14.dp),
              singleLine = true,
              keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go
              ),
              keyboardActions = KeyboardActions(
                onGo = {
                  focusManager.clearFocus()
                  keyboardController?.hide()
                  if (!isPreparingPrePlayAd) {
                    viewModel.validateAndPlay { video ->
                      if (adManager != null && activity != null) {
                        isPreparingPrePlayAd = true
                        adManager.showPrePlayAd(activity) {
                          isPreparingPrePlayAd = false
                          onNavigateToPlayer(video)
                        }
                      } else {
                        onNavigateToPlayer(video)
                      }
                    }
                  }
                }
              ),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("video_link_input")
            )
          }
        }

        // Error Banner
        item {
          AnimatedVisibility(
            visible = errorMessage != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
          ) {
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .testTag("error_state_card"),
              shape = RoundedCornerShape(12.dp),
              colors = CardDefaults.cardColors(containerColor = MasalaError.copy(alpha = 0.12f)),
              border = androidx.compose.foundation.BorderStroke(1.dp, MasalaError.copy(alpha = 0.4f))
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.ErrorOutline,
                  contentDescription = "Error",
                  tint = MasalaError,
                  modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text = errorMessage ?: "",
                  color = MasalaError,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Medium,
                  lineHeight = 18.sp,
                  modifier = Modifier.weight(1f)
                )
                IconButton(
                  onClick = { viewModel.clearError() },
                  modifier = Modifier.size(24.dp)
                ) {
                  Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss error",
                    tint = MasalaError,
                    modifier = Modifier.size(16.dp)
                  )
                }
              }
            }
          }
        }

        // Large Red WATCH & PLAY Button
        item {
          val isWatchBusy = isLoading || isPreparingPrePlayAd
          Button(
            onClick = {
              focusManager.clearFocus()
              keyboardController?.hide()
              viewModel.validateAndPlay { video ->
                if (adManager != null && activity != null) {
                  isPreparingPrePlayAd = true
                  adManager.showPrePlayAd(activity) {
                    isPreparingPrePlayAd = false
                    onNavigateToPlayer(video)
                  }
                } else {
                  onNavigateToPlayer(video)
                }
              }
            },
            enabled = !isWatchBusy,
            modifier = Modifier
              .fillMaxWidth()
              .height(56.dp)
              .testTag("watch_and_play_button"),
            colors = ButtonDefaults.buttonColors(
              containerColor = MasalaRedPrimary,
              disabledContainerColor = MasalaRedPrimary.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(14.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
          ) {
            if (isWatchBusy) {
              CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(26.dp)
              )
              Spacer(modifier = Modifier.width(12.dp))
              Text(
                text = if (isPreparingPrePlayAd) "LOADING AD..." else "LOADING VIDEO...",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
              )
            } else {
              Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "WATCH & PLAY",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp
              )
            }
          }
        }

        // Native Ad Card (Hidden cleanly during VIP ad-free sessions or if unavailable)
        item {
          NativeAdCard(isAdFree = isAdFreeSession)
        }

        // Optional Rewarded Ad Card: Watch a short video ad to unlock Ad-Free session
        if (!isAdFreeSession && adManager != null && activity != null) {
          item {
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .testTag("rewarded_ad_free_card"),
              shape = RoundedCornerShape(14.dp),
              colors = CardDefaults.cardColors(containerColor = MasalaSurface),
              border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.4f))
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.weight(1f)
                ) {
                  Box(
                    modifier = Modifier
                      .size(34.dp)
                      .clip(CircleShape)
                      .background(MasalaGold.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.Default.CardGiftcard,
                      contentDescription = null,
                      tint = MasalaGold,
                      modifier = Modifier.size(18.dp)
                    )
                  }
                  Spacer(modifier = Modifier.width(10.dp))
                  Column {
                    Text(
                      text = "Unlock Ad-Free Viewing",
                      color = MasalaTextPrimary,
                      fontSize = 13.sp,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = if (isRewardedReady) "Watch 1 short ad • 10 min ad-free VIP" else "Preparing rewarded ad...",
                      color = MasalaTextSecondary,
                      fontSize = 11.sp
                    )
                  }
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                  onClick = {
                    adManager.showRewardedAd(
                      activity = activity,
                      onRewardEarned = { _, _ ->
                        Toast.makeText(context, "10-Min VIP Ad-Free unlocked!", Toast.LENGTH_SHORT).show()
                      },
                      onAdClosed = {},
                      onNotReady = {
                        Toast.makeText(context, "Rewarded ad is loading, please try again in a moment.", Toast.LENGTH_SHORT).show()
                      }
                    )
                  },
                  shape = RoundedCornerShape(10.dp),
                  border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.7f)),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                  modifier = Modifier.testTag("watch_rewarded_ad_button")
                ) {
                  Text(
                    text = if (isRewardedReady) "Watch Ad" else "Load Ad",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }
          }
        }

        // Watch History Section
        item {
          Spacer(modifier = Modifier.height(8.dp))

          Card(
            modifier = Modifier
              .fillMaxWidth()
              .testTag("watch_history_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MasalaSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder)
          ) {
            Column(
              modifier = Modifier.padding(16.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(
                    modifier = Modifier
                      .size(28.dp)
                      .clip(CircleShape)
                      .background(MasalaRedPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.Default.History,
                      contentDescription = null,
                      tint = MasalaRedAccent,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = "Watch History",
                    color = MasalaTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                  )
                }

                if (watchHistory.isNotEmpty()) {
                  TextButton(
                    onClick = { viewModel.clearHistory() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("clear_history_button")
                  ) {
                    Text(
                      text = "Clear",
                      color = MasalaTextMuted,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Medium
                    )
                  }
                }
              }

              Spacer(modifier = Modifier.height(10.dp))

              if (watchHistory.isEmpty()) {
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Text(
                    text = "No watched videos yet. Videos you play will appear here.",
                    color = MasalaTextMuted,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                  )
                }
              } else {
                watchHistory.forEach { item ->
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(vertical = 4.dp)
                      .clip(RoundedCornerShape(10.dp))
                      .background(MasalaSurfaceVariant)
                      .clickable {
                        viewModel.playHistoryItem(item) { video ->
                          if (adManager != null && activity != null) {
                            adManager.showInterstitialAtTransition(activity) {
                              onNavigateToPlayer(video)
                            }
                          } else {
                            onNavigateToPlayer(video)
                          }
                        }
                      }
                      .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Box(
                      modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MasalaRedPrimary.copy(alpha = 0.2f)),
                      contentAlignment = Alignment.Center
                    ) {
                      Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Replay",
                        tint = MasalaRedAccent,
                        modifier = Modifier.size(20.dp)
                      )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = item.title,
                        color = MasalaTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                      )
                      Spacer(modifier = Modifier.height(2.dp))
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                          text = formatRelativeTime(item.watchedAt),
                          color = MasalaTextMuted,
                          fontSize = 11.sp
                        )
                        if (item.quality.isNotBlank()) {
                          Spacer(modifier = Modifier.width(6.dp))
                          Text(
                            text = "•  ${item.quality}",
                            color = MasalaGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                          )
                        }
                      }
                    }

                    IconButton(
                      onClick = {
                        val rawId = item.token.trim().ifBlank { item.videoId.trim() }
                        val shareUrl = if (rawId.isNotBlank()) {
                          FirestoreVideoRepository.buildShareableVideoUrl(rawId, telegramConfig.shareLinkBaseUrl)
                        } else {
                          item.streamUrl.trim()
                        }
                        if (shareUrl.isNotBlank()) {
                          coroutineScope.launch {
                            VideoShareHelper.shareVideo(
                              context = context,
                              title = item.title,
                              videoId = item.videoId,
                              thumbnailPath = item.posterUrl,
                              storagePath = item.streamUrl,
                              shareUrl = shareUrl,
                              hasDynamicOgPreview = telegramConfig.shareLinkBaseUrl.isNotBlank()
                            )
                          }
                        } else {
                          Toast.makeText(context, "Share link unavailable", Toast.LENGTH_SHORT).show()
                        }
                      },
                      modifier = Modifier
                        .size(36.dp)
                        .testTag("history_share_button_${item.videoId}")
                    ) {
                      Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Video",
                        tint = MasalaGold,
                        modifier = Modifier.size(18.dp)
                      )
                    }

                    IconButton(
                      onClick = { viewModel.deleteHistoryItem(item.videoId) },
                      modifier = Modifier.size(32.dp)
                    ) {
                      Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove from history",
                        tint = MasalaTextMuted,
                        modifier = Modifier.size(16.dp)
                      )
                    }
                  }
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(14.dp))
        }

        // Telegram Community Channel Section
        item {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .testTag("telegram_channel_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MasalaSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.4f))
          ) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  Brush.verticalGradient(
                    listOf(
                      Color(0xFF229ED9).copy(alpha = 0.12f),
                      MasalaSurface
                    )
                  )
                )
                .padding(18.dp)
            ) {
              Column {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Box(
                    modifier = Modifier
                      .size(44.dp)
                      .clip(CircleShape)
                      .background(Color(0xFF229ED9).copy(alpha = 0.2f))
                      .border(1.dp, Color(0xFF229ED9).copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.AutoMirrored.Filled.Send,
                      contentDescription = "Telegram",
                      tint = Color(0xFF229ED9),
                      modifier = Modifier.size(22.dp)
                    )
                  }

                  Spacer(modifier = Modifier.width(12.dp))

                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "Join VidVora Telegram",
                      color = MasalaTextPrimary,
                      fontSize = 15.sp,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = "Official updates & exclusive access links",
                      color = MasalaGold,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Medium
                    )
                  }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                  text = "Join our official Telegram community to get direct access links, new video releases, and discussions.",
                  color = MasalaTextSecondary,
                  fontSize = 12.sp,
                  lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                  Button(
                    onClick = {
                      try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(telegramLink))
                        context.startActivity(intent)
                      } catch (e: Exception) {
                        Toast.makeText(context, "Could not open Telegram: ${e.message}", Toast.LENGTH_SHORT).show()
                      }
                    },
                    modifier = Modifier
                      .weight(1f)
                      .height(46.dp)
                      .testTag("join_telegram_button"),
                    colors = ButtonDefaults.buttonColors(
                      containerColor = Color(0xFF229ED9),
                      contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                  ) {
                    Icon(
                      imageVector = Icons.AutoMirrored.Filled.Send,
                      contentDescription = null,
                      tint = Color.White,
                      modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text = "JOIN CHANNEL",
                      fontWeight = FontWeight.Bold,
                      fontSize = 13.sp,
                      letterSpacing = 0.8.sp
                    )
                  }

                  OutlinedButton(
                    onClick = {
                      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                      val clip = ClipData.newPlainText("Telegram Link", telegramLink)
                      clipboard.setPrimaryClip(clip)
                      Toast.makeText(context, "Telegram link copied!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                      .height(46.dp)
                      .testTag("copy_telegram_link_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextSecondary)
                  ) {
                    Icon(
                      imageVector = Icons.Default.ContentCopy,
                      contentDescription = "Copy Telegram Link",
                      modifier = Modifier.size(18.dp)
                    )
                  }
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(14.dp))
        }

        // Secondary Native Ad Card in lower feed for higher impressions
        item(key = "secondary_native_ad") {
          NativeAdCard(
            isAdFree = isAdFreeSession,
            slotTag = "native_ad_card_secondary"
          )
          Spacer(modifier = Modifier.height(78.dp))
        }
      }
    }
  }

  // Admin login dialog when lock icon is pressed
  if (showAdminLoginDialog) {
    AdminLoginDialog(
      onDismiss = { showAdminLoginDialog = false },
      onAuthenticatePasskey = { email, passkey ->
        val success = viewModel.authenticateMemberOrAdmin(email, passkey)
        if (success) {
          onNavigateToAdmin()
        }
        success
      },
      onSignInWithGoogle = {
        viewModel.signInAdminWithGoogle(context).also { result ->
          if (result.isSuccess) {
            onNavigateToAdmin()
          }
        }
      }
    )
  }

  if (showAllNoticesDialog) {
    AdminNoticesDialog(
      notifications = notifications,
      onOpenActionUrl = { actionUrl ->
        viewModel.openNotificationActionUrl(
          context = context,
          rawUrl = actionUrl,
          onPlayVideo = { video ->
            showAllNoticesDialog = false
            if (adManager != null && activity != null) {
              adManager.showInterstitialAtTransition(activity) {
                onNavigateToPlayer(video)
              }
            } else {
              onNavigateToPlayer(video)
            }
          },
          onFallbackOpened = {
            showAllNoticesDialog = false
          },
          onError = { err ->
            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
          }
        )
      },
      onDismiss = { showAllNoticesDialog = false }
    )
  }

  if (showTelegramMenuDialog) {
    TelegramCategoryMenuDialog(
      mainTelegramLink = telegramLink,
      categories = telegramCategories,
      onDismiss = {
        if (adManager != null && activity != null) {
          adManager.showInterstitialAtTransition(activity) {
            showTelegramMenuDialog = false
          }
        } else {
          showTelegramMenuDialog = false
        }
      }
    )
  }

  if (showSupportChatDialog) {
    UserSupportChatDialog(
      initialUserName = supportUserName,
      chat = supportChat,
      messages = supportMessages,
      onSendMessage = { userName, text, onDone ->
        viewModel.sendUserSupportMessage(
          context = context,
          userName = userName,
          text = text,
          onResult = onDone
        )
      },
      onMarkRead = { viewModel.markSupportChatRead() },
      onDismiss = {
        if (adManager != null && activity != null) {
          adManager.showInterstitialAtTransition(activity) {
            showSupportChatDialog = false
          }
        } else {
          showSupportChatDialog = false
        }
      }
    )
  }
}

@Composable
private fun AdminBroadcastBannerCard(
  notification: FirestoreNotification,
  totalCount: Int,
  onOpenAll: () -> Unit,
  onOpenActionUrl: (String) -> Unit,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  val imageModel = remember(notification.imageUrl) {
    AppNotificationHelper.decodeImageModel(notification.imageUrl)
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onOpenAll() }
      .testTag("home_admin_notice_banner"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.55f))
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.horizontalGradient(
            listOf(
              MasalaGold.copy(alpha = 0.14f),
              MasalaSurface
            )
          )
        )
        .padding(14.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
      ) {
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MasalaGold.copy(alpha = 0.2f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.NotificationsActive,
            contentDescription = "Announcement",
            tint = MasalaGold,
            modifier = Modifier.size(20.dp)
          )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = "ANNOUNCEMENT",
              color = MasalaGold,
              fontSize = 10.sp,
              fontWeight = FontWeight.Black,
              letterSpacing = 0.8.sp
            )
            if (totalCount > 1) {
              Text(
                text = "• $totalCount total",
                color = MasalaTextMuted,
                fontSize = 10.sp
              )
            }
          }
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = notification.title,
            color = MasalaTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(modifier = Modifier.height(3.dp))
          Text(
            text = notification.message,
            color = MasalaTextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
          )
        }

        IconButton(
          onClick = onDismiss,
          modifier = Modifier
            .size(28.dp)
            .testTag("dismiss_notice_banner_button")
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Dismiss announcement",
            tint = MasalaTextMuted,
            modifier = Modifier.size(16.dp)
          )
        }
      }

      if (imageModel != null) {
        Spacer(modifier = Modifier.height(10.dp))
        AsyncImage(
          model = imageModel,
          contentDescription = "Announcement Image",
          contentScale = ContentScale.Crop,
          modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MasalaSurfaceVariant)
            .testTag("home_notice_banner_image")
        )
      }

      if (notification.actionUrl.isNotBlank()) {
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
          onClick = { onOpenActionUrl(notification.actionUrl) },
          shape = RoundedCornerShape(10.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.7f)),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("home_notice_open_link_button")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(15.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Open Link",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }
  }
}

@Composable
private fun AdminNoticesDialog(
  notifications: List<FirestoreNotification>,
  onOpenActionUrl: (String) -> Unit,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current

  Dialog(onDismissRequest = onDismiss) {
    androidx.compose.material3.Surface(
      shape = RoundedCornerShape(20.dp),
      color = MasalaSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.5f)),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("admin_notices_dialog")
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Campaign,
            contentDescription = null,
            tint = MasalaGold,
            modifier = Modifier.size(24.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "VidVora Announcements",
            color = MasalaTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (notifications.isEmpty()) {
          Text(
            text = "No announcements right now.",
            color = MasalaTextMuted,
            fontSize = 13.sp
          )
        } else {
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .height(320.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            items(notifications.size) { index ->
              val notice = notifications[index]
              val imageModel = remember(notice.imageUrl) {
                AppNotificationHelper.decodeImageModel(notice.imageUrl)
              }
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .background(MasalaSurfaceVariant)
                  .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
                  .padding(12.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = notice.title,
                    color = MasalaGold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                  )
                  Text(
                    text = formatRelativeTime(notice.createdAt.toDate().time),
                    color = MasalaTextMuted,
                    fontSize = 10.sp
                  )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = notice.message,
                  color = MasalaTextPrimary,
                  fontSize = 12.sp,
                  lineHeight = 17.sp
                )
                if (imageModel != null) {
                  Spacer(modifier = Modifier.height(8.dp))
                  AsyncImage(
                    model = imageModel,
                    contentDescription = "Announcement Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                      .fillMaxWidth()
                      .height(130.dp)
                      .clip(RoundedCornerShape(8.dp))
                      .background(MasalaSurface)
                  )
                }
                if (notice.actionUrl.isNotBlank()) {
                  Spacer(modifier = Modifier.height(8.dp))
                  OutlinedButton(
                    onClick = { onOpenActionUrl(notice.actionUrl) },
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MasalaGold.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Icon(
                      imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                      contentDescription = null,
                      modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = "Open Link",
                      fontSize = 11.sp,
                      fontWeight = FontWeight.Bold
                    )
                  }
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
          onClick = onDismiss,
          colors = ButtonDefaults.buttonColors(containerColor = MasalaRedPrimary),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text("Close", fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

private fun formatRelativeTime(timestamp: Long): String {
  val diff = System.currentTimeMillis() - timestamp
  return when {
    diff < 60_000L -> "Just now"
    diff < 3600_000L -> "${diff / 60_000L}m ago"
    diff < 86400_000L -> "${diff / 3600_000L}h ago"
    else -> "${diff / 86400_000L}d ago"
  }
}

