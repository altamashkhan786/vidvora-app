package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.data.model.FirestoreAdmin
import com.example.data.model.FirestoreMember
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

private const val TAG = "FirebaseAuthAdminRepo"

class FirebaseAuthAdminRepository(
  private val auth: FirebaseAuth,
  private val db: FirebaseFirestore,
  private val appContext: Context? = null
) {

  constructor(context: Context) : this(
    FirebaseAuth.getInstance(),
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    ),
    context.applicationContext
  )

  private val creatorPrefs by lazy {
    appContext?.getSharedPreferences(CREATOR_PREFS_NAME, Context.MODE_PRIVATE)
  }

  private val _currentAdminUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
  val currentAdminUser: StateFlow<FirebaseUser?> = _currentAdminUser.asStateFlow()

  private val _isAdminAuthenticated = MutableStateFlow(false)
  val isAdminAuthenticated: StateFlow<Boolean> = _isAdminAuthenticated.asStateFlow()

  private val _adminRole = MutableStateFlow<String?>(null)
  val adminRole: StateFlow<String?> = _adminRole.asStateFlow()

  private val _currentMember = MutableStateFlow<FirestoreMember?>(null)
  val currentMember: StateFlow<FirestoreMember?> = _currentMember.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _hasCustomAdminMasterKey = MutableStateFlow(
    creatorPrefs?.getString(KEY_SUPERADMIN_MASTER_KEY_HASH, null)?.length == 64
  )
  val hasCustomAdminMasterKey: StateFlow<Boolean> = _hasCustomAdminMasterKey.asStateFlow()

  init {
    auth.addAuthStateListener { firebaseAuth ->
      val user = firebaseAuth.currentUser
      _currentAdminUser.value = user
      if (user != null) {
        verifyAdminDocument(user.uid)
      } else {
        // Only clear if not authenticated via dev master passkey, member, or delegated admin session
        if (_adminRole.value != "admin (dev)" && _adminRole.value != "member" && _adminRole.value != "delegated_admin") {
          _isAdminAuthenticated.value = false
          _adminRole.value = null
          _currentMember.value = null
        }
      }
    }
  }

  fun getCachedSuperadminKeyHash(): String? =
    creatorPrefs?.getString(KEY_SUPERADMIN_MASTER_KEY_HASH, null)?.trim()?.takeIf { it.length == 64 }

  fun cacheSuperadminKeyHash(hash: String?) {
    val clean = hash?.trim()?.takeIf { it.length == 64 }
    if (clean != null) {
      creatorPrefs?.edit()?.putString(KEY_SUPERADMIN_MASTER_KEY_HASH, clean)?.apply()
      _hasCustomAdminMasterKey.value = true
    } else {
      creatorPrefs?.edit()?.remove(KEY_SUPERADMIN_MASTER_KEY_HASH)?.apply()
      _hasCustomAdminMasterKey.value = false
    }
  }

  fun authenticateWithPasskey(passkey: String, email: String? = null): Boolean {
    val clean = passkey.trim()
    val cleanEmail = email?.trim()?.lowercase() ?: return false
    val cachedHash = getCachedSuperadminKeyHash()
    val keyMatches = if (cachedHash != null) {
      FirestoreVideoRepository.hashSecretExact(clean) == cachedHash
    } else {
      clean == ADMIN_MASTER_KEY
    }
    val isMatch = cleanEmail == ADMIN_EMAIL && keyMatches
    if (isMatch) {
      _isAdminAuthenticated.value = true
      _adminRole.value = "admin (dev)"
      _currentMember.value = null
      persistSuperadminSession()
    }
    return isMatch
  }

  suspend fun authenticateMemberOrAdmin(
    email: String,
    passkey: String,
    videoRepository: FirestoreVideoRepository
  ): Boolean {
    val cleanEmail = email.trim().lowercase()
    val clean = passkey.trim()

    // Email is strictly required
    if (cleanEmail.isBlank() || !cleanEmail.contains("@") || !cleanEmail.contains(".")) {
      return false
    }

    // 1. Check Superadmin Master Key (live Firestore hash first, then cached/default fallback)
    if (cleanEmail == ADMIN_EMAIL) {
      val remoteHash = videoRepository.getSuperadminMasterKeyHash()
      if (remoteHash != null) {
        cacheSuperadminKeyHash(remoteHash)
      }
      val effectiveHash = remoteHash ?: getCachedSuperadminKeyHash()
      val matched = if (effectiveHash != null) {
        FirestoreVideoRepository.hashSecretExact(clean) == effectiveHash
      } else {
        clean == ADMIN_MASTER_KEY
      }
      if (matched) {
        _isAdminAuthenticated.value = true
        _adminRole.value = "admin (dev)"
        _currentMember.value = null
        persistSuperadminSession()
        return true
      }
    }

    // 2. Check Member email + password match in Firestore /members (falls back to passkey-only only for legacy members without email)
    val exactMember = videoRepository.getMemberByEmailAndPasskey(cleanEmail, clean)
    val member = exactMember ?: videoRepository.getMemberByPasskey(clean)?.takeIf {
      it.emailOrPhone.trim().isBlank() || it.emailOrPhone.trim().equals(cleanEmail, ignoreCase = true)
    }
    if (member != null && member.isActive) {
      signInMemberDirectly(member)
      Log.i(TAG, "${if (member.isDelegatedAdmin()) "Delegated Admin" else "Creator"} authenticated successfully: ${member.name} (${member.memberId}) with email $cleanEmail")
      return true
    }

    return false
  }

  fun signInMemberDirectly(member: FirestoreMember) {
    val resolvedRole = if (member.isDelegatedAdmin()) "delegated_admin" else "member"
    _isAdminAuthenticated.value = true
    _adminRole.value = resolvedRole
    _currentMember.value = member
    persistCreatorSession(member.memberId, resolvedRole)
  }

  suspend fun restorePersistedSession(videoRepository: FirestoreVideoRepository) {
    val persistedRole = creatorPrefs?.getString(KEY_PERSISTED_ROLE, null)?.trim()
    val persistedMemberId = creatorPrefs?.getString(KEY_ACTIVE_CREATOR_MEMBER_ID, null)?.trim()

    if (persistedRole == "superadmin") {
      _isAdminAuthenticated.value = true
      _adminRole.value = "admin (dev)"
      _currentMember.value = null
      return
    }

    if (!persistedMemberId.isNullOrBlank()) {
      val liveMember = videoRepository.getMemberById(persistedMemberId)
      if (liveMember != null && liveMember.isActive) {
        val resolvedRole = if (liveMember.isDelegatedAdmin()) "delegated_admin" else "member"
        _isAdminAuthenticated.value = true
        _adminRole.value = resolvedRole
        _currentMember.value = liveMember
        persistCreatorSession(liveMember.memberId, resolvedRole)
      } else if (liveMember != null && !liveMember.isActive) {
        clearPersistedCreatorSession()
      }
    }
  }

  private fun persistSuperadminSession() {
    creatorPrefs?.edit()
      ?.remove(KEY_ACTIVE_CREATOR_MEMBER_ID)
      ?.remove(KEY_CREATOR_UPLOADED_VIDEO_IDS)
      ?.putString(KEY_PERSISTED_ROLE, "superadmin")
      ?.apply()
  }

  private fun persistCreatorSession(memberId: String, role: String) {
    creatorPrefs?.edit()
      ?.putString(KEY_ACTIVE_CREATOR_MEMBER_ID, memberId)
      ?.putString(KEY_PERSISTED_ROLE, role)
      ?.apply()
  }

  private fun clearPersistedCreatorSession() {
    creatorPrefs?.edit()
      ?.remove(KEY_ACTIVE_CREATOR_MEMBER_ID)
      ?.remove(KEY_PERSISTED_ROLE)
      ?.remove(KEY_CREATOR_UPLOADED_VIDEO_IDS)
      ?.apply()
  }

  fun updateCurrentMember(updatedMember: FirestoreMember) {
    if (_currentMember.value?.memberId == updatedMember.memberId) {
      val updatedRole = if (updatedMember.isDelegatedAdmin()) "delegated_admin" else "member"
      _currentMember.value = updatedMember
      _adminRole.value = updatedRole
      persistCreatorSession(updatedMember.memberId, updatedRole)
    }
  }

  fun getActiveCreatorMemberId(): String? {
    return _currentMember.value?.memberId
      ?: creatorPrefs?.getString(KEY_ACTIVE_CREATOR_MEMBER_ID, null)?.takeIf { it.isNotBlank() }
  }

  fun recordCreatorUploadedVideoId(videoId: String) {
    if (videoId.isBlank()) return
    val current = creatorPrefs?.getStringSet(KEY_CREATOR_UPLOADED_VIDEO_IDS, emptySet())?.toMutableSet()
      ?: mutableSetOf()
    current.add(videoId)
    creatorPrefs?.edit()?.putStringSet(KEY_CREATOR_UPLOADED_VIDEO_IDS, current)?.apply()
  }

  fun getCreatorUploadedVideoIds(): Set<String> {
    if (getActiveCreatorMemberId().isNullOrBlank()) return emptySet()
    return creatorPrefs?.getStringSet(KEY_CREATOR_UPLOADED_VIDEO_IDS, emptySet())?.toSet() ?: emptySet()
  }

  suspend fun signInWithGoogle(context: Context): Result<FirebaseUser> {
    _isLoading.value = true
    return try {
      val credentialManager = CredentialManager.create(context)
      val webClientId = context.getString(R.string.default_web_client_id)

      val googleOption = GetSignInWithGoogleOption.Builder(webClientId)
        .build()

      val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleOption)
        .build()

      val result = credentialManager.getCredential(context = context, request = request)
      val credential = result.credential

      if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
        val authResult = auth.signInWithCredential(authCredential).await()
        val user = authResult.user ?: error("No user returned from Firebase")

        // Strictly verify admin UID and role from protected backend
        val isVerified = verifyAdminRoleFromBackend(user.uid)
        _isAdminAuthenticated.value = isVerified
        _currentAdminUser.value = user
        _isLoading.value = false

        if (isVerified) {
          Result.success(user)
        } else {
          Result.failure(SecurityException("Account '${user.email ?: user.uid}' is not authorized as an administrator in Firestore /admins."))
        }
      } else {
        _isLoading.value = false
        Result.failure(IllegalStateException("Unsupported credential type"))
      }
    } catch (e: GetCredentialCancellationException) {
      Log.w(TAG, "Google Sign-in was cancelled by user")
      _isLoading.value = false
      Result.failure(e)
    } catch (e: Exception) {
      Log.e(TAG, "Google Sign-in failed", e)
      _isLoading.value = false
      Result.failure(e)
    }
  }

  /**
   * Protected Backend Verification:
   * Directly queries the Firestore /admins/{uid} collection.
   * Auto-bootstraps the first authenticated user if collection is empty.
   */
  suspend fun verifyAdminRoleFromBackend(uid: String): Boolean {
    return try {
      val adminDoc = db.collection("admins").document(uid).get().await()
      if (adminDoc.exists()) {
        val isActive = adminDoc.getBoolean("isActive") ?: false
        val role = adminDoc.getString("role")
        val verified = isActive && (role == "admin" || role == "superadmin")
        _isAdminAuthenticated.value = verified
        _adminRole.value = if (verified) role else null
        verified
      } else {
        // Bootstrap: If no admins exist in the collection yet, register the first authenticated user
        val existingAdmins = db.collection("admins").limit(1).get().await()
        if (existingAdmins.isEmpty) {
          val newAdmin = FirestoreAdmin(uid = uid, role = "admin", isActive = true)
          val data = hashMapOf(
            "uid" to newAdmin.uid,
            "role" to newAdmin.role,
            "isActive" to newAdmin.isActive
          )
          db.collection("admins").document(uid).set(data).await()
          _isAdminAuthenticated.value = true
          _adminRole.value = "admin"
          Log.i(TAG, "First administrator auto-bootstrapped in Firestore: $uid")
          true
        } else {
          _isAdminAuthenticated.value = false
          _adminRole.value = null
          false
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Backend admin role verification failed: ${e.message}")
      false
    }
  }

  fun verifyAdminDocument(uid: String) {
    _isLoading.value = true
    db.collection("admins").document(uid).get()
      .addOnSuccessListener { doc ->
        if (doc.exists()) {
          val isActive = doc.getBoolean("isActive") ?: false
          val role = doc.getString("role")
          val verified = isActive && (role == "admin" || role == "superadmin")
          _isAdminAuthenticated.value = verified
          _adminRole.value = if (verified) role else null
          _isLoading.value = false
        } else {
          db.collection("admins").limit(1).get()
            .addOnSuccessListener { snapshot ->
              if (snapshot.isEmpty) {
                val data = hashMapOf(
                  "uid" to uid,
                  "role" to "admin",
                  "isActive" to true
                )
                db.collection("admins").document(uid).set(data)
                  .addOnSuccessListener {
                    _isAdminAuthenticated.value = true
                    _adminRole.value = "admin"
                    _isLoading.value = false
                    Log.i(TAG, "Auto-bootstrapped initial admin via listener: $uid")
                  }
                  .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to auto-bootstrap admin: ${e.message}")
                    _isAdminAuthenticated.value = false
                    _adminRole.value = null
                    _isLoading.value = false
                  }
              } else {
                _isAdminAuthenticated.value = false
                _adminRole.value = null
                _isLoading.value = false
              }
            }
            .addOnFailureListener { e ->
              Log.w(TAG, "Error checking admins list: ${e.message}")
              _isAdminAuthenticated.value = false
              _adminRole.value = null
              _isLoading.value = false
            }
        }
      }
      .addOnFailureListener { e ->
        Log.w(TAG, "Error checking backend admin record: ${e.message}")
        if (_adminRole.value != "admin (dev)" && _adminRole.value != "member" && _adminRole.value != "delegated_admin") {
          _isAdminAuthenticated.value = false
          _adminRole.value = null
        }
        _isLoading.value = false
      }
  }

  fun signOut() {
    auth.signOut()
    _isAdminAuthenticated.value = false
    _adminRole.value = null
    _currentAdminUser.value = null
    _currentMember.value = null
    clearPersistedCreatorSession()
  }

  companion object {
    const val ADMIN_EMAIL = "teamaltamash@gmail.com"
    const val ADMIN_MASTER_KEY = "7827128212"
    private const val CREATOR_PREFS_NAME = "vidvora_creator_prefs"
    private const val KEY_ACTIVE_CREATOR_MEMBER_ID = "active_creator_member_id"
    private const val KEY_PERSISTED_ROLE = "persisted_auth_role"
    private const val KEY_CREATOR_UPLOADED_VIDEO_IDS = "creator_uploaded_video_ids"
    private const val KEY_SUPERADMIN_MASTER_KEY_HASH = "superadmin_master_key_hash"
  }
}
