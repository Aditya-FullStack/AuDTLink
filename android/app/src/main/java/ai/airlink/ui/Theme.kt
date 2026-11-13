package ai.airlink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0B0F14)
private val Panel = Color(0xFF121821)
private val PanelHigh = Color(0xFF1B2430)
private val Mint = Color(0xFF00D2B4)
private val Mist = Color(0xFFE6EEF5)
private val Dim = Color(0xFF93A4B3)

private val colors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00211B),
    primaryContainer = Color(0xFF00382F),
    onPrimaryContainer = Color(0xFF9DF3E4),
    secondary = Color(0xFF7FE8D6),
    background = Ink,
    onBackground = Mist,
    surface = Panel,
    onSurface = Mist,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Dim,
    outline = Color(0xFF2C3947),
    error = Color(0xFFFF6B6B)
)

@Composable
fun AirlinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
