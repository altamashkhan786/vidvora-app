package com.example.ui.screens.home

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AppUpdateConfig
import com.example.ui.theme.MasalaBlack
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaSuccess
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaSurfaceVariant
import com.example.ui.theme.MasalaTextPrimary
import com.example.ui.theme.MasalaTextSecondary

@Composable
fun AppUpdateDialog(
  config: AppUpdateConfig,
  currentVersionName: String,
  onDismissOptional: () -> Unit
) {
  val context = LocalContext.current

  Dialog(
    onDismissRequest = {
      if (!config.isForceUpdate) {
        onDismissOptional()
      }
    },
    properties = DialogProperties(
      dismissOnBackPress = !config.isForceUpdate,
      dismissOnClickOutside = !config.isForceUpdate
    )
  ) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = MasalaSurface,
      border = BorderStroke(1.dp, MasalaGold.copy(alpha = 0.55f)),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("app_update_dialog")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            Brush.verticalGradient(
              listOf(
                MasalaGold.copy(alpha = 0.12f),
                MasalaSurface
              )
            )
          )
          .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Box(
          modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(MasalaGold.copy(alpha = 0.18f))
            .border(1.dp, MasalaGold.copy(alpha = 0.5f), CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.SystemUpdate,
            contentDescription = "App Update Available",
            tint = MasalaGold,
            modifier = Modifier.size(30.dp)
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
          modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaGold.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
          Icon(
            imageVector = Icons.Default.NewReleases,
            contentDescription = null,
            tint = MasalaSuccess,
            modifier = Modifier.size(14.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "v$currentVersionName  →  v${config.latestVersionName}",
            color = MasalaGold,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = config.title,
          color = MasalaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.ExtraBold,
          textAlign = TextAlign.Center
        )

        if (config.message.isNotBlank()) {
          Spacer(modifier = Modifier.height(10.dp))
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(MasalaBlack.copy(alpha = 0.45f))
              .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
              .padding(12.dp)
          ) {
            Text(
              text = config.message,
              color = MasalaTextSecondary,
              fontSize = 13.sp,
              lineHeight = 19.sp,
              textAlign = TextAlign.Start
            )
          }
        }

        if (config.isForceUpdate) {
          Spacer(modifier = Modifier.height(10.dp))
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(MasalaRedPrimary.copy(alpha = 0.14f))
              .padding(horizontal = 10.dp, vertical = 7.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Warning,
              contentDescription = null,
              tint = MasalaGold,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "Important update required to continue using VidVora.",
              color = MasalaTextPrimary,
              fontSize = 11.sp,
              fontWeight = FontWeight.Medium
            )
          }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Button(
          onClick = {
            try {
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse(config.apkUrl.trim())).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
              context.startActivity(intent)
            } catch (_: Exception) {
              Toast.makeText(
                context,
                "Could not open download link. Please check your browser.",
                Toast.LENGTH_LONG
              ).show()
            }
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = MasalaRedPrimary,
            contentColor = Color.White
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .testTag("app_update_now_button")
        ) {
          Icon(
            imageVector = Icons.Default.Download,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Update Now (Download APK)",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
        }

        if (!config.isForceUpdate) {
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedButton(
            onClick = onDismissOptional,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MasalaCardBorder),
            modifier = Modifier
              .fillMaxWidth()
              .defaultMinSize(minHeight = 48.dp)
              .testTag("app_update_later_button")
          ) {
            Text(
              text = "Later",
              color = MasalaTextSecondary,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold
            )
          }
        }
      }
    }
  }
}
