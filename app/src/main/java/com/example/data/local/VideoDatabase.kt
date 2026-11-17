package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [VideoEntity::class, WatchHistoryEntity::class], version = 2, exportSchema = false)
abstract class VideoDatabase : RoomDatabase() {
  abstract fun videoDao(): VideoDao
  abstract fun watchHistoryDao(): WatchHistoryDao

  companion object {
    @Volatile
    private var INSTANCE: VideoDatabase? = null

    fun getDatabase(context: Context): VideoDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          VideoDatabase::class.java,
          "vidvora_videos.db"
        ).fallbackToDestructiveMigration().build()
        INSTANCE = instance
        instance
      }
    }
  }
}
