package com.example.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.FirestoreSupportChat
import com.example.data.model.FirestoreSupportMessage
import com.example.data.model.SupportChatStatus
import com.example.data.model.SupportSenderRole
import com.example.data.model.TelegramCategoryItem
import com.example.data.repository.FirestoreVideoRepository
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TelegramBlue = Color(0xFF229ED9)

@Composable
fun TelegramCategoryMenuDialog(
  mainTelegramLink: String,
  categories: List<TelegramCategoryItem>,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current

  fun openTelegramUrl(rawUrl: String) {
    val cleanUrl = FirestoreVideoRepository.normalizeTelegramUrl(rawUrl)
    if (cleanUrl.isBlank()) return
    try {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl))
      context.startActivity(intent)
    } catch (e: Exception) {
      Toast.makeText(context, "Could not open Telegram: ${e.message}", Toast.LENGTH_SHORT).show()
    }
  }

  fun copyLink(label: String, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, url))
    Toast.makeText(context, "$label link copied!", Toast.LENGTH_SHORT).show()
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = MasalaSurface,
      border = BorderStroke(1.dp, TelegramBlue.copy(alpha = 0.45f)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .widthIn(max = 520.dp)
        .testTag("telegram_category_menu_dialog")
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
          ) {
            Box(
              modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(TelegramBlue.copy(alpha = 0.2f))
                .border(1.dp, TelegramBlue.copy(alpha = 0.5f), CircleShape),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = TelegramBlue,
                modifier = Modifier.size(20.dp)
              )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = "Telegram Channels",
              color = MasalaTextPrimary,
              fontSize = 17.sp,
              fontWeight = FontWeight.Black
            )
          }

          IconButton(
            onClick = onDismiss,
            modifier = Modifier
              .size(48.dp)
              .testTag("close_telegram_menu_button")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close Menu",
              tint = MasalaTextMuted
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Main Official Channel Quick Card
        if (mainTelegramLink.isNotBlank()) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(TelegramBlue.copy(alpha = 0.14f))
              .border(1.dp, TelegramBlue.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
              .clickable { openTelegramUrl(mainTelegramLink) }
              .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Official VidVora Channel",
                color = MasalaTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = mainTelegramLink,
                color = TelegramBlue,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            IconButton(
              onClick = { copyLink("Official Telegram", mainTelegramLink) },
              modifier = Modifier.size(48.dp)
            ) {
              Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy Main Link",
                tint = MasalaTextSecondary,
                modifier = Modifier.size(18.dp)
              )
            }
            Icon(
              imageVector = Icons.AutoMirrored.Filled.OpenInNew,
              contentDescription = "Open Main Channel",
              tint = TelegramBlue,
              modifier = Modifier.size(18.dp)
            )
          }

          Spacer(modifier = Modifier.height(12.dp))
        }

        Text(
          text = "SELECT CATEGORY",
          color = MasalaTextMuted,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          if (categories.isEmpty()) {
            item {
              Text(
                text = "No Telegram categories available right now.",
                color = MasalaTextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp)
              )
            }
          } else {
            items(categories.size, key = { idx -> categories[idx].id }) { index ->
            val category = categories[index]
            val number = index + 1
            val rawLink = category.link.trim()
            val cleanLink = FirestoreVideoRepository.normalizeTelegramUrl(rawLink)
            val isConfigured = cleanLink.isNotBlank()

            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MasalaSurfaceVariant)
                .border(
                  1.dp,
                  if (isConfigured) MasalaCardBorder else MasalaCardBorder.copy(alpha = 0.45f),
                  RoundedCornerShape(12.dp)
                )
                .then(
                  if (isConfigured) {
                    Modifier.clickable { openTelegramUrl(cleanLink) }
                  } else {
                    Modifier
                  }
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("telegram_category_item_${category.id}"),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Box(
                modifier = Modifier
                  .size(30.dp)
                  .clip(CircleShape)
                  .background(
                    if (isConfigured) TelegramBlue.copy(alpha = 0.2f)
                    else MasalaSurface
                  ),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = "$number",
                  color = if (isConfigured) TelegramBlue else MasalaTextMuted,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Black
                )
              }

              Spacer(modifier = Modifier.width(10.dp))

              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "$number. ${category.name}",
                  color = MasalaTextPrimary,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = if (isConfigured) cleanLink else "Admin hasn't added link yet",
                  color = if (isConfigured) MasalaGold else MasalaTextMuted,
                  fontSize = 11.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }

              if (isConfigured) {
                IconButton(
                  onClick = { copyLink(category.name, cleanLink) },
                  modifier = Modifier
                    .size(48.dp)
                    .testTag("copy_category_${category.id}")
                ) {
                  Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy ${category.name} Link",
                    tint = MasalaTextSecondary,
                    modifier = Modifier.size(17.dp)
                  )
                }

                IconButton(
                  onClick = { openTelegramUrl(cleanLink) },
                  modifier = Modifier
                    .size(48.dp)
                    .testTag("open_category_${category.id}")
                ) {
                  Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open ${category.name} Channel",
                    tint = TelegramBlue,
                    modifier = Modifier.size(18.dp)
                  )
                }
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
fun UserSupportChatDialog(
  initialUserName: String,
  chat: FirestoreSupportChat?,
  messages: List<FirestoreSupportMessage>,
  onSendMessage: (userName: String, text: String, onDone: (Boolean, String?) -> Unit) -> Unit,
  onMarkRead: () -> Unit,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  var userNameInput by remember(initialUserName) { mutableStateOf(initialUserName) }
  var messageInput by remember { mutableStateOf("") }
  var isSending by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }
  val listState = rememberLazyListState()

  LaunchedEffect(chat?.unreadByUserCount, messages.size) {
    if ((chat?.unreadByUserCount ?: 0L) > 0L) {
      onMarkRead()
    }
    if (messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.lastIndex)
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = MasalaSurface,
      border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.5f)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 20.dp)
        .widthIn(max = 560.dp)
        .testTag("user_support_chat_dialog")
    ) {
      Column(modifier = Modifier.padding(18.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
          ) {
            Box(
              modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MasalaRedPrimary.copy(alpha = 0.2f))
                .border(1.dp, MasalaRedAccent.copy(alpha = 0.5f), CircleShape),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.SupportAgent,
                contentDescription = null,
                tint = MasalaRedAccent,
                modifier = Modifier.size(22.dp)
              )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  text = "Chat Support",
                  color = MasalaTextPrimary,
                  fontSize = 16.sp,
                  fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.width(8.dp))
                val isResolved = chat?.status == SupportChatStatus.RESOLVED
                Surface(
                  color = (if (isResolved) MasalaSuccess else MasalaGold).copy(alpha = 0.16f),
                  shape = RoundedCornerShape(6.dp)
                ) {
                  Text(
                    text = if (isResolved) "RESOLVED" else "LIVE",
                    color = if (isResolved) MasalaSuccess else MasalaGold,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                  )
                }
              }
              Text(
                text = "Message directly • Reply only from Admin",
                color = MasalaTextSecondary,
                fontSize = 11.sp
              )
            }
          }

          IconButton(
            onClick = onDismiss,
            modifier = Modifier
              .size(48.dp)
              .testTag("close_support_chat_button")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close Support Chat",
              tint = MasalaTextMuted
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = userNameInput,
          onValueChange = { userNameInput = it.take(80) },
          label = { Text("Your Name (Optional)", fontSize = 12.sp) },
          placeholder = { Text("Guest User", fontSize = 12.sp) },
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
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
            .testTag("support_user_name_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Messages area
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 210.dp, max = 300.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaCardBorder, RoundedCornerShape(14.dp))
            .padding(10.dp)
        ) {
          if (messages.isEmpty()) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(16.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Icon(
                imageVector = Icons.Default.SupportAgent,
                contentDescription = null,
                tint = MasalaGold,
                modifier = Modifier.size(36.dp)
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = "Need help with links or app?",
                color = MasalaTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Send your message below. Only the VidVora Admin can reply to your chat.",
                color = MasalaTextMuted,
                fontSize = 11.sp
              )
            }
          } else {
            LazyColumn(
              state = listState,
              verticalArrangement = Arrangement.spacedBy(8.dp),
              contentPadding = PaddingValues(vertical = 4.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              items(messages, key = { it.messageId }) { msg ->
                val isAdminReply = msg.senderRole == SupportSenderRole.ADMIN
                SupportMessageBubble(
                  message = msg,
                  alignRight = !isAdminReply,
                  isAdminBadge = isAdminReply
                )
              }
            }
          }
        }

        if (errorText != null) {
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = errorText!!,
            color = MasalaError,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = messageInput,
            onValueChange = {
              messageInput = it.take(1000)
              if (errorText != null) errorText = null
            },
            placeholder = { Text("Write your message to Admin...", fontSize = 13.sp) },
            maxLines = 3,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MasalaRedPrimary,
              unfocusedBorderColor = MasalaCardBorder,
              focusedTextColor = MasalaTextPrimary,
              unfocusedTextColor = MasalaTextPrimary,
              focusedContainerColor = MasalaSurfaceVariant,
              unfocusedContainerColor = MasalaSurfaceVariant
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("support_message_input")
          )

          Button(
            onClick = {
              val trimmed = messageInput.trim()
              val validation = FirestoreVideoRepository.validateSupportMessageInput(trimmed)
              if (validation != null) {
                errorText = validation
                return@Button
              }
              isSending = true
              errorText = null
              onSendMessage(userNameInput, trimmed) { success, err ->
                isSending = false
                if (success) {
                  messageInput = ""
                } else {
                  errorText = err ?: "Could not send message"
                  Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show()
                }
              }
            },
            enabled = !isSending,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MasalaRedPrimary),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
              .size(50.dp)
              .testTag("send_support_message_button")
          ) {
            if (isSending) {
              CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
              )
            } else {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send Support Message",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun SupportMessageBubble(
  message: FirestoreSupportMessage,
  alignRight: Boolean,
  isAdminBadge: Boolean
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (alignRight) Arrangement.End else Arrangement.Start
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 280.dp)
        .clip(
          RoundedCornerShape(
            topStart = 14.dp,
            topEnd = 14.dp,
            bottomStart = if (alignRight) 14.dp else 4.dp,
            bottomEnd = if (alignRight) 4.dp else 14.dp
          )
        )
        .background(
          when {
            isAdminBadge -> MasalaGold.copy(alpha = 0.16f)
            alignRight -> MasalaRedPrimary.copy(alpha = 0.24f)
            else -> MasalaSurface
          }
        )
        .border(
          1.dp,
          when {
            isAdminBadge -> MasalaGold.copy(alpha = 0.45f)
            alignRight -> MasalaRedAccent.copy(alpha = 0.35f)
            else -> MasalaCardBorder
          },
          RoundedCornerShape(
            topStart = 14.dp,
            topEnd = 14.dp,
            bottomStart = if (alignRight) 14.dp else 4.dp,
            bottomEnd = if (alignRight) 4.dp else 14.dp
          )
        )
        .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        if (isAdminBadge) {
          Icon(
            imageVector = Icons.Default.AdminPanelSettings,
            contentDescription = null,
            tint = MasalaGold,
            modifier = Modifier.size(13.dp)
          )
        }
        Text(
          text = if (isAdminBadge) FirestoreVideoRepository.SUPPORT_TEAM_NAME else message.senderName,
          color = if (isAdminBadge) MasalaGold else MasalaTextSecondary,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold
        )
      }
      Spacer(modifier = Modifier.height(3.dp))
      val context = LocalContext.current
      val detectedLinks = remember(message.text) {
        extractDetectedChatLinks(message.text).distinctBy { it.resolvedUrl }
      }
      val annotatedMessage = remember(message.text) {
        buildLinkifiedChatAnnotatedString(
          text = message.text,
          onLinkClick = { targetUrl ->
            openChatLink(context, targetUrl)
          }
        )
      }
      Text(
        text = annotatedMessage,
        color = MasalaTextPrimary,
        fontSize = 13.sp,
        lineHeight = 18.sp
      )
      if (detectedLinks.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
          detectedLinks.forEachIndexed { idx, link ->
            Surface(
              color = TelegramBlue.copy(alpha = 0.16f),
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, TelegramBlue.copy(alpha = 0.5f)),
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { openChatLink(context, link.resolvedUrl) }
                .testTag("chat_link_chip_${message.messageId}_$idx")
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = link.displayText,
                  color = Color(0xFF4FC3F7),
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                  contentDescription = "Open Link",
                  tint = Color(0xFF4FC3F7),
                  modifier = Modifier.size(14.dp)
                )
              }
            }
          }
        }
      }
      Spacer(modifier = Modifier.height(3.dp))
      Text(
        text = formatChatTime(message.createdAt.toDate()),
        color = MasalaTextMuted,
        fontSize = 9.sp
      )
    }
  }
}

data class DetectedChatLink(
  val startIndex: Int,
  val endIndex: Int,
  val displayText: String,
  val resolvedUrl: String
)

private val CHAT_LINK_REGEX = Regex(
  """(?:https?://|vidvora://|tg://)[^\s<>"{}|\\^`\[\]]+|(?:www\.|(?:t|telegram)\.me/|telegram\.dog/)[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+|@[A-Za-z0-9_]{4,32}|[A-Za-z0-9-]+(?:\.[A-Za-z]{2,})+(?:/[^\s<>"{}|\\^`\[\]]*)?""",
  RegexOption.IGNORE_CASE
)

fun extractDetectedChatLinks(text: String): List<DetectedChatLink> {
  if (text.isBlank()) return emptyList()
  val detected = mutableListOf<DetectedChatLink>()
  for (match in CHAT_LINK_REGEX.findAll(text)) {
    val start = match.range.first
    var raw = match.value
    if (raw.startsWith("@")) {
      if (start > 0) {
        val prev = text[start - 1]
        if (prev.isLetterOrDigit() || prev == '.' || prev == '_' || prev == '-') {
          continue
        }
      }
    } else if (!raw.contains("://") && start > 0 && text[start - 1] == '@') {
      continue
    }

    while (raw.isNotEmpty() && raw.last() in charArrayOf('.', ',', '!', '?', ';', ':', ')', ']', '"', '\'')) {
      raw = raw.dropLast(1)
    }
    if (raw.isBlank()) continue
    val end = start + raw.length
    val resolved = normalizeDetectedChatLinkUrl(raw)
    if (resolved.isNotBlank()) {
      detected.add(
        DetectedChatLink(
          startIndex = start,
          endIndex = end,
          displayText = raw,
          resolvedUrl = resolved
        )
      )
    }
  }
  return detected
}

fun normalizeDetectedChatLinkUrl(raw: String): String {
  val clean = raw.trim()
  if (clean.isBlank()) return ""
  return when {
    clean.startsWith("http://", ignoreCase = true) ||
      clean.startsWith("https://", ignoreCase = true) ||
      clean.startsWith("vidvora://", ignoreCase = true) ||
      clean.startsWith("tg://", ignoreCase = true) -> clean
    clean.startsWith("@") -> "https://t.me/${clean.removePrefix("@")}"
    else -> "https://$clean"
  }
}

private fun buildLinkifiedChatAnnotatedString(
  text: String,
  onLinkClick: (String) -> Unit
) = buildAnnotatedString {
  val links = extractDetectedChatLinks(text)
  if (links.isEmpty()) {
    append(text)
    return@buildAnnotatedString
  }
  var cursor = 0
  for (link in links) {
    if (link.startIndex > cursor) {
      append(text.substring(cursor, link.startIndex))
    }
    val linkStyles = TextLinkStyles(
      style = SpanStyle(
        color = Color(0xFF4FC3F7),
        fontWeight = FontWeight.SemiBold,
        textDecoration = TextDecoration.Underline
      )
    )
    withLink(
      LinkAnnotation.Clickable(
        tag = link.resolvedUrl,
        styles = linkStyles,
        linkInteractionListener = { onLinkClick(link.resolvedUrl) }
      )
    ) {
      append(link.displayText)
    }
    cursor = link.endIndex
  }
  if (cursor < text.length) {
    append(text.substring(cursor))
  }
}

private fun openChatLink(context: Context, url: String) {
  val safeUrl = normalizeDetectedChatLinkUrl(url)
  if (safeUrl.isBlank()) return
  if (com.example.notifications.AppNotificationHelper.openNotificationUrl(context, safeUrl)) {
    return
  }
  try {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
  } catch (e: Exception) {
    Toast.makeText(context, "Could not open link: ${e.message}", Toast.LENGTH_SHORT).show()
  }
}

private fun formatChatTime(date: Date): String {
  return try {
    SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(date)
  } catch (_: Exception) {
    ""
  }
}
