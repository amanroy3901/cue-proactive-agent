package com.cue.genui

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cue.ui.*

/*
 * Components for answers that think, not just look things up: an insight with its reasons, a plan as a timeline,
 * numbers as bars or a ring, options side by side with Cue's pick. The model fills them with what it worked out;
 * live data still comes from the components in CueComponents.kt.
 */

/** Cue's "thinking" accent: violet into cyan, used wherever the answer is Cue's own reasoning. */
private val thinkBrush = Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFF4FC3F7)))

private fun tone(t: String?): Triple<androidx.compose.ui.graphics.vector.ImageVector, Color, String> = when (t) {
    "risk" -> Triple(Icons.Rounded.WarningAmber, Color(0xFFE5484D), "Risk")
    "heads_up" -> Triple(Icons.Rounded.WarningAmber, Color(0xFFF08A00), "Heads-up")
    "good" -> Triple(Icons.Rounded.CheckCircle, Color(0xFF14A37F), "On track")
    else -> Triple(Icons.Rounded.Lightbulb, Color(0xFF6C4FD6), "Idea")
}

/** An insight: what Cue concluded, why, and the reasons as short points. */
object CueInsightComponent : A2uiComponent {
    private val title = A2uiProperty.string("title", required = true, description = "The conclusion in a few words, like 'Book the cab tonight'.")
    private val body = A2uiProperty.string("body", description = "One or two sentences: why it matters for this user.")
    private val points = A2uiProperty.stringList("points", required = false, description = "2 to 4 short reasons or facts behind it, from the user's data.")
    private val toneP = A2uiProperty.stringEnum("tone", enumValues = listOf("idea", "good", "heads_up", "risk"), description = "risk = act now, heads_up = worth knowing, good = on track, idea = a suggestion.")
    override val name = "CueInsight"
    override val description = "Cue's own conclusion with its reasons: use for the main point of a thoughtful answer (a risk, a clash, a recommendation, a pattern). Lead with it."
    override val properties = listOf(title, body, points, toneP)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val (icon, color, label) = tone(properties[toneP])
        Column(
            modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White)
                .border(1.dp, Brush.linearGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.08f))), RoundedCornerShape(24.dp))
                .drawBehind { drawRect(Brush.radialGradient(listOf(color.copy(alpha = 0.12f), Color.Transparent), center = Offset(0f, 0f), radius = size.width * 0.9f)) }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Txt(label.uppercase(), Type.tiny.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(1.4f, androidx.compose.ui.unit.TextUnitType.Sp)), color)
            }
            Txt(properties[title] ?: "", Type.lead, Cue.ink)
            properties[body]?.takeIf { it.isNotBlank() }?.let { Txt(it, Type.body, Cue.muted) }
            properties[points].orEmpty().filter { it.isNotBlank() }.forEach { p ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(10.dp))
                    Txt(p, Type.small, Cue.ink, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** A plan as steps in time: what's done, what's now, what's next. */
object CueTimelineComponent : A2uiComponent {
    private val time = A2uiProperty.string("time", description = "When, short: '7:30 AM', 'Sat', 'Day 2'.")
    private val stepTitle = A2uiProperty.string("title", required = true, description = "The step, a few words.")
    private val note = A2uiProperty.string("note", description = "Optional detail or why.")
    private val state = A2uiProperty.stringEnum("state", enumValues = listOf("done", "now", "next"), description = "done, now (the current or most urgent step) or next.")
    private val title = A2uiProperty.string("title", description = "Heading above the timeline, like 'Sunday, flight day'.")
    private val steps = A2uiProperty.nestedList("steps", listOf(time, stepTitle, note, state), required = true, description = "3 to 8 steps in order.")
    override val name = "CueTimeline"
    override val description = "A plan or schedule as a vertical timeline (a trip day, a countdown to an event, a routine). Mark the current step 'now'."
    override val properties = listOf(title, steps)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val list = properties[steps].orEmpty()
        LiftCard(modifier.fillMaxWidth(), tint = Cue.primaryDeep, radius = 24.dp, padding = 16.dp) {
            properties[title]?.takeIf { it.isNotBlank() }?.let { Txt(it, Type.bodyStrong); Spacer(Modifier.height(10.dp)) }
            list.forEachIndexed { i, s ->
                val st = s[state] ?: "next"
                val last = i == list.lastIndex
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Txt(s[time].orEmpty(), Type.tiny, if (st == "now") Cue.primaryDeep else Cue.muted, modifier = Modifier.width(62.dp).padding(top = 2.dp), maxLines = 2)
                    Box(Modifier.width(22.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        if (!last) Box(Modifier.padding(top = 14.dp).width(2.dp).fillMaxHeight().background(if (st == "done") Cue.primary.copy(alpha = 0.5f) else Cue.line))
                        Box(
                            Modifier.padding(top = 3.dp).size(if (st == "now") 14.dp else 12.dp).clip(CircleShape)
                                .background(when (st) { "done" -> Cue.primary; "now" -> Color.White; else -> Color.White })
                                .border(if (st == "now") 3.dp else 2.dp, if (st == "next") Cue.line else Cue.primary, CircleShape),
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp, bottom = if (last) 0.dp else 14.dp)) {
                        Txt(s[stepTitle].orEmpty(), if (st == "now") Type.bodyStrong else Type.body, if (st == "done") Cue.muted else Cue.ink)
                        s[note]?.takeIf { it.isNotBlank() }?.let { Txt(it, Type.small, Cue.muted) }
                    }
                }
            }
        }
    }
}

/** Numbers compared as bars: spending by category, a habit by week, time per task. */
object CueBarsComponent : A2uiComponent {
    private val title = A2uiProperty.string("title", required = true, description = "What is measured, like 'Delhi trip spending'.")
    private val labels = A2uiProperty.stringList("labels", required = true, description = "One short label per bar.")
    private val values = A2uiProperty.numberList("values", required = true, description = "One number per label, same order.")
    private val unit = A2uiProperty.string("unit", description = "Prefix like '₹' or suffix like ' min'. Prefix when it starts with a symbol.")
    private val caption = A2uiProperty.string("caption", description = "Optional one-line takeaway under the bars.")
    override val name = "CueBars"
    override val description = "A small horizontal bar chart comparing 2 to 6 numbers. Only use numbers from the user's data."
    override val properties = listOf(title, labels, values, unit, caption)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val ls = properties[labels].orEmpty()
        val vs = properties[values].orEmpty().map { it.toDouble() }
        val max = (vs.maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
        val u = properties[unit].orEmpty()
        val grow = remember { Animatable(0f) }
        LaunchedEffect(vs) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        LiftCard(modifier.fillMaxWidth(), tint = Cue.primaryDeep, radius = 24.dp, padding = 16.dp) {
            Txt(properties[title] ?: "", Type.bodyStrong)
            Spacer(Modifier.height(10.dp))
            ls.zip(vs).forEachIndexed { i, (l, v) ->
                val shown = (if (v % 1.0 == 0.0) "%,d".format(v.toLong()) else "%,.1f".format(v)).let { if (u.isNotEmpty() && !u.first().isLetterOrDigit() && !u.startsWith(" ")) u + it else it + u }
                Column(Modifier.padding(bottom = 10.dp)) {
                    Row { Txt(l, Type.small, Cue.ink, modifier = Modifier.weight(1f), maxLines = 1); Txt(shown, Type.smallStrong, Cue.ink) }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Cue.primaryTint)) {
                        Box(Modifier.fillMaxWidth((v / max).toFloat() * grow.value).fillMaxHeight().clip(CircleShape)
                            .background(if (v == vs.max()) thinkBrush else Brush.linearGradient(listOf(Cue.primaryLight, Cue.primary))))
                    }
                }
            }
            properties[caption]?.takeIf { it.isNotBlank() }?.let { Txt(it, Type.small, Cue.muted) }
        }
    }
}

/** How far along something is, as a ring: budget used, packing done, days into a course. */
object CueProgressComponent : A2uiComponent {
    private val label = A2uiProperty.string("label", required = true, description = "What it measures, like 'Packed'.")
    private val value = A2uiProperty.number("value", required = true, description = "Done so far.")
    private val max = A2uiProperty.number("max", required = true, description = "The whole.")
    private val caption = A2uiProperty.string("caption", description = "Short line like '3 of 5 items' or '₹1,840 of ₹25,000'.")
    override val name = "CueProgress"
    override val description = "A progress ring with a label. Put two in a Row to compare (packing done, budget used)."
    override val properties = listOf(label, value, max, caption)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val v = properties[value]?.toFloat() ?: 0f
        val m = (properties[max]?.toFloat() ?: 1f).coerceAtLeast(0.0001f)
        val frac = (v / m).coerceIn(0f, 1f)
        val anim = remember { Animatable(0f) }
        LaunchedEffect(frac) { anim.animateTo(frac, tween(1000, easing = FastOutSlowInEasing)) }
        // Narrow (half a row): the ring above its words; wide: side by side.
        BoxWithConstraints(modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).border(1.dp, Cue.line, RoundedCornerShape(22.dp)).padding(12.dp)) {
        val narrow = maxWidth < 190.dp
        val ring: @Composable () -> Unit = {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 6.dp.toPx()
                    val r = Size(size.width - sw, size.height - sw)
                    drawArc(Cue.primaryTint, 0f, 360f, false, Offset(sw / 2, sw / 2), r, style = Stroke(sw))
                    drawArc(thinkBrush, -90f, 360f * anim.value, false, Offset(sw / 2, sw / 2), r, style = Stroke(sw, cap = StrokeCap.Round))
                }
                Txt("${(frac * 100).toInt()}%", Type.tiny, Cue.ink)
            }
        }
        val words: @Composable () -> Unit = {
            Column {
                Txt(properties[label] ?: "", Type.smallStrong, Cue.ink, maxLines = 2)
                properties[caption]?.takeIf { it.isNotBlank() }?.let { Txt(it, Type.tiny, Cue.muted, maxLines = 3) }
            }
        }
        if (narrow) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ring(); words() }
        else Row(verticalAlignment = Alignment.CenterVertically) { ring(); Spacer(Modifier.width(10.dp)); words() }
        }
    }
}

/** Options side by side, with the one Cue would pick marked. */
object CueCompareComponent : A2uiComponent {
    private val optName = A2uiProperty.string("name", required = true, description = "The option, a few words.")
    private val good = A2uiProperty.string("good", description = "Its main upside, short.")
    private val bad = A2uiProperty.string("bad", description = "Its main downside, short.")
    private val pick = A2uiProperty.boolean("pick", description = "True for the one Cue recommends (only one).")
    private val title = A2uiProperty.string("title", description = "The decision, like 'Getting to the airport'.")
    private val options = A2uiProperty.nestedList("options", listOf(optName, good, bad, pick), required = true, description = "2 or 3 options.")
    override val name = "CueCompare"
    override val description = "A decision: 2 or 3 options with an upside and downside each, and Cue's pick marked. Use when the user must choose."
    override val properties = listOf(title, options)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            properties[title]?.takeIf { it.isNotBlank() }?.let { Txt(it, Type.bodyStrong) }
            properties[options].orEmpty().forEach { o ->
                val picked = o[pick] == true
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (picked) Color(0xFFF3F0FF) else Color.White)
                        .border(if (picked) 1.5.dp else 1.dp, if (picked) thinkBrush else Brush.linearGradient(listOf(Cue.line, Cue.line)), RoundedCornerShape(20.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(o[optName].orEmpty(), Type.bodyStrong, modifier = Modifier.weight(1f))
                        if (picked) Row(Modifier.clip(CircleShape).background(thinkBrush).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
                            Txt("Cue's pick", Type.tiny, Color.White)
                        }
                    }
                    o[good]?.takeIf { it.isNotBlank() }?.let { Txt("+ $it", Type.small, Color(0xFF0E7A5F)) }
                    o[bad]?.takeIf { it.isNotBlank() }?.let { Txt("– $it", Type.small, Color(0xFFB4232A)) }
                }
            }
        }
    }
}

val thinkComponents: List<A2uiComponent> = listOf(CueInsightComponent, CueTimelineComponent, CueBarsComponent, CueProgressComponent, CueCompareComponent)
