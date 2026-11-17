package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.VideoToken

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
  @PrimaryKey val videoId: String,
  val token: String,
  val title: String,
  val description: String,
  val streamUrl: String,
  val posterUrl: String?,
  val quality: String,
  val watchedAt: Long
) {
  fun toVideoToken(): VideoToken = VideoToken(
    id = videoId,
    token = token,
    title = title,
    description = description,
    streamUrl = streamUrl,
    posterUrl = posterUrl,
    quality = quality,
    isActive = true,
    viewCount = 0,
    createdAt = watchedAt
  )

  companion object {
    fun fromVideoToken(video: VideoToken, timestamp: Long = System.currentTimeMillis()): WatchHistoryEntity =
      WatchHistoryEntity(
        videoId = video.id,
        token = video.token,
        title = video.title,
        description = video.description,
        streamUrl = video.streamUrl,
        posterUrl = video.posterUrl,
        quality = video.quality,
        watchedAt = timestamp
      )
  }
}
