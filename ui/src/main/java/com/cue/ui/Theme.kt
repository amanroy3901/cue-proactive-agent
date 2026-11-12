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
 * Bloom with depth. Colour roles: deep teal is Cue itself (trust, the agent, primary actions); coral and apricot are
 * anything time-sensitive; warm cream is the base; each app has one colour so it is recognised before it is read.
 */
object Cue {
    val cream = Color(0xFFF7F3EC)
    val ink = Color(0xFF14201F)
    val muted = Color(0xFF5D6A68)
    val line = Color(0xFFEEEAE3)
    val white = Color.White
    val tealLight = Color(0xFF2A9A90)
    val teal = Color(0xFF1A7A72)
    val tealDeep = Color(0xFF0D4B48)
    val tealNight = Color(0xFF0A3533)
    val tealTint = Color(0xFFD6F0EA)
    val coral = Color(0xFFFF7A59)
    val coralDeep = Color(0xFFE0563A)
    val coralText = Color(0xFFB23E20)
    val coralTint = Color(0xFFFFE6DE)
    val apricot = Color(0xFFFFB49C)
    val night = Color(0xFF0B1B1C)
    val nightText = Color(0xFFF2EFE9)
    val nightMuted = Color(0xFFA9B8B5)

    val tealBrush = Brush.linearGradient(listOf(Color(0xFF1A7A72), Color(0xFF0D4B48), Color(0xFF0A3533)))
    val tealButton = Brush.verticalGradient(listOf(Color(0xFF1E857C), Color(0xFF0D4B48)))
    val coralButton = Brush.verticalGradient(listOf(Color(0xFFFF8E6B), Color(0xFFE0563A)))
    val sunrise = Brush.verticalGradient(listOf(Color(0xFFFFF4EA), Color(0xFFFFD2BC), Color(0xFFFF9E80)))
    val tripBrush = Brush.linearGradient(listOf(Color(0xFF7484E8), Color(0xFF4D5DCB), Color(0xFF35419C)))
    val journalBrush = Brush.linearGradient(listOf(Color(0xFFE2683F), Color(0xFFC24A2E), Color(0xFF9E3622)))
    val marigoldCard = Brush.linearGradient(listOf(Color(0xFFFFF0C7), Color(0xFFFFD98A)))
}

/** One colour per app. */
data class AppTint(val light: Color, val deep: Color, val text: Color)

object AppTints {
    val lists = AppTint(Color(0xFF2A9A90), Color(0xFF0D4B48), Color(0xFF0D4B48))
    val habits = AppTint(Color(0xFFFFC861), Color(0xFFF08A00), Color(0xFF4E3800))
    val journal = AppTint(Color(0xFFFFB49C), Color(0xFFF0663F), Color(0xFF8E2713))
    val albums = AppTint(Color(0xFFB9C6FF), Color(0xFF6C7FE8), Color(0xFF35419C))
    val reminders = AppTint(Color(0xFFFFB3CB), Color(0xFFE2547F), Color(0xFF851B57))
    val papers = AppTint(Color(0xFFEBD3A6), Color(0xFFB8894A), Color(0xFF54441F))
    val renewals = AppTint(Color(0xFFA8E6CC), Color(0xFF3E9E7A), Color(0xFF21694F))
    val focus = AppTint(Color(0xFFD2C2FF), Color(0xFF8466DB), Color(0xFF4A2FA0))
    val practice = AppTint(Color(0xFFFFD3B5), Color(0xFFFF8E6B), Color(0xFF8E2713))
    val people = AppTint(Color(0xFFC6E6F5), Color(0xFF3D8DB8), Color(0xFF1B4F6E))
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
        colorScheme = lightColorScheme(primary = Cue.tealDeep, onPrimary = Color.White, background = Cue.cream, surface = Color.White, onSurface = Cue.ink),
        typography = MaterialTheme.typography.copy(bodyLarge = Type.body, bodyMedium = Type.body, titleLarge = Type.title, labelLarge = Type.bodyStrong),
        content = content,
    )
}
