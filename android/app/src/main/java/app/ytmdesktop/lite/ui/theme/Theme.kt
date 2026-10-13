package app.ytmdesktop.lite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.ytmdesktop.lite.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Palette from src/app.css (--md-sys-color-*, --term-*). Primary follows the user's accent. */
@Immutable
data class YtmdColors(
    val surface: Color = Color(0xFF0F131A),
    val lowest: Color = Color(0xFF0A0D12),
    val low: Color = Color(0xFF10141C),
    val container: Color = Color(0xFF161B24),
    val high: Color = Color(0xFF1C2330),
    val highest: Color = Color(0xFF252D3C),
    val onSurface: Color = Color(0xFFD7DDE8),
    val muted: Color = Color(0xFF7F8AA3),
    val outline: Color = Color(0xFF2A3344),
    val outlineVariant: Color = Color(0xFF1E2633),
    val primary: Color = Color(0xFFA8C7FA),
    val onPrimary: Color = Color(0xFF062E6F),
    val primaryContainer: Color = Color(0xFF1A3A6E),
    val error: Color = Color(0xFFF2B8B5),
    val tertiary: Color = Color(0xFFC4A8E8),
    val termBg: Color = Color(0xFF0C0F14),
    val termChrome: Color = Color(0xFF151A22),
    val ok: Color = Color(0xFF7FD99A),
    val warn: Color = Color(0xFFE6C07B),
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** Type scale: desktop sizes nudged up for touch screens. */
object Type {
    val base = TextStyle(fontFamily = Mono, fontSize = 13.sp, letterSpacing = (-0.01).em, lineHeight = 18.sp)
    val small = base.copy(fontSize = 11.sp, lineHeight = 15.sp)
    val tiny = base.copy(fontSize = 10.sp, lineHeight = 13.sp)
    val title = base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val hero = base.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
}

val Radius = 2.dp

val LocalColors = staticCompositionLocalOf { YtmdColors() }
val LocalCodeTheme = staticCompositionLocalOf { false }

object Ytmd {
    val colors: YtmdColors @Composable get() = LocalColors.current
    val code: Boolean @Composable get() = LocalCodeTheme.current
}

// ── Accent (port of src/lib/accent.ts) ───────────────────────────────────────

data class Rgb(val r: Int, val g: Int, val b: Int)

private fun clampByte(n: Double) = n.roundToInt().coerceIn(0, 255)

fun rgbToHex(c: Rgb) = "#%02x%02x%02x".format(c.r, c.g, c.b)

/** Parse hex (#abc, #aabbcc, #aabbccdd), rgb()/rgba(), or "r, g, b". */
fun parseColorInput(raw: String): Rgb? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    var s = t.removePrefix("#")
    if (Regex("^[0-9a-fA-F]{3}$").matches(s)) s = s.map { "$it$it" }.joinToString("")
    if (Regex("^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$").matches(s)) {
        return Rgb(s.substring(0, 2).toInt(16), s.substring(2, 4).toInt(16), s.substring(4, 6).toInt(16))
    }
    Regex("^rgba?\\(\\s*([0-9.]+)\\s*[, ]\\s*([0-9.]+)\\s*[, ]\\s*([0-9.]+)(?:\\s*[,/]\\s*[0-9.%]+)?\\s*\\)$", RegexOption.IGNORE_CASE)
        .matchEntire(t)?.let { m ->
            val (r, g, b) = m.destructured
            return Rgb(clampByte(r.toDouble()), clampByte(g.toDouble()), clampByte(b.toDouble()))
        }
    val parts = t.split(Regex("[\\s,;/]+")).filter { it.isNotEmpty() }
    if (parts.size == 3 && parts.all { Regex("^-?\\d+(\\.\\d+)?%?$").matches(it) }) {
        val n = parts.map { if (it.endsWith("%")) it.dropLast(1).toDouble() / 100 * 255 else it.toDouble() }
        return Rgb(clampByte(n[0]), clampByte(n[1]), clampByte(n[2]))
    }
    return null
}

fun normalizeAccent(raw: String?): String = parseColorInput(raw ?: "")?.let(::rgbToHex) ?: "#a8c7fa"

private fun luminance(c: Rgb): Double {
    fun lin(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
}

private fun mix(a: Rgb, b: Rgb, t: Double) = Rgb(
    clampByte(a.r + (b.r - a.r) * t), clampByte(a.g + (b.g - a.g) * t), clampByte(a.b + (b.b - a.b) * t),
)

fun Rgb.toColor() = Color(r, g, b)

fun accentColors(hex: String): YtmdColors {
    val rgb = parseColorInput(hex) ?: parseColorInput("#a8c7fa")!!
    return YtmdColors(
        primary = rgb.toColor(),
        onPrimary = if (luminance(rgb) > 0.45) Color(0xFF0B0E14) else Color(0xFFF4F6FA),
        primaryContainer = mix(rgb, Rgb(11, 14, 20), 0.55).toColor(),
    )
}

/** CSS color-mix(in srgb, a p%, b). */
fun Color.mixWith(other: Color, fraction: Float): Color = Color(
    red = red * fraction + other.red * (1 - fraction),
    green = green * fraction + other.green * (1 - fraction),
    blue = blue * fraction + other.blue * (1 - fraction),
    alpha = max(min(alpha * fraction + other.alpha * (1 - fraction), 1f), 0f),
)

@Composable
fun YtmdTheme(accent: String, codeTheme: Boolean, content: @Composable () -> Unit) {
    val colors = remember(accent) { accentColors(accent) }
    val scheme = darkColorScheme(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        primaryContainer = colors.primaryContainer,
        background = colors.surface,
        surface = colors.surface,
        surfaceContainer = colors.container,
        surfaceContainerHigh = colors.high,
        surfaceContainerHighest = colors.highest,
        surfaceContainerLow = colors.low,
        surfaceContainerLowest = colors.lowest,
        onSurface = colors.onSurface,
        onSurfaceVariant = colors.muted,
        outline = colors.outline,
        outlineVariant = colors.outlineVariant,
        error = colors.error,
        tertiary = colors.tertiary,
    )
    CompositionLocalProvider(LocalColors provides colors, LocalCodeTheme provides codeTheme) {
        MaterialTheme(colorScheme = scheme, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides Type.base.copy(color = colors.onSurface),
                androidx.compose.material3.LocalContentColor provides colors.onSurface,
            ) {
                content()
            }
        }
    }
}
