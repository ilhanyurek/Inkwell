package com.office.app.ui.document

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.office.core.text.RunLook

/** Points on paper → sp on screen: an 11pt body is shown at the editor's base size. */
internal fun lookFontSp(look: RunLook, baseSp: Float): Float {
    val sp = ((look.sizePt ?: 11f) / 11f * baseSp).coerceIn(7f, 72f)
    return if (look.vertical != 0) sp * 0.65f else sp
}

/** Span for a run as the document formats it, drawn on [background] (white paper or the theme surface). */
internal fun lookSpanStyle(look: RunLook, baseSp: Float, background: Color): SpanStyle {
    val highlight = look.highlight?.let { Color(it) }
    val color = when {
        look.color != null && highlight != null -> Color(look.color!!)
        look.color != null -> readableOn(Color(look.color!!), background)
        highlight != null -> if (highlight.luminance() > 0.5f) Color(0xFF1B1C1E) else Color(0xFFF5F5F5)
        else -> Color.Unspecified
    }
    val decorations = buildList {
        if (look.underline) add(TextDecoration.Underline)
        if (look.strike) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        color = color,
        background = highlight ?: Color.Unspecified,
        fontSize = lookFontSp(look, baseSp).sp,
        fontWeight = if (look.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (look.italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = fontFamilyFor(look.font),
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        baselineShift = when (look.vertical) {
            1 -> BaselineShift.Superscript
            -1 -> BaselineShift.Subscript
            else -> null
        },
        fontFeatureSettings = if (look.smallCaps) "smcp" else null,
    )
}

/** The phone has no Word fonts: pick the closest generic family, or keep the app font for sans-serif. */
internal fun fontFamilyFor(name: String?): FontFamily? {
    val n = name?.lowercase() ?: return null
    return when {
        listOf("courier", "consolas", "mono", "menlo", "lucida console").any { it in n } -> FontFamily.Monospace
        listOf("comic", "script", "brush", "segoe print", "handwriting").any { it in n } -> FontFamily.Cursive
        "sans" in n -> null
        listOf("times", "georgia", "garamond", "cambria", "serif", "antiqua", "palatino", "constantia", "minion", "bookman", "century").any { it in n } -> FontFamily.Serif
        else -> null
    }
}

/** A document colour that would vanish against [background] (black text in the dark theme) is mirrored in lightness. */
internal fun readableOn(color: Color, background: Color): Color {
    if (background == Color.Unspecified) return color
    val bg = background.luminance()
    val fg = color.luminance()
    val contrast = (maxOf(bg, fg) + 0.05f) / (minOf(bg, fg) + 0.05f)
    if (contrast >= 3f) return color
    val max = maxOf(color.red, color.green, color.blue)
    val min = minOf(color.red, color.green, color.blue)
    val lightness = (max + min) / 2f
    val target = (1f - lightness).let { if (bg < 0.5f) it.coerceAtLeast(0.72f) else it.coerceAtMost(0.3f) }
    // Scale towards white or black while keeping the hue.
    return if (target > lightness) {
        val t = if (lightness >= 1f) 1f else (target - lightness) / (1f - lightness)
        Color(color.red + (1f - color.red) * t, color.green + (1f - color.green) * t, color.blue + (1f - color.blue) * t)
    } else {
        val t = if (lightness <= 0f) 0f else target / lightness
        Color(color.red * t, color.green * t, color.blue * t)
    }
}
