package com.plainnotes.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.plainnotes.android.ui.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF5D7C70),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF9A7A4F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFBF5EC),
    onBackground = Color(0xFF27231D),
    surface = Color(0xFFFFFAF2),
    onSurface = Color(0xFF27231D),
    onSurfaceVariant = Color(0xFF766D60),
)

private val LightColors2 = lightColorScheme(
    primary = Color(0xFF6EC1E4),
    onPrimary = Color(0xFF123446),
    secondary = Color(0xFFF6C23E),
    onSecondary = Color(0xFF513C0B),
    background = Color(0xFFF4EFE6),
    onBackground = Color(0xFF2D2A24),
    surface = Color(0xFFF4EFE6),
    onSurface = Color(0xFF2D2A24),
    onSurfaceVariant = Color(0xFF70685D),
)

private val LightColors3 = lightColorScheme(
    primary = Color(0xFF4F7A71),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFD77A61),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F2EA),
    onBackground = Color(0xFF2A2621),
    surface = Color(0xFFFFFAF4),
    onSurface = Color(0xFF2A2621),
    onSurfaceVariant = Color(0xFF756A5C),
)

private val DarkColors1 = darkColorScheme(
    primary = Color(0xFF8BC8B2),
    onPrimary = Color(0xFF12342B),
    secondary = Color(0xFFE3B57F),
    onSecondary = Color(0xFF3A2508),
    background = Color(0xFF151816),
    onBackground = Color(0xFFF1EFE8),
    surface = Color(0xFF1D211E),
    onSurface = Color(0xFFF1EFE8),
    onSurfaceVariant = Color(0xFFD5CCBE),
)

private val DarkColors2 = darkColorScheme(
    primary = Color(0xFF8BC8B2),
    onPrimary = Color(0xFF12342B),
    secondary = Color(0xFFE3B57F),
    onSecondary = Color(0xFF3A2508),
    background = Color(0xFF242824),
    onBackground = Color(0xFFE7DDD0),
    surface = Color(0xFF343933),
    onSurface = Color(0xFFE7DDD0),
    onSurfaceVariant = Color(0xFFD5CCBE),
)

private val DarkColors3 = darkColorScheme(
    primary = Color(0xFF7CC7D9),
    onPrimary = Color(0xFF0D3340),
    secondary = Color(0xFFE0B36A),
    onSecondary = Color(0xFF3B2A08),
    background = Color(0xFF10151C),
    onBackground = Color(0xFFEAF0F4),
    surface = Color(0xFF18212B),
    onSurface = Color(0xFFEAF0F4),
    onSurfaceVariant = Color(0xFFB8C3CC),
)

@Immutable
data class PlainNotesChromeColors(
    val topAppBarContainer: Color,
    val topAppBarContent: Color,
)

private val LocalChromeColors = staticCompositionLocalOf {
    PlainNotesChromeColors(
        topAppBarContainer = Color.Unspecified,
        topAppBarContent = Color.Unspecified,
    )
}

@Composable
fun PlainNotesTheme(
    themeMode: ThemeMode,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val typography = scaledTypography(fontScale)
    val colorScheme = when (themeMode) {
        ThemeMode.LIGHT -> LightColors
        ThemeMode.LIGHT_2 -> LightColors2
        ThemeMode.LIGHT_3 -> LightColors3
        ThemeMode.DARK_1 -> DarkColors1
        ThemeMode.DARK_2 -> DarkColors2
        ThemeMode.DARK_3 -> DarkColors3
    }
    val chromeColors = when (themeMode) {
        ThemeMode.LIGHT -> PlainNotesChromeColors(
            topAppBarContainer = LightColors.surface,
            topAppBarContent = LightColors.onSurface,
        )
        ThemeMode.LIGHT_2 -> PlainNotesChromeColors(
            topAppBarContainer = Color(0xFFF6C23E),
            topAppBarContent = Color(0xFF513C0B),
        )
        ThemeMode.LIGHT_3 -> PlainNotesChromeColors(
            topAppBarContainer = Color(0xFFDCE8E1),
            topAppBarContent = Color(0xFF23352F),
        )
        ThemeMode.DARK_1 -> PlainNotesChromeColors(
            topAppBarContainer = DarkColors1.surface,
            topAppBarContent = DarkColors1.onSurface,
        )
        ThemeMode.DARK_2 -> PlainNotesChromeColors(
            topAppBarContainer = DarkColors2.surface,
            topAppBarContent = DarkColors2.onSurface,
        )
        ThemeMode.DARK_3 -> PlainNotesChromeColors(
            topAppBarContainer = Color(0xFF213040),
            topAppBarContent = DarkColors3.onSurface,
        )
    }

    CompositionLocalProvider(LocalChromeColors provides chromeColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}

@Composable
fun plainNotesTopAppBarColors(): TopAppBarColors {
    val chromeColors = LocalChromeColors.current
    return TopAppBarDefaults.topAppBarColors(
        containerColor = chromeColors.topAppBarContainer,
        titleContentColor = chromeColors.topAppBarContent,
        navigationIconContentColor = chromeColors.topAppBarContent,
        actionIconContentColor = chromeColors.topAppBarContent,
    )
}

private fun scaledTypography(scale: Float): Typography {
    fun size(base: Float) = (base * scale).sp

    return Typography(
        headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = size(28f), lineHeight = size(34f)),
        headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = size(24f), lineHeight = size(30f)),
        titleMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = size(18f), lineHeight = size(24f)),
        titleSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = size(15f), lineHeight = size(20f)),
        bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = size(17f), lineHeight = size(24f)),
        bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = size(16f), lineHeight = size(23f)),
        labelLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = size(14f), lineHeight = size(18f)),
        labelMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = size(13f), lineHeight = size(17f)),
    )
}
