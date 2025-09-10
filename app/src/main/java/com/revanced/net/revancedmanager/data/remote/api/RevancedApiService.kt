package com.revanced.net.revancedmanager.data.remote.api

import com.revanced.net.revancedmanager.data.remote.dto.AppResponseDto
import retrofit2.http.GET

/**
 * Retrofit API service for ReVanced endpoints
 */
interface RevancedApiService {
    
    @GET("revanced-apps-arm64-v8a.json")
    suspend fun getAppsArm64(): AppResponseDto
    
    @GET("revanced-apps-armeabi-v7a.json")
    suspend fun getAppsArmV7a(): AppResponseDto
    
    @GET("revanced-apps-x86.json")
    suspend fun getAppsX86(): AppResponseDto
    
    @GET("revanced-apps-x86_64.json")
    suspend fun getAppsX86_64(): AppResponseDto
    
    @GET("revanced-apps.json")
    suspend fun getAppsFallback(): AppResponseDto
} 