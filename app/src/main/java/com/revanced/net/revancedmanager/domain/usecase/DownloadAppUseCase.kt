package com.revanced.net.revancedmanager.domain.usecase

import com.revanced.net.revancedmanager.domain.model.AppDownload
import com.revanced.net.revancedmanager.domain.repository.AppRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case for downloading ReVanced apps
 */
class DownloadAppUseCase @Inject constructor(
    private val appRepository: AppRepository
) {
    
    /**
     * Download an app APK
     * @param packageName Package name of the app
     * @param downloadUrl URL to download the APK
     * @return Flow of download progress
     */
    operator fun invoke(packageName: String, downloadUrl: String): Flow<AppDownload> {
        return appRepository.downloadApp(packageName, downloadUrl)
    }
} 