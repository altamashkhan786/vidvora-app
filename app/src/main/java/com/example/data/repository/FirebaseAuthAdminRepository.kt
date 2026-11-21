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
import com.example.data.model.MemberOption
import com.example.data.model.MemberRole
import com.example.notifications.AppNotificationHelper
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.net.URLDecoder
import java.net.URLEncoder
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

  private val _loginEventCounter = MutableStateFlow(0)
  val loginEventCounter: StateFlow<Int> = _loginEventCounter.asStateFlow()

  private val _hasCustomAdminMasterKey = MutableStateFlow(
    creatorPrefs?.getString(KEY_SUPERADMIN_MASTER_KEY_HASH, null)?.length == 64
  )
  val hasCustomAdminMasterKey: StateFlow<Boolean> = _hasCustomAdminMasterKey.asStateFlow()

  init {
    // Synchronously restore any saved Superadmin, Delegated Admin, Creator, or Google Admin session
    // BEFORE attaching FirebaseAuth listener so there is never an accidental auto-logout flash.
    hydratePersistedSessionSync()

    auth.addAuthStateListener { firebaseAuth ->
      val user = firebaseAuth.currentUser
      _currentAdminUser.value = user
      // Never allow FirebaseAuth listener to disturb an active persisted Superadmin/Creator/Admin passkey session
      if (hasPersistedNonGoogleSession()) {
        return@addAuthStateListener
      }
      if (user != null) {
        verifyAdminDocument(user.uid)
      } else {
        // Only clear when no persisted non-Google session is active
        if (!hasPersistedNonGoogleSession() &&
          _adminRole.value != "admin (dev)" &&
          _adminRole.value != "member" &&
          _adminRole.value != "delegated_admin"
        ) {
          _isAdminAuthenticated.value = false
          _adminRole.value = null
          _currentMember.value = null
        }
      }
    }
  }

  private fun hasPersistedNonGoogleSession(): Boolean {
    val prefs = creatorPrefs ?: return false
    val role = prefs.getString(KEY_PERSISTED_ROLE, null)?.trim()
    if (role == "superadmin" || role == "member" || role == "delegated_admin") {
      return true
    }
    return !prefs.getString(KEY_ACTIVE_CREATOR_MEMBER_ID, null).isNullOrBlank()
  }

  private fun hydratePersistedSessionSync() {
    val prefs = creatorPrefs ?: return
    val persistedRole = prefs.getString(KEY_PERSISTED_ROLE, null)?.trim()
    val persistedMemberId = prefs.getString(KEY_ACTIVE_CREATOR_MEMBER_ID, null)?.trim()
    val cachedMemberJson = prefs.getString(KEY_CACHED_MEMBER_JSON, null)

    when {
      persistedRole == "superadmin" -> {
        _isAdminAuthenticated.value = true
        _adminRole.value = "admin (dev)"
        _currentMember.value = null
      }
      !persistedMemberId.isNullOrBlank() || persistedRole == "member" || persistedRole == "delegated_admin" -> {
        val decoded = decodeMemberSessionPayload(cachedMemberJson)
        val effectiveMember = when {
          decoded != null && decoded.isActive -> decoded
          !persistedMemberId.isNullOrBlank() -> {
            val fallbackRole = if (persistedRole == "delegated_admin") MemberRole.ADMIN else MemberRole.CREATOR
            FirestoreMember(
              memberId = persistedMemberId,
              name = if (fallbackRole == MemberRole.ADMIN) "Admin" else "Creator",
              role = fallbackRole,
              isActive = true,
              allowedOptions = MemberOption.defaultKeysForRole(fallbackRole)
            )
          }
          else -> null
        }
        if (effectiveMember != null) {
          val resolvedRole = if (effectiveMember.isDelegatedAdmin() || persistedRole == "delegated_admin") {
            "delegated_admin"
          } else {
            "member"
          }
          _isAdminAuthenticated.value = true
          _adminRole.value = resolvedRole
          _currentMember.value = effectiveMember
        }
      }
      persistedRole == "google_admin" && auth.currentUser != null -> {
        val savedGoogleRole = prefs.getString(KEY_PERSISTED_GOOGLE_ROLE, "admin")?.takeIf { it.isNotBlank() } ?: "admin"
        _isAdminAuthenticated.value = true
        _adminRole.value = savedGoogleRole
        _currentMember.value = null
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
      _loginEventCounter.value = _loginEventCounter.value + 1
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
        _loginEventCounter.value = _loginEventCounter.value + 1
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
    persistCreatorSession(member, resolvedRole)
    _loginEventCounter.value = _loginEventCounter.value + 1
  }

  suspend fun restorePersistedSession(videoRepository: FirestoreVideoRepository) {
    // Always re-hydrate synchronously first so slow networks never appear logged out
    hydratePersistedSessionSync()

    val persistedRole = creatorPrefs?.getString(KEY_PERSISTED_ROLE, null)?.trim()
    val persistedMemberId = creatorPrefs?.getString(KEY_ACTIVE_CREATOR_MEMBER_ID, null)?.trim()

    if (persistedRole == "superadmin") {
      _isAdminAuthenticated.value = true
      _adminRole.value = "admin (dev)"
      _currentMember.value = null
      return
    }

    if (!persistedMemberId.isNullOrBlank()) {
      // Quietly refresh member profile from backend; NEVER clear login on network error or Render cold start
      val liveMember = runCatching {
        videoRepository.getMemberById(persistedMemberId)
      }.getOrNull()

      if (liveMember != null && liveMember.isActive) {
        val resolvedRole = if (liveMember.isDelegatedAdmin()) "delegated_admin" else "member"
        _isAdminAuthenticated.value = true
        _adminRole.value = resolvedRole
        _currentMember.value = liveMember
        persistCreatorSession(liveMember, resolvedRole)
      } else if (liveMember != null && !liveMember.isActive) {
        // Only clear session if the server explicitly confirmed the account was disabled
        _isAdminAuthenticated.value = false
        _adminRole.value = null
        _currentMember.value = null
        clearPersistedCreatorSession()
      }
    }
  }

  private fun persistSuperadminSession() {
    creatorPrefs?.edit()
      ?.remove(KEY_ACTIVE_CREATOR_MEMBER_ID)
      ?.remove(KEY_CACHED_MEMBER_JSON)
      ?.remove(KEY_CREATOR_UPLOADED_VIDEO_IDS)
      ?.remove(KEY_PERSISTED_GOOGLE_ROLE)
      ?.putString(KEY_PERSISTED_ROLE, "superadmin")
      ?.commit()
  }

  private fun persistCreatorSession(member: FirestoreMember, role: String) {
    creatorPrefs?.edit()
      ?.putString(KEY_ACTIVE_CREATOR_MEMBER_ID, member.memberId)
      ?.putString(KEY_PERSISTED_ROLE, role)
      ?.putString(KEY_CACHED_MEMBER_JSON, encodeMemberSessionPayload(member))
      ?.remove(KEY_PERSISTED_GOOGLE_ROLE)
      ?.commit()
  }

  private fun persistGoogleAdminSession(role: String) {
    creatorPrefs?.edit()
      ?.remove(KEY_ACTIVE_CREATOR_MEMBER_ID)
      ?.remove(KEY_CACHED_MEMBER_JSON)
      ?.putString(KEY_PERSISTED_ROLE, "google_admin")
      ?.putString(KEY_PERSISTED_GOOGLE_ROLE, role)
      ?.commit()
  }

  private fun clearPersistedCreatorSession() {
    creatorPrefs?.edit()
      ?.remove(KEY_ACTIVE_CREATOR_MEMBER_ID)
      ?.remove(KEY_CACHED_MEMBER_JSON)
      ?.remove(KEY_PERSISTED_ROLE)
      ?.remove(KEY_PERSISTED_GOOGLE_ROLE)
      ?.remove(KEY_CREATOR_UPLOADED_VIDEO_IDS)
      ?.commit()
  }

  fun updateCurrentMember(updatedMember: FirestoreMember) {
    if (_currentMember.value?.memberId == updatedMember.memberId) {
      val updatedRole = if (updatedMember.isDelegatedAdmin()) "delegated_admin" else "member"
      _currentMember.value = updatedMember
      _adminRole.value = updatedRole
      persistCreatorSession(updatedMember, updatedRole)
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
          persistGoogleAdminSession(_adminRole.value ?: "admin")
          _loginEventCounter.value = _loginEventCounter.value + 1
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
        if (verified && role != null) {
          persistGoogleAdminSession(role)
        }
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
          persistGoogleAdminSession("admin")
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
    // Protect persisted Superadmin/Delegated Admin/Creator sessions from background FirebaseAuth checks
    if (hasPersistedNonGoogleSession()) {
      _isLoading.value = false
      return
    }
    val hasCachedGoogleSession = creatorPrefs?.getString(KEY_PERSISTED_ROLE, null) == "google_admin"
    if (!hasCachedGoogleSession) {
      _isLoading.value = true
    }
    db.collection("admins").document(uid).get()
      .addOnSuccessListener { doc ->
        if (hasPersistedNonGoogleSession()) {
          _isLoading.value = false
          return@addOnSuccessListener
        }
        if (doc.exists()) {
          val isActive = doc.getBoolean("isActive") ?: false
          val role = doc.getString("role")
          val verified = isActive && (role == "admin" || role == "superadmin")
          _isAdminAuthenticated.value = verified
          _adminRole.value = if (verified) role else null
          if (verified && role != null) {
            persistGoogleAdminSession(role)
          } else {
            clearPersistedCreatorSession()
          }
          _isLoading.value = false
        } else {
          db.collection("admins").limit(1).get()
            .addOnSuccessListener { snapshot ->
              if (hasPersistedNonGoogleSession()) {
                _isLoading.value = false
                return@addOnSuccessListener
              }
              if (snapshot.isEmpty) {
                val data = hashMapOf(
                  "uid" to uid,
                  "role" to "admin",
                  "isActive" to true
                )
                db.collection("admins").document(uid).set(data)
                  .addOnSuccessListener {
                    if (!hasPersistedNonGoogleSession()) {
                      _isAdminAuthenticated.value = true
                      _adminRole.value = "admin"
                      persistGoogleAdminSession("admin")
                    }
                    _isLoading.value = false
                    Log.i(TAG, "Auto-bootstrapped initial admin via listener: $uid")
                  }
                  .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to auto-bootstrap admin: ${e.message}")
                    if (!hasPersistedNonGoogleSession() && !hasCachedGoogleSession) {
                      _isAdminAuthenticated.value = false
                      _adminRole.value = null
                    }
                    _isLoading.value = false
                  }
              } else {
                if (!hasPersistedNonGoogleSession()) {
                  _isAdminAuthenticated.value = false
                  _adminRole.value = null
                  clearPersistedCreatorSession()
                }
                _isLoading.value = false
              }
            }
            .addOnFailureListener { e ->
              Log.w(TAG, "Error checking admins list: ${e.message}")
              // Do not log out cached session on transient network failure
              if (!hasPersistedNonGoogleSession() && !hasCachedGoogleSession) {
                _isAdminAuthenticated.value = false
                _adminRole.value = null
              }
              _isLoading.value = false
            }
        }
      }
      .addOnFailureListener { e ->
        Log.w(TAG, "Error checking backend admin record: ${e.message}")
        // Never wipe a persisted session on offline/network error
        if (!hasPersistedNonGoogleSession() && !hasCachedGoogleSession &&
          _adminRole.value != "admin (dev)" && _adminRole.value != "member" && _adminRole.value != "delegated_admin"
        ) {
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
    appContext?.let { AppNotificationHelper.clearAdminSupportNotifiedState(it) }
  }

  companion object {
    const val ADMIN_EMAIL = "teamaltamash@gmail.com"
    const val ADMIN_MASTER_KEY = "7827128212"
    private const val CREATOR_PREFS_NAME = "vidvora_creator_prefs"
    private const val KEY_ACTIVE_CREATOR_MEMBER_ID = "active_creator_member_id"
    private const val KEY_CACHED_MEMBER_JSON = "cached_member_session_payload_v1"
    private const val KEY_PERSISTED_ROLE = "persisted_auth_role"
    private const val KEY_PERSISTED_GOOGLE_ROLE = "persisted_google_admin_role"
    private const val KEY_CREATOR_UPLOADED_VIDEO_IDS = "creator_uploaded_video_ids"
    private const val KEY_SUPERADMIN_MASTER_KEY_HASH = "superadmin_master_key_hash"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun dec(value: String): String = try {
      URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
      value
    }

    /**
     * Pure-Kotlin deterministic serializer for caching [FirestoreMember] locally in SharedPreferences
     * so Creator and Delegated Admin accounts stay logged in permanently even across offline launches.
     */
    fun encodeMemberSessionPayload(member: FirestoreMember): String {
      val optionsEncoded = member.allowedOptions.joinToString(",") { enc(it) }
      val pairs = listOf(
        "memberId" to enc(member.memberId),
        "name" to enc(member.name),
        "emailOrPhone" to enc(member.emailOrPhone),
        "passkey" to enc(member.passkey),
        "role" to enc(member.role),
        "isActive" to member.isActive.toString(),
        "totalEarning" to member.totalEarning.toString(),
        "creditedViews" to member.creditedViews.toString(),
        "viewRatePer1000" to member.viewRatePer1000.toString(),
        "allowedOptions" to optionsEncoded,
        "lastViewsUpdateCycleKey" to enc(member.lastViewsUpdateCycleKey),
        "lastViewsUpdatedAtMs" to member.lastViewsUpdatedAtMs.toString(),
        "referralCode" to enc(member.referralCode),
        "referredByMemberId" to enc(member.referredByMemberId),
        "referredByName" to enc(member.referredByName),
        "referralBonusEarning" to member.referralBonusEarning.toString(),
        "referralCommissionGenerated" to member.referralCommissionGenerated.toString()
      )
      return pairs.joinToString("\n") { (k, v) -> "$k=$v" }
    }

    fun decodeMemberSessionPayload(raw: String?): FirestoreMember? {
      if (raw.isNullOrBlank()) return null
      return try {
        val map = mutableMapOf<String, String>()
        raw.lineSequence().forEach { line ->
          val idx = line.indexOf('=')
          if (idx > 0) {
            map[line.substring(0, idx)] = line.substring(idx + 1)
          }
        }
        val memberId = dec(map["memberId"].orEmpty()).trim()
        if (memberId.isBlank()) return null
        val role = MemberRole.normalize(dec(map["role"].orEmpty()))
        val parsedOptions = map["allowedOptions"]
          ?.split(',')
          ?.map { dec(it).trim() }
          ?.filter { it.isNotBlank() }
          ?.let { MemberOption.sanitizeKeys(it) }
          ?.takeIf { it.isNotEmpty() }
          ?: MemberOption.defaultKeysForRole(role)
        FirestoreMember(
          memberId = memberId,
          name = dec(map["name"].orEmpty()).ifBlank { if (role == MemberRole.ADMIN) "Admin" else "Creator" },
          emailOrPhone = dec(map["emailOrPhone"].orEmpty()),
          passkey = dec(map["passkey"].orEmpty()),
          role = role,
          isActive = map["isActive"]?.toBooleanStrictOrNull() ?: true,
          totalEarning = map["totalEarning"]?.toDoubleOrNull() ?: 0.0,
          creditedViews = map["creditedViews"]?.toLongOrNull() ?: 0L,
          viewRatePer1000 = map["viewRatePer1000"]?.toDoubleOrNull() ?: 190.0,
          allowedOptions = parsedOptions,
          lastViewsUpdateCycleKey = dec(map["lastViewsUpdateCycleKey"].orEmpty()),
          lastViewsUpdatedAtMs = map["lastViewsUpdatedAtMs"]?.toLongOrNull() ?: 0L,
          referralCode = dec(map["referralCode"].orEmpty()),
          referredByMemberId = dec(map["referredByMemberId"].orEmpty()),
          referredByName = dec(map["referredByName"].orEmpty()),
          referralBonusEarning = (map["referralBonusEarning"]?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
          referralCommissionGenerated = (map["referralCommissionGenerated"]?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        )
      } catch (_: Exception) {
        null
      }
    }
  }
}
