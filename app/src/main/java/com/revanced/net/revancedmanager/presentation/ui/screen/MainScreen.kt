package com.revanced.net.revancedmanager.presentation.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.revanced.net.revancedmanager.R
import com.revanced.net.revancedmanager.presentation.bloc.AppBloc
import com.revanced.net.revancedmanager.presentation.bloc.AppEvent
import com.revanced.net.revancedmanager.presentation.bloc.AppState
import com.revanced.net.revancedmanager.presentation.bloc.DialogState
import com.revanced.net.revancedmanager.presentation.ui.components.AppCard
import com.revanced.net.revancedmanager.presentation.ui.components.ConfigDialog

/**
 * Main screen of the ReVanced Manager app
 * Updated with improved dialog handling and better UX
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: AppBloc = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val context = LocalContext.current

    // Handle toast messages
    LaunchedEffect(toastMessage) {
        toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        floatingActionButton = {
            Row {
                FloatingActionButton(
                    onClick = {
                        viewModel.handleEvent(AppEvent.ShowConfigDialog)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings"
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                FloatingActionButton(
                    onClick = {
                        viewModel.handleEvent(AppEvent.RefreshApps)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh apps"
                    )
                }
            }
        }
    ) { paddingValues ->
        when (val currentState = state) {
            is AppState.Loading -> {
                LoadingScreen(modifier = Modifier.padding(paddingValues))
            }
            is AppState.Success -> {
                AppListScreen(
                    apps = currentState.apps,
                    onEvent = viewModel::handleEvent,
                    modifier = Modifier.padding(paddingValues)
                )
                
                // Handle dialogs
                currentState.dialogState?.let { dialogState ->
                    when (dialogState) {
                        is DialogState.Confirmation -> {
                            ConfirmationDialog(
                                title = dialogState.title,
                                message = dialogState.message,
                                onConfirm = {
                                    dialogState.onConfirmAction()
                                },
                                onCancel = {
                                    dialogState.onCancelAction?.invoke() ?: viewModel.handleEvent(AppEvent.DismissDialog)
                                }
                            )
                        }
                        is DialogState.Progress -> {
                            ProgressDialog(
                                title = dialogState.title,
                                message = dialogState.message,
                                progress = dialogState.progress
                            )
                        }
                        is DialogState.Configuration -> {
                            ConfigDialog(
                                config = dialogState.config,
                                onSave = dialogState.onSave,
                                onCancel = dialogState.onCancel
                            )
                        }
                    }
                }
            }
            is AppState.Error -> {
                ErrorScreen(
                    message = currentState.message,
                    onRetry = { viewModel.handleEvent(AppEvent.RefreshApps) },
                    modifier = Modifier.padding(paddingValues)
                )
                
                // Handle dialogs in error state too
                currentState.dialogState?.let { dialogState ->
                    when (dialogState) {
                        is DialogState.Confirmation -> {
                            ConfirmationDialog(
                                title = dialogState.title,
                                message = dialogState.message,
                                onConfirm = {
                                    dialogState.onConfirmAction()
                                },
                                onCancel = {
                                    dialogState.onCancelAction?.invoke() ?: viewModel.handleEvent(AppEvent.DismissDialog)
                                }
                            )
                        }
                        is DialogState.Progress -> {
                            ProgressDialog(
                                title = dialogState.title,
                                message = dialogState.message,
                                progress = dialogState.progress
                            )
                        }
                        is DialogState.Configuration -> {
                            ConfigDialog(
                                config = dialogState.config,
                                onSave = dialogState.onSave,
                                onCancel = dialogState.onCancel
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Confirmation dialog component
 */
@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Progress dialog component
 */
@Composable
private fun ProgressDialog(
    title: String,
    message: String,
    progress: Float?
) {
    AlertDialog(
        onDismissRequest = { /* Not dismissible */ },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress }
                    )
                } else {
                    CircularProgressIndicator()
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = { /* No button for progress dialog */ }
    )
}

/**
 * Loading screen component
 */
@Composable
private fun LoadingScreen(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.loading_apps_message),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/**
 * Error screen component
 */
@Composable
private fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.error_prefix, message),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

/**
 * App list screen component
 */
@Composable
private fun AppListScreen(
    apps: List<com.revanced.net.revancedmanager.domain.model.RevancedApp>,
    onEvent: (AppEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize()
    ) {
        // Header
        item {
            Text(
                text = stringResource(R.string.revanced_manager_by),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF3295E3),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                textAlign = TextAlign.Center
            )
        }

        // App cards
        items(
            items = apps,
            key = { app -> app.packageName }
        ) { app ->
            AppCard(
                app = app,
                onDownloadClick = {
                    onEvent(AppEvent.DownloadApp(app.packageName, app.downloadUrl))
                },
                onInstallClick = {
                    // This will be called internally after download completes
                },
                onUninstallClick = {
                    onEvent(AppEvent.UninstallApp(app.packageName))
                },
                onOpenClick = {
                    onEvent(AppEvent.OpenApp(app.packageName))
                }
            )
        }

        // Support buttons
        item {
            SupportButtons(
                onKofiClick = { launchUrl(context, "https://vanced.to/donate-redir") },
                onWebsiteClick = { launchUrl(context, "https://vanced.to") },
                onGithubClick = { launchUrl(context, "https://github.com/vancedapps/rv-manager") }
            )
        }

        // Bottom spacing
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

/**
 * Support buttons component
 */
@Composable
private fun SupportButtons(
    onKofiClick: () -> Unit,
    onWebsiteClick: () -> Unit,
    onGithubClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Ko-fi support button
        // Button(
        //     onClick = onKofiClick,
        //     colors = ButtonDefaults.buttonColors(
        //         containerColor = Color(0xFF4285F4),
        //         contentColor = Color.White
        //     ),
        //     modifier = Modifier.fillMaxWidth(0.8f),
        //     shape = MaterialTheme.shapes.medium
        // ) {
        //     Icon(
        //         imageVector = Icons.Filled.Coffee,
        //         contentDescription = "Support on Ko-fi",
        //         modifier = Modifier.size(20.dp),
        //         tint = Color.White
        //     )
        //     Spacer(modifier = Modifier.width(8.dp))
        //     Text(
        //         text = "Support me on Ko-fi",
        //         style = MaterialTheme.typography.labelLarge,
        //         color = Color.White
        //     )
        // }

        // Website button
        Button(
            onClick = onWebsiteClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3295E3),
                contentColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth(0.8f),
            shape = MaterialTheme.shapes.medium
        ) {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = "Visit website",
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Visit vanced.to",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }

        // Github button
        Button(
            onClick = onGithubClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3295E3),
                contentColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth(0.8f),
            shape = MaterialTheme.shapes.medium
        ) {
            Icon(
                imageVector = Icons.Filled.Code,
                contentDescription = "Github",
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Source code",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}

/**
 * Helper function to launch URLs
 */
private fun launchUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to open URL", Toast.LENGTH_SHORT).show()
    }
} 