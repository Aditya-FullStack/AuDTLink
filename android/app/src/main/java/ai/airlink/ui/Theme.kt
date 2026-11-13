package ai.airlink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0F766E)
private val TealDeep = Color(0xFF115E59)
private val TealSoft = Color(0xFFE6F5F3)
private val Page = Color(0xFFF7F8FA)
private val Card = Color(0xFFFFFFFF)
private val Line = Color(0xFFE3E7EB)
private val Ink = Color(0xFF141A1F)
private val Muted = Color(0xFF6B7680)
private val Danger = Color(0xFFC0392B)

private val colors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealSoft,
    onPrimaryContainer = TealDeep,
    secondary = TealDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF1FF),
    onSecondaryContainer = Color(0xFF1E3A8A),
    background = Page,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0F3F6),
    onSurfaceVariant = Muted,
    outline = Line,
    outlineVariant = Line,
    error = Danger,
    onError = Color.White
)

@Composable
fun AirlinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
