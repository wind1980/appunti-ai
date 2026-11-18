package it.edoardo.appunti.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Palette ispirata a HiNoter: blu elettrico con sfumatura verso il viola.
val Brand = Color(0xFF4255FF)
val BrandViolet = Color(0xFF8A5CFF)
val BrandGradient = Brush.linearGradient(listOf(Brand, BrandViolet))
val SuccessGreen = Color(0xFF16A36A)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EAFF),
    onPrimaryContainer = Color(0xFF16207A),
    secondary = BrandViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E9FF),
    onSecondaryContainer = Color(0xFF34186E),
    tertiary = Color(0xFFF2803A),
    tertiaryContainer = Color(0xFFFFEDE0),
    onTertiaryContainer = Color(0xFF5A2A06),
    background = Color(0xFFF4F5FA),
    onBackground = Color(0xFF14161F),
    surface = Color(0xFFF4F5FA),
    onSurface = Color(0xFF14161F),
    surfaceVariant = Color(0xFFECEEF5),
    onSurfaceVariant = Color(0xFF5D6274),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F1F7),
    surfaceContainerHighest = Color(0xFFE9EBF3),
    outline = Color(0xFFCDD1DE),
    outlineVariant = Color(0xFFE3E6EF),
    error = Color(0xFFE5484D),
    errorContainer = Color(0xFFFFE6E6),
    onErrorContainer = Color(0xFF6B0F12),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8B96FF),
    onPrimary = Color(0xFF0A1170),
    primaryContainer = Color(0xFF262C6E),
    onPrimaryContainer = Color(0xFFDDE0FF),
    secondary = Color(0xFFB59BFF),
    secondaryContainer = Color(0xFF35275F),
    onSecondaryContainer = Color(0xFFEADDFF),
    tertiary = Color(0xFFFFB27F),
    tertiaryContainer = Color(0xFF4C2C14),
    onTertiaryContainer = Color(0xFFFFDCC4),
    background = Color(0xFF0E1016),
    onBackground = Color(0xFFE6E7EE),
    surface = Color(0xFF0E1016),
    onSurface = Color(0xFFE6E7EE),
    surfaceVariant = Color(0xFF22252F),
    onSurfaceVariant = Color(0xFFA8ADBE),
    surfaceContainerLowest = Color(0xFF171A22),
    surfaceContainerLow = Color(0xFF171A22),
    surfaceContainer = Color(0xFF1B1E27),
    surfaceContainerHigh = Color(0xFF22252F),
    surfaceContainerHighest = Color(0xFF2A2D38),
    outline = Color(0xFF444857),
    outlineVariant = Color(0xFF2E3240),
    errorContainer = Color(0xFF4A1C1F),
    onErrorContainer = Color(0xFFFFDAD9),
)

private val base = Typography()
private val AppTypography = base.copy(
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppuntiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
