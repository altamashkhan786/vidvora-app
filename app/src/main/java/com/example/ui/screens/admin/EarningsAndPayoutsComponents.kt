package com.example.ui.screens.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.EarningSummary
import com.example.data.model.FirestoreMember
import com.example.data.model.FirestoreWithdrawalRequest
import com.example.data.model.MemberStats
import com.example.data.model.WithdrawalStatus
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
import java.util.Locale

@Composable
fun CreatorEarningsSection(
  summary: EarningSummary,
  requests: List<FirestoreWithdrawalRequest>,
  onRequestWithdrawClick: () -> Unit,
  showWithdrawButton: Boolean = true,
  creditedViews: Long? = null
) {
  val canWithdraw = summary.availableBalance >= FirestoreVideoRepository.MIN_WITHDRAWAL_AMOUNT
  val remainingForMin = (FirestoreVideoRepository.MIN_WITHDRAWAL_AMOUNT - summary.availableBalance).coerceAtLeast(0.0)

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.45f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("creator_earnings_card")
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .background(MasalaRedPrimary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.AccountBalanceWallet,
              contentDescription = null,
              tint = MasalaGold,
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text(
              text = "My Earnings & Payouts",
              color = MasalaTextPrimary,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = if (creditedViews != null) {
                "Credited Views: $creditedViews • Min Withdrawal: ₹500 via UPI"
              } else {
                "Minimum Withdrawal: ₹500 via UPI"
              },
              color = MasalaTextMuted,
              fontSize = 11.sp
            )
          }
        }

        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
              if (canWithdraw) MasalaSuccess.copy(alpha = 0.18f)
              else MasalaGold.copy(alpha = 0.14f)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
          Text(
            text = if (canWithdraw) "ELIGIBLE" else "MIN ₹500",
            color = if (canWithdraw) MasalaSuccess else MasalaGold,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Available Balance Hero Box
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MasalaSurfaceVariant)
          .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
          .padding(14.dp)
      ) {
        Text(
          text = "AVAILABLE TO WITHDRAW",
          color = MasalaTextMuted,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = formatInr(summary.availableBalance),
          color = MasalaGold,
          fontSize = 28.sp,
          fontWeight = FontWeight.Black,
          modifier = Modifier.testTag("creator_available_balance")
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          MiniEarningMetric(
            label = "Total Earning",
            value = formatInr(summary.totalEarning),
            valueColor = MasalaTextPrimary,
            modifier = Modifier.weight(1f)
          )
          MiniEarningMetric(
            label = "In Processing",
            value = formatInr(summary.pendingOrProcessingAmount),
            valueColor = MasalaGold,
            modifier = Modifier.weight(1f)
          )
          MiniEarningMetric(
            label = "Paid",
            value = formatInr(summary.paidAmount),
            valueColor = MasalaSuccess,
            modifier = Modifier.weight(1f)
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Separate display: Own Views Earning vs Referral Bonus (7% Admin) — unified in Total Earning for withdrawal
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .testTag("creator_earning_source_breakdown"),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          MiniEarningMetric(
            label = "Own Views Earning",
            value = formatInr(summary.ownViewsEarning),
            valueColor = MasalaTextPrimary,
            modifier = Modifier.weight(1f)
          )
          MiniEarningMetric(
            label = "Referral Bonus (7%)",
            value = formatInr(summary.referralBonusEarning),
            valueColor = MasalaGold,
            modifier = Modifier.weight(1f)
          )
        }
        Text(
          text = "Own Views + Referral Bonus dono milkar ek hi Total Earning wallet me withdraw hote hain",
          color = MasalaTextMuted,
          fontSize = 10.sp,
          modifier = Modifier.padding(top = 5.dp)
        )
      }

      if (showWithdrawButton) {
        Spacer(modifier = Modifier.height(12.dp))

        Button(
          onClick = onRequestWithdrawClick,
          enabled = canWithdraw,
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = MasalaRedPrimary,
            contentColor = Color.White,
            disabledContainerColor = MasalaSurfaceVariant,
            disabledContentColor = MasalaTextMuted
          ),
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("creator_withdraw_button")
        ) {
          Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = if (canWithdraw) {
              "Withdraw via UPI (Min ₹500)"
            } else {
              "Min ₹500 Required (₹${remainingForMin.toLong()} more needed)"
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }

      if (requests.isNotEmpty()) {
        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = MasalaCardBorder)
        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = "Withdrawal History (${requests.size})",
          color = MasalaTextPrimary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          requests.forEach { req ->
            CreatorWithdrawalHistoryRow(request = req)
          }
        }
      }
    }
  }
}

@Composable
private fun MiniEarningMetric(
  label: String,
  value: String,
  valueColor: Color,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(MasalaBlack.copy(alpha = 0.45f))
      .padding(horizontal = 8.dp, vertical = 6.dp)
  ) {
    Text(
      text = label,
      color = MasalaTextMuted,
      fontSize = 9.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    Text(
      text = value,
      color = valueColor,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

@Composable
private fun CreatorWithdrawalHistoryRow(request: FirestoreWithdrawalRequest) {
  val dateLabel = remember(request.requestedAt) {
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(request.requestedAt.toDate())
  }
  val (statusLabel, statusColor) = statusPresentation(request.status)

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(MasalaSurfaceVariant)
      .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
      .padding(10.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = formatInr(request.amount),
        color = MasalaTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.ExtraBold
      )
      StatusBadgePill(label = statusLabel, color = statusColor)
    }

    Spacer(modifier = Modifier.height(4.dp))

    Text(
      text = "UPI: ${request.upiId}",
      color = MasalaGold,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace
    )
    Text(
      text = dateLabel,
      color = MasalaTextMuted,
      fontSize = 10.sp
    )

    if (request.adminNote.isNotBlank()) {
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Admin Note / Ref: ${request.adminNote}",
        color = MasalaTextSecondary,
        fontSize = 11.sp
      )
    }
  }
}

@Composable
fun CreatorWithdrawalDialog(
  availableBalance: Double,
  savedUpiId: String = "",
  onDismiss: () -> Unit,
  onSubmit: (amount: Double, upiId: String, onDone: (Boolean, String?) -> Unit) -> Unit
) {
  var upiId by remember(savedUpiId) { mutableStateOf(savedUpiId.trim()) }
  val maxFloor = availableBalance.toLong().coerceAtLeast(FirestoreVideoRepository.MIN_WITHDRAWAL_AMOUNT.toLong())
  var amountText by remember(availableBalance) {
    mutableStateOf(maxFloor.toString())
  }
  var isSubmitting by remember { mutableStateOf(false) }
  var errorMsg by remember { mutableStateOf<String?>(null) }

  val quickAmounts = remember(availableBalance) {
    val maxLong = availableBalance.toLong()
    val presets = listOf(500L, 1000L, 2000L, 5000L).filter { it <= maxLong }
    (presets + listOf(maxLong)).distinct().filter { it >= 500L }
  }
  val currentAmountValue = amountText.trim().toLongOrNull()

  Dialog(onDismissRequest = { if (!isSubmitting) onDismiss() }) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MasalaSurface,
      border = BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("creator_withdrawal_dialog")
    ) {
      Column(
        modifier = Modifier
          .padding(20.dp)
          .verticalScroll(rememberScrollState())
      ) {
        Text(
          text = "Easy UPI Withdrawal",
          color = MasalaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Available: ${formatInr(availableBalance)} • Min ₹500",
          color = MasalaGold,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(top = 2.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
          text = "1. Amount chunein",
          color = MasalaTextSecondary,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (quickAmounts.isNotEmpty()) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            quickAmounts.forEach { preset ->
              val isAllOption = preset == availableBalance.toLong()
              val isSelected = currentAmountValue == preset
              FilterChip(
                selected = isSelected,
                onClick = {
                  if (!isSubmitting) {
                    amountText = preset.toString()
                    errorMsg = null
                  }
                },
                label = {
                  Text(
                    text = if (isAllOption) "All ₹$preset" else "₹$preset",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                  )
                },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MasalaGold.copy(alpha = 0.22f),
                  selectedLabelColor = MasalaGold,
                  containerColor = MasalaSurfaceVariant,
                  labelColor = MasalaTextPrimary
                ),
                border = FilterChipDefaults.filterChipBorder(
                  enabled = true,
                  selected = isSelected,
                  borderColor = MasalaCardBorder,
                  selectedBorderColor = MasalaGold
                ),
                modifier = Modifier.testTag("withdraw_quick_amount_$preset")
              )
            }
          }
          Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedTextField(
          value = amountText,
          onValueChange = { amountText = it; errorMsg = null },
          label = { Text("Amount (₹) *") },
          placeholder = { Text("Minimum ₹500") },
          singleLine = true,
          enabled = !isSubmitting,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          trailingIcon = {
            Text(
              text = "ALL",
              color = MasalaGold,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier
                .padding(end = 10.dp)
                .clickable(enabled = !isSubmitting) {
                  amountText = availableBalance.toLong().toString()
                  errorMsg = null
                }
            )
          },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("withdraw_amount_input"),
          colors = payoutTextFieldColors()
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "2. Apni UPI ID daalein",
            color = MasalaTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
          if (savedUpiId.isNotBlank() && upiId.trim() == savedUpiId.trim()) {
            Text(
              text = "Last UPI auto-filled ✓",
              color = MasalaSuccess,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
          value = upiId,
          onValueChange = { upiId = it; errorMsg = null },
          label = { Text("UPI ID *") },
          placeholder = { Text("Example: 9876543210@ybl") },
          singleLine = true,
          enabled = !isSubmitting,
          trailingIcon = {
            if (upiId.isNotEmpty()) {
              IconButton(
                onClick = { upiId = ""; errorMsg = null },
                enabled = !isSubmitting
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Clear UPI ID",
                  tint = MasalaTextMuted,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("withdraw_upi_input"),
          colors = payoutTextFieldColors()
        )

        if (errorMsg != null) {
          Text(
            text = errorMsg ?: "",
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
          val parsedPreview = amountText.trim().toDoubleOrNull()
          Button(
            onClick = {
              val cleanUpi = upiId.trim()
              val parsedAmount = amountText.trim().toDoubleOrNull()
              if (parsedAmount == null || parsedAmount < FirestoreVideoRepository.MIN_WITHDRAWAL_AMOUNT) {
                errorMsg = "Minimum withdrawal ₹500 hai"
                return@Button
              }
              if (parsedAmount > availableBalance + 0.001) {
                errorMsg = "Available balance (${formatInr(availableBalance)}) se zyada nahi nikal sakte"
                return@Button
              }
              if (!FirestoreVideoRepository.isValidUpiId(cleanUpi)) {
                errorMsg = "Sahi UPI ID daalein (jaise name@ybl)"
                return@Button
              }
              isSubmitting = true
              onSubmit(parsedAmount, cleanUpi) { ok, err ->
                isSubmitting = false
                if (ok) {
                  onDismiss()
                } else {
                  errorMsg = err ?: "Request failed"
                }
              }
            },
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(containerColor = MasalaRedPrimary, contentColor = Color.White),
            modifier = Modifier
              .weight(1f)
              .testTag("withdraw_submit_button")
          ) {
            Text(
              text = when {
                isSubmitting -> "Sending..."
                parsedPreview != null && parsedPreview >= 500.0 -> "Withdraw ${formatInr(parsedPreview)}"
                else -> "Withdraw Now"
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
fun ManageCreatorEarningDialog(
  memberStats: MemberStats,
  onDismiss: () -> Unit,
  onSaveByViews: (views: Long, ratePer1000: Double, isAddition: Boolean, onDone: (Boolean, String?) -> Unit) -> Unit,
  onSaveManual: (amount: Double, isAddition: Boolean, onDone: (Boolean, String?) -> Unit) -> Unit
) {
  var useViewsMode by remember { mutableStateOf(true) }
  var isAddition by remember { mutableStateOf(true) }
  val defaultRateText = remember(memberStats.member.viewRatePer1000, memberStats.member.creditedViews) {
    val savedRate = memberStats.member.viewRatePer1000
    val effectiveRate = if (savedRate <= 0.0 || (savedRate == 90.0 && memberStats.member.creditedViews == 0L)) {
      FirestoreVideoRepository.DEFAULT_VIEW_RATE_PER_1000
    } else {
      savedRate
    }
    if (effectiveRate % 1.0 == 0.0) effectiveRate.toLong().toString() else effectiveRate.toString()
  }
  var viewsText by remember { mutableStateOf("") }
  var ratePer1000Text by remember { mutableStateOf(defaultRateText) }
  var manualAmountText by remember { mutableStateOf("") }
  var isSaving by remember { mutableStateOf(false) }
  var errorMsg by remember { mutableStateOf<String?>(null) }

  val parsedViews = viewsText.trim().toLongOrNull() ?: 0L
  val parsedRate = ratePer1000Text.trim().toDoubleOrNull() ?: FirestoreVideoRepository.DEFAULT_VIEW_RATE_PER_1000
  val calculatedInr = FirestoreVideoRepository.calculateViewsEarning(parsedViews, parsedRate)

  Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MasalaSurface,
      border = BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("manage_creator_earning_dialog")
    ) {
      Column(
        modifier = Modifier
          .padding(20.dp)
          .verticalScroll(rememberScrollState())
      ) {
        Text(
          text = "Manage Views & Earning",
          color = MasalaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "${memberStats.member.name} • ${memberStats.member.emailOrPhone}",
          color = MasalaTextMuted,
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 2.dp)
        )

        if (memberStats.member.referredByName.isNotBlank()) {
          Spacer(modifier = Modifier.height(6.dp))
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MasalaGold.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = "Referred by ${memberStats.member.referredByName} • +7% Admin Bonus auto-add hoga (Creator se kuch nahi katega)",
              color = MasalaGold,
              fontSize = 10.5.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Admin Overview Pill: 24h Views, Real Views (Admin-only), Creator Views vs Total Earning
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaCardBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("24H VIEWS", color = MasalaWarning, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text("${memberStats.viewsLast24Hours}", color = MasalaWarning, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
          }
          Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("REAL VIEWS", color = MasalaRedAccent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text("${memberStats.totalViews}", color = MasalaRedAccent, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
          }
          Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CREATOR VIEWS", color = MasalaSuccess, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text("${memberStats.member.creditedViews}", color = MasalaSuccess, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
          }
          Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("EARNING", color = MasalaGold, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(formatInr(memberStats.earningSummary.totalEarning), color = MasalaGold, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Primary Mode Selector: Add Views (Auto ₹) vs Manual ₹
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          FilterChip(
            selected = useViewsMode,
            onClick = {
              useViewsMode = true
              errorMsg = null
            },
            label = { Text("Add Views (Auto ₹)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MasalaGold,
              selectedLabelColor = Color.Black,
              containerColor = MasalaSurfaceVariant,
              labelColor = MasalaTextSecondary
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("earning_mode_views_chip")
          )
          FilterChip(
            selected = !useViewsMode,
            onClick = {
              useViewsMode = false
              errorMsg = null
            },
            label = { Text("Manual ₹", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MasalaGold,
              selectedLabelColor = Color.Black,
              containerColor = MasalaSurfaceVariant,
              labelColor = MasalaTextSecondary
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("earning_mode_manual_chip")
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Action Toggle: Add vs Set Total
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          FilterChip(
            selected = isAddition,
            onClick = {
              isAddition = true
              viewsText = ""
              manualAmountText = ""
              errorMsg = null
            },
            label = {
              Text(
                if (useViewsMode) "Add (+Views)" else "Add Earning (+₹)",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
              )
            },
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
              viewsText = memberStats.member.creditedViews.toString()
              manualAmountText = memberStats.earningSummary.totalEarning.toLong().toString()
              errorMsg = null
            },
            label = {
              Text(
                if (useViewsMode) "Set Total Views" else "Set Total (₹)",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
              )
            },
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

        if (useViewsMode) {
          OutlinedTextField(
            value = viewsText,
            onValueChange = { input ->
              viewsText = input.filter { it.isDigit() }
              errorMsg = null
            },
            label = { Text(if (isAddition) "Views to Add to Creator Panel" else "New Total Creator Views") },
            placeholder = { Text("e.g. 1000") },
            singleLine = true,
            enabled = !isSaving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null, tint = MasalaGold) },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("admin_views_input"),
            colors = payoutTextFieldColors()
          )

          Spacer(modifier = Modifier.height(8.dp))

          // Quick view preset buttons
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            listOf(500L, 1000L, 5000L).forEach { preset ->
              OutlinedButton(
                onClick = {
                  val currentInput = viewsText.toLongOrNull() ?: 0L
                  viewsText = if (isAddition) (currentInput + preset).toString() else preset.toString()
                  errorMsg = null
                },
                enabled = !isSaving,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f)
              ) {
                Text(
                  text = if (isAddition) "+$preset" else "$preset",
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
            modifier = Modifier
              .fillMaxWidth()
              .testTag("admin_rate_per_1k_input"),
            colors = payoutTextFieldColors()
          )

          Spacer(modifier = Modifier.height(10.dp))

          // Live Auto-Calculated Payout Preview Box
          val previewViewsResult = if (isAddition) {
            memberStats.member.creditedViews + parsedViews.coerceAtLeast(0L)
          } else {
            parsedViews.coerceAtLeast(0L)
          }
          val previewEarningResult = if (isAddition) {
            memberStats.earningSummary.totalEarning + calculatedInr
          } else {
            calculatedInr + memberStats.member.referralBonusEarning
          }
          val previewPositiveDelta = if (isAddition) {
            calculatedInr.coerceAtLeast(0.0)
          } else {
            (previewEarningResult - memberStats.earningSummary.totalEarning).coerceAtLeast(0.0)
          }
          val previewRefBonus = if (memberStats.member.referredByMemberId.isNotBlank() && previewPositiveDelta > 0.0) {
            FirestoreVideoRepository.calculateReferralBonus(previewPositiveDelta)
          } else 0.0

          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(MasalaGold.copy(alpha = 0.1f))
              .border(1.dp, MasalaGold.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
              .padding(10.dp)
              .testTag("admin_auto_calc_preview")
          ) {
            Text(
              text = if (isAddition) {
                "Auto Payment: +${formatInr(calculatedInr)}"
              } else {
                "Auto Total Earning: ${formatInr(calculatedInr)}"
              },
              color = MasalaGold,
              fontSize = 13.sp,
              fontWeight = FontWeight.ExtraBold
            )
            Text(
              text = "After Save → Creator Views: $previewViewsResult • Total Earning: ${formatInr(previewEarningResult)}",
              color = MasalaTextSecondary,
              fontSize = 11.sp,
              modifier = Modifier.padding(top = 2.dp)
            )
            if (previewRefBonus > 0.0) {
              Text(
                text = "+${formatInr(previewRefBonus)} (7% Admin Bonus) → ${memberStats.member.referredByName.ifBlank { "Referrer" }}",
                color = MasalaSuccess,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp)
              )
            }
          }
        } else {
          OutlinedTextField(
            value = manualAmountText,
            onValueChange = { manualAmountText = it; errorMsg = null },
            label = { Text(if (isAddition) "Amount to Add (₹)" else "New Total Earning (₹)") },
            placeholder = { Text("e.g. 500") },
            singleLine = true,
            enabled = !isSaving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            leadingIcon = { Icon(Icons.Default.CurrencyRupee, contentDescription = null, tint = MasalaGold) },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("admin_earning_amount_input"),
            colors = payoutTextFieldColors()
          )
        }

        if (errorMsg != null) {
          Text(
            text = errorMsg ?: "",
            color = MasalaError,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp)
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
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
          ) {
            Text("Cancel")
          }
          Button(
            onClick = {
              if (useViewsMode) {
                val viewsVal = viewsText.trim().toLongOrNull()
                val rateVal = ratePer1000Text.trim().toDoubleOrNull()
                if (viewsVal == null || viewsVal < 0L || (isAddition && viewsVal <= 0L)) {
                  errorMsg = "Please enter valid views count"
                  return@Button
                }
                if (rateVal == null || rateVal < 0.0) {
                  errorMsg = "Please enter valid rate per 1,000 views"
                  return@Button
                }
                isSaving = true
                onSaveByViews(viewsVal, rateVal, isAddition) { ok, err ->
                  isSaving = false
                  if (ok) {
                    onDismiss()
                  } else {
                    errorMsg = err ?: "Failed to save views & earning"
                  }
                }
              } else {
                val parsed = manualAmountText.trim().toDoubleOrNull()
                if (parsed == null || parsed < 0.0 || (isAddition && parsed <= 0.0)) {
                  errorMsg = "Please enter a valid positive amount"
                  return@Button
                }
                isSaving = true
                onSaveManual(parsed, isAddition) { ok, err ->
                  isSaving = false
                  if (ok) {
                    onDismiss()
                  } else {
                    errorMsg = err ?: "Failed to save earning"
                  }
                }
              }
            },
            enabled = !isSaving,
            colors = ButtonDefaults.buttonColors(containerColor = MasalaGold, contentColor = Color.Black),
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("admin_save_earning_button")
          ) {
            Text(
              text = when {
                isSaving -> "Saving..."
                useViewsMode -> "Save Views & ₹"
                else -> "Save Earning"
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
fun AdminPayoutStatusDialog(
  request: FirestoreWithdrawalRequest,
  targetStatus: String,
  onDismiss: () -> Unit,
  onConfirm: (note: String, onDone: (Boolean, String?) -> Unit) -> Unit
) {
  var note by remember { mutableStateOf(request.adminNote) }
  var isSaving by remember { mutableStateOf(false) }

  val title = when (targetStatus) {
    WithdrawalStatus.PROCESSING -> "Mark Payment Processing"
    WithdrawalStatus.DONE -> "Confirm Payment Done"
    WithdrawalStatus.REJECTED -> "Reject Withdrawal Request"
    else -> "Update Payout Status"
  }

  Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MasalaSurface,
      border = BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("admin_payout_status_dialog")
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        Text(text = title, color = MasalaTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "${request.creatorName} • ${formatInr(request.amount)} → ${request.upiId}",
          color = MasalaGold,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold
        )

        if (targetStatus == WithdrawalStatus.REJECTED) {
          Spacer(modifier = Modifier.height(14.dp))

          OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Reason (Optional)") },
            singleLine = true,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
            colors = payoutTextFieldColors()
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
              isSaving = true
              val finalNote = if (targetStatus == WithdrawalStatus.REJECTED) note.trim() else ""
              onConfirm(finalNote) { ok, _ ->
                isSaving = false
                if (ok) onDismiss()
              }
            },
            enabled = !isSaving,
            colors = ButtonDefaults.buttonColors(
              containerColor = when (targetStatus) {
                WithdrawalStatus.DONE -> MasalaSuccess
                WithdrawalStatus.REJECTED -> MasalaError
                else -> MasalaRedPrimary
              },
              contentColor = Color.White
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("confirm_payout_status_button")
          ) {
            Text(
              text = when {
                isSaving -> "Updating..."
                targetStatus == WithdrawalStatus.DONE -> "Mark Done"
                targetStatus == WithdrawalStatus.PROCESSING -> "Processing"
                else -> "Reject"
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
fun AdminWithdrawalRequestCard(
  request: FirestoreWithdrawalRequest,
  onMarkProcessing: () -> Unit,
  onMarkDone: () -> Unit,
  onReject: () -> Unit
) {
  val context = LocalContext.current
  val (statusLabel, statusColor) = statusPresentation(request.status)
  val dateLabel = remember(request.requestedAt) {
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(request.requestedAt.toDate())
  }

  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("admin_withdrawal_card_${request.requestId}")
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = request.creatorName.ifBlank { "Creator" },
            color = MasalaTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
          )
          if (request.creatorEmail.isNotBlank()) {
            Text(
              text = request.creatorEmail,
              color = MasalaTextMuted,
              fontSize = 11.sp
            )
          }
        }

        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = formatInr(request.amount),
            color = MasalaGold,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black
          )
          StatusBadgePill(label = statusLabel, color = statusColor)
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // UPI ID Copy Row
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .background(MasalaBlack.copy(alpha = 0.45f))
          .border(1.dp, MasalaCardBorder, RoundedCornerShape(8.dp))
          .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("UPI ID", color = MasalaTextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
          Text(
            text = request.upiId,
            color = MasalaTextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
          )
        }
        IconButton(
          onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Creator UPI ID", request.upiId))
            Toast.makeText(context, "UPI ID copied: ${request.upiId}", Toast.LENGTH_SHORT).show()
          },
          modifier = Modifier.size(34.dp)
        ) {
          Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy UPI ID",
            tint = MasalaGold,
            modifier = Modifier.size(16.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      Text(
        text = "Requested: $dateLabel",
        color = MasalaTextMuted,
        fontSize = 10.sp
      )

      if (request.adminNote.isNotBlank()) {
        Text(
          text = "Note / Ref: ${request.adminNote}",
          color = MasalaTextSecondary,
          fontSize = 11.sp,
          modifier = Modifier.padding(top = 4.dp)
        )
      }

      if (request.status == WithdrawalStatus.PENDING || request.status == WithdrawalStatus.PROCESSING) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          if (request.status == WithdrawalStatus.PENDING) {
            OutlinedButton(
              onClick = onMarkProcessing,
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.7f)),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
              modifier = Modifier
                .weight(1f)
                .testTag("mark_processing_${request.requestId}")
            ) {
              Icon(Icons.Default.HourglassTop, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Processing", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
          }

          Button(
            onClick = onMarkDone,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MasalaSuccess, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            modifier = Modifier
              .weight(1f)
              .testTag("mark_done_${request.requestId}")
          ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Payment Done", fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }

          OutlinedButton(
            onClick = onReject,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MasalaError.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaError),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            modifier = Modifier.testTag("mark_rejected_${request.requestId}")
          ) {
            Icon(Icons.Default.Close, contentDescription = "Reject", modifier = Modifier.size(14.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun StatusBadgePill(label: String, color: Color) {
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(color.copy(alpha = 0.16f))
      .padding(horizontal = 8.dp, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = when (label) {
        "Payment Done" -> Icons.Default.CheckCircle
        "Payment Processing" -> Icons.Default.HourglassTop
        "Rejected" -> Icons.Default.Close
        else -> Icons.Default.Schedule
      },
      contentDescription = null,
      tint = color,
      modifier = Modifier.size(11.dp)
    )
    Spacer(modifier = Modifier.width(4.dp))
    Text(
      text = label,
      color = color,
      fontSize = 10.sp,
      fontWeight = FontWeight.ExtraBold
    )
  }
}

private fun statusPresentation(status: String): Pair<String, Color> {
  return when (status) {
    WithdrawalStatus.PROCESSING -> "Payment Processing" to MasalaGold
    WithdrawalStatus.DONE -> "Payment Done" to MasalaSuccess
    WithdrawalStatus.REJECTED -> "Rejected" to MasalaError
    else -> "Pending Review" to MasalaRedAccent
  }
}

@Composable
fun AdminWithdrawalSummarySection(
  requests: List<FirestoreWithdrawalRequest>,
  onOpenPayoutsTab: () -> Unit,
  onMarkProcessing: (FirestoreWithdrawalRequest) -> Unit,
  onMarkDone: (FirestoreWithdrawalRequest) -> Unit
) {
  val activeRequests = remember(requests) {
    requests.filter {
      it.status == WithdrawalStatus.PENDING || it.status == WithdrawalStatus.PROCESSING
    }
  }
  val hasActive = activeRequests.isNotEmpty()

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(
      width = if (hasActive) 1.5.dp else 1.dp,
      color = if (hasActive) MasalaGold.copy(alpha = 0.7f) else MasalaCardBorder
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("admin_withdrawal_requests_section")
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
              .size(38.dp)
              .clip(CircleShape)
              .background(
                if (hasActive) MasalaGold.copy(alpha = 0.2f)
                else MasalaSurfaceVariant
              ),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Payments,
              contentDescription = null,
              tint = if (hasActive) MasalaGold else MasalaTextMuted,
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text(
              text = if (hasActive) {
                "Withdrawal Requests (${activeRequests.size} Active)"
              } else {
                "Creator Withdrawal Requests"
              },
              color = MasalaTextPrimary,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = if (hasActive) {
                "Creators waiting for UPI payout approval"
              } else if (requests.isNotEmpty()) {
                "${requests.size} past payout request(s) settled"
              } else {
                "No creator withdrawal requests yet"
              },
              color = if (hasActive) MasalaGold else MasalaTextMuted,
              fontSize = 11.sp
            )
          }
        }

        OutlinedButton(
          onClick = onOpenPayoutsTab,
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.6f)),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
          modifier = Modifier.testTag("open_payouts_tab_button")
        ) {
          Text(
            text = if (hasActive) "Manage All" else "Payouts",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }

      if (hasActive) {
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          activeRequests.forEach { req ->
            val (statusLabel, statusColor) = statusPresentation(req.status)
            val dateLabel = remember(req.requestedAt) {
              SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(req.requestedAt.toDate())
            }

            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MasalaSurfaceVariant)
                .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(10.dp)
                .testTag("home_withdrawal_item_${req.requestId}")
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = req.creatorName.ifBlank { "Creator" },
                    color = MasalaTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = listOfNotNull(
                      req.creatorEmail.takeIf { it.isNotBlank() },
                      "UPI: ${req.upiId}",
                      dateLabel
                    ).joinToString(" • "),
                    color = MasalaTextMuted,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                  )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                  Text(
                    text = formatInr(req.amount),
                    color = MasalaGold,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black
                  )
                  StatusBadgePill(label = statusLabel, color = statusColor)
                }
              }

              Spacer(modifier = Modifier.height(8.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                if (req.status == WithdrawalStatus.PENDING) {
                  OutlinedButton(
                    onClick = { onMarkProcessing(req) },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                  ) {
                    Icon(Icons.Default.HourglassTop, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Processing", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                  }
                }
                Button(
                  onClick = { onMarkDone(req) },
                  shape = RoundedCornerShape(8.dp),
                  colors = ButtonDefaults.buttonColors(containerColor = MasalaSuccess, contentColor = Color.White),
                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(13.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Payment Done", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
private fun payoutTextFieldColors() = OutlinedTextFieldDefaults.colors(
  focusedTextColor = MasalaTextPrimary,
  unfocusedTextColor = MasalaTextPrimary,
  focusedBorderColor = MasalaRedPrimary,
  unfocusedBorderColor = MasalaCardBorder,
  focusedLabelColor = MasalaGold,
  unfocusedLabelColor = MasalaTextMuted,
  cursorColor = MasalaGold
)
