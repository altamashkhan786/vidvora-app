package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.VideoToken

@Entity(
  tableName = "videos",
  indices = [Index(value = ["token"], unique = true)]
)
data class VideoEntity(
  @PrimaryKey val id: String,
  val token: String,
  val title: String,
  val description: String,
  val streamUrl: String,
  val posterUrl: String?,
  val quality: String,
  val isActive: Boolean,
  val viewCount: Int,
  val createdAt: Long
) {
  fun toDomain(): VideoToken = VideoToken(
    id = id,
    token = token,
    title = title,
    description = description,
    streamUrl = streamUrl,
    posterUrl = posterUrl,
    quality = quality,
    isActive = isActive,
    viewCount = viewCount,
    createdAt = createdAt
  )

  companion object {
    fun fromDomain(domain: VideoToken): VideoEntity = VideoEntity(
      id = domain.id,
      token = domain.token,
      title = domain.title,
      description = domain.description,
      streamUrl = domain.streamUrl,
      posterUrl = domain.posterUrl,
      quality = domain.quality,
      isActive = domain.isActive,
      viewCount = domain.viewCount,
      createdAt = domain.createdAt
    )
  }
}
