package com.example.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AdminAuthRepository {
  private val _isAdminAuthenticated = MutableStateFlow(false)
  val isAdminAuthenticated: StateFlow<Boolean> = _isAdminAuthenticated.asStateFlow()

  fun authenticate(passkey: String): Boolean {
    val clean = passkey.trim()
    val isSuccess = clean == ADMIN_MASTER_KEY
    if (isSuccess) {
      _isAdminAuthenticated.value = true
    }
    return isSuccess
  }

  fun authenticate(email: String, passkey: String): Boolean {
    val cleanEmail = email.trim().lowercase()
    val cleanKey = passkey.trim()
    val isSuccess = cleanEmail == ADMIN_EMAIL && cleanKey == ADMIN_MASTER_KEY
    if (isSuccess) {
      _isAdminAuthenticated.value = true
    }
    return isSuccess
  }

  fun logout() {
    _isAdminAuthenticated.value = false
  }

  companion object {
    const val ADMIN_EMAIL = "teamaltamash@gmail.com"
    const val ADMIN_MASTER_KEY = "7827128212"
  }
}
