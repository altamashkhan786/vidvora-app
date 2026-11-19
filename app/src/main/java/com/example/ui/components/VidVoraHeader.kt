package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaRedAccent
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaTextMuted
import com.example.ui.theme.MasalaTextPrimary

@Composable
fun VidVoraHeader(
  isAdmin: Boolean,
  onAdminClick: () -> Unit,
  onTelegramMenuClick: () -> Unit = {},
  hasTelegramCategoryLinks: Boolean = false,
  signedInLabel: String? = null,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    // Top-left 3-dot icon for Category-wise Telegram Channel Links
    IconButton(
      onClick = onTelegramMenuClick,
      modifier = Modifier
        .size(48.dp)
        .clip(CircleShape)
        .background(MasalaSurface)
        .border(
          1.dp,
          if (hasTelegramCategoryLinks) MasalaGold.copy(alpha = 0.65f) else MasalaCardBorder,
          CircleShape
        )
        .testTag("telegram_menu_button")
    ) {
      Icon(
        imageVector = Icons.Default.MoreVert,
        contentDescription = "Telegram Channels Menu",
        tint = if (hasTelegramCategoryLinks) MasalaGold else MasalaTextMuted,
        modifier = Modifier.size(22.dp)
      )
    }

    // Centered VidVora Brand Logo
    Box(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 8.dp)
        .testTag("app_brand_header"),
      contentAlignment = Alignment.Center
    ) {
      Image(
        painter = painterResource(id = R.drawable.ic_vidvora_logo),
        contentDescription = "VidVora Logo",
        modifier = Modifier
          .height(34.dp)
          .widthIn(max = 138.dp),
        contentScale = ContentScale.Fit
      )
    }

    // Top-right Login / Signup Pill (when logged out) or Panel Pill (when logged in)
    val badgeText = when {
      isAdmin -> signedInLabel?.takeIf { it.isNotBlank() } ?: "Panel"
      else -> "Login / Sign Up"
    }
    val badgeIcon = when {
      isAdmin && signedInLabel == "Creator" -> Icons.Default.Person
      isAdmin -> Icons.Default.AdminPanelSettings
      else -> Icons.AutoMirrored.Filled.Login
    }

    Box(
      modifier = Modifier
        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        .clip(RoundedCornerShape(24.dp))
        .clickable(onClick = onAdminClick)
        .testTag("admin_access_button"),
      contentAlignment = Alignment.CenterEnd
    ) {
      Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (isAdmin) MasalaRedPrimary.copy(alpha = 0.2f) else MasalaSurface,
        border = androidx.compose.foundation.BorderStroke(
          1.dp,
          if (isAdmin) MasalaRedAccent else MasalaGold.copy(alpha = 0.65f)
        )
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center
        ) {
          Icon(
            imageVector = badgeIcon,
            contentDescription = if (isAdmin) "Open Creator or Admin Panel" else "Login or Sign Up",
            tint = if (isAdmin) MasalaRedAccent else MasalaGold,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = badgeText,
            color = if (isAdmin) Color.White else MasalaTextPrimary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}
