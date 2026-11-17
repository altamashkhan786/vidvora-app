package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VidVoraColorScheme = darkColorScheme(
  primary = MasalaRedPrimary,
  onPrimary = Color.White,
  primaryContainer = MasalaRedVariant,
  onPrimaryContainer = Color.White,
  secondary = MasalaGold,
  onSecondary = MasalaBlack,
  secondaryContainer = MasalaSurfaceVariant,
  onSecondaryContainer = MasalaGoldLight,
  background = MasalaBlack,
  onBackground = MasalaTextPrimary,
  surface = MasalaSurface,
  onSurface = MasalaTextPrimary,
  surfaceVariant = MasalaSurfaceVariant,
  onSurfaceVariant = MasalaTextSecondary,
  outline = MasalaCardBorder,
  outlineVariant = Color(0xFF1C2C4D),
  error = MasalaError,
  onError = Color.White
)

@Composable
fun MyApplicationTheme(
  content: @Composable () -> Unit
) {
  MaterialTheme(
    colorScheme = VidVoraColorScheme,
    typography = Typography,
    content = content
  )
}
