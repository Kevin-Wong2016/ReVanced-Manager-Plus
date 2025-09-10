package com.revanced.net.revancedmanager.data.repository

import android.util.Log
import com.revanced.net.revancedmanager.data.local.database.DownloadStateDao
import com.revanced.net.revancedmanager.data.local.database.DownloadStateEntity
import com.revanced.net.revancedmanager.data.local.database.DownloadStatus
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing download states
 * Handles persistence of download information to handle background scenarios
 */
@Singleton
class DownloadStateRepository @Inject constructor(
    private val downloadStateDao: DownloadStateDao
) {
    
    companion object {
        private const val TAG = "DownloadStateRepository"
    }
    
    /**
     * Start tracking a new download
     */
    suspend fun startDownload(packageName: String, downloadUrl: String, appName: String) {
        Log.d(TAG, "Starting download tracking: $packageName")
        val downloadState = DownloadStateEntity(
            packageName = packageName,
            downloadUrl = downloadUrl,
            appName = appName,
            status = DownloadStatus.DOWNLOADING.name,
            progress = 0f
        )
        downloadStateDao.insertDownloadState(downloadState)
    }
    
    /**
     * Update download progress
     */
    suspend fun updateDownloadProgress(packageName: String, progress: Float) {
        downloadStateDao.updateDownloadProgress(packageName, progress)
    }
    
    /**
     * Mark download as completed
     */
    suspend fun markDownloadCompleted(packageName: String, filePath: String) {
        Log.d(TAG, "Marking download completed: $packageName -> $filePath")
        downloadStateDao.markDownloadCompleted(
            packageName = packageName,
            status = DownloadStatus.COMPLETED.name,
            filePath = filePath
        )
    }
    
    /**
     * Mark download as failed
     */
    suspend fun markDownloadFailed(packageName: String, errorMessage: String) {
        Log.d(TAG, "Marking download failed: $packageName -> $errorMessage")
        downloadStateDao.markDownloadFailed(
            packageName = packageName,
            status = DownloadStatus.FAILED.name,
            errorMessage = errorMessage
        )
    }
    
    /**
     * Get all completed downloads that haven't been installed yet
     */
    suspend fun getCompletedDownloads(): List<DownloadStateEntity> {
        val completed = downloadStateDao.getCompletedDownloads()
        Log.d(TAG, "Found ${completed.size} completed downloads")
        
        // Filter out downloads where file no longer exists
        return completed.filter { download ->
            download.filePath?.let { path ->
                val file = File(path)
                val exists = file.exists()
                if (!exists) {
                    Log.w(TAG, "Download file missing, removing from database: $path")
                    // Clean up invalid entry
                    try {
                        downloadStateDao.deleteDownloadState(download.packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to clean up invalid download state", e)
                    }
                }
                exists
            } ?: false
        }
    }
    
    /**
     * Get all active downloads
     */
    suspend fun getActiveDownloads(): List<DownloadStateEntity> {
        return downloadStateDao.getActiveDownloads()
    }
    
    /**
     * Get download state for specific package
     */
    suspend fun getDownloadState(packageName: String): DownloadStateEntity? {
        return downloadStateDao.getDownloadStateByPackage(packageName)
    }
    
    /**
     * Remove download state after successful installation
     */
    suspend fun removeDownloadState(packageName: String) {
        Log.d(TAG, "Removing download state: $packageName")
        downloadStateDao.deleteDownloadState(packageName)
    }
    
    /**
     * Clean up old failed downloads (older than 24 hours)
     */
    suspend fun cleanupOldFailedDownloads() {
        try {
            val allStates = downloadStateDao.getAllDownloadStates()
            val twentyFourHoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
            
            allStates.forEach { state ->
                if (state.status == DownloadStatus.FAILED.name && state.updatedAt < twentyFourHoursAgo) {
                    Log.d(TAG, "Cleaning up old failed download: ${state.packageName}")
                    downloadStateDao.deleteDownloadState(state.packageName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old downloads", e)
        }
    }
    
    /**
     * Check if there are any pending downloads that need attention
     */
    suspend fun hasPendingDownloads(): Boolean {
        val completed = getCompletedDownloads()
        val active = getActiveDownloads()
        return completed.isNotEmpty() || active.isNotEmpty()
    }
    
    /**
     * Clear all download states - used on fresh app start
     */
    suspend fun clearAllDownloadStates() {
        try {
            Log.i(TAG, "Clearing all download states from database")
            downloadStateDao.clearAllDownloadStates()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all download states", e)
            throw e
        }
    }
} 