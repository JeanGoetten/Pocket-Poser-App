package com.posepocket.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

private val LightScheme = lightColorScheme(
    primary = Palette.PrimaryLight, onPrimary = Palette.OnPrimaryLight,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFDDE9FF), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF10356D),
    secondary = Palette.PrimaryLight, secondaryContainer = androidx.compose.ui.graphics.Color(0xFFDDE9FF), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF10356D),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE4E8EE), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF4D5968),
    outline = androidx.compose.ui.graphics.Color(0xFFB8C0CD), outlineVariant = androidx.compose.ui.graphics.Color(0xFFDDE1E7),
    background = Palette.SurfaceLight, onBackground = Palette.OnSurfaceLight,
    surface = Palette.SurfaceLight, onSurface = Palette.OnSurfaceLight,
    error = Palette.ErrorLight,
)
private val DarkScheme = darkColorScheme(
    primary = Palette.PrimaryDark, onPrimary = Palette.OnPrimaryDark,
    background = Palette.SurfaceDark, onBackground = Palette.OnSurfaceDark,
    surface = Palette.SurfaceDark, onSurface = Palette.OnSurfaceDark,
    error = Palette.ErrorDark,
)
private val LightExtras = PocketExtras(warning = Palette.WarningLight, synthetic = Palette.SyntheticLight)
private val DarkExtras = PocketExtras(warning = Palette.WarningDark, synthetic = Palette.SyntheticDark)
private val LocalExtras = staticCompositionLocalOf { LightExtras }

private val PocketShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
)

object PocketTheme {
    val extras: PocketExtras @Composable @ReadOnlyComposable get() = LocalExtras.current
}

@Composable
fun PocketPoserTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtras provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, shapes = PocketShapes, content = content)
    }
}
