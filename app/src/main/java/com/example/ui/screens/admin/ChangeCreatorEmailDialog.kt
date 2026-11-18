package com.example.ui.screens.admin

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.MemberRole
import com.example.data.model.MemberStats
import com.example.data.model.formatInr
import com.example.data.repository.FirestoreVideoRepository
import com.example.ui.theme.MasalaBlack
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaError
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaSuccess
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaSurfaceVariant
import com.example.ui.theme.MasalaTextMuted
import com.example.ui.theme.MasalaTextPrimary
import com.example.ui.theme.MasalaTextSecondary

@Composable
fun ChangeCreatorEmailDialog(
  memberStats: MemberStats,
  allMembers: List<MemberStats>,
  onDismiss: () -> Unit,
  onConfirm: (newEmail: String, onDone: (Boolean, String?) -> Unit) -> Unit
) {
  val member = memberStats.member
  var newEmail by remember(member.memberId) { mutableStateOf("") }
  var isSaving by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val cleanNewEmail = newEmail.trim()
  val matchedExistingTarget = remember(cleanNewEmail, allMembers, member.memberId) {
    if (cleanNewEmail.isBlank()) {
      null
    } else {
      allMembers.firstOrNull { candidate ->
        candidate.member.memberId != member.memberId &&
          candidate.member.emailOrPhone.trim().equals(cleanNewEmail, ignoreCase = true)
      }
    }
  }

  Dialog(
    onDismissRequest = { if (!isSaving) onDismiss() },
    properties = DialogProperties(dismissOnClickOutside = !isSaving)
  ) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MasalaSurface,
      border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.5f)),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("change_creator_email_dialog")
    ) {
      Column(
        modifier = Modifier
          .padding(18.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .background(MasalaGold.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.AlternateEmail,
              contentDescription = null,
              tint = MasalaGold,
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Change Creator Email",
              color = MasalaTextPrimary,
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Purana email ka sara data & earning new email par dikhega",
              color = MasalaGold,
              fontSize = 11.sp
            )
          }
        }

        // Current creator & preserved data summary
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            text = "${member.name} (${MemberRole.displayLabel(member.role)})",
            color = MasalaTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Current Email: ${member.emailOrPhone.ifBlank { "Not set" }}",
            color = MasalaTextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = "Password: ${member.passkey}",
            color = MasalaGold,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
          )

          Spacer(modifier = Modifier.height(2.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            SummaryMiniPill(
              label = "VIDEOS",
              value = "${memberStats.videoCount}",
              valueColor = MasalaTextPrimary,
              modifier = Modifier.weight(1f)
            )
            SummaryMiniPill(
              label = "CREATOR VIEWS",
              value = "${member.creditedViews}",
              valueColor = MasalaSuccess,
              modifier = Modifier.weight(1f)
            )
            SummaryMiniPill(
              label = "EARNING",
              value = formatInr(memberStats.earningSummary.totalEarning),
              valueColor = MasalaGold,
              modifier = Modifier.weight(1f)
            )
          }
        }

        OutlinedTextField(
          value = newEmail,
          onValueChange = {
            newEmail = it
            if (errorMessage != null) errorMessage = null
          },
          label = { Text("New Email Address *") },
          placeholder = { Text("creator.new@gmail.com", fontSize = 12.sp) },
          singleLine = true,
          enabled = !isSaving,
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
            .testTag("change_creator_email_input")
        )

        if (matchedExistingTarget != null) {
          val target = matchedExistingTarget.member
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(MasalaGold.copy(alpha = 0.14f))
              .border(1.dp, MasalaGold.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
              .padding(10.dp),
            verticalAlignment = Alignment.Top
          ) {
            Icon(
              imageVector = Icons.Default.SwapHoriz,
              contentDescription = null,
              tint = MasalaGold,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Account '${target.name}' (${target.emailOrPhone}) pehle se maujood hai. Save karne par purane account ke sabhi ${memberStats.videoCount} videos, ${member.creditedViews} views, ${formatInr(memberStats.earningSummary.totalEarning)} earning aur payout history '${target.name}' (Password: ${target.passkey}) mein transfer ho jayenge.",
              color = MasalaTextPrimary,
              fontSize = 11.sp,
              lineHeight = 15.sp
            )
          }
        } else {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(MasalaSuccess.copy(alpha = 0.12f))
              .border(1.dp, MasalaSuccess.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
              .padding(10.dp),
            verticalAlignment = Alignment.Top
          ) {
            Icon(
              imageVector = Icons.Default.CheckCircle,
              contentDescription = null,
              tint = MasalaSuccess,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Email change hone ke baad creator apne New Email + Password se login karega to uska purana sara video data, views, ₹ earning aur UPI payouts turant new email par dikhega.",
              color = MasalaTextPrimary,
              fontSize = 11.sp,
              lineHeight = 15.sp
            )
          }
        }

        if (errorMessage != null) {
          Text(
            text = errorMessage ?: "",
            color = MasalaError,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
          )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            enabled = !isSaving,
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
          ) {
            Text("Cancel")
          }
          Button(
            onClick = {
              val emailErr = FirestoreVideoRepository.validateEmailInput(cleanNewEmail)
              if (emailErr != null) {
                errorMessage = emailErr
                return@Button
              }
              if (member.emailOrPhone.trim().equals(cleanNewEmail, ignoreCase = true)) {
                errorMessage = "Please enter a different email address."
                return@Button
              }
              isSaving = true
              errorMessage = null
              onConfirm(cleanNewEmail) { success, err ->
                isSaving = false
                if (!success) {
                  errorMessage = err ?: "Failed to change email."
                }
              }
            },
            enabled = !isSaving,
            colors = ButtonDefaults.buttonColors(
              containerColor = MasalaGold,
              contentColor = MasalaBlack
            ),
            modifier = Modifier
              .weight(1.4f)
              .heightIn(min = 48.dp)
              .testTag("confirm_change_creator_email_button")
          ) {
            Text(
              text = when {
                isSaving -> "Updating..."
                matchedExistingTarget != null -> "Transfer & Merge"
                else -> "Update Email"
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
private fun SummaryMiniPill(
  label: String,
  value: String,
  valueColor: Color,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(MasalaSurface)
      .border(1.dp, MasalaCardBorder, RoundedCornerShape(8.dp))
      .padding(horizontal = 6.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = label,
      color = MasalaTextMuted,
      fontSize = 8.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1
    )
    Text(
      text = value,
      color = valueColor,
      fontSize = 12.sp,
      fontWeight = FontWeight.Black,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}
