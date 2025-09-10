package com.revanced.net.revancedmanager.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data transfer object for individual app packages from API
 * Contains only essential fields currently in use
 */
@Serializable
data class AppPackageDto(
    @SerialName("appName")
    val appName: String = "",
    
    @SerialName("androidPackageName")
    val androidPackageName: String = "",
    
    @SerialName("latestVersionCode")
    val latestVersionCode: String = "",
    
    @SerialName("appShortDescription")
    val appShortDescription: String = "",
    
    @SerialName("requireMicroG")
    val requireMicroG: Boolean = false,
    
    @SerialName("latestVersionUrl")
    val latestVersionUrl: String = "",
    
    @SerialName("icon")
    val icon: String = "",
    
    @SerialName("index")
    val index: Int = 0
)

/**
 * Data transfer object for the complete API response
 * Contains only essential fields currently in use
 */
@Serializable
data class AppResponseDto(
    @SerialName("packages")
    val packages: List<AppPackageDto> = emptyList(),
    
    @SerialName("sponsor")
    val sponsor: String? = null
) 