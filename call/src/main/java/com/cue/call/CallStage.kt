package com.cue.call

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.cue.agent.CallCard
import com.cue.ui.Cue
import com.cue.ui.Txt
import com.cue.ui.Type
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * The call as a stage: Cue fills the screen, the conversation reads like live captions, and whatever Cue pulls up
 * (a list, reminders, habits) sits in its own glass panel that updates while you talk.
 */

object Stage {
    val deep = Color(0xFF07061A)
    val deepNight = Color(0xFF0A0614)
    val violet = Color(0xFF7C5CFF)
    val cyan = Color(0xFF4FD8F7)
    val magenta = Color(0xFFE0458F)
    val text = Color(0xFFF2F0FF)
    val dim = Color(0xFFA7A3C7)
    val glass = Color.White.copy(alpha = 0.07f)
    val glassEdge = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.04f)))
    val accent = Brush.linearGradient(listOf(violet, cyan))
    val caps = Type.tiny.copy(letterSpacing = TextUnit(1.6f, TextUnitType.Sp))
}

/**
 * The full-screen backdrop: drifting aurora, a faint star field, a horizon grid, and sonar rings around [orb] that
 * swell with the voice [level] while someone is talking.
 */
@Composable
fun StageBackdrop(level: Float, active: Boolean, night: Boolean, orb: Offset, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "stage")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "drift")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "ring")
    val lv by animateFloatAsState(level, tween(140), label = "lv")
    val stars = remember { val r = java.util.Random(7); List(70) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } }
    Box(
        modifier.fillMaxSize().drawBehind {
            val w = size.width; val h = size.height
            drawRect(Brush.verticalGradient(listOf(if (night) Stage.deepNight else Stage.deep, Color(0xFF140E33), if (night) Color(0xFF1A0B22) else Color(0xFF0D1030))))
            val a = drift * 2 * PI.toFloat()
            fun blob(cx: Float, cy: Float, r: Float, c: Color) = drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r), r, Offset(cx, cy))
            blob(w * (0.22f + 0.12f * cos(a)), h * (0.20f + 0.05f * sin(a)), w * 0.85f, Stage.violet.copy(alpha = 0.42f + 0.2f * lv))
            blob(w * (0.85f + 0.08f * sin(a * 1.3f)), h * (0.38f + 0.07f * cos(a)), w * 0.7f, (if (night) Stage.magenta else Stage.cyan).copy(alpha = 0.22f + 0.15f * lv))
            blob(w * (0.5f + 0.18f * sin(a * 0.7f)), h * 0.92f, w * 0.95f, Stage.magenta.copy(alpha = 0.20f))
            stars.forEach { (x, y, p) ->
                val tw = 0.25f + 0.75f * abs(sin((drift * 5 + p) * PI.toFloat()))
                drawCircle(Color.White.copy(alpha = 0.45f * tw), (0.6f + p) * density, Offset(x * w, y * h * 0.75f))
            }
            // A faint perspective grid on the floor.
            val horizon = h * 0.78f
            val gridC = Stage.cyan.copy(alpha = 0.07f)
            for (i in 0..7) { val f = i / 7f; val y = horizon + (h - horizon) * f * f; drawLine(gridC, Offset(0f, y), Offset(w, y), 1f) }
            for (i in -6..6) { drawLine(gridC, Offset(w / 2 + i * w * 0.04f, horizon), Offset(w / 2 + i * w * 0.32f, h), 1f) }
            // Sonar rings around Cue while voices flow.
            if (orb != Offset.Unspecified && active) repeat(3) { i ->
                val f = (ring + i / 3f) % 1f
                drawCircle(Stage.cyan.copy(alpha = (1 - f) * (0.18f + 0.5f * lv)), radius = w * 0.17f + f * w * 0.42f, center = orb, style = Stroke(1.4f * density))
            }
            if (orb != Offset.Unspecified) drawCircle(Brush.radialGradient(listOf(Stage.violet.copy(alpha = 0.35f + 0.35f * lv), Color.Transparent), center = orb, radius = w * 0.32f), w * 0.32f, orb)
        },
        content = content,
    )
}

/** A frosted round button. */
@Composable
fun GlassButton(icon: ImageVector, label: String, active: Boolean = false, size: Dp = 58.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (active) Color.White.copy(alpha = 0.22f) else Stage.glass)
            .border(1.dp, Stage.glassEdge, CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (active) Color.White else Stage.text, modifier = Modifier.size(22.dp)) }
}

/** "● LISTENING  0:27" — the call's state, small and spaced like an instrument readout. */
@Composable
fun StageReadout(state: String, seconds: Int, brain: String, live: Boolean) {
    val blink by rememberInfiniteTransition(label = "dot").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "b")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background((if (live) Stage.cyan else Stage.dim).copy(alpha = if (live) blink else 1f)))
        Spacer(Modifier.width(8.dp))
        Txt(state.uppercase(), Stage.caps, Stage.text)
        Spacer(Modifier.width(10.dp))
        if (seconds >= 0) Txt("${seconds / 60}:${"%02d".format(seconds % 60)}", Stage.caps, Stage.dim)
        if (brain.isNotEmpty()) { Spacer(Modifier.width(10.dp)); Txt(brain.uppercase(), Stage.caps, Stage.dim, maxLines = 1) }
    }
}

/**
 * The conversation as captions: the line before, small and dim; the line now, large, following the voice word by
 * word. What the user is still saying shows live with a cursor.
 */
@Composable
fun Captions(previous: Line?, current: Line?, partial: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val showPrev = previous != null && previous.text.isNotBlank() && partial.isBlank()
        if (showPrev) Txt((if (previous!!.who == "user") "You  ·  " else "") + previous.text, Type.small, Stage.dim, maxLines = 2, modifier = Modifier.fillMaxWidth())
        if (partial.isNotBlank()) {
            Text(partial + " ▍", style = (if (compact) Type.body else Type.lead).copy(color = Stage.text.copy(alpha = 0.75f)), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        } else if (current != null && current.text.isNotBlank()) {
            AnimatedContent(current, transitionSpec = { (fadeIn(tween(250)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(150)) }, label = "cap") { l ->
                val style = if (l.who == "user") Type.body.copy(color = Stage.dim) else (if (compact) Type.lead else Type.title.copy(fontWeight = FontWeight(700), fontSize = TextUnit(23f, TextUnitType.Sp), lineHeight = TextUnit(30f, TextUnitType.Sp)))
                val txt = if (l.who == "user") AnnotatedString("You  ·  " + l.text) else if (l.spoken >= 0) karaoke(l.text, l.spoken) else AnnotatedString(l.text)
                Text(txt, style = style.copy(color = if (l.who == "user") Stage.dim else Stage.text), maxLines = if (compact) 3 else 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun karaoke(text: String, spoken: Int): AnnotatedString {
    val at = spoken.coerceIn(0, text.length)
    val wordEnd = text.indexOf(' ', at).let { if (it < 0) text.length else it }
    return buildAnnotatedString {
        withStyle(SpanStyle(color = Stage.text)) { append(text.substring(0, at)) }
        withStyle(SpanStyle(color = Stage.cyan)) { append(text.substring(at, wordEnd)) }
        withStyle(SpanStyle(color = Stage.text.copy(alpha = 0.32f))) { append(text.substring(wordEnd)) }
    }
}

/** One thing Cue did, shown for a few seconds under the captions: "✓ Ticked off · Charger". */
data class Act(val stamp: Long, val label: String, val detail: String, val ok: Boolean)

@Composable
fun ActivityTicker(acts: List<Act>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        acts.takeLast(2).forEach { a ->
            key(a.stamp) {
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                AnimatedVisibility(shown, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Row(Modifier.clip(CircleShape).background(Stage.glass).border(1.dp, Stage.glassEdge, CircleShape).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(if (a.ok) Stage.accent else Brush.linearGradient(listOf(Stage.dim, Stage.dim))), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Txt(a.label, Type.smallStrong, Stage.text, maxLines = 1)
                        if (a.detail.isNotBlank()) Txt("  ·  " + a.detail, Type.small, Stage.dim, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

/** The tab name and icon for a card in the live panel. */
fun cardLook(c: CallCard): Pair<String, ImageVector> = when (c) {
    is CallCard.Checklist -> "List" to Icons.Rounded.Checklist
    CallCard.Habits -> "Habits" to Icons.Rounded.LocalFireDepartment
    is CallCard.Journal -> "Journal" to Icons.Rounded.AutoStories
    CallCard.Reminders -> "Reminders" to Icons.Rounded.NotificationsActive
    is CallCard.Found -> "Found" to Icons.Rounded.Description
    is CallCard.People -> "People" to Icons.Rounded.People
    CallCard.Lists -> "Lists" to Icons.Rounded.Checklist
    CallCard.Albums -> "Spaces" to Icons.Rounded.PhotoLibrary
    CallCard.Renewals -> "Renewals" to Icons.Rounded.Autorenew
    CallCard.Focus -> "Focus" to Icons.Rounded.Timer
}

/**
 * Everything Cue has put on screen this call, in a glass panel of its own: one tab per card, the newest in front,
 * live (it reloads after every turn, so a tick by voice shows up at once). Folds down to a single line.
 */
@Composable
fun LivePanel(cards: List<CallCard>, selected: Int, open: Boolean, version: Int, onSelect: (Int) -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val card = cards.getOrNull(selected) ?: return
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(version) { glow.snapTo(1f); glow.animateTo(0f, tween(1400)) }
    val flash = glow.value
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF15122E).copy(alpha = 0.78f))
            .border(1.dp, Brush.linearGradient(listOf(Stage.violet.copy(alpha = 0.35f + 0.3f * flash), Stage.cyan.copy(alpha = 0.15f + 0.3f * flash), Color.White.copy(alpha = 0.05f))), RoundedCornerShape(28.dp))
            .padding(top = 10.dp, bottom = if (open) 6.dp else 10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                cards.forEachIndexed { i, c ->
                    val (label, icon) = cardLook(c)
                    val sel = i == selected
                    Row(
                        Modifier.clip(CircleShape).background(if (sel) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable(role = Role.Tab) { onSelect(i) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = if (sel) Stage.cyan else Stage.dim, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Txt(label, Type.smallStrong, if (sel) Stage.text else Stage.dim, maxLines = 1)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Stage.cyan.copy(alpha = pulse)))
                Spacer(Modifier.width(5.dp))
                Txt("LIVE", Stage.caps, Stage.cyan)
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = if (open) "Fold the panel" else "Open the panel", onClick = onToggle), contentAlignment = Alignment.Center) {
                Icon(if (open) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp, null, tint = Stage.text, modifier = Modifier.size(22.dp))
            }
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Box(Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(androidx.compose.runtime.key(selected) { rememberScrollState() }).padding(horizontal = 6.dp)) {
                AnimatedContent(selected to card, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "card") { (_, c) ->
                    CallCardView(c, version, dark = true)
                }
            }
        }
    }
}
