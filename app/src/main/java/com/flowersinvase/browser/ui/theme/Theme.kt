package com.flowersinvase.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3482FF), onPrimary = Color.White,
    secondary = Color(0xFF3482FF), background = Color(0xFFF3F3F3),
    surface = Color.White, surfaceVariant = Color(0xFFE8E8E8),
    onSurface = Color(0xFF080808), onBackground = Color(0xFF080808),
    onSurfaceVariant = Color(0xFF878787), outline = Color(0xFFDADADA),
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE8E8E8)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF438DFF), onPrimary = Color.White,
    secondary = Color(0xFF438DFF), background = Color(0xFF000000),
    surface = Color(0xFF191919), surfaceVariant = Color(0xFF292929),
    onSurface = Color(0xFFF3F3F3), onBackground = Color(0xFFF3F3F3),
    onSurfaceVariant = Color(0xFF929292), outline = Color(0xFF363636),
    surfaceContainer = Color(0xFF191919), surfaceContainerHigh = Color(0xFF202020),
    surfaceContainerHighest = Color(0xFF292929)
)
@Composable
fun BrowserTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if(darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = Typography) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}
