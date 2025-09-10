package com.revanced.net.revancedmanager.presentation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.revanced.net.revancedmanager.domain.model.ThemeMode
import com.revanced.net.revancedmanager.ui.theme.Typography

// Vibrant color palette for action buttons
// Tailwind-inspired color palette
// Light theme colors

// Primary colors - Blue variants
private val Blue600 = Color(0xFF2563EB)
private val Blue700 = Color(0xFF1D4ED8)
private val Blue500 = Color(0xFF3B82F6)
private val Blue400 = Color(0xFF60A5FA)

// Success colors - Green variants
private val Green600 = Color(0xFF059669)
private val Green500 = Color(0xFF10B981)
private val Green400 = Color(0xFF34D399)

// Warning colors - Orange variants
private val Orange600 = Color(0xFFEA580C)
private val Orange500 = Color(0xFFF97316)
private val Orange400 = Color(0xFFFB923C)

// Error colors - Red variants
private val Red600 = Color(0xFFDC2626)
private val Red500 = Color(0xFFEF4444)
private val Red400 = Color(0xFFF87171)

// Neutral colors
private val Slate100 = Color(0xFFF1F5F9)
private val Slate200 = Color(0xFFE2E8F0)
private val Slate600 = Color(0xFF475569)
private val Slate700 = Color(0xFF334155)
private val Slate800 = Color(0xFF1E293B)
private val Slate900 = Color(0xFF0F172A)
private val Gray50 = Color(0xFFF9FAFB)
private val Gray100 = Color(0xFFF3F4F6)
private val Gray900 = Color(0xFF111827)
private val Zinc800 = Color(0xFF27272A)
private val Zinc900 = Color(0xFF18181B)
private val Zinc950 = Color(0xFF09090B)

private val DarkColorScheme = darkColorScheme(
    primary = Blue500,
    secondary = Slate600,
    tertiary = Blue400,
    background = Zinc950,
    surface = Zinc900,
    surfaceVariant = Zinc800,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Gray100,
    onSurface = Gray100,
    onSurfaceVariant = Slate200,
)

private val LightColorScheme = lightColorScheme(
    primary = Blue600,
    secondary = Slate600,
    tertiary = Blue700,
    background = Gray50,
    surface = Color.White,
    surfaceVariant = Gray100,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Gray900,
    onSurface = Gray900,
    onSurfaceVariant = Slate800,
)

/**
 * Main theme composable for ReVanced Manager
 * Supports dynamic theme switching
 */
@Composable
fun RevancedManagerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Extension properties for custom action button colors
val ColorScheme.downloadColor: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) Blue400 else Blue600

val ColorScheme.updateColor: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) Orange400 else Orange600

val ColorScheme.openColor: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) Green400 else Green600

val ColorScheme.uninstallColor: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) Red500 else Red600 