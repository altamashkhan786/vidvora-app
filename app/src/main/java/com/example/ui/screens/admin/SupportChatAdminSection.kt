package com.example.ui.screens.admin

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.SupportAgent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.FirestoreSupportChat
import com.example.data.model.FirestoreSupportMessage
import com.example.data.model.SupportChatStatus
import com.example.data.model.SupportSenderRole
import com.example.data.repository.FirestoreVideoRepository
import com.example.ui.screens.home.SupportMessageBubble
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

@Composable
fun AdminSupportOverviewBanner(
  totalChats: Int,
  unreadChatsCount: Int,
  openChatsCount: Int,
  onOpenSupportTab: () -> Unit
) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(
      1.dp,
      if (unreadChatsCount > 0) MasalaRedAccent.copy(alpha = 0.6f) else MasalaCardBorder
    ),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onOpenSupportTab)
      .testTag("admin_support_overview_card")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
              if (unreadChatsCount > 0) MasalaRedPrimary.copy(alpha = 0.2f)
              else MasalaGold.copy(alpha = 0.16f)
            ),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = if (unreadChatsCount > 0) Icons.Default.MarkChatUnread else Icons.Default.SupportAgent,
            contentDescription = null,
            tint = if (unreadChatsCount > 0) MasalaRedAccent else MasalaGold,
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
          Text(
            text = "User Support Chats",
            color = MasalaTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = if (unreadChatsCount > 0) {
              "$unreadChatsCount unread user chats • $openChatsCount open / $totalChats total"
            } else {
              "$openChatsCount open • $totalChats total conversations"
            },
            color = if (unreadChatsCount > 0) MasalaGold else MasalaTextSecondary,
            fontSize = 11.sp,
            fontWeight = if (unreadChatsCount > 0) FontWeight.SemiBold else FontWeight.Normal
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      OutlinedButton(
        onClick = onOpenSupportTab,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.65f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
        modifier = Modifier.testTag("open_support_tab_button")
      ) {
        Text("Reply Chats", fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}

fun LazyListScope.adminSupportTabContent(
  chats: List<FirestoreSupportChat>,
  unreadCount: Int,
  statusFilter: String,
  onSelectStatusFilter: (String) -> Unit,
  onOpenConversation: (FirestoreSupportChat) -> Unit,
  onToggleStatus: (FirestoreSupportChat) -> Unit,
  onDeleteChat: (String) -> Unit
) {
  val openCount = chats.count { it.status == SupportChatStatus.OPEN }

  item {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      SupportMetricCard(
        title = "TOTAL CHATS",
        value = "${chats.size}",
        accent = MasalaTextPrimary,
        modifier = Modifier.weight(1f)
      )
      SupportMetricCard(
        title = "UNREAD",
        value = "$unreadCount",
        accent = if (unreadCount > 0) MasalaRedAccent else MasalaGold,
        modifier = Modifier.weight(1f)
      )
      SupportMetricCard(
        title = "OPEN",
        value = "$openCount",
        accent = MasalaSuccess,
        modifier = Modifier.weight(1f)
      )
    }
  }

  item {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      listOf(
        "ALL" to "All (${chats.size})",
        SupportChatStatus.OPEN to "Open ($openCount)",
        SupportChatStatus.RESOLVED to "Resolved (${chats.size - openCount})"
      ).forEach { (key, label) ->
        val selected = statusFilter == key
        Surface(
          color = if (selected) MasalaGold.copy(alpha = 0.2f) else MasalaSurface,
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, if (selected) MasalaGold else MasalaCardBorder),
          modifier = Modifier
            .weight(1f)
            .clickable { onSelectStatusFilter(key) }
            .testTag("support_filter_$key")
        ) {
          Box(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = label,
              color = if (selected) MasalaGold else MasalaTextSecondary,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }
  }

  val filteredChats = chats.filter { chat ->
    when (statusFilter) {
      SupportChatStatus.OPEN -> chat.status == SupportChatStatus.OPEN
      SupportChatStatus.RESOLVED -> chat.status == SupportChatStatus.RESOLVED
      else -> true
    }
  }

  if (filteredChats.isEmpty()) {
    item {
      Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MasalaSurface),
        border = BorderStroke(1.dp, MasalaCardBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(
            imageVector = Icons.Default.SupportAgent,
            contentDescription = null,
            tint = MasalaTextMuted,
            modifier = Modifier.size(38.dp)
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = "No Support Chats Yet",
            color = MasalaTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "When users message from the Support button on Home, their conversations appear here for Admin reply.",
            color = MasalaTextSecondary,
            fontSize = 12.sp
          )
        }
      }
    }
  } else {
    items(filteredChats, key = { it.chatId }) { chat ->
      AdminSupportChatThreadCard(
        chat = chat,
        onOpen = { onOpenConversation(chat) },
        onToggleStatus = { onToggleStatus(chat) },
        onDelete = { onDeleteChat(chat.chatId) }
      )
    }
  }
}

@Composable
private fun SupportMetricCard(
  title: String,
  value: String,
  accent: Color,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, MasalaCardBorder)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = value,
        color = accent,
        fontSize = 20.sp,
        fontWeight = FontWeight.Black
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = title,
        color = MasalaTextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1
      )
    }
  }
}

@Composable
private fun AdminSupportChatThreadCard(
  chat: FirestoreSupportChat,
  onOpen: () -> Unit,
  onToggleStatus: () -> Unit,
  onDelete: () -> Unit
) {
  val hasUnread = chat.unreadByAdminCount > 0L
  val isResolved = chat.status == SupportChatStatus.RESOLVED

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(
      1.dp,
      when {
        hasUnread -> MasalaRedAccent.copy(alpha = 0.7f)
        isResolved -> MasalaSuccess.copy(alpha = 0.35f)
        else -> MasalaCardBorder
      }
    ),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onOpen)
      .testTag("admin_support_chat_${chat.chatId}")
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
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(
                if (hasUnread) MasalaRedPrimary.copy(alpha = 0.22f)
                else MasalaSurfaceVariant
              ),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = if (hasUnread) Icons.Default.MarkChatUnread else Icons.Default.SupportAgent,
              contentDescription = null,
              tint = if (hasUnread) MasalaRedAccent else MasalaGold,
              modifier = Modifier.size(18.dp)
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = chat.userName.ifBlank { "Guest User" },
              color = MasalaTextPrimary,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = "${chat.deviceLabel} • ${formatSupportRelativeTime(chat.lastMessageAt.toDate().time)}",
              color = MasalaTextMuted,
              fontSize = 11.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          if (hasUnread) {
            Surface(
              color = MasalaRedPrimary,
              shape = RoundedCornerShape(6.dp)
            ) {
              Text(
                text = "${chat.unreadByAdminCount} NEW",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
              )
            }
          }
          if (chat.chatId.startsWith("creator_")) {
            Surface(
              color = MasalaGold.copy(alpha = 0.18f),
              shape = RoundedCornerShape(6.dp)
            ) {
              Text(
                text = "CREATOR",
                color = MasalaGold,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
              )
            }
          }
          Surface(
            color = (if (isResolved) MasalaSuccess else MasalaGold).copy(alpha = 0.16f),
            shape = RoundedCornerShape(6.dp)
          ) {
            Text(
              text = chat.status,
              color = if (isResolved) MasalaSuccess else MasalaGold,
              fontSize = 9.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      val senderPrefix = when {
        chat.lastSenderRole == SupportSenderRole.ADMIN -> "VidVora Team: "
        chat.chatId.startsWith("creator_") -> "Creator: "
        else -> "User: "
      }
      Text(
        text = senderPrefix + chat.lastMessage,
        color = MasalaTextSecondary,
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(10.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = onOpen,
            colors = ButtonDefaults.buttonColors(containerColor = MasalaRedPrimary),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("reply_support_chat_${chat.chatId}")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Reply,
              contentDescription = null,
              modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Reply", fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }

          OutlinedButton(
            onClick = onToggleStatus,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, if (isResolved) MasalaGold else MasalaSuccess),
            colors = ButtonDefaults.outlinedButtonColors(
              contentColor = if (isResolved) MasalaGold else MasalaSuccess
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
          ) {
            Icon(
              imageVector = Icons.Default.CheckCircle,
              contentDescription = null,
              modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = if (isResolved) "Reopen" else "Resolve",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }

        IconButton(
          onClick = onDelete,
          modifier = Modifier.size(48.dp)
        ) {
          Icon(
            imageVector = Icons.Default.DeleteOutline,
            contentDescription = "Delete Chat Thread",
            tint = MasalaError
          )
        }
      }
    }
  }
}

@Composable
fun AdminSupportConversationDialog(
  chat: FirestoreSupportChat,
  messages: List<FirestoreSupportMessage>,
  onSendReply: (text: String, onDone: (Boolean, String?) -> Unit) -> Unit,
  onToggleStatus: () -> Unit,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  var replyInput by remember { mutableStateOf("") }
  var isSending by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }
  val listState = rememberLazyListState()

  LaunchedEffect(messages.size) {
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
      border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.55f)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 20.dp)
        .widthIn(max = 580.dp)
        .testTag("admin_support_conversation_dialog")
    ) {
      Column(modifier = Modifier.padding(18.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = chat.userName.ifBlank { "Guest User" },
              color = MasalaTextPrimary,
              fontSize = 16.sp,
              fontWeight = FontWeight.Black,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = "${chat.deviceLabel} • ${chat.status}",
              color = MasalaGold,
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold
            )
          }

          val isResolved = chat.status == SupportChatStatus.RESOLVED
          OutlinedButton(
            onClick = onToggleStatus,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, if (isResolved) MasalaGold else MasalaSuccess),
            colors = ButtonDefaults.outlinedButtonColors(
              contentColor = if (isResolved) MasalaGold else MasalaSuccess
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
          ) {
            Text(
              text = if (isResolved) "Reopen" else "Mark Resolved",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold
            )
          }

          IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(48.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close Conversation",
              tint = MasalaTextMuted
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 330.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaCardBorder, RoundedCornerShape(14.dp))
            .padding(10.dp)
        ) {
          if (messages.isEmpty()) {
            Text(
              text = "No messages loaded yet.",
              color = MasalaTextMuted,
              fontSize = 12.sp,
              modifier = Modifier.align(Alignment.Center)
            )
          } else {
            LazyColumn(
              state = listState,
              verticalArrangement = Arrangement.spacedBy(8.dp),
              contentPadding = PaddingValues(vertical = 4.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              items(messages, key = { it.messageId }) { msg ->
                val isAdminMsg = msg.senderRole == SupportSenderRole.ADMIN
                SupportMessageBubble(
                  message = msg,
                  alignRight = isAdminMsg,
                  isAdminBadge = isAdminMsg
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
            value = replyInput,
            onValueChange = {
              replyInput = it.take(1000)
              if (errorText != null) errorText = null
            },
            placeholder = { Text("Type Admin reply...", fontSize = 13.sp) },
            maxLines = 3,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MasalaGold,
              unfocusedBorderColor = MasalaCardBorder,
              focusedTextColor = MasalaTextPrimary,
              unfocusedTextColor = MasalaTextPrimary,
              focusedContainerColor = MasalaSurfaceVariant,
              unfocusedContainerColor = MasalaSurfaceVariant
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("admin_support_reply_input")
          )

          Button(
            onClick = {
              val trimmed = replyInput.trim()
              val validation = FirestoreVideoRepository.validateSupportMessageInput(trimmed)
              if (validation != null) {
                errorText = validation
                return@Button
              }
              isSending = true
              errorText = null
              onSendReply(trimmed) { success, err ->
                isSending = false
                if (success) {
                  replyInput = ""
                } else {
                  errorText = err ?: "Failed to send reply"
                  Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show()
                }
              }
            },
            enabled = !isSending,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MasalaGold, contentColor = Color.Black),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
              .size(50.dp)
              .testTag("send_admin_support_reply_button")
          ) {
            if (isSending) {
              CircularProgressIndicator(
                color = Color.Black,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
              )
            } else {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send Admin Reply",
                modifier = Modifier.size(20.dp)
              )
            }
          }
        }
      }
    }
  }
}

private fun formatSupportRelativeTime(timestampMs: Long): String {
  val diff = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)
  val minutes = diff / 60_000L
  val hours = diff / 3_600_000L
  val days = diff / 86_400_000L
  return when {
    minutes < 2 -> "Just now"
    minutes < 60 -> "${minutes}m ago"
    hours < 24 -> "${hours}h ago"
    else -> "${days}d ago"
  }
}

@Composable
fun CreatorSupportBannerCard(
  chat: FirestoreSupportChat?,
  onOpenChat: () -> Unit
) {
  val unreadCount = chat?.unreadByUserCount ?: 0L
  val hasUnread = unreadCount > 0L

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(
      1.dp,
      if (hasUnread) MasalaRedAccent.copy(alpha = 0.65f) else MasalaGold.copy(alpha = 0.4f)
    ),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onOpenChat)
      .testTag("creator_support_chat_card")
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
            .background(
              if (hasUnread) MasalaRedPrimary.copy(alpha = 0.22f)
              else MasalaGold.copy(alpha = 0.16f)
            ),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = if (hasUnread) Icons.Default.MarkChatUnread else Icons.Default.SupportAgent,
            contentDescription = null,
            tint = if (hasUnread) MasalaRedAccent else MasalaGold,
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "VidVora Support",
              color = MasalaTextPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
            if (hasUnread) {
              Spacer(modifier = Modifier.width(6.dp))
              Surface(
                color = MasalaRedPrimary,
                shape = RoundedCornerShape(6.dp)
              ) {
                Text(
                  text = "$unreadCount NEW",
                  color = Color.White,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.Black,
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
              }
            }
          }
          Text(
            text = when {
              hasUnread -> "$unreadCount new reply from ${FirestoreVideoRepository.SUPPORT_TEAM_NAME}"
              chat != null && chat.lastMessage.isNotBlank() -> chat.lastMessage
              else -> "Need help? Message ${FirestoreVideoRepository.SUPPORT_TEAM_NAME}"
            },
            color = if (hasUnread) MasalaGold else MasalaTextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      OutlinedButton(
        onClick = onOpenChat,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.65f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
        modifier = Modifier.testTag("creator_open_support_chat_button")
      ) {
        Icon(Icons.Default.SupportAgent, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Chat Support", fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}

@Composable
fun CreatorSupportChatDialog(
  creatorName: String,
  creatorEmail: String,
  chat: FirestoreSupportChat?,
  messages: List<FirestoreSupportMessage>,
  onSendMessage: (text: String, onDone: (Boolean, String?) -> Unit) -> Unit,
  onMarkRead: () -> Unit,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
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
        .testTag("creator_support_chat_dialog")
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
                  text = "VidVora Support",
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
                text = "From: ${creatorName.ifBlank { "Creator" }} • $creatorEmail",
                color = MasalaTextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }

          IconButton(
            onClick = onDismiss,
            modifier = Modifier
              .size(48.dp)
              .testTag("close_creator_support_chat_button")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close Creator Support Chat",
              tint = MasalaTextMuted
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 210.dp, max = 320.dp)
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
                text = "Need help with payouts, videos, or links?",
                color = MasalaTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Send your message below. ${FirestoreVideoRepository.SUPPORT_TEAM_NAME} will reply here directly.",
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
            placeholder = { Text("Message VidVora Support Team...", fontSize = 13.sp) },
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
              .testTag("creator_support_message_input")
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
              onSendMessage(trimmed) { success, err ->
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
              .testTag("creator_send_support_message_button")
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
