/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat

private fun ytpLightColorScheme() = lightColorScheme(
    primary = YtpColors.Primary,
    onPrimary = YtpColors.OnPrimary,
    primaryContainer = YtpColors.PrimaryContainer,
    onPrimaryContainer = YtpColors.TextPrimary,
    secondary = YtpColors.PrimaryLight,
    onSecondary = YtpColors.OnPrimary,
    secondaryContainer = YtpColors.PrimaryContainer,
    onSecondaryContainer = YtpColors.TextPrimary,
    tertiary = YtpColors.PrimaryAccent,
    onTertiary = YtpColors.OnPrimary,
    tertiaryContainer = YtpColors.PrimaryContainer,
    onTertiaryContainer = YtpColors.TextPrimary,
    error = YtpColors.Error,
    onError = YtpColors.OnPrimary,
    errorContainer = YtpColors.ErrorContainer,
    onErrorContainer = YtpColors.TextPrimary,
    background = YtpColors.PageBackground,
    onBackground = YtpColors.TextPrimary,
    surface = YtpColors.Surface,
    onSurface = YtpColors.TextPrimary,
    surfaceVariant = YtpColors.PrimarySurfaceHighlight,
    onSurfaceVariant = YtpColors.TextSecondary,
    outline = YtpColors.Border,
    outlineVariant = YtpColors.Divider
)

private fun ytpDarkColorScheme() = darkColorScheme(
    primary = YtpColors.ThemePrimary,
    onPrimary = YtpColors.OnPrimary,
    primaryContainer = YtpColors.PrimaryContainer,
    onPrimaryContainer = YtpColors.TextPrimary,
    secondary = YtpColors.PrimaryLight,
    onSecondary = YtpColors.OnPrimary,
    secondaryContainer = YtpColors.PrimaryContainer,
    onSecondaryContainer = YtpColors.TextPrimary,
    tertiary = YtpColors.PrimaryAccent,
    onTertiary = YtpColors.OnPrimary,
    tertiaryContainer = YtpColors.PrimaryContainer,
    onTertiaryContainer = YtpColors.TextPrimary,
    error = YtpColors.Error,
    onError = YtpColors.OnPrimary,
    errorContainer = YtpColors.ErrorContainer,
    onErrorContainer = YtpColors.TextPrimary,
    background = YtpColors.PageBackground,
    onBackground = YtpColors.TextPrimary,
    surface = YtpColors.Surface,
    onSurface = YtpColors.TextPrimary,
    surfaceVariant = YtpColors.PrimarySurfaceHighlight,
    onSurfaceVariant = YtpColors.TextSecondary,
    outline = YtpColors.Border,
    outlineVariant = YtpColors.Divider
)

@Composable
fun LSPTheme(
    // Follow the system by default while keeping previews/tests able to override it.
    isDarkTheme: Boolean? = null,
    enableDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val useDarkTheme = isDarkTheme ?: isSystemInDarkTheme()
    YtpColors.setDarkTheme(useDarkTheme)
    val colorScheme = when {
        enableDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDarkTheme -> ytpDarkColorScheme()
        else -> ytpLightColorScheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as Activity).window.statusBarColor = colorScheme.background.toArgb()
            (view.context as Activity).window.navigationBarColor = colorScheme.background.toArgb()
            ViewCompat.getWindowInsetsController(view)?.isAppearanceLightStatusBars = !useDarkTheme
            ViewCompat.getWindowInsetsController(view)?.isAppearanceLightNavigationBars = !useDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
