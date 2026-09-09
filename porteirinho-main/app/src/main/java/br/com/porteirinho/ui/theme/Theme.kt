package br.com.porteirinho.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF7EA2AA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBED),
    onPrimaryContainer = Color(0xFF233D4D),
    secondary = Color(0xFF6B8A90),
    tertiary = Color(0xFFF6AE2D),
    error = Color(0xFF6B0F1A),
    errorContainer = Color(0xFFF4DFDF),
    background = Color(0xFFFFFFFF),
    surface = Color.White,
    surfaceVariant = Color(0xFFF4F4F4),
    onSurface = Color(0xFF333333),
    onSurfaceVariant = Color(0xFF606060),
    outline = Color(0xFFD2D2D2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BBDC4),
    onPrimary = Color(0xFF1C1C1C),
    primaryContainer = Color(0xFF445E64),
    secondary = Color(0xFFA5BEC4),
    tertiary = Color(0xFFF6AE2D),
    background = Color(0xFF1C1C1C),
    surface = Color(0xFF282828),
    surfaceVariant = Color(0xFF333333),
)

@Composable
fun PorteirinhoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
