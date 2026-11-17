package com.example.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.DisposableEffect
import com.example.ui.theme.MasalaCardBorder
import com.example.ui.theme.MasalaError
import com.example.ui.theme.MasalaGold
import com.example.ui.theme.MasalaRedPrimary
import com.example.ui.theme.MasalaSurface
import com.example.ui.theme.MasalaSurfaceVariant
import com.example.ui.theme.MasalaTextMuted
import com.example.ui.theme.MasalaTextPrimary
import com.example.ui.theme.MasalaTextSecondary
import kotlinx.coroutines.launch

@Composable
fun AdminLoginDialog(
  onDismiss: () -> Unit,
  onAuthenticatePasskey: suspend (email: String, passkey: String) -> Boolean,
  onSignInWithGoogle: suspend () -> Result<Unit>
) {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val coroutineScope = rememberCoroutineScope()
  var email by remember { mutableStateOf("") }
  var passkey by remember { mutableStateOf("") }
  var isPasswordVisible by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var isGoogleLoading by remember { mutableStateOf(false) }
  var isPasskeyLoading by remember { mutableStateOf(false) }

  fun safeDismiss() {
    focusManager.clearFocus()
    keyboardController?.hide()
    onDismiss()
  }

  // Cleanly dismiss any active ActionMode or keyboard on unmount
  DisposableEffect(Unit) {
    onDispose {
      focusManager.clearFocus()
      keyboardController?.hide()
    }
  }

  fun attemptPasskeyLogin() {
    focusManager.clearFocus()
    keyboardController?.hide()
    val cleanEmail = email.trim()
    val cleanKey = passkey.trim()

    if (cleanEmail.isBlank()) {
      errorMessage = "Kripya valid Email enter karein"
      return
    }
    if (!cleanEmail.contains("@") || !cleanEmail.contains(".")) {
      errorMessage = "Kripya valid Email enter karein"
      return
    }
    if (cleanKey.isBlank()) {
      errorMessage = "Kripya Master Key enter karein"
      return
    }

    coroutineScope.launch {
      isPasskeyLoading = true
      errorMessage = null
      val success = onAuthenticatePasskey(cleanEmail, cleanKey)
      isPasskeyLoading = false
      if (!success) {
        errorMessage = "Email ya Master Key galat hai."
      } else {
        safeDismiss()
      }
    }
  }

  Dialog(onDismissRequest = { safeDismiss() }) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = MasalaSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("admin_login_dialog")
    ) {
      Column(
        modifier = Modifier.padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Box(
          modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaRedPrimary.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            tint = MasalaRedPrimary,
            modifier = Modifier.size(30.dp)
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
          text = "Administrator Portal",
          color = MasalaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Email field
        OutlinedTextField(
          value = email,
          onValueChange = {
            email = it
            errorMessage = null
          },
          label = { Text("Email Address") },
          leadingIcon = {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = MasalaGold)
          },
          keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next
          ),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MasalaRedPrimary,
            unfocusedBorderColor = MasalaCardBorder,
            focusedTextColor = MasalaTextPrimary,
            unfocusedTextColor = MasalaTextPrimary,
            focusedContainerColor = MasalaSurfaceVariant,
            unfocusedContainerColor = MasalaSurfaceVariant,
            focusedLabelColor = MasalaGold,
            unfocusedLabelColor = MasalaTextMuted
          ),
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_email_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Master Key field
        OutlinedTextField(
          value = passkey,
          onValueChange = {
            passkey = it
            errorMessage = null
          },
          label = { Text("Master Key") },
          leadingIcon = {
            Icon(Icons.Default.Key, contentDescription = null, tint = MasalaGold)
          },
          trailingIcon = {
            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
              Icon(
                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (isPasswordVisible) "Hide key" else "Show key",
                tint = MasalaTextMuted
              )
            }
          },
          visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
          ),
          keyboardActions = KeyboardActions(onDone = { attemptPasskeyLogin() }),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MasalaRedPrimary,
            unfocusedBorderColor = MasalaCardBorder,
            focusedTextColor = MasalaTextPrimary,
            unfocusedTextColor = MasalaTextPrimary,
            focusedContainerColor = MasalaSurfaceVariant,
            unfocusedContainerColor = MasalaSurfaceVariant,
            focusedLabelColor = MasalaGold,
            unfocusedLabelColor = MasalaTextMuted
          ),
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_passkey_input")
        )

        if (errorMessage != null) {
          Text(
            text = errorMessage ?: "",
            color = MasalaError,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
              .align(Alignment.Start)
              .padding(top = 6.dp)
              .testTag("admin_login_error")
          )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
          text = "Apna Email aur Master Key enter karein tabhi access milega.",
          color = MasalaTextMuted,
          fontSize = 11.sp,
          modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = { safeDismiss() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextSecondary),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(46.dp)
              .testTag("admin_login_cancel")
          ) {
            Text("Cancel")
          }

          Button(
            onClick = { attemptPasskeyLogin() },
            enabled = !isPasskeyLoading,
            colors = ButtonDefaults.buttonColors(
              containerColor = MasalaRedPrimary,
              contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(46.dp)
              .testTag("admin_login_submit")
          ) {
            if (isPasskeyLoading) {
              CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
              Text("Unlock", fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }
  }
}
