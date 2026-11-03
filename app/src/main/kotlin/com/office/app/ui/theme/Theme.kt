package com.office.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.office.app.data.ThemeMode
import com.office.core.FileKind

// A restrained palette: warm neutrals, one ink-blue accent, and a muted hue per file type.
private val Ink = Color(0xFF2B59C3)
private val InkDark = Color(0xFFA9C0F7)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FA),
    onPrimaryContainer = Color(0xFF0E2557),
    secondary = Color(0xFF55606E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8EB),
    onSecondaryContainer = Color(0xFF1D232B),
    tertiary = Color(0xFF1E7F4F),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFFAFAF8),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFEDEDE9),
    onSurfaceVariant = Color(0xFF5B5E63),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F5F2),
    surfaceContainer = Color(0xFFF0F0EC),
    surfaceContainerHigh = Color(0xFFEAEAE6),
    surfaceContainerHighest = Color(0xFFE4E4E0),
    outline = Color(0xFFC9CAC5),
    outlineVariant = Color(0xFFE1E1DC),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = InkDark,
    onPrimary = Color(0xFF0E2557),
    primaryContainer = Color(0xFF243E78),
    onPrimaryContainer = Color(0xFFDCE5FA),
    secondary = Color(0xFFBCC3CD),
    onSecondary = Color(0xFF262C34),
    secondaryContainer = Color(0xFF30353C),
    onSecondaryContainer = Color(0xFFDDE1E7),
    tertiary = Color(0xFF7FD3A5),
    background = Color(0xFF121314),
    onBackground = Color(0xFFE4E3E0),
    surface = Color(0xFF121314),
    onSurface = Color(0xFFE4E3E0),
    surfaceVariant = Color(0xFF2A2B2D),
    onSurfaceVariant = Color(0xFFA9ABAF),
    surfaceContainerLowest = Color(0xFF0D0E0F),
    surfaceContainerLow = Color(0xFF17181A),
    surfaceContainer = Color(0xFF1B1C1E),
    surfaceContainerHigh = Color(0xFF232426),
    surfaceContainerHighest = Color(0xFF2C2D30),
    outline = Color(0xFF45474B),
    outlineVariant = Color(0xFF323337),
    error = Color(0xFFF2B8B5),
)

@Immutable
data class KindColors(val document: Color, val sheet: Color, val pdf: Color, val slides: Color, val text: Color) {
    fun of(kind: FileKind): Color = when (kind) {
        FileKind.DOCUMENT -> document
        FileKind.SPREADSHEET, FileKind.CSV -> sheet
        FileKind.PDF -> pdf
        FileKind.PRESENTATION -> slides
        FileKind.TEXT, FileKind.MARKDOWN -> text
    }
}

private val LightKinds = KindColors(Color(0xFF2B59C3), Color(0xFF1E7F4F), Color(0xFFB8432F), Color(0xFFB8670F), Color(0xFF5E6670))
private val DarkKinds = KindColors(Color(0xFFA9C0F7), Color(0xFF7FD3A5), Color(0xFFF2A493), Color(0xFFF0BE7E), Color(0xFFB4BAC2))

val LocalKindColors = staticCompositionLocalOf { LightKinds }

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Monospace style used for cell references and formulas. */
val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun OfficeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalKindColors provides if (dark) DarkKinds else LightKinds) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
