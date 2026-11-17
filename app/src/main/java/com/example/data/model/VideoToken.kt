package com.example.data.model

data class VideoToken(
  val id: String,
  val token: String,
  val title: String,
  val description: String = "",
  val streamUrl: String,
  val posterUrl: String? = null,
  val quality: String = "1080p FHD",
  val isActive: Boolean = true,
  val viewCount: Int = 0,
  val createdAt: Long = System.currentTimeMillis(),
  val uploadedBy: String = "admin",
  val uploaderName: String = "Admin"
) {
  val shareableLink: String
    get() = "vidvora://watch?token=$token"
}

sealed interface TokenValidationResult {
  data class Success(val video: VideoToken) : TokenValidationResult
  data object TokenNotFound : TokenValidationResult
  data object TokenDeactivated : TokenValidationResult
  data class Error(val message: String) : TokenValidationResult
}
