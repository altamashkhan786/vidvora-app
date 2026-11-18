package com.example.ui.screens.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AppUpdateConfig
import com.example.data.repository.FirestoreVideoRepository
import com.example.ui.theme.MasalaBlack
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaError
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaSuccess
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaSurfaceVariant
import com.example.ui.theme.MasalaTextMuted
import com.example.ui.theme.MasalaTextPrimary
import com.example.ui.theme.MasalaTextSecondary

@Composable
fun AdminAppUpdateCard(
  config: AppUpdateConfig,
  currentAppVersionCode: Int,
  currentAppVersionName: String,
  onEditClick: () -> Unit
) {
  val isLiveForCurrentBuild = config.shouldPromptUpdate(currentAppVersionCode)

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MasalaSurface),
    border = BorderStroke(
      1.dp,
      if (isLiveForCurrentBuild) MasalaGold.copy(alpha = 0.65f) else MasalaCardBorder
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("admin_app_update_card")
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
            .size(40.dp)
            .clip(CircleShape)
            .background(MasalaGold.copy(alpha = 0.16f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.SystemUpdate,
            contentDescription = "In-App Update Control",
            tint = MasalaGold,
            modifier = Modifier.size(22.dp)
          )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "In-App Update Popup",
              color = MasalaTextPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                  when {
                    isLiveForCurrentBuild -> MasalaSuccess.copy(alpha = 0.2f)
                    config.isEnabled -> MasalaGold.copy(alpha = 0.18f)
                    else -> MasalaSurfaceVariant
                  }
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Text(
                text = when {
                  isLiveForCurrentBuild && config.isForceUpdate -> "LIVE • FORCE"
                  isLiveForCurrentBuild -> "LIVE"
                  config.isEnabled -> "SAME BUILD"
                  else -> "OFF"
                },
                color = when {
                  isLiveForCurrentBuild -> MasalaSuccess
                  config.isEnabled -> MasalaGold
                  else -> MasalaTextMuted
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold
              )
            }
          }

          Spacer(modifier = Modifier.height(2.dp))

          Text(
            text = "Installed: v$currentAppVersionName ($currentAppVersionCode) • Target: v${config.latestVersionName} (${config.latestVersionCode})",
            color = MasalaGold,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )

          Text(
            text = config.apkUrl,
            color = MasalaTextSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      OutlinedButton(
        onClick = onEditClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaGold),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier
          .defaultMinSize(minHeight = 48.dp)
          .testTag("edit_app_update_button")
      ) {
        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Configure", fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}

@Composable
fun EditAppUpdateDialog(
  currentConfig: AppUpdateConfig,
  currentAppVersionCode: Int,
  currentAppVersionName: String,
  onDismiss: () -> Unit,
  onSave: (AppUpdateConfig, onDone: (Boolean, String?) -> Unit) -> Unit
) {
  var isEnabled by remember(currentConfig) { mutableStateOf(currentConfig.isEnabled) }
  var isForceUpdate by remember(currentConfig) { mutableStateOf(currentConfig.isForceUpdate) }
  var versionCodeInput by remember(currentConfig) {
    mutableStateOf(
      if (currentConfig.latestVersionCode > currentAppVersionCode) {
        currentConfig.latestVersionCode.toString()
      } else {
        (currentAppVersionCode + 1).toString()
      }
    )
  }
  var versionNameInput by remember(currentConfig) {
    mutableStateOf(
      if (currentConfig.latestVersionCode > currentAppVersionCode) {
        currentConfig.latestVersionName
      } else {
        "1.${currentAppVersionCode}"
      }
    )
  }
  var titleInput by remember(currentConfig) { mutableStateOf(currentConfig.title) }
  var messageInput by remember(currentConfig) { mutableStateOf(currentConfig.message) }
  var apkUrlInput by remember(currentConfig) {
    mutableStateOf(
      currentConfig.apkUrl.ifBlank { FirestoreVideoRepository.DEFAULT_APK_DOWNLOAD_URL }
    )
  }
  var validationError by remember { mutableStateOf<String?>(null) }
  var isSaving by remember { mutableStateOf(false) }

  Dialog(
    onDismissRequest = { if (!isSaving) onDismiss() },
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MasalaSurface,
      border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.45f)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 20.dp)
        .testTag("edit_app_update_dialog")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(18.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .background(MasalaGold.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.NewReleases,
              contentDescription = null,
              tint = MasalaGold,
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Self-Hosted App Update",
              color = MasalaTextPrimary,
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "No Play Store needed • Prompts users automatically on Home",
              color = MasalaTextSecondary,
              fontSize = 11.sp
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MasalaSuccess,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Current APK: v$currentAppVersionName (Code $currentAppVersionCode). Popup triggers on devices with Version Code < Latest Version Code.",
            color = MasalaTextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MasalaBlack.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Enable Update Popup",
              color = MasalaTextPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = "Turn ON after uploading new vidvora.apk to CloudFront",
              color = MasalaTextMuted,
              fontSize = 10.sp
            )
          }
          Switch(
            checked = isEnabled,
            onCheckedChange = { isEnabled = it },
            colors = SwitchDefaults.colors(
              checkedThumbColor = MasalaBlack,
              checkedTrackColor = MasalaSuccess
            ),
            modifier = Modifier.testTag("update_enabled_switch")
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MasalaBlack.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Force Update (Mandatory)",
              color = MasalaTextPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = "Hides 'Later' button so older versions must update",
              color = MasalaTextMuted,
              fontSize = 10.sp
            )
          }
          Switch(
            checked = isForceUpdate,
            onCheckedChange = { isForceUpdate = it },
            colors = SwitchDefaults.colors(
              checkedThumbColor = Color.White,
              checkedTrackColor = MasalaRedPrimary
            ),
            modifier = Modifier.testTag("update_force_switch")
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedTextField(
            value = versionCodeInput,
            onValueChange = { raw ->
              versionCodeInput = raw.filter { it.isDigit() }.take(6)
              validationError = null
            },
            label = { Text("Version Code", fontSize = 11.sp) },
            placeholder = { Text("${currentAppVersionCode + 1}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MasalaGold,
              unfocusedBorderColor = MasalaCardBorder,
              focusedTextColor = MasalaTextPrimary,
              unfocusedTextColor = MasalaTextPrimary
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("update_version_code_input")
          )

          OutlinedTextField(
            value = versionNameInput,
            onValueChange = {
              versionNameInput = it
              validationError = null
            },
            label = { Text("Version Name", fontSize = 11.sp) },
            placeholder = { Text("1.1") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MasalaGold,
              unfocusedBorderColor = MasalaCardBorder,
              focusedTextColor = MasalaTextPrimary,
              unfocusedTextColor = MasalaTextPrimary
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("update_version_name_input")
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = titleInput,
          onValueChange = {
            titleInput = it
            validationError = null
          },
          label = { Text("Popup Title", fontSize = 11.sp) },
          singleLine = true,
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MasalaGold,
            unfocusedBorderColor = MasalaCardBorder,
            focusedTextColor = MasalaTextPrimary,
            unfocusedTextColor = MasalaTextPrimary
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("update_title_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = messageInput,
          onValueChange = {
            messageInput = it
            validationError = null
          },
          label = { Text("What's New / Message", fontSize = 11.sp) },
          minLines = 2,
          maxLines = 4,
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MasalaGold,
            unfocusedBorderColor = MasalaCardBorder,
            focusedTextColor = MasalaTextPrimary,
            unfocusedTextColor = MasalaTextPrimary
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("update_message_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Direct APK Download URL",
            color = MasalaTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
          )
          TextButton(
            onClick = {
              apkUrlInput = FirestoreVideoRepository.DEFAULT_APK_DOWNLOAD_URL
              validationError = null
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = null,
              tint = MasalaGold,
              modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Use CloudFront APK", color = MasalaGold, fontSize = 11.sp)
          }
        }

        OutlinedTextField(
          value = apkUrlInput,
          onValueChange = {
            apkUrlInput = it
            validationError = null
          },
          placeholder = { Text(FirestoreVideoRepository.DEFAULT_APK_DOWNLOAD_URL, fontSize = 11.sp) },
          singleLine = true,
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MasalaGold,
            unfocusedBorderColor = MasalaCardBorder,
            focusedTextColor = MasalaTextPrimary,
            unfocusedTextColor = MasalaTextPrimary
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("update_apk_url_input")
        )

        if (!validationError.isNullOrBlank()) {
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = validationError!!,
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
            onClick = { if (!isSaving) onDismiss() },
            enabled = !isSaving,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MasalaCardBorder),
            modifier = Modifier
              .weight(1f)
              .defaultMinSize(minHeight = 48.dp)
          ) {
            Text("Cancel", color = MasalaTextSecondary, fontWeight = FontWeight.SemiBold)
          }

          Button(
            onClick = {
              val parsedCode = versionCodeInput.trim().toIntOrNull() ?: 0
              val resolvedUrl = apkUrlInput.trim().ifBlank {
                FirestoreVideoRepository.DEFAULT_APK_DOWNLOAD_URL
              }
              val error = FirestoreVideoRepository.validateAppUpdateInput(
                latestVersionCode = parsedCode,
                latestVersionName = versionNameInput,
                title = titleInput,
                message = messageInput,
                apkUrl = resolvedUrl
              )
              if (error != null) {
                validationError = error
                return@Button
              }
              isSaving = true
              validationError = null
              onSave(
                AppUpdateConfig(
                  isEnabled = isEnabled,
                  latestVersionCode = parsedCode,
                  latestVersionName = versionNameInput.trim(),
                  title = titleInput.trim(),
                  message = messageInput.trim(),
                  apkUrl = resolvedUrl,
                  isForceUpdate = isForceUpdate
                )
              ) { success, errMsg ->
                isSaving = false
                if (!success) {
                  validationError = errMsg ?: "Failed to save update settings."
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
              .weight(1.3f)
              .defaultMinSize(minHeight = 48.dp)
              .testTag("save_app_update_button")
          ) {
            if (isSaving) {
              CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
              )
            } else {
              Text("Save & Publish", fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }
  }
}
