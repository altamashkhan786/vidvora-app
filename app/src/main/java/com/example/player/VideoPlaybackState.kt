package com.example.player

data class VideoPlaybackState(
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = true,
  val currentPositionMs: Long = 0L,
  val durationMs: Long = 0L,
  val bufferedPositionMs: Long = 0L,
  val volume: Float = 1.0f,
  val isMuted: Boolean = false,
  val isEnded: Boolean = false,
  val errorMessage: String? = null
) {
  val formattedCurrentTime: String
    get() = formatTime(currentPositionMs)

  val formattedDuration: String
    get() = formatTime(durationMs)

  val progress: Float
    get() = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

  val bufferedProgress: Float
    get() = if (durationMs > 0) (bufferedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

  companion object {
    fun formatTime(ms: Long): String {
      if (ms <= 0) return "00:00"
      val totalSeconds = ms / 1000
      val seconds = totalSeconds % 60
      val minutes = (totalSeconds / 60) % 60
      val hours = totalSeconds / 3600
      return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
      } else {
        String.format("%02d:%02d", minutes, seconds)
      }
    }
  }
}
