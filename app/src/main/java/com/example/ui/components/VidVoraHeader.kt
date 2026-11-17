package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaRedAccent
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaTextMuted

@Composable
fun VidVoraHeader(
  isAdmin: Boolean,
  onAdminClick: () -> Unit,
  onTelegramMenuClick: () -> Unit = {},
  hasTelegramCategoryLinks: Boolean = false,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    // Top-left 3-dot icon for Category-wise Telegram Channel Links
    IconButton(
      onClick = onTelegramMenuClick,
      modifier = Modifier
        .align(Alignment.CenterStart)
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
        .align(Alignment.Center)
        .testTag("app_brand_header"),
      contentAlignment = Alignment.Center
    ) {
      Image(
        painter = painterResource(id = R.drawable.ic_vidvora_logo),
        contentDescription = "VidVora Logo",
        modifier = Modifier
          .height(36.dp)
          .widthIn(max = 160.dp),
        contentScale = ContentScale.Fit
      )
    }

    // Top-right Admin entry button
    IconButton(
      onClick = onAdminClick,
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .size(48.dp)
        .clip(CircleShape)
        .background(if (isAdmin) MasalaRedPrimary.copy(alpha = 0.22f) else MasalaSurface)
        .border(
          1.dp,
          if (isAdmin) MasalaRedAccent else MasalaCardBorder,
          CircleShape
        )
        .testTag("admin_access_button")
    ) {
      Icon(
        imageVector = if (isAdmin) Icons.Default.AdminPanelSettings else Icons.Default.Lock,
        contentDescription = if (isAdmin) "Admin Panel Active" else "Admin Access",
        tint = if (isAdmin) MasalaRedAccent else MasalaTextMuted,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}
