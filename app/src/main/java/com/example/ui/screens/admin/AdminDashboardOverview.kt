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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FirestoreVideo
import com.example.data.model.FirestoreWithdrawalRequest
import com.example.data.model.MemberStats
import com.example.data.model.WithdrawalStatus
import com.example.data.model.formatInr
import com.example.ui.theme.MasalaCardBorder
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
import java.text.NumberFormat
import java.util.Locale

data class AdminLifetimeAnalytics(
  val lifetimeVideoViews: Long = 0L,
  val lifetimeCreditedViews: Long = 0L,
  val viewsLast24Hours: Long = 0L,
  val lifetimeWithdrawalTotalPaid: Double = 0.0,
  val lifetimeCreatorWithdrawalPaid: Double = 0.0,
  val lifetimeAdminWithdrawalPaid: Double = 0.0,
  val lifetimePaidTransactionCount: Int = 0,
  val pendingOrProcessingAmount: Double = 0.0,
  val pendingOrProcessingCount: Int = 0
)

fun computeAdminLifetimeAnalytics(
  videos: List<FirestoreVideo>,
  memberStatsList: List<MemberStats>,
  withdrawalRequests: List<FirestoreWithdrawalRequest>
): AdminLifetimeAnalytics {
  val lifetimeVideoViews = videos.sumOf { it.totalViews.coerceAtLeast(0L) }
  val lifetimeCreditedViews = memberStatsList.sumOf { it.member.creditedViews.coerceAtLeast(0L) }
  val viewsLast24Hours = memberStatsList.sumOf { it.viewsLast24Hours.coerceAtLeast(0L) }

  val isAdminByMemberId = memberStatsList.associate { it.member.memberId.trim() to it.member.isDelegatedAdmin() }
  var totalPaid = 0.0
  var creatorPaid = 0.0
  var adminPaid = 0.0
  var paidCount = 0
  var pendingAmount = 0.0
  var pendingCount = 0

  withdrawalRequests.forEach { req ->
    val safeAmount = req.amount.takeIf { !it.isNaN() && it > 0.0 } ?: 0.0
    when (req.status.trim().uppercase(Locale.US)) {
      WithdrawalStatus.DONE -> {
        totalPaid += safeAmount
        paidCount += 1
        if (isAdminByMemberId[req.memberId.trim()] == true) {
          adminPaid += safeAmount
        } else {
          creatorPaid += safeAmount
        }
      }
      WithdrawalStatus.PENDING, WithdrawalStatus.PROCESSING -> {
        pendingAmount += safeAmount
        pendingCount += 1
      }
    }
  }

  return AdminLifetimeAnalytics(
    lifetimeVideoViews = lifetimeVideoViews,
    lifetimeCreditedViews = lifetimeCreditedViews,
    viewsLast24Hours = viewsLast24Hours,
    lifetimeWithdrawalTotalPaid = totalPaid,
    lifetimeCreatorWithdrawalPaid = creatorPaid,
    lifetimeAdminWithdrawalPaid = adminPaid,
    lifetimePaidTransactionCount = paidCount,
    pendingOrProcessingAmount = pendingAmount,
    pendingOrProcessingCount = pendingCount
  )
}

fun formatIndianCount(value: Long): String {
  val digits = value.coerceAtLeast(0L).toString()
  if (digits.length <= 3) return digits
  val lastThree = digits.takeLast(3)
  val leading = digits.dropLast(3)
  val groupedLeading = leading.reversed().chunked(2).joinToString(",").reversed()
  return "$groupedLeading,$lastThree"
}

data class AdminNavPillItem(
  val label: String,
  val icon: ImageVector,
  val badge: String? = null,
  val accentColor: Color = MasalaRedPrimary,
  val testTag: String
)

@Composable
fun AdminPillNavigationBar(
  items: List<AdminNavPillItem>,
  selectedIndex: Int,
  onSelectIndex: (Int) -> Unit
) {
  val rowState = rememberLazyListState()
  LaunchedEffect(selectedIndex) {
    if (selectedIndex in items.indices) {
      rowState.animateScrollToItem(selectedIndex.coerceAtLeast(0))
    }
  }

  LazyRow(
    state = rowState,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    contentPadding = PaddingValues(vertical = 2.dp),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("admin_pill_nav_bar")
  ) {
    itemsIndexed(items) { index, item ->
      val isSelected = index == selectedIndex
      val containerColor = if (isSelected) item.accentColor else MasalaSurface
      val contentColor = if (isSelected) {
        if (item.accentColor == MasalaGold) Color.Black else Color.White
      } else {
        MasalaTextSecondary
      }
      val borderColor = if (isSelected) item.accentColor else MasalaCardBorder

      Surface(
        shape = RoundedCornerShape(22.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
          .heightIn(min = 40.dp)
          .clip(RoundedCornerShape(22.dp))
          .clickable { onSelectIndex(index) }
          .testTag(item.testTag)
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
          )
          Text(
            text = item.label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
          )
          if (!item.badge.isNullOrBlank()) {
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                  if (isSelected) Color.Black.copy(alpha = 0.22f)
                  else item.accentColor.copy(alpha = 0.18f)
                )
                .padding(horizontal = 6.dp, vertical = 1.5.dp)
            ) {
              Text(
                text = item.badge,
                color = if (isSelected) contentColor else item.accentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                softWrap = false
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun LifetimeViewsHeroCard(
  analytics: AdminLifetimeAnalytics,
  onClick: (() -> Unit)? = null
) {
  val gradientBrush = Brush.linearGradient(
    colors = listOf(
      Color(0xFF241621),
      MasalaSurface,
      Color(0xFF1D1826)
    )
  )
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    border = BorderStroke(1.dp, MasalaRedAccent.copy(alpha = 0.45f)),
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .background(gradientBrush)
      .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
      .testTag("admin_lifetime_views_card")
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(MasalaRedAccent.copy(alpha = 0.18f))
              .border(1.dp, MasalaRedAccent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Visibility,
              contentDescription = "Lifetime Views",
              tint = MasalaRedAccent,
              modifier = Modifier.size(21.dp)
            )
          }
          Column {
            Text(
              text = "LIFETIME VIEWS",
              color = MasalaTextSecondary,
              fontSize = 10.5.sp,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = 0.8.sp
            )
            Text(
              text = "All-Time Real Video Views",
              color = MasalaTextMuted,
              fontSize = 11.sp
            )
          }
        }
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MasalaRedAccent.copy(alpha = 0.15f),
          border = BorderStroke(1.dp, MasalaRedAccent.copy(alpha = 0.4f))
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(
              imageVector = Icons.Default.TrendingUp,
              contentDescription = null,
              tint = MasalaRedAccent,
              modifier = Modifier.size(13.dp)
            )
            Text(
              text = "ALL TIME",
              color = MasalaRedAccent,
              fontSize = 9.5.sp,
              fontWeight = FontWeight.ExtraBold
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = formatIndianCount(analytics.lifetimeVideoViews),
        color = MasalaTextPrimary,
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.testTag("admin_lifetime_views_value")
      )

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(Color.Black.copy(alpha = 0.28f))
          .border(1.dp, MasalaCardBorder.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
          .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("PANEL CREDITED VIEWS", color = MasalaTextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
          Text(
            text = formatIndianCount(analytics.lifetimeCreditedViews),
            color = MasalaSuccess,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
          Text("LAST 24 HOURS", color = MasalaTextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
          Text(
            text = formatIndianCount(analytics.viewsLast24Hours),
            color = MasalaWarning,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
      }
    }
  }
}

@Composable
fun LifetimeWithdrawalHeroCard(
  analytics: AdminLifetimeAnalytics,
  onClick: (() -> Unit)? = null
) {
  val gradientBrush = Brush.linearGradient(
    colors = listOf(
      Color(0xFF13241D),
      MasalaSurface,
      Color(0xFF211C13)
    )
  )
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    border = BorderStroke(1.dp, MasalaSuccess.copy(alpha = 0.45f)),
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .background(gradientBrush)
      .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
      .testTag("admin_lifetime_withdrawal_card")
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(MasalaSuccess.copy(alpha = 0.18f))
              .border(1.dp, MasalaSuccess.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.AccountBalanceWallet,
              contentDescription = "Lifetime Total Withdrawal Paid",
              tint = MasalaSuccess,
              modifier = Modifier.size(21.dp)
            )
          }
          Column {
            Text(
              text = "LIFETIME WITHDRAWAL PAID",
              color = MasalaTextSecondary,
              fontSize = 10.5.sp,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = 0.8.sp
            )
            Text(
              text = "Creators & Admins Total Payouts",
              color = MasalaTextMuted,
              fontSize = 11.sp
            )
          }
        }
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MasalaSuccess.copy(alpha = 0.16f),
          border = BorderStroke(1.dp, MasalaSuccess.copy(alpha = 0.45f))
        ) {
          Text(
            text = "${analytics.lifetimePaidTransactionCount} PAID",
            color = MasalaSuccess,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = formatInr(analytics.lifetimeWithdrawalTotalPaid),
        color = MasalaGold,
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.testTag("admin_lifetime_withdrawal_value")
      )

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(Color.Black.copy(alpha = 0.28f))
          .border(1.dp, MasalaCardBorder.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
          .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("CREATORS PAID", color = MasalaTextMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
          Text(
            text = formatInr(analytics.lifetimeCreatorWithdrawalPaid),
            color = Color(0xFF26C6DA),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
          Text("ADMINS PAID", color = MasalaTextMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
          Text(
            text = formatInr(analytics.lifetimeAdminWithdrawalPaid),
            color = Color(0xFFB388FF),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
          Text("PENDING QUEUE", color = MasalaTextMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
          Text(
            text = formatInr(analytics.pendingOrProcessingAmount),
            color = MasalaWarning,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}

data class AdminToolTile(
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val accentColor: Color,
  val badge: String? = null,
  val testTag: String,
  val onClick: () -> Unit
)

fun LazyListScope.adminOverviewDashboardContent(
  analytics: AdminLifetimeAnalytics,
  showLifetimeViews: Boolean,
  showLifetimeWithdrawals: Boolean,
  totalVideos: Int,
  activeLinks: Int,
  totalMembers: Int,
  totalInstalls: Int,
  toolTiles: List<AdminToolTile>,
  onLifetimeViewsClick: (() -> Unit)?,
  onLifetimeWithdrawalsClick: (() -> Unit)?
) {
  if (showLifetimeViews || showLifetimeWithdrawals) {
    item(key = "overview_lifetime_heading") {
      Text(
        text = "LIFETIME SUMMARY",
        color = MasalaTextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.sp
      )
    }
  }

  if (showLifetimeViews) {
    item(key = "overview_lifetime_views_card") {
      LifetimeViewsHeroCard(
        analytics = analytics,
        onClick = onLifetimeViewsClick
      )
    }
  }

  if (showLifetimeWithdrawals) {
    item(key = "overview_lifetime_withdrawal_card") {
      LifetimeWithdrawalHeroCard(
        analytics = analytics,
        onClick = onLifetimeWithdrawalsClick
      )
    }
  }

  // Compact unified 4-stat pulse strip
  item(key = "overview_platform_pulse") {
    Card(
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = MasalaSurface),
      border = BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("admin_platform_pulse_card")
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
      ) {
        PulseMetricCell(label = "VIDEOS", value = formatIndianCount(totalVideos.toLong()), color = MasalaTextPrimary, modifier = Modifier.weight(1f))
        PulseMetricCell(label = "LINKS", value = formatIndianCount(activeLinks.toLong()), color = MasalaSuccess, modifier = Modifier.weight(1f))
        PulseMetricCell(label = "MEMBERS", value = formatIndianCount(totalMembers.toLong()), color = MasalaGold, modifier = Modifier.weight(1f))
        PulseMetricCell(label = "INSTALLS", value = formatIndianCount(totalInstalls.toLong()), color = Color(0xFF4FC3F7), modifier = Modifier.weight(1f))
      }
    }
  }

  if (toolTiles.isNotEmpty()) {
    item(key = "overview_tools_heading") {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = "ADMIN TOOLS & CONTROLS",
          color = MasalaTextMuted,
          fontSize = 11.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 1.sp
        )
        Text(
          text = "${toolTiles.size} Modules",
          color = MasalaGold,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    val rows = toolTiles.chunked(2)
    itemsIndexed(
      items = rows,
      key = { idx, _ -> "overview_tools_row_$idx" }
    ) { _, rowTiles ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        rowTiles.forEach { tile ->
          AdminFeatureGridCard(
            tile = tile,
            modifier = Modifier.weight(1f)
          )
        }
        if (rowTiles.size == 1) {
          Spacer(modifier = Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
private fun PulseMetricCell(
  label: String,
  value: String,
  color: Color,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = value,
      color = color,
      fontSize = 16.sp,
      fontWeight = FontWeight.ExtraBold,
      maxLines = 1
    )
    Text(
      text = label,
      color = MasalaTextMuted,
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1
    )
  }
}

@Composable
private fun AdminFeatureGridCard(
  tile: AdminToolTile,
  modifier: Modifier = Modifier
) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, tile.accentColor.copy(alpha = 0.28f)),
    modifier = modifier
      .heightIn(min = 108.dp)
      .clip(RoundedCornerShape(16.dp))
      .clickable { tile.onClick() }
      .testTag(tile.testTag)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(13.dp),
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tile.accentColor.copy(alpha = 0.16f))
            .border(1.dp, tile.accentColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = tile.icon,
            contentDescription = tile.title,
            tint = tile.accentColor,
            modifier = Modifier.size(19.dp)
          )
        }

        if (!tile.badge.isNullOrBlank()) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = tile.accentColor.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, tile.accentColor.copy(alpha = 0.4f))
          ) {
            Text(
              text = tile.badge,
              color = tile.accentColor,
              fontSize = 9.5.sp,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 1,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
            )
          }
        } else {
          Icon(
            imageVector = Icons.Default.ArrowForwardIos,
            contentDescription = null,
            tint = MasalaTextMuted,
            modifier = Modifier
              .padding(top = 4.dp)
              .size(12.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Column {
        Text(
          text = tile.title,
          color = MasalaTextPrimary,
          fontSize = 13.5.sp,
          fontWeight = FontWeight.ExtraBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = tile.subtitle,
          color = MasalaTextMuted,
          fontSize = 10.5.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 2.dp)
        )
      }
    }
  }
}

@Composable
fun AdminSectionHeaderBanner(
  title: String,
  subtitle: String,
  accentColor: Color = MasalaGold
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = title,
      color = MasalaTextPrimary,
      fontSize = 16.sp,
      fontWeight = FontWeight.ExtraBold
    )
    Text(
      text = subtitle,
      color = MasalaTextMuted,
      fontSize = 11.5.sp
    )
    Spacer(modifier = Modifier.height(6.dp))
    HorizontalDivider(color = accentColor.copy(alpha = 0.25f))
  }
}

data class CreatorLifetimeAnalytics(
  val lifetimeCreditedViews: Long = 0L,
  val viewRatePer1000: Double = 190.0,
  val totalEarning: Double = 0.0,
  val lifetimeWithdrawalPaid: Double = 0.0,
  val paidTransactionCount: Int = 0,
  val pendingOrProcessingAmount: Double = 0.0,
  val availableBalance: Double = 0.0
)

fun computeCreatorLifetimeAnalytics(
  creditedViews: Long,
  viewRatePer1000: Double,
  summary: com.example.data.model.EarningSummary,
  withdrawalRequests: List<FirestoreWithdrawalRequest>
): CreatorLifetimeAnalytics {
  val doneCount = withdrawalRequests.count {
    it.status.trim().uppercase(Locale.US) == WithdrawalStatus.DONE
  }
  return CreatorLifetimeAnalytics(
    lifetimeCreditedViews = creditedViews.coerceAtLeast(0L),
    viewRatePer1000 = viewRatePer1000.coerceAtLeast(0.0),
    totalEarning = summary.totalEarning.coerceAtLeast(0.0),
    lifetimeWithdrawalPaid = summary.paidAmount.coerceAtLeast(0.0),
    paidTransactionCount = doneCount,
    pendingOrProcessingAmount = summary.pendingOrProcessingAmount.coerceAtLeast(0.0),
    availableBalance = summary.availableBalance.coerceAtLeast(0.0)
  )
}

@Composable
fun CreatorLifetimeViewsHeroCard(
  analytics: CreatorLifetimeAnalytics,
  onClick: (() -> Unit)? = null
) {
  val gradientBrush = Brush.linearGradient(
    colors = listOf(
      Color(0xFF241621),
      MasalaSurface,
      Color(0xFF1D1826)
    )
  )
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    border = BorderStroke(1.dp, MasalaRedAccent.copy(alpha = 0.45f)),
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .background(gradientBrush)
      .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
      .testTag("creator_lifetime_views_card")
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Box(
          modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MasalaRedAccent.copy(alpha = 0.18f))
            .border(1.dp, MasalaRedAccent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Visibility,
            contentDescription = "My Lifetime Views",
            tint = MasalaRedAccent,
            modifier = Modifier.size(21.dp)
          )
        }
        Column {
          Text(
            text = "MY LIFETIME VIEWS",
            color = MasalaTextSecondary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp
          )
          Text(
            text = "Total Credited Creator Views",
            color = MasalaTextMuted,
            fontSize = 11.sp
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = formatIndianCount(analytics.lifetimeCreditedViews),
        color = MasalaTextPrimary,
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.testTag("creator_lifetime_views_value")
      )

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(Color.Black.copy(alpha = 0.28f))
          .border(1.dp, MasalaCardBorder.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
          .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("LIFETIME EARNED", color = MasalaTextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
          Text(
            text = formatInr(analytics.totalEarning),
            color = MasalaGold,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
          Text("AVAILABLE BALANCE", color = MasalaTextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
          Text(
            text = formatInr(analytics.availableBalance),
            color = MasalaSuccess,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
      }
    }
  }
}

@Composable
fun CreatorLifetimeWithdrawalHeroCard(
  analytics: CreatorLifetimeAnalytics,
  onClick: (() -> Unit)? = null
) {
  val gradientBrush = Brush.linearGradient(
    colors = listOf(
      Color(0xFF13241D),
      MasalaSurface,
      Color(0xFF211C13)
    )
  )
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    border = BorderStroke(1.dp, MasalaSuccess.copy(alpha = 0.45f)),
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .background(gradientBrush)
      .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
      .testTag("creator_lifetime_withdrawal_card")
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(MasalaSuccess.copy(alpha = 0.18f))
              .border(1.dp, MasalaSuccess.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.AccountBalanceWallet,
              contentDescription = "My Lifetime Withdrawal Paid",
              tint = MasalaSuccess,
              modifier = Modifier.size(21.dp)
            )
          }
          Column {
            Text(
              text = "MY LIFETIME WITHDRAWAL",
              color = MasalaTextSecondary,
              fontSize = 10.5.sp,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = 0.8.sp
            )
            Text(
              text = "Total Paid to Your UPI",
              color = MasalaTextMuted,
              fontSize = 11.sp
            )
          }
        }
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MasalaSuccess.copy(alpha = 0.16f),
          border = BorderStroke(1.dp, MasalaSuccess.copy(alpha = 0.45f))
        ) {
          Text(
            text = "${analytics.paidTransactionCount} PAID",
            color = MasalaSuccess,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = formatInr(analytics.lifetimeWithdrawalPaid),
        color = MasalaGold,
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.testTag("creator_lifetime_withdrawal_value")
      )

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(Color.Black.copy(alpha = 0.28f))
          .border(1.dp, MasalaCardBorder.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
          .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("AVAILABLE", color = MasalaTextMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
          Text(
            text = formatInr(analytics.availableBalance),
            color = Color(0xFF26C6DA),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
          Text("IN QUEUE", color = MasalaTextMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
          Text(
            text = formatInr(analytics.pendingOrProcessingAmount),
            color = MasalaWarning,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
          Text("TOTAL EARNED", color = MasalaTextMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
          Text(
            text = formatInr(analytics.totalEarning),
            color = MasalaGold,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}

fun LazyListScope.creatorOverviewDashboardContent(
  analytics: CreatorLifetimeAnalytics,
  showEarningsCard: Boolean,
  myVideosCount: Int,
  myActiveVideosCount: Int,
  myActiveLinksCount: Int,
  toolTiles: List<AdminToolTile>,
  onLifetimeViewsClick: (() -> Unit)?,
  onLifetimeWithdrawalsClick: (() -> Unit)?
) {
  item(key = "creator_overview_lifetime_heading") {
    Text(
      text = "MY LIFETIME SUMMARY",
      color = MasalaTextMuted,
      fontSize = 11.sp,
      fontWeight = FontWeight.ExtraBold,
      letterSpacing = 1.sp
    )
  }

  item(key = "creator_overview_lifetime_views_card") {
    CreatorLifetimeViewsHeroCard(
      analytics = analytics,
      onClick = onLifetimeViewsClick
    )
  }

  if (showEarningsCard) {
    item(key = "creator_overview_lifetime_withdrawal_card") {
      CreatorLifetimeWithdrawalHeroCard(
        analytics = analytics,
        onClick = onLifetimeWithdrawalsClick
      )
    }
  }

  item(key = "creator_overview_pulse") {
    Card(
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = MasalaSurface),
      border = BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("creator_platform_pulse_card")
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
      ) {
        PulseMetricCell(label = "MY VIDEOS", value = formatIndianCount(myVideosCount.toLong()), color = MasalaTextPrimary, modifier = Modifier.weight(1f))
        PulseMetricCell(label = "ACTIVE", value = formatIndianCount(myActiveVideosCount.toLong()), color = MasalaSuccess, modifier = Modifier.weight(1f))
        PulseMetricCell(label = "LINKS", value = formatIndianCount(myActiveLinksCount.toLong()), color = MasalaGold, modifier = Modifier.weight(1f))
      }
    }
  }

  if (toolTiles.isNotEmpty()) {
    item(key = "creator_overview_tools_heading") {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = "CREATOR TOOLS",
          color = MasalaTextMuted,
          fontSize = 11.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 1.sp
        )
        Text(
          text = "${toolTiles.size} Options",
          color = MasalaGold,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    val rows = toolTiles.chunked(2)
    itemsIndexed(
      items = rows,
      key = { idx, _ -> "creator_overview_tools_row_$idx" }
    ) { _, rowTiles ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        rowTiles.forEach { tile ->
          AdminFeatureGridCard(
            tile = tile,
            modifier = Modifier.weight(1f)
          )
        }
        if (rowTiles.size == 1) {
          Spacer(modifier = Modifier.weight(1f))
        }
      }
    }
  }
}
