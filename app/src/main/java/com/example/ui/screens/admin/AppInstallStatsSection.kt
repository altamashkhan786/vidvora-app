package com.example.ui.screens.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppInstallStats
import com.example.data.model.FirestoreAppInstall
import com.example.data.model.isEstimatedUninstalled
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaError
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaSuccess
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaSurfaceVariant
import com.example.ui.theme.MasalaTextMuted
import com.example.ui.theme.MasalaTextPrimary
import com.example.ui.theme.MasalaTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AdminAppInstallsOverviewBanner(
  stats: AppInstallStats,
  onOpenDetails: () -> Unit
) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.35f)),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onOpenDetails)
      .testTag("admin_app_installs_overview_card")
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(34.dp)
              .clip(CircleShape)
              .background(MasalaGold.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Smartphone,
              contentDescription = null,
              tint = MasalaGold,
              modifier = Modifier.size(18.dp)
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text(
              text = "App Downloads & Installs",
              color = MasalaTextPrimary,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "${stats.retentionPercent}% active retention • ${stats.inactivityWindowDays}d window",
              color = MasalaTextMuted,
              fontSize = 11.sp
            )
          }
        }

        OutlinedButton(
          onClick = onOpenDetails,
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.6f)),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
          modifier = Modifier.testTag("open_install_stats_button")
        ) {
          Text("Details", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        MiniInstallMetricPill(
          label = "DOWNLOADED",
          value = "${stats.totalDownloads}",
          accent = MasalaGold,
          modifier = Modifier.weight(1f)
        )
        MiniInstallMetricPill(
          label = "INSTALLED",
          value = "${stats.currentlyInstalledCount}",
          accent = MasalaSuccess,
          modifier = Modifier.weight(1f)
        )
        MiniInstallMetricPill(
          label = "UNINSTALLED",
          value = "${stats.uninstalledCount}",
          accent = MasalaError,
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}

@Composable
private fun MiniInstallMetricPill(
  label: String,
  value: String,
  accent: Color,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(10.dp))
      .background(MasalaSurfaceVariant)
      .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
      .padding(vertical = 8.dp, horizontal = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = label,
      color = MasalaTextMuted,
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 0.4.sp,
      maxLines = 1
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = value,
      color = accent,
      fontSize = 16.sp,
      fontWeight = FontWeight.Black
    )
  }
}

fun LazyListScope.adminAppInstallsTabContent(
  stats: AppInstallStats,
  installs: List<FirestoreAppInstall>,
  inactivityWindowDays: Int,
  statusFilter: String,
  onSelectWindowDays: (Int) -> Unit,
  onSelectStatusFilter: (String) -> Unit,
  onToggleMarkedUninstalled: (String, Boolean) -> Unit,
  onDeleteInstallRecord: (String) -> Unit
) {
  item {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      InstallBigStatCard(
        title = "DOWNLOADED",
        subtitle = "Total Installs",
        value = "${stats.totalDownloads}",
        icon = Icons.Default.Download,
        accent = MasalaGold,
        modifier = Modifier
          .weight(1f)
          .testTag("stat_total_downloads")
      )
      InstallBigStatCard(
        title = "INSTALLED",
        subtitle = "Active Devices",
        value = "${stats.currentlyInstalledCount}",
        icon = Icons.Default.CheckCircle,
        accent = MasalaSuccess,
        modifier = Modifier
          .weight(1f)
          .testTag("stat_currently_installed")
      )
      InstallBigStatCard(
        title = "UNINSTALLED",
        subtitle = "Inactive >${inactivityWindowDays}d",
        value = "${stats.uninstalledCount}",
        icon = Icons.Default.Cancel,
        accent = MasalaError,
        modifier = Modifier
          .weight(1f)
          .testTag("stat_uninstalled")
      )
    }
  }

  item {
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MasalaSurface),
      border = BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Active Retention Rate",
            color = MasalaTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "${stats.retentionPercent}%",
            color = MasalaSuccess,
            fontSize = 15.sp,
            fontWeight = FontWeight.Black
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
          progress = { (stats.retentionPercent / 100f).coerceIn(0f, 1f) },
          color = MasalaSuccess,
          trackColor = MasalaSurfaceVariant,
          strokeCap = StrokeCap.Round,
          modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Surface(
            color = MasalaGold.copy(alpha = 0.14f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f)
          ) {
            Column(modifier = Modifier.padding(10.dp)) {
              Text("NEW TODAY (24H)", color = MasalaGold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
              Spacer(modifier = Modifier.height(2.dp))
              Text("${stats.downloadsToday}", color = MasalaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
          }
          Surface(
            color = MasalaSuccess.copy(alpha = 0.14f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f)
          ) {
            Column(modifier = Modifier.padding(10.dp)) {
              Text("ACTIVE TODAY (24H)", color = MasalaSuccess, fontSize = 9.sp, fontWeight = FontWeight.Bold)
              Spacer(modifier = Modifier.height(2.dp))
              Text("${stats.activeLast24hCount}", color = MasalaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
          text = "Uninstall Detection Window",
          color = MasalaTextPrimary,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          listOf(7, 14, 30).forEach { days ->
            val selected = inactivityWindowDays == days
            Surface(
              color = if (selected) MasalaGold.copy(alpha = 0.22f) else MasalaSurfaceVariant,
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, if (selected) MasalaGold else MasalaCardBorder),
              modifier = Modifier
                .weight(1f)
                .clickable { onSelectWindowDays(days) }
                .testTag("window_${days}_days")
            ) {
              Box(
                modifier = Modifier.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = "$days Days",
                  color = if (selected) MasalaGold else MasalaTextSecondary,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MasalaSurfaceVariant)
            .padding(10.dp),
          verticalAlignment = Alignment.Top
        ) {
          Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MasalaGold,
            modifier = Modifier
              .size(16.dp)
              .padding(top = 1.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Android does not broadcast an uninstall event. Devices inactive for over $inactivityWindowDays days (or manually marked) are counted as Uninstalled. If a user reinstalls, a fresh download is counted.",
            color = MasalaTextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp
          )
        }
      }
    }
  }

  item {
    val filteredCountText = when (statusFilter) {
      "INSTALLED" -> "Installed Devices (${stats.currentlyInstalledCount})"
      "UNINSTALLED" -> "Uninstalled / Inactive (${stats.uninstalledCount})"
      else -> "All Tracked Devices (${installs.size})"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
      Text(
        text = filteredCountText,
        color = MasalaTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        listOf(
          "ALL" to "All (${installs.size})",
          "INSTALLED" to "Installed (${stats.currentlyInstalledCount})",
          "UNINSTALLED" to "Uninstalled (${stats.uninstalledCount})"
        ).forEach { (key, label) ->
          val selected = statusFilter == key
          Surface(
            color = if (selected) MasalaGold.copy(alpha = 0.2f) else MasalaSurface,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, if (selected) MasalaGold else MasalaCardBorder),
            modifier = Modifier
              .weight(1f)
              .clickable { onSelectStatusFilter(key) }
              .testTag("install_filter_$key")
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
  }

  val nowMs = System.currentTimeMillis()
  val filteredDevices = installs.filter { item ->
    val isUninstalled = isEstimatedUninstalled(item, inactivityWindowDays, nowMs)
    when (statusFilter) {
      "INSTALLED" -> !isUninstalled
      "UNINSTALLED" -> isUninstalled
      else -> true
    }
  }

  if (filteredDevices.isEmpty()) {
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
            imageVector = Icons.Default.Smartphone,
            contentDescription = null,
            tint = MasalaTextMuted,
            modifier = Modifier.size(38.dp)
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = "No Devices in This View",
            color = MasalaTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "App installs are recorded automatically whenever users open VidVora.",
            color = MasalaTextSecondary,
            fontSize = 12.sp
          )
        }
      }
    }
  } else {
    items(filteredDevices, key = { it.installId }) { install ->
      val isUninstalled = isEstimatedUninstalled(install, inactivityWindowDays, nowMs)
      AppInstallDeviceCard(
        install = install,
        isUninstalled = isUninstalled,
        nowMs = nowMs,
        onToggleMarkUninstalled = {
          onToggleMarkedUninstalled(install.installId, !install.isMarkedUninstalled)
        },
        onDelete = { onDeleteInstallRecord(install.installId) }
      )
    }
  }
}

@Composable
private fun InstallBigStatCard(
  title: String,
  subtitle: String,
  value: String,
  icon: ImageVector,
  accent: Color,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = accent,
        modifier = Modifier.size(20.dp)
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = value,
        color = accent,
        fontSize = 22.sp,
        fontWeight = FontWeight.Black
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = title,
        color = MasalaTextPrimary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1
      )
      Text(
        text = subtitle,
        color = MasalaTextMuted,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@Composable
private fun AppInstallDeviceCard(
  install: FirestoreAppInstall,
  isUninstalled: Boolean,
  nowMs: Long,
  onToggleMarkUninstalled: () -> Unit,
  onDelete: () -> Unit
) {
  val statusColor = if (isUninstalled) MasalaError else MasalaSuccess
  val statusLabel = when {
    install.isMarkedUninstalled -> "UNINSTALLED (MANUAL)"
    isUninstalled -> "UNINSTALLED (EST.)"
    else -> "INSTALLED"
  }

  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.32f)),
    modifier = Modifier.fillMaxWidth()
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
              .size(34.dp)
              .clip(CircleShape)
              .background(statusColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Smartphone,
              contentDescription = null,
              tint = statusColor,
              modifier = Modifier.size(18.dp)
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = install.deviceLabel,
              color = MasalaTextPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = "${install.androidVersion} • v${install.appVersion} • ${install.sessionCount} opens",
              color = MasalaTextSecondary,
              fontSize = 11.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
          color = statusColor.copy(alpha = 0.16f),
          shape = RoundedCornerShape(6.dp)
        ) {
          Text(
            text = statusLabel,
            color = statusColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Downloaded: ${formatShortDate(install.firstInstalledAt.toDate())}",
            color = MasalaTextMuted,
            fontSize = 11.sp
          )
          Text(
            text = "Last Seen: ${formatRelativeTime(install.lastSeenAt.toDate().time, nowMs)}",
            color = MasalaTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          OutlinedButton(
            onClick = onToggleMarkUninstalled,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, if (install.isMarkedUninstalled) MasalaSuccess else MasalaCardBorder),
            colors = ButtonDefaults.outlinedButtonColors(
              contentColor = if (install.isMarkedUninstalled) MasalaSuccess else MasalaTextSecondary
            )
          ) {
            Icon(
              imageVector = if (install.isMarkedUninstalled) Icons.Default.Restore else Icons.Default.Cancel,
              contentDescription = null,
              modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = if (install.isMarkedUninstalled) "Restore" else "Mark Gone",
              fontSize = 11.sp
            )
          }

          IconButton(onClick = onDelete) {
            Icon(
              imageVector = Icons.Default.DeleteOutline,
              contentDescription = "Remove Install Record",
              tint = MasalaError
            )
          }
        }
      }
    }
  }
}

private fun formatShortDate(date: Date): String {
  return try {
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
  } catch (_: Exception) {
    "Unknown"
  }
}

private fun formatRelativeTime(timestampMs: Long, nowMs: Long): String {
  val diff = (nowMs - timestampMs).coerceAtLeast(0L)
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
