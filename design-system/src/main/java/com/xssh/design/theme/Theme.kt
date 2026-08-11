package com.xssh.design.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColors =
    darkColorScheme(
        primary = XSshPrimary,
        onPrimary = XSshOnPrimary,
        primaryContainer = XSshPrimaryContainer,
        onPrimaryContainer = XSshOnPrimaryContainer,
        secondary = XSshSecondary,
        onSecondary = XSshOnSecondary,
        secondaryContainer = XSshSecondaryContainer,
        onSecondaryContainer = XSshOnSecondaryContainer,
        tertiary = XSshTertiary,
        onTertiary = XSshOnTertiary,
        tertiaryContainer = XSshTertiaryContainer,
        onTertiaryContainer = XSshOnTertiaryContainer,
        background = XSshBackground,
        surface = XSshSurface,
        surfaceVariant = XSshSurfaceElev,
        surfaceContainer = XSshSurfaceElev,
        surfaceContainerHigh = XSshSurfaceHigh,
        onSurface = XSshOnSurface,
        onSurfaceVariant = XSshOnSurfaceVariant,
        outline = XSshOutline,
        outlineVariant = XSshOutlineVariant,
        error = XSshError,
        onError = XSshOnError,
        errorContainer = XSshErrorContainer,
    )

private val LightColors =
    lightColorScheme(
        primary = XSshLightPrimary,
        onPrimary = XSshLightOnPrimary,
        primaryContainer = XSshLightPrimaryContainer,
        onPrimaryContainer = XSshLightOnPrimaryContainer,
        secondary = XSshLightSecondary,
        onSecondary = XSshLightOnSecondary,
        secondaryContainer = XSshLightSecondaryContainer,
        onSecondaryContainer = XSshLightOnSecondaryContainer,
        background = XSshLightBackground,
        surface = XSshLightSurface,
        surfaceVariant = XSshLightSurfaceElev,
        surfaceContainer = XSshLightSurfaceElev,
        surfaceContainerHigh = XSshLightSurfaceHigh,
        onSurface = XSshLightOnSurface,
        onSurfaceVariant = XSshLightOnSurfaceVariant,
        outline = XSshLightOutline,
        outlineVariant = XSshLightOutlineVariant,
        error = XSshLightError,
        errorContainer = XSshLightErrorContainer,
    )

private val XSshShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

@Composable
fun XSshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColors
            else -> LightColors
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = XSshTypography,
        shapes = XSshShapes,
        content = content,
    )
}
