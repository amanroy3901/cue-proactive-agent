package com.cue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Pale lavender base with soft colour glows at the top, tinted by the moment. */
@Composable
fun GlowBackground(
    glowA: Color = Cue.glowBlush,
    glowB: Color = Cue.glowLavender,
    base: Color = Cue.base,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(glowA, glowA.copy(alpha = 0f)), center = Offset(size.width * 0.9f, -size.height * 0.04f), radius = size.width * 1.15f))
                drawRect(Brush.radialGradient(listOf(glowB, glowB.copy(alpha = 0f)), center = Offset(0f, 0f), radius = size.width * 0.85f))
            },
        content = content,
    )
}

/** A white card lifted off the base, with a shadow tinted by its content's colour. */
@Composable
fun LiftCard(
    modifier: Modifier = Modifier,
    tint: Color = Cue.primaryDeep,
    radius: Dp = 26.dp,
    padding: Dp = 16.dp,
    background: Color = Color.White,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .shadow(14.dp, shape, ambientColor = tint.copy(alpha = 0.25f), spotColor = tint.copy(alpha = 0.35f))
            .clip(shape)
            .background(background)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** A card filled with a gradient: the hero, the trip, a pinned app. */
@Composable
fun GradientCard(
    brush: Brush,
    shadowColor: Color,
    modifier: Modifier = Modifier,
    radius: Dp = 30.dp,
    padding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .shadow(18.dp, shape, ambientColor = shadowColor.copy(alpha = 0.4f), spotColor = shadowColor.copy(alpha = 0.6f))
            .clip(shape)
            .background(brush)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), center = Offset(size.width, 0f), radius = size.width * 0.7f))
            }
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Glossy icon chip in an app's colour. */
@Composable
fun IconChip(icon: ImageVector, tint: AppTint, size: Dp = 40.dp, iconSize: Dp = 20.dp) {
    Box(
        Modifier
            .size(size)
            .shadow(6.dp, RoundedCornerShape(size * 0.35f), spotColor = tint.deep.copy(alpha = 0.5f))
            .clip(RoundedCornerShape(size * 0.35f))
            .background(Brush.linearGradient(listOf(tint.light, tint.deep))),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize)) }
}

/** Cue's avatar: a lit orb in the Qira ribbon colours. */
@Composable
fun CueOrb(size: Dp, label: String? = null, ring: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .then(if (ring) Modifier.border(size * 0.07f, Color.White.copy(alpha = 0.6f), CircleShape) else Modifier)
            .shadow(size * 0.18f, CircleShape, spotColor = Cue.primaryDeep)
            .clip(CircleShape)
            .background(Cue.ribbonBrush)
            .drawBehind { drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), center = Offset(this.size.width * 0.3f, this.size.height * 0.25f), radius = this.size.width * 0.6f)) },
        contentAlignment = Alignment.Center,
    ) { if (label != null) Text(label, style = Type.title.copy(color = Color.White, fontSize = (size.value * 0.26f).let { androidx.compose.ui.unit.TextUnit(it, androidx.compose.ui.unit.TextUnitType.Sp) })) }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, brush: Brush = Cue.primaryButton, shadow: Color = Cue.primaryDeep, icon: ImageVector? = null, height: Dp = 58.dp, textColor: Color = Color.White, onClick: () -> Unit) {
    Row(
        modifier
            .height(height)
            .shadow(14.dp, CircleShape, spotColor = shadow.copy(alpha = 0.6f))
            .clip(CircleShape)
            .background(brush)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = textColor, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, style = Type.bodyStrong.copy(color = textColor, fontSize = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)))
    }
}

@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, color: Color = Cue.primaryTint, textColor: Color = Cue.ink, height: Dp = 52.dp, onClick: () -> Unit) {
    Box(
        modifier.height(height).clip(CircleShape).background(color).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.bodyStrong.copy(color = textColor)) }
}

@Composable
fun Pill(text: String, background: Color, color: Color, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(
        modifier.height(28.dp).clip(CircleShape).background(background).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) { Box(Modifier.size(7.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = Type.tiny.copy(color = color))
    }
}

/** A tickable list row: rounded square box, filled with the Qira ribbon when done. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun CheckRow(text: String, checked: Boolean, onToggle: () -> Unit, dark: Boolean = false, highlight: Boolean = false, onLongPress: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlight) Cue.primaryTint else Color.Transparent)
            .then(if (onLongPress != null) Modifier.combinedClickable(role = Role.Checkbox, onClick = onToggle, onLongClick = onLongPress, onLongClickLabel = "Edit")
                else Modifier.clickable(role = Role.Checkbox, onClick = onToggle))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (checked) Cue.primaryBrush else Brush.linearGradient(listOf(Color(0xFFFBFAFF), Color(0xFFFBFAFF))))
                .then(if (!checked) Modifier.border(2.dp, Color(0xFFADA6C4), RoundedCornerShape(8.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            style = Type.body.copy(color = if (checked) Cue.muted else if (dark) Cue.nightText else Cue.ink, textDecoration = if (checked) TextDecoration.LineThrough else null),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun BackHeader(title: String, subtitle: String?, onBack: () -> Unit, glow: Color = Cue.primaryDeep) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).shadow(6.dp, CircleShape, spotColor = glow.copy(alpha = 0.3f)).clip(CircleShape).background(Color.White.copy(alpha = 0.85f))
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Cue.ink, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = Type.display.copy(fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp), color = Cue.ink))
            if (subtitle != null) Text(subtitle, style = Type.small.copy(color = Cue.muted))
        }
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, style = Type.smallStrong.copy(color = Cue.muted))
        if (trailing != null) Text(trailing, style = Type.smallStrong.copy(color = Cue.muted))
    }
}

data class NavEntry(val key: String, val label: String, val icon: ImageVector)

/**
 * The floating bar: a dark glass capsule with Cue's orb in the middle (tap to talk). [left] and [right] are the two
 * tabs either side of it.
 */
@Composable
fun CueBottomBar(left: List<NavEntry>, right: List<NavEntry>, selected: String, onSelect: (String) -> Unit, onTalk: () -> Unit, modifier: Modifier = Modifier) {
    val spin by androidx.compose.animation.core.rememberInfiniteTransition(label = "orb").animateFloat(0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(6000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin")
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .shadow(24.dp, RoundedCornerShape(30.dp), spotColor = Color(0xFF3A2A9E), ambientColor = Color(0xFF3A2A9E))
                .clip(RoundedCornerShape(30.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1C1838), Color(0xFF110E26))))
                .border(1.dp, Brush.linearGradient(listOf(Color(0x667C5CFF), Color(0x334FD8F7), Color(0x14FFFFFF))), RoundedCornerShape(30.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) { left.forEach { NavItem(it, it.key == selected) { onSelect(it.key) } } }
            Spacer(Modifier.width(76.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) { right.forEach { NavItem(it, it.key == selected) { onSelect(it.key) } } }
        }
        // Cue's orb: a spinning aurora ring around a dark core.
        Box(
            Modifier.align(Alignment.TopCenter).size(70.dp)
                .shadow(18.dp, CircleShape, spotColor = Color(0xFF7C5CFF), ambientColor = Color(0xFF4FD8F7))
                .clip(CircleShape)
                .drawBehind {
                    rotate(spin) {
                        drawCircle(Brush.sweepGradient(listOf(Color(0xFF7C5CFF), Color(0xFF4FD8F7), Color(0xFFE0458F), Color(0xFF7C5CFF))))
                    }
                    drawCircle(Brush.radialGradient(listOf(Color(0xFF2A2160), Color(0xFF0E0B22))), radius = size.minDimension / 2 - 3.dp.toPx())
                    drawCircle(Brush.radialGradient(listOf(Color(0x664FD8F7), Color.Transparent)), radius = size.minDimension / 3)
                }
                .clickable(role = Role.Button, onClickLabel = "Talk to Cue", onClick = onTalk),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Mic, contentDescription = "Talk to Cue", tint = Color.White, modifier = Modifier.size(26.dp)) }
    }
}

@Composable
private fun RowScope.NavItem(e: NavEntry, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Tab, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.width(46.dp).height(30.dp).clip(CircleShape)
                .background(if (selected) Brush.linearGradient(listOf(Color(0x557C5CFF), Color(0x334FD8F7))) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) { Icon(e.icon, contentDescription = null, tint = if (selected) Color.White else Color(0xFF9C97C2), modifier = Modifier.size(22.dp)) }
        Text(e.label, style = Type.tiny.copy(color = if (selected) Color.White else Color(0xFF9C97C2), fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight(800) else androidx.compose.ui.text.font.FontWeight(600)), maxLines = 1)
    }
}

@Composable
fun Txt(text: String, style: TextStyle, color: Color = Cue.ink, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, style = style.copy(color = color), modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

fun Modifier.shapeBg(color: Color, shape: Shape = CircleShape) = this.clip(shape).background(color)
