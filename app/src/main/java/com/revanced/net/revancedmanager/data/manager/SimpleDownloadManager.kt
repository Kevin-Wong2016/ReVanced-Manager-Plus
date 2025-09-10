package com.revanced.net.revancedmanager.data.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.revanced.net.revancedmanager.domain.model.AppDownload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple download manager using DownloadService
 * Clean and efficient solution for background downloads
 */
@Singleton
class SimpleDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "SimpleDownloadManager"
    }
    
    private var downloadService: DownloadService? = null
    private var isBound = false
    
    // Internal state for active downloads
    private val _activeDownloads = MutableStateFlow<Map<String, AppDownload>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, AppDownload>> = _activeDownloads.asStateFlow()
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as DownloadService.DownloadBinder
            downloadService = binder.getService()
            isBound = true
            
            // Start observing download progress
            observeDownloadProgress()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            downloadService = null
            isBound = false
        }
    }
    
    /**
     * Start downloading an app
     * @param packageName Package name of the app
     * @param downloadUrl Download URL
     * @param appName Display name of the app
     * @return Flow of download progress
     */
    fun downloadApp(packageName: String, downloadUrl: String, appName: String = packageName): Flow<AppDownload> {
        Log.i(TAG, "Starting download for: $packageName")
        Log.i(TAG, "App name: $appName")
        Log.i(TAG, "URL: $downloadUrl")
        
        // Start the service
        DownloadService.startDownload(context, packageName, downloadUrl, appName)
        
        // Bind to service if not already bound
        if (!isBound) {
            bindToService()
        }
        
        // Return a flow that emits updates for this specific package
        return activeDownloads.map { downloads ->
            downloads[packageName] ?: AppDownload(
                packageName = packageName,
                url = downloadUrl,
                progress = 0f,
                isComplete = false
            )
        }
    }
    
    /**
     * Cancel download for a specific package
     */
    fun cancelDownload(packageName: String) {
        Log.i(TAG, "Cancelling download for: $packageName")
        // The service will handle cancellation automatically when it's destroyed
        // For now, we just remove from our tracking
        val current = _activeDownloads.value.toMutableMap()
        current.remove(packageName)
        _activeDownloads.value = current
    }
    
    /**
     * Cancel all active downloads
     */
    fun cancelAllDownloads() {
        Log.i(TAG, "Cancelling all active downloads")
        _activeDownloads.value = emptyMap()
    }
    
    /**
     * Check if a download is currently active
     */
    fun isDownloadActive(packageName: String): Boolean {
        val download = _activeDownloads.value[packageName]
        return download != null && !download.isComplete
    }
    
    /**
     * Get all active downloads
     */
    fun getActiveDownloads(): Map<String, AppDownload> {
        return _activeDownloads.value
    }
    
    private fun bindToService() {
        val intent = Intent(context, DownloadService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "Binding to DownloadService")
    }
    
    private fun observeDownloadProgress() {
        downloadService?.let { service ->
            // Convert service progress to our AppDownload format
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                service.downloadProgress.collect { progressMap ->
                    Log.d(TAG, "Progress update for ${progressMap.size} downloads")
                    
                    val updatedDownloads = mutableMapOf<String, AppDownload>()
                    
                    progressMap.forEach { (packageName, progress) ->
                        Log.d(TAG, "Progress update: $packageName - ${progress.progress}%")
                        
                        val appDownload = AppDownload(
                            packageName = packageName,
                            url = "", // URL not needed for progress updates
                            progress = progress.progress / 100f,
                            isComplete = progress.isComplete,
                            filePath = progress.filePath
                        )
                        
                        updatedDownloads[packageName] = appDownload
                    }
                    
                    // Update our state
                    _activeDownloads.value = updatedDownloads
                    
                    // Remove completed downloads after delay
                    progressMap.values.filter { it.isComplete }.forEach { completedProgress ->
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(5000) // Keep for 5 seconds
                            val current = _activeDownloads.value.toMutableMap()
                            current.remove(completedProgress.packageName)
                            _activeDownloads.value = current
                        }
                    }
                }
            }
        }
    }
    
    fun cleanup() {
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
        }
    }
} 