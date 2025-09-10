package com.revanced.net.revancedmanager.data.mapper

import android.util.Log
import com.revanced.net.revancedmanager.data.remote.dto.AppPackageDto
import com.revanced.net.revancedmanager.data.remote.dto.AppResponseDto
import com.revanced.net.revancedmanager.domain.model.AppStatus
import com.revanced.net.revancedmanager.domain.model.RevancedApp
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mapper class for converting DTOs to domain models
 * Robust design to handle API changes gracefully
 */
@Singleton
class AppMapper @Inject constructor() {

    companion object {
        private const val TAG = "AppMapper"
    }

    /**
     * Convert AppPackageDto to RevancedApp domain model with robust error handling
     */
    fun toDomainModel(packageDto: AppPackageDto, installedVersion: String? = null): RevancedApp {
        return try {
            RevancedApp(
                packageName = packageDto.androidPackageName.takeIf { it.isNotBlank() } 
                    ?: run {
                        Log.w(TAG, "Empty package name found, using fallback")
                        "unknown.package.${System.currentTimeMillis()}"
                    },
                title = packageDto.appName.takeIf { it.isNotBlank() } 
                    ?: packageDto.androidPackageName.split(".").lastOrNull() 
                    ?: "Unknown App",
                latestVersion = packageDto.latestVersionCode.takeIf { it.isNotBlank() } 
                    ?: "1.0.0",
                currentVersion = installedVersion,
                description = packageDto.appShortDescription.takeIf { it.isNotBlank() }
                    ?: "No description available",
                iconUrl = packageDto.icon.takeIf { it.isNotBlank() && isValidUrl(it) }
                    ?: getDefaultIconUrl(packageDto.androidPackageName),
                downloadUrl = packageDto.latestVersionUrl.takeIf { it.isNotBlank() && isValidUrl(it) }
                    ?: run {
                        Log.w(TAG, "Invalid download URL for ${packageDto.androidPackageName}: ${packageDto.latestVersionUrl}")
                        ""
                    },
                requiresMicroG = packageDto.requireMicroG,
                index = packageDto.index.coerceAtLeast(0),
                status = AppStatus.UNKNOWN // Will be determined by repository
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error mapping package DTO to domain model", e)
            // Fallback to minimal valid app object
            createFallbackApp(packageDto, installedVersion)
        }
    }

    /**
     * Convert AppResponseDto to list of RevancedApp with robust error handling
     */
    fun toDomainModel(responseDto: AppResponseDto): List<RevancedApp> {
        return try {
            if (responseDto.packages.isEmpty()) {
                Log.w(TAG, "Empty packages list in response")
                return emptyList()
            }

            val validApps = mutableListOf<RevancedApp>()
            responseDto.packages.forEachIndexed { index, packageDto ->
                try {
                    val app = toDomainModel(packageDto)
                    // Validate essential fields
                    if (isValidApp(app)) {
                        validApps.add(app)
                    } else {
                        Log.w(TAG, "Skipping invalid app at index $index: ${packageDto.androidPackageName}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error mapping app at index $index", e)
                    // Continue processing other apps
                }
            }

            Log.i(TAG, "Successfully mapped ${validApps.size} out of ${responseDto.packages.size} apps")
            validApps
        } catch (e: Exception) {
            Log.e(TAG, "Error mapping response DTO to domain models", e)
            emptyList()
        }
    }

    /**
     * Create a fallback app when normal mapping fails
     */
    private fun createFallbackApp(packageDto: AppPackageDto, installedVersion: String?): RevancedApp {
        Log.w(TAG, "Creating fallback app for ${packageDto.androidPackageName}")
        return RevancedApp(
            packageName = packageDto.androidPackageName.takeIf { it.isNotBlank() } 
                ?: "fallback.${System.currentTimeMillis()}",
            title = "Unknown App",
            latestVersion = "1.0.0",
            currentVersion = installedVersion,
            description = "Failed to load app information",
            iconUrl = "",
            downloadUrl = "",
            requiresMicroG = false,
            index = Int.MAX_VALUE, // Put fallback apps at the end
            status = AppStatus.UNKNOWN
        )
    }

    /**
     * Validate if the mapped app has essential information
     */
    private fun isValidApp(app: RevancedApp): Boolean {
        return app.packageName.isNotBlank() && 
               app.title.isNotBlank() && 
               app.latestVersion.isNotBlank()
    }

    /**
     * Basic URL validation
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            url.startsWith("http://") || url.startsWith("https://")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generate default icon URL based on package name
     */
    private fun getDefaultIconUrl(packageName: String): String {
        return try {
            // Try to generate a fallback icon URL
            "https://via.placeholder.com/128x128/4CAF50/FFFFFF?text=${packageName.split(".").lastOrNull()?.take(2)?.uppercase() ?: "AP"}"
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * Extension function to convert AppPackageDto to RevancedApp domain model
 * Kept for backward compatibility with robust error handling
 */
fun AppPackageDto.toDomainModel(installedVersion: String? = null): RevancedApp {
    return try {
        RevancedApp(
            packageName = androidPackageName.takeIf { it.isNotBlank() } 
                ?: "unknown.${System.currentTimeMillis()}",
            title = appName.takeIf { it.isNotBlank() } 
                ?: androidPackageName.split(".").lastOrNull() 
                ?: "Unknown App",
            latestVersion = latestVersionCode.takeIf { it.isNotBlank() } ?: "1.0.0",
            currentVersion = installedVersion,
            description = appShortDescription.takeIf { it.isNotBlank() } 
                ?: "No description available",
            iconUrl = icon.takeIf { it.isNotBlank() } ?: "",
            downloadUrl = latestVersionUrl.takeIf { it.isNotBlank() } ?: "",
            requiresMicroG = requireMicroG,
            index = index.coerceAtLeast(0),
            status = AppStatus.UNKNOWN
        )
    } catch (e: Exception) {
        Log.e("AppMapperExtension", "Error in extension mapping", e)
        RevancedApp(
            packageName = androidPackageName.takeIf { it.isNotBlank() } ?: "fallback.${System.currentTimeMillis()}",
            title = "Unknown App",
            latestVersion = "1.0.0",
            currentVersion = installedVersion,
            description = "Failed to load app information",
            iconUrl = "",
            downloadUrl = "",
            requiresMicroG = false,
            index = Int.MAX_VALUE,
            status = AppStatus.UNKNOWN
        )
    }
}

/**
 * Extension function to convert AppResponseDto to list of RevancedApp
 * Kept for backward compatibility with robust error handling
 */
fun AppResponseDto.toDomainModel(): List<RevancedApp> {
    return try {
        packages.mapNotNull { packageDto ->
            try {
                packageDto.toDomainModel()
            } catch (e: Exception) {
                Log.e("AppMapperExtension", "Error mapping package ${packageDto.androidPackageName}", e)
                null
            }
        }
    } catch (e: Exception) {
        Log.e("AppMapperExtension", "Error mapping response", e)
        emptyList()
    }
} 