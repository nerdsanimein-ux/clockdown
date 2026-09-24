package com.rishabh.clockdown.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Own palette rather than wallpaper colours: the event cards are the colour, the chrome stays calm.
private val Light = lightColorScheme(
    primary = Color(0xFF4A3FE0), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E0FF), onPrimaryContainer = Color(0xFF1A1170),
    secondary = Color(0xFF5B5D72), secondaryContainer = Color(0xFFE1E0F9), onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFFE9604A),
    background = Color(0xFFF6F3EC), onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFF6F3EC), onSurface = Color(0xFF1C1B20), onSurfaceVariant = Color(0xFF55535F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBF9F4), surfaceContainer = Color(0xFFEFEBE2),
    surfaceContainerHigh = Color(0xFFE9E5DA), surfaceContainerHighest = Color(0xFFE3DFD3),
    outline = Color(0xFF87848F), outlineVariant = Color(0xFFCBC7D0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFA8FF), onPrimary = Color(0xFF2A1FA0),
    primaryContainer = Color(0xFF3A2FC8), onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = Color(0xFFC5C4DD), secondaryContainer = Color(0xFF444559), onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFFFFB4A5),
    background = Color(0xFF0F1115), onBackground = Color(0xFFE6E4EB),
    surface = Color(0xFF0F1115), onSurface = Color(0xFFE6E4EB), onSurfaceVariant = Color(0xFFB0AEB9),
    surfaceContainerLowest = Color(0xFF0A0B0E), surfaceContainerLow = Color(0xFF15171C), surfaceContainer = Color(0xFF1B1E24),
    surfaceContainerHigh = Color(0xFF23262D), surfaceContainerHighest = Color(0xFF2C3038),
    outline = Color(0xFF7A7885), outlineVariant = Color(0xFF3A3944),
)

// Expressive shapes: big, soft containers.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(40.dp),
)

@Composable
fun ClockdownTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = Typography,
        shapes = ExpressiveShapes,
        motionScheme = MotionScheme.expressive(), // spring-based motion for every built-in component
        content = content,
    )
}
