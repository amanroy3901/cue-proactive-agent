package com.cue.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pixel, Cue's character: a glossy toy robot drawn in a 240 x 240 box and scaled to [size].
 * Arms are two-bone IK chains (shoulder, elbow, wrist); every mood is a list of hand targets over time.
 * Design source: the "Pixel, final" canvas (Take 6 reference).
 */
enum class PixelMood {
    Idle, Hello, Ringing, RingingNight, Connecting, Listening, Thinking, Speaking,
    Ticking, Writing, Filing, Reminder, Finding, Habit, Celebrate, Sleeping, HoldPass, Offline,
}

/** Which mood a call card (tool result) should flash for about two seconds. */
fun pixelMoodForTool(name: String): PixelMood = when (name) {
    "checklist", "lists" -> PixelMood.Ticking
    "journal" -> PixelMood.Writing
    "albums", "note" -> PixelMood.Filing
    "reminders", "renewals" -> PixelMood.Reminder
    "found", "people" -> PixelMood.Finding
    "habits", "focus" -> PixelMood.Habit
    else -> PixelMood.Celebrate
}

private val CY = Color(0xFF8BEFFF)
private val MOUTH = Color(0xFFC77DFF)
private val LINE = Color(0xFFBFB0E6)
private val NAVY = Color(0xFF1E1A4A)
private val PACK = Color(0xFF2A2260)
private val CLOTH = Color(0xFF7B4FE0)
private val SOLE = Color(0xFF6A3FD8)
private val RED = Color(0xFFF2454B)
private val PEARL = listOf(Color.White, Color(0xFFF6F3FD), Color(0xFFDCD3F2), Color(0xFFC2B4E6))

private val SR = Offset(142f, 154f)
private val SL = Offset(98f, 154f)
private const val U = 26f
private const val F = 24f
private const val AW = 17f
private const val HR = 10.5f

private enum class Bend { DOWN, OUT }
private enum class Body { BREATHE, BOUNCE, HOP, SWAY, STILL }
private enum class Eyes { HAPPY, ROUND, UP, WIDE, CLOSED, CALM, DOTS, DETERMINED, UNPLUG, LOOK, SIP }
private enum class Mouth { SMILE, OPEN, O, TALK, LEVEL, NONE }
private enum class Prop { NONE, PHONE, LANTERN, PEN, CLIPBOARD, STICKY, ALBUM, CLOCK, MAGNIFIER, DOC, TORCH, PLUG }

/** A hand key: a target the hand reaches (solved with IK) or explicit upper/forearm angles. h = absolute hand angle (keeps props upright). */
private class Key(val at: Float, val x: Float = 0f, val y: Float = 0f, val h: Float? = null, val bend: Bend = Bend.DOWN, val a1: Float? = null, val af: Float? = null)

private class Pose(
    val dur: Int,
    val r: List<Key>, val l: List<Key>,
    val head: List<Pair<Float, Float>> = listOf(0f to 0f),
    val body: Body = Body.BREATHE,
    val eyes: Eyes = Eyes.HAPPY, val mouth: Mouth = Mouth.SMILE,
    val propR: Prop = Prop.NONE, val propRH: Float = 0f,
    val propL: Prop = Prop.NONE, val propLH: Float = 0f,
    val openR: Boolean = false, val openL: Boolean = false,
    val sit: Boolean = false, val night: Boolean = false, val nightcap: Boolean = false,
    val gears: Boolean = false, val zz: Boolean = false, val sparkles: Boolean = false,
    val pass: Boolean = false, val droop: Boolean = false, val fastEars: Boolean = false,
)

private fun restR() = Key(0f, 150f, 198f, bend = Bend.OUT)
private fun restL() = Key(0f, 90f, 198f, bend = Bend.OUT)
private fun k(at: Float, x: Float, y: Float, h: Float? = null, bend: Bend = Bend.DOWN) = Key(at, x, y, h, bend)
private fun waveR(at: Float, af: Float) = Key(at, a1 = -108f, af = af)
private fun waveL(at: Float, af: Float) = Key(at, a1 = 108f, af = af)
private fun at(at: Float, key: Key) = Key(at, key.x, key.y, key.h, key.bend, key.a1, key.af)

private fun pose(m: PixelMood): Pose = when (m) {
    PixelMood.Idle -> Pose(7000,
        listOf(at(0f, restR()), at(.46f, restR()), waveR(.54f, -158f), waveR(.6f, -140f), waveR(.66f, -178f), waveR(.72f, -142f), waveR(.78f, -176f), waveR(.86f, -156f), at(.94f, restR()), at(1f, restR())),
        listOf(at(0f, restL()), k(.5f, 88f, 196f, bend = Bend.OUT), at(1f, restL())),
        head = listOf(0f to 0f, .5f to 0f, .58f to 5f, .86f to 5f, .94f to 0f, 1f to 0f), eyes = Eyes.LOOK, openR = true)
    PixelMood.Hello -> Pose(1400, listOf(waveR(0f, -145f), waveR(.5f, -185f), waveR(1f, -145f)), listOf(at(0f, restL())),
        head = listOf(0f to 4f, .5f to 7f, 1f to 4f), body = Body.HOP, mouth = Mouth.OPEN, openR = true, fastEars = true)
    PixelMood.Ringing -> Pose(1200,
        listOf(k(0f, 166f, 124f, 172f), k(.25f, 166f, 124f, 188f), k(.5f, 166f, 124f, 172f), k(.75f, 166f, 124f, 188f), k(1f, 166f, 124f, 172f)),
        listOf(waveL(0f, 145f), waveL(.5f, 185f), waveL(1f, 145f)),
        head = listOf(0f to -3f, .5f to -6f, 1f to -3f), body = Body.BOUNCE, mouth = Mouth.OPEN, propR = Prop.PHONE, propRH = 180f, openL = true, fastEars = true)
    PixelMood.RingingNight -> Pose(4000, listOf(k(0f, 166f, 152f, 0f), k(.5f, 168f, 150f, 6f), k(1f, 166f, 152f, 0f)), listOf(at(0f, restL())),
        head = listOf(0f to 3f, .5f to 6f, 1f to 3f), body = Body.SWAY, eyes = Eyes.CALM, propR = Prop.LANTERN, nightcap = true, night = true)
    PixelMood.Connecting -> Pose(3000, listOf(at(0f, restR())), listOf(at(0f, restL())), eyes = Eyes.DOTS, mouth = Mouth.NONE, fastEars = true)
    PixelMood.Listening -> Pose(2600, listOf(at(0f, restR())), listOf(k(0f, 68f, 120f, -200f), k(.5f, 68f, 122f, -200f), k(1f, 68f, 120f, -200f)),
        head = listOf(0f to -6f, .5f to -8f, 1f to -6f), eyes = Eyes.ROUND, mouth = Mouth.LEVEL)
    PixelMood.Thinking -> Pose(2600,
        listOf(k(0f, 128f, 144f, 165f), k(.4f, 128f, 144f, 165f), k(.5f, 129f, 147f, 160f), k(.6f, 128f, 144f, 165f), k(.7f, 129f, 147f, 160f), k(.8f, 128f, 144f, 165f), k(1f, 128f, 144f, 165f)),
        listOf(k(0f, 138f, 174f, -100f)), head = listOf(0f to -5f, .5f to -8f, 1f to -5f), eyes = Eyes.UP, mouth = Mouth.O, gears = true)
    PixelMood.Speaking -> Pose(2200, listOf(k(0f, 172f, 168f), k(.3f, 178f, 154f), k(.6f, 168f, 170f), k(1f, 172f, 168f)), listOf(at(0f, restL())),
        head = listOf(0f to 2f, .4f to -2f, 1f to 2f), mouth = Mouth.TALK, openR = true)
    PixelMood.Ticking -> Pose(1800,
        listOf(k(0f, 84f, 138f, 20f), k(.15f, 88f, 142f, 20f), k(.3f, 96f, 128f, 20f), k(.5f, 84f, 138f, 20f), k(1f, 84f, 138f, 20f)),
        listOf(k(0f, 86f, 166f, -180f)), head = listOf(0f to -4f), mouth = Mouth.OPEN, propR = Prop.PEN, propRH = 20f, propL = Prop.CLIPBOARD, propLH = 180f)
    PixelMood.Writing -> Pose(1600,
        listOf(k(0f, 82f, 136f, 20f), k(.33f, 92f, 134f, 20f), k(.66f, 84f, 142f, 20f), k(1f, 82f, 136f, 20f)),
        listOf(k(0f, 86f, 166f, -180f)), head = listOf(0f to -5f), eyes = Eyes.ROUND, propR = Prop.PEN, propRH = 20f, propL = Prop.CLIPBOARD, propLH = 180f)
    PixelMood.Filing -> Pose(2800,
        listOf(k(0f, 168f, 132f, 180f), k(.2f, 168f, 132f, 180f), k(.6f, 98f, 150f, 180f), k(.75f, 98f, 150f, 180f), k(1f, 168f, 132f, 180f)),
        listOf(k(0f, 86f, 176f, -180f)), head = listOf(0f to 0f, .6f to -5f, 1f to 0f), eyes = Eyes.ROUND, propR = Prop.STICKY, propRH = 180f, propL = Prop.ALBUM, propLH = 180f)
    PixelMood.Reminder -> Pose(2400, listOf(k(0f, 116f, 122f), k(.25f, 120f, 118f), k(.5f, 116f, 114f), k(.75f, 112f, 118f), k(1f, 116f, 122f)),
        listOf(k(0f, 104f, 156f, -180f)), head = listOf(0f to 4f), propL = Prop.CLOCK, propLH = 180f)
    PixelMood.Finding -> Pose(2400, listOf(k(0f, 98f, 156f, 160f), k(.35f, 104f, 144f, 160f), k(.7f, 94f, 140f, 160f), k(1f, 98f, 156f, 160f)),
        listOf(k(0f, 84f, 168f, -180f)), head = listOf(0f to -5f), eyes = Eyes.ROUND, mouth = Mouth.O, propR = Prop.MAGNIFIER, propRH = 180f, propL = Prop.DOC, propLH = 180f)
    PixelMood.Habit -> Pose(1600, listOf(k(0f, 166f, 122f, 180f), k(.5f, 166f, 116f, 180f), k(1f, 166f, 122f, 180f)), listOf(at(0f, restL())),
        body = Body.BOUNCE, mouth = Mouth.OPEN, propR = Prop.TORCH, propRH = 180f)
    PixelMood.Celebrate -> Pose(900, listOf(k(0f, 178f, 112f, bend = Bend.OUT), k(.45f, 184f, 100f, bend = Bend.OUT), k(1f, 178f, 112f, bend = Bend.OUT)),
        listOf(k(0f, 62f, 112f, bend = Bend.OUT), k(.45f, 56f, 100f, bend = Bend.OUT), k(1f, 62f, 112f, bend = Bend.OUT)),
        body = Body.HOP, mouth = Mouth.OPEN, sparkles = true, fastEars = true)
    PixelMood.Sleeping -> Pose(4000, listOf(k(0f, 138f, 178f, 160f)), listOf(k(0f, 102f, 178f, -160f)),
        head = listOf(0f to -10f, .5f to -12f, 1f to -10f), eyes = Eyes.CLOSED, mouth = Mouth.NONE, sit = true, zz = true, droop = true, night = true)
    PixelMood.HoldPass -> Pose(3000, listOf(k(0f, 146f, 172f, 160f), k(.5f, 146f, 168f, 160f), k(1f, 146f, 172f, 160f)),
        listOf(k(0f, 94f, 172f, -160f), k(.5f, 94f, 168f, -160f), k(1f, 94f, 172f, -160f)),
        head = listOf(0f to -4f, .5f to 4f, 1f to -4f), mouth = Mouth.OPEN, pass = true)
    PixelMood.Offline -> Pose(3000, listOf(k(0f, 162f, 170f, 0f)), listOf(at(0f, restL())),
        head = listOf(0f to -6f, .5f to -9f, 1f to -6f), eyes = Eyes.UNPLUG, mouth = Mouth.NONE, propR = Prop.PLUG, droop = true)
}

// ------------------------------------------------------------------ IK
private fun ang(dx: Float, dy: Float) = Math.toDegrees(atan2(-dx.toDouble(), dy.toDouble())).toFloat()
private fun sinD(a: Float) = sin(a * PI / 180).toFloat()
private fun cosD(a: Float) = cos(a * PI / 180).toFloat()

private fun ik(s: Offset, tx: Float, ty: Float, bend: Bend, right: Boolean): Pair<Float, Float> {
    val dx = tx - s.x; val dy = ty - s.y
    val d = max(kotlin.math.abs(U - F) + .5f, min(U + F - .3f, hypot(dx, dy)))
    val th = ang(dx, dy)
    val a = Math.toDegrees(acos(((U * U + d * d - F * F) / (2 * U * d)).toDouble())).toFloat()
    val tcx = s.x - d * sinD(th); val tcy = s.y + d * cosD(th)
    var best: Triple<Float, Float, Float>? = null
    for (sg in listOf(1f, -1f)) {
        val t1 = th + sg * a
        val ex = s.x - U * sinD(t1); val ey = s.y + U * cosD(t1)
        val tf = ang(tcx - ex, tcy - ey)
        val score = if (bend == Bend.OUT) (ex - 120f) * (if (right) 1f else -1f) else ey
        if (best == null || score > best.first) best = Triple(score, t1, tf)
    }
    return best!!.second to best.third
}

private fun norm(a: Float): Float { var x = a; while (x > 180f) x -= 360f; while (x <= -180f) x += 360f; return x }
private fun unwrap(prev: Float?, a: Float): Float { var x = norm(a); if (prev == null) return x; while (x - prev > 180f) x -= 360f; while (x - prev < -180f) x += 360f; return x }

/** Angles (upper, forearm relative, wrist relative) for each key, unwrapped so tweening never spins the long way. */
private fun solveKeys(keys: List<Key>, right: Boolean): List<Pair<Float, FloatArray>> {
    val s = if (right) SR else SL
    var prev: FloatArray? = null
    return keys.map { key ->
        val (t1, tf) = if (key.a1 != null) key.a1 to key.af!! else ik(s, key.x, key.y, key.bend, right)
        val raw = floatArrayOf(t1, tf - t1, key.h?.let { it - tf } ?: 0f)
        val p = prev
        val a = FloatArray(3) { i -> unwrap(p?.get(i), raw[i]) }
        prev = a
        key.at to a
    }
}

private fun ease(t: Float) = (1 - cos(t * PI).toFloat()) / 2

private fun sample(frames: List<Pair<Float, FloatArray>>, t: Float): FloatArray {
    if (frames.size == 1) return frames[0].second
    val i = frames.indexOfLast { it.first <= t }.coerceIn(0, frames.size - 2)
    val (t0, a0) = frames[i]; val (t1, a1) = frames[i + 1]
    val f = if (t1 - t0 <= 0f) 0f else ease(((t - t0) / (t1 - t0)).coerceIn(0f, 1f))
    return FloatArray(3) { a0[it] + (a1[it] - a0[it]) * f }
}

private fun sample1(frames: List<Pair<Float, Float>>, t: Float): Float {
    if (frames.size == 1) return frames[0].second
    val i = frames.indexOfLast { it.first <= t }.coerceIn(0, frames.size - 2)
    val (t0, a0) = frames[i]; val (t1, a1) = frames[i + 1]
    val f = if (t1 - t0 <= 0f) 0f else ease(((t - t0) / (t1 - t0)).coerceIn(0f, 1f))
    return a0 + (a1 - a0) * f
}

// ------------------------------------------------------------------ composables
/** The whole character. [level] (0..1) drives the listening bars. Set [animate] false for a still pose. */
@Composable
internal fun PixelCanvas(mood: PixelMood, size: Dp, modifier: Modifier = Modifier, level: Float = 0f, animate: Boolean = true) {
    val p = remember(mood) { pose(mood) }
    val rFrames = remember(mood) { solveKeys(p.r, true) }
    val lFrames = remember(mood) { solveKeys(p.l, false) }
    val clock = remember { mutableLongStateOf(0L) }
    val lvl by rememberUpdatedState(level)
    if (animate) LaunchedEffect(Unit) { val start = withFrameMillis { it }; while (true) withFrameMillis { clock.longValue = it - start } }
    Canvas(modifier.size(size).semantics { contentDescription = "Cue's character, Pixel" }) {
        val ms = clock.longValue
        scale(this.size.width / 240f, pivot = Offset.Zero) { drawPixel(p, rFrames, lFrames, ms, lvl) }
    }
}

/** Head only, for small places (home line, bubble, notifications). */
@Composable
internal fun PixelCanvasHead(size: Dp, modifier: Modifier = Modifier, happy: Boolean = true, animate: Boolean = true) {
    val clock = remember { mutableLongStateOf(0L) }
    if (animate) LaunchedEffect(Unit) { val start = withFrameMillis { it }; while (true) withFrameMillis { clock.longValue = it - start } }
    Canvas(modifier.size(size).semantics { contentDescription = "Cue" }) {
        val ms = clock.longValue
        scale(this.size.width / 150f, pivot = Offset.Zero) {
            translate(-45f, -2f) { drawHead(if (happy) Eyes.HAPPY else Eyes.ROUND, Mouth.SMILE, ms, 0f, droop = false, fastEars = false, nightcap = false) }
        }
    }
}

// ------------------------------------------------------------------ drawing
private fun DrawScope.oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Color, deg: Float = 0f, alpha: Float = 1f) =
    rotate(deg, Offset(cx, cy)) { drawOval(color, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), alpha = alpha) }

private fun DrawScope.ovalB(cx: Float, cy: Float, rx: Float, ry: Float, brush: Brush, deg: Float = 0f, alpha: Float = 1f) =
    rotate(deg, Offset(cx, cy)) { drawOval(brush, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), alpha = alpha) }

private fun DrawScope.ovalS(cx: Float, cy: Float, rx: Float, ry: Float, color: Color, w: Float, alpha: Float = 1f) =
    drawOval(color, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), alpha = alpha, style = Stroke(w))

private fun DrawScope.rr(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color, alpha: Float = 1f) =
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r), alpha = alpha)

private fun DrawScope.rrB(x: Float, y: Float, w: Float, h: Float, r: Float, brush: Brush) = drawRoundRect(brush, Offset(x, y), Size(w, h), CornerRadius(r))

private fun pearl(cx: Float, cy: Float, r: Float) = Brush.radialGradient(0f to PEARL[0], .42f to PEARL[1], .8f to PEARL[2], 1f to PEARL[3], center = Offset(cx, cy), radius = r)

/** A soft glow: the same stroke wider and faint behind. */
private fun DrawScope.glowLine(path: Path, color: Color, w: Float) {
    drawPath(path, color, alpha = .25f, style = Stroke(w * 2.6f, cap = StrokeCap.Round))
    drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round))
}

private fun DrawScope.glowDot(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) {
    oval(cx, cy, rx + 3f, ry + 3f, color, alpha = .22f)
    oval(cx, cy, rx, ry, color)
}

private fun DrawScope.drawPixel(p: Pose, rF: List<Pair<Float, FloatArray>>, lF: List<Pair<Float, FloatArray>>, ms: Long, level: Float) {
    val t = (ms % p.dur).toFloat() / p.dur
    val sec = ms / 1000f
    // aura and platform
    drawCircle(Brush.radialGradient(listOf(Color(0xFF8B5CF6).copy(alpha = if (p.night) .45f else .28f), Color.Transparent), Offset(120f, 112f), 110f), 110f, Offset(120f, 112f))
    if (p.night) drawNightSky(sec)
    ovalB(120f, 220f, 80f, 14f, Brush.radialGradient(listOf(Color(0xFF9C6BFF).copy(alpha = .7f), Color.Transparent), Offset(120f, 220f), 80f))
    ovalS(120f, 220f, 56f, 8f, Color(0xFF9C7BFF), 2.2f)
    ovalS(120f, 220f, 40f, 5f, Color(0xFF7FB8FF), 1.4f, .7f)
    if (p.sparkles) drawSparkles(sec)

    // body motion around the feet
    val (dy, sx, sy, rotB) = when (p.body) {
        Body.BREATHE -> { val w = sin(sec * 2 * PI / 3.4).toFloat(); Quad(0f, 1f + .008f * w, 1f - .008f * w, 0f) }
        Body.BOUNCE -> { val w = ((1 - cos(t * 2 * PI * (p.dur / 700f).coerceAtLeast(1f))) / 2).toFloat(); Quad(-8f * w, 1.03f - .05f * w, .97f + .06f * w, 0f) }
        Body.HOP -> { val w = sin(t * PI).toFloat(); Quad(-15f * w * w, 1f + .04f * (1 - w), 1f - .05f * (1 - w), 0f) }
        Body.SWAY -> Quad(0f, 1f, 1f, 2f * sin(sec * 2 * PI / 3).toFloat())
        Body.STILL -> Quad(0f, 1f, 1f, 0f)
    }
    withTransform({
        translate(0f, dy)
        rotate(rotB, Offset(120f, 216f))
        scale(sx, sy, Offset(120f, 216f))
    }) {
        // backpack, legs, torso
        rr(68f, 150f, 32f, 46f, 13f, PACK); rr(70f, 152f, 28f, 16f, 8f, Color(0xFF4A2D9E))
        if (p.sit) drawSitLegs() else drawLegs()
        drawTorso(sec)
        if (p.pass) drawPass()
        val headTilt = sample1(p.head, t)
        rotate(headTilt, Offset(120f, 142f)) {
            drawHead(p.eyes, p.mouth, ms, level, p.droop, p.fastEars, p.nightcap, t)
        }
        val ar = sample(rF, t); val al = sample(lF, t)
        drawArm(SL, al, false, p.openL, p.propL, p.propLH, ms)
        drawArm(SR, ar, true, p.openR, p.propR, p.propRH, ms)
        if (p.pass) { drawThumbOver(SL, al, false); drawThumbOver(SR, ar, true) }
    }
    if (p.gears) drawGears(sec)
    if (p.zz) drawZz(sec)
}

private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

private fun DrawScope.drawLegs() {
    for (x in listOf(106f, 134f)) {
        val fx = x + if (x < 120f) -3f else 3f
        drawRoundRect(Brush.linearGradient(listOf(Color.White, Color(0xFFDDD4F4)), Offset(x - 9f, 188f), Offset(x + 9f, 210f)), Offset(x - 9.5f, 188f), Size(19f, 22f), CornerRadius(9f))
        oval(fx, 215f, 15.5f, 6.5f, SOLE)
        ovalB(fx, 211.5f, 15.5f, 8f, pearl(fx - 5f, 207f, 20f))
        ovalS(fx, 211.5f, 15.5f, 8f, LINE, 1.4f)
    }
}

private fun DrawScope.drawSitLegs() {
    for ((x, fx) in listOf(102f to 88f, 138f to 152f)) {
        ovalB(x, 202f, 17f, 11f, pearl(x - 6f, 196f, 22f)); ovalS(x, 202f, 17f, 11f, LINE, 1.4f)
        ovalB(fx, 209f, 12.5f, 11.5f, pearl(fx - 4f, 204f, 16f)); ovalS(fx, 209f, 12.5f, 11.5f, LINE, 1.4f)
        oval(fx, 210f, 8f, 7.5f, SOLE)
    }
}

private fun DrawScope.drawTorso(sec: Float) {
    val body = Path().apply { moveTo(96f, 146f); cubicTo(96f, 136f, 144f, 136f, 144f, 146f); lineTo(148f, 184f); cubicTo(149f, 201f, 91f, 201f, 92f, 184f); close() }
    drawPath(body, pearl(110f, 150f, 70f)); drawPath(body, LINE, style = Stroke(1.6f))
    drawLine(PACK, Offset(102f, 147f), Offset(106f, 184f), 5f, StrokeCap.Round)
    drawLine(PACK, Offset(138f, 147f), Offset(134f, 184f), 5f, StrokeCap.Round)
    ovalB(120f, 145f, 25f, 6.5f, Brush.verticalGradient(listOf(Color(0xFFA47AFF), Color(0xFF6A3FD8)), 138f, 152f))
    val pulse = .45f + .55f * ((1 + sin(sec * 2 * PI / 1.8).toFloat()) / 2)
    drawCircle(Color(0xFFFF5A6E), 10f, Offset(127f, 165f), alpha = .3f * pulse)
    drawCircle(RED, 6f, Offset(127f, 165f)); drawCircle(Color.White, 2f, Offset(125f, 163f), alpha = .8f)
}

private fun DrawScope.drawHead(eyes: Eyes, mouth: Mouth, ms: Long, level: Float, droop: Boolean, fastEars: Boolean, nightcap: Boolean, t: Float = 0f) {
    val sec = ms / 1000f
    val period = if (fastEars) .5f else 3.2f
    val sway = 6f * sin(sec * 2 * PI / period).toFloat()
    val earBrush = Brush.linearGradient(listOf(Color(0xFFE4D6FF), Color(0xFFB48CFF), Color(0xFFE879C8)), Offset(30f, 10f), Offset(90f, 45f))
    rotate(if (droop) -28f else -sway, Offset(94f, 42f)) {
        drawPath(Path().apply { moveTo(94f, 42f); quadraticTo(88f, 32f, 80f, 30f) }, Color(0xFFC9B2FF), style = Stroke(4f, cap = StrokeCap.Round))
        val ear = Path().apply { moveTo(82f, 31f); cubicTo(70f, 20f, 44f, 14f, 34f, 22f); cubicTo(28f, 30f, 44f, 40f, 60f, 40f); cubicTo(70f, 40f, 78f, 36f, 82f, 31f); close() }
        drawPath(ear, Color(0xFFB48CFF), alpha = .25f, style = Stroke(6f)); drawPath(ear, earBrush)
        drawPath(Path().apply { moveTo(40f, 25f); cubicTo(50f, 20f, 64f, 22f, 74f, 28f) }, Color.White, alpha = .6f, style = Stroke(3.5f, cap = StrokeCap.Round))
    }
    rotate(if (droop) 40f else sway, Offset(140f, 36f)) {
        drawPath(Path().apply { moveTo(140f, 36f); quadraticTo(143f, 28f, 147f, 24f) }, Color(0xFFC9B2FF), style = Stroke(4f, cap = StrokeCap.Round))
        ovalB(152f, 10f, 9.5f, 16f, Brush.linearGradient(listOf(Color(0xFFE4D6FF), Color(0xFFB48CFF), Color(0xFFE879C8)), Offset(144f, -6f), Offset(160f, 26f)), 14f)
        oval(149f, 5f, 3f, 8f, Color.White, 14f, .55f)
    }
    oval(183f, 94f, 11f, 20f, NAVY)
    ovalB(120f, 86f, 64f, 56f, pearl(98f, 58f, 118f))
    drawOval(Brush.horizontalGradient(0f to Color(0xFFB48CFF).copy(alpha = .95f), .35f to Color.Transparent, .8f to Color.Transparent, 1f to Color(0xFF7FB8FF).copy(alpha = .7f), startX = 56f, endX = 184f),
        Offset(56f, 30f), Size(128f, 112f), style = Stroke(3.5f))
    oval(96f, 50f, 22f, 9f, Color.White, -22f, .9f)
    if (nightcap) drawNightcap()
    rr(78f, 56f, 90f, 62f, 29f, Color(0xFFECE6FB))
    rrB(81f, 59f, 84f, 56f, 26f, Brush.verticalGradient(listOf(Color(0xFF2A2150), Color(0xFF0B0817)), 59f, 115f))
    drawPath(Path().apply { moveTo(92f, 66f); quadraticTo(123f, 58f, 154f, 66f); quadraticTo(144f, 72f, 123f, 72f); quadraticTo(102f, 72f, 92f, 66f); close() }, Color.White, alpha = .13f)
    drawFace(eyes, mouth, sec, level, t)
    oval(60f, 94f, 16.5f, 24.5f, NAVY)
    ovalS(60f, 94f, 12f, 19f, Color(0xFF7FB8FF), 7f, .25f)
    ovalS(60f, 94f, 12f, 19f, Color(0xFF7FB8FF), 3.5f)
    oval(60f, 94f, 7f, 11f, Color(0xFF2C2466))
    drawCircle(RED, 3.6f, Offset(60f, 94f))
}

private val EL = Offset(108f, 86f)
private val ER = Offset(138f, 86f)

private fun DrawScope.drawFace(eyes: Eyes, mouth: Mouth, sec: Float, level: Float, t: Float) {
    val blinkPhase = (sec % 4.4f) / 4.4f
    val blink = if (blinkPhase in .91f..0.97f) .12f else 1f
    fun arcUp(c: Offset) = Path().apply { moveTo(c.x - 9f, c.y + 3f); quadraticTo(c.x, c.y - 9f, c.x + 9f, c.y + 3f) }
    fun arcDown(c: Offset) = Path().apply { moveTo(c.x - 9f, c.y); quadraticTo(c.x, c.y + 7f, c.x + 9f, c.y) }
    when (eyes) {
        Eyes.HAPPY -> { glowLine(arcUp(EL), CY, 5f); glowLine(arcUp(ER), CY, 5f) }
        Eyes.ROUND, Eyes.LOOK, Eyes.UP -> {
            val look = if (eyes == Eyes.LOOK) { val q = (sec % 6f) / 6f; if (q in .38f..0.55f) -3f else if (q in .63f..0.8f) 3f else 0f } else 0f
            val ox = if (eyes == Eyes.UP) 3f else look
            val oy = if (eyes == Eyes.UP) -4f else 0f
            for (c in listOf(EL, ER)) { glowDot(c.x + ox, c.y + oy, 6.5f, 8.5f * blink, CY); if (blink > .5f) drawCircle(Color.White, 2f, Offset(c.x + ox + 2f, c.y + oy - 3f), alpha = .9f) }
        }
        Eyes.WIDE -> for (c in listOf(EL, ER)) { drawCircle(CY, 9f, c, style = Stroke(4f)); drawCircle(CY, 3.5f, c) }
        Eyes.CLOSED -> { glowLine(arcDown(EL), CY, 4.5f); glowLine(arcDown(ER), CY, 4.5f) }
        Eyes.CALM -> for (c in listOf(EL, ER)) glowLine(Path().apply { moveTo(c.x - 8f, c.y + 1f); quadraticTo(c.x, c.y - 4f, c.x + 8f, c.y + 1f) }, CY, 4.5f)
        Eyes.DOTS -> for (i in 0..2) {
            val ph = ((sec / 1.2f) - i * .17f).let { it - kotlin.math.floor(it) }
            val up = if (ph < .4f) sin(ph / .4f * PI).toFloat() else 0f
            glowDot(109f + i * 14f, 88f - 3f * up, 4.5f, 4.5f, CY.copy(alpha = .35f + .65f * up))
        }
        Eyes.DETERMINED -> {
            drawLine(CY, Offset(98f, 78f), Offset(117f, 84f), 4f, StrokeCap.Round); drawLine(CY, Offset(148f, 78f), Offset(129f, 84f), 4f, StrokeCap.Round)
            glowDot(EL.x, EL.y + 4f, 7f, 5f * blink, CY); glowDot(ER.x, ER.y + 4f, 7f, 5f * blink, CY)
        }
        Eyes.UNPLUG -> {
            glowLine(Path().apply { moveTo(110f, 80f); lineTo(110f, 88f); moveTo(118f, 80f); lineTo(118f, 88f); moveTo(106f, 88f); lineTo(122f, 88f); quadraticTo(114f, 100f, 106f, 88f); moveTo(114f, 98f); lineTo(114f, 104f) }, CY, 3f)
            glowLine(Path().apply { moveTo(132f, 92f); lineTo(148f, 76f) }, Color(0xFFFF8FB0), 3f)
        }
        Eyes.SIP -> { glowLine(arcDown(EL), CY, 4.5f); glowLine(arcDown(ER), CY, 4.5f) }
    }
    val m = Offset(123f, 101f)
    when (mouth) {
        Mouth.SMILE -> drawPath(Path().apply { moveTo(m.x - 4f, m.y); quadraticTo(m.x, m.y + 3.5f, m.x + 4f, m.y) }, MOUTH, style = Stroke(2.4f, cap = StrokeCap.Round))
        Mouth.OPEN -> drawPath(Path().apply { moveTo(m.x - 5f, m.y - 1f); quadraticTo(m.x, m.y + 8f, m.x + 5f, m.y - 1f); close() }, MOUTH)
        Mouth.O -> oval(m.x, m.y + 1f, 3.2f, 3.8f, MOUTH)
        Mouth.TALK, Mouth.LEVEL -> {
            val hs = if (mouth == Mouth.TALK) listOf(3f, 7f, 11f, 6f, 9f, 4f, 8f, 5f) else listOf(2f, 4f, 6f, 4f, 7f, 3f, 5f, 2f)
            val col = if (mouth == Mouth.TALK) MOUTH else CY
            hs.forEachIndexed { i, h ->
                val wob = if (mouth == Mouth.TALK) (.3f + .7f * ((1 + sin((sec * 13.9f) + i * 1.3f)) / 2)) else (.25f + .75f * level.coerceIn(0f, 1f) * ((1 + sin(sec * 9f + i)) / 2) + .1f)
                val hh = h * wob.coerceIn(.2f, 1.2f)
                drawRoundRect(col, Offset(m.x - 15f + i * 4f, m.y + 1f - hh / 2), Size(2.4f, hh), CornerRadius(1.2f))
            }
        }
        Mouth.NONE -> {}
    }
}

private fun DrawScope.drawNightcap() {
    val cap = Path().apply { moveTo(66f, 50f); cubicTo(74f, 18f, 120f, 6f, 150f, 20f); cubicTo(170f, 30f, 182f, 40f, 196f, 70f); lineTo(186f, 74f); cubicTo(176f, 50f, 160f, 42f, 150f, 40f); cubicTo(130f, 34f, 96f, 40f, 76f, 58f); close() }
    drawPath(cap, Brush.verticalGradient(listOf(Color(0xFFA47AFF), Color(0xFF6A3FD8)), 10f, 74f))
    drawCircle(Color.White, 8f, Offset(192f, 76f))
    drawPath(Path().apply { moveTo(66f, 50f); quadraticTo(120f, 30f, 172f, 44f) }, Color(0xFFE4D6FF), style = Stroke(6f, cap = StrokeCap.Round))
}

// ------------------------------------------------------------------ arms and props
private fun DrawScope.limb(x: Float, y: Float, h: Float) {
    val r = CornerRadius(AW / 2)
    drawRoundRect(LINE, Offset(x - AW / 2 - 1.8f, y - AW / 2 - 1.8f), Size(AW + 3.6f, h + AW + 3.6f), CornerRadius(AW / 2 + 1.8f))
    drawRoundRect(Brush.linearGradient(listOf(Color.White, Color(0xFFDDD4F4)), Offset(x - AW / 2, y), Offset(x + AW / 2, y + h)), Offset(x - AW / 2, y - AW / 2), Size(AW, h + AW), r)
}

private fun DrawScope.drawArm(s: Offset, a: FloatArray, right: Boolean, open: Boolean, prop: Prop, propH: Float, ms: Long) {
    val e = Offset(s.x, s.y + U); val h = Offset(s.x, s.y + U + F)
    val inner = if (right) -1f else 1f
    rotate(a[0], s) {
        limb(s.x, s.y, U)
        rotate(a[1], e) {
            limb(e.x, e.y, F - AW / 2)
            rotate(a[2], h) {
                if (open) for (deg in listOf(-26f, 0f, 26f)) {
                    val dx = -sinD(deg); val dy = cosD(deg)
                    drawLine(LINE, Offset(h.x + dx * 3, h.y + dy * 3), Offset(h.x + dx * (HR + 5), h.y + dy * (HR + 5)), 10.6f, StrokeCap.Round)
                    drawLine(Color.White, Offset(h.x + dx * 3, h.y + dy * 3), Offset(h.x + dx * (HR + 5), h.y + dy * (HR + 5)), 7f, StrokeCap.Round)
                }
                if (prop == Prop.LANTERN) drawProp(prop, h, propH, ms)
                drawCircle(LINE, HR + 1.8f, h)
                drawCircle(pearl(h.x - 3f, h.y - 4f, HR * 1.6f), HR, h)
                oval(h.x - inner * 3f, h.y + 3f, 3.2f, 2.2f, Color.White, alpha = .55f)
                if (prop != Prop.NONE && prop != Prop.LANTERN) drawProp(prop, h, propH, ms)
                thumb(h, inner)
            }
        }
    }
}

private fun DrawScope.thumb(h: Offset, inner: Float) {
    val tx = h.x + inner * (HR - 2f); val ty = h.y - HR * .4f
    rotate(-25f * inner, Offset(tx, ty)) {
        drawOval(LINE, Offset(tx - HR * .38f - 1.4f, ty - HR * .56f - 1.4f), Size(HR * .76f + 2.8f, HR * 1.12f + 2.8f))
        drawOval(Color.White, Offset(tx - HR * .38f, ty - HR * .56f), Size(HR * .76f, HR * 1.12f))
    }
}

private fun DrawScope.drawThumbOver(s: Offset, a: FloatArray, right: Boolean) {
    val e = Offset(s.x, s.y + U); val h = Offset(s.x, s.y + U + F)
    rotate(a[0], s) { rotate(a[1], e) { rotate(a[2], h) { thumb(h, if (right) -1f else 1f) } } }
}

/** Props are drawn upright in world space: undo the hand's nominal angle around the hand centre. */
private fun DrawScope.drawProp(prop: Prop, h: Offset, propH: Float, ms: Long) {
    val sec = ms / 1000f
    translate(h.x, h.y) {
        rotate(-propH, Offset.Zero) {
            when (prop) {
                Prop.PHONE -> {
                    val ring = (sec % 1.2f) / 1.2f
                    drawCircle(CY, 16f * (.7f + .8f * ring), Offset(0f, -16f), alpha = .9f * (1 - ring), style = Stroke(2f))
                    rr(-8.5f, -30f, 17f, 32f, 4f, NAVY)
                    rrB(-6.5f, -27.5f, 13f, 24f, 2.5f, Brush.horizontalGradient(listOf(Color(0xFF7D8BFF), Color(0xFFA86CF0), Color(0xFFEE5C9C)), -6.5f, 6.5f))
                    drawCircle(Color.White, 3f, Offset(0f, -16f))
                }
                Prop.LANTERN -> {
                    drawPath(Path().apply { moveTo(-6f, 2f); quadraticTo(0f, -8f, 6f, 2f) }, SOLE, style = Stroke(2.4f))
                    val glow = .6f + .4f * sin(sec * 2 * PI / 1.8).toFloat()
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE7A8).copy(alpha = .9f * glow), Color.Transparent), Offset(0f, 20f), 22f), 22f, Offset(0f, 20f))
                    rr(-8f, 3f, 16f, 4f, 2f, SOLE)
                    rr(-9f, 7f, 18f, 22f, 5f, Color(0xFFFFE7A8), .6f)
                    drawRoundRect(Color(0xFFB48CFF), Offset(-9f, 7f), Size(18f, 22f), CornerRadius(5f), style = Stroke(2f))
                    oval(0f, 18f, 3f, 5f * (.9f + .1f * glow), Color(0xFFFFB86B))
                    rr(-8f, 29f, 16f, 4f, 2f, SOLE)
                }
                Prop.PEN -> {
                    drawLine(SOLE, Offset(9f, -16f), Offset(-4f, 10f), 4.6f, StrokeCap.Round)
                    drawLine(NAVY, Offset(-4f, 10f), Offset(-7f, 16f), 2.4f, StrokeCap.Round)
                    drawCircle(RED, 3f, Offset(9f, -16f))
                }
                Prop.CLIPBOARD -> {
                    rr(-18f, -52f, 36f, 46f, 4f, Color.White); drawRoundRect(Color(0xFFD9CFFA), Offset(-18f, -52f), Size(36f, 46f), CornerRadius(4f), style = Stroke(1.6f))
                    rr(-8f, -55f, 16f, 7f, 3f, SOLE)
                    for (i in 0..2) {
                        val y = -40f + i * 12f
                        drawRoundRect(Color(0xFFB48CFF), Offset(-13f, y - 4f), Size(8f, 8f), CornerRadius(2f), style = Stroke(1.6f))
                        drawLine(Color(0xFFD3C7FA), Offset(-1f, y), Offset(13f, y), 2.4f, StrokeCap.Round)
                        val show = ((sec % 3f) / 3f) > (.12f + i * .3f)
                        if (show) drawPath(Path().apply { moveTo(-12f, y); lineTo(-9.5f, y + 2.5f); lineTo(-5f, y - 3f) }, CLOTH, style = Stroke(2f, cap = StrokeCap.Round))
                    }
                }
                Prop.STICKY -> {
                    val phase = (sec % 2.8f) / 2.8f
                    val a = if (phase in .65f..0.95f) 0f else 1f
                    rotate(-8f, Offset(0f, -20f)) { rr(-10f, -30f, 20f, 20f, 2f, Color(0xFFFFC7DD), a) }
                }
                Prop.ALBUM -> {
                    rrB(-4f, -36f, 38f, 30f, 4f, Brush.horizontalGradient(listOf(Color(0xFF7D8BFF), Color(0xFFA86CF0), Color(0xFFEE5C9C)), -4f, 34f))
                    rr(-4f, -36f, 7f, 30f, 3f, Color(0xFF4B2FB5))
                }
                Prop.CLOCK -> {
                    val shake = if ((sec % 2.4f) < .5f) 3f * sin(sec * 60f) else 0f
                    rotate(shake, Offset(0f, -17f)) {
                        drawCircle(RED, 5f, Offset(-9f, -31f)); drawCircle(RED, 5f, Offset(9f, -31f))
                        drawCircle(Color.White, 14f, Offset(0f, -17f)); drawCircle(Color(0xFFB48CFF), 14f, Offset(0f, -17f), style = Stroke(3f))
                        drawLine(Color(0xFF4B2FB5), Offset(0f, -17f), Offset(0f, -25f), 2.2f, StrokeCap.Round)
                        drawLine(Color(0xFF4B2FB5), Offset(0f, -17f), Offset(5f, -17f), 2.2f, StrokeCap.Round)
                    }
                }
                Prop.MAGNIFIER -> {
                    drawLine(SOLE, Offset.Zero, Offset(0f, -12f), 5f, StrokeCap.Round)
                    drawCircle(CY, 12f, Offset(0f, -24f), alpha = .18f); drawCircle(SOLE, 12f, Offset(0f, -24f), style = Stroke(3.5f))
                }
                Prop.DOC -> {
                    rr(-16f, -44f, 32f, 40f, 3f, Color.White)
                    for (i in 0..3) drawLine(Color(0xFFB9A6F5), Offset(-10f, -36f + i * 6f), Offset(if (i == 3) 4f else 10f, -36f + i * 6f), 2f, StrokeCap.Round)
                }
                Prop.TORCH -> {
                    rr(-3f, -20f, 6f, 22f, 3f, SOLE)
                    val fl = 1f + .1f * sin(sec * 25f)
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE7A8).copy(alpha = .9f), Color.Transparent), Offset(0f, -30f), 18f), 18f, Offset(0f, -30f))
                    scale(1f, fl, Offset(0f, -21f)) {
                        drawPath(Path().apply { moveTo(0f, -40f); quadraticTo(10f, -28f, 4f, -21f); quadraticTo(0f, -18f, -4f, -21f); quadraticTo(-10f, -28f, 0f, -40f); close() }, Color(0xFFFF9C5A))
                    }
                }
                Prop.PLUG -> {
                    drawPath(Path().apply { moveTo(0f, 6f); quadraticTo(6f, 30f, -12f, 34f); quadraticTo(-30f, 38f, -30f, 24f) }, PACK, style = Stroke(3.5f, cap = StrokeCap.Round))
                    rr(-6f, -2f, 12f, 12f, 3f, PACK)
                    drawLine(Color(0xFFB8B4C8), Offset(-3f, -2f), Offset(-3f, -8f), 2.2f, StrokeCap.Round)
                    drawLine(Color(0xFFB8B4C8), Offset(3f, -2f), Offset(3f, -8f), 2.2f, StrokeCap.Round)
                }
                Prop.NONE -> {}
            }
        }
    }
}

private fun DrawScope.drawPass() {
    rotate(-4f, Offset(120f, 168f)) {
        rr(90f, 146f, 60f, 40f, 6f, Color.White)
        drawRoundRect(Color(0xFFD9CFFA), Offset(90f, 146f), Size(60f, 40f), CornerRadius(6f), style = Stroke(1.6f))
        rrB(90f, 146f, 60f, 12f, 6f, Brush.horizontalGradient(listOf(Color(0xFF7D8BFF), Color(0xFFA86CF0), Color(0xFFEE5C9C)), 90f, 150f))
        drawRect(Brush.horizontalGradient(listOf(Color(0xFF7D8BFF), Color(0xFFA86CF0), Color(0xFFEE5C9C)), 90f, 150f), Offset(90f, 153f), Size(60f, 5f))
        rr(96f, 164f, 16f, 7f, 2f, NAVY); rr(128f, 164f, 16f, 7f, 2f, NAVY)
        drawLine(CLOTH, Offset(115f, 167.5f), Offset(124f, 167.5f), 1.6f, StrokeCap.Round)
        rr(96f, 176f, 34f, 3f, 1.5f, Color(0xFFA3222A))
    }
}

private fun DrawScope.drawGears(sec: Float) {
    fun gear(cx: Float, cy: Float, r: Float, deg: Float, color: Color) = rotate(deg, Offset(cx, cy)) {
        drawCircle(color, r, Offset(cx, cy), style = Stroke(2.4f))
        for (i in 0 until 8) { val a = i * 45f; drawLine(color, Offset(cx + r * sinD(a), cy - r * cosD(a)), Offset(cx + (r + 4f) * sinD(a), cy - (r + 4f) * cosD(a)), 3f, StrokeCap.Round) }
        drawCircle(color, r * .35f, Offset(cx, cy), style = Stroke(2f))
    }
    gear(184f, 34f, 9f, sec * 90f, CY)
    gear(206f, 52f, 6.5f, -sec * 120f, Color(0xFFB48CFF))
}

private fun DrawScope.drawZz(sec: Float) {
    for (i in 0..2) {
        val ph = ((sec / 3f) + i / 3f).let { it - kotlin.math.floor(it) }
        val s = 10f - i * 2f
        val x = 176f + 14f * ph; val y = 52f - 30f * ph
        val a = if (ph < .2f) ph / .2f else 1f - (ph - .2f) / .8f
        drawPath(Path().apply { moveTo(x, y); lineTo(x + s, y); lineTo(x, y + s); lineTo(x + s, y + s) }, Color(0xFFC9B2FF), alpha = a.coerceIn(0f, 1f), style = Stroke(2.4f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawSparkles(sec: Float) {
    val spots = listOf(Triple(28f, 70f, Color(0xFFFFD25E)), Triple(214f, 60f, Color(0xFFFF8FC8)), Triple(22f, 160f, CY), Triple(220f, 158f, Color(0xFFB48CFF)))
    spots.forEachIndexed { i, (x, y, c) ->
        val ph = ((sec / 1.1f) + i * .33f).let { it - kotlin.math.floor(it) }
        val s = sin(ph * PI).toFloat()
        if (s > .05f) drawPath(Path().apply { moveTo(x, y - 10 * s); lineTo(x + 2.6f * s, y - 2.6f * s); lineTo(x + 10 * s, y); lineTo(x + 2.6f * s, y + 2.6f * s); lineTo(x, y + 10 * s); lineTo(x - 2.6f * s, y + 2.6f * s); lineTo(x - 10 * s, y); lineTo(x - 2.6f * s, y - 2.6f * s); close() }, c, alpha = s)
    }
}

private fun DrawScope.drawNightSky(sec: Float) {
    val moon = Path.combine(
        androidx.compose.ui.graphics.PathOperation.Difference,
        Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(204f, 46f), 18f)) },
        Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(194f, 40f), 15f)) },
    )
    drawPath(moon, Color(0xFFFFE7A8), alpha = .9f)
    listOf(Offset(30f, 40f), Offset(52f, 22f), Offset(176f, 92f), Offset(24f, 120f), Offset(210f, 130f)).forEachIndexed { i, o ->
        drawCircle(Color.White, 2f, o, alpha = .45f + .55f * ((1 + sin(sec * 3.9f + i * 2f)) / 2))
    }
}
