package com.example.ui.screens.admin

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.FirestoreMember
import com.example.data.repository.FirestoreVideoRepository
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AuthPortalTab {
  LOGIN,
  SIGNUP
}

@Composable
fun AdminLoginDialog(
  onDismiss: () -> Unit,
  onAuthenticatePasskey: suspend (email: String, passkey: String) -> Boolean,
  onSignInWithGoogle: suspend () -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
  onSendSignupOtp: suspend (name: String, email: String, password: String) -> Result<String> = { _, _, _ ->
    Result.failure(IllegalStateException("Signup unavailable"))
  },
  onVerifySignupOtp: suspend (name: String, email: String, password: String, otp: String) -> Result<FirestoreMember> = { _, _, _, _ ->
    Result.failure(IllegalStateException("Signup unavailable"))
  },
  onSendResetOtp: suspend (email: String) -> Result<String> = {
    Result.failure(IllegalStateException("Reset unavailable"))
  },
  onVerifyResetOtp: suspend (email: String, otp: String, newPassword: String) -> Result<FirestoreMember> = { _, _, _ ->
    Result.failure(IllegalStateException("Reset unavailable"))
  },
  onAuthenticatedNavigate: () -> Unit = {}
) {
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val coroutineScope = rememberCoroutineScope()

  var activeTab by remember { mutableStateOf(AuthPortalTab.LOGIN) }

  // Login tab state
  var loginEmail by remember { mutableStateOf("") }
  var loginPassword by remember { mutableStateOf("") }
  var isLoginPassVisible by remember { mutableStateOf(false) }
  var isForgotPasswordMode by remember { mutableStateOf(false) }
  var resetOtpSent by remember { mutableStateOf(false) }
  var resetOtpCode by remember { mutableStateOf("") }
  var resetNewPassword by remember { mutableStateOf("") }

  // Sign up tab state
  var signupName by remember { mutableStateOf("") }
  var signupEmail by remember { mutableStateOf("") }
  var signupPassword by remember { mutableStateOf("") }
  var isSignupPassVisible by remember { mutableStateOf(false) }
  var signupOtpSent by remember { mutableStateOf(false) }
  var signupOtpCode by remember { mutableStateOf("") }

  // Shared status
  var isBusy by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var resendCooldownSeconds by remember { mutableIntStateOf(0) }

  LaunchedEffect(resendCooldownSeconds) {
    if (resendCooldownSeconds > 0) {
      delay(1000L)
      resendCooldownSeconds -= 1
    }
  }

  fun safeDismiss() {
    focusManager.clearFocus()
    keyboardController?.hide()
    onDismiss()
  }

  DisposableEffect(Unit) {
    onDispose {
      focusManager.clearFocus()
      keyboardController?.hide()
    }
  }

  @Composable
  fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MasalaGold,
    unfocusedBorderColor = MasalaCardBorder,
    focusedTextColor = MasalaTextPrimary,
    unfocusedTextColor = MasalaTextPrimary,
    focusedContainerColor = MasalaSurfaceVariant,
    unfocusedContainerColor = MasalaSurfaceVariant,
    focusedLabelColor = MasalaGold,
    unfocusedLabelColor = MasalaTextMuted
  )

  fun attemptLogin() {
    focusManager.clearFocus()
    keyboardController?.hide()
    val cleanEmail = loginEmail.trim()
    val cleanKey = loginPassword.trim()

    if (cleanEmail.isBlank() || !cleanEmail.contains("@") || !cleanEmail.contains(".")) {
      errorMessage = "Kripya valid Gmail / Email enter karein"
      return
    }
    if (cleanKey.isBlank()) {
      errorMessage = "Kripya Password enter karein"
      return
    }

    coroutineScope.launch {
      isBusy = true
      errorMessage = null
      statusMessage = null
      val success = onAuthenticatePasskey(cleanEmail, cleanKey)
      isBusy = false
      if (!success) {
        errorMessage = "Email ya Password galat hai (ya account paused hai)."
      } else {
        onAuthenticatedNavigate()
        safeDismiss()
      }
    }
  }

  fun attemptSendSignupOtp() {
    focusManager.clearFocus()
    keyboardController?.hide()
    val cleanName = signupName.trim()
    val cleanEmail = signupEmail.trim()
    val cleanPass = signupPassword.trim()

    val validationErr = FirestoreVideoRepository.validateMemberInput(cleanName, cleanEmail, cleanPass)
    if (validationErr != null) {
      errorMessage = validationErr
      return
    }

    coroutineScope.launch {
      isBusy = true
      errorMessage = null
      statusMessage = null
      val res = onSendSignupOtp(cleanName, cleanEmail, cleanPass)
      isBusy = false
      res.onSuccess { msg ->
        signupOtpSent = true
        signupOtpCode = ""
        resendCooldownSeconds = 60
        statusMessage = msg
      }.onFailure { err ->
        errorMessage = err.message ?: "OTP send karne me problem aayi."
      }
    }
  }

  fun attemptVerifySignupOtp() {
    focusManager.clearFocus()
    keyboardController?.hide()
    val cleanOtp = signupOtpCode.trim()
    if (!cleanOtp.matches(Regex("^\\d{6}$"))) {
      errorMessage = "Kripya Gmail par aaya 6-digit OTP enter karein."
      return
    }

    coroutineScope.launch {
      isBusy = true
      errorMessage = null
      val res = onVerifySignupOtp(signupName.trim(), signupEmail.trim(), signupPassword.trim(), cleanOtp)
      isBusy = false
      res.onSuccess {
        onAuthenticatedNavigate()
        safeDismiss()
      }.onFailure { err ->
        errorMessage = err.message ?: "OTP verify nahi ho paya."
      }
    }
  }

  fun attemptSendResetOtp() {
    focusManager.clearFocus()
    keyboardController?.hide()
    val cleanEmail = loginEmail.trim()
    val emailErr = FirestoreVideoRepository.validateEmailInput(cleanEmail)
    if (emailErr != null) {
      errorMessage = emailErr
      return
    }
    coroutineScope.launch {
      isBusy = true
      errorMessage = null
      statusMessage = null
      val res = onSendResetOtp(cleanEmail)
      isBusy = false
      res.onSuccess { msg ->
        resetOtpSent = true
        resetOtpCode = ""
        resendCooldownSeconds = 60
        statusMessage = msg
      }.onFailure { err ->
        errorMessage = err.message ?: "Reset OTP send nahi ho paya."
      }
    }
  }

  fun attemptVerifyResetOtp() {
    focusManager.clearFocus()
    keyboardController?.hide()
    val cleanOtp = resetOtpCode.trim()
    val cleanNewPass = resetNewPassword.trim()
    if (!cleanOtp.matches(Regex("^\\d{6}$"))) {
      errorMessage = "Kripya 6-digit OTP enter karein."
      return
    }
    if (cleanNewPass.length < FirestoreVideoRepository.MIN_PASSKEY_LENGTH) {
      errorMessage = "New Password kam se kam ${FirestoreVideoRepository.MIN_PASSKEY_LENGTH} characters ka hona chahiye."
      return
    }
    coroutineScope.launch {
      isBusy = true
      errorMessage = null
      val res = onVerifyResetOtp(loginEmail.trim(), cleanOtp, cleanNewPass)
      isBusy = false
      res.onSuccess {
        onAuthenticatedNavigate()
        safeDismiss()
      }.onFailure { err ->
        errorMessage = err.message ?: "Password reset failed."
      }
    }
  }

  Dialog(
    onDismissRequest = { if (!isBusy) safeDismiss() },
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = MasalaSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp)
        .testTag("admin_login_dialog")
    ) {
      Column(
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Icon badge
        Box(
          modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MasalaSurfaceVariant)
            .border(
              1.dp,
              if (activeTab == AuthPortalTab.SIGNUP) MasalaGold.copy(alpha = 0.6f) else MasalaRedPrimary.copy(alpha = 0.5f),
              RoundedCornerShape(16.dp)
            ),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = when {
              activeTab == AuthPortalTab.SIGNUP && signupOtpSent -> Icons.Default.MarkEmailRead
              activeTab == AuthPortalTab.SIGNUP -> Icons.Default.PersonAdd
              else -> Icons.AutoMirrored.Filled.Login
            },
            contentDescription = null,
            tint = if (activeTab == AuthPortalTab.SIGNUP) MasalaGold else MasalaRedPrimary,
            modifier = Modifier.size(28.dp)
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = when {
            activeTab == AuthPortalTab.SIGNUP && signupOtpSent -> "Verify Gmail OTP"
            activeTab == AuthPortalTab.SIGNUP -> "Creator Sign Up"
            isForgotPasswordMode -> "Reset Password via OTP"
            else -> "Login to VidVora Portal"
          },
          color = MasalaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center
        )

        Text(
          text = when {
            activeTab == AuthPortalTab.SIGNUP && signupOtpSent ->
              "Apne Gmail par aaya 6-digit OTP enter karein"
            activeTab == AuthPortalTab.SIGNUP ->
              "Apna Creator account khud banayein aur seedha panel open karein"
            isForgotPasswordMode ->
              "Gmail par OTP mangwayein aur naya Password set karein"
            else ->
              "Creator, Admin ya Superadmin Gmail & Password se sign in karein"
          },
          color = MasalaTextSecondary,
          fontSize = 11.5.sp,
          textAlign = TextAlign.Center,
          lineHeight = 15.sp,
          modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 2-Tab Switcher: Login | Sign Up
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MasalaSurfaceVariant)
            .border(1.dp, MasalaCardBorder, RoundedCornerShape(12.dp))
            .padding(4.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          val isLoginSelected = activeTab == AuthPortalTab.LOGIN
          Box(
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 42.dp)
              .clip(RoundedCornerShape(9.dp))
              .background(if (isLoginSelected) MasalaRedPrimary else Color.Transparent)
              .clickable(enabled = !isBusy) {
                activeTab = AuthPortalTab.LOGIN
                errorMessage = null
                statusMessage = null
              }
              .testTag("auth_tab_login"),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "Login",
              color = if (isLoginSelected) Color.White else MasalaTextSecondary,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
          }

          val isSignupSelected = activeTab == AuthPortalTab.SIGNUP
          Box(
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 42.dp)
              .clip(RoundedCornerShape(9.dp))
              .background(if (isSignupSelected) MasalaGold else Color.Transparent)
              .clickable(enabled = !isBusy) {
                activeTab = AuthPortalTab.SIGNUP
                errorMessage = null
                statusMessage = null
              }
              .testTag("auth_tab_signup"),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "Sign Up (New Creator)",
              color = if (isSignupSelected) Color.Black else MasalaTextSecondary,
              fontSize = 12.5.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (activeTab == AuthPortalTab.LOGIN) {
          if (!isForgotPasswordMode) {
            // Normal Gmail + Password Login
            OutlinedTextField(
              value = loginEmail,
              onValueChange = {
                loginEmail = it
                errorMessage = null
              },
              label = { Text("Gmail / Email Address") },
              leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = MasalaGold)
              },
              keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
              ),
              colors = authFieldColors(),
              singleLine = true,
              enabled = !isBusy,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("admin_email_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
              value = loginPassword,
              onValueChange = {
                loginPassword = it
                errorMessage = null
              },
              label = { Text("Password") },
              leadingIcon = {
                Icon(Icons.Default.Key, contentDescription = null, tint = MasalaGold)
              },
              trailingIcon = {
                IconButton(onClick = { isLoginPassVisible = !isLoginPassVisible }) {
                  Icon(
                    imageVector = if (isLoginPassVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (isLoginPassVisible) "Hide password" else "Show password",
                    tint = MasalaTextMuted
                  )
                }
              },
              visualTransformation = if (isLoginPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
              ),
              keyboardActions = KeyboardActions(onDone = { attemptLogin() }),
              colors = authFieldColors(),
              singleLine = true,
              enabled = !isBusy,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("admin_passkey_input")
            )

            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              TextButton(
                onClick = {
                  isForgotPasswordMode = true
                  resetOtpSent = false
                  resetOtpCode = ""
                  resetNewPassword = ""
                  errorMessage = null
                  statusMessage = null
                },
                enabled = !isBusy,
                modifier = Modifier.testTag("forgot_password_button")
              ) {
                Text(
                  text = "Forgot Password?",
                  color = MasalaGold,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.SemiBold
                )
              }

              TextButton(
                onClick = {
                  activeTab = AuthPortalTab.SIGNUP
                  errorMessage = null
                  statusMessage = null
                },
                enabled = !isBusy
              ) {
                Text(
                  text = "New Creator? Sign Up →",
                  color = MasalaTextSecondary,
                  fontSize = 11.5.sp
                )
              }
            }
          } else {
            // Forgot Password OTP reset flow inside Login tab
            OutlinedTextField(
              value = loginEmail,
              onValueChange = {
                loginEmail = it
                errorMessage = null
              },
              label = { Text("Registered Gmail Address") },
              leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = MasalaGold)
              },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
              colors = authFieldColors(),
              singleLine = true,
              enabled = !isBusy && !resetOtpSent,
              modifier = Modifier.fillMaxWidth()
            )

            if (resetOtpSent) {
              Spacer(modifier = Modifier.height(10.dp))
              OutlinedTextField(
                value = resetOtpCode,
                onValueChange = { raw ->
                  resetOtpCode = raw.filter { it.isDigit() }.take(6)
                  errorMessage = null
                },
                label = { Text("6-Digit Gmail OTP") },
                leadingIcon = {
                  Icon(Icons.Default.Pin, contentDescription = null, tint = MasalaGold)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                colors = authFieldColors(),
                singleLine = true,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
              )

              Spacer(modifier = Modifier.height(10.dp))
              OutlinedTextField(
                value = resetNewPassword,
                onValueChange = {
                  resetNewPassword = it
                  errorMessage = null
                },
                label = { Text("New Password (min 4 chars)") },
                leadingIcon = {
                  Icon(Icons.Default.Key, contentDescription = null, tint = MasalaGold)
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { attemptVerifyResetOtp() }),
                colors = authFieldColors(),
                singleLine = true,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
              )

              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                TextButton(
                  onClick = { attemptSendResetOtp() },
                  enabled = !isBusy && resendCooldownSeconds == 0
                ) {
                  Text(
                    text = if (resendCooldownSeconds > 0) "Resend in ${resendCooldownSeconds}s" else "Resend OTP",
                    color = MasalaGold,
                    fontSize = 12.sp
                  )
                }
                TextButton(
                  onClick = {
                    isForgotPasswordMode = false
                    resetOtpSent = false
                    errorMessage = null
                    statusMessage = null
                  },
                  enabled = !isBusy
                ) {
                  Text("Back to Login", color = MasalaTextSecondary, fontSize = 12.sp)
                }
              }
            } else {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End
              ) {
                TextButton(
                  onClick = {
                    isForgotPasswordMode = false
                    errorMessage = null
                    statusMessage = null
                  },
                  enabled = !isBusy
                ) {
                  Text("Back to Login", color = MasalaTextSecondary, fontSize = 12.sp)
                }
              }
            }
          }
        } else {
          // SIGNUP TAB
          if (!signupOtpSent) {
            OutlinedTextField(
              value = signupName,
              onValueChange = {
                signupName = it
                errorMessage = null
              },
              label = { Text("Full Name") },
              leadingIcon = {
                Icon(Icons.Default.Person, contentDescription = null, tint = MasalaGold)
              },
              keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next
              ),
              colors = authFieldColors(),
              singleLine = true,
              enabled = !isBusy,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("signup_name_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
              value = signupEmail,
              onValueChange = {
                signupEmail = it
                errorMessage = null
              },
              label = { Text("Gmail Address") },
              leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = MasalaGold)
              },
              keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
              ),
              colors = authFieldColors(),
              singleLine = true,
              enabled = !isBusy,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("signup_email_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
              value = signupPassword,
              onValueChange = {
                signupPassword = it
                errorMessage = null
              },
              label = { Text("Create Password (min 4 chars)") },
              leadingIcon = {
                Icon(Icons.Default.Key, contentDescription = null, tint = MasalaGold)
              },
              trailingIcon = {
                IconButton(onClick = { isSignupPassVisible = !isSignupPassVisible }) {
                  Icon(
                    imageVector = if (isSignupPassVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (isSignupPassVisible) "Hide password" else "Show password",
                    tint = MasalaTextMuted
                  )
                }
              },
              visualTransformation = if (isSignupPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
              ),
              keyboardActions = KeyboardActions(onDone = { attemptSendSignupOtp() }),
              colors = authFieldColors(),
              singleLine = true,
              enabled = !isBusy,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("signup_password_input")
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
              text = "Aapke Gmail par 6-digit OTP aayega. Verify hote hi account banega aur direct Creator Panel khul jayega.",
              color = MasalaTextMuted,
              fontSize = 11.sp,
              lineHeight = 15.sp,
              modifier = Modifier.align(Alignment.Start)
            )
          } else {
            // STEP 2: OTP VERIFICATION
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MasalaSurfaceVariant)
                .border(1.dp, MasalaGold.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(12.dp)
            ) {
              Text(
                text = "OTP SENT TO GMAIL",
                color = MasalaGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = signupEmail.trim().lowercase(),
                color = MasalaTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "Inbox ya Spam folder check karein (10 min valid).",
                color = MasalaTextMuted,
                fontSize = 10.5.sp
              )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
              value = signupOtpCode,
              onValueChange = { raw ->
                signupOtpCode = raw.filter { it.isDigit() }.take(6)
                errorMessage = null
              },
              label = { Text("Enter 6-Digit OTP") },
              leadingIcon = {
                Icon(Icons.Default.Pin, contentDescription = null, tint = MasalaGold)
              },
              keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
              ),
              keyboardActions = KeyboardActions(onDone = { attemptVerifySignupOtp() }),
              colors = authFieldColors(),
              singleLine = true,
              enabled = !isBusy,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("signup_otp_input")
            )

            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              TextButton(
                onClick = { attemptSendSignupOtp() },
                enabled = !isBusy && resendCooldownSeconds == 0,
                modifier = Modifier.testTag("signup_resend_otp_button")
              ) {
                Text(
                  text = if (resendCooldownSeconds > 0) "Resend OTP (${resendCooldownSeconds}s)" else "Resend OTP",
                  color = if (resendCooldownSeconds == 0) MasalaGold else MasalaTextMuted,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold
                )
              }

              TextButton(
                onClick = {
                  signupOtpSent = false
                  signupOtpCode = ""
                  errorMessage = null
                  statusMessage = null
                },
                enabled = !isBusy
              ) {
                Text("Change Gmail", color = MasalaTextSecondary, fontSize = 12.sp)
              }
            }
          }
        }

        if (statusMessage != null) {
          Text(
            text = statusMessage ?: "",
            color = MasalaSuccess,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
              .align(Alignment.Start)
              .padding(top = 6.dp)
              .testTag("auth_status_message")
          )
        }

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

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = { safeDismiss() },
            enabled = !isBusy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaTextSecondary),
            border = androidx.compose.foundation.BorderStroke(1.dp, MasalaCardBorder),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("admin_login_cancel")
          ) {
            Text("Cancel")
          }

          val primaryButtonText = when {
            activeTab == AuthPortalTab.LOGIN && !isForgotPasswordMode -> "Sign In"
            activeTab == AuthPortalTab.LOGIN && isForgotPasswordMode && !resetOtpSent -> "Send OTP"
            activeTab == AuthPortalTab.LOGIN && isForgotPasswordMode && resetOtpSent -> "Reset & Login"
            activeTab == AuthPortalTab.SIGNUP && !signupOtpSent -> "Send OTP"
            else -> "Verify & Join"
          }

          val primaryButtonTag = when {
            activeTab == AuthPortalTab.SIGNUP && !signupOtpSent -> "signup_send_otp_button"
            activeTab == AuthPortalTab.SIGNUP && signupOtpSent -> "signup_verify_otp_button"
            else -> "admin_login_submit"
          }

          Button(
            onClick = {
              when {
                activeTab == AuthPortalTab.LOGIN && !isForgotPasswordMode -> attemptLogin()
                activeTab == AuthPortalTab.LOGIN && isForgotPasswordMode && !resetOtpSent -> attemptSendResetOtp()
                activeTab == AuthPortalTab.LOGIN && isForgotPasswordMode && resetOtpSent -> attemptVerifyResetOtp()
                activeTab == AuthPortalTab.SIGNUP && !signupOtpSent -> attemptSendSignupOtp()
                else -> attemptVerifySignupOtp()
              }
            },
            enabled = !isBusy,
            colors = ButtonDefaults.buttonColors(
              containerColor = if (activeTab == AuthPortalTab.SIGNUP) MasalaGold else MasalaRedPrimary,
              contentColor = if (activeTab == AuthPortalTab.SIGNUP) Color.Black else Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1.3f)
              .height(48.dp)
              .testTag(primaryButtonTag)
          ) {
            if (isBusy) {
              CircularProgressIndicator(
                color = if (activeTab == AuthPortalTab.SIGNUP) Color.Black else Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
              )
            } else {
              Text(primaryButtonText, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }
  }
}
