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
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
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
 * The conversation over Cue, the way a voice assistant shows it: the newest line at the bottom, large; older lines
 * drift up and fade out. Cue's line follows the voice word by word; what the user is still saying shows with a cursor.
 */
@Composable
fun ConversationOverlay(lines: List<Line>, partial: String, modifier: Modifier = Modifier, maxHeight: Dp = 300.dp) {
    val shown = lines.takeLast(5)
    Box(
        modifier.fillMaxWidth().heightIn(max = maxHeight).clipToBounds()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true).padding(horizontal = 26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val total = shown.size + if (partial.isNotBlank()) 1 else 0
            shown.forEachIndexed { i, l ->
                val age = total - 1 - i
                androidx.compose.runtime.key(l) {
                    var seen by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { seen = true }
                    AnimatedVisibility(seen, enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 }) {
                        OverlayLine(l, (if (age == 0) 1f else if (age == 1) 0.8f else 0.5f))
                    }
                }
            }
            if (partial.isNotBlank()) Text("$partial ▍", style = Type.lead.copy(color = Stage.text.copy(alpha = 0.7f)), modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun OverlayLine(l: Line, alpha: Float) {
    if (l.who == "user") {
        Text(l.text, style = Type.lead.copy(color = Stage.dim.copy(alpha = alpha), fontWeight = FontWeight(600)), modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    } else {
        val style = Type.title.copy(fontWeight = FontWeight(700), fontSize = TextUnit(22f, TextUnitType.Sp), lineHeight = TextUnit(29f, TextUnitType.Sp), color = Stage.text.copy(alpha = alpha))
        Text(if (l.spoken >= 0) karaoke(l.text, l.spoken) else AnnotatedString(l.text), style = style, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The board's handle on the right edge: one glass button per card Cue has put up this call. The one that just
 * changed pulses. Tap to open the board.
 */
@Composable
fun BoardRail(cards: List<CallCard>, updated: Int, stamp: Long, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "rail").animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    var fresh by remember { mutableStateOf(false) }
    LaunchedEffect(stamp) { fresh = true; kotlinx.coroutines.delay(5000); fresh = false }
    Column(modifier.clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)).background(Color(0xFF15122E).copy(alpha = 0.72f))
        .border(1.dp, Stage.glassEdge, RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cards.forEachIndexed { i, c ->
            val (label, icon) = cardLook(c)
            val hot = fresh && i == updated
            Box(Modifier.size(46.dp).clip(CircleShape).background(if (hot) Stage.accent else Brush.linearGradient(listOf(Stage.glass, Stage.glass)))
                .clickable(role = Role.Button, onClickLabel = "Open $label") { onOpen(i) }, contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
                if (hot) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = pulse)))
            }
        }
    }
}

/** A small note beside the rail when a card changes while the board is closed: "List · updated". */
@Composable
fun BoardPeek(card: CallCard?, stamp: Long, isNew: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(stamp) { if (card != null && stamp > 0) { shown = true; kotlinx.coroutines.delay(4500); shown = false } }
    AnimatedVisibility(shown && card != null, modifier = modifier, enter = fadeIn() + androidx.compose.animation.slideInHorizontally { it / 2 }, exit = fadeOut()) {
        val (label, icon) = cardLook(card!!)
        Row(Modifier.clip(CircleShape).background(Color(0xFF15122E).copy(alpha = 0.85f)).border(1.dp, Stage.accent, CircleShape).clickable(onClick = onOpen)
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Stage.cyan, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Txt(if (isNew) "$label on your board" else "$label updated", Type.smallStrong, Stage.text, maxLines = 1)
        }
    }
}

/**
 * The board: everything Cue has put up this call, in its own sheet that slides over from the right, apart from the
 * conversation. One tab per card, live: it re-reads the data after every turn, so a tick by voice shows at once.
 */
@Composable
fun BoardDrawer(open: Boolean, cards: List<CallCard>, selected: Int, version: Int, onSelect: (Int) -> Unit, onClose: () -> Unit) {
    AnimatedVisibility(open && cards.isNotEmpty(), enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClose))
    }
    AnimatedVisibility(open && cards.isNotEmpty(), modifier = Modifier.fillMaxSize(),
        enter = androidx.compose.animation.slideInHorizontally(tween(320)) { it } + fadeIn(), exit = androidx.compose.animation.slideOutHorizontally(tween(260)) { it } + fadeOut()) {
        val card = cards.getOrNull(selected.coerceIn(0, (cards.size - 1).coerceAtLeast(0))) ?: return@AnimatedVisibility
        val glow = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(version) { glow.snapTo(1f); glow.animateTo(0f, tween(1400)) }
        val pulse by rememberInfiniteTransition(label = "live").animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 64.dp, bottom = 96.dp), contentAlignment = Alignment.TopEnd) {
            Column(
                Modifier.fillMaxWidth(0.9f).wrapContentHeight().clip(RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp)).background(Brush.verticalGradient(listOf(Color(0xFF1A1638), Color(0xFF110E26))))
                    .border(1.dp, Brush.linearGradient(listOf(Stage.violet.copy(alpha = 0.4f + 0.4f * glow.value), Stage.cyan.copy(alpha = 0.15f + 0.4f * glow.value), Color.White.copy(alpha = 0.05f))),
                        RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp))
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                    .padding(top = 14.dp, bottom = 10.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("BOARD", Stage.caps, Stage.dim)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Stage.cyan.copy(alpha = pulse)))
                    Spacer(Modifier.width(5.dp))
                    Txt("LIVE", Stage.caps, Stage.cyan, modifier = Modifier.weight(1f))
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Close the board", onClick = onClose), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Close", tint = Stage.text, modifier = Modifier.size(22.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cards.forEachIndexed { i, c ->
                        val (label, icon) = cardLook(c)
                        val sel = c == card
                        Row(Modifier.clip(CircleShape).background(if (sel) Color.White.copy(alpha = 0.14f) else Color.Transparent).clickable(role = Role.Tab) { onSelect(i) }
                            .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = if (sel) Stage.cyan else Stage.dim, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp))
                            Txt(label, Type.smallStrong, if (sel) Stage.text else Stage.dim, maxLines = 1)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(androidx.compose.runtime.key(selected) { rememberScrollState() }).padding(horizontal = 8.dp)) {
                    AnimatedContent(card, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "card") { c -> CallCardView(c, version, dark = true) }
                }
            }
        }
    }
}
