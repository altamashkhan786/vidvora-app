package com.example.data.repository

import android.net.Uri
import com.example.data.local.VideoDao
import com.example.data.local.VideoEntity
import com.example.data.local.WatchHistoryDao
import com.example.data.local.WatchHistoryEntity
import com.example.data.model.TokenValidationResult
import com.example.data.model.VideoToken
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class VideoRepository(
  private val videoDao: VideoDao,
  private val watchHistoryDao: WatchHistoryDao? = null
) {

  val allVideosFlow: Flow<List<VideoToken>> = videoDao.getAllVideosFlow().map { entities ->
    entities.map { it.toDomain() }
  }

  val watchHistoryFlow: Flow<List<WatchHistoryEntity>> =
    watchHistoryDao?.getWatchHistoryFlow() ?: flowOf(emptyList())

  suspend fun initializeSeedsIfEmpty() {
    if (videoDao.getCount() == 0) {
      val seeds = listOf(
        VideoEntity(
          id = "seed_1",
          token = "GM-SPICE-01",
          title = "Big Buck Bunny (Special Masala Edition)",
          description = "Classic open-source animated short film in pristine 1080p full resolution.",
          streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
          posterUrl = "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=600",
          quality = "1080p Full HD",
          isActive = true,
          viewCount = 1284,
          createdAt = System.currentTimeMillis() - 86400000L * 7
        ),
        VideoEntity(
          id = "seed_2",
          token = "GM-MASALA-02",
          title = "Tears of Steel (Sci-Fi Cinema)",
          description = "Set in a dystopian future where VFX and live action merge seamlessly.",
          streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
          posterUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=600",
          quality = "4K Ultra HD",
          isActive = true,
          viewCount = 3450,
          createdAt = System.currentTimeMillis() - 86400000L * 5
        ),
        VideoEntity(
          id = "seed_3",
          token = "GM-CHILI-03",
          title = "Sintel (Fantasy Epic Short)",
          description = "Emotional fantasy journey of a lone warrior searching for her dragon friend.",
          streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
          posterUrl = "https://images.unsplash.com/photo-1518173946687-a4c8a383392e?w=600",
          quality = "1080p Full HD",
          isActive = true,
          viewCount = 2190,
          createdAt = System.currentTimeMillis() - 86400000L * 3
        ),
        VideoEntity(
          id = "seed_4",
          token = "GM-FLAME-04",
          title = "Elephants Dream (Surreal Steampunk)",
          description = "Mind-bending mechanical world inside the giant machine.",
          streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
          posterUrl = "https://images.unsplash.com/photo-1574375927938-d5a98e8ffe85?w=600",
          quality = "1080p HD",
          isActive = false, // Seeded as deactivated to showcase error state handling!
          viewCount = 89,
          createdAt = System.currentTimeMillis() - 86400000L * 2
        )
      )
      videoDao.insertAll(seeds)
    }
  }

  fun extractToken(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""

    return try {
      val uri = Uri.parse(trimmed)
      val directMedia = FirestoreVideoRepository.isDirectMediaUri(uri)
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

  suspend fun validateAndGetVideo(
    rawInput: String,
    skipViewIncrementVideoIds: Set<String> = emptySet()
  ): TokenValidationResult {
    val key = extractToken(rawInput)
    if (key.isBlank()) {
      return TokenValidationResult.Error("Please enter or paste a video link.")
    }

    var entity = videoDao.findByToken(key) ?: videoDao.findById(key)

    if (entity == null && key.startsWith("link_", ignoreCase = true)) {
      val stripped = key.removePrefix("link_").removePrefix("LINK_")
      val normalizedToken = stripped.replace("_", "-").uppercase()
      entity = videoDao.findByToken(normalizedToken) ?: videoDao.findById(stripped)
    }

    if (entity == null) {
      return TokenValidationResult.TokenNotFound
    }

    if (!entity.isActive) {
      return TokenValidationResult.TokenDeactivated
    }

    val shouldIncrement = entity.id !in skipViewIncrementVideoIds
    if (shouldIncrement) {
      // Increment view count asynchronously
      videoDao.incrementViewCount(entity.id)
    }
    return TokenValidationResult.Success(
      entity.toDomain().copy(viewCount = if (shouldIncrement) entity.viewCount + 1 else entity.viewCount)
    )
  }

  suspend fun createVideoToken(
    title: String,
    description: String,
    streamUrl: String,
    posterUrl: String? = null,
    quality: String = "1080p FHD",
    customToken: String? = null,
    customId: String? = null
  ): VideoToken {
    val token = if (!customToken.isNullOrBlank()) {
      customToken.trim().uppercase()
    } else {
      generateUniqueToken()
    }

    val video = VideoToken(
      id = customId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
      token = token,
      title = title.trim(),
      description = description.trim(),
      streamUrl = streamUrl.trim(),
      posterUrl = posterUrl?.trim()?.ifEmpty { null },
      quality = quality,
      isActive = true,
      viewCount = 0,
      createdAt = System.currentTimeMillis()
    )

    videoDao.insertOrUpdate(VideoEntity.fromDomain(video))
    return video
  }

  suspend fun toggleActiveStatus(id: String, newStatus: Boolean) {
    videoDao.updateActiveStatus(id, newStatus)
  }

  suspend fun deleteVideo(id: String) {
    videoDao.deleteById(id)
  }

  suspend fun getVideoById(id: String): VideoToken? {
    return videoDao.findById(id)?.toDomain()
  }

  suspend fun recordWatchHistory(video: VideoToken) {
    watchHistoryDao?.insertOrUpdate(WatchHistoryEntity.fromVideoToken(video))
  }

  suspend fun clearWatchHistory() {
    watchHistoryDao?.clearAll()
  }

  suspend fun removeWatchHistoryItem(videoId: String) {
    watchHistoryDao?.deleteByVideoId(videoId)
  }

  private fun generateUniqueToken(): String {
    val allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val segment1 = (1..4).map { allowedChars.random() }.joinToString("")
    val segment2 = (1..4).map { allowedChars.random() }.joinToString("")
    return "GM-$segment1-$segment2"
  }
}
