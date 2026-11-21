package com.example.ui.screens.admin

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.FirestoreMember
import com.example.data.model.MemberRole
import com.example.data.model.MemberStats
import com.example.data.model.formatInr
import com.example.data.repository.FirestoreVideoRepository
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
import com.example.ui.theme.MasalaWarning
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure IST (Asia/Kolkata) daily cycle helper for Superadmin's "Update Creator Views" workflow.
 *
 * Every day at **11:50 PM IST (23:50 Asia/Kolkata)** — 10 minutes before 12:00 AM IST midnight —
 * the cycle key rolls over to the next date (`YYYY-MM-DD`). Any creator/admin whose views were
 * updated during the previous cycle automatically reappears in the Pending list at 11:50 PM IST.
 * Any update saved at 11:50 PM – 11:59 PM IST or 12:00 AM – 11:49 PM IST stays hidden until
 * 11:50 PM IST of that evening.
 */
object DailyViewsResetClock {
  val IST_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
  const val RESET_HOUR_IST: Int = 23
  const val RESET_MINUTE_IST: Int = 50
  const val RESET_OFFSET_BEFORE_MIDNIGHT_MS: Long = 10L * 60L * 1000L

  fun currentCycleKeyIst(nowMs: Long = System.currentTimeMillis()): String {
    val shifted = Date(nowMs + RESET_OFFSET_BEFORE_MIDNIGHT_MS)
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
      timeZone = IST_TIME_ZONE
    }
    return sdf.format(shifted)
  }

  fun msUntilNextResetIst(nowMs: Long = System.currentTimeMillis()): Long {
    val cal = Calendar.getInstance(IST_TIME_ZONE).apply {
      timeInMillis = nowMs
      set(Calendar.HOUR_OF_DAY, RESET_HOUR_IST)
      set(Calendar.MINUTE, RESET_MINUTE_IST)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
      if (timeInMillis <= nowMs) {
        add(Calendar.DAY_OF_MONTH, 1)
      }
    }
    return (cal.timeInMillis - nowMs).coerceAtLeast(0L)
  }

  fun formatCountdown(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    if (safeMs < 60_000L) {
      val secs = (safeMs / 1000L).coerceAtLeast(1L)
      return "${secs}s"
    }
    val totalMinutes = (safeMs + 59_999L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
      hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
      hours > 0L -> "${hours}h"
      else -> "${minutes}m"
    }
  }

  fun formatIstTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val sdf = SimpleDateFormat("hh:mm a", Locale.US).apply {
      timeZone = IST_TIME_ZONE
    }
    return "${sdf.format(Date(epochMs))} IST"
  }

  fun isUpdatedInCurrentCycle(member: FirestoreMember, cycleKey: String): Boolean {
    val cleanCycle = cycleKey.trim()
    return cleanCycle.isNotBlank() && member.lastViewsUpdateCycleKey.trim() == cleanCycle
  }
}

enum class DailyViewsRoleFilter(val label: String) {
  ALL("All"),
  CREATORS("Creators"),
  ADMINS("Admins")
}

@Composable
fun AdminUpdateViewsOverviewBanner(
  pendingCount: Int,
  doneCount: Int,
  totalCount: Int,
  nextResetCountdown: String,
  onOpenUpdateViewsTab: () -> Unit
) {
  val accentColor = if (pendingCount > 0) MasalaGold else MasalaSuccess
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onOpenUpdateViewsTab() }
      .testTag("admin_update_views_overview_banner")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(CircleShape)
          .background(accentColor.copy(alpha = 0.16f))
          .border(1.dp, accentColor.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = if (pendingCount > 0) Icons.Default.TrendingUp else Icons.Default.CheckCircle,
          contentDescription = "Update Creator Views",
          tint = accentColor,
          modifier = Modifier.size(22.dp)
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Update Creator Views",
          color = MasalaTextPrimary,
          fontSize = 14.5.sp,
          fontWeight = FontWeight.ExtraBold
        )
        Text(
          text = if (pendingCount > 0) {
            "$pendingCount Pending • $doneCount/$totalCount Done Today"
          } else {
            "Sabhi $totalCount profiles aaj update ho gaye!"
          },
          color = accentColor,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Auto-show daily at 11:50 PM IST ($nextResetCountdown left)",
          color = MasalaTextMuted,
          fontSize = 10.5.sp
        )
      }

      Surface(
        shape = RoundedCornerShape(10.dp),
        color = accentColor.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f))
      ) {
        Text(
          text = if (pendingCount > 0) "Update ($pendingCount)" else "Done",
          color = accentColor,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.ExtraBold,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
      }
    }
  }
}

fun LazyListScope.adminUpdateCreatorViewsTabContent(
  memberStatsList: List<MemberStats>,
  currentCycleKey: String,
  nowIstMs: Long,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  roleFilter: DailyViewsRoleFilter,
  onRoleFilterChange: (DailyViewsRoleFilter) -> Unit,
  showDoneToday: Boolean,
  onToggleShowDoneToday: () -> Unit,
  onUpdateViewsClick: (MemberStats) -> Unit,
  onReopenMemberClick: (MemberStats) -> Unit
) {
  val pendingAll = memberStatsList.filterNot {
    DailyViewsResetClock.isUpdatedInCurrentCycle(it.member, currentCycleKey)
  }
  val doneAll = memberStatsList
    .filter { DailyViewsResetClock.isUpdatedInCurrentCycle(it.member, currentCycleKey) }
    .sortedByDescending { it.member.lastViewsUpdatedAtMs }

  val normalizedQuery = searchQuery.trim().lowercase()
  fun matchesFilters(stats: MemberStats): Boolean {
    val roleOk = when (roleFilter) {
      DailyViewsRoleFilter.ALL -> true
      DailyViewsRoleFilter.CREATORS -> !stats.member.isDelegatedAdmin()
      DailyViewsRoleFilter.ADMINS -> stats.member.isDelegatedAdmin()
    }
    if (!roleOk) return false
    if (normalizedQuery.isBlank()) return true
    return stats.member.name.lowercase().contains(normalizedQuery) ||
      stats.member.emailOrPhone.lowercase().contains(normalizedQuery) ||
      stats.member.memberId.lowercase().contains(normalizedQuery)
  }

  val filteredPending = pendingAll.filter(::matchesFilters)
  val filteredDone = doneAll.filter(::matchesFilters)

  val totalCount = memberStatsList.size
  val doneCount = doneAll.size
  val pendingCount = pendingAll.size
  val progressFraction = if (totalCount > 0) doneCount.toFloat() / totalCount.toFloat() else 0f
  val nextResetCountdown = DailyViewsResetClock.formatCountdown(
    DailyViewsResetClock.msUntilNextResetIst(nowIstMs)
  )
  val currentIstClock = DailyViewsResetClock.formatIstTime(nowIstMs)

  // 1. Top Hero Status Card with IST 11:50 PM Reset Countdown
  item(key = "daily_views_header_card") {
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MasalaSurface),
      border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.45f)),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("update_creator_views_header_card")
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Update Creator Views",
              color = MasalaTextPrimary,
              fontSize = 17.sp,
              fontWeight = FontWeight.ExtraBold
            )
            Text(
              text = "Har Creator & Admin ke views 24h me 1 baar update karein",
              color = MasalaTextSecondary,
              fontSize = 11.5.sp
            )
          }
          Surface(
            shape = RoundedCornerShape(20.dp),
            color = MasalaSurfaceVariant,
            border = BorderStroke(1.dp, MasalaCardBorder)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = MasalaGold,
                modifier = Modifier.size(13.dp)
              )
              Text(
                text = currentIstClock,
                color = MasalaGold,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Auto-Reset Schedule Banner
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MasalaGold.copy(alpha = 0.1f))
            .border(1.dp, MasalaGold.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(
            imageVector = Icons.Default.VisibilityOff,
            contentDescription = null,
            tint = MasalaGold,
            modifier = Modifier.size(16.dp)
          )
          Text(
            text = "Views update karte hi profile hide hoga • Sabhi profiles roz 11:50 PM IST par auto-show honge ($nextResetCountdown baaki)",
            color = MasalaTextPrimary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f)
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3 Metric Chips: Pending | Done Today | Total
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          DailyViewsMetricTile(
            label = "PENDING",
            value = "$pendingCount",
            accent = if (pendingCount > 0) MasalaWarning else MasalaSuccess,
            modifier = Modifier.weight(1f)
          )
          DailyViewsMetricTile(
            label = "DONE TODAY",
            value = "$doneCount",
            accent = MasalaSuccess,
            modifier = Modifier.weight(1f)
          )
          DailyViewsMetricTile(
            label = "TOTAL",
            value = "$totalCount",
            accent = MasalaGold,
            modifier = Modifier.weight(1f)
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        LinearProgressIndicator(
          progress = { progressFraction },
          color = if (pendingCount == 0 && totalCount > 0) MasalaSuccess else MasalaGold,
          trackColor = MasalaSurfaceVariant,
          modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(4.dp))
        )
      }
    }
  }

  // 2. Search + Role Filter Chips
  item(key = "daily_views_filters") {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        placeholder = {
          Text(
            text = "Search pending Creator / Admin by name or Gmail...",
            fontSize = 12.5.sp,
            color = MasalaTextMuted
          )
        },
        leadingIcon = {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Search profiles",
            tint = MasalaGold
          )
        },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(
              onClick = { onSearchQueryChange("") },
              modifier = Modifier.testTag("clear_daily_views_search_btn")
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
        colors = dailyViewsTextFieldColors(),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("daily_views_search_input")
      )

      val pendingCreatorsCount = pendingAll.count { !it.member.isDelegatedAdmin() }
      val pendingAdminsCount = pendingAll.count { it.member.isDelegatedAdmin() }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        DailyViewsRoleFilter.entries.forEach { filter ->
          val count = when (filter) {
            DailyViewsRoleFilter.ALL -> pendingAll.size
            DailyViewsRoleFilter.CREATORS -> pendingCreatorsCount
            DailyViewsRoleFilter.ADMINS -> pendingAdminsCount
          }
          FilterChip(
            selected = roleFilter == filter,
            onClick = { onRoleFilterChange(filter) },
            label = {
              Text(
                text = "${filter.label} ($count)",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
              )
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MasalaGold,
              selectedLabelColor = Color.Black,
              containerColor = MasalaSurface,
              labelColor = MasalaTextSecondary
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("daily_views_filter_${filter.name.lowercase()}")
          )
        }
      }
    }
  }

  // 3. Pending Profiles Queue
  if (totalCount == 0) {
    item(key = "daily_views_no_members") {
      Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MasalaSurface),
        border = BorderStroke(1.dp, MasalaCardBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = "Abhi koi Creator ya Admin register nahi hai",
            color = MasalaTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Members tab se Creator ya Admin add karein, fir yahan unke views daily update kar sakte hain.",
            color = MasalaTextMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
          )
        }
      }
    }
  } else if (pendingAll.isEmpty()) {
    item(key = "daily_views_all_done_state") {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MasalaSuccess.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, MasalaSuccess.copy(alpha = 0.5f)),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("daily_views_all_done_card")
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(22.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Box(
            modifier = Modifier
              .size(52.dp)
              .clip(CircleShape)
              .background(MasalaSuccess.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.CheckCircle,
              contentDescription = "All Updated",
              tint = MasalaSuccess,
              modifier = Modifier.size(32.dp)
            )
          }
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = "Aaj sabhi profiles ke views update ho gaye!",
            color = MasalaTextPrimary,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Sabhi $totalCount Creators & Admins roz raat 11:50 PM IST par apne aap wapas show ho jayenge ($nextResetCountdown baaki).",
            color = MasalaTextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
          )
        }
      }
    }
  } else if (filteredPending.isEmpty()) {
    item(key = "daily_views_filter_empty") {
      Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MasalaSurface),
        border = BorderStroke(1.dp, MasalaCardBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = "Is filter/search me koi pending profile nahi mila",
            color = MasalaTextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
          )
        }
      }
    }
  } else {
    items(
      items = filteredPending,
      key = { "daily_views_pending_${it.member.memberId}" }
    ) { stats ->
      DailyViewsPendingMemberCard(
        memberStats = stats,
        onUpdateViewsClick = { onUpdateViewsClick(stats) }
      )
    }
  }

  // 4. Collapsible "Updated Today (Hidden until 11:50 PM IST)" Drawer
  if (doneAll.isNotEmpty()) {
    item(key = "daily_views_done_drawer_header") {
      Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MasalaSurface),
        border = BorderStroke(1.dp, MasalaSuccess.copy(alpha = 0.35f)),
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onToggleShowDoneToday() }
          .testTag("daily_views_done_drawer_toggle")
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
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
          ) {
            Icon(
              imageVector = Icons.Default.VisibilityOff,
              contentDescription = null,
              tint = MasalaSuccess,
              modifier = Modifier.size(18.dp)
            )
            Column {
              Text(
                text = "Updated Today — Hidden (${doneAll.size})",
                color = MasalaSuccess,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "11:50 PM IST par wapas show honge • Tap to view or re-open",
                color = MasalaTextMuted,
                fontSize = 10.5.sp
              )
            }
          }
          Icon(
            imageVector = if (showDoneToday) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (showDoneToday) "Collapse updated list" else "Expand updated list",
            tint = MasalaTextSecondary
          )
        }
      }
    }

    if (showDoneToday) {
      items(
        items = filteredDone,
        key = { "daily_views_done_${it.member.memberId}" }
      ) { stats ->
        DailyViewsDoneMemberCard(
          memberStats = stats,
          onReopenClick = { onReopenMemberClick(stats) }
        )
      }
    }
  }
}

@Composable
private fun DailyViewsMetricTile(
  label: String,
  value: String,
  accent: Color,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(10.dp))
      .background(MasalaSurfaceVariant)
      .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
      .padding(vertical = 8.dp, horizontal = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = label,
      color = MasalaTextMuted,
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold
    )
    Text(
      text = value,
      color = accent,
      fontSize = 17.sp,
      fontWeight = FontWeight.ExtraBold
    )
  }
}

@Composable
private fun DailyViewsPendingMemberCard(
  memberStats: MemberStats,
  onUpdateViewsClick: () -> Unit
) {
  val member = memberStats.member
  val isAdminRole = member.isDelegatedAdmin()
  val roleBadgeColor = if (isAdminRole) Color(0xFFB388FF) else Color(0xFF26C6DA)

  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, MasalaCardBorder),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("daily_views_pending_card_${member.memberId}")
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = member.name.ifBlank { "Unnamed Member" },
              color = MasalaTextPrimary,
              fontSize = 15.sp,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false)
            )
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = roleBadgeColor.copy(alpha = 0.16f),
              border = BorderStroke(1.dp, roleBadgeColor.copy(alpha = 0.5f))
            ) {
              Text(
                text = MemberRole.displayLabel(member.role).uppercase(),
                color = roleBadgeColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
            if (!member.isActive) {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = MasalaError.copy(alpha = 0.15f)
              ) {
                Text(
                  text = "INACTIVE",
                  color = MasalaError,
                  fontSize = 8.5.sp,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
              }
            }
          }
          if (member.emailOrPhone.isNotBlank()) {
            Text(
              text = member.emailOrPhone,
              color = MasalaTextMuted,
              fontSize = 11.5.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
          if (member.referredByName.isNotBlank()) {
            Text(
              text = "Referred by ${member.referredByName} • +7% Admin Bonus",
              color = MasalaGold,
              fontSize = 10.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MasalaWarning.copy(alpha = 0.14f),
          border = BorderStroke(1.dp, MasalaWarning.copy(alpha = 0.45f))
        ) {
          Text(
            text = "PENDING",
            color = MasalaWarning,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Compact 4-column stats row
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(MasalaSurfaceVariant)
          .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("24H REAL", color = MasalaWarning, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
          Text(
            text = "${memberStats.viewsLast24Hours}",
            color = MasalaWarning,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
          Text("TOTAL REAL", color = MasalaRedAccent, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
          Text(
            text = "${memberStats.totalViews}",
            color = MasalaRedAccent,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
          Text("PANEL VIEWS", color = MasalaSuccess, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
          Text(
            text = "${member.creditedViews}",
            color = MasalaSuccess,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
          Text("EARNING", color = MasalaGold, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
          Text(
            text = formatInr(memberStats.earningSummary.totalEarning),
            color = MasalaGold,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Button(
        onClick = onUpdateViewsClick,
        colors = ButtonDefaults.buttonColors(
          containerColor = MasalaGold,
          contentColor = Color.Black
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .testTag("daily_update_views_btn_${member.memberId}")
      ) {
        Icon(
          imageVector = Icons.Default.Visibility,
          contentDescription = null,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Update Views & Hide Today",
          fontWeight = FontWeight.ExtraBold,
          fontSize = 13.5.sp
        )
      }
    }
  }
}

@Composable
private fun DailyViewsDoneMemberCard(
  memberStats: MemberStats,
  onReopenClick: () -> Unit
) {
  val member = memberStats.member
  val updatedTime = DailyViewsResetClock.formatIstTime(member.lastViewsUpdatedAtMs)
  val roleLabel = MemberRole.displayLabel(member.role)

  Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurfaceVariant.copy(alpha = 0.7f)),
    border = BorderStroke(1.dp, MasalaSuccess.copy(alpha = 0.25f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("daily_views_done_card_${member.memberId}")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = "Updated Today",
        tint = MasalaSuccess,
        modifier = Modifier.size(20.dp)
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "${member.name.ifBlank { "Member" }} • $roleLabel",
          color = MasalaTextPrimary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = buildString {
            append("Panel Views: ${member.creditedViews} • ${formatInr(memberStats.earningSummary.totalEarning)}")
            if (updatedTime.isNotBlank()) {
              append(" • $updatedTime")
            }
          },
          color = MasalaTextMuted,
          fontSize = 10.5.sp,
          maxLines = 2
        )
      }
      OutlinedButton(
        onClick = onReopenClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag("daily_reopen_views_btn_${member.memberId}")
      ) {
        Icon(
          imageVector = Icons.Default.Refresh,
          contentDescription = "Re-open profile",
          modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("Re-open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}

@Composable
fun DailyUpdateViewsDialog(
  memberStats: MemberStats,
  onDismiss: () -> Unit,
  onSaveDailyViews: (
    views: Long,
    ratePer1000: Double,
    isAddition: Boolean,
    onDone: (Boolean, String?) -> Unit
  ) -> Unit
) {
  val member = memberStats.member
  var isAddition by remember(member.memberId) { mutableStateOf(true) }
  var viewsText by remember(member.memberId) { mutableStateOf("") }
  val initialRate = remember(member.memberId, member.viewRatePer1000) {
    val r = member.viewRatePer1000
    if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
  }
  var ratePer1000Text by remember(member.memberId) { mutableStateOf(initialRate) }
  var isSaving by remember { mutableStateOf(false) }
  var errorMsg by remember { mutableStateOf<String?>(null) }

  val parsedViews = viewsText.toLongOrNull() ?: 0L
  val parsedRate = ratePer1000Text.toDoubleOrNull() ?: member.viewRatePer1000
  val calculatedInr = FirestoreVideoRepository.calculateViewsEarning(parsedViews, parsedRate)
  val previewViewsResult = if (isAddition) {
    member.creditedViews + parsedViews.coerceAtLeast(0L)
  } else {
    parsedViews.coerceAtLeast(0L)
  }
  val previewEarningResult = if (isAddition) {
    memberStats.earningSummary.totalEarning + calculatedInr
  } else {
    calculatedInr
  }

  Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
    Card(
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = MasalaSurface),
      border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.55f)),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("daily_update_views_dialog")
    ) {
      Column(
        modifier = Modifier
          .padding(18.dp)
          .verticalScroll(rememberScrollState())
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Update Daily Views",
              color = MasalaTextPrimary,
              fontSize = 17.sp,
              fontWeight = FontWeight.ExtraBold
            )
            Text(
              text = "${member.name.ifBlank { "Member" }} (${MemberRole.displayLabel(member.role)})",
              color = MasalaGold,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
            if (member.emailOrPhone.isNotBlank()) {
              Text(
                text = member.emailOrPhone,
                color = MasalaTextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            if (member.referredByName.isNotBlank()) {
              Text(
                text = "Referred by ${member.referredByName} • +7% Admin Bonus auto-add hoga",
                color = MasalaSuccess,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
          }
          IconButton(
            onClick = { if (!isSaving) onDismiss() },
            modifier = Modifier.testTag("close_daily_views_dialog_btn")
          ) {
            Icon(Icons.Default.Close, contentDescription = "Close dialog", tint = MasalaTextSecondary)
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Current snapshot strip
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MasalaSurfaceVariant)
            .padding(10.dp),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("24H REAL", color = MasalaWarning, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
            Text("${memberStats.viewsLast24Hours}", color = MasalaWarning, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
          }
          Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PANEL VIEWS", color = MasalaSuccess, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
            Text("${member.creditedViews}", color = MasalaSuccess, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
          }
          Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("EARNING", color = MasalaGold, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
            Text(formatInr(memberStats.earningSummary.totalEarning), color = MasalaGold, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Add (+Views) vs Set Total Views
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          FilterChip(
            selected = isAddition,
            onClick = {
              isAddition = true
              viewsText = ""
              errorMsg = null
            },
            label = { Text("Add Today (+Views)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MasalaRedPrimary,
              selectedLabelColor = Color.White,
              containerColor = MasalaSurfaceVariant,
              labelColor = MasalaTextSecondary
            ),
            modifier = Modifier.weight(1f)
          )
          FilterChip(
            selected = !isAddition,
            onClick = {
              isAddition = false
              viewsText = member.creditedViews.toString()
              errorMsg = null
            },
            label = { Text("Set Total Views", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MasalaRedPrimary,
              selectedLabelColor = Color.White,
              containerColor = MasalaSurfaceVariant,
              labelColor = MasalaTextSecondary
            ),
            modifier = Modifier.weight(1f)
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = viewsText,
          onValueChange = { input ->
            viewsText = input.filter { it.isDigit() }
            errorMsg = null
          },
          label = { Text(if (isAddition) "Aaj ke Views (0 allowed to hide)" else "New Total Panel Views") },
          placeholder = { Text(if (isAddition) "e.g. 1000 ya 0" else "${member.creditedViews}") },
          singleLine = true,
          enabled = !isSaving,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null, tint = MasalaGold) },
          colors = dailyViewsTextFieldColors(),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("daily_views_count_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Quick Fill Row: 24h Real Views + Presets
        if (memberStats.viewsLast24Hours > 0L && isAddition) {
          OutlinedButton(
            onClick = {
              viewsText = memberStats.viewsLast24Hours.toString()
              errorMsg = null
            },
            enabled = !isSaving,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MasalaWarning.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaWarning),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 42.dp)
              .testTag("daily_views_use_24h_real_btn")
          ) {
            Text(
              text = "Use 24h Real Views (+${memberStats.viewsLast24Hours})",
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
          }
          Spacer(modifier = Modifier.height(6.dp))
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          listOf(0L, 500L, 1000L, 5000L).forEach { preset ->
            OutlinedButton(
              onClick = {
                if (preset == 0L) {
                  viewsText = "0"
                } else {
                  val currentInput = viewsText.toLongOrNull() ?: 0L
                  viewsText = if (isAddition) (currentInput + preset).toString() else preset.toString()
                }
                errorMsg = null
              },
              enabled = !isSaving,
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.45f)),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
              contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
              modifier = Modifier.weight(1f)
            ) {
              Text(
                text = when {
                  preset == 0L -> "0"
                  isAddition -> "+$preset"
                  else -> "$preset"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = ratePer1000Text,
          onValueChange = { input ->
            ratePer1000Text = input.filter { it.isDigit() || it == '.' }
            errorMsg = null
          },
          label = { Text("Rate per 1,000 Views (₹)") },
          placeholder = { Text("190") },
          singleLine = true,
          enabled = !isSaving,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          leadingIcon = { Icon(Icons.Default.CurrencyRupee, contentDescription = null, tint = MasalaGold) },
          colors = dailyViewsTextFieldColors(),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("daily_views_rate_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Live Preview + Auto-Hide Info Box
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MasalaGold.copy(alpha = 0.1f))
            .border(1.dp, MasalaGold.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(10.dp)
        ) {
          Text(
            text = if (isAddition) {
              if (parsedViews == 0L) "No new views (+₹0) • Profile will be marked Done Today"
              else "Auto Earning: +${formatInr(calculatedInr)}"
            } else {
              "Auto Total Earning: ${formatInr(calculatedInr)}"
            },
            color = MasalaGold,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.ExtraBold
          )
          Text(
            text = "After Save → Panel Views: $previewViewsResult • Total Earning: ${formatInr(previewEarningResult)}",
            color = MasalaTextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
          )
          HorizontalDivider(
            color = MasalaGold.copy(alpha = 0.25f),
            modifier = Modifier.padding(vertical = 6.dp)
          )
          Text(
            text = "Save karte hi yeh profile hide ho jayega aur aaj raat 11:50 PM IST par wapas show hoga.",
            color = MasalaSuccess,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold
          )
        }

        if (!errorMsg.isNullOrBlank()) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = errorMsg ?: "",
            color = MasalaError,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedButton(
            onClick = { if (!isSaving) onDismiss() },
            enabled = !isSaving,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .weight(0.9f)
              .heightIn(min = 48.dp)
          ) {
            Text("Cancel", color = MasalaTextSecondary, fontWeight = FontWeight.Bold)
          }

          Button(
            onClick = {
              val viewsVal = viewsText.trim().toLongOrNull()
              val rateVal = ratePer1000Text.trim().toDoubleOrNull()
              if (viewsVal == null || viewsVal < 0L) {
                errorMsg = "Please enter valid views (0 ya usse zyada)."
                return@Button
              }
              if (rateVal == null || rateVal < 0.0) {
                errorMsg = "Please enter a valid rate per 1,000 views."
                return@Button
              }
              isSaving = true
              errorMsg = null
              onSaveDailyViews(viewsVal, rateVal, isAddition) { ok, err ->
                isSaving = false
                if (ok) {
                  onDismiss()
                } else {
                  errorMsg = err ?: "Views update nahi ho paye. Dobara try karein."
                }
              }
            },
            enabled = !isSaving,
            colors = ButtonDefaults.buttonColors(
              containerColor = MasalaGold,
              contentColor = Color.Black
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .weight(1.3f)
              .heightIn(min = 48.dp)
              .testTag("daily_views_save_button")
          ) {
            if (isSaving) {
              CircularProgressIndicator(
                color = Color.Black,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Saving...", fontWeight = FontWeight.ExtraBold)
            } else {
              Text("Update & Hide", fontWeight = FontWeight.ExtraBold)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun dailyViewsTextFieldColors() = OutlinedTextFieldDefaults.colors(
  focusedTextColor = MasalaTextPrimary,
  unfocusedTextColor = MasalaTextPrimary,
  focusedBorderColor = MasalaGold,
  unfocusedBorderColor = MasalaCardBorder,
  focusedContainerColor = MasalaBlack,
  unfocusedContainerColor = MasalaBlack,
  focusedLabelColor = MasalaGold,
  unfocusedLabelColor = MasalaTextMuted,
  cursorColor = MasalaGold
)
