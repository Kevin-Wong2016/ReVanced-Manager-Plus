package com.revanced.net.revancedmanager.data.repository

import android.util.Log
import com.revanced.net.revancedmanager.core.common.Result
import com.revanced.net.revancedmanager.data.local.preferences.PreferencesManager
import com.revanced.net.revancedmanager.data.manager.AppManager
import com.revanced.net.revancedmanager.data.manager.SimpleDownloadManager
import com.revanced.net.revancedmanager.data.mapper.AppMapper
import com.revanced.net.revancedmanager.data.remote.api.RevancedApiService
import com.revanced.net.revancedmanager.data.remote.dto.AppResponseDto
import com.revanced.net.revancedmanager.domain.model.AppDownload
import com.revanced.net.revancedmanager.domain.model.AppStatus
import com.revanced.net.revancedmanager.domain.model.RevancedApp
import com.revanced.net.revancedmanager.domain.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of AppRepository interface
 * Updated to use ModernDownloadManager for background downloads
 */
@Singleton
class AppRepositoryImpl @Inject constructor(
    private val apiService: RevancedApiService,
    private val appManager: AppManager,
    private val downloadManager: SimpleDownloadManager, // Updated to use simple manager
    private val preferencesManager: PreferencesManager,
    private val appMapper: AppMapper,
    private val json: Json
) : AppRepository {

    companion object {
        private const val TAG = "AppRepositoryImpl"
    }

    override fun getApps(forceRefresh: Boolean): Flow<Result<List<RevancedApp>>> = flow {
        try {
            emit(Result.Loading)
            Log.i(TAG, "Getting apps, forceRefresh: $forceRefresh")
            
            val apps = if (forceRefresh) {
                Log.d(TAG, "Force refresh - getting apps from network")
                getAppsFromNetwork()
            } else {
                // Try cache first, fallback to network
                val cachedJson = preferencesManager.getCachedAppList()
                if (cachedJson.isNotEmpty()) {
                    try {
                        Log.d(TAG, "Found cached data, converting to domain models")
                        val response = json.decodeFromString<AppResponseDto>(cachedJson)
                        val cachedApps = appMapper.toDomainModel(response)
                        Log.d(TAG, "Using cached apps: ${cachedApps.size} items")
                        cachedApps
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse cached data, falling back to network", e)
                        getAppsFromNetwork()
                    }
                } else {
                    Log.d(TAG, "Cache empty - getting apps from network")
                    getAppsFromNetwork()
                }
            }
            
            // Add installed version information
            val appsWithVersions = apps.map { app ->
                val installedVersion = appManager.getInstalledVersion(app.packageName)
                app.copy(currentVersion = installedVersion)
            }
            
            Log.i(TAG, "Successfully loaded ${appsWithVersions.size} apps")
            emit(Result.Success(appsWithVersions))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get apps", e)
            emit(Result.Error(e))
        }
    }.flowOn(Dispatchers.IO)
    
    override suspend fun getInstalledVersion(packageName: String): String? {
        return withContext(Dispatchers.IO) {
            appManager.getInstalledVersion(packageName)
        }
    }
    
    override fun downloadApp(packageName: String, downloadUrl: String): Flow<AppDownload> {
        Log.i(TAG, "Starting download for: $packageName")
        Log.i(TAG, "Download URL: $downloadUrl")
        
        // Storage space check is handled in the DownloadService
        
        // Get app name for better notification display
        val appName = try {
            // Try to get app name from cached JSON
            val cachedJson = preferencesManager.getCachedAppList()
            if (cachedJson.isNotEmpty()) {
                val response = json.decodeFromString<AppResponseDto>(cachedJson)
                response.packages.find { it.androidPackageName == packageName }?.appName ?: packageName
            } else {
                packageName
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get app name, using package name", e)
            packageName
        }
        
        Log.i(TAG, "Using app name for notifications: $appName")
        
        // Start download using simple download manager
        return downloadManager.downloadApp(packageName, downloadUrl, appName)
    }
    
    override suspend fun installApp(packageName: String, apkFilePath: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Installing app: $packageName from $apkFilePath")
                val success = appManager.installApp(packageName, apkFilePath)
                Log.i(TAG, "Installation result for $packageName: $success")
                Result.Success(success)
            } catch (e: Exception) {
                Log.e(TAG, "Installation failed for $packageName", e)
                Result.Error(e)
            }
        }
    }
    
    override suspend fun uninstallApp(packageName: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Uninstalling app: $packageName")
                val success = appManager.uninstallApp(packageName)
                Log.i(TAG, "Uninstallation result for $packageName: $success")
                Result.Success(success)
            } catch (e: Exception) {
                Log.e(TAG, "Uninstallation failed for $packageName", e)
                Result.Error(e)
            }
        }
    }
    
    override suspend fun openApp(packageName: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Opening app: $packageName")
                val success = appManager.openApp(packageName)
                Log.i(TAG, "Open app result for $packageName: $success")
                Result.Success(success)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open app: $packageName", e)
                Result.Error(e)
            }
        }
    }
    
    override suspend fun isAppInstalled(packageName: String): Boolean {
        return withContext(Dispatchers.IO) {
            val installed = appManager.isAppInstalled(packageName)
            Log.d(TAG, "App installed check for $packageName: $installed")
            installed
        }
    }
    
    /**
     * Get apps from network based on device architecture
     */
    private suspend fun getAppsFromNetwork(): List<RevancedApp> {
        Log.d(TAG, "Fetching apps from network")
        val supportedAbis = appManager.getSupportedAbis()
        Log.d(TAG, "Device supported ABIs: $supportedAbis")
        
        val response = when {
            supportedAbis.contains("arm64-v8a") -> {
                Log.d(TAG, "Using ARM64 endpoint")
                apiService.getAppsArm64()
            }
            supportedAbis.contains("armeabi-v7a") -> {
                Log.d(TAG, "Using ARMv7 endpoint")
                apiService.getAppsArmV7a()
            }
            supportedAbis.contains("x86") -> {
                Log.d(TAG, "Using x86 endpoint")
                apiService.getAppsX86()
            }
            supportedAbis.contains("x86_64") -> {
                Log.d(TAG, "Using x86_64 endpoint")
                apiService.getAppsX86_64()
            }
            else -> {
                Log.d(TAG, "Using fallback endpoint")
                apiService.getAppsFallback()
            }
        }
        
        val apps = appMapper.toDomainModel(response)
        Log.i(TAG, "Fetched ${apps.size} apps from network")
        
        // Cache the response
        try {
            val jsonString = json.encodeToString<AppResponseDto>(response)
            preferencesManager.saveCachedAppList(jsonString)
            Log.d(TAG, "Apps cached successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache apps", e)
        }
        
        return apps
    }
    


    /**
     * Get apps from cache immediately for fast UI loading
     */
    override fun getAppsFromCacheImmediately(): Flow<Result<List<RevancedApp>>> = flow {
        try {
            Log.i(TAG, "Getting apps from cache immediately")
            val cachedJson = preferencesManager.getCachedAppList()
            
            if (cachedJson.isNotEmpty()) {
                try {
                    Log.d(TAG, "Found cached apps data, parsing JSON...")
                    val response = json.decodeFromString<AppResponseDto>(cachedJson)
                    
                    // Convert DTO to domain model and calculate status real-time
                    val appsWithVersionsAndStatus = response.packages.mapNotNull { packageDto ->
                        try {
                            val installedVersion = appManager.getInstalledVersion(packageDto.androidPackageName)
                            
                            // Validate essential fields before creating RevancedApp
                            if (packageDto.androidPackageName.isBlank() || packageDto.appName.isBlank()) {
                                Log.w(TAG, "Skipping invalid package: empty name or package name")
                                return@mapNotNull null
                            }
                            
                            // Create RevancedApp with calculated status
                            RevancedApp(
                                packageName = packageDto.androidPackageName,
                                title = packageDto.appName,
                                latestVersion = packageDto.latestVersionCode.takeIf { it.isNotBlank() } ?: "1.0.0",
                                currentVersion = installedVersion,
                                description = packageDto.appShortDescription.takeIf { it.isNotBlank() } ?: "No description available",
                                iconUrl = packageDto.icon,
                                downloadUrl = packageDto.latestVersionUrl,
                                requiresMicroG = packageDto.requireMicroG,
                                index = packageDto.index.coerceAtLeast(0),
                                status = when {
                                    installedVersion == null -> AppStatus.NOT_INSTALLED
                                    compareVersions(installedVersion, packageDto.latestVersionCode) >= 0 -> AppStatus.UP_TO_DATE
                                    else -> AppStatus.UPDATE_AVAILABLE
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing package ${packageDto.androidPackageName}", e)
                            null
                        }
                    }
                    
                    Log.i(TAG, "Loaded ${appsWithVersionsAndStatus.size} apps from cache immediately with real-time status")
                    emit(Result.Success(appsWithVersionsAndStatus))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse cached JSON, treating as no cache", e)
                    emit(Result.Loading)
                }
            } else {
                Log.d(TAG, "No cache available, will need to load from network")
                emit(Result.Loading)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cached apps", e)
            emit(Result.Loading)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Background refresh with fallback to cache if network fails
     */
    override fun backgroundRefreshApps(): Flow<Result<List<RevancedApp>>> = flow {
        try {
            Log.i(TAG, "Background refresh starting")
            val freshApps = getAppsFromNetwork() // Get fresh DTO and convert to domain
            
            // Add installed version information and determine status
            val appsWithVersionsAndStatus = freshApps.map { app ->
                val installedVersion = appManager.getInstalledVersion(app.packageName)
                app.copy(
                    currentVersion = installedVersion,
                    status = when {
                        installedVersion == null -> AppStatus.NOT_INSTALLED
                        compareVersions(installedVersion, app.latestVersion) >= 0 -> AppStatus.UP_TO_DATE
                        else -> AppStatus.UPDATE_AVAILABLE
                    }
                )
            }
            
            Log.i(TAG, "Background refresh completed: ${appsWithVersionsAndStatus.size} apps with real-time status")
            emit(Result.Success(appsWithVersionsAndStatus))
            
        } catch (e: Exception) {
            Log.w(TAG, "Background refresh failed, falling back to cache", e)
            // Fallback to cache with real-time status calculation
            val cachedJson = preferencesManager.getCachedAppList()
            if (cachedJson.isNotEmpty()) {
                try {
                    Log.d(TAG, "Attempting fallback to cached data...")
                    val response = json.decodeFromString<AppResponseDto>(cachedJson)
                    val appsFromCache = response.packages.mapNotNull { packageDto ->
                        try {
                            val installedVersion = appManager.getInstalledVersion(packageDto.androidPackageName)
                            
                            // Validate essential fields
                            if (packageDto.androidPackageName.isBlank() || packageDto.appName.isBlank()) {
                                Log.w(TAG, "Skipping invalid fallback package: empty name or package name")
                                return@mapNotNull null
                            }
                            
                            RevancedApp(
                                packageName = packageDto.androidPackageName,
                                title = packageDto.appName,
                                latestVersion = packageDto.latestVersionCode.takeIf { it.isNotBlank() } ?: "1.0.0",
                                currentVersion = installedVersion,
                                description = packageDto.appShortDescription.takeIf { it.isNotBlank() } ?: "No description available",
                                iconUrl = packageDto.icon,
                                downloadUrl = packageDto.latestVersionUrl,
                                requiresMicroG = packageDto.requireMicroG,
                                index = packageDto.index.coerceAtLeast(0),
                                status = when {
                                    installedVersion == null -> AppStatus.NOT_INSTALLED
                                    compareVersions(installedVersion, packageDto.latestVersionCode) >= 0 -> AppStatus.UP_TO_DATE
                                    else -> AppStatus.UPDATE_AVAILABLE
                                }
                            )
                        } catch (packageException: Exception) {
                            Log.e(TAG, "Error processing fallback package ${packageDto.androidPackageName}", packageException)
                            null
                        }
                    }
                    Log.i(TAG, "Using cached apps as fallback with real-time status: ${appsFromCache.size} items")
                    emit(Result.Success(appsFromCache))
                } catch (cacheException: Exception) {
                    Log.e(TAG, "Failed to parse cached data during fallback", cacheException)
                    emit(Result.Error(e))
                }
            } else {
                Log.e(TAG, "No cache available for fallback")
                emit(Result.Error(e))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Compare two app lists and return apps that have been updated
     */
    override fun getUpdatedApps(oldApps: List<RevancedApp>, newApps: List<RevancedApp>): List<RevancedApp> {
        val updatedApps = mutableListOf<RevancedApp>()
        
        newApps.forEach { newApp ->
            val oldApp = oldApps.find { it.packageName == newApp.packageName }
            
            // Check if this is a new app or has updates
            val hasChanges = oldApp == null || 
                             oldApp.latestVersion != newApp.latestVersion ||
                             oldApp.downloadUrl != newApp.downloadUrl ||
                             oldApp.title != newApp.title ||
                             oldApp.description != newApp.description ||
                             oldApp.currentVersion != newApp.currentVersion
            
            if (hasChanges) {
                updatedApps.add(newApp)
                Log.d(TAG, "App updated: ${newApp.packageName} - ${if (oldApp == null) "NEW" else "CHANGED"}")
            }
        }
        
        Log.i(TAG, "Found ${updatedApps.size} updated apps out of ${newApps.size} total")
        return updatedApps
    }
    
    /**
     * Compare two version strings
     * @param installedVersion Currently installed version
     * @param latestVersion Latest available version  
     * @return Positive if installedVersion > latestVersion, negative if less, zero if equal
     */
    private fun compareVersions(installedVersion: String, latestVersion: String): Int {
        if (installedVersion.isEmpty() || latestVersion.isEmpty()) {
            return 0
        }
        
        return try {
            val installedParts = installedVersion.split(".").map { part ->
                part.takeWhile { it.isDigit() }.ifEmpty { "0" }
            }
            val latestParts = latestVersion.split(".").map { part ->
                part.takeWhile { it.isDigit() }.ifEmpty { "0" }
            }
            
            val length = minOf(installedParts.size, latestParts.size)
            
            for (i in 0 until length) {
                val installedNum = installedParts[i].toLongOrNull() ?: 0L
                val latestNum = latestParts[i].toLongOrNull() ?: 0L
                
                when {
                    installedNum > latestNum -> return 1
                    installedNum < latestNum -> return -1
                }
            }
            
            installedParts.size.compareTo(latestParts.size)
        } catch (e: Exception) {
            0
        }
    }
} 