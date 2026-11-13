package com.cue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
fun CheckRow(text: String, checked: Boolean, onToggle: () -> Unit, dark: Boolean = false, highlight: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlight) Cue.primaryTint else Color.Transparent)
            .clickable(role = Role.Checkbox, onClick = onToggle)
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

/** The frosted floating bar: Home, two pinned apps, raised Talk, Apps. */
@Composable
fun CueBottomBar(left: List<NavEntry>, right: List<NavEntry>, selected: String, onSelect: (String) -> Unit, onTalk: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 14.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(20.dp, RoundedCornerShape(36.dp), spotColor = Cue.primaryDeep.copy(alpha = 0.35f))
                .clip(RoundedCornerShape(36.dp))
                .background(Color.White.copy(alpha = 0.92f))
                .border(1.dp, Color.White, RoundedCornerShape(36.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) { left.forEach { NavItem(it, it.key == selected) { onSelect(it.key) } } }
            Spacer(Modifier.width(64.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) { right.forEach { NavItem(it, it.key == selected) { onSelect(it.key) } } }
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-18).dp)
                .size(64.dp)
                .shadow(14.dp, CircleShape, spotColor = Cue.primaryDeep)
                .clip(CircleShape)
                .background(Cue.base)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Cue.ribbonBrush)
                .clickable(role = Role.Button, onClickLabel = "Talk to Cue", onClick = onTalk),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Mic, contentDescription = "Talk to Cue", tint = Color.White, modifier = Modifier.size(26.dp)) }
    }
}

@Composable
private fun RowScope.NavItem(e: NavEntry, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.width(48.dp).height(30.dp).clip(CircleShape).background(if (selected) Cue.primaryTint else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Icon(e.icon, contentDescription = null, tint = if (selected) Cue.primaryDeep else Cue.muted, modifier = Modifier.size(22.dp)) }
        Text(e.label, style = Type.tiny.copy(color = if (selected) Cue.primaryDeep else Cue.muted, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight(800) else androidx.compose.ui.text.font.FontWeight(600)), maxLines = 1)
    }
}

@Composable
fun Txt(text: String, style: TextStyle, color: Color = Cue.ink, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, style = style.copy(color = color), modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

fun Modifier.shapeBg(color: Color, shape: Shape = CircleShape) = this.clip(shape).background(color)
