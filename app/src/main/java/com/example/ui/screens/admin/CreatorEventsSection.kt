package com.example.ui.screens.admin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.model.FirestoreCreatorEvent
import com.example.data.repository.FirestoreVideoRepository
import com.example.notifications.AppNotificationHelper
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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Creator Portal entry banner for viewing Admin-published Gift Events & Contests.
 */
@Composable
fun CreatorEventsBannerCard(
  activeEvents: List<FirestoreCreatorEvent>,
  onOpenEvents: () -> Unit
) {
  val latestEvent = activeEvents.firstOrNull()
  val hasLiveEvents = activeEvents.isNotEmpty()
  val latestImageModel = remember(latestEvent?.imageUrl) {
    latestEvent?.imageUrl?.let { AppNotificationHelper.decodeImageModel(it) }
  }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(
      1.dp,
      if (hasLiveEvents) MasalaGold.copy(alpha = 0.7f) else MasalaCardBorder
    ),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onOpenEvents)
      .testTag("creator_events_banner_card")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.horizontalGradient(
            colors = listOf(
              if (hasLiveEvents) MasalaGold.copy(alpha = 0.14f) else MasalaSurface,
              if (hasLiveEvents) MasalaRedPrimary.copy(alpha = 0.12f) else MasalaSurface
            )
          )
        )
        .padding(14.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(MasalaGold.copy(alpha = 0.2f))
              .border(1.dp, MasalaGold.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.CardGiftcard,
              contentDescription = "Creator Events",
              tint = MasalaGold,
              modifier = Modifier.size(21.dp)
            )
          }

          Spacer(modifier = Modifier.width(12.dp))

          Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "Event & Gifts",
                color = MasalaTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black
              )
              if (hasLiveEvents) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                  color = MasalaRedPrimary,
                  shape = RoundedCornerShape(6.dp)
                ) {
                  Text(
                    text = "${activeEvents.size} LIVE",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                  )
                }
              }
            }
            Text(
              text = when {
                latestEvent != null -> latestEvent.title
                else -> "Check here for upcoming creator gifts & bonus announcements"
              },
              color = if (hasLiveEvents) MasalaGold else MasalaTextSecondary,
              fontSize = 11.sp,
              fontWeight = if (hasLiveEvents) FontWeight.SemiBold else FontWeight.Normal,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedButton(
          onClick = onOpenEvents,
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.7f)),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("creator_open_events_button")
        ) {
          Icon(Icons.Default.CardGiftcard, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = if (hasLiveEvents) "View (${activeEvents.size})" else "Events",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }

      if (latestEvent != null) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
            .padding(10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (latestImageModel != null) {
            AsyncImage(
              model = latestImageModel,
              contentDescription = latestEvent.title,
              contentScale = ContentScale.Crop,
              modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MasalaGold.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
          }
          Column(modifier = Modifier.weight(1f)) {
            if (latestEvent.giftLabel.isNotBlank()) {
              Surface(
                color = MasalaGold.copy(alpha = 0.16f),
                shape = RoundedCornerShape(5.dp)
              ) {
                Text(
                  text = latestEvent.giftLabel,
                  color = MasalaGold,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.ExtraBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
              }
              Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
              text = latestEvent.description,
              color = MasalaTextSecondary,
              fontSize = 11.sp,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              lineHeight = 15.sp
            )
          }
        }
      }
    }
  }
}

/**
 * Creator-facing modal showing all Live Gift Announcements & Event Details.
 */
@Composable
fun CreatorEventsDialog(
  events: List<FirestoreCreatorEvent>,
  onDismiss: () -> Unit
) {
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
        .testTag("creator_events_dialog")
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
                .background(MasalaGold.copy(alpha = 0.2f))
                .border(1.dp, MasalaGold.copy(alpha = 0.6f), CircleShape),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.CardGiftcard,
                contentDescription = null,
                tint = MasalaGold,
                modifier = Modifier.size(22.dp)
              )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text(
                text = "Creator Events & Gifts",
                color = MasalaTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black
              )
              Text(
                text = if (events.isEmpty()) "No active events right now"
                else "${events.size} active announcement(s) for Creators",
                color = MasalaGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
          }

          IconButton(
            onClick = onDismiss,
            modifier = Modifier
              .size(48.dp)
              .testTag("close_creator_events_dialog_button")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close Creator Events",
              tint = MasalaTextMuted
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (events.isEmpty()) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .background(MasalaSurfaceVariant)
              .border(1.dp, MasalaCardBorder, RoundedCornerShape(16.dp))
              .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Icon(
              imageVector = Icons.Default.CardGiftcard,
              contentDescription = null,
              tint = MasalaGold.copy(alpha = 0.7f),
              modifier = Modifier.size(42.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = "No Active Events Yet",
              color = MasalaTextPrimary,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "Whenever VidVora Admin announces special gifts, bonuses, or creator challenges, they will appear right here.",
              color = MasalaTextSecondary,
              fontSize = 12.sp,
              textAlign = TextAlign.Center,
              lineHeight = 17.sp
            )
          }
        } else {
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            items(events, key = { it.eventId }) { event ->
              CreatorEventDetailCard(event = event)
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
          onClick = onDismiss,
          colors = ButtonDefaults.buttonColors(
            containerColor = MasalaRedPrimary,
            contentColor = Color.White
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("creator_events_done_button")
        ) {
          Text("Got It", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
      }
    }
  }
}

@Composable
private fun CreatorEventDetailCard(event: FirestoreCreatorEvent) {
  val imageModel = remember(event.imageUrl) {
    AppNotificationHelper.decodeImageModel(event.imageUrl)
  }
  val formattedDate = remember(event.updatedAt) {
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(event.updatedAt.toDate())
  }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurfaceVariant),
    border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.4f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("creator_event_card_${event.eventId}")
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      if (imageModel != null) {
        AsyncImage(
          model = imageModel,
          contentDescription = "${event.title} event banner",
          contentScale = ContentScale.Crop,
          modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(MasalaBlack)
        )
      }

      Column(modifier = Modifier.padding(14.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (event.giftLabel.isNotBlank()) {
            Surface(
              color = MasalaGold.copy(alpha = 0.2f),
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.5f))
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.CardGiftcard,
                  contentDescription = null,
                  tint = MasalaGold,
                  modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = event.giftLabel,
                  color = MasalaGold,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.ExtraBold
                )
              }
            }
          } else {
            Surface(
              color = MasalaSuccess.copy(alpha = 0.16f),
              shape = RoundedCornerShape(6.dp)
            ) {
              Text(
                text = "GIFT EVENT",
                color = MasalaSuccess,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
              )
            }
          }

          Text(
            text = formattedDate,
            color = MasalaTextMuted,
            fontSize = 10.sp
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = event.title,
          color = MasalaTextPrimary,
          fontSize = 16.sp,
          fontWeight = FontWeight.Black
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
          text = event.description,
          color = MasalaTextSecondary,
          fontSize = 13.sp,
          lineHeight = 19.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "Announced by ${event.createdBy}",
          color = MasalaTextMuted,
          fontSize = 10.sp,
          fontWeight = FontWeight.Medium
        )
      }
    }
  }
}

/**
 * Admin Events management tab inside Admin Panel: create, edit (title, image, description, gift tag), pause/resume, delete.
 */
fun LazyListScope.adminEventsTabContent(
  events: List<FirestoreCreatorEvent>,
  onCreateEventClick: () -> Unit,
  onEditEventClick: (FirestoreCreatorEvent) -> Unit,
  onToggleEventActive: (FirestoreCreatorEvent) -> Unit,
  onDeleteEventClick: (FirestoreCreatorEvent) -> Unit
) {
  val liveCount = events.count { it.isActive }
  val pausedCount = (events.size - liveCount).coerceAtLeast(0)

  item {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      EventMetricCard(
        title = "TOTAL EVENTS",
        value = "${events.size}",
        accent = MasalaTextPrimary,
        modifier = Modifier.weight(1f)
      )
      EventMetricCard(
        title = "LIVE IN PORTAL",
        value = "$liveCount",
        accent = MasalaSuccess,
        modifier = Modifier.weight(1f)
      )
      EventMetricCard(
        title = "PAUSED",
        value = "$pausedCount",
        accent = MasalaGold,
        modifier = Modifier.weight(1f)
      )
    }
  }

  item {
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MasalaSurface),
      border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.55f)),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(MasalaGold.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.CardGiftcard,
              contentDescription = null,
              tint = MasalaGold,
              modifier = Modifier.size(21.dp)
            )
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Creator Gift & Event Announcements",
              color = MasalaTextPrimary,
              fontSize = 14.sp,
              fontWeight = FontWeight.Black
            )
            Text(
              text = "Publish gift contests, festive bonuses or creator offers with banner image, title & full description.",
              color = MasalaTextSecondary,
              fontSize = 11.sp,
              lineHeight = 15.sp
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
          onClick = onCreateEventClick,
          colors = ButtonDefaults.buttonColors(
            containerColor = MasalaRedPrimary,
            contentColor = Color.White
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("admin_create_event_button")
        ) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Create New Gift Event", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
      }
    }
  }

  item {
    Text(
      text = "Published Creator Events (${events.size})",
      color = MasalaTextPrimary,
      fontSize = 16.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.fillMaxWidth()
    )
  }

  if (events.isEmpty()) {
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
            imageVector = Icons.Default.EventAvailable,
            contentDescription = null,
            tint = MasalaTextMuted,
            modifier = Modifier.size(40.dp)
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = "No Creator Events Yet",
            color = MasalaTextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Tap 'Create New Gift Event' above to announce gifts or bonus challenges directly inside every Creator's panel.",
            color = MasalaTextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
          )
        }
      }
    }
  } else {
    items(events, key = { it.eventId }) { event ->
      AdminCreatorEventCard(
        event = event,
        onEdit = { onEditEventClick(event) },
        onToggleActive = { onToggleEventActive(event) },
        onDelete = { onDeleteEventClick(event) }
      )
    }
  }
}

@Composable
private fun EventMetricCard(
  title: String,
  value: String,
  accent: Color,
  modifier: Modifier = Modifier
) {
  Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, MasalaCardBorder),
    modifier = modifier
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
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
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@Composable
private fun AdminCreatorEventCard(
  event: FirestoreCreatorEvent,
  onEdit: () -> Unit,
  onToggleActive: () -> Unit,
  onDelete: () -> Unit
) {
  val imageModel = remember(event.imageUrl) {
    AppNotificationHelper.decodeImageModel(event.imageUrl)
  }
  val formattedDate = remember(event.updatedAt) {
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(event.updatedAt.toDate())
  }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(
      1.dp,
      if (event.isActive) MasalaGold.copy(alpha = 0.45f) else MasalaCardBorder
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("admin_event_card_${event.eventId}")
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      if (imageModel != null) {
        AsyncImage(
          model = imageModel,
          contentDescription = "${event.title} banner",
          contentScale = ContentScale.Crop,
          modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(MasalaBlack)
        )
      }

      Column(modifier = Modifier.padding(14.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
          ) {
            Surface(
              color = (if (event.isActive) MasalaSuccess else MasalaTextMuted).copy(alpha = 0.16f),
              shape = RoundedCornerShape(6.dp)
            ) {
              Text(
                text = if (event.isActive) "LIVE FOR CREATORS" else "PAUSED",
                color = if (event.isActive) MasalaSuccess else MasalaTextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
              )
            }

            if (event.giftLabel.isNotBlank()) {
              Surface(
                color = MasalaGold.copy(alpha = 0.18f),
                shape = RoundedCornerShape(6.dp)
              ) {
                Text(
                  text = event.giftLabel,
                  color = MasalaGold,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.ExtraBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
              }
            }
          }

          Text(
            text = formattedDate,
            color = MasalaTextMuted,
            fontSize = 10.sp
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = event.title,
          color = MasalaTextPrimary,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
          text = event.description,
          color = MasalaTextSecondary,
          fontSize = 12.sp,
          lineHeight = 17.sp,
          maxLines = 4,
          overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
              checked = event.isActive,
              onCheckedChange = { onToggleActive() },
              colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MasalaSuccess,
                uncheckedThumbColor = MasalaTextMuted,
                uncheckedTrackColor = MasalaSurfaceVariant
              ),
              modifier = Modifier.testTag("toggle_event_active_${event.eventId}")
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (event.isActive) "Visible" else "Hidden",
              color = if (event.isActive) MasalaSuccess else MasalaTextMuted,
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold
            )
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = onEdit,
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.65f)),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("edit_event_${event.eventId}")
            ) {
              Icon(Icons.Default.Edit, contentDescription = "Edit Event", modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Edit", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            IconButton(
              onClick = onDelete,
              modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MasalaError.copy(alpha = 0.14f))
                .testTag("delete_event_${event.eventId}")
            ) {
              Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Delete Event",
                tint = MasalaError,
                modifier = Modifier.size(19.dp)
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Dialog for Admin to create a new Creator Event or edit an existing one (Title, Gift Tag, Image, Description, Active toggle).
 */
@Composable
fun AdminCreatorEventEditorDialog(
  existingEvent: FirestoreCreatorEvent? = null,
  onDismiss: () -> Unit,
  onSave: (
    title: String,
    description: String,
    giftLabel: String,
    imageUri: Uri?,
    imageUrl: String,
    isActive: Boolean,
    onDone: (Boolean, String?) -> Unit
  ) -> Unit
) {
  val isEditing = existingEvent != null
  var title by remember(existingEvent?.eventId) { mutableStateOf(existingEvent?.title ?: "") }
  var giftLabel by remember(existingEvent?.eventId) { mutableStateOf(existingEvent?.giftLabel ?: "") }
  var description by remember(existingEvent?.eventId) { mutableStateOf(existingEvent?.description ?: "") }
  var selectedImageUri by remember(existingEvent?.eventId) { mutableStateOf<Uri?>(null) }
  var imageUrl by remember(existingEvent?.eventId) { mutableStateOf(existingEvent?.imageUrl ?: "") }
  var isActive by remember(existingEvent?.eventId) { mutableStateOf(existingEvent?.isActive ?: true) }
  var isSaving by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val imagePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
  ) { uri: Uri? ->
    if (uri != null) {
      selectedImageUri = uri
      imageUrl = ""
      errorMessage = null
    }
  }

  val existingImageModel = remember(imageUrl, selectedImageUri) {
    if (selectedImageUri != null) selectedImageUri
    else AppNotificationHelper.decodeImageModel(imageUrl)
  }

  val fieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MasalaGold,
    unfocusedBorderColor = MasalaCardBorder,
    focusedTextColor = MasalaTextPrimary,
    unfocusedTextColor = MasalaTextPrimary,
    focusedLabelColor = MasalaGold,
    unfocusedLabelColor = MasalaTextSecondary,
    focusedContainerColor = MasalaSurfaceVariant,
    unfocusedContainerColor = MasalaSurfaceVariant
  )

  Dialog(
    onDismissRequest = { if (!isSaving) onDismiss() },
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = MasalaSurface,
      border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.55f)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 20.dp)
        .widthIn(max = 560.dp)
        .testTag("admin_event_editor_dialog")
    ) {
      Column(
        modifier = Modifier
          .padding(18.dp)
          .verticalScroll(rememberScrollState())
      ) {
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
                .size(38.dp)
                .clip(CircleShape)
                .background(MasalaGold.copy(alpha = 0.2f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.CardGiftcard,
                contentDescription = null,
                tint = MasalaGold,
                modifier = Modifier.size(20.dp)
              )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text(
                text = if (isEditing) "Edit Creator Event" else "New Creator Gift Event",
                color = MasalaTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black
              )
              Text(
                text = "Visible inside all Creators' Event & Gifts section",
                color = MasalaTextSecondary,
                fontSize = 11.sp
              )
            }
          }

          IconButton(
            onClick = { if (!isSaving) onDismiss() },
            enabled = !isSaving,
            modifier = Modifier.size(48.dp)
          ) {
            Icon(Icons.Default.Close, contentDescription = "Close Event Editor", tint = MasalaTextMuted)
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
          label = { Text("Event Title *") },
          placeholder = { Text("e.g., Diwali Creator Gift Giveaway") },
          singleLine = true,
          enabled = !isSaving,
          colors = fieldColors,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("event_title_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = giftLabel,
          onValueChange = {
            if (it.length <= 60) {
              giftLabel = it
              errorMessage = null
            }
          },
          label = { Text("Gift / Reward Tag (Optional)") },
          placeholder = { Text("e.g., Gift Hamper • ₹5,000 Bonus") },
          leadingIcon = {
            Icon(Icons.Default.CardGiftcard, contentDescription = null, tint = MasalaGold, modifier = Modifier.size(18.dp))
          },
          singleLine = true,
          enabled = !isSaving,
          colors = fieldColors,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("event_gift_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = description,
          onValueChange = {
            if (it.length <= 1500) {
              description = it
              errorMessage = null
            }
          },
          label = { Text("Description & Gift Rules *") },
          placeholder = { Text("Explain what Creators win, eligibility, and announcement details...") },
          minLines = 3,
          maxLines = 6,
          enabled = !isSaving,
          colors = fieldColors,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("event_description_input")
        )

        Text(
          text = "${description.length}/1500 characters",
          color = MasalaTextMuted,
          fontSize = 10.sp,
          modifier = Modifier
            .align(Alignment.End)
            .padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
          text = "Event Banner Image (Optional)",
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
            enabled = !isSaving,
            colors = ButtonDefaults.outlinedButtonColors(
              contentColor = if (selectedImageUri != null) MasalaSuccess else MasalaGold
            ),
            border = BorderStroke(
              1.dp,
              if (selectedImageUri != null) MasalaSuccess else MasalaGold.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("event_pick_image_button")
          ) {
            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (selectedImageUri != null || existingImageModel != null) "Change Image" else "Pick Image from Gallery",
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
              enabled = !isSaving,
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaError),
              border = BorderStroke(1.dp, MasalaError.copy(alpha = 0.5f)),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .height(48.dp)
                .testTag("event_clear_image_button")
            ) {
              Icon(Icons.Default.Close, contentDescription = "Remove image", modifier = Modifier.size(16.dp))
            }
          }
        }

        if (existingImageModel != null) {
          Spacer(modifier = Modifier.height(8.dp))
          AsyncImage(
            model = existingImageModel,
            contentDescription = "Event image preview",
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .fillMaxWidth()
              .height(140.dp)
              .clip(RoundedCornerShape(10.dp))
              .border(1.dp, MasalaCardBorder, RoundedCornerShape(10.dp))
              .background(MasalaBlack)
              .testTag("event_image_preview")
          )
        }

        if (selectedImageUri == null && !imageUrl.startsWith("data:image/", ignoreCase = true)) {
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedTextField(
            value = imageUrl,
            onValueChange = {
              imageUrl = it
              errorMessage = null
            },
            label = { Text("Or Paste Image URL (Optional)") },
            placeholder = { Text("https://example.com/gift-banner.jpg") },
            leadingIcon = {
              Icon(Icons.Default.Image, contentDescription = null, tint = MasalaTextMuted, modifier = Modifier.size(18.dp))
            },
            singleLine = true,
            enabled = !isSaving,
            colors = fieldColors,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("event_image_url_input")
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Publish Live to Creators",
              color = MasalaTextPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = if (isActive) "All active Creators can view this event" else "Saved as paused draft",
              color = MasalaTextMuted,
              fontSize = 11.sp
            )
          }
          Switch(
            checked = isActive,
            onCheckedChange = { isActive = it },
            enabled = !isSaving,
            colors = SwitchDefaults.colors(
              checkedThumbColor = Color.White,
              checkedTrackColor = MasalaSuccess
            ),
            modifier = Modifier.testTag("event_active_switch")
          )
        }

        if (errorMessage != null) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = errorMessage!!,
            color = MasalaError,
            fontSize = 12.sp,
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
            enabled = !isSaving,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextSecondary),
            border = BorderStroke(1.dp, MasalaCardBorder),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
          ) {
            Text("Cancel")
          }

          Button(
            onClick = {
              val imageToValidate = if (selectedImageUri != null) "" else imageUrl
              val validation = FirestoreVideoRepository.validateCreatorEventInput(
                title = title,
                description = description,
                giftLabel = giftLabel,
                imageUrl = imageToValidate
              )
              if (validation != null) {
                errorMessage = validation
                return@Button
              }
              isSaving = true
              errorMessage = null
              onSave(
                title.trim(),
                description.trim(),
                giftLabel.trim(),
                selectedImageUri,
                imageUrl.trim(),
                isActive
              ) { success, err ->
                isSaving = false
                if (!success) {
                  errorMessage = err ?: "Failed to save Creator Event"
                }
              }
            },
            enabled = !isSaving,
            colors = ButtonDefaults.buttonColors(
              containerColor = MasalaRedPrimary,
              contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1.4f)
              .height(48.dp)
              .testTag("save_event_button")
          ) {
            if (isSaving) {
              CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text("Saving...", fontWeight = FontWeight.Bold)
            } else {
              Text(
                text = if (isEditing) "Save Changes" else "Publish Event",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
              )
            }
          }
        }
      }
    }
  }
}
