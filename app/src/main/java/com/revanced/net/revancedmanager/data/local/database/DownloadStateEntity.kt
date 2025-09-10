package com.revanced.net.revancedmanager.data.local.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for storing download state in Room database
 * Used to persist download information when app goes to background
 */
@Entity(tableName = "download_states")
data class DownloadStateEntity(
    @PrimaryKey
    val packageName: String,
    val downloadUrl: String,
    val appName: String,
    val status: String, // DOWNLOADING, COMPLETED, FAILED
    val progress: Float = 0f,
    val filePath: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Enum for download status
 */
enum class DownloadStatus {
    DOWNLOADING,
    COMPLETED,
    FAILED
} 