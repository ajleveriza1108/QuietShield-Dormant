package com.ajcoder.quietshield.dormant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ajcoder.quietshield.dormant.domain.ThemeChoice

private val AmoledScheme = darkColorScheme(
    primary = Color(0xFF76F2B2),
    onPrimary = Color(0xFF002116),
    secondary = Color(0xFFB6CCBE),
    background = Color.Black,
    onBackground = Color(0xFFE8F0EA),
    surface = Color.Black,
    onSurface = Color(0xFFE8F0EA),
    surfaceVariant = Color(0xFF111512),
    onSurfaceVariant = Color(0xFFBEC9C1),
    error = Color(0xFFFFB4AB),
)

private val OledScheme = darkColorScheme(
    primary = Color(0xFF76F2B2),
    onPrimary = Color(0xFF002116),
    secondary = Color(0xFFB6CCBE),
    background = Color(0xFF080A09),
    onBackground = Color(0xFFE8F0EA),
    surface = Color(0xFF101412),
    onSurface = Color(0xFFE8F0EA),
    surfaceVariant = Color(0xFF1B211D),
    onSurfaceVariant = Color(0xFFBEC9C1),
    error = Color(0xFFFFB4AB),
)

private val DirtyWhiteScheme = lightColorScheme(
    primary = Color(0xFF176B48),
    onPrimary = Color.White,
    secondary = Color(0xFF4E6357),
    background = Color(0xFFF5F1E8),
    onBackground = Color(0xFF181C19),
    surface = Color(0xFFFFFBF3),
    onSurface = Color(0xFF181C19),
    surfaceVariant = Color(0xFFE7E2D9),
    onSurfaceVariant = Color(0xFF414944),
    error = Color(0xFFBA1A1A),
)

@Composable
fun QuietShieldDormantTheme(
    choice: ThemeChoice,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (choice) {
        ThemeChoice.AMOLED -> AmoledScheme
        ThemeChoice.OLED -> OledScheme
        ThemeChoice.DIRTY_WHITE -> DirtyWhiteScheme
        ThemeChoice.SYSTEM -> if (isSystemInDarkTheme()) OledScheme else DirtyWhiteScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
