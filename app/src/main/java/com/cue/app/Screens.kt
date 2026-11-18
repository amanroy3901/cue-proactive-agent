package com.cue.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.cue.agent.Airports
import com.cue.agent.TextKit
import com.cue.domain.*
import com.cue.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalTime
import java.util.Locale

// ------------------------------------------------------------------ shared pieces

@Composable
fun Page(glowA: Color = Cue.glowBlush, glowB: Color = Cue.glowLavender, base: Color = Cue.base, content: LazyListScope.() -> Unit) {
    GlowBackground(glowA, glowB, base) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

fun sp(v: Float) = TextUnit(v, TextUnitType.Sp)

@Composable fun now(): Long {
    var t by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); t = System.currentTimeMillis() } }
    return t
}

private val zone get() = java.time.ZoneId.systemDefault()

fun greeting(): String = when (LocalTime.now().hour) { in 0..3 -> "Still up"; in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

fun ticketFields(item: Item?): JSONObject = runCatching { JSONObject(item?.fields ?: "{}") }.getOrDefault(JSONObject())

@Composable
fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).shadow(6.dp, CircleShape, spotColor = Cue.primaryDeep.copy(alpha = 0.3f)).clip(CircleShape).background(Color.White.copy(alpha = 0.85f))
        .clickable(role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Cue.ink, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun TripPass(ticket: Item, compact: Boolean = false, onClick: (() -> Unit)? = null) {
    val f = ticketFields(ticket)
    val from = f.optString("from", "—"); val to = f.optString("to", "—")
    GradientCard(Cue.tripBrush, Color(0xFF3F45B8), Modifier.fillMaxWidth(), radius = 28.dp, padding = 0.dp, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt("Boarding pass" + (f.optString("flight").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), Type.smallStrong, Color(0xFFE3E7FF))
            Txt(ticket.eventStart?.let { TextKit.clock(it, zone) } ?: "", Type.smallStrong, Color.White)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (compact) 10.dp else 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Txt(from, Type.big.copy(fontSize = sp(36f)), Color.White); Txt(Airports.code(from)?.city ?: "", Type.small, Color(0xFFE3E7FF)) }
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Flight, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Column(horizontalAlignment = Alignment.End) { Txt(to, Type.big.copy(fontSize = sp(36f)), Color.White); Txt(Airports.code(to)?.city ?: "", Type.small, Color(0xFFE3E7FF)) }
        }
        Row(Modifier.fillMaxWidth().drawBehind {
            drawLine(Color.White.copy(alpha = 0.4f), Offset(0f, 0f), Offset(size.width, 0f), 3f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
        }.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(ticket.eventStart?.let { TextKit.dayLabel(it, System.currentTimeMillis(), zone).replaceFirstChar { c -> c.uppercase() } } ?: "", Type.small, Color(0xFFE3E7FF))
            f.optString("gate").takeIf { it.isNotBlank() }?.let { Txt("Gate $it", Type.smallStrong, Color.White) }
            f.optString("seat").takeIf { it.isNotBlank() }?.let { Txt("Seat $it", Type.smallStrong, Color.White) }
        }
    }
}

@Composable
fun ChecklistCard(title: String, list: List<ChecklistEntry>, tint: Color = Cue.primaryDeep, header: (@Composable () -> Unit)? = null, onToggle: (ChecklistEntry) -> Unit) {
    LiftCard(Modifier.fillMaxWidth(), tint = tint, radius = 28.dp, padding = 6.dp) {
        if (header != null) header() else Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconChip(Icons.Rounded.Checklist, AppTints.lists, 30.dp, 15.dp)
            Spacer(Modifier.width(10.dp))
            Txt(title, Type.bodyStrong, modifier = Modifier.weight(1f))
            Txt("${list.count { it.checked }} of ${list.size}", Type.smallStrong, Cue.muted)
        }
        list.sortedWith(compareBy<ChecklistEntry> { it.checked }.thenBy { it.position }).forEach { e -> CheckRow(e.text, e.checked, onToggle = { onToggle(e) }) }
    }
}

// ------------------------------------------------------------------ Home

@Composable
fun HomeScreen(a: MainActivity, nav: Nav) {
    val engine = a.cue.engine
    val dao = engine.dao
    val scope = rememberCoroutineScope()
    val t = now()
    val reminders by dao.scheduledRemindersFlow().collectAsState(emptyList())
    val items by dao.allItemsFlow().collectAsState(emptyList())
    val entries by dao.allEntriesFlow().collectAsState(emptyList())
    val albums by dao.albumsFlow().collectAsState(emptyList())
    val habits by dao.habitsFlow().collectAsState(emptyList())
    val logs by dao.habitLogsFlow().collectAsState(emptyList())
    val pending by dao.openPendingFlow().collectAsState(emptyList())
    val people by dao.peopleFlow().collectAsState(emptyList())

    val nextCall = reminders.filter { it.kind in ReminderKind.calls }.minByOrNull { it.fireAt }
    val ticket = items.filter { it.kind == ItemKind.TICKET && (it.eventStart ?: 0) > t - 3_600_000 }.minByOrNull { it.eventStart!! }
    val trip = ticket?.albumId?.let { id -> albums.firstOrNull { it.id == id } }
    val tripList = items.firstOrNull { it.type == ItemType.CHECKLIST && it.albumId == trip?.id && trip != null } ?: items.firstOrNull { it.type == ItemType.CHECKLIST }
    val listEntries = entries.filter { it.itemId == tripList?.id }
    val open = listEntries.filter { !it.checked }
    val gym = habits.firstOrNull()
    val today = TextKit.dayKey(t, zone)
    val streak = gym?.let { h -> var d = java.time.LocalDate.now(); val done = logs.filter { it.habitId == h.id && it.done }.map { it.day }.toSet(); if (today !in done) d = d.minusDays(1); var n = 0; while (d.toString() in done) { n++; d = d.minusDays(1) }; n } ?: 0
    val gymToday = gym?.let { h -> logs.any { it.habitId == h.id && it.day == today && it.done } } ?: false
    val dose = reminders.filter { it.kind == ReminderKind.DOSE }.minByOrNull { it.fireAt }
    val expiring = items.filter { (it.expiresAt ?: 0) in t..(t + 45L * 86_400_000) }.minByOrNull { it.expiresAt!! }
    val callLabel = nextCall?.let { if (it.kind == ReminderKind.CHECKIN_MORNING) "morning" else if (it.kind == ReminderKind.CHECKIN_NIGHT) "night" else "" }.orEmpty()
    val nudge = when {
        open.isNotEmpty() && trip != null -> "${countWord(open.size)} left to pack for ${trip.title.substringBefore(" trip").substringBefore(",")}." + (nextCall?.let { " I'll ask about ${if (open.size == 1) "it" else "them"} at ${TextKit.clock(it.fireAt, zone).lowercase(Locale.ROOT)}." } ?: "")
        dose != null && TextKit.dayKey(dose.fireAt, zone) == today -> "${dose.text} at ${TextKit.clock(dose.fireAt, zone)}. I'll ring you."
        expiring != null -> "Your ${expiring.title.removeSuffix(" bill")}${if (expiring.kind == ItemKind.BILL) " warranty" else ""} ends ${TextKit.dateLabel(expiring.expiresAt!!, zone)}. I'll ring you a week before."
        pending.isNotEmpty() -> "${people.firstOrNull { it.id == pending.first().personId }?.name ?: "Someone"} is waiting: ${pending.first().text.lowercase(Locale.ROOT)}."
        else -> "Nothing pressing. Tap Talk any time, or wait for my call."
    }

    Page {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH)), Type.smallStrong, Cue.primary)
                    Txt("${greeting()}, ${a.cue.settings.userName}", Type.title.copy(fontSize = sp(24f)))
                }
                RoundIconButton(Icons.Rounded.Tune, "Settings") { nav.go("settings") }
            }
        }
        item {
            GradientCard(Cue.primaryBrush, Cue.primaryDeep, Modifier.fillMaxWidth(), radius = 32.dp, padding = 22.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (nextCall != null) {
                            val mins = ((nextCall.fireAt - t) / 60_000).coerceAtLeast(0)
                            Txt(if (callLabel.isNotEmpty()) "${callLabel.replaceFirstChar { it.uppercase() }} call in" else nextCall.text, Type.smallStrong, Color(0xFFF3EEFF), maxLines = 1)
                            Txt(if (mins < 90) "$mins min" else if (mins < 24 * 60) "${mins / 60} h" + (if (mins % 60 >= 5) " ${mins % 60} m" else "") else TextKit.dayLabel(nextCall.fireAt, t, zone), Type.big, Color.White)
                        } else {
                            Txt("No calls set", Type.smallStrong, Color(0xFFF3EEFF)); Txt("Talk any time", Type.title, Color.White)
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.height(44.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Brush.verticalGradient(listOf(Color.White, Color(0xFFF5F0FF))))
                            .clickable(role = Role.Button) { a.talk() }.padding(start = 14.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Cue.coral)); Spacer(Modifier.width(8.dp)); Txt("Talk now", Type.bodyStrong, Cue.primaryDeep)
                        }
                    }
                    DayRing(t, a.cue.settings.morningMinutes, a.cue.settings.nightMinutes, nextCall?.fireAt)
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Pixel(PixelMood.Idle, 84.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt("Cue", Type.smallStrong, Cue.primaryDeep)
                    Txt(nudge, Type.lead)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().shadow(10.dp, CircleShape, spotColor = Cue.primaryDeep.copy(alpha = 0.3f)).clip(CircleShape).background(Color.White.copy(alpha = 0.9f))
                .clickable(role = Role.Button) { nav.go("ask") }.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Cue.primaryDeep, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                Txt("Ask Cue by typing", Type.bodyStrong, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Cue.muted)
            }
        }
        if (tripList != null && listEntries.isNotEmpty()) item {
            ChecklistCard(tripList.title, listEntries, header = if (trip != null && ticket != null) ({
                Row(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(22.dp)).clickable { nav.go("album/${trip.id}") }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconChip(Icons.Rounded.Flight, AppTints.albums, 36.dp, 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(trip.title.substringBefore(","), Type.bodyStrong)
                        Txt("Flight ${TextKit.dayLabel(ticket.eventStart!!, t, zone)}, ${TextKit.clock(ticket.eventStart!!, zone)}", Type.small, Cue.muted)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Cue.muted)
                }
            }) else null) { e -> scope.launch { dao.updateEntry(e.copy(checked = !e.checked)) } }
        }
        if (gym != null) item {
            Row(Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(24.dp), spotColor = Color(0xFFC98A00)).clip(RoundedCornerShape(24.dp)).background(Cue.marigoldCard)
                .clickable { nav.go("habits") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconChip(Icons.Rounded.LocalFireDepartment, AppTints.habits)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt("${gym.name}, $streak day${if (streak == 1) "" else "s"} in a row", Type.bodyStrong, Color(0xFF4E3800))
                    Txt(if (gymToday) "Done today" else "Today is still open", Type.small, Color(0xFF4E3800))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(7) { i -> val filled = i < streak.coerceAtMost(6) || (i == 6 && gymToday)
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (filled) Color(0xFFC77700) else Color.Transparent).border(2.dp, Color(0xFFC77700), CircleShape)) }
                }
            }
        }
        if (pending.isNotEmpty()) item {
            LiftCard(Modifier.fillMaxWidth(), tint = AppTints.people.deep, onClick = { nav.go("people") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(Icons.Rounded.People, AppTints.people, 36.dp, 18.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt("Waiting on you", Type.bodyStrong)
                        Txt(pending.take(2).joinToString(" · ") { p -> "${people.firstOrNull { it.id == p.personId }?.name}: ${p.text}" }, Type.small, Cue.muted, maxLines = 2)
                    }
                }
            }
        }
        val upcoming = reminders.filter { it.kind !in setOf(ReminderKind.PRELOAD, ReminderKind.CHECKIN_MORNING, ReminderKind.CHECKIN_NIGHT, ReminderKind.AIRPORT_BACKUP) }.sortedBy { it.fireAt }.take(3)
        if (upcoming.isNotEmpty()) {
            item { SectionLabel("Coming up", "All") }
            items(upcoming) { r -> ReminderRow(r, t) { nav.go("reminders") } }
        }
    }
}

fun countWord(n: Int) = when (n) { 1 -> "One thing"; 2 -> "Two things"; 3 -> "Three things"; else -> "$n things" }

@Composable
fun DayRing(t: Long, morning: Int, night: Int, next: Long?) {
    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
    val frac = ((nowMin - morning).toFloat() / (night - morning).coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 9.dp.toPx()
            drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, style = Stroke(stroke))
            drawArc(Color.White.copy(alpha = 0.9f), 135f, 270f * frac, false, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Cue.coral, 135f + 270f - 6f, 6f, false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(next?.let { TextKit.clock(it, zone) } ?: "—", Type.bodyStrong, Color.White)
            Txt("next call", Type.tiny, Color(0xFFF3EEFF))
        }
    }
}

@Composable
fun ReminderRow(r: Reminder, t: Long, daily: Boolean = false, onClick: () -> Unit) {
    val tint = when (r.kind) {
        ReminderKind.DOSE, ReminderKind.CARE -> AppTints.reminders; ReminderKind.EXPIRY -> AppTints.renewals; ReminderKind.ACCOUNTABILITY -> AppTints.habits
        ReminderKind.LEAVE_BY -> AppTints.albums; ReminderKind.PRACTICE -> AppTints.practice; else -> AppTints.lists
    }
    val icon = when (r.kind) {
        ReminderKind.DOSE, ReminderKind.CARE -> Icons.Rounded.Medication; ReminderKind.EXPIRY -> Icons.Rounded.Autorenew; ReminderKind.ACCOUNTABILITY -> Icons.Rounded.LocalFireDepartment
        ReminderKind.LEAVE_BY -> Icons.Rounded.Flight; ReminderKind.PRACTICE -> Icons.Rounded.RecordVoiceOver; ReminderKind.CHECKIN_MORNING -> Icons.Rounded.WbSunny
        ReminderKind.CHECKIN_NIGHT -> Icons.Rounded.Bedtime; else -> Icons.Rounded.Alarm
    }
    LiftCard(Modifier.fillMaxWidth(), tint = tint.deep, padding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip(icon, tint, 38.dp, 19.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(r.text, Type.bodyStrong, maxLines = 2)
                Txt((if (daily) "Daily at ${TextKit.clock(r.fireAt, zone)}" else "${TextKit.dayLabel(r.fireAt, t, zone).replaceFirstChar { it.uppercase() }}, ${TextKit.clock(r.fireAt, zone)}") +
                    if (r.surface == Surface.RING) " · rings" else "", Type.small, Cue.muted)
            }
        }
    }
}

// ------------------------------------------------------------------ Apps

@Composable
fun AppsScreen(a: MainActivity, nav: Nav, pins: List<String>) {
    val dao = a.cue.engine.dao
    val t = now()
    val reminders by dao.scheduledRemindersFlow().collectAsState(emptyList())
    val items by dao.allItemsFlow().collectAsState(emptyList())
    val entries by dao.allEntriesFlow().collectAsState(emptyList())
    val albums by dao.albumsFlow().collectAsState(emptyList())
    val habits by dao.habitsFlow().collectAsState(emptyList())
    val pending by dao.openPendingFlow().collectAsState(emptyList())
    fun status(key: String): String = when (key) {
        "lists" -> "${entries.count { !it.checked }} open"
        "notes" -> "${items.count { it.type == ItemType.NOTE && it.kind !in setOf("practice", "renewal") }} kept"
        "ask" -> "Answers as cards"
        "habits" -> habits.firstOrNull()?.let { "${it.name} streak" } ?: "Start one"
        "journal" -> items.firstOrNull { it.type == ItemType.JOURNAL }?.let { "Last: ${TextKit.dayLabel(it.createdAt, t, zone)}" } ?: "From night calls"
        "albums" -> "${albums.size} album${if (albums.size == 1) "" else "s"}"
        "reminders" -> "${reminders.count { it.kind !in setOf(ReminderKind.PRELOAD, ReminderKind.AIRPORT_BACKUP) }} scheduled"
        "papers" -> "${items.count { it.type == ItemType.GALLERY }} saved"
        "renewals" -> items.filter { it.expiresAt != null }.minByOrNull { it.expiresAt!! }?.let { "${it.title.removeSuffix(" bill")}, ${TextKit.dateLabel(it.expiresAt!!, zone)}" } ?: "None yet"
        "people" -> "${pending.size} waiting"
        "practice" -> if (a.cue.settings.practiceEnabled) "Daily at ${TextKit.clock(TextKit.nextDaily(a.cue.settings.practiceMinutes, t, zone), zone)}" else "5-minute calls"
        "focus" -> "25-minute timer"
        "search" -> "Find anything"
        else -> ""
    }
    fun toggle(key: String) {
        val now = pins.toMutableList()
        if (key in now) now.remove(key) else if (now.size < 3) now += key else { a.cue.engine.surfaces.toast("You can pin three. Unpin one first."); return }
        a.updatePins(now)
    }
    Page(glowA = Cue.glowLavender, glowB = Cue.glowBlush) {
        item { Column { Txt("Apps", Type.display); Txt("I run these for you. Pin up to three.", Type.body, Cue.muted) } }
        item { SectionLabel("Pinned to your bar", "${pins.size} of 3") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pins.mapNotNull { AppCatalog.get(it) }.forEach { app ->
                    GradientCard(Brush.linearGradient(listOf(app.tint.light, app.tint.deep)), app.tint.deep, Modifier.weight(1f).height(112.dp), radius = 26.dp, padding = 14.dp, onClick = { nav.go(app.key) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Icon(app.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Icon(Icons.Rounded.PushPin, "Unpin ${app.label}", tint = Color.White, modifier = Modifier.size(20.dp).clickable(role = Role.Button) { toggle(app.key) })
                        }
                        Spacer(Modifier.weight(1f))
                        Txt(app.label, Type.bodyStrong, Color.White)
                        Txt(status(app.key), Type.small, Color.White.copy(alpha = 0.85f), maxLines = 1)
                    }
                }
                repeat(3 - pins.size) { Box(Modifier.weight(1f).height(112.dp).clip(RoundedCornerShape(26.dp)).border(1.5.dp, Color(0xFFCFC8E6), RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) { Txt("Free slot", Type.small, Cue.muted) } }
            }
        }
        item { SectionLabel("All apps") }
        AppCatalog.all.filter { it.key !in pins }.chunked(2).forEach { row ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { app ->
                        LiftCard(Modifier.weight(1f).height(106.dp), tint = app.tint.deep, padding = 14.dp, onClick = { nav.go(app.key) }) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                IconChip(app.icon, app.tint, 38.dp, 19.dp)
                                Icon(Icons.Rounded.PushPin, "Pin ${app.label}", tint = Color(0xFFADA6C4), modifier = Modifier.size(20.dp).clickable(role = Role.Button) { toggle(app.key) })
                            }
                            Spacer(Modifier.weight(1f))
                            Txt(app.label, Type.bodyStrong)
                            Txt(status(app.key), Type.small, Cue.muted, maxLines = 1)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Lists, Habits

@Composable
fun AddField(hint: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().shadow(10.dp, CircleShape, spotColor = Cue.primaryDeep.copy(alpha = 0.3f)).clip(CircleShape).background(Color.White).padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(text, { text = it }, Modifier.weight(1f).padding(vertical = 15.dp), textStyle = Type.body.copy(color = Cue.ink), singleLine = true,
            cursorBrush = SolidColor(Cue.primaryDeep), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { onAdd(text.trim()); text = "" } }),
            decorationBox = { inner -> if (text.isEmpty()) Txt(hint, Type.body, Cue.muted); inner() })
        Box(Modifier.size(42.dp).clip(CircleShape).background(Cue.primaryButton).clickable(role = Role.Button, onClickLabel = "Add") { if (text.isNotBlank()) { onAdd(text.trim()); text = "" } },
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Add", tint = Color.White) }
    }
}


@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.6f)).border(1.5.dp, Color(0xFFCFC8E6), RoundedCornerShape(24.dp)).padding(20.dp)) {
        Txt(text, Type.body, Cue.muted)
    }
}


// ------------------------------------------------------------------ Journal


// ------------------------------------------------------------------ Albums

@Composable
fun AlbumsScreen(a: MainActivity, nav: Nav) {
    val dao = a.cue.engine.dao
    val albums by dao.albumsFlow().collectAsState(emptyList())
    val items by dao.allItemsFlow().collectAsState(emptyList())
    val t = now()
    Page(glowA = Color(0xFFDCE2FF), glowB = Cue.glowBlush) {
        item { BackHeader("Spaces", "A page for each thing you're doing", { nav.back() }, glow = Color(0xFF2F3A9E)) }
        item { NewSpaceCard(a, nav) }
        if (albums.isEmpty()) item { EmptyHint("Save a ticket, a prescription or a bill and I'll start a space for it too.") }
        albums.firstOrNull { it.kind == AlbumKind.TRIP }?.let { trip ->
            item {
                GradientCard(Cue.tripBrush, Color(0xFF3F45B8), Modifier.fillMaxWidth(), onClick = { nav.go("album/${trip.id}") }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Flight, null, tint = Color.White) }
                        trip.startAt?.let { Pill(TextKit.dayLabel(it, t, zone).replaceFirstChar { c -> c.uppercase() }, Cue.coral, Color(0xFF3A1208)) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Txt(trip.title.substringBefore(","), Type.title, Color.White)
                    Txt(trip.startAt?.let { TextKit.dateLabel(it, zone) } ?: "", Type.small, Color(0xFFE3E7FF))
                    Spacer(Modifier.height(12.dp))
                    val kinds = items.filter { it.albumId == trip.id }.map { when (it.type) { ItemType.CHECKLIST -> "List"; ItemType.NOTE -> "Note"; ItemType.JOURNAL -> "Journal"; else -> it.kind.replaceFirstChar { c -> c.uppercase() }.replace("Ticket", "Ticket").replace("Hotel", "Hotel") } }.distinct()
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { kinds.take(4).forEach { Pill(it, Color.White.copy(alpha = 0.18f), Color.White) } }
                }
            }
        }
        albums.filter { it.kind != AlbumKind.TRIP || it != albums.firstOrNull { a -> a.kind == AlbumKind.TRIP } }.chunked(2).forEach { row ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { al ->
                        val (tint, icon) = kindLook(al.kind)
                        LiftCard(Modifier.weight(1f).height(128.dp), tint = tint.deep, padding = 14.dp, onClick = { nav.go("album/${al.id}") }) {
                            IconChip(icon, tint)
                            Spacer(Modifier.weight(1f))
                            Txt(al.title, Type.bodyStrong, maxLines = 2)
                            Txt("${items.count { it.albumId == al.id }} items", Type.small, Cue.muted)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        items.firstOrNull { (it.expiresAt ?: 0) > t }?.let { exp ->
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.7f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Cue.coral)); Spacer(Modifier.width(12.dp))
                    Txt("Your ${exp.title.removeSuffix(" bill")}${if (exp.kind == ItemKind.BILL) " warranty" else ""} ends ${TextKit.dateLabel(exp.expiresAt!!, zone)}. I'll ring you before then.", Type.small)
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(a: MainActivity, nav: Nav, albumId: Long) {
    val dao = a.cue.engine.dao
    val scope = rememberCoroutineScope()
    val album by dao.albumFlow(albumId).collectAsState(null)
    val items by dao.itemsInAlbumFlow(albumId).collectAsState(emptyList())
    val entries by dao.allEntriesFlow().collectAsState(emptyList())
    val reminders by dao.scheduledRemindersFlow().collectAsState(emptyList())
    val t = now()
    val al = album ?: return Page { item { BackHeader("Album", null, { nav.back() }) } }
    Page(glowA = if (al.kind == AlbumKind.HEALTH) Color(0xFFFFD6E2) else Color(0xFFDCE2FF)) {
        item { BackHeader(al.title.substringBefore(","), (al.startAt?.let { TextKit.dateLabel(it, zone) + " · " } ?: "") + "made by Cue", { nav.back() }, glow = Color(0xFF2F3A9E)) }
        items.firstOrNull { it.kind == ItemKind.TICKET }?.let { item { TripPass(it) } }
        items.filter { it.type == ItemType.CHECKLIST }.forEach { list ->
            val es = entries.filter { it.itemId == list.id }
            if (es.isNotEmpty()) item { ChecklistCard(list.title, es) { e -> scope.launch { dao.updateEntry(e.copy(checked = !e.checked)) } } }
        }
        val notes = items.filter { it.type == ItemType.NOTE }
        val journal = items.filter { it.type == ItemType.JOURNAL }
        if (notes.isNotEmpty() || journal.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                notes.firstOrNull()?.let { n ->
                    Column(Modifier.weight(1f).fillMaxHeight().shadow(12.dp, RoundedCornerShape(24.dp), spotColor = Color(0xFFC98A00)).clip(RoundedCornerShape(24.dp)).background(Cue.marigoldCard).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconChip(Icons.Rounded.StickyNote2, AppTints.habits, 32.dp, 16.dp); Txt("Note", Type.smallStrong, Color(0xFF4E3800)); Txt(n.body, Type.body, Color(0xFF4E3800))
                    }
                }
                journal.firstOrNull()?.let { j ->
                    Column(Modifier.weight(1f).fillMaxHeight().shadow(12.dp, RoundedCornerShape(24.dp), spotColor = Cue.coralDeep).clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFFFE8DE), Color(0xFFFFC9B5)))).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconChip(Icons.Rounded.Book, AppTints.journal, 32.dp, 16.dp); Txt("Journal", Type.smallStrong, Color(0xFF5A1E10)); Txt(j.body, Type.body, Color(0xFF5A1E10), maxLines = 4)
                    }
                }
            }
        }
        val docs = items.filter { it.type == ItemType.GALLERY && it.kind != ItemKind.TICKET }
        if (docs.isNotEmpty()) {
            item { SectionLabel("Documents") }
            items(docs) { d -> DocRow(d, t) }
        }
        val albumReminders = reminders.filter { it.albumId == al.id && it.kind != ReminderKind.PRELOAD }.sortedBy { it.fireAt }.take(6)
        if (albumReminders.isNotEmpty()) {
            item { SectionLabel("I'll ring you") }
            items(albumReminders) { r -> ReminderRow(r, t) { nav.go("reminders") } }
        }
        item {
            Row(Modifier.fillMaxWidth().shadow(10.dp, CircleShape, spotColor = Cue.primaryDeep.copy(alpha = 0.3f)).clip(CircleShape).background(Color.White.copy(alpha = 0.85f))
                .clickable { a.talk() }.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Ask Cue about this ${if (al.kind == AlbumKind.TRIP) "trip" else "album"}", Type.bodyStrong, modifier = Modifier.weight(1f))
                PixelHead(40.dp)
            }
        }
    }
}

@Composable
fun DocRow(d: Item, t: Long, onClick: (() -> Unit)? = null) {
    val (tint, icon) = when (d.kind) {
        ItemKind.PRESCRIPTION -> AppTints.reminders to Icons.Rounded.Medication; ItemKind.LAB_REPORT -> AppTints.reminders to Icons.Rounded.Science
        ItemKind.INSURANCE, ItemKind.VEHICLE -> AppTints.renewals to Icons.Rounded.Shield; ItemKind.BILL -> AppTints.papers to Icons.Rounded.Receipt
        ItemKind.HOTEL -> AppTints.albums to Icons.Rounded.Hotel; ItemKind.ID_DOC -> AppTints.people to Icons.Rounded.Badge
        ItemKind.PLACE_SAVE -> AppTints.journal to Icons.Rounded.LocalCafe; else -> AppTints.focus to Icons.Rounded.Image
    }
    val f = ticketFields(d)
    val sub = listOfNotNull(f.optString("doctor").takeIf { it.isNotBlank() }?.let { "Dr. $it" }, f.optString("amount").takeIf { it.isNotBlank() }?.let { "₹$it" },
        f.optString("warranty").takeIf { it.isNotBlank() }?.let { "$it warranty" }, d.expiresAt?.let { "until ${TextKit.dateLabel(it, zone)}" },
        "saved ${TextKit.dayLabel(d.createdAt, t, zone)}").joinToString(" · ")
    LiftCard(Modifier.fillMaxWidth(), tint = tint.deep, padding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip(icon, tint, 38.dp, 19.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Txt(d.title, Type.bodyStrong, maxLines = 1); Txt(sub, Type.small, Cue.muted, maxLines = 2) }
        }
    }
}

// ------------------------------------------------------------------ Reminders, Papers, Renewals, People





// ------------------------------------------------------------------ Practice, Focus, Search

@Composable
fun PracticeScreen(a: MainActivity, nav: Nav) {
    val dao = a.cue.engine.dao
    val notes by dao.itemsOfTypeFlow(ItemType.NOTE).collectAsState(emptyList())
    var enabled by remember { mutableStateOf(a.cue.settings.practiceEnabled) }
    val scope = rememberCoroutineScope()
    Page(glowA = Color(0xFFFFD3B5)) {
        item { BackHeader("English practice", "A 5-minute call that gently corrects you", { nav.back() }, glow = Cue.coralDeep) }
        item {
            GradientCard(Brush.linearGradient(listOf(Color(0xFFFFB49C), Color(0xFFF27A55), Color(0xFFC24A2E))), Cue.coralDeep, Modifier.fillMaxWidth()) {
                Txt("Talk for five minutes. I'll keep the conversation going and fix one mistake at a time.", Type.lead, Color.White)
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Start a practice call", Modifier.fillMaxWidth(), brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFFFF1EA))), shadow = Cue.coralDeep, icon = Icons.Rounded.Call, textColor = Cue.coralText) { a.talk("PRACTICE") }
            }
        }
        item {
            LiftCard(Modifier.fillMaxWidth(), tint = Cue.coralDeep) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Txt("Ring me daily", Type.bodyStrong); Txt("At ${TextKit.clock(TextKit.nextDaily(a.cue.settings.practiceMinutes, System.currentTimeMillis(), zone), zone)}", Type.small, Cue.muted) }
                    androidx.compose.material3.Switch(enabled, { enabled = it; a.cue.settings.practiceEnabled = it; scope.launch { a.cue.engine.planner.reconcile() } })
                }
            }
        }
        val practice = notes.filter { it.kind == "practice" }
        if (practice.isNotEmpty()) { item { SectionLabel("Your corrections") }; items(practice) { n -> LiftCard(Modifier.fillMaxWidth(), tint = Cue.coralDeep) { Txt(n.title, Type.smallStrong, Cue.muted); Txt(n.body, Type.body) } } }
    }
}


@Composable
fun SearchScreen(a: MainActivity, nav: Nav) {
    val engine = a.cue.engine
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val t = now()
    fun run(q: String) { query = q; scope.launch { results = engine.search(q); searched = true } }
    Page(glowA = Cue.glowLavender, glowB = Cue.glowBlush) {
        item { BackHeader("Find anything", null, { nav.back() }) }
        item {
            Row(Modifier.fillMaxWidth().shadow(14.dp, CircleShape, spotColor = Cue.primaryDeep.copy(alpha = 0.45f)).clip(CircleShape).background(Color.White).border(2.dp, Cue.primary, CircleShape)
                .padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Cue.primaryDeep); Spacer(Modifier.width(10.dp))
                BasicTextField(query, { query = it }, Modifier.weight(1f).padding(vertical = 17.dp), textStyle = Type.bodyStrong.copy(color = Cue.ink), singleLine = true,
                    cursorBrush = SolidColor(Cue.primaryDeep), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run(query) }),
                    decorationBox = { inner -> if (query.isEmpty()) Txt("café last week, AC bill…", Type.body, Cue.muted); inner() })
                Box(Modifier.size(42.dp).clip(CircleShape).background(Cue.ribbonBrush).clickable(role = Role.Button, onClickLabel = "Ask by voice") { a.talk() },
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Mic, "Ask by voice", tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
        if (searched) item { SectionLabel(if (results.isEmpty()) "Nothing found for \"$query\"" else "${results.size} found") }
        items(results.take(12)) { DocRowAny(it, t) }
        item { SectionLabel("Try") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("AC bill", "boarding pass", "café", "hotel", "lab report", "prescription").forEach { s ->
                    Box(Modifier.height(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.8f)).clickable { run(s) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) { Txt(s, Type.smallStrong) }
                }
            }
        }
    }
}

@Composable
fun DocRowAny(d: Item, t: Long) {
    if (d.type == ItemType.GALLERY) DocRow(d, t) else LiftCard(Modifier.fillMaxWidth(), tint = AppTints.lists.deep, padding = 14.dp) {
        Txt(d.title, Type.bodyStrong, maxLines = 1); Txt("${d.type.lowercase().replaceFirstChar { it.uppercase() }} · ${TextKit.dayLabel(d.createdAt, t, zone)}", Type.small, Cue.muted)
    }
}
