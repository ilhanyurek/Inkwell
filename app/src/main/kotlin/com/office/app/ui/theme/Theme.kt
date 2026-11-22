package com.office.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.office.app.R
import com.office.app.data.ThemeMode
import com.office.core.FileKind

// Deep navy on a cool light grey, with white rounded cards. Cards, sheets, menus and dialogs all use the
// surfaceContainer roles; the page itself is background/surface; surfaceVariant is the quiet grey fill.

private val Navy = Color(0xFF1D2B5E)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6EAF6),
    onPrimaryContainer = Navy,
    inversePrimary = Color(0xFFB9C7FF),
    secondary = Color(0xFF5D6573),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EAF6),
    onSecondaryContainer = Navy,
    tertiary = Color(0xFF1E7F4F),
    onTertiary = Color.White,
    background = Color(0xFFF1F3F7),
    onBackground = Color(0xFF12161F),
    surface = Color(0xFFF1F3F7),
    onSurface = Color(0xFF12161F),
    surfaceVariant = Color(0xFFE8EBF1),
    onSurfaceVariant = Color(0xFF5D6573),
    surfaceTint = Navy,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE8EBF1),
    surfaceDim = Color(0xFFE3E6EC),
    surfaceBright = Color.White,
    outline = Color(0xFFC3C8D2),
    outlineVariant = Color(0xFFE3E6EC),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE4E2),
    onErrorContainer = Color(0xFF5F1410),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C7FF),
    onPrimary = Color(0xFF0F1A45),
    primaryContainer = Color(0xFF2B3666),
    onPrimaryContainer = Color(0xFFDDE3FF),
    inversePrimary = Navy,
    secondary = Color(0xFFB3BAC7),
    onSecondary = Color(0xFF1E232C),
    secondaryContainer = Color(0xFF2B3666),
    onSecondaryContainer = Color(0xFFDDE3FF),
    tertiary = Color(0xFF7FD3A5),
    onTertiary = Color(0xFF00391F),
    background = Color(0xFF101217),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF101217),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF252933),
    onSurfaceVariant = Color(0xFF9AA1AE),
    surfaceTint = Color(0xFFB9C7FF),
    surfaceContainerLowest = Color(0xFF0B0D11),
    surfaceContainerLow = Color(0xFF1A1D24),
    surfaceContainer = Color(0xFF1A1D24),
    surfaceContainerHigh = Color(0xFF1E2129),
    surfaceContainerHighest = Color(0xFF282C36),
    surfaceDim = Color(0xFF101217),
    surfaceBright = Color(0xFF2A2E38),
    outline = Color(0xFF424854),
    outlineVariant = Color(0xFF2A2E37),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** True black for OLED screens: black page, near-black cards that carry a thin border. */
private val BlackColors = DarkColors.copy(
    primary = Color(0xFFA9BCFF),
    onPrimary = Color(0xFF0B1440),
    primaryContainer = Color(0xFF1E2650),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondaryContainer = Color(0xFF1E2650),
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF1A1C20),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF111215),
    surfaceContainer = Color(0xFF111215),
    surfaceContainerHigh = Color(0xFF15161A),
    surfaceContainerHighest = Color(0xFF1C1E22),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF1C1E22),
    outline = Color(0xFF34373D),
    outlineVariant = Color(0xFF1C1E22),
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

private val LightKinds = KindColors(Color(0xFF2F56B8), Color(0xFF1E7F4F), Color(0xFFC0442F), Color(0xFFB8670F), Color(0xFF5D6573))
private val DarkKinds = KindColors(Color(0xFFA9BCFF), Color(0xFF7FD3A5), Color(0xFFF2A493), Color(0xFFF0BE7E), Color(0xFFB3BAC7))

val LocalKindColors = staticCompositionLocalOf { LightKinds }

/** Extra design tokens the Material colour scheme has no slot for. */
@Immutable
data class Appearance(
    /** A hairline drawn around cards, used in the black theme where shadows do not show. */
    val cardBorder: Color?,
    val dark: Boolean,
    val black: Boolean,
)

val LocalAppearance = staticCompositionLocalOf { Appearance(cardBorder = null, dark = false, black = false) }

val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)

private val AppTypography = Typography().let { t ->
    fun TextStyle.f(weight: FontWeight? = null, size: Int? = null, line: Int? = null, spacing: Double? = null) = copy(
        fontFamily = Figtree,
        fontWeight = weight ?: fontWeight,
        fontSize = size?.sp ?: fontSize,
        lineHeight = line?.sp ?: lineHeight,
        letterSpacing = spacing?.sp ?: letterSpacing,
    )
    Typography(
        displayLarge = t.displayLarge.f(FontWeight.Bold),
        displayMedium = t.displayMedium.f(FontWeight.Bold),
        displaySmall = t.displaySmall.f(FontWeight.Bold, spacing = -0.5),
        headlineLarge = t.headlineLarge.f(FontWeight.Bold, spacing = -0.5),
        headlineMedium = t.headlineMedium.f(FontWeight.Bold, size = 28, line = 34, spacing = -0.5),
        headlineSmall = t.headlineSmall.f(FontWeight.Bold, size = 22, line = 28, spacing = -0.25),
        titleLarge = t.titleLarge.f(FontWeight.Bold, size = 20, line = 26, spacing = -0.2),
        titleMedium = t.titleMedium.f(FontWeight.SemiBold, size = 16, line = 22, spacing = 0.0),
        titleSmall = t.titleSmall.f(FontWeight.SemiBold, spacing = 0.0),
        bodyLarge = t.bodyLarge.f(FontWeight.Normal, spacing = 0.1),
        bodyMedium = t.bodyMedium.f(FontWeight.Normal, spacing = 0.1),
        bodySmall = t.bodySmall.f(FontWeight.Normal, spacing = 0.1),
        labelLarge = t.labelLarge.f(FontWeight.SemiBold, spacing = 0.1),
        labelMedium = t.labelMedium.f(FontWeight.SemiBold, size = 12, spacing = 0.8),
        labelSmall = t.labelSmall.f(FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Colours used for a document drawn as a white sheet, whatever the app theme. */
val PaperColorScheme: ColorScheme get() = LightColors.copy(
    background = Color.White,
    surface = Color.White,
    surfaceContainerHigh = Color(0xFFEEF0F4),
)

/** Monospace style used for cell references and formulas. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun OfficeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
    val black = themeMode == ThemeMode.BLACK
    val base = when {
        black -> BlackColors
        dark -> DarkColors
        else -> LightColors
    }
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Wallpaper colours only replace the accent; the page and cards keep the app's own greys.
        val ctx = LocalContext.current
        val dyn = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        base.copy(
            primary = dyn.primary,
            onPrimary = dyn.onPrimary,
            primaryContainer = dyn.primaryContainer,
            onPrimaryContainer = dyn.onPrimaryContainer,
            secondaryContainer = dyn.secondaryContainer,
            onSecondaryContainer = dyn.onSecondaryContainer,
        )
    } else base

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    val appearance = Appearance(cardBorder = if (black) Color(0xFF1F2125) else null, dark = dark, black = black)
    CompositionLocalProvider(
        LocalKindColors provides if (dark) DarkKinds else LightKinds,
        LocalAppearance provides appearance,
    ) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
