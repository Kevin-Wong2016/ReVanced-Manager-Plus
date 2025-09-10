package com.revanced.net.revancedmanager.data.local.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

/**
 * Room database for ReVanced Manager
 * Contains tables for download states and other persistent data
 */
@Database(
    entities = [DownloadStateEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RevancedDatabase : RoomDatabase() {
    
    abstract fun downloadStateDao(): DownloadStateDao
    
    companion object {
        const val DATABASE_NAME = "revanced_database"
    }
} 