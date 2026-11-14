package ai.airlink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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

private val LightScheme = lightColorScheme(
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

private val NightPage = Color(0xFF0B0F14)
private val NightCard = Color(0xFF121821)
private val NightRaised = Color(0xFF1B2430)
private val NightLine = Color(0xFF2C3947)
private val NightInk = Color(0xFFE6EEF5)
private val NightMuted = Color(0xFF93A4B3)
private val Mint = Color(0xFF2DD4BF)

private val DarkScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00201B),
    primaryContainer = Color(0xFF123B36),
    onPrimaryContainer = Color(0xFF9DF3E4),
    secondary = Color(0xFF7FE8D6),
    onSecondary = Color(0xFF00201B),
    secondaryContainer = Color(0xFF1B2A44),
    onSecondaryContainer = Color(0xFFBFD3FF),
    background = NightPage,
    onBackground = NightInk,
    surface = NightCard,
    onSurface = NightInk,
    surfaceVariant = NightRaised,
    onSurfaceVariant = NightMuted,
    outline = NightLine,
    outlineVariant = NightLine,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0A0A)
)

@Composable
fun AirlinkTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(),
        content = content
    )
}
