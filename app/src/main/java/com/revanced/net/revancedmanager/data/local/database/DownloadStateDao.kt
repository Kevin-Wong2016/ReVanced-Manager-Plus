package com.revanced.net.revancedmanager.data.local.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing download states in Room database
 */
@Dao
interface DownloadStateDao {
    
    @Query("SELECT * FROM download_states")
    suspend fun getAllDownloadStates(): List<DownloadStateEntity>
    
    @Query("SELECT * FROM download_states WHERE status = :status")
    suspend fun getDownloadStatesByStatus(status: String): List<DownloadStateEntity>
    
    @Query("SELECT * FROM download_states WHERE packageName = :packageName")
    suspend fun getDownloadStateByPackage(packageName: String): DownloadStateEntity?
    
    @Query("SELECT * FROM download_states WHERE status = 'COMPLETED'")
    suspend fun getCompletedDownloads(): List<DownloadStateEntity>
    
    @Query("SELECT * FROM download_states WHERE status = 'DOWNLOADING'")
    suspend fun getActiveDownloads(): List<DownloadStateEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadState(downloadState: DownloadStateEntity)
    
    @Update
    suspend fun updateDownloadState(downloadState: DownloadStateEntity)
    
    @Query("UPDATE download_states SET status = :status, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun updateDownloadStatus(packageName: String, status: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE download_states SET progress = :progress, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun updateDownloadProgress(packageName: String, progress: Float, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE download_states SET status = :status, filePath = :filePath, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun markDownloadCompleted(packageName: String, status: String, filePath: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE download_states SET status = :status, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun markDownloadFailed(packageName: String, status: String, errorMessage: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("DELETE FROM download_states WHERE packageName = :packageName")
    suspend fun deleteDownloadState(packageName: String)
    
    @Query("DELETE FROM download_states WHERE status = :status")
    suspend fun deleteDownloadStatesByStatus(status: String)
    
    @Query("DELETE FROM download_states")
    suspend fun clearAllDownloadStates()
} 