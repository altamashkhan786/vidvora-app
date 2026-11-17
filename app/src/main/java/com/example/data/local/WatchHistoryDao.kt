package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
  @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT 30")
  fun getWatchHistoryFlow(): Flow<List<WatchHistoryEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(item: WatchHistoryEntity)

  @Query("DELETE FROM watch_history WHERE videoId = :videoId")
  suspend fun deleteByVideoId(videoId: String)

  @Query("DELETE FROM watch_history")
  suspend fun clearAll()

  @Query("SELECT COUNT(*) FROM watch_history")
  suspend fun getCount(): Int
}
