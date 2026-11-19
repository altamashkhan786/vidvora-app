package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.R
import com.example.data.model.AppUpdateConfig
import com.example.data.model.CreatorEmailChangeResult
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
import com.example.data.model.TokenValidationResult
import com.example.data.model.VideoToken
import com.example.data.model.WithdrawalStatus
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "FirestoreVideoRepo"

data class VideoCreationResult(
  val video: FirestoreVideo,
  val accessLink: FirestoreAccessLink,
  val rawToken: String,
  val shareableLink: String
)

class FirestoreVideoRepository(
  private val db: FirebaseFirestore,
  private val storage: FirebaseStorage,
  private val functions: FirebaseFunctions,
  private val appContext: Context? = null
) {

  constructor(context: Context) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    ),
    FirebaseStorage.getInstance(),
    FirebaseFunctions.getInstance(),
    context.applicationContext
  )

  private val backgroundIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private fun prewarmStreamEndpoint(streamUrl: String) {
    if (!streamUrl.startsWith("https://", ignoreCase = true) ||
      !streamUrl.contains("/streamPublicVideo", ignoreCase = true)
    ) {
      return
    }
    backgroundIoScope.launch {
      try {
        val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
          requestMethod = "HEAD"
          connectTimeout = 4_000
          readTimeout = 4_000
          setRequestProperty("User-Agent", "VidVoraPlayer/1.0 (Android)")
        }
        conn.responseCode
        conn.disconnect()
      } catch (_: Exception) {
        // Best-effort TCP/cache warmup only
      }
    }
  }

  fun extractToken(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""

    return try {
      val uri = Uri.parse(trimmed)
      val directMedia = isDirectMediaUri(uri)
      val linkParam = uri.getQueryParameter("link")
        ?: uri.getQueryParameter("v")
        ?: uri.getQueryParameter("video")
        ?: if (!directMedia) uri.getQueryParameter("token") else null
      if (!linkParam.isNullOrBlank()) {
        return linkParam.trim()
      }

      if (trimmed.startsWith("vidvora://", ignoreCase = true)) {
        val lastPathSegment = uri.lastPathSegment
        if (!lastPathSegment.isNullOrBlank() &&
          !lastPathSegment.equals("watch", ignoreCase = true) &&
          !lastPathSegment.equals("index.html", ignoreCase = true)
        ) {
          return lastPathSegment.trim()
        }
        return trimmed
      }

      val lastPathSegment = uri.lastPathSegment
      if (!lastPathSegment.isNullOrBlank() &&
        (lastPathSegment.startsWith("GM-", ignoreCase = true) ||
          lastPathSegment.startsWith("link_", ignoreCase = true) ||
          lastPathSegment.startsWith("vid_", ignoreCase = true))
      ) {
        return lastPathSegment.trim()
      }

      // Extract embedded code/link if user pasted a multi-line WhatsApp message
      val codeRegex = Regex("""(GM-[A-Z0-9-]{4,}|link_[a-zA-Z0-9_]+|vid_[a-zA-Z0-9_]+)""", RegexOption.IGNORE_CASE)
      val match = codeRegex.find(trimmed)
      if (match != null && trimmed.contains(" ")) {
        return match.value.trim()
      }

      trimmed
    } catch (_: Exception) {
      trimmed
    }
  }

  fun hashToken(token: String): String {
    val clean = token.trim().uppercase()
    val bytes = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }

  /**
   * Generates a unique random code for shareable video links.
   */
  fun generateCryptographicToken(): String {
    val secureRandom = SecureRandom()
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val part1 = (1..4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
    val part2 = (1..4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
    val part3 = (1..4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
    return "GM-$part1-$part2-$part3"
  }

  suspend fun saveLocalMediaCopy(
    fileName: String,
    sourceUri: Uri?,
    onProgress: ((Float) -> Unit)? = null
  ): String? = withContext(Dispatchers.IO) {
    val ctx = appContext ?: return@withContext sourceUri?.toString()
    if (sourceUri == null) return@withContext null
    if (sourceUri.scheme.equals("file", ignoreCase = true)) {
      val path = sourceUri.path
      if (!path.isNullOrBlank() && File(path).exists()) {
        return@withContext sourceUri.toString()
      }
      return@withContext null
    }
    try {
      val mediaDir = File(ctx.filesDir, "media").apply { mkdirs() }
      val destFile = File(mediaDir, fileName)
      ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
        destFile.outputStream().use { output ->
          val buffer = ByteArray(16384)
          var totalBytesCopied = 0L
          val fileSize = try {
            ctx.contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L
          } catch (_: Exception) { -1L }
          var bytesRead: Int
          while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalBytesCopied += bytesRead
            if (fileSize > 0) {
              onProgress?.invoke(((totalBytesCopied.toFloat() / fileSize.toFloat()) * 100f).coerceIn(0f, 100f))
            }
          }
          output.flush()
        }
      } ?: return@withContext null
      if (!destFile.exists() || destFile.length() <= 0L) {
        return@withContext null
      }
      onProgress?.invoke(100f)
      Uri.fromFile(destFile).toString()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to save local media copy for $fileName: ${e.message}")
      null
    }
  }

  private fun compressBitmapToJpegBytes(
    bitmap: Bitmap,
    maxDim: Int = 1280,
    maxBytes: Int = 280_000,
    initialQuality: Int = 90,
    minQuality: Int = 55,
    hardCapBytes: Int = 340_000
  ): ByteArray? {
    return try {
      val scale = minOf(
        1f,
        maxDim.toFloat() / bitmap.width.coerceAtLeast(1),
        maxDim.toFloat() / bitmap.height.coerceAtLeast(1)
      )
      val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
          bitmap,
          (bitmap.width * scale).toInt().coerceAtLeast(1),
          (bitmap.height * scale).toInt().coerceAtLeast(1),
          true
        )
      } else {
        bitmap
      }
      var quality = initialQuality.coerceIn(40, 96)
      var bytes: ByteArray
      do {
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        bytes = baos.toByteArray()
        quality -= 10
      } while (bytes.size > maxBytes && quality >= minQuality)
      if (scaled !== bitmap) scaled.recycle()
      bytes.takeIf { it.size <= hardCapBytes }
    } catch (_: Exception) {
      null
    }
  }

  suspend fun extractVideoThumbnailJpegBytes(
    videoUri: Uri? = null,
    remoteStreamUrl: String? = null
  ): ByteArray? {
    val ctx = appContext
    val cleanRemote = remoteStreamUrl?.trim().orEmpty()
    val timeoutMs = 5000L
    return withTimeoutOrNull(timeoutMs) {
      withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
          when {
            videoUri != null && ctx != null -> {
              retriever.setDataSource(ctx, videoUri)
            }
            cleanRemote.startsWith("http://", ignoreCase = true) ||
              cleanRemote.startsWith("https://", ignoreCase = true) -> {
              retriever.setDataSource(cleanRemote, emptyMap())
            }
            else -> return@withContext null
          }
          val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
              1_000_000L,
              MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
              1280,
              1280
            ) ?: retriever.getScaledFrameAtTime(
              0L,
              MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
              1280,
              1280
            )
          } else {
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
              ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          } ?: return@withContext null
          val compressed = compressBitmapToJpegBytes(
            bitmap = frame,
            maxDim = 1280,
            maxBytes = 280_000,
            initialQuality = 90,
            minQuality = 55,
            hardCapBytes = 340_000
          )
          frame.recycle()
          compressed
        } catch (e: Throwable) {
          Log.w(TAG, "Auto-thumbnail extraction skipped: ${e.message}")
          null
        } finally {
          try {
            retriever.release()
          } catch (_: Exception) {
          }
        }
      }
    }
  }

  suspend fun resolveFallbackVideoThumbnail(
    videoId: String,
    videoUri: Uri? = null,
    remoteStreamUrl: String? = null,
    thumbnailUri: Uri? = null
  ): String? {
    if (thumbnailUri != null) {
      return encodeCompressedImageDataUri(thumbnailUri, maxDim = 1280, maxBytes = 280_000, hardCapBytes = 340_000)
        ?: saveLocalMediaCopy("${videoId}_thumb.jpg", thumbnailUri)
    }
    val frameBytes = extractVideoThumbnailJpegBytes(videoUri, remoteStreamUrl) ?: return null
    return encodeJpegBytesAsDataUrl(frameBytes)
  }

  private fun normalizeMediaUrl(rawPath: String): String {
    val trimmed = rawPath.trim()
    if (trimmed.isBlank()) return ""
    if (trimmed.startsWith("http://", ignoreCase = true) ||
      trimmed.startsWith("https://", ignoreCase = true) ||
      trimmed.startsWith("file://", ignoreCase = true) ||
      trimmed.startsWith("content://", ignoreCase = true) ||
      trimmed.startsWith("gs://", ignoreCase = true)
    ) {
      return trimmed
    }
    if (trimmed.startsWith("/data/") || trimmed.startsWith("/storage/")) {
      return "file://$trimmed"
    }
    if (!trimmed.startsWith("/") && trimmed.contains(".")) {
      return "https://$trimmed"
    }
    return trimmed
  }

  private fun isPlayableResolvedUrl(url: String, videoId: String? = null): String? {
    val normalized = normalizeMediaUrl(url)
    if (normalized.startsWith("http://", ignoreCase = true) || normalized.startsWith("https://", ignoreCase = true)) {
      return normalized
    }
    if (normalized.startsWith("file://", ignoreCase = true)) {
      val path = Uri.parse(normalized).path
      if (!path.isNullOrBlank() && File(path).exists() && File(path).length() > 0L) {
        return normalized
      }
      if (!videoId.isNullOrBlank() && appContext != null) {
        val localFallback = File(appContext.filesDir, "media/$videoId.mp4")
        if (localFallback.exists() && localFallback.length() > 0L) {
          return Uri.fromFile(localFallback).toString()
        }
      }
      return null
    }
    return null
  }

  /**
   * Direct playback resolution for Admin/Creator panel Watch button without Cloud Function / link round-trips.
   */
  suspend fun resolveDirectVideoForPlayback(
    video: FirestoreVideo,
    currentMemberId: String? = null
  ): TokenValidationResult {
    val resolvedUrl = resolveStreamUrl(video.storagePath, video.videoId)
    val playableUrl = isPlayableResolvedUrl(resolvedUrl, video.videoId)
      ?: return TokenValidationResult.Error(
        if (video.storagePath.startsWith("file://")) {
          "Local video file not found on this device. Please re-upload or paste a https:// Video Stream URL."
        } else {
          "Unable to open video stream URL."
        }
      )

    val countView = shouldIncrementViewCount(video.uploadedBy, currentMemberId)
    if (countView) {
      try {
        db.collection("videos").document(video.videoId).update("totalViews", FieldValue.increment(1))
      } catch (_: Exception) {}
      backgroundIoScope.launch {
        recordCreatorHourlyView(video.uploadedBy)
      }
    }
    prewarmStreamEndpoint(playableUrl)

    return TokenValidationResult.Success(
      VideoToken(
        id = video.videoId,
        token = video.videoId,
        title = video.title.ifBlank { "VidVora Video" },
        description = video.description,
        streamUrl = playableUrl,
        posterUrl = video.thumbnailPath,
        quality = "1080p FHD",
        isActive = video.isActive,
        viewCount = if (countView) (video.totalViews + 1).toInt() else video.totalViews.toInt(),
        createdAt = video.createdAt.toDate().time,
        uploadedBy = video.uploadedBy,
        uploaderName = video.uploaderName
      )
    )
  }

  /**
   * Validates video link authorization and resolves the playable stream URL.
   * Queries Direct Firestore FIRST for instant playback, with Cloud Function fallback for protected Storage paths.
   */
  suspend fun validateAndGetVideo(
    rawInput: String,
    currentMemberId: String? = null
  ): TokenValidationResult {
    val trimmedInput = rawInput.trim()
    val extractedKey = extractToken(trimmedInput)
    if (extractedKey.isBlank()) {
      return TokenValidationResult.Error("Please enter or paste a video link.")
    }

    val isWrappedAppLink = extractedKey != trimmedInput
    val isDirectHttpUrl = !isWrappedAppLink &&
      (trimmedInput.startsWith("http://", ignoreCase = true) ||
        trimmedInput.startsWith("https://", ignoreCase = true))
    val isDirectFileUrl = !isWrappedAppLink && trimmedInput.startsWith("file://", ignoreCase = true)

    val cleanUpper = extractedKey.trim().uppercase()

    // 1. Direct Firestore lookup FIRST (fast, works without Cloud Functions deployed)
    try {
      var resolvedVideoDoc: com.google.firebase.firestore.DocumentSnapshot? = null
      val keyCandidates = listOf(extractedKey.trim(), extractedKey.trim().lowercase(), cleanUpper).distinct()

      // 1a. Look up via accessLinks
      if (!isDirectHttpUrl && !isDirectFileUrl) {
        val tokenHash = hashToken(extractedKey)
        val linksQuery = db.collection("accessLinks")
          .whereEqualTo("tokenHash", tokenHash)
          .limit(1)
          .get()
          .await()

        var linkDoc: com.google.firebase.firestore.DocumentSnapshot? = if (!linksQuery.isEmpty) {
          linksQuery.documents.first()
        } else {
          null
        }

        if (linkDoc == null) {
          for (candidate in keyCandidates) {
            val docSnap = db.collection("accessLinks").document(candidate).get().await()
            if (docSnap.exists()) {
              linkDoc = docSnap
              break
            }
          }
        }

        if (linkDoc != null && linkDoc.exists()) {
          val isActive = linkDoc.getBoolean("isActive") ?: false
          if (!isActive) {
            return TokenValidationResult.TokenDeactivated
          }
          val expiresAt = linkDoc.getTimestamp("expiresAt")
          if (expiresAt != null && expiresAt.toDate().time < System.currentTimeMillis()) {
            return TokenValidationResult.TokenDeactivated
          }
          val linkedVideoId = linkDoc.getString("videoId")
          if (linkedVideoId.isNullOrBlank()) {
            return TokenValidationResult.Error("Video link metadata is missing video reference.")
          }
          val vDoc = db.collection("videos").document(linkedVideoId).get().await()
          if (vDoc.exists()) {
            resolvedVideoDoc = vDoc
          }
        }
      }

      // 1b. Look up directly in videos collection by videoId
      if (resolvedVideoDoc == null && !isDirectHttpUrl && !isDirectFileUrl) {
        for (candidate in keyCandidates) {
          val vDoc = db.collection("videos").document(candidate).get().await()
          if (vDoc.exists()) {
            resolvedVideoDoc = vDoc
            break
          }
        }
      }

      // 1c. Look up by matching storagePath when a direct HTTP(S) URL is pasted
      if (resolvedVideoDoc == null && isDirectHttpUrl) {
        val urlMatches = db.collection("videos")
          .whereEqualTo("isActive", true)
          .whereEqualTo("storagePath", trimmedInput)
          .limit(1)
          .get()
          .await()
        if (!urlMatches.isEmpty) {
          resolvedVideoDoc = urlMatches.documents.first()
        }
      }

      if (resolvedVideoDoc != null && resolvedVideoDoc.exists()) {
        val videoId = resolvedVideoDoc.getString("videoId") ?: resolvedVideoDoc.id
        val isVideoActive = resolvedVideoDoc.getBoolean("isActive") ?: false
        if (!isVideoActive) {
          return TokenValidationResult.TokenDeactivated
        }

        val title = resolvedVideoDoc.getString("title") ?: "Video Stream"
        val description = resolvedVideoDoc.getString("description") ?: ""
        val thumbnailPath = resolvedVideoDoc.getString("thumbnailPath")
        val storagePath = resolvedVideoDoc.getString("storagePath") ?: ""
        val createdAt = resolvedVideoDoc.getTimestamp("createdAt") ?: Timestamp.now()
        val totalViews = resolvedVideoDoc.getLong("totalViews") ?: 0L
        val uploadedBy = resolvedVideoDoc.getString("uploadedBy") ?: "admin"
        val uploaderName = resolvedVideoDoc.getString("uploaderName") ?: "Admin"

        val resolvedStreamUrl = resolveStreamUrl(storagePath, videoId)
        val playableUrl = isPlayableResolvedUrl(resolvedStreamUrl, videoId)

        if (playableUrl != null) {
          val countView = shouldIncrementViewCount(uploadedBy, currentMemberId)
          if (countView) {
            try {
              db.collection("videos").document(videoId).update("totalViews", FieldValue.increment(1))
            } catch (_: Exception) {}
            backgroundIoScope.launch {
              recordCreatorHourlyView(uploadedBy)
            }
          }
          prewarmStreamEndpoint(playableUrl)

          return TokenValidationResult.Success(
            VideoToken(
              id = videoId,
              token = cleanUpper,
              title = title,
              description = description,
              streamUrl = playableUrl,
              posterUrl = thumbnailPath,
              quality = "1080p FHD",
              isActive = true,
              viewCount = if (countView) (totalViews + 1).toInt() else totalViews.toInt(),
              createdAt = createdAt.toDate().time,
              uploadedBy = uploadedBy,
              uploaderName = uploaderName
            )
          )
        } else if (resolvedStreamUrl.startsWith("file://", ignoreCase = true) || storagePath.startsWith("file://", ignoreCase = true)) {
          return TokenValidationResult.Error(
            "This video file was saved only on the uploader's device. To share across devices, upload with a public https:// Video Stream URL."
          )
        }
      }

      // 1d. Direct external HTTP(S) or existing file:// video URL fallback
      if (isDirectHttpUrl || isDirectFileUrl) {
        val playableDirect = isPlayableResolvedUrl(trimmedInput)
        if (playableDirect != null) {
          val directTitle = Uri.parse(playableDirect).lastPathSegment
            ?.substringBeforeLast('.')
            ?.replace('_', ' ')
            ?.replace('-', ' ')
            ?.takeIf { it.isNotBlank() }
            ?: "Direct Video Stream"
          return TokenValidationResult.Success(
            VideoToken(
              id = "direct_" + hashToken(playableDirect).take(12),
              token = playableDirect,
              title = directTitle,
              description = "Direct link playback",
              streamUrl = playableDirect,
              quality = "HD Stream",
              isActive = true
            )
          )
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Direct Firestore validation warning: ${e.message}")
      if (isDirectHttpUrl) {
        return TokenValidationResult.Success(
          VideoToken(
            id = "direct_" + hashToken(trimmedInput).take(12),
            token = trimmedInput,
            title = "Direct Video Stream",
            description = "Direct link playback",
            streamUrl = trimmedInput,
            quality = "HD Stream",
            isActive = true
          )
        )
      }
    }

    // 2. Secondary Cloud Function check (for private Firebase Storage gs:// paths when deployed)
    if (!isDirectHttpUrl && !isDirectFileUrl && !extractedKey.startsWith("vid_", ignoreCase = true)) {
      try {
        val data = hashMapOf("token" to extractedKey.trim())
        val functionResult = functions
          .getHttpsCallable("validateVideoAccess")
          .call(data)
          .await()

        val responseMap = functionResult.data as? Map<*, *>
        if (responseMap != null && responseMap["success"] == true) {
          val videoId = responseMap["videoId"] as? String ?: ""
          val title = responseMap["title"] as? String ?: "Video Stream"
          val description = responseMap["description"] as? String ?: ""
          val streamUrl = responseMap["streamUrl"] as? String ?: ""
          val thumbnailUrl = responseMap["thumbnailUrl"] as? String

          return TokenValidationResult.Success(
            VideoToken(
              id = videoId,
              token = cleanUpper,
              title = title,
              description = description,
              streamUrl = streamUrl,
              posterUrl = thumbnailUrl,
              quality = "1080p FHD",
              isActive = true,
              viewCount = 0,
              createdAt = System.currentTimeMillis()
            )
          )
        }
      } catch (e: FirebaseFunctionsException) {
        Log.w(TAG, "Cloud Function validateVideoAccess error code=${e.code}", e)
        when (e.code) {
          FirebaseFunctionsException.Code.FAILED_PRECONDITION -> {
            return TokenValidationResult.TokenDeactivated
          }
          FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> {
            return TokenValidationResult.Error("Too many link attempts. Please wait a moment and try again.")
          }
          else -> {}
        }
      } catch (e: Exception) {
        Log.w(TAG, "Cloud Function unavailable: ${e.message}")
      }
    }

    return TokenValidationResult.TokenNotFound
  }

  suspend fun resolveStreamUrl(storagePath: String, videoId: String? = null): String {
    val normalized = normalizeMediaUrl(storagePath)
    if (normalized.startsWith("http://", ignoreCase = true) ||
      normalized.startsWith("https://", ignoreCase = true)
    ) {
      return normalized
    }
    if (normalized.startsWith("file://", ignoreCase = true)) {
      val path = Uri.parse(normalized).path
      if (!path.isNullOrBlank() && File(path).exists() && File(path).length() > 0L) {
        return normalized
      }
    }
    if (!videoId.isNullOrBlank() && appContext != null) {
      val localFile = File(appContext.filesDir, "media/$videoId.mp4")
      if (localFile.exists() && localFile.length() > 0L) {
        return Uri.fromFile(localFile).toString()
      }
    }
    if (normalized.isNotBlank() && !normalized.startsWith("file://", ignoreCase = true)) {
      try {
        storage.maxOperationRetryTimeMillis = 4000L
        val ref = if (normalized.startsWith("gs://")) {
          storage.getReferenceFromUrl(normalized)
        } else {
          storage.reference.child(normalized.trimStart('/'))
        }
        return ref.downloadUrl.await().toString()
      } catch (e: Exception) {
        Log.w(TAG, "Could not resolve Cloud Storage URL for $normalized: ${e.message}")
      }
    }
    return normalized
  }

  suspend fun findActiveVideoByStoragePath(url: String): VideoToken? {
    val cleanUrl = url.trim()
    if (cleanUrl.isBlank()) return null
    return try {
      val matches = db.collection("videos")
        .whereEqualTo("isActive", true)
        .whereEqualTo("storagePath", cleanUrl)
        .limit(1)
        .get()
        .await()
      if (matches.isEmpty) return null
      val doc = matches.documents.first()
      if (doc.getBoolean("isActive") != true) return null
      val videoId = doc.getString("videoId") ?: doc.id
      val storagePath = doc.getString("storagePath") ?: ""
      val resolved = resolveStreamUrl(storagePath, videoId)
      val playable = isPlayableResolvedUrl(resolved, videoId) ?: return null
      VideoToken(
        id = videoId,
        token = videoId,
        title = doc.getString("title") ?: "Video Stream",
        description = doc.getString("description") ?: "",
        streamUrl = playable,
        posterUrl = doc.getString("thumbnailPath"),
        quality = "1080p FHD",
        isActive = true,
        viewCount = (doc.getLong("totalViews") ?: 0L).toInt(),
        createdAt = (doc.getTimestamp("createdAt") ?: Timestamp.now()).toDate().time,
        uploadedBy = doc.getString("uploadedBy") ?: "admin",
        uploaderName = doc.getString("uploaderName") ?: "Admin"
      )
    } catch (e: Exception) {
      Log.w(TAG, "Storage path lookup warning: ${e.message}")
      null
    }
  }

  fun getAllVideosFlow(): Flow<List<FirestoreVideo>> = callbackFlow {
    val registration: ListenerRegistration = db.collection("videos")
      .whereEqualTo("isActive", true)
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Listen warning on videos: ${error.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val items = snapshot?.documents?.mapNotNull { doc ->
          try {
            val estimatedCreatedAt = doc.getTimestamp(
              "createdAt",
              DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: Timestamp.now()
            FirestoreVideo(
              videoId = doc.getString("videoId") ?: doc.id,
              title = doc.getString("title") ?: "",
              description = doc.getString("description") ?: "",
              thumbnailPath = doc.getString("thumbnailPath"),
              storagePath = doc.getString("storagePath") ?: "",
              createdAt = estimatedCreatedAt,
              isActive = doc.getBoolean("isActive") ?: true,
              totalViews = doc.getLong("totalViews") ?: 0L,
              uploadedBy = doc.getString("uploadedBy") ?: "admin",
              uploaderName = doc.getString("uploaderName") ?: "Admin"
            )
          } catch (_: Exception) {
            null
          }
        } ?: emptyList()
        trySend(sortVideosNewestFirst(items))
      }
    awaitClose { registration.remove() }
  }

  fun getAllAccessLinksFlow(): Flow<List<FirestoreAccessLink>> = callbackFlow {
    val registration: ListenerRegistration = db.collection("accessLinks")
      .whereEqualTo("isActive", true)
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Listen warning on accessLinks: ${error.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val items = snapshot?.documents?.mapNotNull { doc ->
          try {
            FirestoreAccessLink(
              linkId = doc.getString("linkId") ?: doc.id,
              videoId = doc.getString("videoId") ?: "",
              tokenHash = doc.getString("tokenHash") ?: "",
              isActive = doc.getBoolean("isActive") ?: true,
              createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now(),
              expiresAt = doc.getTimestamp("expiresAt")
            )
          } catch (_: Exception) {
            null
          }
        } ?: emptyList()
        trySend(items)
      }
    awaitClose { registration.remove() }
  }

  /**
   * Upload video and thumbnail files (with persistent local fallback if Storage is unprovisioned/restricted)
   * and create playable records in Firestore.
   */
  suspend fun uploadAndCreateVideo(
    title: String,
    description: String,
    videoUri: Uri?,
    thumbnailUri: Uri?,
    manualStreamUrl: String?,
    customToken: String?,
    uploadedBy: String = "admin",
    uploaderName: String = "Admin",
    onProgress: (Float) -> Unit
  ): VideoCreationResult {
    val videoId = "vid_" + UUID.randomUUID().toString().replace("-", "").take(12)
    val linkId = "link_" + UUID.randomUUID().toString().replace("-", "").take(12)

    var finalStoragePath: String = normalizeMediaUrl(manualStreamUrl ?: "")
    var finalThumbnailPath: String? = null
    val hasFirebaseAuthUser = FirebaseAuth.getInstance().currentUser != null

    // 1. Upload chosen thumbnail OR auto-extract video frame thumbnail
    if (thumbnailUri != null) {
      val localDataUrl = encodeCompressedImageDataUri(
        imageUri = thumbnailUri,
        maxDim = 1280,
        maxBytes = 280_000,
        hardCapBytes = 340_000
      )
      val localThumb = localDataUrl ?: saveLocalMediaCopy("${videoId}_thumb.jpg", thumbnailUri)
      if (hasFirebaseAuthUser) {
        try {
          storage.maxUploadRetryTimeMillis = 5000L
          val thumbRef = storage.reference.child("videos/$videoId/thumbnail.jpg")
          thumbRef.putFile(thumbnailUri).await()
          finalThumbnailPath = try {
            thumbRef.downloadUrl.await().toString()
          } catch (_: Exception) {
            localThumb ?: thumbRef.path
          }
        } catch (e: Exception) {
          Log.w(TAG, "Thumbnail cloud upload skipped, using compact thumbnail: ${e.message}")
          finalThumbnailPath = localThumb
        }
      } else {
        finalThumbnailPath = localThumb
      }
    } else {
      val autoFrameBytes = extractVideoThumbnailJpegBytes(
        videoUri = videoUri,
        remoteStreamUrl = finalStoragePath.takeIf { it.isNotBlank() }
      )
      if (autoFrameBytes != null) {
        val compactDataUrl = encodeJpegBytesAsDataUrl(autoFrameBytes)
        if (hasFirebaseAuthUser) {
          try {
            storage.maxUploadRetryTimeMillis = 5000L
            val thumbRef = storage.reference.child("videos/$videoId/thumbnail.jpg")
            thumbRef.putBytes(autoFrameBytes).await()
            finalThumbnailPath = try {
              thumbRef.downloadUrl.await().toString()
            } catch (_: Exception) {
              compactDataUrl
            }
          } catch (e: Exception) {
            Log.w(TAG, "Auto-thumbnail cloud upload skipped, using compact JPEG: ${e.message}")
            finalThumbnailPath = compactDataUrl
          }
        } else {
          finalThumbnailPath = compactDataUrl
        }
      }
    }

    // 2. Upload or locally persist video file if selected from device
    if (videoUri != null) {
      val localVideoUri = saveLocalMediaCopy("$videoId.mp4", videoUri) { copyProgress ->
        onProgress(if (hasFirebaseAuthUser) copyProgress * 0.4f else copyProgress)
      }
      var uploadedRemoteUrl: String? = null
      if (hasFirebaseAuthUser) {
        try {
          storage.maxUploadRetryTimeMillis = 5000L
          storage.maxOperationRetryTimeMillis = 5000L
          val videoRef = storage.reference.child("videos/$videoId/video.mp4")
          val uploadTask = videoRef.putFile(videoUri)

          uploadTask.addOnProgressListener { snapshot ->
            if (snapshot.totalByteCount > 0) {
              val progress = 40f + (snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount.toFloat()) * 60f
              onProgress(progress.coerceIn(40f, 100f))
            }
          }

          uploadTask.await()
          uploadedRemoteUrl = try {
            videoRef.downloadUrl.await().toString()
          } catch (_: Exception) {
            localVideoUri ?: videoRef.path
          }
        } catch (e: Exception) {
          Log.w(TAG, "Video cloud upload unavailable, using persistent device path: ${e.message}")
          onProgress(100f)
        }
      } else {
        onProgress(100f)
      }

      finalStoragePath = uploadedRemoteUrl
        ?: localVideoUri
        ?: finalStoragePath.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Unable to read selected video file. Please pick another file or paste a Video Stream URL.")
    }

    if (finalStoragePath.isBlank()) {
      error("No video file selected or stream URL provided.")
    }

    // 3. Generate unique share link code
    val rawToken = if (!customToken.isNullOrBlank()) {
      customToken.trim().uppercase()
    } else {
      generateCryptographicToken()
    }

    val tokenHash = hashToken(rawToken)

    val video = FirestoreVideo(
      videoId = videoId,
      title = title.trim(),
      description = description.trim(),
      thumbnailPath = finalThumbnailPath,
      storagePath = finalStoragePath,
      createdAt = Timestamp.now(),
      isActive = true,
      totalViews = 0L,
      uploadedBy = uploadedBy,
      uploaderName = uploaderName
    )

    val accessLink = FirestoreAccessLink(
      linkId = linkId,
      videoId = videoId,
      tokenHash = tokenHash,
      isActive = true,
      createdAt = Timestamp.now(),
      expiresAt = null
    )

    val videoData = hashMapOf(
      "videoId" to video.videoId,
      "title" to video.title,
      "description" to video.description,
      "thumbnailPath" to video.thumbnailPath,
      "storagePath" to video.storagePath,
      "createdAt" to FieldValue.serverTimestamp(),
      "isActive" to video.isActive,
      "totalViews" to video.totalViews,
      "uploadedBy" to video.uploadedBy,
      "uploaderName" to video.uploaderName
    )

    val linkData = hashMapOf(
      "linkId" to accessLink.linkId,
      "videoId" to accessLink.videoId,
      "tokenHash" to accessLink.tokenHash,
      "isActive" to accessLink.isActive,
      "createdAt" to FieldValue.serverTimestamp(),
      "expiresAt" to accessLink.expiresAt
    )

    // Save in Cloud Firestore
    db.collection("videos").document(videoId).set(videoData).await()
    db.collection("accessLinks").document(linkId).set(linkData).await()

    val configuredShareBase = try {
      db.collection("appConfig").document("general").get().await().getString("shareLinkBaseUrl").orEmpty()
    } catch (_: Exception) {
      ""
    }
    val shareableLink = buildShareableVideoUrl(rawToken, configuredShareBase)

    return VideoCreationResult(
      video = video,
      accessLink = accessLink,
      rawToken = rawToken,
      shareableLink = shareableLink
    )
  }

  suspend fun updateVideoMetadata(videoId: String, newTitle: String, newDescription: String) {
    db.collection("videos").document(videoId).update(
      mapOf(
        "title" to newTitle.trim(),
        "description" to newDescription.trim()
      )
    ).await()
  }

  suspend fun toggleLinkActive(linkId: String, isActive: Boolean) {
    db.collection("accessLinks").document(linkId).update("isActive", isActive).await()
  }

  suspend fun toggleVideoActive(videoId: String, isActive: Boolean) {
    db.collection("videos").document(videoId).update("isActive", isActive).await()
  }

  suspend fun deleteLink(linkId: String) {
    db.collection("accessLinks").document(linkId).delete().await()
  }

  suspend fun deleteVideoAndAssociatedData(videoId: String) {
    try {
      db.collection("videos").document(videoId).delete().await()

      val links = db.collection("accessLinks").whereEqualTo("videoId", videoId).get().await()
      for (doc in links.documents) {
        doc.reference.delete().await()
      }

      try {
        storage.reference.child("videos/$videoId/video.mp4").delete().await()
      } catch (_: Exception) {}
      try {
        storage.reference.child("videos/$videoId/thumbnail.jpg").delete().await()
      } catch (_: Exception) {}
    } catch (e: Exception) {
      Log.e(TAG, "Error deleting video and assets: ${e.message}", e)
      throw e
    }
  }

  suspend fun seedInitialDataIfEmpty() {
    try {
      val existing = db.collection("videos").limit(1).get().await()
      if (existing.isEmpty) {
        val seeds = listOf(
          Triple(
            "Big Buck Bunny (Special Masala Edition)",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            "GM-SPICE-01"
          ),
          Triple(
            "Tears of Steel (Sci-Fi Cinema)",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
            "GM-MASALA-02"
          ),
          Triple(
            "Sintel (Fantasy Epic Short)",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
            "GM-CHILI-03"
          )
        )

        for ((title, url, token) in seeds) {
          val videoId = "seed_" + token.lowercase().replace("-", "_")
          val linkId = "link_" + token.lowercase().replace("-", "_")
          val tokenHash = hashToken(token)

          val videoData = hashMapOf(
            "videoId" to videoId,
            "title" to title,
            "description" to "Pristine streaming media available via VidVora video link",
            "thumbnailPath" to null,
            "storagePath" to url,
            "createdAt" to FieldValue.serverTimestamp(),
            "isActive" to true,
            "totalViews" to 240L
          )

          val linkData = hashMapOf(
            "linkId" to linkId,
            "videoId" to videoId,
            "tokenHash" to tokenHash,
            "isActive" to true,
            "createdAt" to FieldValue.serverTimestamp(),
            "expiresAt" to null
          )

          db.collection("videos").document(videoId).set(videoData).await()
          db.collection("accessLinks").document(linkId).set(linkData).await()
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Seed initialization check: ${e.message}")
    }
  }

  private fun parseTelegramConfigSnapshot(snapshot: DocumentSnapshot?): TelegramConfig {
    val mainRaw = snapshot?.getString("telegramLink")
    val mainLink = if (!mainRaw.isNullOrBlank()) mainRaw.trim() else DEFAULT_TELEGRAM_LINK
    val creatorBotLink = normalizeTelegramUrl(snapshot?.getString("creatorBotLink").orEmpty())
    val financeBotLink = normalizeTelegramUrl(snapshot?.getString("financeBotLink").orEmpty())
    val shareLinkBaseUrl = normalizeShareLinkBaseUrl(snapshot?.getString("shareLinkBaseUrl").orEmpty())
    val rawMap = snapshot?.get("categoryLinks") as? Map<*, *>
    val categoryMap = TelegramChannelCategory.entries.associate { category ->
      val value = (rawMap?.get(category.key) as? String)?.trim().orEmpty()
      category.key to value
    }
    val rawNamesMap = snapshot?.get("categoryNames") as? Map<*, *>
    val categoryNamesMap = buildMap {
      TelegramChannelCategory.entries.forEach { category ->
        val overrideName = (rawNamesMap?.get(category.key) as? String)
          ?.trim()
          ?.take(MAX_CATEGORY_NAME_LENGTH)
          .orEmpty()
        if (overrideName.isNotBlank() && overrideName != category.label) {
          put(category.key, overrideName)
        }
      }
    }
    val rawDeletedKeys = snapshot?.get("deletedCategoryKeys") as? List<*>
    val deletedKeys = rawDeletedKeys
      ?.mapNotNull { (it as? String)?.trim() }
      ?.filter { TelegramChannelCategory.fromKey(it) != null }
      ?.distinct()
      .orEmpty()
    val rawCustomList = snapshot?.get("customCategories") as? List<*>
    val parsedCustomCategories = rawCustomList?.mapNotNull { entry ->
      val map = entry as? Map<*, *> ?: return@mapNotNull null
      val id = (map["id"] as? String)?.trim().orEmpty()
      val name = (map["name"] as? String)?.trim().orEmpty()
      val link = (map["link"] as? String)?.trim().orEmpty()
      if (id.isBlank() || name.isBlank()) return@mapNotNull null
      TelegramCategoryItem(
        id = id,
        name = name,
        link = link,
        isBuiltIn = false
      )
    } ?: emptyList()

    return TelegramConfig(
      mainLink = mainLink,
      creatorBotLink = creatorBotLink,
      financeBotLink = financeBotLink,
      shareLinkBaseUrl = shareLinkBaseUrl,
      categoryLinks = categoryMap,
      categoryNames = categoryNamesMap,
      deletedCategoryKeys = deletedKeys,
      customCategories = parsedCustomCategories
    )
  }

  fun getTelegramConfigFlow(): Flow<TelegramConfig> = callbackFlow {
    val registration = db.collection("appConfig").document("general")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Error listening to telegram config: ${error.message}")
          trySend(TelegramConfig(mainLink = DEFAULT_TELEGRAM_LINK))
          return@addSnapshotListener
        }
        trySend(parseTelegramConfigSnapshot(snapshot))
      }
    awaitClose { registration.remove() }
  }

  suspend fun fetchTelegramConfigFromServer(): TelegramConfig? {
    return try {
      val snapshot = db.collection("appConfig").document("general")
        .get(com.google.firebase.firestore.Source.SERVER)
        .await()
      parseTelegramConfigSnapshot(snapshot)
    } catch (e: Exception) {
      Log.w(TAG, "Server refresh on appConfig/general skipped: ${e.message}")
      null
    }
  }

  fun getTelegramLinkFlow(): Flow<String> = callbackFlow {
    val registration = db.collection("appConfig").document("general")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Error listening to telegram link: ${error.message}")
          trySend(DEFAULT_TELEGRAM_LINK)
          return@addSnapshotListener
        }
        val link = snapshot?.getString("telegramLink")
        if (!link.isNullOrBlank()) {
          trySend(link.trim())
        } else {
          trySend(DEFAULT_TELEGRAM_LINK)
        }
      }
    awaitClose { registration.remove() }
  }

  suspend fun updateTelegramConfig(
    mainLink: String,
    categoryLinks: Map<String, String>,
    customCategories: List<TelegramCategoryItem> = emptyList(),
    categoryNames: Map<String, String> = emptyMap(),
    deletedCategoryKeys: List<String> = emptyList(),
    creatorBotLink: String = "",
    financeBotLink: String = "",
    shareLinkBaseUrl: String = ""
  ): Result<TelegramConfig> {
    return try {
      val cleanMain = normalizeTelegramUrl(mainLink).ifBlank { DEFAULT_TELEGRAM_LINK }
      if (cleanMain.length > 500) {
        return Result.failure(IllegalArgumentException("Main Telegram link cannot exceed 500 characters."))
      }
      val cleanCreatorBotLink = normalizeTelegramUrl(creatorBotLink)
      if (cleanCreatorBotLink.length > 500) {
        return Result.failure(IllegalArgumentException("Creator Bot link cannot exceed 500 characters."))
      }
      val cleanFinanceBotLink = normalizeTelegramUrl(financeBotLink)
      if (cleanFinanceBotLink.length > 500) {
        return Result.failure(IllegalArgumentException("Finance Bot link cannot exceed 500 characters."))
      }
      val cleanShareLinkBaseUrl = normalizeShareLinkBaseUrl(shareLinkBaseUrl)
      if (cleanShareLinkBaseUrl.length > 500) {
        return Result.failure(IllegalArgumentException("Share Preview URL cannot exceed 500 characters."))
      }
      val cleanDeletedKeys = deletedCategoryKeys
        .map { it.trim() }
        .filter { TelegramChannelCategory.fromKey(it) != null }
        .distinct()
      val deletedKeySet = cleanDeletedKeys.toSet()

      val seenNames = mutableSetOf<String>()
      val sanitizedCategoryNames = mutableMapOf<String, String>()
      val sanitizedCategories = TelegramChannelCategory.entries.associate { category ->
        if (category.key in deletedKeySet) {
          category.key to ""
        } else {
          val resolvedName = categoryNames[category.key]?.trim().takeIf { !it.isNullOrBlank() } ?: category.label
          if (resolvedName.length > MAX_CATEGORY_NAME_LENGTH) {
            return Result.failure(
              IllegalArgumentException("Category name '$resolvedName' cannot exceed $MAX_CATEGORY_NAME_LENGTH characters.")
            )
          }
          if (!seenNames.add(resolvedName.lowercase())) {
            return Result.failure(
              IllegalArgumentException("Duplicate category name '$resolvedName'. Please use a unique name.")
            )
          }
          if (resolvedName != category.label) {
            sanitizedCategoryNames[category.key] = resolvedName
          }
          val cleanUrl = normalizeTelegramUrl(categoryLinks[category.key].orEmpty())
          if (cleanUrl.length > 500) {
            return Result.failure(
              IllegalArgumentException("$resolvedName link cannot exceed 500 characters.")
            )
          }
          category.key to cleanUrl
        }
      }

      if (customCategories.size > MAX_CUSTOM_TELEGRAM_CATEGORIES) {
        return Result.failure(
          IllegalArgumentException("You can add up to $MAX_CUSTOM_TELEGRAM_CATEGORIES custom categories.")
        )
      }

      val seenIds = mutableSetOf<String>()
      val sanitizedCustomItems = mutableListOf<TelegramCategoryItem>()
      val serializedCustomMaps = mutableListOf<Map<String, String>>()

      for (item in customCategories) {
        val cleanName = item.name.trim()
        if (cleanName.isBlank()) {
          return Result.failure(IllegalArgumentException("Category name cannot be empty."))
        }
        if (cleanName.length > MAX_CATEGORY_NAME_LENGTH) {
          return Result.failure(
            IllegalArgumentException("Category name '$cleanName' cannot exceed $MAX_CATEGORY_NAME_LENGTH characters.")
          )
        }
        val lowerName = cleanName.lowercase()
        if (!seenNames.add(lowerName)) {
          return Result.failure(
            IllegalArgumentException("Duplicate category name '$cleanName'. Please use a unique name.")
          )
        }
        val cleanUrl = normalizeTelegramUrl(item.link)
        if (cleanUrl.length > 500) {
          return Result.failure(
            IllegalArgumentException("$cleanName link cannot exceed 500 characters.")
          )
        }
        var cleanId = item.id.trim().takeIf {
          it.matches(Regex("^[a-zA-Z0-9_\\-]{3,128}$")) &&
            TelegramChannelCategory.fromKey(it) == null
        } ?: buildCustomCategoryId(cleanName)
        while (!seenIds.add(cleanId)) {
          cleanId = buildCustomCategoryId(cleanName)
        }
        val normalizedItem = TelegramCategoryItem(
          id = cleanId,
          name = cleanName,
          link = cleanUrl,
          isBuiltIn = false
        )
        sanitizedCustomItems.add(normalizedItem)
        serializedCustomMaps.add(
          mapOf(
            "id" to cleanId,
            "name" to cleanName,
            "link" to cleanUrl
          )
        )
      }

      val data = hashMapOf<String, Any>(
        "telegramLink" to cleanMain,
        "creatorBotLink" to cleanCreatorBotLink,
        "financeBotLink" to cleanFinanceBotLink,
        "shareLinkBaseUrl" to cleanShareLinkBaseUrl,
        "categoryLinks" to sanitizedCategories,
        "categoryNames" to sanitizedCategoryNames,
        "deletedCategoryKeys" to cleanDeletedKeys,
        "customCategories" to serializedCustomMaps,
        "updatedAt" to FieldValue.serverTimestamp()
      )
      db.collection("appConfig").document("general")
        .set(data)
        .await()
      Result.success(
        TelegramConfig(
          mainLink = cleanMain,
          creatorBotLink = cleanCreatorBotLink,
          financeBotLink = cleanFinanceBotLink,
          shareLinkBaseUrl = cleanShareLinkBaseUrl,
          categoryLinks = sanitizedCategories,
          categoryNames = sanitizedCategoryNames,
          deletedCategoryKeys = cleanDeletedKeys,
          customCategories = sanitizedCustomItems
        )
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to update telegram config in Firestore", e)
      Result.failure(e)
    }
  }

  suspend fun updateTelegramLink(newLink: String): Result<Unit> {
    return try {
      val cleanLink = normalizeTelegramUrl(newLink).ifBlank { DEFAULT_TELEGRAM_LINK }
      val data = mapOf(
        "telegramLink" to cleanLink,
        "updatedAt" to FieldValue.serverTimestamp()
      )
      db.collection("appConfig").document("general")
        .set(data, com.google.firebase.firestore.SetOptions.merge())
        .await()
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to update telegram link in Firestore", e)
      Result.failure(e)
    }
  }

  suspend fun getTelegramLink(): String {
    return try {
      val snap = db.collection("appConfig").document("general").get().await()
      val link = snap.getString("telegramLink")
      if (!link.isNullOrBlank()) link.trim() else DEFAULT_TELEGRAM_LINK
    } catch (e: Exception) {
      DEFAULT_TELEGRAM_LINK
    }
  }

  // --- Self-Hosted In-App Update Config ---

  private fun parseAppUpdateConfigSnapshot(snapshot: DocumentSnapshot?): AppUpdateConfig {
    if (snapshot == null || !snapshot.exists()) {
      return AppUpdateConfig(
        latestVersionCode = BuildConfig.VERSION_CODE,
        latestVersionName = BuildConfig.VERSION_NAME,
        apkUrl = DEFAULT_APK_DOWNLOAD_URL
      )
    }
    val isEnabled = snapshot.getBoolean("isEnabled") ?: false
    val latestVersionCode = (snapshot.getLong("latestVersionCode")?.toInt() ?: BuildConfig.VERSION_CODE)
      .coerceAtLeast(1)
    val latestVersionName = snapshot.getString("latestVersionName")?.trim()
      ?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
    val title = snapshot.getString("title")?.trim()
      ?.takeIf { it.isNotBlank() } ?: "New VidVora Update Available"
    val message = snapshot.getString("message")?.trim()
      ?: "Faster video playback and improvements are ready. Update now!"
    val apkUrl = snapshot.getString("apkUrl")?.trim()
      ?.takeIf { it.isNotBlank() } ?: DEFAULT_APK_DOWNLOAD_URL
    val isForceUpdate = snapshot.getBoolean("isForceUpdate") ?: false
    return AppUpdateConfig(
      isEnabled = isEnabled,
      latestVersionCode = latestVersionCode,
      latestVersionName = latestVersionName,
      title = title,
      message = message,
      apkUrl = apkUrl,
      isForceUpdate = isForceUpdate
    )
  }

  fun getAppUpdateConfigFlow(): Flow<AppUpdateConfig> = callbackFlow {
    val registration = db.collection("appConfig").document(APP_UPDATE_DOC_ID)
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Error listening to appUpdate config: ${error.message}")
          trySend(
            AppUpdateConfig(
              latestVersionCode = BuildConfig.VERSION_CODE,
              latestVersionName = BuildConfig.VERSION_NAME,
              apkUrl = DEFAULT_APK_DOWNLOAD_URL
            )
          )
          return@addSnapshotListener
        }
        trySend(parseAppUpdateConfigSnapshot(snapshot))
      }
    awaitClose { registration.remove() }
  }

  suspend fun fetchAppUpdateConfigFromServer(): AppUpdateConfig? {
    return try {
      val snapshot = db.collection("appConfig").document(APP_UPDATE_DOC_ID)
        .get(com.google.firebase.firestore.Source.SERVER)
        .await()
      parseAppUpdateConfigSnapshot(snapshot)
    } catch (e: Exception) {
      Log.w(TAG, "Server refresh on appConfig/appUpdate skipped: ${e.message}")
      null
    }
  }

  suspend fun updateAppUpdateConfig(
    isEnabled: Boolean,
    latestVersionCode: Int,
    latestVersionName: String,
    title: String,
    message: String,
    apkUrl: String,
    isForceUpdate: Boolean
  ): Result<AppUpdateConfig> {
    return try {
      val cleanVersionName = latestVersionName.trim()
      val cleanTitle = title.trim()
      val cleanMessage = message.trim()
      val cleanApkUrl = apkUrl.trim().ifBlank { DEFAULT_APK_DOWNLOAD_URL }

      val validationError = validateAppUpdateInput(
        latestVersionCode = latestVersionCode,
        latestVersionName = cleanVersionName,
        title = cleanTitle,
        message = cleanMessage,
        apkUrl = cleanApkUrl
      )
      if (validationError != null) {
        return Result.failure(IllegalArgumentException(validationError))
      }

      val payload = hashMapOf<String, Any>(
        "isEnabled" to isEnabled,
        "latestVersionCode" to latestVersionCode,
        "latestVersionName" to cleanVersionName,
        "title" to cleanTitle,
        "message" to cleanMessage,
        "apkUrl" to cleanApkUrl,
        "isForceUpdate" to isForceUpdate,
        "updatedAt" to FieldValue.serverTimestamp()
      )
      db.collection("appConfig").document(APP_UPDATE_DOC_ID)
        .set(payload)
        .await()

      Result.success(
        AppUpdateConfig(
          isEnabled = isEnabled,
          latestVersionCode = latestVersionCode,
          latestVersionName = cleanVersionName,
          title = cleanTitle,
          message = cleanMessage,
          apkUrl = cleanApkUrl,
          isForceUpdate = isForceUpdate
        )
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to update appUpdate config in Firestore", e)
      Result.failure(e)
    }
  }

  // --- Superadmin Master Key Security Config ---

  suspend fun getSuperadminMasterKeyHash(): String? {
    return try {
      val snap = db.collection("appConfig").document(ADMIN_SECURITY_DOC_ID).get().await()
      snap.getString("adminMasterKeyHash")?.trim()?.takeIf { it.length == 64 }
    } catch (e: Exception) {
      Log.w(TAG, "Unable to fetch adminSecurity config: ${e.message}")
      null
    }
  }

  suspend fun verifySuperadminMasterKey(
    candidatePasskey: String,
    localFallbackHash: String? = null
  ): Boolean {
    val clean = candidatePasskey.trim()
    if (clean.isBlank()) return false
    val remoteHash = getSuperadminMasterKeyHash()
    val effectiveHash = remoteHash ?: localFallbackHash?.trim()?.takeIf { it.length == 64 }
    return if (effectiveHash != null) {
      hashSecretExact(clean) == effectiveHash
    } else {
      clean == FirebaseAuthAdminRepository.ADMIN_MASTER_KEY
    }
  }

  suspend fun changeSuperadminMasterKey(
    currentPasskey: String,
    newPasskey: String,
    confirmPasskey: String,
    localFallbackHash: String? = null
  ): Result<String> {
    return try {
      val remoteHash = getSuperadminMasterKeyHash()
      val effectiveHash = remoteHash ?: localFallbackHash?.trim()?.takeIf { it.length == 64 }
      val validationError = validateSuperadminMasterKeyChange(
        storedHash = effectiveHash,
        currentInput = currentPasskey,
        newPasskey = newPasskey,
        confirmPasskey = confirmPasskey
      )
      if (validationError != null) {
        return Result.failure(IllegalArgumentException(validationError))
      }

      val cleanNew = newPasskey.trim()
      val conflictingMember = getMemberByPasskey(cleanNew)
      if (conflictingMember != null) {
        return Result.failure(IllegalArgumentException("This Password is already used by a Member. Please choose a unique password."))
      }

      val newHash = hashSecretExact(cleanNew)
      val payload = mapOf(
        "adminMasterKeyHash" to newHash,
        "updatedAt" to FieldValue.serverTimestamp()
      )
      db.collection("appConfig").document(ADMIN_SECURITY_DOC_ID)
        .set(payload)
        .await()
      Result.success(newHash)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to update Superadmin Master Key", e)
      Result.failure(e)
    }
  }

  // --- Member / Creator Management ---

  private fun parseMemberDoc(doc: DocumentSnapshot): FirestoreMember {
    val normalizedRole = MemberRole.normalize(doc.getString("role"))
    val resolvedOptions = if (doc.contains("allowedOptions")) {
      val rawList = doc.get("allowedOptions") as? List<*>
      MemberOption.sanitizeKeys(rawList?.mapNotNull { it as? String }.orEmpty())
    } else {
      MemberOption.defaultKeysForRole(normalizedRole)
    }
    val rawHourlyViews = doc.get("hourlyViews") as? Map<*, *>
    val parsedHourlyViews = buildMap<String, Long> {
      rawHourlyViews?.forEach { (k, v) ->
        val key = (k as? String)?.trim().orEmpty()
        val count = (v as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
        if (key.length == 10 && key.all { it.isDigit() } && count > 0L) {
          put(key, count)
        }
      }
    }
    return FirestoreMember(
      memberId = doc.getString("memberId")?.takeIf { it.isNotBlank() } ?: doc.id,
      name = doc.getString("name") ?: "",
      emailOrPhone = doc.getString("emailOrPhone") ?: "",
      passkey = doc.getString("passkey") ?: "",
      role = normalizedRole,
      isActive = doc.getBoolean("isActive") ?: true,
      createdAt = doc.getTimestamp("createdAt") ?: Timestamp(0, 0),
      totalEarning = (doc.get("totalEarning") as? Number)?.toDouble() ?: 0.0,
      creditedViews = (doc.get("creditedViews") as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L,
      viewRatePer1000 = (doc.get("viewRatePer1000") as? Number)?.toDouble()?.takeIf { it >= 0.0 }
        ?: DEFAULT_VIEW_RATE_PER_1000,
      allowedOptions = resolvedOptions,
      hourlyViews = parsedHourlyViews
    )
  }

  private suspend fun recordCreatorHourlyView(uploadedBy: String) {
    val cleanUploader = uploadedBy.trim()
    if (cleanUploader.isBlank() || cleanUploader.equals("admin", ignoreCase = true)) return
    try {
      val bucketKey = currentUtcHourBucketKey()
      val memberRef = resolveMemberDocRef(cleanUploader)
      memberRef.update("hourlyViews.$bucketKey", FieldValue.increment(1)).await()
    } catch (_: Exception) {
      // Best-effort 24h view bucket tracking; never block playback
    }
  }

  fun getAllMembersFlow(): Flow<List<FirestoreMember>> = callbackFlow {
    val registration = db.collection("members")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Listen warning on members: ${error.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val members = snapshot?.documents?.mapNotNull { doc ->
          try {
            parseMemberDoc(doc)
          } catch (_: Exception) {
            null
          }
        } ?: emptyList()
        trySend(sortMembersNewestFirst(members))
      }
    awaitClose { registration.remove() }
  }

  suspend fun addMember(
    name: String,
    emailOrPhone: String,
    passkey: String,
    role: String = MemberRole.CREATOR,
    allowedOptions: List<String> = MemberOption.defaultKeysForRole(role)
  ): Result<FirestoreMember> {
    return try {
      val validationError = validateMemberInput(name, emailOrPhone, passkey)
      if (validationError != null) {
        return Result.failure(IllegalArgumentException(validationError))
      }
      val cleanName = name.trim()
      val cleanEmail = emailOrPhone.trim()
      val cleanPass = passkey.trim()
      val normalizedRole = MemberRole.normalize(role)
      val cleanOptions = MemberOption.sanitizeKeys(allowedOptions)
      if (cleanOptions.isEmpty()) {
        return Result.failure(IllegalArgumentException("Please select at least 1 option for this account."))
      }

      // Prevent duplicate email registration across members
      val existingMembers = db.collection("members").get().await()
      val emailCollision = existingMembers.documents.firstOrNull { doc ->
        (doc.getString("emailOrPhone") ?: "").trim().equals(cleanEmail, ignoreCase = true)
      }
      if (emailCollision != null) {
        val existingName = (emailCollision.getString("name") ?: "Creator").trim()
        return Result.failure(
          IllegalArgumentException(
            "Email '$cleanEmail' is already registered to '$existingName'. Use Change Email on their card to update or transfer data."
          )
        )
      }

      // Prevent Master Key collision with another member/admin
      val existingWithKey = db.collection("members")
        .whereEqualTo("passkey", cleanPass)
        .get()
        .await()
      if (!existingWithKey.isEmpty) {
        return Result.failure(IllegalArgumentException("This Password is already assigned to another member."))
      }

      val memberId = "mem_" + UUID.randomUUID().toString().replace("-", "").take(10)
      val member = FirestoreMember(
        memberId = memberId,
        name = cleanName,
        emailOrPhone = cleanEmail,
        passkey = cleanPass,
        role = normalizedRole,
        isActive = true,
        createdAt = Timestamp.now(),
        totalEarning = 0.0,
        creditedViews = 0L,
        viewRatePer1000 = DEFAULT_VIEW_RATE_PER_1000,
        allowedOptions = cleanOptions
      )
      val data = hashMapOf(
        "memberId" to member.memberId,
        "name" to member.name,
        "emailOrPhone" to member.emailOrPhone,
        "passkey" to member.passkey,
        "role" to member.role,
        "allowedOptions" to member.allowedOptions,
        "isActive" to member.isActive,
        "createdAt" to member.createdAt,
        "totalEarning" to 0.0,
        "creditedViews" to 0L,
        "viewRatePer1000" to DEFAULT_VIEW_RATE_PER_1000
      )
      db.collection("members").document(memberId).set(data).await()
      Result.success(member)
    } catch (e: Exception) {
      Log.e(TAG, "Error adding member: ${e.message}", e)
      Result.failure(e)
    }
  }

  private suspend fun resolveMemberDocRef(memberId: String): DocumentReference {
    val cleanId = memberId.trim()
    val directRef = db.collection("members").document(cleanId)
    if (directRef.get().await().exists()) return directRef
    val byField = db.collection("members")
      .whereEqualTo("memberId", cleanId)
      .limit(1)
      .get()
      .await()
    return byField.documents.firstOrNull()?.reference ?: directRef
  }

  private suspend fun syncMemberMetadataToRelatedDocs(
    memberId: String,
    updatedName: String,
    updatedEmail: String,
    memberRole: String
  ): Int {
    var syncedWithdrawals = 0
    val withdrawals = db.collection("withdrawalRequests")
      .whereEqualTo("memberId", memberId)
      .get()
      .await()
    for (doc in withdrawals.documents) {
      doc.reference.update(
        mapOf(
          "creatorEmail" to updatedEmail,
          "creatorName" to updatedName.ifBlank { "Creator" },
          "updatedAt" to FieldValue.serverTimestamp()
        )
      ).await()
      syncedWithdrawals++
    }

    val chatId = buildCreatorSupportChatId(memberId)
    if (chatId.isNotBlank()) {
      val chatRef = db.collection("supportChats").document(chatId)
      val chatSnap = chatRef.get().await()
      if (chatSnap.exists()) {
        val roleLabel = MemberRole.displayLabel(memberRole)
        val newLabel = "$roleLabel • ${updatedEmail.ifBlank { memberId }}".take(100)
        chatRef.update("deviceLabel", newLabel).await()
      }
    }
    return syncedWithdrawals
  }

  suspend fun updateMemberDetails(
    memberId: String,
    name: String,
    emailOrPhone: String,
    passkey: String,
    isActive: Boolean,
    role: String? = null,
    allowedOptions: List<String>? = null
  ): Result<FirestoreMember> {
    return try {
      val validationError = validateMemberInput(name, emailOrPhone, passkey)
      if (validationError != null) {
        return Result.failure(IllegalArgumentException(validationError))
      }
      val cleanId = memberId.trim()
      val cleanName = name.trim()
      val cleanEmail = emailOrPhone.trim()
      val cleanPass = passkey.trim()

      if (allowedOptions != null && MemberOption.sanitizeKeys(allowedOptions).isEmpty()) {
        return Result.failure(IllegalArgumentException("Please select at least 1 option for this account."))
      }

      val targetRef = resolveMemberDocRef(cleanId)
      val currentSnap = targetRef.get().await()
      if (!currentSnap.exists()) {
        return Result.failure(IllegalArgumentException("Member account no longer exists."))
      }
      val previousMember = parseMemberDoc(currentSnap)
      val canonicalMemberId = previousMember.memberId

      val allMembersSnap = db.collection("members").get().await()
      val emailConflict = allMembersSnap.documents.firstOrNull { doc ->
        doc.id != targetRef.id &&
          (doc.getString("memberId") ?: doc.id) != canonicalMemberId &&
          (doc.getString("emailOrPhone") ?: "").trim().equals(cleanEmail, ignoreCase = true)
      }
      if (emailConflict != null) {
        val conflictName = (emailConflict.getString("name") ?: "Creator").trim()
        return Result.failure(
          IllegalArgumentException(
            "Email '$cleanEmail' is already used by '$conflictName'. Use 'Change Email' button to transfer old data & earnings to that email."
          )
        )
      }

      val resolvedRole = if (role != null) MemberRole.normalize(role) else previousMember.role
      val updates = hashMapOf<String, Any>(
        "name" to cleanName,
        "emailOrPhone" to cleanEmail,
        "passkey" to cleanPass,
        "isActive" to isActive
      )
      if (role != null) {
        updates["role"] = resolvedRole
      }
      if (allowedOptions != null) {
        updates["allowedOptions"] = MemberOption.sanitizeKeys(allowedOptions)
      }
      targetRef.update(updates).await()

      if (!previousMember.emailOrPhone.trim().equals(cleanEmail, ignoreCase = true) ||
        previousMember.name.trim() != cleanName
      ) {
        syncMemberMetadataToRelatedDocs(
          memberId = canonicalMemberId,
          updatedName = cleanName,
          updatedEmail = cleanEmail,
          memberRole = resolvedRole
        )
      }

      val snap = targetRef.get().await()
      val updated = parseMemberDoc(snap)
      Result.success(updated)
    } catch (e: Exception) {
      Log.e(TAG, "Error updating member details: ${e.message}", e)
      Result.failure(e)
    }
  }

  /**
   * Changes a Creator's email while ensuring all uploaded videos, credited views, earnings,
   * withdrawal requests, and support chat history stay intact on the new email.
   * If another Creator account already exists for [newEmail], automatically merges all videos,
   * views, earnings, withdrawal requests, and options from [sourceMemberId] into that target account
   * and retires the old email account.
   */
  suspend fun changeCreatorEmail(
    sourceMemberId: String,
    newEmail: String
  ): Result<CreatorEmailChangeResult> {
    return try {
      val emailError = validateEmailInput(newEmail)
      if (emailError != null) {
        return Result.failure(IllegalArgumentException(emailError))
      }
      val cleanSourceId = sourceMemberId.trim()
      if (cleanSourceId.isBlank()) {
        return Result.failure(IllegalArgumentException("Invalid creator account."))
      }
      val cleanNewEmail = newEmail.trim()

      val sourceRef = resolveMemberDocRef(cleanSourceId)
      val sourceSnap = sourceRef.get().await()
      if (!sourceSnap.exists()) {
        return Result.failure(IllegalArgumentException("Creator account no longer exists."))
      }
      val sourceMember = parseMemberDoc(sourceSnap)
      val canonicalSourceId = sourceMember.memberId
      val previousEmail = sourceMember.emailOrPhone.trim()

      if (previousEmail.equals(cleanNewEmail, ignoreCase = true)) {
        return Result.failure(IllegalArgumentException("New email is the same as the current email."))
      }

      val allMembersSnap = db.collection("members").get().await()
      val existingTargetDoc = allMembersSnap.documents.firstOrNull { doc ->
        doc.id != sourceRef.id &&
          (doc.getString("memberId") ?: doc.id) != canonicalSourceId &&
          (doc.getString("emailOrPhone") ?: "").trim().equals(cleanNewEmail, ignoreCase = true)
      }

      // Case 1: Clean in-place email update (same memberId retains 100% of videos, views, earnings & payouts)
      if (existingTargetDoc == null) {
        sourceRef.update("emailOrPhone", cleanNewEmail).await()
        val activeVideoCount = db.collection("videos")
          .whereEqualTo("uploadedBy", canonicalSourceId)
          .whereEqualTo("isActive", true)
          .get()
          .await()
          .size()
        val inactiveVideoCount = try {
          db.collection("videos")
            .whereEqualTo("uploadedBy", canonicalSourceId)
            .whereEqualTo("isActive", false)
            .get()
            .await()
            .size()
        } catch (_: Exception) {
          0
        }
        val videoCount = activeVideoCount + inactiveVideoCount
        val syncedWithdrawals = syncMemberMetadataToRelatedDocs(
          memberId = canonicalSourceId,
          updatedName = sourceMember.name,
          updatedEmail = cleanNewEmail,
          memberRole = sourceMember.role
        )
        val updatedMember = parseMemberDoc(sourceRef.get().await())
        return Result.success(
          CreatorEmailChangeResult(
            member = updatedMember,
            previousEmail = previousEmail,
            updatedEmail = cleanNewEmail,
            migratedVideosCount = videoCount,
            syncedWithdrawalsCount = syncedWithdrawals,
            mergedExistingAccount = false
          )
        )
      }

      // Case 2: Another account already exists under newEmail -> merge all source data & earnings into it
      val targetRef = existingTargetDoc.reference
      val targetMember = parseMemberDoc(existingTargetDoc)
      val canonicalTargetId = targetMember.memberId
      val resolvedTargetName = targetMember.name.ifBlank { sourceMember.name.ifBlank { "Creator" } }

      // 1) Migrate all uploaded videos from old memberId to target memberId
      val activeSourceVideos = db.collection("videos")
        .whereEqualTo("uploadedBy", canonicalSourceId)
        .whereEqualTo("isActive", true)
        .get()
        .await()
        .documents
      val inactiveSourceVideos = try {
        db.collection("videos")
          .whereEqualTo("uploadedBy", canonicalSourceId)
          .whereEqualTo("isActive", false)
          .get()
          .await()
          .documents
      } catch (_: Exception) {
        emptyList()
      }
      var migratedVideosCount = 0
      for (videoDoc in (activeSourceVideos + inactiveSourceVideos)) {
        videoDoc.reference.update(
          mapOf(
            "uploadedBy" to canonicalTargetId,
            "uploaderName" to resolvedTargetName
          )
        ).await()
        migratedVideosCount++
      }

      // 2) Migrate all withdrawal requests from old memberId to target memberId (keeping financial fields immutable)
      val sourceWithdrawals = db.collection("withdrawalRequests")
        .whereEqualTo("memberId", canonicalSourceId)
        .get()
        .await()
      var syncedWithdrawalsCount = 0
      for (reqDoc in sourceWithdrawals.documents) {
        reqDoc.reference.update(
          mapOf(
            "memberId" to canonicalTargetId,
            "creatorEmail" to cleanNewEmail,
            "creatorName" to resolvedTargetName,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        ).await()
        syncedWithdrawalsCount++
      }

      // Also ensure existing target withdrawals have normalized email/name
      val targetWithdrawals = db.collection("withdrawalRequests")
        .whereEqualTo("memberId", canonicalTargetId)
        .get()
        .await()
      for (reqDoc in targetWithdrawals.documents) {
        reqDoc.reference.update(
          mapOf(
            "creatorEmail" to cleanNewEmail,
            "creatorName" to resolvedTargetName,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        ).await()
        syncedWithdrawalsCount++
      }

      // 3) Atomically combine creditedViews, totalEarning, and options onto target member
      val mergedOptions = MemberOption.sanitizeKeys(targetMember.allowedOptions + sourceMember.allowedOptions)
      val resolvedRate = if (targetMember.viewRatePer1000 > 0.0) {
        targetMember.viewRatePer1000
      } else {
        sourceMember.viewRatePer1000
      }
      val mergedTarget = db.runTransaction { tx ->
        val latestTargetSnap = tx.get(targetRef)
        if (!latestTargetSnap.exists()) {
          throw IllegalArgumentException("Target email account no longer exists.")
        }
        val latestTarget = parseMemberDoc(latestTargetSnap)
        val latestSourceSnap = tx.get(sourceRef)
        val latestSource = if (latestSourceSnap.exists()) parseMemberDoc(latestSourceSnap) else sourceMember

        val combinedViews = latestTarget.creditedViews + latestSource.creditedViews
        val combinedEarning = kotlin.math.round((latestTarget.totalEarning + latestSource.totalEarning) * 100.0) / 100.0
        val combinedHourlyViews = mergeHourlyViews(latestTarget.hourlyViews, latestSource.hourlyViews)

        tx.update(
          targetRef,
          mapOf(
            "creditedViews" to combinedViews,
            "totalEarning" to combinedEarning,
            "viewRatePer1000" to resolvedRate,
            "allowedOptions" to mergedOptions,
            "hourlyViews" to combinedHourlyViews,
            "isActive" to (latestTarget.isActive || latestSource.isActive)
          )
        )
        latestTarget.copy(
          creditedViews = combinedViews,
          totalEarning = combinedEarning,
          viewRatePer1000 = resolvedRate,
          allowedOptions = mergedOptions,
          hourlyViews = combinedHourlyViews,
          isActive = latestTarget.isActive || latestSource.isActive
        )
      }.await()

      // 4) Sync support chat device label on target (and migrate old chat messages if target had none)
      val sourceChatId = buildCreatorSupportChatId(canonicalSourceId)
      val targetChatId = buildCreatorSupportChatId(canonicalTargetId)
      if (targetChatId.isNotBlank()) {
        val targetChatRef = db.collection("supportChats").document(targetChatId)
        val targetChatSnap = targetChatRef.get().await()
        val roleLabel = MemberRole.displayLabel(mergedTarget.role)
        val updatedDeviceLabel = "$roleLabel • $cleanNewEmail".take(100)
        if (targetChatSnap.exists()) {
          targetChatRef.update("deviceLabel", updatedDeviceLabel).await()
        } else if (sourceChatId.isNotBlank()) {
          val sourceChatRef = db.collection("supportChats").document(sourceChatId)
          val sourceChatSnap = sourceChatRef.get().await()
          if (sourceChatSnap.exists()) {
            val sourceMsgs = sourceChatRef.collection("messages").get().await()
            val lastMessage = (sourceChatSnap.getString("lastMessage") ?: "Account email updated").take(1000)
            targetChatRef.set(
              mapOf(
                "chatId" to targetChatId,
                "userName" to "$resolvedTargetName (Creator)".take(80),
                "deviceLabel" to updatedDeviceLabel,
                "lastMessage" to lastMessage,
                "lastSenderRole" to SupportSenderRole.USER,
                "lastMessageAt" to FieldValue.serverTimestamp(),
                "createdAt" to FieldValue.serverTimestamp(),
                "unreadByAdminCount" to (sourceChatSnap.getLong("unreadByAdminCount") ?: 0L).coerceAtLeast(0L),
                "unreadByUserCount" to 0L,
                "status" to (sourceChatSnap.getString("status") ?: SupportChatStatus.OPEN)
              )
            ).await()
            for (msgDoc in sourceMsgs.documents) {
              val msgId = msgDoc.getString("messageId") ?: msgDoc.id
              val msgRole = msgDoc.getString("senderRole") ?: SupportSenderRole.USER
              val msgSender = (msgDoc.getString("senderName") ?: resolvedTargetName).take(80)
              val msgText = (msgDoc.getString("text") ?: "").take(1000)
              if (msgText.isNotBlank()) {
                targetChatRef.collection("messages").document(msgId).set(
                  mapOf(
                    "messageId" to msgId,
                    "chatId" to targetChatId,
                    "senderRole" to msgRole,
                    "senderName" to msgSender,
                    "text" to msgText,
                    "createdAt" to (msgDoc.getTimestamp("createdAt") ?: FieldValue.serverTimestamp())
                  )
                ).await()
              }
            }
          }
        }
      }

      // 5) Retire old member account so old email login no longer splits future uploads
      deleteMember(canonicalSourceId)

      Result.success(
        CreatorEmailChangeResult(
          member = mergedTarget,
          previousEmail = previousEmail,
          updatedEmail = cleanNewEmail,
          migratedVideosCount = migratedVideosCount,
          syncedWithdrawalsCount = syncedWithdrawalsCount,
          mergedExistingAccount = true
        )
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error changing creator email: ${e.message}", e)
      Result.failure(e)
    }
  }

  suspend fun changeMemberPasskey(
    memberId: String,
    currentPasskey: String,
    newPasskey: String,
    confirmPasskey: String = newPasskey
  ): Result<FirestoreMember> {
    return try {
      val cleanId = memberId.trim()
      if (cleanId.isBlank()) {
        return Result.failure(IllegalArgumentException("Member session not found."))
      }
      val snap = db.collection("members").document(cleanId).get().await()
      if (!snap.exists()) {
        return Result.failure(IllegalArgumentException("Member account no longer exists."))
      }
      val storedPasskey = (snap.getString("passkey") ?: "").trim()
      val validationError = validatePasskeyChange(
        storedPasskey = storedPasskey,
        currentInput = currentPasskey,
        newPasskey = newPasskey,
        confirmPasskey = confirmPasskey
      )
      if (validationError != null) {
        return Result.failure(IllegalArgumentException(validationError))
      }

      val cleanNew = newPasskey.trim()

      db.collection("members").document(cleanId).update("passkey", cleanNew).await()

      Result.success(parseMemberDoc(snap).copy(passkey = cleanNew))
    } catch (e: Exception) {
      Log.e(TAG, "Error changing member passkey: ${e.message}", e)
      Result.failure(e)
    }
  }

  suspend fun toggleMemberActive(memberId: String, isActive: Boolean): Result<Unit> {
    return try {
      db.collection("members").document(memberId).update("isActive", isActive).await()
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun deleteMember(memberId: String): Result<Unit> {
    return try {
      val cleanId = memberId.trim()
      if (cleanId.isBlank()) {
        return Result.failure(IllegalArgumentException("Invalid member ID."))
      }
      db.collection("members").document(cleanId).delete().await()
      val matchingByField = db.collection("members")
        .whereEqualTo("memberId", cleanId)
        .get()
        .await()
      for (doc in matchingByField.documents) {
        if (doc.id != cleanId) {
          doc.reference.delete().await()
        }
      }
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Error deleting member $memberId: ${e.message}", e)
      Result.failure(e)
    }
  }

  suspend fun getMemberByPasskey(inputKey: String): FirestoreMember? {
    return try {
      val snap = db.collection("members")
        .whereEqualTo("passkey", inputKey.trim())
        .limit(1)
        .get()
        .await()
      val doc = snap.documents.firstOrNull() ?: return null
      val isActive = doc.getBoolean("isActive") ?: false
      if (!isActive) return null
      parseMemberDoc(doc)
    } catch (e: Exception) {
      Log.w(TAG, "getMemberByPasskey failed: ${e.message}")
      null
    }
  }

  suspend fun getMemberByEmailAndPasskey(email: String, inputKey: String): FirestoreMember? {
    val cleanEmail = email.trim().lowercase()
    val cleanKey = inputKey.trim()
    if (cleanEmail.isBlank() || cleanKey.isBlank()) return null
    return try {
      val snap = db.collection("members")
        .whereEqualTo("passkey", cleanKey)
        .get()
        .await()
      val doc = snap.documents.firstOrNull { d ->
        (d.getString("emailOrPhone") ?: "").trim().lowercase() == cleanEmail
      } ?: run {
        // Fallback scan in case of subtle whitespace in stored emailOrPhone
        val all = db.collection("members").get().await()
        all.documents.firstOrNull { d ->
          (d.getString("emailOrPhone") ?: "").trim().lowercase() == cleanEmail &&
            (d.getString("passkey") ?: "").trim() == cleanKey
        }
      } ?: return null

      val isActive = doc.getBoolean("isActive") ?: false
      if (!isActive) return null
      parseMemberDoc(doc)
    } catch (e: Exception) {
      Log.w(TAG, "getMemberByEmailAndPasskey failed: ${e.message}")
      null
    }
  }

  suspend fun getMemberById(memberId: String): FirestoreMember? {
    val cleanId = memberId.trim()
    if (cleanId.isBlank()) return null
    return try {
      val ref = resolveMemberDocRef(cleanId)
      val doc = ref.get().await()
      if (!doc.exists()) return null
      parseMemberDoc(doc)
    } catch (e: Exception) {
      Log.w(TAG, "getMemberById failed: ${e.message}")
      null
    }
  }

  private suspend fun resolveAuthBackendBaseUrl(): String {
    return try {
      val snap = db.collection("appConfig").document("general").get().await()
      val explicit = snap.getString("authBackendBaseUrl").orEmpty().trim().trimEnd('/')
      if (explicit.startsWith("http://", ignoreCase = true) || explicit.startsWith("https://", ignoreCase = true)) {
        return explicit
      }
      val shareBase = snap.getString("shareLinkBaseUrl").orEmpty().trim()
      extractRenderOriginFromShareUrl(shareBase).ifBlank { DEFAULT_AUTH_BACKEND_BASE_URL }
    } catch (_: Exception) {
      DEFAULT_AUTH_BACKEND_BASE_URL
    }
  }

  private suspend fun postAuthJson(endpointPath: String, jsonBody: org.json.JSONObject): Pair<Int, org.json.JSONObject> =
    withContext(Dispatchers.IO) {
      val baseUrl = resolveAuthBackendBaseUrl().trimEnd('/')
      val url = URL("$baseUrl$endpointPath")
      val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 20_000
        readTimeout = 35_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        setRequestProperty("Accept", "application/json")
      }
      try {
        conn.outputStream.use { os ->
          os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val parsed = try {
          if (raw.isNotBlank()) org.json.JSONObject(raw) else org.json.JSONObject()
        } catch (_: Exception) {
          org.json.JSONObject().put("error", raw.ifBlank { "Server error ($code)" })
        }
        code to parsed
      } finally {
        conn.disconnect()
      }
    }

  private fun parseMemberJson(memberObj: org.json.JSONObject, fallbackPasskey: String): FirestoreMember {
    val allowedArr = memberObj.optJSONArray("allowedOptions")
    val parsedOptions = buildList {
      if (allowedArr != null) {
        for (i in 0 until allowedArr.length()) {
          val k = allowedArr.optString(i).trim()
          if (k.isNotBlank()) add(k)
        }
      }
    }
    val role = MemberRole.normalize(memberObj.optString("role", MemberRole.CREATOR))
    return FirestoreMember(
      memberId = memberObj.optString("memberId", ""),
      name = memberObj.optString("name", "Creator"),
      emailOrPhone = memberObj.optString("emailOrPhone", ""),
      passkey = memberObj.optString("passkey", "").ifBlank { fallbackPasskey },
      role = role,
      isActive = memberObj.optBoolean("isActive", true),
      createdAt = Timestamp.now(),
      totalEarning = memberObj.optDouble("totalEarning", 0.0),
      creditedViews = memberObj.optLong("creditedViews", 0L),
      viewRatePer1000 = memberObj.optDouble("viewRatePer1000", DEFAULT_VIEW_RATE_PER_1000),
      allowedOptions = if (parsedOptions.isNotEmpty()) MemberOption.sanitizeKeys(parsedOptions) else MemberOption.defaultKeysForRole(role)
    )
  }

  suspend fun sendSignupOtp(name: String, email: String, password: String): Result<String> {
    val validationErr = validateMemberInput(name, email, password)
    if (validationErr != null) {
      return Result.failure(IllegalArgumentException(validationErr))
    }
    return try {
      val payload = org.json.JSONObject()
        .put("name", name.trim())
        .put("email", email.trim().lowercase())
        .put("password", password.trim())
      val (code, json) = postAuthJson("/auth/send-signup-otp", payload)
      if (code in 200..299 && json.optBoolean("ok", false)) {
        Result.success(json.optString("message", "OTP Gmail par bhej diya gaya hai!"))
      } else {
        val msg = json.optString("error").ifBlank { "OTP send karne me problem aayi (HTTP $code)." }
        Result.failure(IllegalStateException(msg))
      }
    } catch (e: Exception) {
      Log.w(TAG, "sendSignupOtp failed: ${e.message}", e)
      Result.failure(IllegalStateException("Server se connect nahi ho paya. Thodi der baad try karein."))
    }
  }

  suspend fun verifySignupOtpAndCreateMember(
    name: String,
    email: String,
    password: String,
    otp: String
  ): Result<FirestoreMember> {
    val validationErr = validateMemberInput(name, email, password)
    if (validationErr != null) {
      return Result.failure(IllegalArgumentException(validationErr))
    }
    val cleanOtp = otp.trim()
    if (!cleanOtp.matches(Regex("^\\d{6}$"))) {
      return Result.failure(IllegalArgumentException("Kripya 6-digit numeric OTP enter karein."))
    }
    return try {
      val payload = org.json.JSONObject()
        .put("name", name.trim())
        .put("email", email.trim().lowercase())
        .put("password", password.trim())
        .put("otp", cleanOtp)
      val (code, json) = postAuthJson("/auth/verify-signup-otp", payload)
      if (code in 200..299 && json.optBoolean("ok", false)) {
        val memberObj = json.optJSONObject("member")
          ?: return Result.failure(IllegalStateException("Account verified, please sign in on Login tab."))
        val parsed = parseMemberJson(memberObj, password.trim())
        val freshFromFirestore = if (parsed.memberId.isNotBlank()) getMemberById(parsed.memberId) else null
        Result.success(freshFromFirestore ?: parsed)
      } else {
        val msg = json.optString("error").ifBlank { "OTP verify nahi ho paya (HTTP $code)." }
        Result.failure(IllegalStateException(msg))
      }
    } catch (e: Exception) {
      Log.w(TAG, "verifySignupOtp failed: ${e.message}", e)
      Result.failure(IllegalStateException("Server se connect nahi ho paya. Thodi der baad try karein."))
    }
  }

  suspend fun sendResetPasswordOtp(email: String): Result<String> {
    val emailErr = validateEmailInput(email)
    if (emailErr != null) {
      return Result.failure(IllegalArgumentException(emailErr))
    }
    return try {
      val payload = org.json.JSONObject()
        .put("email", email.trim().lowercase())
      val (code, json) = postAuthJson("/auth/send-reset-otp", payload)
      if (code in 200..299 && json.optBoolean("ok", false)) {
        Result.success(json.optString("message", "Reset OTP Gmail par bhej diya gaya hai!"))
      } else {
        val msg = json.optString("error").ifBlank { "Reset OTP send nahi ho paya (HTTP $code)." }
        Result.failure(IllegalStateException(msg))
      }
    } catch (e: Exception) {
      Log.w(TAG, "sendResetPasswordOtp failed: ${e.message}", e)
      Result.failure(IllegalStateException("Server se connect nahi ho paya. Thodi der baad try karein."))
    }
  }

  suspend fun verifyResetPasswordOtp(
    email: String,
    otp: String,
    newPassword: String
  ): Result<FirestoreMember> {
    val emailErr = validateEmailInput(email)
    if (emailErr != null) return Result.failure(IllegalArgumentException(emailErr))
    val cleanPass = newPassword.trim()
    if (cleanPass.length < MIN_PASSKEY_LENGTH) {
      return Result.failure(IllegalArgumentException("Password must be at least $MIN_PASSKEY_LENGTH characters."))
    }
    val cleanOtp = otp.trim()
    if (!cleanOtp.matches(Regex("^\\d{6}$"))) {
      return Result.failure(IllegalArgumentException("Kripya 6-digit numeric OTP enter karein."))
    }
    return try {
      val payload = org.json.JSONObject()
        .put("email", email.trim().lowercase())
        .put("otp", cleanOtp)
        .put("newPassword", cleanPass)
      val (code, json) = postAuthJson("/auth/verify-reset-otp", payload)
      if (code in 200..299 && json.optBoolean("ok", false)) {
        val memberObj = json.optJSONObject("member")
          ?: return Result.failure(IllegalStateException("Password reset ho gaya, ab Login tab se sign in karein."))
        val parsed = parseMemberJson(memberObj, cleanPass)
        val freshFromFirestore = if (parsed.memberId.isNotBlank()) getMemberById(parsed.memberId) else null
        Result.success(freshFromFirestore ?: parsed)
      } else {
        val msg = json.optString("error").ifBlank { "Password reset failed (HTTP $code)." }
        Result.failure(IllegalStateException(msg))
      }
    } catch (e: Exception) {
      Log.w(TAG, "verifyResetPasswordOtp failed: ${e.message}", e)
      Result.failure(IllegalStateException("Server se connect nahi ho paya. Thodi der baad try karein."))
    }
  }

  // --- Creator Earnings & UPI Withdrawal Requests ---

  suspend fun updateMemberEarning(memberId: String, newTotalEarning: Double): Result<Unit> {
    return try {
      val safeEarning = kotlin.math.round(newTotalEarning.coerceAtLeast(0.0) * 100.0) / 100.0
      db.collection("members").document(memberId)
        .update("totalEarning", safeEarning)
        .await()
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Error updating member earning: ${e.message}", e)
      Result.failure(e)
    }
  }

  suspend fun creditCreatorViewsAndEarning(
    memberId: String,
    viewsInput: Long,
    ratePer1000: Double = DEFAULT_VIEW_RATE_PER_1000,
    isAddition: Boolean = true
  ): Result<FirestoreMember> {
    return try {
      val cleanId = memberId.trim()
      if (cleanId.isBlank()) {
        return Result.failure(IllegalArgumentException("Invalid member ID."))
      }
      if (viewsInput < 0L || (isAddition && viewsInput <= 0L)) {
        return Result.failure(IllegalArgumentException("Please enter a valid views count."))
      }
      if (ratePer1000.isNaN() || ratePer1000.isInfinite() || ratePer1000 < 0.0) {
        return Result.failure(IllegalArgumentException("Rate per 1,000 views must be non-negative."))
      }

      val directRef = db.collection("members").document(cleanId)
      val targetRef = if (directRef.get().await().exists()) {
        directRef
      } else {
        val byField = db.collection("members")
          .whereEqualTo("memberId", cleanId)
          .limit(1)
          .get()
          .await()
        byField.documents.firstOrNull()?.reference ?: directRef
      }

      val updatedMember = db.runTransaction { tx ->
        val snap = tx.get(targetRef)
        if (!snap.exists()) {
          throw IllegalArgumentException("Member account no longer exists.")
        }
        val currentMember = parseMemberDoc(snap)
        val earningDeltaOrTotal = calculateViewsEarning(viewsInput, ratePer1000)
        val newCreditedViews = if (isAddition) {
          currentMember.creditedViews + viewsInput
        } else {
          viewsInput
        }
        val newTotalEarning = if (isAddition) {
          kotlin.math.round((currentMember.totalEarning + earningDeltaOrTotal) * 100.0) / 100.0
        } else {
          earningDeltaOrTotal
        }

        tx.update(
          targetRef,
          mapOf(
            "creditedViews" to newCreditedViews,
            "viewRatePer1000" to ratePer1000,
            "totalEarning" to newTotalEarning
          )
        )
        currentMember.copy(
          creditedViews = newCreditedViews,
          viewRatePer1000 = ratePer1000,
          totalEarning = newTotalEarning
        )
      }.await()

      Result.success(updatedMember)
    } catch (e: Exception) {
      Log.e(TAG, "Error crediting creator views & earning: ${e.message}", e)
      Result.failure(e)
    }
  }

  fun getAllWithdrawalRequestsFlow(): Flow<List<FirestoreWithdrawalRequest>> = callbackFlow {
    val registration = db.collection("withdrawalRequests")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Listen warning on withdrawalRequests: ${error.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val requests = snapshot?.documents?.mapNotNull { doc ->
          try {
            FirestoreWithdrawalRequest(
              requestId = doc.getString("requestId") ?: doc.id,
              memberId = doc.getString("memberId") ?: "",
              creatorName = doc.getString("creatorName") ?: "",
              creatorEmail = doc.getString("creatorEmail") ?: "",
              amount = (doc.get("amount") as? Number)?.toDouble() ?: 0.0,
              upiId = doc.getString("upiId") ?: "",
              status = doc.getString("status") ?: WithdrawalStatus.PENDING,
              adminNote = doc.getString("adminNote") ?: "",
              requestedAt = doc.getTimestamp("requestedAt") ?: Timestamp.now(),
              updatedAt = doc.getTimestamp("updatedAt")
            )
          } catch (_: Exception) {
            null
          }
        }?.sortedByDescending { it.requestedAt.toDate().time } ?: emptyList()
        trySend(requests)
      }
    awaitClose { registration.remove() }
  }

  suspend fun submitWithdrawalRequest(
    member: FirestoreMember,
    amount: Double,
    upiId: String
  ): Result<FirestoreWithdrawalRequest> {
    return try {
      val cleanUpi = upiId.trim()
      if (!isValidUpiId(cleanUpi)) {
        return Result.failure(IllegalArgumentException("Please enter a valid UPI ID (e.g. name@ybl)"))
      }
      if (amount < MIN_WITHDRAWAL_AMOUNT) {
        return Result.failure(IllegalArgumentException("Minimum withdrawal amount is ₹500"))
      }

      // Verify live creator balance from Firestore before creating request
      val memberSnap = db.collection("members").document(member.memberId).get().await()
      val liveTotalEarning = (memberSnap.get("totalEarning") as? Number)?.toDouble() ?: member.totalEarning

      val existingReqs = db.collection("withdrawalRequests")
        .whereEqualTo("memberId", member.memberId)
        .get()
        .await()

      val reservedAmount = existingReqs.documents.sumOf { doc ->
        val status = doc.getString("status") ?: WithdrawalStatus.PENDING
        if (status != WithdrawalStatus.REJECTED) {
          (doc.get("amount") as? Number)?.toDouble() ?: 0.0
        } else {
          0.0
        }
      }

      val availableBalance = (liveTotalEarning - reservedAmount).coerceAtLeast(0.0)
      if (amount > availableBalance + 0.001) {
        return Result.failure(
          IllegalArgumentException("Insufficient available balance (Available: ₹${availableBalance.toLong()})")
        )
      }

      val requestId = "wdr_" + UUID.randomUUID().toString().replace("-", "").take(12)
      val payload = hashMapOf(
        "requestId" to requestId,
        "memberId" to member.memberId,
        "creatorName" to member.name.ifBlank { "Creator" },
        "creatorEmail" to member.emailOrPhone,
        "amount" to amount,
        "upiId" to cleanUpi,
        "status" to WithdrawalStatus.PENDING,
        "adminNote" to "",
        "requestedAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
      )

      db.collection("withdrawalRequests").document(requestId).set(payload).await()

      Result.success(
        FirestoreWithdrawalRequest(
          requestId = requestId,
          memberId = member.memberId,
          creatorName = member.name.ifBlank { "Creator" },
          creatorEmail = member.emailOrPhone,
          amount = amount,
          upiId = cleanUpi,
          status = WithdrawalStatus.PENDING,
          adminNote = "",
          requestedAt = Timestamp.now(),
          updatedAt = Timestamp.now()
        )
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error submitting withdrawal request: ${e.message}", e)
      Result.failure(e)
    }
  }

  suspend fun updateWithdrawalStatus(
    requestId: String,
    newStatus: String,
    adminNote: String
  ): Result<Unit> {
    return try {
      val allowed = setOf(
        WithdrawalStatus.PENDING,
        WithdrawalStatus.PROCESSING,
        WithdrawalStatus.DONE,
        WithdrawalStatus.REJECTED
      )
      if (newStatus !in allowed) {
        return Result.failure(IllegalArgumentException("Invalid payout status: $newStatus"))
      }
      db.collection("withdrawalRequests").document(requestId)
        .update(
          mapOf(
            "status" to newStatus,
            "adminNote" to adminNote.trim(),
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
        .await()
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Error updating withdrawal status: ${e.message}", e)
      Result.failure(e)
    }
  }

  // --- Superadmin Broadcast Notifications ---

  fun getNotificationsFlow(): Flow<List<FirestoreNotification>> = callbackFlow {
    val registration = db.collection("notifications")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Listen warning on notifications: ${error.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val items = snapshot?.documents?.mapNotNull { doc ->
          try {
            val isActive = doc.getBoolean("isActive") ?: true
            if (!isActive) return@mapNotNull null
            FirestoreNotification(
              notificationId = doc.getString("notificationId") ?: doc.id,
              title = doc.getString("title") ?: "",
              message = doc.getString("message") ?: "",
              imageUrl = doc.getString("imageUrl") ?: "",
              actionUrl = doc.getString("actionUrl") ?: "",
              sentBy = doc.getString("sentBy") ?: "Admin",
              createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now(),
              isActive = isActive
            )
          } catch (_: Exception) {
            null
          }
        }?.sortedByDescending { it.createdAt.toDate().time } ?: emptyList()
        trySend(items)
      }
    awaitClose { registration.remove() }
  }

  private suspend fun encodeCompressedImageDataUri(
    imageUri: Uri,
    maxDim: Int = 1280,
    maxBytes: Int = 220_000,
    hardCapBytes: Int = 240_000
  ): String? = withContext(Dispatchers.IO) {
    val ctx = appContext ?: return@withContext null
    try {
      val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      ctx.contentResolver.openInputStream(imageUri)?.use { input ->
        BitmapFactory.decodeStream(input, null, boundsOptions)
      }
      var sampleSize = 1
      while ((boundsOptions.outWidth / sampleSize) > maxDim * 2 ||
        (boundsOptions.outHeight / sampleSize) > maxDim * 2
      ) {
        sampleSize *= 2
      }
      val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
      val decoded = ctx.contentResolver.openInputStream(imageUri)?.use { input ->
        BitmapFactory.decodeStream(input, null, decodeOptions)
      } ?: return@withContext null

      val scale = minOf(
        1f,
        maxDim.toFloat() / decoded.width.coerceAtLeast(1),
        maxDim.toFloat() / decoded.height.coerceAtLeast(1)
      )
      val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
          decoded,
          (decoded.width * scale).toInt().coerceAtLeast(1),
          (decoded.height * scale).toInt().coerceAtLeast(1),
          true
        )
      } else {
        decoded
      }

      var quality = 90
      var bytes: ByteArray
      do {
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        bytes = baos.toByteArray()
        quality -= 10
      } while (bytes.size > maxBytes && quality >= 50)

      if (scaled !== decoded) scaled.recycle()
      decoded.recycle()

      if (bytes.size > hardCapBytes) {
        return@withContext null
      }
      val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
      "data:image/jpeg;base64,$base64"
    } catch (e: Exception) {
      Log.w(TAG, "Failed to encode image: ${e.message}")
      null
    }
  }

  suspend fun sendBroadcastNotification(
    title: String,
    message: String,
    imageUri: Uri? = null,
    imageUrlInput: String = "",
    actionUrl: String = "",
    sentBy: String = "Superadmin"
  ): Result<FirestoreNotification> {
    return try {
      val resolvedActionUrl = normalizeNotificationActionUrl(actionUrl)
      val resolvedImageUrl = if (imageUri != null) {
        encodeCompressedImageDataUri(imageUri)
          ?: return Result.failure(
            IllegalArgumentException("Could not compress selected image. Please pick a smaller image or paste an Image URL.")
          )
      } else {
        imageUrlInput.trim()
      }

      val validationError = validateNotificationInput(
        title = title,
        message = message,
        imageUrl = resolvedImageUrl,
        actionUrl = resolvedActionUrl
      )
      if (validationError != null) {
        return Result.failure(IllegalArgumentException(validationError))
      }
      val cleanTitle = title.trim()
      val cleanMessage = message.trim()
      val cleanSentBy = sentBy.trim().ifBlank { "Superadmin" }.take(120)
      val notificationId = "ntf_" + UUID.randomUUID().toString().replace("-", "").take(12)

      val payload = hashMapOf<String, Any>(
        "notificationId" to notificationId,
        "title" to cleanTitle,
        "message" to cleanMessage,
        "imageUrl" to resolvedImageUrl,
        "actionUrl" to resolvedActionUrl,
        "sentBy" to cleanSentBy,
        "createdAt" to FieldValue.serverTimestamp(),
        "isActive" to true
      )

      db.collection("notifications").document(notificationId).set(payload).await()

      Result.success(
        FirestoreNotification(
          notificationId = notificationId,
          title = cleanTitle,
          message = cleanMessage,
          imageUrl = resolvedImageUrl,
          actionUrl = resolvedActionUrl,
          sentBy = cleanSentBy,
          createdAt = Timestamp.now(),
          isActive = true
        )
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error sending broadcast notification: ${e.message}", e)
      Result.failure(e)
    }
  }

  suspend fun deleteNotification(notificationId: String): Result<Unit> {
    return try {
      db.collection("notifications").document(notificationId).delete().await()
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Error deleting notification: ${e.message}", e)
      Result.failure(e)
    }
  }

  // --- Creator Gift & Announcement Events ---

  fun getCreatorEventsFlow(): Flow<List<FirestoreCreatorEvent>> = callbackFlow {
    val registration = db.collection("creatorEvents")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Listen warning on creatorEvents: ${error.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val items = snapshot?.documents?.mapNotNull { doc ->
          try {
            val createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now()
            val updatedAt = doc.getTimestamp("updatedAt") ?: createdAt
            FirestoreCreatorEvent(
              eventId = doc.getString("eventId") ?: doc.id,
              title = doc.getString("title") ?: "",
              description = doc.getString("description") ?: "",
              giftLabel = doc.getString("giftLabel") ?: "",
              imageUrl = doc.getString("imageUrl") ?: "",
              createdBy = doc.getString("createdBy") ?: "Superadmin",
              createdAt = createdAt,
              updatedAt = updatedAt,
              isActive = doc.getBoolean("isActive") ?: true
            )
          } catch (_: Exception) {
            null
          }
        }?.sortedWith(
          compareByDescending<FirestoreCreatorEvent> { it.updatedAt.toDate().time }
            .thenByDescending { it.createdAt.toDate().time }
        ) ?: emptyList()
        trySend(items)
      }
    awaitClose { registration.remove() }
  }

  suspend fun createCreatorEvent(
    title: String,
    description: String,
    giftLabel: String = "",
    imageUri: Uri? = null,
    imageUrlInput: String = "",
    isActive: Boolean = true,
    createdBy: String = "Superadmin"
  ): Result<FirestoreCreatorEvent> {
    return try {
      val resolvedImageUrl = if (imageUri != null) {
        encodeCompressedImageDataUri(imageUri)
          ?: return Result.failure(
            IllegalArgumentException("Could not compress selected image. Please pick a smaller image or paste an HTTPS Image URL.")
          )
      } else {
        imageUrlInput.trim()
      }

      val validationError = validateCreatorEventInput(
        title = title,
        description = description,
        giftLabel = giftLabel,
        imageUrl = resolvedImageUrl
      )
      if (validationError != null) {
        return Result.failure(IllegalArgumentException(validationError))
      }

      val cleanTitle = title.trim()
      val cleanDescription = description.trim()
      val cleanGiftLabel = giftLabel.trim()
      val cleanCreatedBy = createdBy.trim().ifBlank { "Superadmin" }.take(120)
      val eventId = "evt_" + UUID.randomUUID().toString().replace("-", "").take(12)

      val payload = hashMapOf<String, Any>(
        "eventId" to eventId,
        "title" to cleanTitle,
        "description" to cleanDescription,
        "giftLabel" to cleanGiftLabel,
        "imageUrl" to resolvedImageUrl,
        "createdBy" to cleanCreatedBy,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
        "isActive" to isActive
      )

      db.collection("creatorEvents").document(eventId).set(payload).await()

      Result.success(
        FirestoreCreatorEvent(
          eventId = eventId,
          title = cleanTitle,
          description = cleanDescription,
          giftLabel = cleanGiftLabel,
          imageUrl = resolvedImageUrl,
          createdBy = cleanCreatedBy,
          createdAt = Timestamp.now(),
          updatedAt = Timestamp.now(),
          isActive = isActive
        )
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error creating creator event: ${e.message}", e)
      Result.failure(e)
    }
  }

  suspend fun updateCreatorEvent(
    eventId: String,
    title: String,
    description: String,
    giftLabel: String = "",
    imageUri: Uri? = null,
    imageUrlInput: String = "",
    isActive: Boolean = true
  ): Result<Unit> {
    return try {
      val cleanId = eventId.trim()
      if (cleanId.isBlank()) {
        return Result.failure(IllegalArgumentException("Missing event ID."))
      }
      val resolvedImageUrl = if (imageUri != null) {
        encodeCompressedImageDataUri(imageUri)
          ?: return Result.failure(
            IllegalArgumentException("Could not compress selected image. Please pick a smaller image or paste an HTTPS Image URL.")
          )
      } else {
        imageUrlInput.trim()
      }

      val validationError = validateCreatorEventInput(
        title = title,
        description = description,
        giftLabel = giftLabel,
        imageUrl = resolvedImageUrl
      )
      if (validationError != null) {
        return Result.failure(IllegalArgumentException(validationError))
      }

      val updates = hashMapOf<String, Any>(
        "title" to title.trim(),
        "description" to description.trim(),
        "giftLabel" to giftLabel.trim(),
        "imageUrl" to resolvedImageUrl,
        "isActive" to isActive,
        "updatedAt" to FieldValue.serverTimestamp()
      )

      db.collection("creatorEvents").document(cleanId).update(updates).await()
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Error updating creator event $eventId: ${e.message}", e)
      Result.failure(e)
    }
  }

  suspend fun deleteCreatorEvent(eventId: String): Result<Unit> {
    return try {
      val cleanId = eventId.trim()
      if (cleanId.isBlank()) {
        return Result.failure(IllegalArgumentException("Missing event ID."))
      }
      db.collection("creatorEvents").document(cleanId).delete().await()
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Error deleting creator event $eventId: ${e.message}", e)
      Result.failure(e)
    }
  }

  // --- App Installation, Active & Estimated Uninstall Analytics ---

  fun getAllAppInstallsFlow(): Flow<List<FirestoreAppInstall>> = callbackFlow {
    val registration = db.collection("appInstalls")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Listen warning on appInstalls: ${error.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val items = snapshot?.documents?.mapNotNull { doc ->
          try {
            FirestoreAppInstall(
              installId = doc.getString("installId") ?: doc.id,
              firstInstalledAt = doc.getTimestamp("firstInstalledAt") ?: Timestamp.now(),
              lastSeenAt = doc.getTimestamp("lastSeenAt") ?: Timestamp.now(),
              appVersion = doc.getString("appVersion") ?: "1.0",
              deviceLabel = doc.getString("deviceLabel") ?: "Android Device",
              androidVersion = doc.getString("androidVersion") ?: "Android",
              sessionCount = (doc.get("sessionCount") as? Number)?.toLong() ?: 1L,
              isMarkedUninstalled = doc.getBoolean("isMarkedUninstalled") ?: false
            )
          } catch (_: Exception) {
            null
          }
        }?.sortedByDescending { it.lastSeenAt.toDate().time } ?: emptyList()
        trySend(items)
      }
    awaitClose { registration.remove() }
  }

  suspend fun registerOrHeartbeatAppInstall(context: Context = appContext ?: error("Context required")) {
    try {
      val prefs = context.applicationContext.getSharedPreferences(INSTALL_PREFS_NAME, Context.MODE_PRIVATE)
      var installId = prefs.getString(KEY_INSTALL_ID, null)?.trim().orEmpty()
      if (installId.isBlank() || !installId.matches(Regex("^[a-zA-Z0-9_\\-]{6,64}$"))) {
        installId = "inst_" + UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(KEY_INSTALL_ID, installId).putBoolean(KEY_SERVER_SYNCED, false).apply()
      }

      val isServerSynced = prefs.getBoolean(KEY_SERVER_SYNCED, false)
      val lastHeartbeatMs = prefs.getLong(KEY_LAST_HEARTBEAT_MS, 0L)
      val lastRecordedVersion = prefs.getString(KEY_LAST_VERSION, null)
      val nowMs = System.currentTimeMillis()
      val currentVersion = BuildConfig.VERSION_NAME.trim().ifBlank { "1.0" }.take(40)
      val versionChanged = lastRecordedVersion != currentVersion

      // Throttle subsequent heartbeats to at most once per hour unless version changed
      if (isServerSynced && !versionChanged && (nowMs - lastHeartbeatMs) < HEARTBEAT_THROTTLE_MS) {
        return
      }

      val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
      val model = Build.MODEL?.trim().orEmpty()
      val rawLabel = when {
        manufacturer.isBlank() && model.isBlank() -> "Android Device"
        model.startsWith(manufacturer, ignoreCase = true) -> model
        manufacturer.isBlank() -> model
        model.isBlank() -> manufacturer
        else -> "$manufacturer $model"
      }
      val safeDeviceLabel = rawLabel.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
      }.take(100)
      val safeAndroidVersion = "Android ${Build.VERSION.RELEASE ?: "Unknown"}".take(40)

      val docRef = db.collection("appInstalls").document(installId)
      if (!isServerSynced) {
        val initialPayload = hashMapOf<String, Any>(
          "installId" to installId,
          "firstInstalledAt" to FieldValue.serverTimestamp(),
          "lastSeenAt" to FieldValue.serverTimestamp(),
          "appVersion" to currentVersion,
          "deviceLabel" to safeDeviceLabel,
          "androidVersion" to safeAndroidVersion,
          "sessionCount" to 1L,
          "isMarkedUninstalled" to false
        )
        docRef.set(initialPayload).await()
        prefs.edit()
          .putBoolean(KEY_SERVER_SYNCED, true)
          .putLong(KEY_LAST_HEARTBEAT_MS, nowMs)
          .putString(KEY_LAST_VERSION, currentVersion)
          .apply()
      } else {
        try {
          docRef.update(
            mapOf(
              "lastSeenAt" to FieldValue.serverTimestamp(),
              "appVersion" to currentVersion,
              "deviceLabel" to safeDeviceLabel,
              "androidVersion" to safeAndroidVersion,
              "sessionCount" to FieldValue.increment(1L),
              "isMarkedUninstalled" to false
            )
          ).await()
        } catch (e: FirebaseFirestoreException) {
          if (e.code == FirebaseFirestoreException.Code.NOT_FOUND) {
            val recreatePayload = hashMapOf<String, Any>(
              "installId" to installId,
              "firstInstalledAt" to FieldValue.serverTimestamp(),
              "lastSeenAt" to FieldValue.serverTimestamp(),
              "appVersion" to currentVersion,
              "deviceLabel" to safeDeviceLabel,
              "androidVersion" to safeAndroidVersion,
              "sessionCount" to 1L,
              "isMarkedUninstalled" to false
            )
            docRef.set(recreatePayload).await()
          } else {
            throw e
          }
        }
        prefs.edit()
          .putLong(KEY_LAST_HEARTBEAT_MS, nowMs)
          .putString(KEY_LAST_VERSION, currentVersion)
          .apply()
      }
    } catch (e: Exception) {
      Log.w(TAG, "App install heartbeat skipped: ${e.message}")
    }
  }

  suspend fun setInstallMarkedUninstalled(installId: String, markedUninstalled: Boolean): Result<Unit> {
    return try {
      db.collection("appInstalls").document(installId)
        .update("isMarkedUninstalled", markedUninstalled)
        .await()
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Error updating install status: ${e.message}", e)
      Result.failure(e)
    }
  }

  suspend fun deleteAppInstallRecord(installId: String): Result<Unit> {
    return try {
      db.collection("appInstalls").document(installId).delete().await()
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Error deleting install record: ${e.message}", e)
      Result.failure(e)
    }
  }

  // --- Live Support Chat (Users message, Only Admin replies) ---

  private fun currentDeviceLabel(): String {
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    val rawLabel = when {
      manufacturer.isBlank() && model.isBlank() -> "Android Device"
      model.startsWith(manufacturer, ignoreCase = true) -> model
      manufacturer.isBlank() -> model
      model.isBlank() -> manufacturer
      else -> "$manufacturer $model"
    }
    return rawLabel.replaceFirstChar {
      if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
    }.take(100).ifBlank { "Android Device" }
  }

  fun getOrCreateSupportChatId(context: Context = appContext ?: error("Context required")): String {
    val prefs = context.applicationContext.getSharedPreferences(INSTALL_PREFS_NAME, Context.MODE_PRIVATE)
    var chatId = prefs.getString(KEY_SUPPORT_CHAT_ID, null)?.trim().orEmpty()
    if (chatId.isBlank() || !chatId.matches(Regex("^[a-zA-Z0-9_\\-]{6,64}$"))) {
      chatId = "sup_" + UUID.randomUUID().toString().replace("-", "").take(16)
      prefs.edit().putString(KEY_SUPPORT_CHAT_ID, chatId).apply()
    }
    return chatId
  }

  fun getSavedSupportUserName(context: Context = appContext ?: error("Context required")): String {
    val prefs = context.applicationContext.getSharedPreferences(INSTALL_PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_SUPPORT_USER_NAME, "")?.trim().orEmpty()
  }

  fun saveSupportUserName(userName: String, context: Context = appContext ?: error("Context required")) {
    val prefs = context.applicationContext.getSharedPreferences(INSTALL_PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_SUPPORT_USER_NAME, userName.trim().take(80)).apply()
  }

  private fun parseSupportChatDoc(doc: DocumentSnapshot): FirestoreSupportChat? {
    if (!doc.exists()) return null
    return FirestoreSupportChat(
      chatId = doc.getString("chatId") ?: doc.id,
      userName = doc.getString("userName") ?: "User",
      deviceLabel = doc.getString("deviceLabel") ?: "Android Device",
      lastMessage = doc.getString("lastMessage") ?: "",
      lastSenderRole = doc.getString("lastSenderRole") ?: SupportSenderRole.USER,
      lastMessageAt = doc.getTimestamp("lastMessageAt") ?: Timestamp.now(),
      createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now(),
      unreadByAdminCount = (doc.get("unreadByAdminCount") as? Number)?.toLong() ?: 0L,
      unreadByUserCount = (doc.get("unreadByUserCount") as? Number)?.toLong() ?: 0L,
      status = doc.getString("status") ?: SupportChatStatus.OPEN
    )
  }

  fun observeSupportChat(chatId: String): Flow<FirestoreSupportChat?> = callbackFlow {
    if (chatId.isBlank()) {
      trySend(null)
      awaitClose { }
      return@callbackFlow
    }
    val registration = db.collection("supportChats").document(chatId)
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Listen warning on supportChat $chatId: ${error.message}")
          trySend(null)
          return@addSnapshotListener
        }
        val parsed = try {
          snapshot?.let { parseSupportChatDoc(it) }
        } catch (_: Exception) {
          null
        }
        trySend(parsed)
      }
    awaitClose { registration.remove() }
  }

  fun observeAllSupportChats(): Flow<List<FirestoreSupportChat>> = callbackFlow {
    val registration = db.collection("supportChats")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Listen warning on supportChats: ${error.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val items = snapshot?.documents?.mapNotNull { doc ->
          try {
            parseSupportChatDoc(doc)
          } catch (_: Exception) {
            null
          }
        }?.sortedByDescending { it.lastMessageAt.toDate().time } ?: emptyList()
        trySend(items)
      }
    awaitClose { registration.remove() }
  }

  fun observeSupportMessages(chatId: String): Flow<List<FirestoreSupportMessage>> = callbackFlow {
    if (chatId.isBlank()) {
      trySend(emptyList())
      awaitClose { }
      return@callbackFlow
    }
    val registration = db.collection("supportChats").document(chatId)
      .collection("messages")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w(TAG, "Listen warning on supportMessages $chatId: ${error.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val items = snapshot?.documents?.mapNotNull { doc ->
          try {
            FirestoreSupportMessage(
              messageId = doc.getString("messageId") ?: doc.id,
              chatId = doc.getString("chatId") ?: chatId,
              senderRole = doc.getString("senderRole") ?: SupportSenderRole.USER,
              senderName = doc.getString("senderName") ?: "User",
              text = doc.getString("text") ?: "",
              createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now()
            )
          } catch (_: Exception) {
            null
          }
        }?.sortedWith(
          compareBy<FirestoreSupportMessage> { it.createdAt.seconds }
            .thenBy { it.createdAt.nanoseconds }
            .thenBy { it.messageId }
        ) ?: emptyList()
        trySend(items)
      }
    awaitClose { registration.remove() }
  }

  suspend fun sendSupportMessage(
    chatId: String,
    senderRole: String,
    senderName: String,
    text: String,
    deviceLabelOverride: String? = null
  ): Result<FirestoreSupportMessage> {
    return try {
      val cleanChatId = chatId.trim()
      if (cleanChatId.isBlank() || !cleanChatId.matches(Regex("^[a-zA-Z0-9_\\-]{1,128}$"))) {
        return Result.failure(IllegalArgumentException("Invalid support chat session."))
      }
      val validationError = validateSupportMessageInput(text)
      if (validationError != null) {
        return Result.failure(IllegalArgumentException(validationError))
      }
      if (senderRole != SupportSenderRole.USER && senderRole != SupportSenderRole.ADMIN) {
        return Result.failure(IllegalArgumentException("Invalid sender role."))
      }

      val cleanText = text.trim().take(1000)
      val cleanSenderName = if (senderRole == SupportSenderRole.ADMIN) {
        SUPPORT_TEAM_NAME
      } else {
        senderName.trim().ifBlank { "Guest User" }.take(80)
      }
      val safeDeviceLabel = deviceLabelOverride?.trim()?.take(100)?.takeIf { it.isNotBlank() }
        ?: currentDeviceLabel()
      val messageId = "msg_" + System.currentTimeMillis() + "_" +
        UUID.randomUUID().toString().replace("-", "").take(8)

      val chatRef = db.collection("supportChats").document(cleanChatId)
      val msgRef = chatRef.collection("messages").document(messageId)

      db.runTransaction { transaction ->
        val existingChat = transaction.get(chatRef)
        if (!existingChat.exists()) {
          if (senderRole != SupportSenderRole.USER) {
            throw IllegalStateException("User has not started this support chat yet.")
          }
          val chatPayload = hashMapOf<String, Any>(
            "chatId" to cleanChatId,
            "userName" to cleanSenderName,
            "deviceLabel" to safeDeviceLabel,
            "lastMessage" to cleanText,
            "lastSenderRole" to SupportSenderRole.USER,
            "lastMessageAt" to FieldValue.serverTimestamp(),
            "createdAt" to FieldValue.serverTimestamp(),
            "unreadByAdminCount" to 1L,
            "unreadByUserCount" to 0L,
            "status" to SupportChatStatus.OPEN
          )
          transaction.set(chatRef, chatPayload)
        } else {
          val existingUserName = existingChat.getString("userName")?.takeIf { it.isNotBlank() } ?: "Guest User"
          val existingDeviceLabel = existingChat.getString("deviceLabel")?.takeIf { it.isNotBlank() } ?: safeDeviceLabel
          val existingStatus = existingChat.getString("status") ?: SupportChatStatus.OPEN
          val currentAdminUnread = (existingChat.get("unreadByAdminCount") as? Number)?.toLong() ?: 0L
          val currentUserUnread = (existingChat.get("unreadByUserCount") as? Number)?.toLong() ?: 0L

          val updatedUserName = if (senderRole == SupportSenderRole.USER) cleanSenderName else existingUserName
          val nextAdminUnread = if (senderRole == SupportSenderRole.USER) currentAdminUnread + 1L else 0L
          val nextUserUnread = if (senderRole == SupportSenderRole.ADMIN) currentUserUnread + 1L else 0L
          val nextStatus = if (senderRole == SupportSenderRole.USER) SupportChatStatus.OPEN else existingStatus

          val chatUpdates = hashMapOf<String, Any>(
            "userName" to updatedUserName,
            "deviceLabel" to existingDeviceLabel,
            "lastMessage" to cleanText,
            "lastSenderRole" to senderRole,
            "lastMessageAt" to FieldValue.serverTimestamp(),
            "unreadByAdminCount" to nextAdminUnread,
            "unreadByUserCount" to nextUserUnread,
            "status" to nextStatus
          )
          transaction.update(chatRef, chatUpdates)
        }

        val msgPayload = hashMapOf<String, Any>(
          "messageId" to messageId,
          "chatId" to cleanChatId,
          "senderRole" to senderRole,
          "senderName" to cleanSenderName,
          "text" to cleanText,
          "createdAt" to FieldValue.serverTimestamp()
        )
        transaction.set(msgRef, msgPayload)
      }.await()

      Result.success(
        FirestoreSupportMessage(
          messageId = messageId,
          chatId = cleanChatId,
          senderRole = senderRole,
          senderName = cleanSenderName,
          text = cleanText,
          createdAt = Timestamp.now()
        )
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error sending support message: ${e.message}", e)
      Result.failure(e)
    }
  }

  suspend fun markSupportChatReadByUser(chatId: String): Result<Unit> {
    return try {
      if (chatId.isBlank()) return Result.success(Unit)
      val snap = db.collection("supportChats").document(chatId).get().await()
      if (!snap.exists()) return Result.success(Unit)
      val unread = (snap.get("unreadByUserCount") as? Number)?.toLong() ?: 0L
      if (unread > 0L) {
        snap.reference.update("unreadByUserCount", 0L).await()
      }
      Result.success(Unit)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to mark support chat read by user: ${e.message}")
      Result.failure(e)
    }
  }

  suspend fun markSupportChatReadByAdmin(chatId: String): Result<Unit> {
    return try {
      if (chatId.isBlank()) return Result.success(Unit)
      val snap = db.collection("supportChats").document(chatId).get().await()
      if (!snap.exists()) return Result.success(Unit)
      val unread = (snap.get("unreadByAdminCount") as? Number)?.toLong() ?: 0L
      if (unread > 0L) {
        snap.reference.update("unreadByAdminCount", 0L).await()
      }
      Result.success(Unit)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to mark support chat read by admin: ${e.message}")
      Result.failure(e)
    }
  }

  suspend fun updateSupportChatStatus(chatId: String, status: String): Result<Unit> {
    return try {
      if (status != SupportChatStatus.OPEN && status != SupportChatStatus.RESOLVED) {
        return Result.failure(IllegalArgumentException("Invalid support status: $status"))
      }
      db.collection("supportChats").document(chatId)
        .update("status", status)
        .await()
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Error updating support chat status: ${e.message}", e)
      Result.failure(e)
    }
  }

  suspend fun deleteSupportChat(chatId: String): Result<Unit> {
    return try {
      val chatRef = db.collection("supportChats").document(chatId)
      val messagesSnap = chatRef.collection("messages").get().await()
      for (doc in messagesSnap.documents) {
        doc.reference.delete().await()
      }
      chatRef.delete().await()
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Error deleting support chat: ${e.message}", e)
      Result.failure(e)
    }
  }

  companion object {
    private const val INSTALL_PREFS_NAME = "vidvora_install_prefs"
    private const val KEY_INSTALL_ID = "install_uuid"
    private const val KEY_SERVER_SYNCED = "server_synced_install"
    private const val KEY_LAST_HEARTBEAT_MS = "last_heartbeat_ms"
    private const val KEY_LAST_VERSION = "last_recorded_version"
    private const val KEY_SUPPORT_CHAT_ID = "support_chat_id"
    private const val KEY_SUPPORT_USER_NAME = "support_user_name"
    private const val HEARTBEAT_THROTTLE_MS = 60 * 60 * 1000L
    private const val ADMIN_SECURITY_DOC_ID = "adminSecurity"
    private const val APP_UPDATE_DOC_ID = "appUpdate"

    const val DEFAULT_TELEGRAM_LINK = "https://t.me/VidVoraApp"
    const val DEFAULT_APK_DOWNLOAD_URL = "https://d1mn8mrvlzisg5.cloudfront.net/vidvora.apk"
    const val SHARE_LINK_BASE_URL = "https://d1mn8mrvlzisg5.cloudfront.net/index.html?link="
    const val DEFAULT_AUTH_BACKEND_BASE_URL = "https://vidvora-share-bot.onrender.com"
    const val MIN_WITHDRAWAL_AMOUNT = 500.0
    const val DEFAULT_VIEW_RATE_PER_1000 = 190.0
    const val MIN_PASSKEY_LENGTH = 4

    fun extractRenderOriginFromShareUrl(rawUrl: String): String {
      val clean = rawUrl.trim()
      if (clean.isBlank()) return ""
      return try {
        val withScheme = if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
          clean
        } else {
          "https://$clean"
        }
        val uri = java.net.URI(withScheme)
        val host = uri.host.orEmpty().lowercase()
        if (host.endsWith(".onrender.com")) {
          val scheme = uri.scheme ?: "https"
          "$scheme://$host"
        } else {
          ""
        }
      } catch (_: Exception) {
        ""
      }
    }

    const val MAX_CUSTOM_TELEGRAM_CATEGORIES = 40
    const val MAX_CATEGORY_NAME_LENGTH = 60
    const val SUPPORT_TEAM_NAME = "VidVora Support Team"

    fun calculateViewsEarning(
      views: Long,
      ratePer1000: Double = DEFAULT_VIEW_RATE_PER_1000
    ): Double {
      if (views <= 0L || ratePer1000 <= 0.0 || ratePer1000.isNaN() || ratePer1000.isInfinite()) {
        return 0.0
      }
      val raw = (views.toDouble() / 1000.0) * ratePer1000
      return kotlin.math.round(raw * 100.0) / 100.0
    }

    fun buildCustomCategoryId(name: String): String {
      val slug = name.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(28)
        .ifBlank { "custom" }
      val suffix = UUID.randomUUID().toString().replace("-", "").take(6)
      return "cat_${slug}_$suffix"
    }

    fun validateCustomCategoryName(
      name: String,
      existingCategoryNames: Collection<String>,
      activeBuiltInNames: Collection<String> = TelegramChannelCategory.entries.map { it.label }
    ): String? {
      val clean = name.trim()
      if (clean.isBlank()) return "Category name cannot be empty."
      if (clean.length > MAX_CATEGORY_NAME_LENGTH) {
        return "Category name cannot exceed $MAX_CATEGORY_NAME_LENGTH characters."
      }
      val lower = clean.lowercase()
      val conflictsBuiltIn = activeBuiltInNames.any { it.trim().lowercase() == lower }
      val conflictsExisting = existingCategoryNames.any { it.trim().lowercase() == lower }
      if (conflictsBuiltIn || conflictsExisting) {
        return "A category named '$clean' already exists."
      }
      return null
    }

    fun normalizeShareLinkBaseUrl(rawUrl: String): String {
      val clean = rawUrl.trim()
      if (clean.isBlank()) return ""
      val withScheme = if (
        clean.startsWith("http://", ignoreCase = true) ||
        clean.startsWith("https://", ignoreCase = true)
      ) {
        clean
      } else {
        "https://$clean"
      }
      if (withScheme.endsWith("?link=", ignoreCase = true) ||
        withScheme.endsWith("&link=", ignoreCase = true) ||
        withScheme.endsWith("?v=", ignoreCase = true) ||
        withScheme.endsWith("?token=", ignoreCase = true)
      ) {
        return withScheme
      }
      return try {
        val uri = java.net.URI(withScheme)
        val scheme = uri.scheme ?: "https"
        val authority = uri.rawAuthority ?: return withScheme
        val path = uri.rawPath.orEmpty().let {
          if (it.isBlank() || it == "/") "/share" else it.trimEnd('/')
        }
        val query = uri.rawQuery.orEmpty()
        if (query.isBlank()) {
          "$scheme://$authority$path?link="
        } else if (query.contains("link=", ignoreCase = true)) {
          withScheme
        } else {
          "$scheme://$authority$path?$query&link="
        }
      } catch (_: Exception) {
        withScheme
      }
    }

    fun buildShareableVideoUrl(rawIdentifier: String, configuredBaseUrl: String = ""): String {
      val cleanId = rawIdentifier.trim()
      if (cleanId.isBlank()) return ""
      if (cleanId.startsWith("http://", ignoreCase = true) ||
        cleanId.startsWith("https://", ignoreCase = true) ||
        cleanId.startsWith("vidvora://", ignoreCase = true)
      ) {
        return cleanId
      }
      val normalizedBase = normalizeShareLinkBaseUrl(configuredBaseUrl).ifBlank { SHARE_LINK_BASE_URL }
      val encodedId = java.net.URLEncoder.encode(cleanId, "UTF-8").replace("+", "%20")
      return "$normalizedBase$encodedId"
    }

    fun normalizeTelegramUrl(raw: String): String {
      val clean = raw.trim()
      if (clean.isBlank()) return ""
      if (clean.startsWith("@") && clean.length > 1) {
        return "https://t.me/${clean.removePrefix("@").trim()}"
      }
      return if (
        clean.startsWith("http://", ignoreCase = true) ||
        clean.startsWith("https://", ignoreCase = true) ||
        clean.startsWith("tg://", ignoreCase = true)
      ) {
        clean
      } else {
        "https://$clean"
      }
    }

    fun validateSupportMessageInput(text: String): String? {
      val clean = text.trim()
      if (clean.isBlank()) return "Message cannot be empty."
      if (clean.length > 1000) return "Message cannot exceed 1000 characters."
      return null
    }

    fun isValidUpiId(upiId: String): Boolean {
      val clean = upiId.trim()
      return clean.matches(Regex("^[^\\s@]{2,}@[^\\s@]{2,}$"))
    }

    fun shouldIncrementViewCount(uploadedBy: String, viewerMemberId: String?): Boolean {
      val cleanViewer = viewerMemberId?.trim()
      if (cleanViewer.isNullOrBlank()) return true
      return uploadedBy.trim() != cleanViewer
    }

    fun validateEmailInput(email: String): String? {
      val cleanEmail = email.trim()
      if (cleanEmail.isBlank() || !cleanEmail.contains("@") || !cleanEmail.contains(".")) {
        return "Please enter a valid email address."
      }
      if (cleanEmail.length > 160) {
        return "Email address cannot exceed 160 characters."
      }
      return null
    }

    fun validateMemberInput(name: String, emailOrPhone: String, passkey: String): String? {
      val cleanName = name.trim()
      val cleanPass = passkey.trim()
      if (cleanName.isBlank()) return "Member name cannot be empty."
      val emailError = validateEmailInput(emailOrPhone)
      if (emailError != null) return emailError
      if (cleanPass.length < MIN_PASSKEY_LENGTH) {
        return "Password must be at least $MIN_PASSKEY_LENGTH characters."
      }
      if (cleanPass == FirebaseAuthAdminRepository.ADMIN_MASTER_KEY) {
        return "Please choose a different Password."
      }
      return null
    }

    fun validatePasskeyChange(
      storedPasskey: String,
      currentInput: String,
      newPasskey: String,
      confirmPasskey: String
    ): String? {
      val cleanCurrent = currentInput.trim()
      val cleanNew = newPasskey.trim()
      val cleanConfirm = confirmPasskey.trim()
      if (cleanCurrent.isBlank()) return "Please enter your current Password."
      if (cleanCurrent != storedPasskey.trim()) return "Current Password is incorrect."
      if (cleanNew.length < MIN_PASSKEY_LENGTH) {
        return "New Password must be at least $MIN_PASSKEY_LENGTH characters."
      }
      if (cleanNew == cleanCurrent) return "New Password must be different from current Password."
      if (cleanNew != cleanConfirm) return "New Password and Confirm Password do not match."
      if (cleanNew == FirebaseAuthAdminRepository.ADMIN_MASTER_KEY) {
        return "This Password is reserved. Please choose another password."
      }
      return null
    }

    fun hashSecretExact(secret: String): String {
      val bytes = MessageDigest.getInstance("SHA-256")
        .digest(secret.trim().toByteArray(Charsets.UTF_8))
      return bytes.joinToString("") { "%02x".format(it) }
    }

    fun validateSuperadminMasterKeyChange(
      storedHash: String?,
      currentInput: String,
      newPasskey: String,
      confirmPasskey: String
    ): String? {
      val cleanCurrent = currentInput.trim()
      val cleanNew = newPasskey.trim()
      val cleanConfirm = confirmPasskey.trim()
      if (cleanCurrent.isBlank()) return "Please enter your current Admin Password."
      val normalizedHash = storedHash?.trim()?.takeIf { it.length == 64 }
      val currentMatches = if (normalizedHash != null) {
        hashSecretExact(cleanCurrent) == normalizedHash
      } else {
        cleanCurrent == FirebaseAuthAdminRepository.ADMIN_MASTER_KEY
      }
      if (!currentMatches) return "Current Admin Password is incorrect."
      if (cleanNew.length < MIN_PASSKEY_LENGTH) {
        return "New Password must be at least $MIN_PASSKEY_LENGTH characters."
      }
      if (cleanNew.length > 128) {
        return "New Password cannot exceed 128 characters."
      }
      if (cleanNew == cleanCurrent) return "New Password must be different from current Password."
      if (cleanNew != cleanConfirm) return "New Password and Confirm Password do not match."
      return null
    }

    fun isRawVidvoraToken(value: String): Boolean {
      val clean = value.trim()
      if (clean.isBlank()) return false
      return !clean.contains("://") &&
        !clean.contains("/") &&
        !clean.any { it.isWhitespace() } &&
        (clean.startsWith("GM-", ignoreCase = true) ||
          clean.startsWith("link_", ignoreCase = true) ||
          clean.startsWith("vid_", ignoreCase = true))
    }

    fun isDirectMediaUri(uri: Uri): Boolean {
      val scheme = uri.scheme?.lowercase().orEmpty()
      if (scheme == "rtmp" || scheme == "rtsp") return true
      val host = uri.host?.lowercase().orEmpty()
      val pathLower = uri.path?.lowercase().orEmpty()
      val isImageFile = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg").any { pathLower.endsWith(it) }
      if (!isImageFile && (host.contains("firebasestorage.googleapis.com") || host.contains("storage.googleapis.com"))) {
        return true
      }
      return listOf(".mp4", ".m3u8", ".webm", ".mkv", ".mov", ".m4v", ".3gp", ".mpd").any {
        pathLower.endsWith(it)
      }
    }

    fun isVidvoraVideoUrl(rawUrl: String): Boolean {
      val clean = rawUrl.trim()
      if (clean.isBlank()) return false
      if (isRawVidvoraToken(clean)) return true
      if (clean.startsWith("vidvora://", ignoreCase = true)) return true
      val normalized = normalizeNotificationActionUrl(clean)
      return try {
        val parsed = java.net.URI(normalized)
        val scheme = parsed.scheme?.lowercase().orEmpty()
        val host = parsed.host?.lowercase().orEmpty()
        val pathLower = parsed.path?.lowercase().orEmpty()
        if (pathLower.endsWith(".apk")) return false
        if (host == "d1mn8mrvlzisg5.cloudfront.net" || host == "gen-lang-client-0000740810.web.app") {
          return true
        }
        val query = parsed.rawQuery.orEmpty()
        if (query.isNotBlank()) {
          val params = query.split("&").mapNotNull { pair ->
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) {
              val key = java.net.URLDecoder.decode(pair.substring(0, eqIdx), "UTF-8").lowercase()
              val value = java.net.URLDecoder.decode(pair.substring(eqIdx + 1), "UTF-8")
              key to value
            } else {
              null
            }
          }.toMap()
          val paramToken = params["link"] ?: params["video"] ?: params["v"] ?: params["token"]
          if (!paramToken.isNullOrBlank() &&
            (isRawVidvoraToken(paramToken) ||
              pathLower.startsWith("/share") ||
              pathLower.startsWith("/watch") ||
              host.endsWith(".onrender.com"))
          ) {
            return true
          }
        }
        val lastSegment = pathLower.trimEnd('/').substringAfterLast('/')
        if (lastSegment.isNotBlank() && isRawVidvoraToken(lastSegment)) {
          return true
        }
        if (scheme == "rtmp" || scheme == "rtsp") return true
        val isImageFile = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg").any { pathLower.endsWith(it) }
        if (!isImageFile && (host.contains("firebasestorage.googleapis.com") || host.contains("storage.googleapis.com"))) {
          return true
        }
        listOf(".mp4", ".m3u8", ".webm", ".mkv", ".mov", ".m4v", ".3gp", ".mpd").any {
          pathLower.endsWith(it)
        }
      } catch (_: Exception) {
        false
      }
    }

    fun normalizeNotificationActionUrl(rawUrl: String): String {
      val clean = rawUrl.trim()
      if (clean.isBlank()) return ""
      if (isRawVidvoraToken(clean)) {
        return "vidvora://watch?link=$clean"
      }
      return if (
        clean.startsWith("http://", ignoreCase = true) ||
        clean.startsWith("https://", ignoreCase = true) ||
        clean.startsWith("vidvora://", ignoreCase = true)
      ) {
        clean
      } else {
        "https://$clean"
      }
    }

    fun validateNotificationInput(
      title: String,
      message: String,
      imageUrl: String = "",
      actionUrl: String = ""
    ): String? {
      val cleanTitle = title.trim()
      val cleanMessage = message.trim()
      val cleanImage = imageUrl.trim()
      val cleanUrl = normalizeNotificationActionUrl(actionUrl)

      if (cleanTitle.isBlank()) return "Notification title cannot be empty."
      if (cleanTitle.length > 120) return "Notification title cannot exceed 120 characters."
      if (cleanMessage.isBlank()) return "Notification message cannot be empty."
      if (cleanMessage.length > 500) return "Notification message cannot exceed 500 characters."

      if (cleanImage.isNotBlank()) {
        if (cleanImage.length > 350_000) {
          return "Notification image is too large. Please pick a smaller image or paste an HTTPS URL."
        }
        val validImagePrefix = cleanImage.startsWith("http://", ignoreCase = true) ||
          cleanImage.startsWith("https://", ignoreCase = true) ||
          cleanImage.startsWith("data:image/", ignoreCase = true)
        if (!validImagePrefix) {
          return "Image URL must start with http:// or https://"
        }
      }

      if (cleanUrl.isNotBlank()) {
        if (cleanUrl.length > 1000) return "Notification URL cannot exceed 1000 characters."
        if (cleanUrl.any { it.isWhitespace() }) return "Notification URL cannot contain spaces."
        val hostPart = cleanUrl.substringAfter("://", "")
        if (hostPart.isBlank() || (!hostPart.contains(".") && !cleanUrl.startsWith("vidvora://", ignoreCase = true))) {
          return "Please enter a valid notification URL (e.g., https://example.com)."
        }
      }
      return null
    }

    fun validateCreatorEventInput(
      title: String,
      description: String,
      giftLabel: String = "",
      imageUrl: String = ""
    ): String? {
      val cleanTitle = title.trim()
      val cleanDesc = description.trim()
      val cleanGift = giftLabel.trim()
      val cleanImage = imageUrl.trim()

      if (cleanTitle.isBlank()) return "Event title cannot be empty."
      if (cleanTitle.length > 120) return "Event title cannot exceed 120 characters."
      if (cleanDesc.isBlank()) return "Event description cannot be empty."
      if (cleanDesc.length > 1500) return "Event description cannot exceed 1500 characters."
      if (cleanGift.length > 60) return "Gift label cannot exceed 60 characters."

      if (cleanImage.isNotBlank()) {
        if (cleanImage.length > 350_000) {
          return "Event image is too large. Please pick a smaller image or paste an HTTPS URL."
        }
        val validImagePrefix = cleanImage.startsWith("http://", ignoreCase = true) ||
          cleanImage.startsWith("https://", ignoreCase = true) ||
          cleanImage.startsWith("data:image/", ignoreCase = true)
        if (!validImagePrefix) {
          return "Image URL must start with http:// or https://"
        }
      }
      return null
    }

    fun validateAppUpdateInput(
      latestVersionCode: Int,
      latestVersionName: String,
      title: String,
      message: String,
      apkUrl: String
    ): String? {
      val cleanVersionName = latestVersionName.trim()
      val cleanTitle = title.trim()
      val cleanMessage = message.trim()
      val cleanUrl = apkUrl.trim()

      if (latestVersionCode !in 1..999999) {
        return "Version Code must be between 1 and 999999."
      }
      if (cleanVersionName.isBlank()) {
        return "Version Name cannot be empty (e.g., 1.1)."
      }
      if (cleanVersionName.length > 40) {
        return "Version Name cannot exceed 40 characters."
      }
      if (cleanTitle.isBlank()) {
        return "Update popup title cannot be empty."
      }
      if (cleanTitle.length > 120) {
        return "Update popup title cannot exceed 120 characters."
      }
      if (cleanMessage.length > 1000) {
        return "Release notes cannot exceed 1000 characters."
      }
      if (cleanUrl.isBlank()) {
        return "APK Download URL cannot be empty."
      }
      if (cleanUrl.length > 500) {
        return "APK Download URL cannot exceed 500 characters."
      }
      if (cleanUrl.any { it.isWhitespace() }) {
        return "APK Download URL cannot contain spaces."
      }
      val validProtocol = cleanUrl.startsWith("https://", ignoreCase = true) ||
        cleanUrl.startsWith("http://", ignoreCase = true)
      val hostPart = cleanUrl.substringAfter("://", "")
      if (!validProtocol || hostPart.isBlank() || !hostPart.contains(".")) {
        return "Please enter a valid http/https APK URL (e.g., $DEFAULT_APK_DOWNLOAD_URL)."
      }
      return null
    }

    fun buildCreatorSupportChatId(memberId: String): String {
      val clean = memberId.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
      return if (clean.isBlank()) "" else "creator_${clean.take(100)}"
    }

    val VIDEO_NEWEST_FIRST_COMPARATOR: Comparator<FirestoreVideo> =
      compareByDescending<FirestoreVideo> { it.createdAt.seconds }
        .thenByDescending { it.createdAt.nanoseconds }
        .thenByDescending { it.videoId }

    fun sortVideosNewestFirst(videos: List<FirestoreVideo>): List<FirestoreVideo> =
      videos.sortedWith(VIDEO_NEWEST_FIRST_COMPARATOR)

    val MEMBER_NEWEST_FIRST_COMPARATOR: Comparator<FirestoreMember> =
      compareByDescending<FirestoreMember> { it.createdAt.seconds }
        .thenByDescending { it.createdAt.nanoseconds }
        .thenByDescending { it.memberId }

    fun sortMembersNewestFirst(members: List<FirestoreMember>): List<FirestoreMember> =
      members.sortedWith(MEMBER_NEWEST_FIRST_COMPARATOR)

    fun matchesMemberQuery(member: FirestoreMember, query: String): Boolean {
      val cleanQuery = query.trim()
      if (cleanQuery.isBlank()) return true
      return member.name.contains(cleanQuery, ignoreCase = true) ||
        member.emailOrPhone.contains(cleanQuery, ignoreCase = true)
    }

    fun filterMembersByQuery(members: List<FirestoreMember>, query: String): List<FirestoreMember> {
      val cleanQuery = query.trim()
      if (cleanQuery.isBlank()) return members
      return members.filter { matchesMemberQuery(it, cleanQuery) }
    }

    fun encodeJpegBytesAsDataUrl(bytes: ByteArray): String =
      "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

    private const val MILLIS_PER_HOUR = 3_600_000L
    private const val HOURS_WINDOW_24H = 24

    fun currentUtcHourBucketKey(nowMillis: Long = System.currentTimeMillis()): String {
      val sdf = SimpleDateFormat("yyyyMMddHH", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }
      return sdf.format(Date(nowMillis))
    }

    fun recent24HourBucketKeys(nowMillis: Long = System.currentTimeMillis()): Set<String> {
      val sdf = SimpleDateFormat("yyyyMMddHH", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }
      return buildSet {
        for (hoursAgo in 0 until HOURS_WINDOW_24H) {
          add(sdf.format(Date(nowMillis - hoursAgo * MILLIS_PER_HOUR)))
        }
      }
    }

    fun computeViewsLast24Hours(
      hourlyViews: Map<String, Long>,
      nowMillis: Long = System.currentTimeMillis()
    ): Long {
      if (hourlyViews.isEmpty()) return 0L
      val activeKeys = recent24HourBucketKeys(nowMillis)
      var total = 0L
      for (key in activeKeys) {
        val count = hourlyViews[key] ?: 0L
        if (count > 0L) {
          total += count
        }
      }
      return total
    }

    fun mergeHourlyViews(
      target: Map<String, Long>,
      source: Map<String, Long>,
      nowMillis: Long = System.currentTimeMillis()
    ): Map<String, Long> {
      val activeKeys = recent24HourBucketKeys(nowMillis)
      val merged = linkedMapOf<String, Long>()
      for (key in activeKeys) {
        val combined = (target[key] ?: 0L).coerceAtLeast(0L) + (source[key] ?: 0L).coerceAtLeast(0L)
        if (combined > 0L) {
          merged[key] = combined
        }
      }
      return merged
    }
  }
}
