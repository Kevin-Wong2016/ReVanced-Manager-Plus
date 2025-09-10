package com.revanced.net.revancedmanager.presentation.bloc

import com.revanced.net.revancedmanager.domain.model.AppConfig
import com.revanced.net.revancedmanager.domain.model.RevancedApp

/**
 * Represents the different states of the main app screen
 */
sealed class AppState {
    data object Loading : AppState()
    data class Success(
        val apps: List<RevancedApp>, 
        val dialogState: DialogState? = null,
        val config: AppConfig = AppConfig()
    ) : AppState()
    data class Error(
        val message: String, 
        val dialogState: DialogState? = null,
        val config: AppConfig = AppConfig()
    ) : AppState()
}

/**
 * Represents dialog states for confirmations
 */
sealed class DialogState {
    data class Confirmation(
        val title: String,
        val message: String,
        val onConfirmAction: () -> Unit,
        val onCancelAction: (() -> Unit)? = null
    ) : DialogState()
    
    data class Progress(
        val title: String,
        val message: String,
        val progress: Float? = null
    ) : DialogState()
    
    data class Configuration(
        val config: AppConfig,
        val onSave: (AppConfig) -> Unit,
        val onCancel: () -> Unit
    ) : DialogState()
}

/**
 * Represents individual app states for UI updates
 */
data class AppUiState(
    val app: RevancedApp,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) 