package com.revanced.net.revancedmanager.data.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.revanced.net.revancedmanager.MainActivity
import com.revanced.net.revancedmanager.R
import com.revanced.net.revancedmanager.core.di.NetworkModule
import com.revanced.net.revancedmanager.data.repository.DownloadStateRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Enhanced foreground service for concurrent APK downloads
 * Supports multiple simultaneous downloads with progress tracking
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    @NetworkModule.DownloadClient
    lateinit var okHttpClient: OkHttpClient
    
    @Inject
    lateinit var downloadStateRepository: DownloadStateRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = DownloadBinder()
    
    // Concurrent download management
    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val downloadProgresses = ConcurrentHashMap<String, DownloadProgress>()
    
    // Progress tracking
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = _downloadProgress.asStateFlow()
    
    private val decimalFormat = DecimalFormat("#.##")
    
    companion object {
        private const val TAG = "DownloadService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "download_channel"
        private const val SUMMARY_NOTIFICATION_ID = 1000
        
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_DOWNLOAD_URL = "download_url"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_FILE_PATH = "file_path"
        
        // Broadcast actions
        const val ACTION_DOWNLOAD_COMPLETE = "com.revanced.net.revancedmanager.DOWNLOAD_COMPLETE"
        const val ACTION_ALL_DOWNLOADS_COMPLETE = "com.revanced.net.revancedmanager.ALL_DOWNLOADS_COMPLETE"
        
        fun startDownload(context: Context, packageName: String, downloadUrl: String, appName: String = packageName) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_APP_NAME, appName)
            }
            context.startForegroundService(intent)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 DownloadService created with concurrent support")
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME) ?: return START_NOT_STICKY
        val downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: return START_NOT_STICKY
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: packageName
        
        Log.i(TAG, "📥 Starting concurrent download: $appName ($packageName)")
        
        // Check if already downloading
        if (activeDownloads.containsKey(packageName)) {
            Log.w(TAG, "⚠️ Download already in progress for: $packageName")
            return START_NOT_STICKY
        }
        
        // Start foreground if first download
        if (activeDownloads.isEmpty()) {
            startForeground(SUMMARY_NOTIFICATION_ID, createSummaryNotification())
        }
        
        // Start concurrent download
        startConcurrentDownload(packageName, downloadUrl, appName)
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onDestroy() {
        Log.i(TAG, "🛑 DownloadService destroyed, cancelling all downloads")
        activeDownloads.values.forEach { it.cancel() }
        serviceScope.cancel()
        super.onDestroy()
    }
    
    private fun startConcurrentDownload(packageName: String, downloadUrl: String, appName: String) {
        val job = serviceScope.launch {
            try {
                // Start tracking download in database
                downloadStateRepository.startDownload(packageName, downloadUrl, appName)
                
                val initialProgress = DownloadProgress(packageName, 0f, "Starting download...", false, appName)
                downloadProgresses[packageName] = initialProgress
                updateProgressState()
                
                val result = downloadFile(packageName, downloadUrl, appName)
                
                when (result) {
                    is DownloadResult.Success -> {
                        Log.i(TAG, "✅ Download completed: $appName -> ${result.filePath}")
                        
                        // Mark as completed in database
                        downloadStateRepository.markDownloadCompleted(packageName, result.filePath)
                        
                        val completedProgress = DownloadProgress(packageName, 100f, "Download completed", true, appName, result.filePath)
                        downloadProgresses[packageName] = completedProgress
                        updateProgressState()
                        
                        // Send individual completion broadcast
                        sendDownloadCompleteBroadcast(packageName, result.filePath)
                    }
                    is DownloadResult.Error -> {
                        Log.e(TAG, "❌ Download failed: $appName -> ${result.message}")
                        
                        // Mark as failed in database
                        downloadStateRepository.markDownloadFailed(packageName, result.message)
                        
                        val failedProgress = DownloadProgress(packageName, 0f, result.message, true, appName, error = result.message)
                        downloadProgresses[packageName] = failedProgress
                        updateProgressState()
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                Log.e(TAG, "💥 Download exception for $appName", e)
                
                // Mark as failed in database
                downloadStateRepository.markDownloadFailed(packageName, errorMsg)
                
                val failedProgress = DownloadProgress(packageName, 0f, errorMsg, true, appName, error = errorMsg)
                downloadProgresses[packageName] = failedProgress
                updateProgressState()
            } finally {
                // Remove from active downloads
                activeDownloads.remove(packageName)
                
                // Update summary notification
                updateSummaryNotification()
                
                // Check if all downloads are complete
                if (activeDownloads.isEmpty()) {
                    checkAllDownloadsComplete()
                }
            }
        }
        
        activeDownloads[packageName] = job
    }
    
    private fun updateProgressState() {
        _downloadProgress.value = downloadProgresses.toMap()
    }
    
    private fun checkAllDownloadsComplete() {
        serviceScope.launch {
            delay(1000) // Small delay to ensure all updates are processed
            
            if (activeDownloads.isEmpty()) {
                val completedDownloads = downloadProgresses.values.filter { it.isComplete && it.error == null }
                val failedDownloads = downloadProgresses.values.filter { it.isComplete && it.error != null }
                
                Log.i(TAG, "📊 All downloads finished - Success: ${completedDownloads.size}, Failed: ${failedDownloads.size}")
                
                // Send all downloads complete broadcast
                sendAllDownloadsCompleteBroadcast(completedDownloads, failedDownloads)
                
                // Clean up and stop service
                delay(3000) // Keep notification for 3 seconds
                downloadProgresses.clear()
                stopSelf()
            }
        }
    }
    
    private suspend fun downloadFile(packageName: String, downloadUrl: String, appName: String): DownloadResult {
        val downloadDir = File(getExternalFilesDir(null), "downloads").apply {
            if (!exists()) mkdirs()
        }
        
        val outputFile = File(downloadDir, "$packageName.apk")
        if (outputFile.exists()) outputFile.delete()
        
        return try {
            Log.i(TAG, "📡 Starting HTTP request for: $appName")
            
            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("User-Agent", "ReVancedManager/1.0")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return DownloadResult.Error("HTTP Error: ${response.code}")
            }
            
            val responseBody = response.body ?: return DownloadResult.Error("Empty response")
            val totalBytes = responseBody.contentLength()
            
            Log.i(TAG, "📦 Download started for $appName - Size: ${formatBytes(totalBytes)}")
            
            responseBody.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloadedBytes = 0L
                    var lastUpdateTime = 0L
                    val startTime = System.currentTimeMillis()
                    
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (!currentCoroutineContext().isActive) {
                            outputFile.delete()
                            return DownloadResult.Error("Download cancelled")
                        }
                        
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime > 500) { // Update every 500ms for concurrent downloads
                            val progress = if (totalBytes > 0) {
                                (downloadedBytes.toFloat() / totalBytes * 100).coerceIn(0f, 100f)
                            } else 0f
                            
                            val speed = if (currentTime > startTime) {
                                downloadedBytes * 1000 / (currentTime - startTime)
                            } else 0L
                            
                            val status = "Downloaded: ${formatBytes(downloadedBytes)}" +
                                    if (totalBytes > 0) " / ${formatBytes(totalBytes)}" else "" +
                                    " • ${formatBytes(speed)}/s"
                            
                            val progressData = DownloadProgress(packageName, progress, status, false, appName)
                            downloadProgresses[packageName] = progressData
                            updateProgressState()
                            
                            // Update progress in database
                            try {
                                downloadStateRepository.updateDownloadProgress(packageName, progress)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to update download progress in database", e)
                            }
                            
                            Log.d(TAG, "📈 Download progress [$appName]: ${String.format("%.1f", progress)}% - ${formatBytes(downloadedBytes)}/${formatBytes(totalBytes)} @ ${formatBytes(speed)}/s")
                            
                            // Update summary notification
                            updateSummaryNotification()
                            
                            lastUpdateTime = currentTime
                        }
                    }
                }
            }
            
            Log.i(TAG, "✅ Download completed for $appName - File: ${outputFile.absolutePath}")
            DownloadResult.Success(outputFile.absolutePath)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Download failed for $appName", e)
            if (outputFile.exists()) outputFile.delete()
            DownloadResult.Error(e.message ?: "Download failed")
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Concurrent download progress notifications"
                setSound(null, null)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createSummaryNotification() = 
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloads in progress")
            .setContentText("Preparing downloads...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .setGroup("DOWNLOAD_GROUP")
            .setGroupSummary(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    
    private fun updateSummaryNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (activeDownloads.isEmpty()) {
            val completedCount = downloadProgresses.values.count { it.isComplete && it.error == null }
            val failedCount = downloadProgresses.values.count { it.isComplete && it.error != null }
            
            val summaryText = when {
                completedCount > 0 && failedCount > 0 -> "$completedCount completed, $failedCount failed"
                completedCount > 0 -> "$completedCount downloads completed"
                failedCount > 0 -> "$failedCount downloads failed"
                else -> "All downloads finished"
            }
            
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloads finished")
                .setContentText(summaryText)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setGroup("DOWNLOAD_GROUP")
                .setGroupSummary(true)
                .build()
            
            notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification)
        } else {
            val activeCount = activeDownloads.size
            val completedCount = downloadProgresses.values.count { it.isComplete }
            val totalAvgProgress = downloadProgresses.values
                .filter { !it.isComplete }
                .map { it.progress }
                .average()
                .takeIf { !it.isNaN() } ?: 0.0
            
            val summaryText = if (completedCount > 0) {
                "$activeCount active, $completedCount completed"
            } else {
                "$activeCount downloads active"
            }
            
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloads in progress")
                .setContentText(summaryText)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setSilent(true)
                .setProgress(100, totalAvgProgress.toInt(), totalAvgProgress == 0.0)
                .setGroup("DOWNLOAD_GROUP")
                .setGroupSummary(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            
            notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification)
        }
    }
    
    /**
     * Send broadcast when individual download completes
     */
    private fun sendDownloadCompleteBroadcast(packageName: String, filePath: String) {
        val intent = Intent(ACTION_DOWNLOAD_COMPLETE).apply {
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_FILE_PATH, filePath)
            flags = Intent.FLAG_RECEIVER_FOREGROUND
        }
        
        try {
            sendBroadcast(intent)
            Log.d(TAG, "📡 Individual download complete broadcast sent for: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send download complete broadcast", e)
        }
    }
    
    /**
     * Send broadcast when all downloads are complete
     */
    private fun sendAllDownloadsCompleteBroadcast(
        completedDownloads: List<DownloadProgress>,
        failedDownloads: List<DownloadProgress>
    ) {
        val intent = Intent(ACTION_ALL_DOWNLOADS_COMPLETE).apply {
            putStringArrayListExtra("completed_packages", ArrayList(completedDownloads.map { it.packageName }))
            putStringArrayListExtra("completed_names", ArrayList(completedDownloads.map { it.appName }))
            putStringArrayListExtra("completed_paths", ArrayList(completedDownloads.mapNotNull { it.filePath }))
            putStringArrayListExtra("failed_packages", ArrayList(failedDownloads.map { it.packageName }))
            putStringArrayListExtra("failed_names", ArrayList(failedDownloads.map { it.appName }))
            putStringArrayListExtra("failed_errors", ArrayList(failedDownloads.mapNotNull { it.error }))
            flags = Intent.FLAG_RECEIVER_FOREGROUND
        }
        
        try {
            sendBroadcast(intent)
            Log.d(TAG, "📡 All downloads complete broadcast sent - Success: ${completedDownloads.size}, Failed: ${failedDownloads.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send all downloads complete broadcast", e)
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${decimalFormat.format(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${decimalFormat.format(mb)} MB"
        val gb = mb / 1024.0
        return "${decimalFormat.format(gb)} GB"
    }
    
    fun getActiveDownloads(): Map<String, DownloadProgress> = downloadProgresses.toMap()
    
    inner class DownloadBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }
    
    data class DownloadProgress(
        val packageName: String,
        val progress: Float,
        val status: String,
        val isComplete: Boolean,
        val appName: String,
        val filePath: String? = null,
        val error: String? = null
    )
    
    sealed class DownloadResult {
        data class Success(val filePath: String) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }
} 