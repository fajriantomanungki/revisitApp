package com.fajriantomanungki.revisitapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** Palet visual RevisitApp yang terinspirasi nuansa lapangan SE2026. */
object Se2026Palette {
    val Navy = Color(0xFF073B5C)
    val Blue = Color(0xFF0B6E99)
    val Teal = Color(0xFF0F766E)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFD92D20)
    val Ink = Color(0xFF102A43)
    val Mist = Color(0xFFF5F8FB)
    val Line = Color(0xFFD7E2EA)
}

private val LightColors = lightColorScheme(
    primary = Se2026Palette.Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEEF7),
    onPrimaryContainer = Color(0xFF06263A),
    secondary = Se2026Palette.Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4F1EA),
    onSecondaryContainer = Color(0xFF063B35),
    tertiary = Se2026Palette.Amber,
    onTertiary = Color(0xFF422B00),
    tertiaryContainer = Color(0xFFFFEEC5),
    onTertiaryContainer = Color(0xFF3D2A00),
    error = Se2026Palette.Red,
    onError = Color.White,
    errorContainer = Color(0xFFFDE3E1),
    onErrorContainer = Color(0xFF5F1412),
    background = Se2026Palette.Mist,
    onBackground = Se2026Palette.Ink,
    surface = Color.White,
    onSurface = Se2026Palette.Ink,
    surfaceVariant = Color(0xFFEAF1F5),
    onSurfaceVariant = Color(0xFF526777),
    outline = Color(0xFF9AAEBC),
    outlineVariant = Se2026Palette.Line
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DD7F1),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF064D6D),
    onPrimaryContainer = Color(0xFFDCEEF7),
    secondary = Color(0xFF8BD6C8),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF07584F),
    onSecondaryContainer = Color(0xFFD4F1EA),
    tertiary = Color(0xFFFFCA63),
    onTertiary = Color(0xFF422B00),
    tertiaryContainer = Color(0xFF644900),
    onTertiaryContainer = Color(0xFFFFEEC5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1B24),
    onBackground = Color(0xFFE4F0F7),
    surface = Color(0xFF132630),
    onSurface = Color(0xFFE4F0F7),
    surfaceVariant = Color(0xFF344954),
    onSurfaceVariant = Color(0xFFC0D0D9),
    outline = Color(0xFF8999A3),
    outlineVariant = Color(0xFF344954)
)

private val RevisitTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4f).sp
        ),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

private val RevisitShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun RevisitAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = RevisitTypography,
        shapes = RevisitShapes,
        content = content
    )
}
