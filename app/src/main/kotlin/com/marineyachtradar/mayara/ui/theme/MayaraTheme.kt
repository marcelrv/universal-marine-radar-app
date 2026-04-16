package com.marineyachtradar.mayara.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Application colour scheme.
 *
 * Default: deep dark marine chartplotter aesthetic (spec §3.1)
 *   Background: #0B0C10 (deep charcoal/black)
 *   Accents:    high-visibility marine green
 *
 * Night-vision mode (Red/Black) — TODO Phase 4: toggle via DataStore preference.
 */

private val MarineGreen = Color(0xFF00E676)
private val MarineGreenDim = Color(0xFF2E7D32)
private val CharcoalBlack = Color(0xFF0B0C10)
private val SurfaceVariant = Color(0xFF1C1E24)
private val OnSurface = Color(0xFFE0E0E0)

private val MayaraDarkColors = darkColorScheme(
    primary = MarineGreen,
    onPrimary = Color.Black,
    primaryContainer = MarineGreenDim,
    onPrimaryContainer = Color.White,
    background = CharcoalBlack,
    onBackground = OnSurface,
    surface = SurfaceVariant,
    onSurface = OnSurface,
    surfaceVariant = Color(0xFF2A2D35),
    onSurfaceVariant = Color(0xFFB0B0B0),
    error = Color(0xFFCF6679),
    onError = Color.Black,
)

@Composable
fun MayaraTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = CharcoalBlack.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = MayaraDarkColors,
        content = content,
    )
}
