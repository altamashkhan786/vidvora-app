package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
  @Query("SELECT * FROM videos ORDER BY createdAt DESC")
  fun getAllVideosFlow(): Flow<List<VideoEntity>>

  @Query("SELECT * FROM videos WHERE token = :token COLLATE NOCASE LIMIT 1")
  suspend fun findByToken(token: String): VideoEntity?

  @Query("SELECT * FROM videos WHERE id = :id LIMIT 1")
  suspend fun findById(id: String): VideoEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(video: VideoEntity)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(videos: List<VideoEntity>)

  @Query("UPDATE videos SET isActive = :isActive WHERE id = :id")
  suspend fun updateActiveStatus(id: String, isActive: Boolean)

  @Query("UPDATE videos SET viewCount = viewCount + 1 WHERE id = :id")
  suspend fun incrementViewCount(id: String)

  @Query("DELETE FROM videos WHERE id = :id")
  suspend fun deleteById(id: String)

  @Query("SELECT COUNT(*) FROM videos")
  suspend fun getCount(): Int
}
