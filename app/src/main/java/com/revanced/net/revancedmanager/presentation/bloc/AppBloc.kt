package com.revanced.net.revancedmanager.presentation.bloc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revanced.net.revancedmanager.R
import com.revanced.net.revancedmanager.core.common.LocaleHelper
import com.revanced.net.revancedmanager.core.common.Result
import com.revanced.net.revancedmanager.core.common.StringProvider
import com.revanced.net.revancedmanager.data.local.preferences.PreferencesManager
import com.revanced.net.revancedmanager.data.manager.AppManager
import com.revanced.net.revancedmanager.data.manager.DownloadService
import com.revanced.net.revancedmanager.data.manager.SimpleDownloadManager
import com.revanced.net.revancedmanager.data.manager.RevancedPackageInstaller
import com.revanced.net.revancedmanager.data.manager.InstallationResult
import com.revanced.net.revancedmanager.data.repository.DownloadStateRepository
import com.revanced.net.revancedmanager.domain.model.AppConfig
import com.revanced.net.revancedmanager.domain.model.AppStatus
import com.revanced.net.revancedmanager.domain.model.Language
import com.revanced.net.revancedmanager.domain.model.RevancedApp
import com.revanced.net.revancedmanager.domain.model.ThemeMode
import com.revanced.net.revancedmanager.domain.usecase.AppManagementUseCases
import com.revanced.net.revancedmanager.domain.usecase.DownloadAppUseCase
import com.revanced.net.revancedmanager.domain.usecase.GetAppsUseCase
import com.revanced.net.revancedmanager.domain.usecase.InstallAppUseCase
import com.revanced.net.revancedmanager.domain.usecase.OpenAppUseCase
import com.revanced.net.revancedmanager.domain.usecase.UninstallAppUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * BLoC for managing app state and handling user events
 * Improved version with better download/install flow and error handling
 */
@HiltViewModel
class AppBloc @Inject constructor(
    @ApplicationContext private val context: Context,
    private val useCases: AppManagementUseCases,
    private val simpleDownloadManager: SimpleDownloadManager,
    private val appManager: AppManager,
    private val preferencesManager: PreferencesManager,
    private val stringProvider: StringProvider,
    private val packageInstaller: RevancedPackageInstaller,
    private val downloadStateRepository: DownloadStateRepository
) : ViewModel(), DefaultLifecycleObserver {

    companion object {
        private const val TAG = "AppBloc"
    }

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // Track concurrent download/install states
    private val activeDownloads = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val installationRetries = mutableMapOf<String, Int>()
    private val completedDownloads = mutableSetOf<String>()
    
    // Installation Queue for parallel installation prompts
    private val installationQueue = mutableListOf<PendingInstallation>()
    
    // Track if app was backgrounded to determine auto-install behavior
    private var wasAppBackgrounded = false
    
    data class PendingInstallation(
        val packageName: String,
        val filePath: String,
        val appName: String
    )
    
    // Broadcast receiver for download completion - enhanced for concurrent downloads
    private val downloadCompleteBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadService.ACTION_DOWNLOAD_COMPLETE -> {
                    val packageName = intent.getStringExtra(DownloadService.EXTRA_PACKAGE_NAME)
                    val filePath = intent.getStringExtra(DownloadService.EXTRA_FILE_PATH)
                    
                    if (packageName != null && filePath != null) {
                        Log.i(TAG, "📥 Individual download complete broadcast: $packageName")
                        handleEvent(AppEvent.DownloadCompleted(packageName, filePath))
                    }
                }
                
                DownloadService.ACTION_ALL_DOWNLOADS_COMPLETE -> {
                    Log.i(TAG, "🎉 All downloads complete broadcast received")
                    
                    val completedPackages = intent.getStringArrayListExtra("completed_packages") ?: arrayListOf()
                    val completedNames = intent.getStringArrayListExtra("completed_names") ?: arrayListOf()
                    val completedPaths = intent.getStringArrayListExtra("completed_paths") ?: arrayListOf()
                    val failedPackages = intent.getStringArrayListExtra("failed_packages") ?: arrayListOf()
                    val failedNames = intent.getStringArrayListExtra("failed_names") ?: arrayListOf()
                    val failedErrors = intent.getStringArrayListExtra("failed_errors") ?: arrayListOf()
                    
                    // Auto-install all completed downloads immediately
                    handleEvent(AppEvent.AutoInstallAllCompleted(
                        completedPackages, completedNames, completedPaths,
                        failedPackages, failedNames, failedErrors
                    ))
                }
            }
        }
    }

    init {
        Log.i(TAG, "AppBloc initialized")
        handleEvent(AppEvent.LoadConfiguration)
        handleEvent(AppEvent.LoadAppsFromCacheFirst)  // Changed to load cache first
        
        // Listen for installation events from PackageInstaller
        setupPackageInstallerListener()
        
        // Setup lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Register broadcast receiver for download completion
        registerDownloadBroadcastReceiver()
    }

    /**
     * Setup PackageInstaller listener for immediate and accurate installation feedback
     */
    private fun setupPackageInstallerListener() {
        packageInstaller.installationResults
            .onEach { result ->
                when (result) {
                    is InstallationResult.Success -> {
                        Log.i(TAG, "✅ Installation successful: ${result.packageName}")
                        handleInstallationSuccess(result.packageName, appManager.getInstalledVersion(result.packageName) ?: "Unknown")
                        // Clean up queue after successful installation
                        Log.i(TAG, "🔄 Installation success - cleaning up queue")
                        processNextInstallation()
                    }
                    
                    is InstallationResult.Failed -> {
                        Log.i(TAG, "❌ Installation failed: ${result.packageName}, error: ${result.error}")
                        
                        // 🔥 Check if user aborted/cancelled installation - don't retry in this case
                        val isUserAbort = result.error.contains("aborted", ignoreCase = true) || 
                                         result.error.contains("cancelled", ignoreCase = true) ||
                                         result.error.contains("user denied", ignoreCase = true) ||
                                         result.statusCode == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED
                        
                        if (isUserAbort) {
                            Log.i(TAG, "🚫 User aborted installation for: ${result.packageName}, not showing retry dialog")
                            handleInstallationAborted(result.packageName, result.error)
                        } else {
                            handleInstallationFailedWithRetry(result.packageName, result.error)
                        }
                        
                        // Clean up queue after failed installation
                        Log.i(TAG, "🔄 Installation failed - cleaning up queue")
                        processNextInstallation()
                    }
                    
                    is InstallationResult.PendingUserAction -> {
                        Log.i(TAG, "⏳ User action required for: ${result.packageName}")
                        showToast(stringProvider.getString(R.string.installation_pending_user_action))
                        
                        // Start a timeout to handle user cancellation/inaction - but only when app is in foreground
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(30000) // 30 seconds timeout
                            
                            // 🔥 ONLY process timeout if app is currently in foreground
                            if (!wasAppBackgrounded) {
                                // Check if this specific installation is still pending
                                val stillPending = installationQueue.any { it.packageName == result.packageName }
                                if (stillPending) {
                                    Log.w(TAG, "⏰ Installation timeout for: ${result.packageName}, assuming user cancelled")
                                    handleInstallationAborted(result.packageName, "User cancelled or timeout")
                                    processNextInstallation()
                                }
                            } else {
                                Log.i(TAG, "⏳ App is backgrounded, skipping timeout cleanup for: ${result.packageName}")
                            }
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }
    
    /**
     * Register broadcast receiver for download completion events - enhanced for concurrent downloads
     */
    private fun registerDownloadBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_DOWNLOAD_COMPLETE)
            addAction(DownloadService.ACTION_ALL_DOWNLOADS_COMPLETE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadCompleteBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadCompleteBroadcastReceiver, filter)
        }
        Log.i(TAG, "📡 Enhanced download broadcast receiver registered")
    }
    
    /**
     * Lifecycle event - App moved to foreground
     */
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.i(TAG, "🌟 === APP MOVED TO FOREGROUND === 🌟")
        Log.i(TAG, "📱 Owner: ${owner.javaClass.simpleName}")
        Log.i(TAG, "📱 Was backgrounded: $wasAppBackgrounded")
        
        if (wasAppBackgrounded) {
            Log.i(TAG, "🔄 App was backgrounded, checking pending downloads...")
            checkPendingDownloadsOnForeground()
        } else {
            Log.i(TAG, "🔄 Fresh app start, resetting all queues and pending downloads...")
            resetAllQueuesAndPendingDownloads()
        }
        
        // Reset background flag
        wasAppBackgrounded = false
    }
    
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.i(TAG, "⭐ === APP MOVED TO BACKGROUND === ⭐")
        wasAppBackgrounded = true
    }
    
    /**
     * Reset all queues and pending downloads on fresh app start
     */
    private fun resetAllQueuesAndPendingDownloads() {
        Log.i(TAG, "🗑️ === RESETTING ALL QUEUES AND PENDING DOWNLOADS ===")
        
        viewModelScope.launch {
            try {
                // Clear installation queue
                clearInstallationQueue()
                
                // Clear all pending download states
                downloadStateRepository.clearAllDownloadStates()
                Log.i(TAG, "🗑️ Cleared all download states from database")
                
                // Reset all app statuses to UPDATE_AVAILABLE if they were in downloading/installing states
                val currentState = _state.value
                if (currentState is AppState.Success) {
                    val updatedApps = currentState.apps.map { app ->
                        when (app.status) {
                            AppStatus.DOWNLOADING, 
                            AppStatus.INSTALLING -> {
                                Log.i(TAG, "🔄 Resetting ${app.packageName} status from ${app.status} to UPDATE_AVAILABLE")
                                app.copy(status = AppStatus.UPDATE_AVAILABLE, downloadProgress = 0f)
                            }
                            else -> app
                        }
                    }
                    _state.value = currentState.copy(apps = updatedApps)
                }
                
                Log.i(TAG, "✅ All queues and pending downloads reset successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error resetting queues and pending downloads", e)
            }
        }
    }
    
    /**
     * Check for pending downloads when app comes to foreground
     * Enhanced to force-check download service status and handle missed broadcasts
     */
    private fun checkPendingDownloadsOnForeground() {
        Log.i(TAG, "🔍 === CHECKING PENDING DOWNLOADS ON FOREGROUND ===")
        
        viewModelScope.launch {
            try {
                // Debug auto-install preference first
                val isAutoInstallEnabled = preferencesManager.isAutoInstallEnabled()
                Log.i(TAG, "🔧 Auto-install enabled: $isAutoInstallEnabled")
                
                // 🔥 NEW: Force check download service progress to catch missed broadcasts
                Log.i(TAG, "🔍 Force checking download service status...")
                checkDownloadServiceProgress()
                
                // Small delay to let service updates process
                delay(1000)
                
                val completedDownloads = downloadStateRepository.getCompletedDownloads()
                val activeDownloads = downloadStateRepository.getActiveDownloads()
                
                Log.i(TAG, "📊 Found ${completedDownloads.size} completed downloads, ${activeDownloads.size} active downloads")
                
                // Debug completed downloads details
                completedDownloads.forEachIndexed { index, download ->
                    Log.i(TAG, "📦 Completed download [$index]: ${download.packageName}")
                    Log.i(TAG, "    📂 File path: ${download.filePath}")
                    Log.i(TAG, "    📅 Created: ${download.createdAt}")
                    Log.i(TAG, "    📅 Updated: ${download.updatedAt}")
                    
                    // Check if file exists
                    val fileExists = download.filePath?.let { java.io.File(it).exists() } ?: false
                    Log.i(TAG, "    📁 File exists: $fileExists")
                }
                
                // 🔥 Process completed downloads immediately
                if (completedDownloads.isNotEmpty()) {
                    Log.i(TAG, "🚀 Processing ${completedDownloads.size} completed downloads immediately")
                    
                    completedDownloads.forEach { download ->
                        Log.i(TAG, "⚡ Triggering installation for completed download: ${download.packageName}")
                        
                        // Trigger installation immediately
                        queueInstallation(download.packageName, download.filePath!!, download.appName)
                    }
                } else {
                    Log.i(TAG, "📭 No completed downloads found")
                }
                
                // Handle active downloads that might have been interrupted or need status update
                activeDownloads.forEach { download ->
                    Log.w(TAG, "🔄 Found active download, checking status: ${download.packageName}")
                    
                    // Check if file actually exists and is complete
                    val filePath = download.filePath
                    if (filePath != null && java.io.File(filePath).exists()) {
                        Log.i(TAG, "📦 Active download appears complete, marking as completed: ${download.packageName}")
                        // Mark as completed and trigger installation
                        downloadStateRepository.markDownloadCompleted(download.packageName, filePath)
                        queueInstallation(download.packageName, filePath, download.appName)
                    } else {
                        Log.w(TAG, "❌ Active download file missing or incomplete, cleaning up: ${download.packageName}")
                        // Reset UI state - user can retry if needed
                        updateAppStatus(download.packageName, AppStatus.UPDATE_AVAILABLE)
                        // Clean up the interrupted download state
                        downloadStateRepository.removeDownloadState(download.packageName)
                    }
                }
                
                // Clean up old failed downloads
                downloadStateRepository.cleanupOldFailedDownloads()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking pending downloads", e)
            }
        }
    }
    
    /**
     * Force check download service progress to catch missed broadcasts
     */
    private fun checkDownloadServiceProgress() {
        try {
            Log.i(TAG, "🔍 Checking SimpleDownloadManager for active downloads...")
            
            // Get current download progress from SimpleDownloadManager
            val activeDownloads = simpleDownloadManager.getActiveDownloads()
            
            Log.i(TAG, "📊 SimpleDownloadManager reports ${activeDownloads.size} active downloads")
            
            activeDownloads.forEach { (packageName, download) ->
                Log.i(TAG, "📥 Active download: $packageName - Progress: ${download.progress}% - Complete: ${download.isComplete}")
                
                if (download.isComplete && download.filePath != null) {
                    Log.i(TAG, "🎉 Found completed download that may have missed broadcast: $packageName")
                    
                    // Trigger download completion handling
                    handleEvent(AppEvent.DownloadCompleted(packageName, download.filePath!!))
                } else if (!download.isComplete) {
                    Log.i(TAG, "🔄 Download still in progress: $packageName (${download.progress}%)")
                    
                    // Update UI progress
                    updateAppProgress(packageName, download.progress)
                    updateAppStatus(packageName, AppStatus.DOWNLOADING)
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error checking download service progress", e)
        }
    }
    
    /**
     * Clean up resources when ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()
        try {
            context.unregisterReceiver(downloadCompleteBroadcastReceiver)
            Log.i(TAG, "Download broadcast receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister broadcast receiver", e)
        }
    }

    /**
     * Handle incoming events
     */
    fun handleEvent(event: AppEvent) {
        Log.d(TAG, "Handling event: ${event::class.simpleName}")
        when (event) {
            is AppEvent.LoadApps -> loadApps(forceRefresh = false)
            is AppEvent.RefreshApps -> loadApps(forceRefresh = true)
            is AppEvent.LoadAppsFromCacheFirst -> loadAppsFromCacheFirst()
            is AppEvent.BackgroundRefreshApps -> backgroundRefreshApps()
            is AppEvent.UpdateSingleApp -> updateSingleApp(event.app)
            is AppEvent.DownloadApp -> downloadApp(event.packageName, event.downloadUrl)
            is AppEvent.InstallApp -> installApp(event.packageName, event.apkFilePath)
            is AppEvent.UninstallApp -> uninstallApp(event.packageName)
            is AppEvent.ShowReinstallConfirmation -> showReinstallConfirmation(event.packageName)
            is AppEvent.ReinstallApp -> reinstallApp(event.packageName)
            is AppEvent.OpenApp -> openApp(event.packageName)
            is AppEvent.UpdateAppProgress -> updateAppProgress(event.packageName, event.progress)
            is AppEvent.UpdateAppStatus -> updateAppStatus(event.packageName, event.status)
            is AppEvent.ShowError -> showError(event.message)
            is AppEvent.RetryInstallation -> retryInstallation(event.packageName, event.apkFilePath, event.shouldUninstallFirst)
            is AppEvent.CancelDownload -> cancelDownload(event.packageName)
            is AppEvent.ConfirmUninstallBeforeReinstall -> confirmUninstallBeforeReinstall(event.packageName, event.apkFilePath)
            is AppEvent.DismissDialog -> dismissDialog()
            is AppEvent.DismissDialogAndUpdateStatus -> {
                dismissDialog()
                updateAppStatus(event.packageName, event.status)
            }

            is AppEvent.DownloadCompleted -> handleDownloadCompleted(event.packageName, event.filePath)
            is AppEvent.DownloadFailed -> handleDownloadFailed(event.packageName, event.error)
            is AppEvent.InstallationCompleted -> handleInstallationCompleted(event.packageName, event.success)
            is AppEvent.ShowConfirmationDialog -> showConfirmationDialog(event.title, event.message, event.onConfirm, event.onCancel)
            is AppEvent.ShowConfigDialog -> showConfigDialog()
            is AppEvent.SaveConfiguration -> saveConfiguration(event.config)
            is AppEvent.UpdateCompactMode -> updateCompactMode(event.enabled)
            is AppEvent.LoadConfiguration -> loadConfiguration()
            
            // Concurrent downloads events - simplified for auto-install
            is AppEvent.AutoInstallAllCompleted -> autoInstallAllCompleted(
                event.completedPackages, event.completedNames, event.completedPaths,
                event.failedPackages, event.failedNames, event.failedErrors
            )
        }
    }

    /**
     * Load apps from cache first for fast UI, then trigger background refresh
     */
    private fun loadAppsFromCacheFirst() {
        Log.i(TAG, "Loading apps from cache first for fast UI")
        viewModelScope.launch {
            useCases.appRepository.getAppsFromCacheImmediately()
                .onEach { result ->
                    when (result) {
                        is Result.Loading -> {
                            Log.d(TAG, "No cache available, loading from network")
                            _state.value = AppState.Loading
                            // If no cache, fallback to normal loading
                            loadApps(forceRefresh = false)
                        }
                        is Result.Success -> {
                            Log.i(TAG, "Cache loaded successfully: ${result.data.size} items")
                            val config = try {
                                preferencesManager.getAppConfig()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to load config, using fallback", e)
                                AppConfig(ThemeMode.DARK, Language.ENGLISH)
                            }
                            _state.value = AppState.Success(result.data, config = config)
                            
                            // Start background refresh after a short delay to let UI render
                            viewModelScope.launch {
                                delay(500) // 500ms delay to let UI stabilize
                                Log.i(TAG, "Starting background refresh after UI render")
                                handleEvent(AppEvent.BackgroundRefreshApps)
                            }
                        }
                        is Result.Error -> {
                            Log.e(TAG, "Failed to load cached apps", result.exception)
                            // If cache loading fails, fallback to normal loading
                            loadApps(forceRefresh = false)
                        }
                    }
                }
                .launchIn(this)
        }
    }

    /**
     * Background refresh apps and update UI smoothly
     */
    private fun backgroundRefreshApps() {
        Log.i(TAG, "Background refresh starting")
        viewModelScope.launch {
            useCases.appRepository.backgroundRefreshApps()
                .onEach { result ->
                    when (result) {
                        is Result.Loading -> {
                            Log.d(TAG, "Background refresh in progress...")
                            // Don't show loading state during background refresh
                        }
                        is Result.Success -> {
                            Log.i(TAG, "Background refresh completed: ${result.data.size} items")
                            
                            val currentState = _state.value
                            if (currentState is AppState.Success) {
                                val oldApps = currentState.apps
                                val newApps = result.data
                                
                                // Find which apps have been updated
                                val updatedApps = useCases.appRepository.getUpdatedApps(oldApps, newApps)
                                
                                if (updatedApps.isNotEmpty()) {
                                    Log.i(TAG, "Found ${updatedApps.size} updated apps, updating UI smoothly")
                                    
                                    // Update the entire list with smooth transition
                                    val config = try {
                                        preferencesManager.getAppConfig()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to load config, using fallback", e)
                                        AppConfig(ThemeMode.DARK, Language.ENGLISH)
                                    }
                                    
                                    _state.value = AppState.Success(newApps, config = config)
                                    
                                    // Show a subtle toast if there are significant updates
                                    if (updatedApps.size > 1) {
                                        showToast(stringProvider.getString(R.string.apps_updated, updatedApps.size))
                                    }
                                } else {
                                    Log.d(TAG, "No app updates found during background refresh")
                                }
                            } else {
                                Log.w(TAG, "Background refresh completed but current state is not Success")
                            }
                        }
                        is Result.Error -> {
                            Log.w(TAG, "Background refresh failed, keeping current state", result.exception)
                            // Don't update UI on background refresh failure
                            // User will still see cached data
                        }
                    }
                }
                .launchIn(this)
        }
    }

    /**
     * Update a single app in the current list
     */
    private fun updateSingleApp(updatedApp: RevancedApp) {
        Log.d(TAG, "Updating single app: ${updatedApp.packageName}")
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == updatedApp.packageName) {
                    updatedApp
                } else {
                    app
                }
            }
            _state.value = currentState.copy(apps = updatedApps)
            Log.d(TAG, "Single app updated in UI: ${updatedApp.packageName}")
        }
    }

    /**
     * Load apps from repository
     */
    private fun loadApps(forceRefresh: Boolean) {
        Log.i(TAG, "Loading apps, forceRefresh: $forceRefresh")
        viewModelScope.launch {
            useCases.getAppsUseCase(forceRefresh)
                .onEach { result ->
                    when (result) {
                        is Result.Loading -> {
                            Log.d(TAG, "Apps loading...")
                            _state.value = AppState.Loading
                        }
                        is Result.Success -> {
                            Log.i(TAG, "Apps loaded successfully: ${result.data.size} items")
                            // Ensure config is included when transitioning to Success state
                            val config = try {
                                preferencesManager.getAppConfig()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to load config during app loading, using fallback", e)
                                AppConfig(ThemeMode.DARK, Language.ENGLISH)
                            }
                            _state.value = AppState.Success(result.data, config = config)
                        }
                        is Result.Error -> {
                            Log.e(TAG, "Failed to load apps", result.exception)
                            // Ensure config is included even in Error state
                            val config = try {
                                preferencesManager.getAppConfig()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to load config during app error, using fallback", e)
                                AppConfig(ThemeMode.DARK, Language.ENGLISH)
                            }
                            _state.value = AppState.Error(
                                message = result.message,
                                dialogState = null,
                                config = config
                            )
                        }
                    }
                }
                .launchIn(this)
        }
    }

    /**
     * Download an app using modern WorkManager-based system
     */
    private fun downloadApp(packageName: String, downloadUrl: String) {
        Log.i(TAG, "Starting download: $packageName")
        Log.d(TAG, "Download URL: $downloadUrl")
        
        // Cancel any existing download for this package
        cancelDownload(packageName)
        
        // 🔥 Clear completed downloads tracking to allow re-download and installation
        completedDownloads.remove(packageName)
        Log.i(TAG, "🗑️ Cleared completed downloads tracking for: $packageName")
        
        // Storage space check is handled in the service
        // Proceed directly to download
        
        val downloadJob = viewModelScope.launch {
            try {
                // Update app status to downloading
                updateAppStatus(packageName, AppStatus.DOWNLOADING)
                updateAppProgress(packageName, 0f)
                
                // Show starting message
                showToast(stringProvider.getString(R.string.download_starting))
                Log.i(TAG, "Download starting for: $packageName")
                
                simpleDownloadManager.downloadApp(packageName, downloadUrl)
                    .catch { error ->
                        Log.e(TAG, "Download failed for $packageName", error)
                        val errorMessage = when {
                            error.message?.contains("insufficient memory", ignoreCase = true) == true ||
                            error.message?.contains("OutOfMemoryError", ignoreCase = true) == true -> {
                                stringProvider.getString(R.string.download_failed_memory)
                            }
                            error.message?.contains("space", ignoreCase = true) == true -> {
                                stringProvider.getString(R.string.download_failed_storage)
                            }
                            else -> {
                                stringProvider.getString(R.string.download_failed, error.message ?: "Unknown error")
                            }
                        }
                        handleDownloadFailed(packageName, errorMessage)
                    }
                    .onEach { download ->
                        // Update progress
                        val progressPercent = download.progress * 100f
                        updateAppProgress(packageName, download.progress)
                        
                        // Log progress more frequently for better monitoring
                        Log.d(TAG, "Download progress for $packageName: ${String.format("%.2f", progressPercent)}% (${download.progress})")
                        
                        // Additional detailed logging for progress tracking
                        if (progressPercent > 0f) {
                            Log.i(TAG, "[$packageName] Download progress: ${String.format("%.1f", progressPercent)}%")
                        }
                        
                        if (download.isComplete && download.filePath != null) {
                            Log.i(TAG, "Download completed for $packageName: ${download.filePath}")
                            handleDownloadCompleted(packageName, download.filePath)
                            // Stop progress monitoring after completion
                            return@onEach
                        }
                    }
                    .launchIn(this)
                
            } catch (e: Exception) {
                Log.e(TAG, "Download error for $packageName", e)
                val errorMessage = when {
                    e.message?.contains("insufficient memory", ignoreCase = true) == true ||
                    e.message?.contains("OutOfMemoryError", ignoreCase = true) == true -> {
                        stringProvider.getString(R.string.download_failed_memory)
                    }
                    e.message?.contains("space", ignoreCase = true) == true -> {
                        stringProvider.getString(R.string.download_failed_storage)
                    }
                    else -> {
                        stringProvider.getString(R.string.download_failed, e.message ?: "Unknown error")
                    }
                }
                handleDownloadFailed(packageName, errorMessage)
            }
        }
        
        activeDownloads[packageName] = downloadJob
    }

    /**
     * Handle download completion - auto-install immediately
     */
    private fun handleDownloadCompleted(packageName: String, filePath: String) {
        // Prevent duplicate handling of the same download completion
        if (completedDownloads.contains(packageName)) {
            Log.w(TAG, "Download completion already handled for: $packageName")
            return
        }
        
        Log.i(TAG, "🚀 Download completed, queueing for installation: $packageName -> $filePath")
        completedDownloads.add(packageName)
        activeDownloads.remove(packageName)
        
        // Get app name for better logging
        val currentState = _state.value
        val appName = if (currentState is AppState.Success) {
            currentState.apps.find { it.packageName == packageName }?.title ?: packageName
        } else {
            packageName
        }
        
        // Add to installation queue
        queueInstallation(packageName, filePath, appName)
        showToast(stringProvider.getString(R.string.download_completed_installing))
    }

    /**
     * Handle download failure
     */
    private fun handleDownloadFailed(packageName: String, error: String) {
        Log.e(TAG, "Download failed: $packageName - $error")
        activeDownloads.remove(packageName)
        
        updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
        updateAppProgress(packageName, 0f)
        showError(error)
    }

    /**
     * Cancel an ongoing download
     */
    private fun cancelDownload(packageName: String) {
        Log.i(TAG, "Cancelling download: $packageName")
        activeDownloads[packageName]?.cancel()
        activeDownloads.remove(packageName)
        
        // Cancel download
        simpleDownloadManager.cancelDownload(packageName)
        
        updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
        updateAppProgress(packageName, 0f)
        
        showToast(stringProvider.getString(R.string.download_cancelled))
    }

    /**
     * Install an app using queue system (public interface)
     */
    private fun installApp(packageName: String, apkFilePath: String) {
        val currentState = _state.value
        val appName = if (currentState is AppState.Success) {
            currentState.apps.find { it.packageName == packageName }?.title ?: packageName
        } else {
            packageName
        }
        
        Log.i(TAG, "📋 Queueing app installation: $appName ($packageName)")
        queueInstallation(packageName, apkFilePath, appName)
    }
    
    /**
     * Install an app directly using PackageInstaller API (used by queue)
     */
    private fun installAppDirect(packageName: String, apkFilePath: String) {
        Log.i(TAG, "🚀 Installing app directly: $packageName from $apkFilePath")
        viewModelScope.launch {
            try {
                val result = useCases.installAppUseCase(packageName, apkFilePath)
                when (result) {
                    is Result.Success -> {
                        if (result.data) {
                            Log.i(TAG, "✅ Installation started successfully: $packageName")
                            showToast(stringProvider.getString(R.string.installation_started))
                            // PackageInstaller will handle the rest via BroadcastReceiver
                        } else {
                            Log.w(TAG, "⚠️ Installation failed to start: $packageName")
                            handleInstallationFailedWithRetry(packageName, stringProvider.getString(R.string.installation_failed_start))
                        }
                    }
                    is Result.Error -> {
                        Log.e(TAG, "❌ Installation error: $packageName", result.exception)
                        handleInstallationFailedWithRetry(packageName, result.message)
                    }
                    is Result.Loading -> {
                        // Should not happen in this case
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 Installation exception: $packageName", e)
                handleInstallationFailedWithRetry(packageName, e.message ?: "Installation failed")
            }
        }
    }

    /**
     * Handle installation failure with immediate error feedback and retry dialog
     */
    private fun handleInstallationFailedWithRetry(packageName: String, error: String) {
        val currentRetries = installationRetries[packageName] ?: 0
        
        // Update app status based on current installation state
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val app = currentState.apps.find { it.packageName == packageName }
            app?.let {
                val isInstalled = appManager.isAppInstalled(packageName)
                val newStatus = if (isInstalled) {
                    val installedVersion = appManager.getInstalledVersion(packageName)
                    if (installedVersion != null) {
                        when (compareVersions(installedVersion, app.latestVersion)) {
                            0 -> AppStatus.UP_TO_DATE
                            1 -> AppStatus.UP_TO_DATE
                            else -> AppStatus.UPDATE_AVAILABLE
                        }
                    } else {
                        AppStatus.NOT_INSTALLED
                    }
                } else {
                    AppStatus.NOT_INSTALLED
                }
                
                updateAppStatus(packageName, newStatus)
            }
        }
        
        if (currentRetries < 1) { // Allow one retry
            installationRetries[packageName] = currentRetries + 1
            
            // Show retry dialog
            showConfirmationDialog(
                title = stringProvider.getString(R.string.installation_failed_title),
                message = stringProvider.getString(R.string.installation_failed_retry_message, error),
                onConfirm = AppEvent.RetryInstallation(packageName, getDownloadPath(packageName) ?: "", shouldUninstallFirst = true),
                onCancel = AppEvent.DismissDialogAndUpdateStatus(packageName, AppStatus.UPDATE_AVAILABLE)
            )
        } else {
            // Max retries reached
            installationRetries.remove(packageName)
            showError(stringProvider.getString(R.string.download_failed, error))
        }
        
        // Remove from installation queue
        installationQueue.removeAll { it.packageName == packageName }
        Log.i(TAG, "🗑️ Removed failed installation from queue: $packageName")
        
        // Remove from download state database to prevent re-queuing
        viewModelScope.launch {
            try {
                downloadStateRepository.removeDownloadState(packageName)
                Log.i(TAG, "🗑️ Removed failed installation from download state: $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove download state for failed installation", e)
            }
        }
    }

    /**
     * Handle successful installation
     */
    private fun handleInstallationSuccess(packageName: String, installedVersion: String) {
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val app = currentState.apps.find { it.packageName == packageName }
            app?.let {
                val newStatus = when (compareVersions(installedVersion, app.latestVersion)) {
                    0 -> AppStatus.UP_TO_DATE  // Same version as latest
                    1 -> AppStatus.UP_TO_DATE  // Installed version is newer than latest
                    else -> AppStatus.UPDATE_AVAILABLE  // Still needs update (rare case)
                }
                
                val updatedApps = currentState.apps.map { appItem ->
                    if (appItem.packageName == packageName) {
                        appItem.copy(
                            status = newStatus,
                            currentVersion = installedVersion,
                            downloadProgress = 0f
                        )
                    } else {
                        appItem
                    }
                }
                
                _state.value = currentState.copy(apps = updatedApps)
                showToast(stringProvider.getString(R.string.installation_completed))
                
                // Clear pending install state and retries
                preferencesManager.removeKey("pending_install_${packageName}")
                installationRetries.remove(packageName)
                completedDownloads.remove(packageName)
                
                // Remove from installation queue
                installationQueue.removeAll { it.packageName == packageName }
                Log.i(TAG, "🗑️ Removed successful installation from queue: $packageName")
                
                // Remove download state from database after successful installation
                viewModelScope.launch {
                    try {
                        downloadStateRepository.removeDownloadState(packageName)
                        Log.d(TAG, "Removed download state after successful installation: $packageName")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove download state", e)
                    }
                }
            }
        }
    }

    /**
     * Update status of a single app without refreshing the entire list
     * This prevents screen flickering and unnecessary API calls
     */
    private suspend fun updateSingleAppStatus(packageName: String) {
        try {
            val currentState = _state.value
            if (currentState is AppState.Success) {
                val appToUpdate = currentState.apps.find { it.packageName == packageName }
                if (appToUpdate != null) {
                    // Don't update status if app is currently being processed
                    // This prevents overriding INSTALLING/DOWNLOADING/UNINSTALLING states
                    if (appToUpdate.status in listOf(
                        AppStatus.DOWNLOADING,
                        AppStatus.INSTALLING,
                        AppStatus.UNINSTALLING
                    )) {
                        // Only handle UNINSTALLING status here, others are handled by their respective monitoring functions
                        if (appToUpdate.status == AppStatus.UNINSTALLING) {
                            val isInstalled = appManager.isAppInstalled(packageName)
                            if (!isInstalled) {
                                // Uninstallation completed
                                val updatedApps = currentState.apps.map { app ->
                                    if (app.packageName == packageName) {
                                        app.copy(
                                            status = AppStatus.NOT_INSTALLED,
                                            currentVersion = null,
                                            downloadProgress = 0f
                                        )
                                    } else {
                                        app
                                    }
                                }
                                
                                _state.value = currentState.copy(apps = updatedApps)
                                showToast(stringProvider.getString(R.string.uninstallation_completed))
                            }
                        }
                        // For DOWNLOADING and INSTALLING, don't interfere - let their monitoring functions handle
                        return
                    }
                    
                    // Normal status update for non-processing apps
                    val isInstalled = appManager.isAppInstalled(packageName)
                    val installedVersion = if (isInstalled) {
                        appManager.getInstalledVersion(packageName)
                    } else {
                        null
                    }
                    
                    // Determine new status based on installation state
                    val newStatus = when {
                        !isInstalled -> AppStatus.NOT_INSTALLED
                        installedVersion != null -> {
                            when (compareVersions(installedVersion, appToUpdate.latestVersion)) {
                                0 -> AppStatus.UP_TO_DATE  // Same version
                                1 -> AppStatus.UP_TO_DATE  // Installed version is newer
                                else -> AppStatus.UPDATE_AVAILABLE  // Latest version is newer
                            }
                        }
                        else -> AppStatus.NOT_INSTALLED
                    }
                    
                    // Only update if status actually changed
                    if (newStatus != appToUpdate.status) {
                        val updatedApps = currentState.apps.map { app ->
                            if (app.packageName == packageName) {
                                app.copy(
                                    status = newStatus,
                                    currentVersion = installedVersion,
                                    downloadProgress = 0f
                                )
                            } else {
                                app
                            }
                        }
                        
                        _state.value = currentState.copy(apps = updatedApps)
                        showToast(stringProvider.getString(R.string.app_status_updated))
                    }
                }
            }
        } catch (e: Exception) {
            // If single app update fails, fallback to full refresh
            loadApps(forceRefresh = true)
        }
    }

    /**
     * Handle installation completion
     */
    private fun handleInstallationCompleted(packageName: String, success: Boolean) {
        if (success) {
            updateAppStatus(packageName, AppStatus.UP_TO_DATE)
            showToast(stringProvider.getString(R.string.installation_completed))
            installationRetries.remove(packageName)
            
            // Clear pending install state  
            preferencesManager.removeKey("pending_install_${packageName}")
        } else {
            val currentState = _state.value
            if (currentState is AppState.Success) {
                val app = currentState.apps.find { it.packageName == packageName }
                app?.let {
                    val apkFilePath = getDownloadPath(packageName) ?: preferencesManager.getPendingInstallPath(packageName)
                    if (apkFilePath != null) {
                        handleInstallationFailed(packageName, apkFilePath, stringProvider.getString(R.string.installation_failed_start))
                    } else {
                        updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                        showError(stringProvider.getString(R.string.installation_failed_apk_not_found))
                    }
                }
            }
        }
    }

    /**
     * Handle installation aborted by user - no retry dialog
     */
    private fun handleInstallationAborted(packageName: String, error: String) {
        Log.i(TAG, "🚫 Installation aborted by user: $packageName")
        
        // Clean up without retry
        installationRetries.remove(packageName)
        
        // 🔥 Check actual installation status instead of assuming UPDATE_AVAILABLE
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val app = currentState.apps.find { it.packageName == packageName }
            app?.let {
                val isInstalled = appManager.isAppInstalled(packageName)
                val newStatus = if (isInstalled) {
                    val installedVersion = appManager.getInstalledVersion(packageName)
                    if (installedVersion != null) {
                        when (compareVersions(installedVersion, app.latestVersion)) {
                            0 -> AppStatus.UP_TO_DATE
                            1 -> AppStatus.UP_TO_DATE
                            else -> AppStatus.UPDATE_AVAILABLE
                        }
                    } else {
                        AppStatus.NOT_INSTALLED
                    }
                } else {
                    AppStatus.NOT_INSTALLED
                }
                
                Log.i(TAG, "📦 After user abort, setting status to: $newStatus (installed: $isInstalled)")
                updateAppStatus(packageName, newStatus)
            }
        }
        
        // Show simple error message without retry option
        showToast(stringProvider.getString(R.string.installation_cancelled_by_user))
        
        // Clean up download state and installation queue
        viewModelScope.launch {
            downloadStateRepository.removeDownloadState(packageName)
            Log.i(TAG, "🗑️ Removed download state after user abort: $packageName")
        }
        
        // Remove from installation queue and completed downloads tracking
        installationQueue.removeAll { it.packageName == packageName }
        completedDownloads.remove(packageName)
        Log.i(TAG, "🗑️ Removed from installation queue and completed downloads after user abort: $packageName")
    }

    /**
     * Handle installation failure with retry logic
     */
    private fun handleInstallationFailed(packageName: String, apkFilePath: String, error: String) {
        val currentRetries = installationRetries[packageName] ?: 0
        
        if (currentRetries < 1) { // Allow one retry
            installationRetries[packageName] = currentRetries + 1
            
            // Show retry dialog
            showConfirmationDialog(
                title = stringProvider.getString(R.string.installation_failed_title),
                message = stringProvider.getString(R.string.installation_failed_retry_message, error),
                onConfirm = AppEvent.RetryInstallation(packageName, apkFilePath, shouldUninstallFirst = true),
                onCancel = AppEvent.UpdateAppStatus(packageName, AppStatus.NOT_INSTALLED)
            )
        } else {
            // Max retries reached
            installationRetries.remove(packageName)
            updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
            showError(stringProvider.getString(R.string.download_failed, error))
        }
    }

    /**
     * Retry installation with optional uninstall first
     */
    private fun retryInstallation(packageName: String, apkFilePath: String, shouldUninstallFirst: Boolean) {
        dismissDialog()
        
        if (shouldUninstallFirst) {
            viewModelScope.launch {
                try {
                    // 🔥 Check if package is actually installed before attempting uninstall
                    val isInstalled = appManager.isAppInstalled(packageName)
                    if (!isInstalled) {
                        Log.i(TAG, "📦 Package $packageName is not installed, skipping uninstall and proceeding with installation")
                        showToast(stringProvider.getString(R.string.app_not_installed_proceeding))
                        installApp(packageName, apkFilePath)
                        return@launch
                    }
                    
                    // First uninstall the existing app
                    val uninstallResult = useCases.uninstallAppUseCase(packageName)
                    when (uninstallResult) {
                        is Result.Success -> {
                            if (uninstallResult.data) {
                                showToast(stringProvider.getString(R.string.old_version_uninstalled))
                                delay(2000) // Wait for uninstall to complete
                                installApp(packageName, apkFilePath)
                            } else {
                                showError(stringProvider.getString(R.string.failed_uninstall_old_version))
                                updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                            }
                        }
                        is Result.Error -> {
                            showError(stringProvider.getString(R.string.failed_uninstall_old_version_error, uninstallResult.message))
                            updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                        }
                        is Result.Loading -> {
                            // Should not happen
                        }
                    }
                } catch (e: Exception) {
                    showError(stringProvider.getString(R.string.failed_uninstall_old_version_error, e.message ?: ""))
                    updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                }
            }
        } else {
            installApp(packageName, apkFilePath)
        }
    }

    /**
     * Confirm uninstall before reinstall
     */
    private fun confirmUninstallBeforeReinstall(packageName: String, apkFilePath: String) {
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val app = currentState.apps.find { it.packageName == packageName }
            app?.let {
                showConfirmationDialog(
                    title = stringProvider.getString(R.string.uninstall_required_title),
                    message = stringProvider.getString(R.string.uninstall_required_message, app.title),
                    onConfirm = AppEvent.RetryInstallation(packageName, apkFilePath, shouldUninstallFirst = true),
                    onCancel = AppEvent.UpdateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                )
            }
        }
    }

    /**
     * Uninstall an app
     */
    private fun uninstallApp(packageName: String) {
        dismissDialog()
        
        viewModelScope.launch {
            try {
                updateAppStatus(packageName, AppStatus.UNINSTALLING)
                
                val result = useCases.uninstallAppUseCase(packageName)
                when (result) {
                    is Result.Success -> {
                        if (result.data) {
                            showToast(stringProvider.getString(R.string.uninstallation_started))
                            // Monitor uninstallation
                            delay(3000)
                            updateSingleAppStatus(packageName)
                        } else {
                            showError(stringProvider.getString(R.string.failed_start_uninstallation))
                            updateSingleAppStatus(packageName)
                        }
                    }
                    is Result.Error -> {
                        showError(stringProvider.getString(R.string.uninstallation_failed, result.message))
                        updateSingleAppStatus(packageName)
                    }
                    is Result.Loading -> {
                        // Should not happen in this case
                    }
                }
            } catch (e: Exception) {
                showError(stringProvider.getString(R.string.uninstallation_failed, e.message ?: ""))
                updateSingleAppStatus(packageName)
            }
        }
    }

    /**
     * Show confirmation dialog before reinstalling an app
     */
    private fun showReinstallConfirmation(packageName: String) {
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val app = currentState.apps.find { it.packageName == packageName }
            if (app != null) {
                val title = stringProvider.getString(R.string.reinstall_confirmation_title)
                val message = stringProvider.getString(R.string.reinstall_confirmation_message, app.title)
                val confirmEvent = AppEvent.ReinstallApp(packageName)
                val cancelEvent = AppEvent.DismissDialog
                
                handleEvent(AppEvent.ShowConfirmationDialog(title, message, confirmEvent, cancelEvent))
            }
        }
    }

    /**
     * Reinstall an app (uninstall -> download -> install)
     */
    private fun reinstallApp(packageName: String) {
        dismissDialog()
        
        viewModelScope.launch {
            try {
                val currentState = _state.value
                if (currentState is AppState.Success) {
                    val app = currentState.apps.find { it.packageName == packageName }
                    if (app != null) {
                        // Step 1: Uninstall
                        updateAppStatus(packageName, AppStatus.UNINSTALLING)
                        showToast(stringProvider.getString(R.string.reinstall_started))
                        
                        val uninstallResult = useCases.uninstallAppUseCase(packageName)
                        when (uninstallResult) {
                            is Result.Success -> {
                                if (uninstallResult.data) {
                                    // Wait for uninstallation to complete
                                    delay(3000)
                                    
                                    // Step 2: Download latest version
                                    updateAppStatus(packageName, AppStatus.DOWNLOADING)
                                    updateAppProgress(packageName, 0f)
                                    
                                    // Clear completed downloads tracking
                                    completedDownloads.remove(packageName)
                                    
                                    simpleDownloadManager.downloadApp(packageName, app.downloadUrl)
                                        .catch { error ->
                                            Log.e(TAG, "Reinstall download failed for $packageName", error)
                                            showError(stringProvider.getString(R.string.reinstall_failed, error.message ?: "Download failed"))
                                            updateSingleAppStatus(packageName)
                                        }
                                        .onEach { download ->
                                            // Update progress
                                            updateAppProgress(packageName, download.progress)
                                            
                                            // Download completed, proceed to install
                                             if (download.progress >= 1.0f) {
                                                 // Step 3: Install
                                                 val filePath = download.filePath
                                                 if (filePath != null) {
                                                     updateAppStatus(packageName, AppStatus.INSTALLING)
                                                     val installResult = useCases.installAppUseCase(packageName, filePath)
                                                     when (installResult) {
                                                         is Result.Success -> {
                                                             if (installResult.data) {
                                                                 showToast(stringProvider.getString(R.string.reinstall_completed))
                                                             } else {
                                                                 showError(stringProvider.getString(R.string.reinstall_failed))
                                                                 updateSingleAppStatus(packageName)
                                                             }
                                                         }
                                                         is Result.Error -> {
                                                             showError(stringProvider.getString(R.string.reinstall_failed, installResult.message))
                                                             updateSingleAppStatus(packageName)
                                                         }
                                                         is Result.Loading -> {
                                                             // Should not happen
                                                         }
                                                     }
                                                 } else {
                                                     showError(stringProvider.getString(R.string.reinstall_failed, "File path is null"))
                                                     updateSingleAppStatus(packageName)
                                                 }
                                             }
                                        }.launchIn(this@launch)
                                } else {
                                    showError(stringProvider.getString(R.string.reinstall_failed))
                                    updateSingleAppStatus(packageName)
                                }
                            }
                            is Result.Error -> {
                                showError(stringProvider.getString(R.string.reinstall_failed, uninstallResult.message))
                                updateSingleAppStatus(packageName)
                            }
                            is Result.Loading -> {
                                // Should not happen
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                showError(stringProvider.getString(R.string.reinstall_failed, e.message ?: ""))
                updateSingleAppStatus(packageName)
            }
        }
    }

    /**
     * Open an installed app
     */
    private fun openApp(packageName: String) {
        viewModelScope.launch {
            try {
                val result = useCases.openAppUseCase(packageName)
                when (result) {
                    is Result.Success -> {
                        if (!result.data) {
                            showError(stringProvider.getString(R.string.failed_open_app))
                        }
                    }
                    is Result.Error -> {
                        showError(stringProvider.getString(R.string.failed_open_app_error, result.message))
                    }
                    is Result.Loading -> {
                        // Should not happen in this case
                    }
                }
            } catch (e: Exception) {
                showError(stringProvider.getString(R.string.failed_open_app_error, e.message ?: ""))
            }
        }
    }

    /**
     * Show confirmation dialog
     */
    private fun showConfirmationDialog(title: String, message: String, onConfirm: AppEvent, onCancel: AppEvent?) {
        val currentState = _state.value
        val dialogState = DialogState.Confirmation(
            title = title,
            message = message,
            onConfirmAction = { handleEvent(onConfirm) },
            onCancelAction = onCancel?.let { { handleEvent(it) } }
        )
        
        when (currentState) {
            is AppState.Success -> {
                _state.value = currentState.copy(dialogState = dialogState)
            }
            is AppState.Error -> {
                _state.value = currentState.copy(dialogState = dialogState)
            }
            is AppState.Loading -> {
                // Can't show dialog during loading
            }
        }
    }

    /**
     * Dismiss dialog
     */
    private fun dismissDialog() {
        val currentState = _state.value
        when (currentState) {
            is AppState.Success -> {
                _state.value = currentState.copy(dialogState = null)
            }
            is AppState.Error -> {
                _state.value = currentState.copy(dialogState = null)
            }
            is AppState.Loading -> {
                // No dialog to dismiss
            }
        }
    }

    /**
     * Update app progress
     */
    private fun updateAppProgress(packageName: String, progress: Float) {
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == packageName) {
                    app.copy(downloadProgress = progress)
                } else {
                    app
                }
            }
            _state.value = currentState.copy(apps = updatedApps)
        }
    }

    /**
     * Update app status
     */
    private fun updateAppStatus(packageName: String, status: AppStatus) {
        val currentState = _state.value
        if (currentState is AppState.Success) {
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == packageName) {
                    app.copy(status = status)
                } else {
                    app
                }
            }
            _state.value = currentState.copy(apps = updatedApps)
        }
    }

    /**
     * Show error message
     */
    private fun showError(message: String) {
        Log.w(TAG, "Showing error: $message")
        _toastMessage.value = message
    }

    /**
     * Show toast message
     */
    private fun showToast(message: String) {
        Log.d(TAG, "Showing toast: $message")
        _toastMessage.value = message
    }
    
    /**
     * Clear toast message after showing
     */
    fun clearToast() {
        _toastMessage.value = null
    }

    /**
     * Get download path for a package
     */
    private fun getDownloadPath(packageName: String): String? {
        val downloadDir = File(context.getExternalFilesDir(null), "downloads")
        val apkFile = File(downloadDir, "$packageName.apk")
        return if (apkFile.exists()) apkFile.absolutePath else null
    }

    /**
     * Compare version strings
     * @return 1 if version1 > version2, -1 if version1 < version2, 0 if equal
     */
    private fun compareVersions(version1: String, version2: String): Int {
        if (version1.isEmpty() || version2.isEmpty()) {
            return 0
        }
        
        return try {
            val parts1 = version1.split(".").map { part ->
                part.takeWhile { it.isDigit() }.ifEmpty { "0" }
            }
            val parts2 = version2.split(".").map { part ->
                part.takeWhile { it.isDigit() }.ifEmpty { "0" }
            }
            
            val length = minOf(parts1.size, parts2.size)
            
            for (i in 0 until length) {
                val num1 = parts1[i].toLongOrNull() ?: 0L
                val num2 = parts2[i].toLongOrNull() ?: 0L
                
                when {
                    num1 > num2 -> return 1
                    num1 < num2 -> return -1
                }
            }
            
            parts1.size.compareTo(parts2.size)
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Show configuration dialog with proper config loading
     */
    private fun showConfigDialog() {
        // Always get the latest config from PreferencesManager to ensure accurate values
        val config = try {
            preferencesManager.getAppConfig()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load config, using fallback", e)
            // Fallback to dark theme and English as requested
            AppConfig(ThemeMode.DARK, Language.ENGLISH)
        }
        
        val dialogState = DialogState.Configuration(
            config = config,
            onSave = { newConfig -> handleEvent(AppEvent.SaveConfiguration(newConfig)) },
            onCancel = { handleEvent(AppEvent.DismissDialog) }
        )
        
        val currentState = _state.value
        when (currentState) {
            is AppState.Success -> {
                _state.value = currentState.copy(dialogState = dialogState, config = config)
            }
            is AppState.Error -> {
                _state.value = currentState.copy(dialogState = dialogState, config = config)
            }
            is AppState.Loading -> {
                // For loading state, we can still show the dialog with current config
                _state.value = AppState.Success(
                    apps = emptyList(), 
                    dialogState = dialogState, 
                    config = config
                )
            }
        }
    }
    
    /**
     * Save configuration with immediate language application
     */
    private fun saveConfiguration(config: AppConfig) {
        Log.i(TAG, "🔧 === SAVE CONFIGURATION START ===")
        Log.i(TAG, "🔧 New config - Theme: ${config.themeMode}, Language: ${config.language.displayName} (${config.language.code})")
        
        val previousConfig = when (val currentState = _state.value) {
            is AppState.Success -> {
                Log.d(TAG, "🔧 Previous config from Success state: ${currentState.config}")
                currentState.config
            }
            is AppState.Error -> {
                Log.d(TAG, "🔧 Previous config from Error state: ${currentState.config}")
                currentState.config
            }
            else -> {
                Log.d(TAG, "🔧 No previous config, using default")
                AppConfig()
            }
        }
        
        Log.i(TAG, "🔧 Previous config - Theme: ${previousConfig.themeMode}, Language: ${previousConfig.language.displayName} (${previousConfig.language.code})")
        
        // Save to preferences
        Log.d(TAG, "🔧 Saving config to preferences...")
        preferencesManager.saveAppConfig(config)
        Log.d(TAG, "🔧 Config saved to preferences successfully")
        
        // Update state
        val currentState = _state.value
        Log.d(TAG, "🔧 Current state type: ${currentState::class.simpleName}")
        when (currentState) {
            is AppState.Success -> {
                Log.d(TAG, "🔧 Updating Success state with new config")
                _state.value = currentState.copy(config = config, dialogState = null)
            }
            is AppState.Error -> {
                Log.d(TAG, "🔧 Updating Error state with new config")
                _state.value = currentState.copy(config = config, dialogState = null)
            }
            is AppState.Loading -> {
                Log.w(TAG, "🔧 Can't update config during loading state")
            }
        }
        
        // Check if language changed and apply immediately
        val languageChanged = previousConfig.language != config.language
        Log.i(TAG, "🔧 Language changed: $languageChanged")
        
        if (languageChanged) {
            Log.i(TAG, "🔧 === LANGUAGE CHANGE DETECTED ===")
            Log.i(TAG, "🔧 From: ${previousConfig.language.displayName} (${previousConfig.language.code})")
            Log.i(TAG, "🔧 To: ${config.language.displayName} (${config.language.code})")
            Log.i(TAG, "🔧 Current thread: ${Thread.currentThread().name}")
            Log.i(TAG, "🔧 ================================")
            Log.i(TAG, "🔧 Language changed - applying immediately without toast")
            applyLanguageImmediately(config.language)
        } else {
            Log.d(TAG, "🔧 No language change, just saving config with toast")
            showToast(stringProvider.getString(R.string.configuration_saved))
        }
        
        Log.i(TAG, "🔧 === SAVE CONFIGURATION END ===")
    }
    
    /**
     * Update compact mode setting
     */
    private fun updateCompactMode(enabled: Boolean) {
        Log.i(TAG, "🔧 Updating compact mode to: $enabled")
        
        val currentState = _state.value
        val currentConfig = when (currentState) {
            is AppState.Success -> currentState.config
            is AppState.Error -> currentState.config
            else -> AppConfig()
        }
        
        val newConfig = currentConfig.copy(compactMode = enabled)
        saveConfiguration(newConfig)
    }
    
    /**
     * Apply language immediately using LocaleHelper with activity recreation
     */
    private fun applyLanguageImmediately(newLanguage: Language) {
        Log.i(TAG, "🌍 === APPLY LANGUAGE IMMEDIATELY START ===")
        Log.i(TAG, "🌍 Target language: ${newLanguage.displayName} (${newLanguage.code})")
        Log.i(TAG, "🌍 Current thread: ${Thread.currentThread().name}")
        
        viewModelScope.launch {
            try {
                Log.i(TAG, "🌍 Inside viewModelScope.launch")
                Log.i(TAG, "🌍 Coroutine thread: ${Thread.currentThread().name}")
                
                // Handle language codes with country codes (e.g., es-ES -> es)
                val languageCode = if (newLanguage.code.contains("-")) {
                    val cleanCode = newLanguage.code.split("-")[0]
                    Log.d(TAG, "🌍 Cleaned language code: ${newLanguage.code} -> $cleanCode")
                    cleanCode
                } else {
                    Log.d(TAG, "🌍 Using original language code: ${newLanguage.code}")
                    newLanguage.code
                }
                
                Log.i(TAG, "🌍 Final language code to apply: $languageCode")
                
                // Update state first (before recreation)
                Log.d(TAG, "🌍 Updating state before language application...")
                val currentState = _state.value
                Log.d(TAG, "🌍 Current state type: ${currentState::class.simpleName}")
                
                when (currentState) {
                    is AppState.Success -> {
                        Log.d(TAG, "🌍 Updating Success state with new language")
                        val updatedConfig = currentState.config.copy(language = newLanguage)
                        _state.value = currentState.copy(config = updatedConfig)
                        Log.d(TAG, "🌍 Success state updated")
                    }
                    is AppState.Error -> {
                        Log.d(TAG, "🌍 Updating Error state with new language")
                        val updatedConfig = currentState.config.copy(language = newLanguage)
                        _state.value = currentState.copy(config = updatedConfig)
                        Log.d(TAG, "🌍 Error state updated")
                    }
                    else -> {
                        Log.w(TAG, "🌍 State is Loading, cannot update")
                    }
                }
                
                Log.i(TAG, "🌍 About to call LocaleHelper.applyLocaleToActivity...")
                Log.i(TAG, "🌍 Context type: ${context::class.simpleName}")
                Log.i(TAG, "🌍 Context toString: $context")
                
                // Use LocaleHelper for reliable language switching
                Log.i(TAG, "🌍 About to recreate activity for language change...")
                LocaleHelper.applyLocaleToActivity(context, languageCode)
                
                Log.i(TAG, "🌍 LocaleHelper.applyLocaleToActivity called")
                Log.i(TAG, "🌍 Apply language immediately completed successfully")
                
                // Note: No toast for language change - user will see UI change immediately
                
            } catch (e: Exception) {
                Log.e(TAG, "🌍 💥 Exception in applyLanguageImmediately", e)
                Log.e(TAG, "🌍 💥 Exception message: ${e.message}")
                Log.e(TAG, "🌍 💥 Exception cause: ${e.cause}")
                showToast(stringProvider.getString(R.string.error_applying_language))
            }
        }
        
        Log.i(TAG, "🌍 === APPLY LANGUAGE IMMEDIATELY END ===")
    }
    
    /**
     * Load configuration with robust error handling
     */
    private fun loadConfiguration() {
        val config = try {
            preferencesManager.getAppConfig()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load configuration, using fallback", e)
            // Use fallback configuration as requested: DARK theme and ENGLISH language
            AppConfig(ThemeMode.DARK, Language.ENGLISH)
        }
        
        Log.d(TAG, "Loaded configuration: theme=${config.themeMode}, language=${config.language.displayName}")
        
        val currentState = _state.value
        when (currentState) {
            is AppState.Success -> {
                _state.value = currentState.copy(config = config)
            }
            is AppState.Error -> {
                _state.value = currentState.copy(config = config)
            }
            is AppState.Loading -> {
                // Store config for when loading completes
                // We'll apply it once the state transitions to Success or Error
                Log.d(TAG, "Config loaded during loading state, will apply when state is ready")
            }
        }
    }
    
    // ============= INSTALLATION QUEUE MANAGEMENT =============
    
    /**
     * Add app to installation queue and trigger installation immediately
     * This allows multiple installation dialogs to be shown at once
     */
    private fun queueInstallation(packageName: String, filePath: String, appName: String = packageName) {
        Log.i(TAG, "📋 Adding to installation queue: $appName ($packageName)")
        
        // Check if already in queue
        if (installationQueue.any { it.packageName == packageName }) {
            Log.w(TAG, "⚠️ App already in installation queue: $packageName")
            return
        }
        
        val pendingInstallation = PendingInstallation(packageName, filePath, appName)
        installationQueue.add(pendingInstallation)
        
        Log.i(TAG, "📋 Queue size: ${installationQueue.size}")
        
        // 🔥 NEW: Trigger installation immediately without waiting for previous ones
        Log.i(TAG, "🚀 Triggering installation immediately: $appName")
        triggerInstallationImmediately(pendingInstallation)
    }
    
    /**
     * Trigger installation immediately for the given app
     * This allows multiple installation dialogs to be shown simultaneously
     */
    private fun triggerInstallationImmediately(pendingInstallation: PendingInstallation) {
        Log.i(TAG, "🚀 === TRIGGER INSTALLATION IMMEDIATELY ===")
        Log.i(TAG, "🚀 Installing: ${pendingInstallation.appName} (${pendingInstallation.packageName})")
        
        // Update UI to installing state
        updateAppStatus(pendingInstallation.packageName, AppStatus.INSTALLING)
        
        // Start actual installation immediately
        installAppDirect(pendingInstallation.packageName, pendingInstallation.filePath)
    }
    
    /**
     * Process next installation in queue (kept for compatibility but simplified)
     * This is now mainly used for cleanup and fallback scenarios
     */
    private fun processNextInstallation() {
        Log.i(TAG, "📋 === PROCESS NEXT INSTALLATION (CLEANUP) ===")
        Log.i(TAG, "📋 Queue size: ${installationQueue.size}")
        
        if (installationQueue.isEmpty()) {
            Log.i(TAG, "📋 Installation queue empty")
            return
        }
        
        // Remove completed installations from queue
        val completedPackages = mutableListOf<String>()
        installationQueue.removeAll { installation ->
            val currentState = _state.value
            if (currentState is AppState.Success) {
                val app = currentState.apps.find { it.packageName == installation.packageName }
                val isCompleted = app?.status == AppStatus.UP_TO_DATE || app?.status == AppStatus.NOT_INSTALLED
                if (isCompleted) {
                    completedPackages.add(installation.packageName)
                    Log.i(TAG, "🗑️ Removing completed installation from queue: ${installation.appName}")
                }
                isCompleted
            } else false
        }
        
        Log.i(TAG, "📋 Cleaned up ${completedPackages.size} completed installations")
        Log.i(TAG, "📋 Remaining in queue: ${installationQueue.size}")
    }
    
    /**
     * Clear installation queue (in case of errors or app restart)
     */
    private fun clearInstallationQueue() {
        Log.i(TAG, "🗑️ Clearing installation queue (${installationQueue.size} items)")
        installationQueue.clear()
    }
    
    // ============= CONCURRENT DOWNLOADS HANDLING - AUTO INSTALL =============
    
    /**
     * Auto-install all completed downloads immediately without showing dialog
     */
    private fun autoInstallAllCompleted(
        completedPackages: List<String>,
        completedNames: List<String>,
        completedPaths: List<String>,
        failedPackages: List<String>,
        failedNames: List<String>,
        failedErrors: List<String>
    ) {
        Log.i(TAG, "🚀 Auto-installing all completed downloads")
        Log.i(TAG, "📊 Completed: ${completedPackages.size}, Failed: ${failedPackages.size}")
        
        viewModelScope.launch {
            try {
                // Update UI states for failed downloads first
                failedPackages.forEach { packageName ->
                    updateAppStatus(packageName, AppStatus.UPDATE_AVAILABLE)
                }
                
                // Queue all completed downloads for sequential installation
                completedPackages.zip(completedNames).zip(completedPaths).forEach { (namePackage, path) ->
                    val (packageName, appName) = namePackage
                    Log.i(TAG, "📋 Queueing for installation: $appName ($packageName)")
                    
                    // Add to installation queue
                    queueInstallation(packageName, path, appName)
                }
                
                // Show summary toast
                if (completedPackages.isNotEmpty()) {
                    val message = if (completedPackages.size == 1) {
                        "Installing ${completedNames[0]}..."
                    } else {
                        "Installing ${completedPackages.size} apps..."
                    }
                    showToast(message)
                }
                
                // Show failed downloads toast if any
                if (failedPackages.isNotEmpty()) {
                    showToast("${failedPackages.size} downloads failed")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error auto-installing completed downloads", e)
                showError("Failed to auto-install apps: ${e.message}")
            }
        }
    }
    
}