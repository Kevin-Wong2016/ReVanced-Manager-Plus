package com.revanced.net.revancedmanager.data.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.app.PendingIntent
import android.util.Log
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enhanced PackageInstaller that provides immediate feedback on installation results
 * Based on proven AppInstaller implementation but integrated with modern architecture
 */
@Singleton
class RevancedPackageInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "RevancedPackageInstaller"
    }
    
    private val installReceiver = InstallReceiver()
    private val ACTION_INSTALL_RESULT = "${context.packageName}.INSTALL_RESULT"
    
    // Events for installation results
    private val _installationResults = MutableSharedFlow<InstallationResult>()
    val installationResults: SharedFlow<InstallationResult> = _installationResults
    
    init {
        registerReceiver()
    }

    private fun registerReceiver() {
        val filter = IntentFilter(ACTION_INSTALL_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(installReceiver, filter)
        }
        Log.i(TAG, "InstallReceiver registered")
    }

    /**
     * Installs an APK file using PackageInstaller API
     * This provides immediate and accurate feedback on installation results
     *
     * @param packageName Package name for tracking and event handling
     * @param apkPath Full path to the APK file to be installed
     * @return True if installation process started successfully, false otherwise
     */
    fun installApp(packageName: String, apkPath: String): Boolean {
        Log.i(TAG, "Starting installation for APK: $apkPath, package: $packageName")
        
        return try {
            val packageInstaller = context.packageManager.packageInstaller

            // Create installation session parameters
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            
            // Create and open installation session
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            // Write APK file to session
            session.use { activeSession ->
                FileInputStream(File(apkPath)).use { inputStream ->
                    // Open write stream with a generic name since package name isn't needed
                    activeSession.openWrite("package", 0, -1).use { outputStream ->
                        // Copy APK data to installation session
                        inputStream.copyTo(outputStream)
                        // Ensure all data is written
                        activeSession.fsync(outputStream)
                    }
                }

                // Create intent for installation result
                val intent = Intent(ACTION_INSTALL_RESULT).apply {
                    setPackage(context.packageName)
                    // Keep package name in extra for tracking purposes
                    putExtra("PACKAGE_NAME", packageName)
                }

                // Create appropriate PendingIntent based on Android version
                val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                } else {
                    PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                    )
                }

                // Start the installation
                activeSession.commit(pendingIntent.intentSender)
                Log.i(TAG, "Installation session committed for: $packageName")
            }
            
            true
        } catch (e: IOException) {
            Log.e(TAG, "Installation failed for $packageName", e)
            
            // Emit failure event immediately
            CoroutineScope(Dispatchers.IO).launch {
                _installationResults.emit(
                    InstallationResult.Failed(
                        packageName = packageName,
                        error = "Installation failed: ${e.message}"
                    )
                )
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during installation for $packageName", e)
            
            // Emit failure event immediately
            CoroutineScope(Dispatchers.IO).launch {
                _installationResults.emit(
                    InstallationResult.Failed(
                        packageName = packageName,
                        error = "Unexpected error: ${e.message}"
                    )
                )
            }
            
            false
        }
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(installReceiver)
            Log.i(TAG, "InstallReceiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
    }

    inner class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.getStringExtra("PACKAGE_NAME") ?: "Unknown package"
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown reason"

            Log.i(TAG, "Installation result received for $packageName: status=$status, message=$message")

            CoroutineScope(Dispatchers.IO).launch {
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        Log.i(TAG, "User action required for: $packageName")
                        
                        // Start the user confirmation activity
                        val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        
                        confirmIntent?.let { 
                            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) 
                        }

                        _installationResults.emit(
                            InstallationResult.PendingUserAction(
                                packageName = packageName,
                                message = message
                            )
                        )
                    }
                    
                    PackageInstaller.STATUS_SUCCESS -> {
                        Log.i(TAG, "Installation successful for: $packageName")
                        
                        _installationResults.emit(
                            InstallationResult.Success(
                                packageName = packageName,
                                message = message
                            )
                        )
                    }
                    
                    else -> {
                        Log.w(TAG, "Installation failed for $packageName: $message (status: $status)")
                        
                        // Determine specific error type
                        val errorType = when (status) {
                            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was aborted"
                            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation was blocked"
                            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Package conflict detected"
                            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Package is incompatible"
                            PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid package"
                            PackageInstaller.STATUS_FAILURE_STORAGE -> "Insufficient storage"
                            else -> message
                        }
                        
                        _installationResults.emit(
                            InstallationResult.Failed(
                                packageName = packageName,
                                error = errorType,
                                statusCode = status
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sealed class representing installation results from PackageInstaller
 */
sealed class InstallationResult {
    data class Success(
        val packageName: String,
        val message: String
    ) : InstallationResult()
    
    data class Failed(
        val packageName: String,
        val error: String,
        val statusCode: Int = -1
    ) : InstallationResult()
    
    data class PendingUserAction(
        val packageName: String,
        val message: String
    ) : InstallationResult()
} 