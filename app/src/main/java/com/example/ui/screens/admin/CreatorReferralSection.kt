package com.example.ui.screens.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CreatorReferralSummary
import com.example.data.model.ReferralStatus
import com.example.data.model.ReferredCreatorItem
import com.example.data.model.formatInr
import com.example.ui.theme.MasalaBlack
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaSuccess
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaSurfaceVariant
import com.example.ui.theme.MasalaTextMuted
import com.example.ui.theme.MasalaTextPrimary
import com.example.ui.theme.MasalaTextSecondary
import com.example.ui.theme.MasalaWarning
import java.text.SimpleDateFormat
import java.util.Locale

enum class ReferralListFilter(val label: String) {
  ALL("All"),
  SUCCESSFUL("Successful"),
  PENDING("Pending")
}

private const val VIDVORA_APK_DOWNLOAD_URL = "https://vidvora-share-bot.onrender.com/download"

fun LazyListScope.creatorReferralTabContent(
  summary: CreatorReferralSummary,
  creatorName: String,
  referredByName: String,
  selectedFilter: ReferralListFilter,
  onSelectFilter: (ReferralListFilter) -> Unit,
  onOpenEarningsClick: (() -> Unit)? = null
) {
  val filteredReferrals = when (selectedFilter) {
    ReferralListFilter.ALL -> summary.referredCreators
    ReferralListFilter.SUCCESSFUL -> summary.referredCreators.filter { it.status == ReferralStatus.SUCCESSFUL }
    ReferralListFilter.PENDING -> summary.referredCreators.filter { it.status == ReferralStatus.PENDING }
  }

  // 1. Hero Referral Code & Invite Card
  item(key = "creator_referral_hero_card") {
    CreatorReferralHeroCard(
      summary = summary,
      creatorName = creatorName,
      referredByName = referredByName
    )
  }

  // 2. Referral Metrics + Separate Bonus / Unified Withdrawal Banner
  item(key = "creator_referral_stats_card") {
    CreatorReferralStatsCard(
      summary = summary,
      onOpenEarningsClick = onOpenEarningsClick
    )
  }

  // 3. Filter Chips + Referred Creators Section Header
  item(key = "creator_referral_list_header") {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "My Referred Creators (${summary.totalReferrals})",
          color = MasalaTextPrimary,
          fontSize = 15.sp,
          fontWeight = FontWeight.ExtraBold
        )
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MasalaGold.copy(alpha = 0.14f),
          border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.4f))
        ) {
          Text(
            text = "7% ADMIN BONUS",
            color = MasalaGold,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        ReferralListFilter.entries.forEach { filter ->
          val count = when (filter) {
            ReferralListFilter.ALL -> summary.totalReferrals
            ReferralListFilter.SUCCESSFUL -> summary.successfulCount
            ReferralListFilter.PENDING -> summary.pendingCount
          }
          FilterChip(
            selected = selectedFilter == filter,
            onClick = { onSelectFilter(filter) },
            label = {
              Text(
                text = "${filter.label} ($count)",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
              .testTag("referral_filter_${filter.name.lowercase()}")
          )
        }
      }
    }
  }

  // 4. Referred Creators List or Empty State
  if (summary.referredCreators.isEmpty()) {
    item(key = "creator_referral_empty_state") {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MasalaSurface),
        border = BorderStroke(1.dp, MasalaCardBorder),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("creator_referral_empty_card")
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Box(
            modifier = Modifier
              .size(52.dp)
              .clip(CircleShape)
              .background(MasalaGold.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Group,
              contentDescription = null,
              tint = MasalaGold,
              modifier = Modifier.size(28.dp)
            )
          }
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = "Abhi koi Creator refer nahi hua hai",
            color = MasalaTextPrimary,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Apna Referral Code upar se Copy ya Share karein. Jab koi naya Creator aapke code se Sign Up karega, uska naam aur Pending / Successful status yahan dikhega.",
            color = MasalaTextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center
          )
        }
      }
    }
  } else if (filteredReferrals.isEmpty()) {
    item(key = "creator_referral_filter_empty") {
      Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MasalaSurface),
        border = BorderStroke(1.dp, MasalaCardBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = "Is filter me abhi koi referred creator nahi hai.",
          color = MasalaTextSecondary,
          fontSize = 12.5.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
        )
      }
    }
  } else {
    items(
      items = filteredReferrals,
      key = { "referred_creator_${it.memberId}" }
    ) { item ->
      ReferredCreatorRowCard(item = item)
    }
  }

  // 5. Compact "How 7% Admin Referral Works" Rules Card
  item(key = "creator_referral_rules_card") {
    ReferralHowItWorksCard()
  }
}

@Composable
private fun CreatorReferralHeroCard(
  summary: CreatorReferralSummary,
  creatorName: String,
  referredByName: String
) {
  val context = LocalContext.current
  val code = summary.myReferralCode.ifBlank { "VV-CREATOR" }

  fun copyCode() {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("VidVora Referral Code", code))
    Toast.makeText(context, "Referral Code $code copied!", Toast.LENGTH_SHORT).show()
  }

  fun shareInvite() {
    val shareText = buildString {
      append("VidVora par Creator bano aur videos upload karke earn karo!\n\n")
      append("Sign Up karte waqt mera Referral Code daalein: $code\n")
      if (creatorName.isNotBlank()) {
        append("Invited by: $creatorName\n")
      }
      append("\nVidVora Official App Download:\n$VIDVORA_APK_DOWNLOAD_URL")
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, "Join VidVora as a Creator")
      putExtra(Intent.EXTRA_TEXT, shareText)
    }
    try {
      context.startActivity(Intent.createChooser(intent, "Share Referral Code"))
    } catch (_: Exception) {
      copyCode()
    }
  }

  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.55f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("creator_referral_hero_card")
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Box(
          modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MasalaGold.copy(alpha = 0.18f))
            .border(1.dp, MasalaGold.copy(alpha = 0.5f), CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.CardGiftcard,
            contentDescription = "Refer and Earn",
            tint = MasalaGold,
            modifier = Modifier.size(22.dp)
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Refer Creators & Earn 7%",
            color = MasalaTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold
          )
          Text(
            text = "Har referral ki earning par 7% extra bonus seedha Admin ki taraf se!",
            color = MasalaTextSecondary,
            fontSize = 11.5.sp,
            lineHeight = 15.sp
          )
        }
      }

      if (referredByName.isNotBlank()) {
        Spacer(modifier = Modifier.height(10.dp))
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MasalaSuccess.copy(alpha = 0.12f),
          border = BorderStroke(1.dp, MasalaSuccess.copy(alpha = 0.35f))
        ) {
          Text(
            text = "Joined via referral from $referredByName",
            color = MasalaSuccess,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Referral Code Display Box
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MasalaBlack.copy(alpha = 0.55f))
          .border(1.dp, MasalaGold.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
          .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "YOUR REFERRAL CODE",
          color = MasalaTextMuted,
          fontSize = 9.5.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = code,
          color = MasalaGold,
          fontSize = 24.sp,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Black,
          letterSpacing = 2.sp,
          modifier = Modifier.testTag("creator_referral_code_text")
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedButton(
          onClick = { copyCode() },
          shape = RoundedCornerShape(10.dp),
          border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.6f)),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("creator_referral_copy_button")
        ) {
          Icon(Icons.Default.ContentCopy, contentDescription = "Copy referral code", modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Copy Code", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
        }

        Button(
          onClick = { shareInvite() },
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(containerColor = MasalaGold, contentColor = Color.Black),
          modifier = Modifier
            .weight(1.1f)
            .heightIn(min = 48.dp)
            .testTag("creator_referral_share_button")
        ) {
          Icon(Icons.Default.Share, contentDescription = "Share referral invite", modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Share Invite", fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(MasalaSuccess.copy(alpha = 0.1f))
          .border(1.dp, MasalaSuccess.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
          .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.Verified,
          contentDescription = null,
          tint = MasalaSuccess,
          modifier = Modifier.size(16.dp)
        )
        Text(
          text = "7% bonus Admin ki taraf se add hota hai — refer kiye gaye Creator ke views ya earning se kuch nahi kat-ta.",
          color = MasalaTextPrimary,
          fontSize = 11.sp,
          lineHeight = 15.sp,
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}

@Composable
private fun CreatorReferralStatsCard(
  summary: CreatorReferralSummary,
  onOpenEarningsClick: (() -> Unit)?
) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, MasalaCardBorder),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("creator_referral_stats_card")
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        ReferralMetricTile(
          label = "TOTAL REFERRED",
          value = "${summary.totalReferrals}",
          accent = MasalaTextPrimary,
          modifier = Modifier.weight(1f)
        )
        ReferralMetricTile(
          label = "SUCCESS / PENDING",
          value = "${summary.successfulCount} / ${summary.pendingCount}",
          accent = MasalaSuccess,
          modifier = Modifier.weight(1.1f)
        )
        ReferralMetricTile(
          label = "REFERRAL EARNED",
          value = formatInr(summary.totalReferralBonusInr),
          accent = MasalaGold,
          modifier = Modifier.weight(1.2f)
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(MasalaSurfaceVariant)
          .border(1.dp, MasalaCardBorder, RoundedCornerShape(10.dp))
          .padding(10.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(
            imageVector = Icons.Default.AccountBalanceWallet,
            contentDescription = null,
            tint = MasalaGold,
            modifier = Modifier.size(16.dp)
          )
          Text(
            text = "Alag Hisaab, Ek Wallet Withdrawal",
            color = MasalaGold,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Referral se mili kamai (${formatInr(summary.totalReferralBonusInr)}) yahan aur Earnings breakdown me alag dikhti hai, aur withdraw ke time aapke Total Earning balance me automatically jud kar ek saath UPI withdraw hoti hai.",
          color = MasalaTextSecondary,
          fontSize = 11.sp,
          lineHeight = 15.sp
        )

        if (onOpenEarningsClick != null) {
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedButton(
            onClick = onOpenEarningsClick,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MasalaRedPrimary.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextPrimary),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("referral_open_earnings_button")
          ) {
            Text(
              text = "Open Earnings & Withdraw Wallet",
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ReferralMetricTile(
  label: String,
  value: String,
  accent: Color,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(10.dp))
      .background(MasalaSurfaceVariant)
      .border(1.dp, MasalaCardBorder, RoundedCornerShape(10.dp))
      .padding(horizontal = 8.dp, vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = label,
      color = MasalaTextMuted,
      fontSize = 8.5.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = value,
      color = accent,
      fontSize = 14.5.sp,
      fontWeight = FontWeight.ExtraBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

@Composable
private fun ReferredCreatorRowCard(item: ReferredCreatorItem) {
  val isSuccessful = item.status == ReferralStatus.SUCCESSFUL
  val statusColor = if (isSuccessful) MasalaSuccess else MasalaWarning
  val statusLabel = if (isSuccessful) "SUCCESSFUL" else "PENDING"
  val joinedDate = remember(item.joinedAt) {
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(item.joinedAt.toDate())
  }
  val initial = item.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "C"

  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("referred_creator_card_${item.memberId}")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Box(
        modifier = Modifier
          .size(42.dp)
          .clip(CircleShape)
          .background(statusColor.copy(alpha = 0.16f))
          .border(1.dp, statusColor.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = initial,
          color = statusColor,
          fontSize = 16.sp,
          fontWeight = FontWeight.ExtraBold
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.name.ifBlank { "Creator" },
          color = MasalaTextPrimary,
          fontSize = 14.5.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = "Joined $joinedDate",
          color = MasalaTextMuted,
          fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
          Icon(
            imageVector = if (isSuccessful) Icons.Default.CheckCircle else Icons.Default.HourglassTop,
            contentDescription = statusLabel,
            tint = statusColor,
            modifier = Modifier.size(13.dp)
          )
          Text(
            text = if (isSuccessful) {
              "Active • Views/Earning updated"
            } else {
              "Pending • Waiting for 1st view update"
            },
            color = statusColor,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      Column(horizontalAlignment = Alignment.End) {
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = statusColor.copy(alpha = 0.16f),
          border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
        ) {
          Text(
            text = statusLabel,
            color = statusColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
          )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
          text = "+${formatInr(item.bonusEarnedInr)}",
          color = if (item.bonusEarnedInr > 0.0) MasalaGold else MasalaTextMuted,
          fontSize = 14.sp,
          fontWeight = FontWeight.ExtraBold
        )
        Text(
          text = "7% Bonus",
          color = MasalaTextMuted,
          fontSize = 9.5.sp
        )
      }
    }
  }
}

@Composable
private fun ReferralHowItWorksCard() {
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurfaceVariant.copy(alpha = 0.7f)),
    border = BorderStroke(1.dp, MasalaCardBorder),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Text(
        text = "Referral Program Kaise Kaam Karta Hai?",
        color = MasalaGold,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold
      )
      Text(
        text = "1. Naya Creator jab Sign Up me aapka Referral Code dalega to uska naam yahan PENDING status me dikhega.",
        color = MasalaTextSecondary,
        fontSize = 11.5.sp,
        lineHeight = 16.sp
      )
      Text(
        text = "2. Jaise hi Admin us Creator ke views ya earning update karega, status automatically SUCCESSFUL ho jayega aur 7% bonus Admin ki taraf se aapke account me add ho jayega.",
        color = MasalaTextSecondary,
        fontSize = 11.5.sp,
        lineHeight = 16.sp
      )
      Text(
        text = "3. Refer kiye gaye Creator ko uski puri 100% views aur earning milti hai — unke hisse se kuch nahi kat-ta.",
        color = MasalaTextSecondary,
        fontSize = 11.5.sp,
        lineHeight = 16.sp
      )
    }
  }
}
