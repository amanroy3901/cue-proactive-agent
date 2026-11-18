package com.cue.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Qira palette. Colour roles: the Qira ribbon (blue-violet to purple to pink) is Cue itself (the agent, its voice and its
 * primary actions); coral to red is anything time-sensitive; white to pale lavender is the base; each app has one colour
 * so it is recognised before it is read. No teal or green anywhere.
 */
object Cue {
    val base = Color(0xFFF5F3FF)
    val baseDeep = Color(0xFFEDE8FF)
    val ink = Color(0xFF1D1B26)
    val muted = Color(0xFF5A5670)
    val line = Color(0xFFECE8F6)
    val white = Color.White

    // Cue's own colour: the Qira ribbon. Light stops for glows and orbs, deep stops for text and icons on light backgrounds.
    val primaryLight = Color(0xFF8B95FF)
    val primary = Color(0xFF6C4FD6)
    val primaryDeep = Color(0xFF4B2FB5)
    val primaryNight = Color(0xFF2A1A6E)
    val primaryTint = Color(0xFFECE7FF)
    val ribbonBlue = Color(0xFF7D8BFF)
    val ribbonPurple = Color(0xFFA86CF0)
    val ribbonPink = Color(0xFFEE5C9C)

    // Pink secondary.
    val pink = Color(0xFFEE5C9C)
    val pinkTint = Color(0xFFFFE3EE)
    val pinkText = Color(0xFFA01E57)

    // Time-sensitive: coral to red.
    val coral = Color(0xFFFF7A55)
    val coralDeep = Color(0xFFF2454B)
    val coralText = Color(0xFFA3222A)
    val coralTint = Color(0xFFFFE6E3)
    val apricot = Color(0xFFFFB09A)

    // Night: deep indigo with a lavender glow.
    val night = Color(0xFF1C1830)
    val nightRaised = Color(0xFF2A2445)
    val nightText = Color(0xFFEEEAFB)
    val nightMuted = Color(0xFFB5ADD6)
    val nightGlow = Color(0xFFB07AF2)

    // Soft glows for page backgrounds.
    val glowLavender = Color(0xFFE4DEFF)
    val glowBlush = Color(0xFFFFE0EC)
    val glowPeri = Color(0xFFDCE0FF)

    /** The exact Qira logo ribbon: the orb, the Talk button and other decorative surfaces without small text. */
    val ribbonBrush = Brush.linearGradient(listOf(ribbonBlue, ribbonPurple, ribbonPink))
    /** The ribbon deepened just enough to carry white text (about 4.3 to 4.8:1): hero cards, user bubbles, ticks. */
    val primaryBrush = Brush.linearGradient(listOf(Color(0xFF6268F0), Color(0xFF8E4FDE), Color(0xFFD4447F)))
    val primaryButton = Brush.horizontalGradient(listOf(Color(0xFF6268F0), Color(0xFF8E4FDE), Color(0xFFD4447F)))
    val coralButton = Brush.verticalGradient(listOf(Color(0xFFFF7A55), Color(0xFFF2454B)))
    val sunrise = Brush.verticalGradient(listOf(Color(0xFFF7F3FF), Color(0xFFFFDDEA), Color(0xFFFFA9C2)))
    val tripBrush = Brush.linearGradient(listOf(Color(0xFF7D8BFF), Color(0xFF5B6BE8), Color(0xFF3F45B8)))
    val journalBrush = Brush.linearGradient(listOf(Color(0xFFE2683F), Color(0xFFC24A2E), Color(0xFF9E3622)))
    val marigoldCard = Brush.linearGradient(listOf(Color(0xFFFFF0C7), Color(0xFFFFD98A)))
}

/** One colour per app. */
data class AppTint(val light: Color, val deep: Color, val text: Color)

object AppTints {
    val lists = AppTint(Color(0xFFB07AF2), Color(0xFF8A4FD8), Color(0xFF5A2AA8))
    val habits = AppTint(Color(0xFFFFC861), Color(0xFFF08A00), Color(0xFF4E3800))
    val journal = AppTint(Color(0xFFFFB49C), Color(0xFFF0663F), Color(0xFF8E2713))
    val albums = AppTint(Color(0xFF8B95FF), Color(0xFF5B6BE8), Color(0xFF2F3A9E))
    val reminders = AppTint(Color(0xFFFF9EC4), Color(0xFFE2547F), Color(0xFFA01E57))
    val papers = AppTint(Color(0xFFF7C9A8), Color(0xFFC27A4A), Color(0xFF6B3B1A))
    val renewals = AppTint(Color(0xFF8FC2FF), Color(0xFF3D7FD6), Color(0xFF1D4F94))
    val focus = AppTint(Color(0xFFC7B6FF), Color(0xFF5B45C9), Color(0xFF33228A))
    val practice = AppTint(Color(0xFFFFD3B5), Color(0xFFFF8E6B), Color(0xFF8E2713))
    val people = AppTint(Color(0xFF9CC8F5), Color(0xFF2F7DC0), Color(0xFF1B4F6E))
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun jakarta(weight: Int) = Font(
    R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Jakarta = FontFamily(jakarta(400), jakarta(500), jakarta(600), jakarta(700), jakarta(800))

object Type {
    val display = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(800), fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em)
    val title = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(800), fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.01).em)
    val big = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(800), fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-0.03).em)
    val lead = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(700), fontSize = 19.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em)
    val body = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(500), fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(800), fontSize = 15.sp, lineHeight = 21.sp)
    val small = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 18.sp)
    val smallStrong = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(800), fontSize = 13.sp, lineHeight = 18.sp)
    val tiny = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(700), fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
fun CueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Cue.primaryDeep, onPrimary = Color.White, background = Cue.base, surface = Color.White, onSurface = Cue.ink),
        typography = MaterialTheme.typography.copy(bodyLarge = Type.body, bodyMedium = Type.body, titleLarge = Type.title, labelLarge = Type.bodyStrong),
        content = content,
    )
}
