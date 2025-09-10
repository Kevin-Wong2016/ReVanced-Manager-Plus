package com.revanced.net.revancedmanager.data.manager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for handling app installation, uninstallation and package operations
 * Replaces the old AppInstaller, AppUninstaller, and PackageUtils
 */
@Singleton
class AppManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packageInstaller: RevancedPackageInstaller
) {
    
    private val packageManager: PackageManager = context.packageManager
    
    /**
     * Get the installed version of an app
     * @param packageName Package name of the app
     * @return Installed version string or null if not installed
     */
    fun getInstalledVersion(packageName: String): String? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if an app is installed
     * @param packageName Package name of the app
     * @return True if installed, false otherwise
     */
    fun isAppInstalled(packageName: String): Boolean {
        return getInstalledVersion(packageName) != null
    }
    
    /**
     * Install an APK file using PackageInstaller API
     * @param packageName Package name of the app to install
     * @param apkFilePath Path to the APK file
     * @return True if installation process started successfully
     */
    fun installApp(packageName: String, apkFilePath: String): Boolean {
        return try {
            val apkFile = File(apkFilePath)
            if (!apkFile.exists()) {
                return false
            }
            
            packageInstaller.installApp(packageName, apkFilePath)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Uninstall an app
     * @param packageName Package name of the app
     * @return True if uninstallation intent was launched successfully
     */
    fun uninstallApp(packageName: String): Boolean {
        return try {
            // 🔥 Check if package is actually installed before attempting uninstall
            if (!isAppInstalled(packageName)) {
                android.util.Log.w("AppManager", "Package $packageName is not installed, skipping uninstall")
                return false
            }
            
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Open an installed app
     * @param packageName Package name of the app
     * @return True if app was opened successfully
     */
    fun openApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Get list of supported CPU architectures
     */
    fun getSupportedAbis(): Array<String> {
        return try {
            Build.SUPPORTED_ABIS
        } catch (e: Exception) {
            emptyArray()
        }
    }
} 