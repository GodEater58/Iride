package it.goletto.iride.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import it.goletto.iride.R

/**
 * Palette ispirata al corpo di una reflex: grafite opaca, display superiore
 * retroilluminato in ambra, rosso solo per l'esposizione in corso.
 * In modalità notte tutto diventa rosso su nero per non perdere l'adattamento al buio.
 */
@Immutable
data class IridePalette(
    val body: Color,        // sfondo
    val raised: Color,      // superfici rialzate (pannelli, tasti)
    val groove: Color,      // incavi, campi, linee
    val text: Color,
    val muted: Color,
    val lcd: Color,         // readout e azioni principali
    val lcdDim: Color,
    val onLcd: Color,
    val exposure: Color,    // scatto / posa in corso / errori
    val ok: Color,          // collegato
    val night: Boolean,
)

val DayPalette = IridePalette(
    body = Color(0xFF1C1E21),
    raised = Color(0xFF26292D),
    groove = Color(0xFF34383E),
    text = Color(0xFFECE8E1),
    muted = Color(0xFF9A9EA6),
    lcd = Color(0xFFF3A83B),
    lcdDim = Color(0xFF5A4322),
    onLcd = Color(0xFF231A0C),
    exposure = Color(0xFFE5484D),
    ok = Color(0xFF5BC27A),
    night = false,
)

val NightPalette = IridePalette(
    body = Color(0xFF000000),
    raised = Color(0xFF140303),
    groove = Color(0xFF2A0807),
    text = Color(0xFFD23A30),
    muted = Color(0xFF7E1F19),
    lcd = Color(0xFFE0453A),
    lcdDim = Color(0xFF3A0B09),
    onLcd = Color(0xFF000000),
    exposure = Color(0xFFFF5A4C),
    ok = Color(0xFFB8322A),
    night = true,
)

val LocalIride = staticCompositionLocalOf { DayPalette }

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semi_bold, FontWeight.SemiBold),
)

val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semi_bold, FontWeight.SemiBold),
)

/** Stile per i numeri da display: condensato, cifre tabellari. */
fun readout(size: Int, weight: FontWeight = FontWeight.SemiBold) = TextStyle(
    fontFamily = BarlowCondensed,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * 1.05).sp,
    fontFeatureSettings = "tnum",
    letterSpacing = 0.2.sp,
)

private val IrideTypography = Typography(
    displaySmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun IrideTheme(night: Boolean, content: @Composable () -> Unit) {
    val target = if (night) NightPalette else DayPalette
    val spec = tween<Color>(450)
    val body by animateColorAsState(target.body, spec, label = "body")
    val raised by animateColorAsState(target.raised, spec, label = "raised")
    val groove by animateColorAsState(target.groove, spec, label = "groove")
    val text by animateColorAsState(target.text, spec, label = "text")
    val muted by animateColorAsState(target.muted, spec, label = "muted")
    val lcd by animateColorAsState(target.lcd, spec, label = "lcd")
    val lcdDim by animateColorAsState(target.lcdDim, spec, label = "lcdDim")
    val onLcd by animateColorAsState(target.onLcd, spec, label = "onLcd")
    val exposure by animateColorAsState(target.exposure, spec, label = "exposure")
    val ok by animateColorAsState(target.ok, spec, label = "ok")
    val p = IridePalette(body, raised, groove, text, muted, lcd, lcdDim, onLcd, exposure, ok, night)

    val scheme = darkColorScheme(
        primary = p.lcd,
        onPrimary = p.onLcd,
        secondary = p.lcd,
        onSecondary = p.onLcd,
        background = p.body,
        onBackground = p.text,
        surface = p.body,
        onSurface = p.text,
        surfaceVariant = p.raised,
        onSurfaceVariant = p.muted,
        surfaceContainerLow = p.raised,
        surfaceContainer = p.raised,
        surfaceContainerHigh = p.raised,
        outline = p.groove,
        outlineVariant = p.groove,
        error = p.exposure,
        onError = p.onLcd,
    )
    CompositionLocalProvider(LocalIride provides p) {
        MaterialTheme(colorScheme = scheme, typography = IrideTypography, content = content)
    }
}
