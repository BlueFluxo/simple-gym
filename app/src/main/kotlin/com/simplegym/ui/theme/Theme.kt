package com.simplegym.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Background = Color(0xFF0F1115)
val Surface = Color(0xFF1A1D23)
val Accent = Color(0xFFFF7A1A)

/** Warning / hurt amber. */
val Amber = Color(0xFFFFB020)

private val OnDark = Color(0xFFECEEF2)
private val OnDarkMuted = Color(0xFF9AA0AB)

/** A step lighter than [Surface]: tonal buttons and dividers sitting on a card. */
private val Raised = Color(0xFF2A2E36)

/** Every tappable control is at least this tall and wide. */
val MinTouch = 56.dp

private val GymColors = darkColorScheme(
    primary = Accent,
    onPrimary = Background,
    primaryContainer = Accent,
    onPrimaryContainer = Background,
    secondary = Accent,
    onSecondary = Background,
    // FilledTonalButton and friends draw on secondaryContainer: keep it on the palette, not the M3 purple-grey.
    secondaryContainer = Raised,
    onSecondaryContainer = OnDark,
    tertiary = Amber,
    onTertiary = Background,
    tertiaryContainer = Amber,
    onTertiaryContainer = Background,
    background = Background,
    onBackground = OnDark,
    surface = Surface,
    onSurface = OnDark,
    surfaceVariant = Surface,
    onSurfaceVariant = OnDarkMuted,
    surfaceContainer = Surface,
    surfaceContainerHigh = Surface,
    surfaceContainerLowest = Background,
    surfaceContainerLow = Surface,
    surfaceContainerHighest = Raised,
    outline = OnDarkMuted,
    outlineVariant = Raised,
    error = Amber,
    onError = Background,
    errorContainer = Amber,
    onErrorContainer = Background,
)

/** Big numerals for weights and reps; the rest is Material's default scale. */
private val GymTypography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
)

/** The app is dark-only: there is deliberately no light scheme and no dynamic colour. */
@Composable
fun SimpleGymTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GymColors, typography = GymTypography, content = content)
}
