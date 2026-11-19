package com.example.data.remote

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.AppUpdateConfig
import com.example.data.model.FirestoreAccessLink
import com.example.data.model.FirestoreAppInstall
import com.example.data.model.FirestoreCreatorEvent
import com.example.data.model.FirestoreMember
import com.example.data.model.FirestoreNotification
import com.example.data.model.FirestoreSupportChat
import com.example.data.model.FirestoreSupportMessage
import com.example.data.model.FirestoreVideo
import com.example.data.model.FirestoreWithdrawalRequest
import com.example.data.model.MemberOption
import com.example.data.model.MemberRole
import com.example.data.model.SupportChatStatus
import com.example.data.model.SupportSenderRole
import com.example.data.model.TelegramCategoryItem
import com.example.data.model.TelegramChannelCategory
import com.example.data.model.TelegramConfig
import com.example.data.model.WithdrawalStatus
import com.example.data.repository.FirestoreVideoRepository
import com.google.firebase.Timestamp
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "BackendDbClient"

data class RemoteDoc(
  val id: String,
  val data: JSONObject
)

data class RemoteResolveVideoResponse(
  val ok: Boolean,
  val backend: String,
  val status: String,
  val video: JSONObject?,
  val error: String?
)

/**
 * Lightweight HTTP client for VidVora's Render + PostgreSQL (Neon/Supabase) backend API.
 * When DATABASE_URL is configured on Render, all app reads & writes run on unlimited
 * PostgreSQL with zero Firebase Firestore quota usage.
 */
class BackendDbClient(
  private val baseUrlProvider: () -> String = { FirestoreVideoRepository.DEFAULT_AUTH_BACKEND_BASE_URL }
) {

  @Volatile
  private var cachedPostgresActive: Boolean? = null

  @Volatile
  private var cachedStatusCheckedAtMs: Long = 0L

  private fun baseOrigin(): String =
    baseUrlProvider().trim().trimEnd('/').ifBlank {
      FirestoreVideoRepository.DEFAULT_AUTH_BACKEND_BASE_URL
    }

  private fun markBackendFromResponse(backend: String?) {
    val clean = backend?.trim()?.lowercase().orEmpty()
    if (clean == "postgresql") {
      cachedPostgresActive = true
      cachedStatusCheckedAtMs = System.currentTimeMillis()
    } else if (clean == "firestore") {
      cachedPostgresActive = false
      cachedStatusCheckedAtMs = System.currentTimeMillis()
    }
  }

  /**
   * Checks whether Render is actively backed by PostgreSQL (Neon/Supabase).
   * Caches positive status for 10 minutes and negative/offline status for 30 seconds.
   */
  suspend fun isPostgresActive(forceRefresh: Boolean = false): Boolean {
    val now = System.currentTimeMillis()
    val cached = cachedPostgresActive
    if (!forceRefresh && cached != null) {
      val ttlMs = if (cached) 10 * 60 * 1000L else 30_000L
      if ((now - cachedStatusCheckedAtMs) < ttlMs) {
        return cached
      }
    }
    return withContext(Dispatchers.IO) {
      try {
        val url = URL("${baseOrigin()}/api/status")
        val conn = (url.openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = 8_000
          readTimeout = 12_000
          setRequestProperty("Accept", "application/json")
          setRequestProperty("User-Agent", "VidVoraApp/1.0 (Android)")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code in 200..299 && text.isNotBlank()) {
          val json = JSONObject(text)
          val active = json.optBoolean("postgresActive", false) ||
            json.optString("databaseBackend", "").equals("postgresql", ignoreCase = true)
          cachedPostgresActive = active
          cachedStatusCheckedAtMs = System.currentTimeMillis()
          active
        } else {
          cachedPostgresActive = false
          cachedStatusCheckedAtMs = System.currentTimeMillis()
          false
        }
      } catch (e: Exception) {
        Log.w(TAG, "Backend /api/status check skipped: ${e.message}")
        cachedPostgresActive ?: false
      }
    }
  }

  suspend fun resolveVideo(token: String, currentMemberId: String?): RemoteResolveVideoResponse? =
    withContext(Dispatchers.IO) {
      try {
        val body = JSONObject().apply {
          put("token", token.trim())
          if (!currentMemberId.isNullOrBlank()) {
            put("currentMemberId", currentMemberId.trim())
          }
        }
        val (code, json) = postJson("/api/resolve-video", body, connectTimeoutMs = 10_000, readTimeoutMs = 18_000)
        if (code in 200..299) {
          val backend = json.optString("backend", "")
          markBackendFromResponse(backend)
          RemoteResolveVideoResponse(
            ok = json.optBoolean("ok", false),
            backend = backend,
            status = json.optString("status", "not_found"),
            video = json.optJSONObject("video"),
            error = json.optString("error", "").takeIf { it.isNotBlank() }
          )
        } else {
          null
        }
      } catch (e: Exception) {
        Log.w(TAG, "resolveVideo request skipped: ${e.message}")
        null
      }
    }

  suspend fun creditView(videoId: String, uploadedBy: String, currentMemberId: String?): Boolean =
    withContext(Dispatchers.IO) {
      try {
        val body = JSONObject().apply {
          put("videoId", videoId.trim())
          put("uploadedBy", uploadedBy.trim())
          if (!currentMemberId.isNullOrBlank()) {
            put("currentMemberId", currentMemberId.trim())
          }
        }
        val (code, json) = postJson("/api/credit-view", body, connectTimeoutMs = 8_000, readTimeoutMs = 12_000)
        if (code in 200..299) {
          markBackendFromResponse(json.optString("backend", ""))
          json.optBoolean("ok", false)
        } else {
          false
        }
      } catch (e: Exception) {
        Log.w(TAG, "creditView skipped: ${e.message}")
        false
      }
    }

  suspend fun getCollection(
    collection: String,
    subpath: String = "",
    isActive: Boolean? = null,
    field: String? = null,
    value: String? = null,
    field2: String? = null,
    value2: String? = null,
    limit: Int? = null
  ): List<RemoteDoc>? = withContext(Dispatchers.IO) {
    try {
      val params = mutableListOf<String>()
      if (subpath.isNotBlank()) params.add("subpath=${encode(subpath)}")
      if (isActive != null) params.add("isActive=$isActive")
      if (!field.isNullOrBlank() && value != null) {
        params.add("field=${encode(field)}")
        params.add("value=${encode(value)}")
      }
      if (!field2.isNullOrBlank() && value2 != null) {
        params.add("field2=${encode(field2)}")
        params.add("value2=${encode(value2)}")
      }
      if (limit != null && limit > 0) params.add("limit=$limit")
      val qs = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
      val safeCol = encode(collection.ifBlank { "_sub" })
      val (code, json) = getJson("/api/collection/$safeCol$qs")
      if (code in 200..299 && json.optBoolean("ok", false)) {
        markBackendFromResponse(json.optString("backend", ""))
        val arr = json.optJSONArray("docs") ?: JSONArray()
        val list = ArrayList<RemoteDoc>(arr.length())
        for (i in 0 until arr.length()) {
          val item = arr.optJSONObject(i) ?: continue
          val id = item.optString("id", "")
          val data = item.optJSONObject("data") ?: JSONObject()
          if (id.isNotBlank()) {
            list.add(RemoteDoc(id = id, data = data))
          }
        }
        list
      } else {
        null
      }
    } catch (e: Exception) {
      Log.w(TAG, "getCollection($collection) warning: ${e.message}")
      null
    }
  }

  suspend fun getDoc(
    collection: String,
    docId: String,
    subpath: String = ""
  ): Pair<Boolean, JSONObject?>? = withContext(Dispatchers.IO) {
    try {
      val qs = if (subpath.isNotBlank()) "?subpath=${encode(subpath)}" else ""
      val (code, json) = getJson("/api/doc/${encode(collection)}/${encode(docId)}$qs")
      if (code in 200..299 && json.optBoolean("ok", false)) {
        markBackendFromResponse(json.optString("backend", ""))
        val exists = json.optBoolean("exists", false)
        val data = if (exists) json.optJSONObject("data") else null
        Pair(exists, data)
      } else {
        null
      }
    } catch (e: Exception) {
      Log.w(TAG, "getDoc($collection/$docId) warning: ${e.message}")
      null
    }
  }

  suspend fun writeDoc(
    collection: String,
    docId: String,
    action: String,
    data: Map<String, Any?>? = null,
    subpath: String = ""
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      val qs = if (subpath.isNotBlank()) "?subpath=${encode(subpath)}" else ""
      val payload = JSONObject().apply {
        put("action", action)
        if (data != null) {
          put("data", mapToJsonObject(data))
        }
      }
      val (code, json) = postJson("/api/doc/${encode(collection)}/${encode(docId)}$qs", payload)
      if (code in 200..299 && json.optBoolean("ok", false)) {
        markBackendFromResponse(json.optString("backend", ""))
        true
      } else {
        Log.w(TAG, "writeDoc($action $collection/$docId) failed HTTP $code: ${json.optString("error")}")
        false
      }
    } catch (e: Exception) {
      Log.w(TAG, "writeDoc($action $collection/$docId) error: ${e.message}")
      false
    }
  }

  private fun getJson(pathAndQuery: String, connectTimeoutMs: Int = 10_000, readTimeoutMs: Int = 20_000): Pair<Int, JSONObject> {
    val url = URL("${baseOrigin()}$pathAndQuery")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = connectTimeoutMs
      readTimeout = readTimeoutMs
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "VidVoraApp/1.0 (Android)")
    }
    return try {
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      val parsed = try {
        if (text.isNotBlank()) JSONObject(text) else JSONObject()
      } catch (_: Exception) {
        JSONObject().put("error", text.take(200))
      }
      Pair(code, parsed)
    } finally {
      conn.disconnect()
    }
  }

  private fun postJson(
    pathAndQuery: String,
    jsonBody: JSONObject,
    connectTimeoutMs: Int = 12_000,
    readTimeoutMs: Int = 25_000
  ): Pair<Int, JSONObject> {
    val url = URL("${baseOrigin()}$pathAndQuery")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = connectTimeoutMs
      readTimeout = readTimeoutMs
      doOutput = true
      setRequestProperty("Content-Type", "application/json; charset=UTF-8")
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "VidVoraApp/1.0 (Android)")
    }
    return try {
      conn.outputStream.use { out ->
        out.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
      }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      val parsed = try {
        if (text.isNotBlank()) JSONObject(text) else JSONObject()
      } catch (_: Exception) {
        JSONObject().put("error", text.take(200))
      }
      Pair(code, parsed)
    } finally {
      conn.disconnect()
    }
  }

  private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

  companion object {
    fun serverTimestampMarker(): Map<String, Any> = mapOf("__vidvora_server_ts__" to true)
    fun incrementMarker(delta: Number): Map<String, Any> = mapOf("__vidvora_inc__" to delta)

    fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
      val obj = JSONObject()
      for ((k, v) in map) {
        obj.put(k, anyToJsonValue(v))
      }
      return obj
    }

    private fun anyToJsonValue(value: Any?): Any {
      if (value == null) return JSONObject.NULL
      return when (value) {
        is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long, is Double, is Float -> value
        is Number -> value.toDouble()
        is Timestamp -> JSONObject().put("__vidvora_ts_ms__", value.toDate().time)
        is Map<*, *> -> {
          val obj = JSONObject()
          for ((mk, mv) in value) {
            if (mk != null) obj.put(mk.toString(), anyToJsonValue(mv))
          }
          obj
        }
        is Collection<*> -> {
          val arr = JSONArray()
          for (item in value) {
            arr.put(anyToJsonValue(item))
          }
          arr
        }
        else -> {
          val className = value.javaClass.name
          if (className.contains("ServerTimestamp", ignoreCase = true)) {
            JSONObject().put("__vidvora_server_ts__", true)
          } else {
            value.toString()
          }
        }
      }
    }

    fun optNullableString(json: JSONObject, key: String): String? {
      if (!json.has(key) || json.isNull(key)) return null
      return json.optString(key, "").takeIf { it.isNotBlank() }
    }

    fun optStringClean(json: JSONObject, key: String, fallback: String = ""): String {
      if (!json.has(key) || json.isNull(key)) return fallback
      return json.optString(key, fallback)
    }

    fun optTimestamp(json: JSONObject, key: String, fallbackNow: Boolean = true): Timestamp {
      val ms = optTimestampMillis(json, key)
      if (ms != null) {
        val safe = ms.coerceAtLeast(0L)
        return Timestamp(safe / 1000L, ((safe % 1000L) * 1_000_000L).toInt())
      }
      return if (fallbackNow) Timestamp.now() else Timestamp(0, 0)
    }

    fun optNullableTimestamp(json: JSONObject, key: String): Timestamp? {
      val ms = optTimestampMillis(json, key) ?: return null
      val safe = ms.coerceAtLeast(0L)
      return Timestamp(safe / 1000L, ((safe % 1000L) * 1_000_000L).toInt())
    }

    fun optTimestampMillis(json: JSONObject, key: String): Long? {
      if (!json.has(key) || json.isNull(key)) return null
      val raw = json.opt(key) ?: return null
      return when (raw) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        is JSONObject -> {
          val sec = raw.optLong("seconds", -1L)
          if (sec >= 0L) sec * 1000L else null
        }
        else -> null
      }
    }

    fun parseVideo(doc: RemoteDoc): FirestoreVideo {
      val d = doc.data
      return FirestoreVideo(
        videoId = optStringClean(d, "videoId", doc.id).ifBlank { doc.id },
        title = optStringClean(d, "title", ""),
        description = optStringClean(d, "description", ""),
        thumbnailPath = optNullableString(d, "thumbnailPath"),
        storagePath = optStringClean(d, "storagePath", ""),
        createdAt = optTimestamp(d, "createdAt", fallbackNow = true),
        isActive = d.optBoolean("isActive", true),
        totalViews = d.optLong("totalViews", 0L),
        uploadedBy = optStringClean(d, "uploadedBy", "admin").ifBlank { "admin" },
        uploaderName = optStringClean(d, "uploaderName", "Admin").ifBlank { "Admin" }
      )
    }

    fun parseAccessLink(doc: RemoteDoc): FirestoreAccessLink {
      val d = doc.data
      return FirestoreAccessLink(
        linkId = optStringClean(d, "linkId", doc.id).ifBlank { doc.id },
        videoId = optStringClean(d, "videoId", ""),
        tokenHash = optStringClean(d, "tokenHash", ""),
        isActive = d.optBoolean("isActive", true),
        createdAt = optTimestamp(d, "createdAt", fallbackNow = true),
        expiresAt = optNullableTimestamp(d, "expiresAt")
      )
    }

    fun parseMember(doc: RemoteDoc): FirestoreMember {
      val d = doc.data
      val normalizedRole = MemberRole.normalize(optNullableString(d, "role"))
      val resolvedOptions = if (d.has("allowedOptions") && !d.isNull("allowedOptions")) {
        val arr = d.optJSONArray("allowedOptions")
        val raw = mutableListOf<String>()
        if (arr != null) {
          for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotBlank()) raw.add(s)
          }
        }
        MemberOption.sanitizeKeys(raw)
      } else {
        MemberOption.defaultKeysForRole(normalizedRole)
      }

      val hourlyObj = d.optJSONObject("hourlyViews")
      val parsedHourly = buildMap<String, Long> {
        if (hourlyObj != null) {
          val keys = hourlyObj.keys()
          while (keys.hasNext()) {
            val k = keys.next().trim()
            val count = hourlyObj.optLong(k, 0L).coerceAtLeast(0L)
            if (k.length == 10 && k.all { it.isDigit() } && count > 0L) {
              put(k, count)
            }
          }
        }
      }

      val rate = d.optDouble("viewRatePer1000", Double.NaN)
      val safeRate = if (!rate.isNaN() && rate >= 0.0) rate else FirestoreVideoRepository.DEFAULT_VIEW_RATE_PER_1000

      return FirestoreMember(
        memberId = optStringClean(d, "memberId", doc.id).ifBlank { doc.id },
        name = optStringClean(d, "name", ""),
        emailOrPhone = optStringClean(d, "emailOrPhone", ""),
        passkey = optStringClean(d, "passkey", ""),
        role = normalizedRole,
        isActive = d.optBoolean("isActive", true),
        createdAt = optTimestamp(d, "createdAt", fallbackNow = false),
        totalEarning = d.optDouble("totalEarning", 0.0).takeIf { !it.isNaN() } ?: 0.0,
        creditedViews = d.optLong("creditedViews", 0L).coerceAtLeast(0L),
        viewRatePer1000 = safeRate,
        allowedOptions = resolvedOptions,
        hourlyViews = parsedHourly,
        lastViewsUpdateCycleKey = optStringClean(d, "lastViewsUpdateCycleKey", ""),
        lastViewsUpdatedAtMs = d.optLong("lastViewsUpdatedAtMs", 0L).coerceAtLeast(0L)
      )
    }

    fun parseWithdrawal(doc: RemoteDoc): FirestoreWithdrawalRequest {
      val d = doc.data
      return FirestoreWithdrawalRequest(
        requestId = optStringClean(d, "requestId", doc.id).ifBlank { doc.id },
        memberId = optStringClean(d, "memberId", ""),
        creatorName = optStringClean(d, "creatorName", ""),
        creatorEmail = optStringClean(d, "creatorEmail", ""),
        amount = d.optDouble("amount", 0.0).takeIf { !it.isNaN() } ?: 0.0,
        upiId = optStringClean(d, "upiId", ""),
        status = optStringClean(d, "status", WithdrawalStatus.PENDING).ifBlank { WithdrawalStatus.PENDING },
        adminNote = optStringClean(d, "adminNote", ""),
        requestedAt = optTimestamp(d, "requestedAt", fallbackNow = true),
        updatedAt = optNullableTimestamp(d, "updatedAt")
      )
    }

    fun parseNotification(doc: RemoteDoc): FirestoreNotification? {
      val d = doc.data
      val isActive = d.optBoolean("isActive", true)
      if (!isActive) return null
      return FirestoreNotification(
        notificationId = optStringClean(d, "notificationId", doc.id).ifBlank { doc.id },
        title = optStringClean(d, "title", ""),
        message = optStringClean(d, "message", ""),
        imageUrl = optStringClean(d, "imageUrl", ""),
        actionUrl = optStringClean(d, "actionUrl", ""),
        sentBy = optStringClean(d, "sentBy", "Admin").ifBlank { "Admin" },
        createdAt = optTimestamp(d, "createdAt", fallbackNow = true),
        isActive = isActive
      )
    }

    fun parseCreatorEvent(doc: RemoteDoc): FirestoreCreatorEvent {
      val d = doc.data
      val createdAt = optTimestamp(d, "createdAt", fallbackNow = true)
      val updatedAt = optNullableTimestamp(d, "updatedAt") ?: createdAt
      return FirestoreCreatorEvent(
        eventId = optStringClean(d, "eventId", doc.id).ifBlank { doc.id },
        title = optStringClean(d, "title", ""),
        description = optStringClean(d, "description", ""),
        giftLabel = optStringClean(d, "giftLabel", ""),
        imageUrl = optStringClean(d, "imageUrl", ""),
        createdBy = optStringClean(d, "createdBy", "Superadmin").ifBlank { "Superadmin" },
        createdAt = createdAt,
        updatedAt = updatedAt,
        isActive = d.optBoolean("isActive", true)
      )
    }

    fun parseAppInstall(doc: RemoteDoc): FirestoreAppInstall {
      val d = doc.data
      return FirestoreAppInstall(
        installId = optStringClean(d, "installId", doc.id).ifBlank { doc.id },
        firstInstalledAt = optTimestamp(d, "firstInstalledAt", fallbackNow = true),
        lastSeenAt = optTimestamp(d, "lastSeenAt", fallbackNow = true),
        appVersion = optStringClean(d, "appVersion", "1.0").ifBlank { "1.0" },
        deviceLabel = optStringClean(d, "deviceLabel", "Android Device").ifBlank { "Android Device" },
        androidVersion = optStringClean(d, "androidVersion", "Android").ifBlank { "Android" },
        sessionCount = d.optLong("sessionCount", 1L),
        isMarkedUninstalled = d.optBoolean("isMarkedUninstalled", false)
      )
    }

    fun parseSupportChat(doc: RemoteDoc): FirestoreSupportChat {
      val d = doc.data
      return FirestoreSupportChat(
        chatId = optStringClean(d, "chatId", doc.id).ifBlank { doc.id },
        userName = optStringClean(d, "userName", "User").ifBlank { "User" },
        deviceLabel = optStringClean(d, "deviceLabel", "Android Device").ifBlank { "Android Device" },
        lastMessage = optStringClean(d, "lastMessage", ""),
        lastSenderRole = optStringClean(d, "lastSenderRole", SupportSenderRole.USER).ifBlank { SupportSenderRole.USER },
        lastMessageAt = optTimestamp(d, "lastMessageAt", fallbackNow = true),
        createdAt = optTimestamp(d, "createdAt", fallbackNow = true),
        unreadByAdminCount = d.optLong("unreadByAdminCount", 0L),
        unreadByUserCount = d.optLong("unreadByUserCount", 0L),
        status = optStringClean(d, "status", SupportChatStatus.OPEN).ifBlank { SupportChatStatus.OPEN }
      )
    }

    fun parseSupportMessage(doc: RemoteDoc, fallbackChatId: String): FirestoreSupportMessage {
      val d = doc.data
      return FirestoreSupportMessage(
        messageId = optStringClean(d, "messageId", doc.id).ifBlank { doc.id },
        chatId = optStringClean(d, "chatId", fallbackChatId).ifBlank { fallbackChatId },
        senderRole = optStringClean(d, "senderRole", SupportSenderRole.USER).ifBlank { SupportSenderRole.USER },
        senderName = optStringClean(d, "senderName", "User").ifBlank { "User" },
        text = optStringClean(d, "text", ""),
        createdAt = optTimestamp(d, "createdAt", fallbackNow = true)
      )
    }

    fun parseTelegramConfig(json: JSONObject?): TelegramConfig {
      val mainRaw = json?.let { optNullableString(it, "telegramLink") }
      val mainLink = if (!mainRaw.isNullOrBlank()) mainRaw.trim() else FirestoreVideoRepository.DEFAULT_TELEGRAM_LINK
      val creatorBotLink = FirestoreVideoRepository.normalizeTelegramUrl(
        json?.let { optStringClean(it, "creatorBotLink") }.orEmpty()
      )
      val financeBotLink = FirestoreVideoRepository.normalizeTelegramUrl(
        json?.let { optStringClean(it, "financeBotLink") }.orEmpty()
      )
      val shareLinkBaseUrl = FirestoreVideoRepository.normalizeShareLinkBaseUrl(
        json?.let { optStringClean(it, "shareLinkBaseUrl") }.orEmpty()
      )
      val rawLinks = json?.optJSONObject("categoryLinks")
      val categoryMap = TelegramChannelCategory.entries.associate { category ->
        category.key to (rawLinks?.optString(category.key, "")?.trim().orEmpty())
      }
      val rawNames = json?.optJSONObject("categoryNames")
      val categoryNamesMap = buildMap {
        TelegramChannelCategory.entries.forEach { category ->
          val overrideName = rawNames?.optString(category.key, "")
            ?.trim()
            ?.take(FirestoreVideoRepository.MAX_CATEGORY_NAME_LENGTH)
            .orEmpty()
          if (overrideName.isNotBlank() && overrideName != category.label) {
            put(category.key, overrideName)
          }
        }
      }
      val rawDeleted = json?.optJSONArray("deletedCategoryKeys")
      val deletedKeys = buildList {
        if (rawDeleted != null) {
          for (i in 0 until rawDeleted.length()) {
            val k = rawDeleted.optString(i, "").trim()
            if (TelegramChannelCategory.fromKey(k) != null && !contains(k)) {
              add(k)
            }
          }
        }
      }
      val rawCustom = json?.optJSONArray("customCategories")
      val customCategories = buildList {
        if (rawCustom != null) {
          for (i in 0 until rawCustom.length()) {
            val entry = rawCustom.optJSONObject(i) ?: continue
            val id = entry.optString("id", "").trim()
            val name = entry.optString("name", "").trim()
            val link = entry.optString("link", "").trim()
            if (id.isNotBlank() && name.isNotBlank()) {
              add(TelegramCategoryItem(id = id, name = name, link = link, isBuiltIn = false))
            }
          }
        }
      }
      return TelegramConfig(
        mainLink = mainLink,
        creatorBotLink = creatorBotLink,
        financeBotLink = financeBotLink,
        shareLinkBaseUrl = shareLinkBaseUrl,
        categoryLinks = categoryMap,
        categoryNames = categoryNamesMap,
        deletedCategoryKeys = deletedKeys,
        customCategories = customCategories
      )
    }

    fun parseAppUpdateConfig(json: JSONObject?): AppUpdateConfig {
      if (json == null) {
        return AppUpdateConfig(
          latestVersionCode = BuildConfig.VERSION_CODE,
          latestVersionName = BuildConfig.VERSION_NAME,
          apkUrl = FirestoreVideoRepository.DEFAULT_APK_DOWNLOAD_URL
        )
      }
      return AppUpdateConfig(
        isEnabled = json.optBoolean("isEnabled", false),
        latestVersionCode = json.optInt("latestVersionCode", BuildConfig.VERSION_CODE).coerceAtLeast(1),
        latestVersionName = optStringClean(json, "latestVersionName", BuildConfig.VERSION_NAME)
          .trim().ifBlank { BuildConfig.VERSION_NAME },
        title = optStringClean(json, "title", "New VidVora Update Available")
          .trim().ifBlank { "New VidVora Update Available" },
        message = optStringClean(json, "message", "Faster video playback and improvements are ready. Update now!").trim(),
        apkUrl = optStringClean(json, "apkUrl", FirestoreVideoRepository.DEFAULT_APK_DOWNLOAD_URL)
          .trim().ifBlank { FirestoreVideoRepository.DEFAULT_APK_DOWNLOAD_URL },
        isForceUpdate = json.optBoolean("isForceUpdate", false)
      )
    }
  }
}
