package com.cue.genui

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cue.agent.HabitDays
import com.cue.agent.TextKit
import com.cue.domain.*
import com.cue.ui.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.ZoneId

/*
 * Cue's own A2UI components. The model only ever names a row by its number ("listId": 12); the component reads the
 * real data from the phone's database and draws it with Cue's design system. A PNR, a dose or a lab value never has to
 * pass through the model to appear on screen, and the card stays live: tick an item and it updates in place.
 */

private val zone: ZoneId get() = ZoneId.systemDefault()

private fun Number?.id(): Long = this?.toLong() ?: -1L

/** A card frame shared by Cue's components. */
@Composable
private fun CueCard(tint: AppTint, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, status: String?, modifier: Modifier, onOpen: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    LiftCard(modifier.fillMaxWidth(), tint = tint.deep, radius = 24.dp, padding = 12.dp) {
        Row(Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen) else Modifier), verticalAlignment = Alignment.CenterVertically) {
            IconChip(icon, tint, 30.dp, 15.dp); Spacer(Modifier.width(10.dp))
            Txt(title, Type.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1)
            if (status != null) Txt(status, Type.smallStrong, Cue.muted)
            if (onOpen != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open", tint = Cue.muted)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Missing(what: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Cue.primaryTint).padding(12.dp)) { Txt("$what isn't on this phone any more.", Type.small, Cue.muted) }
}

/** A checklist from Lists, live: tick here and it's ticked everywhere. */
object CueListComponent : A2uiComponent {
    private val listId = A2uiProperty.number("listId", required = true, description = "The list's number from the user's data (LISTS).")
    private val showDone = A2uiProperty.boolean("showDone", description = "Also show ticked entries. Default true.")
    override val name = "CueList"
    override val description = "One of the user's checklists, drawn live from the phone with tick boxes the user can tap. Use for packing lists, to-dos, groceries."
    override val properties = listOf(listId, showDone)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val env = LocalCueGen.current
        val id = properties[listId].id()
        val all by remember(id) { env.dao.allEntriesFlow().map { es -> es.filter { it.itemId == id } } }.collectAsState(emptyList())
        var title by remember(id) { mutableStateOf<String?>(null) }
        LaunchedEffect(id) { title = env.dao.item(id)?.title ?: "" }
        if (title == "") return Missing("That list", modifier)
        val scope = rememberCoroutineScope()
        val shown = if (properties[showDone] == false) all.filter { !it.checked } else all
        CueCard(AppTints.lists, Icons.Rounded.Checklist, title ?: "List", "${all.count { it.checked }} of ${all.size}", modifier, onOpen = { env.open("lists") }) {
            shown.sortedWith(compareBy<ChecklistEntry> { it.checked }.thenBy { it.position }).forEach { e ->
                CheckRow(e.text, e.checked, onToggle = { scope.launch { env.dao.updateEntry(e.copy(checked = !e.checked)) } })
            }
            if (shown.isEmpty()) Txt("Nothing here.", Type.small, Cue.muted, modifier = Modifier.padding(8.dp))
        }
    }
}

/** What's coming up: rings and nudges, soonest first. */
object CueRemindersComponent : A2uiComponent {
    private val limit = A2uiProperty.number("limit", description = "How many to show, default 4.")
    private val day = A2uiProperty.string("day", description = "Only this day: 'today' or 'tomorrow'. Empty for any.")
    override val name = "CueReminders"
    override val description = "The user's upcoming reminders and calls, live from the phone."
    override val properties = listOf(limit, day)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val env = LocalCueGen.current
        val rs by env.dao.scheduledRemindersFlow().collectAsState(emptyList())
        val now = System.currentTimeMillis()
        val want = properties[day]?.lowercase()
        val target = when (want) { "today" -> TextKit.dayKey(now, zone); "tomorrow" -> TextKit.dayKey(now + 86_400_000, zone); else -> null }
        val shown = rs.filter { it.kind !in setOf(ReminderKind.PRELOAD, ReminderKind.AIRPORT_BACKUP) && (target == null || TextKit.dayKey(it.fireAt, zone) == target) }
            .sortedBy { it.fireAt }.take(properties[limit]?.toInt() ?: 4)
        CueCard(AppTints.reminders, Icons.Rounded.Alarm, "Coming up", if (shown.isEmpty()) null else "${shown.size}", modifier, onOpen = { env.open("reminders") }) {
            if (shown.isEmpty()) Txt("Nothing planned.", Type.small, Cue.muted, modifier = Modifier.padding(8.dp))
            shown.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(TextKit.clock(r.fireAt, zone), Type.smallStrong, AppTints.reminders.text, modifier = Modifier.width(70.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(r.text.ifBlank { "Reminder" }, Type.body, maxLines = 2)
                        Txt(TextKit.dayLabel(r.fireAt, now, zone).replaceFirstChar { it.uppercase() } + if (r.surface == Surface.RING) " · rings" else "", Type.tiny, Cue.muted)
                    }
                }
            }
        }
    }
}

/** A habit with its streak and a done button. */
object CueHabitComponent : A2uiComponent {
    private val habitId = A2uiProperty.number("habitId", required = true, description = "The habit's number (HABITS).")
    override val name = "CueHabit"
    override val description = "One of the user's habits: streak, the last 7 days, and a button to mark today done."
    override val properties = listOf(habitId)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val env = LocalCueGen.current
        val id = properties[habitId].id()
        val habits by env.dao.habitsFlow().collectAsState(null)
        val logs by env.dao.habitLogsFlow().collectAsState(emptyList())
        val h = habits?.firstOrNull { it.id == id }
        if (habits != null && h == null) return Missing("That habit", modifier)
        if (h == null) return
        val scope = rememberCoroutineScope()
        val today = java.time.LocalDate.now()
        val mine = logs.filter { it.habitId == id }
        val done = mine.filter { it.done }.map { it.day }.toSet()
        val streak = HabitDays.streak(h, mine, today, zone)
        val brown = Color(0xFF4E3800)
        Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Cue.marigoldCard).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(Icons.Rounded.LocalFireDepartment, AppTints.habits, 34.dp, 17.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Txt(h.name, Type.bodyStrong, brown); Txt("$streak-day streak", Type.small, brown) }
                val doneToday = today.toString() in done
                SoftButton(if (doneToday) "Done today" else "Mark done", color = if (doneToday) Color.White.copy(alpha = 0.5f) else Color.White, textColor = brown, height = 38.dp) {
                    scope.launch { env.dao.upsertHabitLog(HabitLog(habitId = id, day = today.toString(), done = !doneToday)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (6 downTo 0).forEach { back ->
                    val d = today.minusDays(back.toLong())
                    Box(Modifier.size(26.dp).clip(CircleShape).background(if (d.toString() in done) Color(0xFFC77700) else Color.White.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                        Txt(d.dayOfWeek.name.take(1), Type.tiny, if (d.toString() in done) Color.White else brown)
                    }
                }
            }
        }
    }
}

/** A saved paper (ticket, bill, prescription, report): the fields stay on the phone. */
object CuePaperComponent : A2uiComponent {
    private val itemId = A2uiProperty.number("itemId", required = true, description = "The paper's number (PAPERS).")
    override val name = "CuePaper"
    override val description = "A saved document drawn from the phone with its private details (PNR, seat, amounts, doctor, dates). Use it instead of writing those details in Text."
    override val properties = listOf(itemId)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val env = LocalCueGen.current
        val id = properties[itemId].id()
        var item by remember(id) { mutableStateOf<Item?>(null) }
        var loaded by remember(id) { mutableStateOf(false) }
        LaunchedEffect(id) { item = env.dao.item(id); loaded = true }
        val d = item ?: return if (loaded) Missing("That paper", modifier) else Unit
        val f = runCatching { JSONObject(d.fields) }.getOrDefault(JSONObject())
        if (d.kind == ItemKind.TICKET) {
            GradientCard(Cue.tripBrush, Color(0xFF3F45B8), modifier.fillMaxWidth(), radius = 24.dp, padding = 16.dp, onClick = { d.albumId?.let { env.open("album/$it") } }) {
                Txt("Boarding pass" + (f.optString("flight").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), Type.smallStrong, Color(0xFFE3E7FF))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Txt(f.optString("from", "—"), Type.big.copy(fontSize = Type.big.fontSize * 0.75f), Color.White)
                    Icon(Icons.Rounded.Flight, null, tint = Color.White)
                    Txt(f.optString("to", "—"), Type.big.copy(fontSize = Type.big.fontSize * 0.75f), Color.White)
                }
                Txt(listOfNotNull(d.eventStart?.let { TextKit.dateLabel(it, zone) + ", " + TextKit.clock(it, zone) }, f.optString("pnr").takeIf { it.isNotBlank() }?.let { "PNR $it" },
                    f.optString("seat").takeIf { it.isNotBlank() }?.let { "Seat $it" }, f.optString("gate").takeIf { it.isNotBlank() }?.let { "Gate $it" }).joinToString(" · "), Type.smallStrong, Color.White)
            }
            return
        }
        val tint = when (d.kind) { ItemKind.PRESCRIPTION, ItemKind.LAB_REPORT -> AppTints.reminders; ItemKind.INSURANCE, ItemKind.VEHICLE -> AppTints.renewals; else -> AppTints.papers }
        val lines = f.keys().asSequence().filter { it !in setOf("raw", "text", "ocr") }.mapNotNull { k -> f.optString(k).takeIf { it.isNotBlank() && it.length < 80 }?.let { "${k.replace('_', ' ').replaceFirstChar { c -> c.uppercase() }}: $it" } }.take(6).toList()
        CueCard(tint, Icons.Rounded.Description, d.title, d.kind.replace('_', ' '), modifier, onOpen = { env.open(d.albumId?.let { "album/$it" } ?: "papers") }) {
            lines.forEach { Txt(it, Type.small, Cue.ink, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
            d.expiresAt?.let { Txt("Ends ${TextKit.dateLabel(it, zone)}", Type.smallStrong, Cue.coralText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
            if (lines.isEmpty() && d.body.isNotBlank()) Txt(d.body, Type.small, Cue.muted, maxLines = 4, modifier = Modifier.padding(8.dp))
        }
    }
}

/** A note or a journal day, read from the phone. */
object CueNoteComponent : A2uiComponent {
    private val itemId = A2uiProperty.number("itemId", required = true, description = "The note's or journal entry's number (NOTES or JOURNAL).")
    override val name = "CueNote"
    override val description = "One of the user's notes or journal days, shown in full from the phone."
    override val properties = listOf(itemId)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val env = LocalCueGen.current
        val id = properties[itemId].id()
        var item by remember(id) { mutableStateOf<Item?>(null) }
        var loaded by remember(id) { mutableStateOf(false) }
        LaunchedEffect(id) { item = env.dao.item(id); loaded = true }
        val n = item ?: return if (loaded) Missing("That note", modifier) else Unit
        val journal = n.type == ItemType.JOURNAL
        Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (journal) Brush.linearGradient(listOf(Color(0xFFFFF1EA), Color(0xFFFFDCCD))) else Cue.marigoldCard)
            .clickable { env.open(if (journal) "journal" else "notes") }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(if (journal) "Journal · " + TextKit.dateLabel(n.eventStart ?: n.createdAt, zone) else "Note", Type.smallStrong, if (journal) Color(0xFF8E2713) else Color(0xFF7A5A10))
            Txt(n.body.ifBlank { n.title }, Type.body, if (journal) Color(0xFF5A1E10) else Color(0xFF4E3800))
        }
    }
}

/** What's pending with one person. */
object CuePersonComponent : A2uiComponent {
    private val personId = A2uiProperty.number("personId", required = true, description = "The person's number (PEOPLE).")
    override val name = "CuePerson"
    override val description = "What the user owes one person, with tick boxes to mark items done."
    override val properties = listOf(personId)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val env = LocalCueGen.current
        val id = properties[personId].id()
        val people by env.dao.peopleFlow().collectAsState(null)
        val pending by env.dao.openPendingFlow().collectAsState(emptyList())
        val p = people?.firstOrNull { it.id == id }
        if (people != null && p == null) return Missing("That person", modifier)
        if (p == null) return
        val scope = rememberCoroutineScope()
        val mine = pending.filter { it.personId == id }
        CueCard(AppTints.people, Icons.Rounded.People, p.name, if (mine.isEmpty()) "All clear" else "${mine.size} waiting", modifier, onOpen = { env.open("people") }) {
            mine.forEach { item -> CheckRow(item.text, false, onToggle = { scope.launch { env.dao.updatePending(item.copy(status = "DONE")) } }) }
        }
    }
}

/** A big number with a label: a streak, a total, a countdown. */
object CueStatComponent : A2uiComponent {
    private val label = A2uiProperty.dynamicString("label", required = true, description = "What the number is, like 'Days to Goa'.")
    private val value = A2uiProperty.dynamicString("value", required = true, description = "The number or short value, like '4' or '₹2,400'.")
    private val caption = A2uiProperty.dynamicString("caption", description = "Optional small line under it.")
    override val name = "CueStat"
    override val description = "A big number tile. Put two or three in a Row for a summary."
    override val properties = listOf(label, value, caption)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val l = properties.bind(label) ?: ""
        val v = properties.bind(value) ?: ""
        val c = properties.bind(caption)
        Column(modifier.clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFFF1ECFF), Color(0xFFE4DCFF)))).padding(14.dp)) {
            Txt(l, Type.smallStrong, Cue.primaryDeep, maxLines = 1)
            Txt(v, Type.title, Cue.ink, maxLines = 1)
            if (!c.isNullOrBlank()) Txt(c, Type.tiny, Cue.muted, maxLines = 2)
        }
    }
}

/** A chip that opens one of Cue's own screens. */
object CueOpenComponent : A2uiComponent {
    private val label = A2uiProperty.string("label", required = true, description = "Chip text, like 'Open Lists'.")
    private val route = A2uiProperty.stringEnum("route", enumValues = listOf("lists", "notes", "habits", "journal", "reminders", "papers", "renewals", "people", "focus", "albums", "search"),
        description = "Which screen. For a space use CueSpace.")
    override val name = "CueOpen"
    override val description = "A small chip that opens one of Cue's app screens."
    override val properties = listOf(label, route)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val env = LocalCueGen.current
        Txt(properties[label] ?: "Open", Type.smallStrong, Cue.primaryDeep, modifier = modifier.clip(CircleShape).background(Color.White).border(1.dp, Cue.line, CircleShape)
            .clickable { properties[route]?.let(env::open) }.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

/** A space (trip, visit, event) as a tappable card. */
object CueSpaceComponent : A2uiComponent {
    private val spaceId = A2uiProperty.number("spaceId", required = true, description = "The space's number (SPACES).")
    override val name = "CueSpace"
    override val description = "One of the user's spaces (a trip, a doctor visit, an event) as a card that opens its page."
    override val properties = listOf(spaceId)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val env = LocalCueGen.current
        val id = properties[spaceId].id()
        val al by env.dao.albumFlow(id).collectAsState(null)
        val a = al ?: return
        GradientCard(Cue.tripBrush, Color(0xFF3F45B8), modifier.fillMaxWidth(), radius = 24.dp, padding = 16.dp, onClick = { env.open("album/$id") }) {
            Txt(a.title, Type.title, Color.White)
            Txt(a.startAt?.let { "From " + TextKit.dateLabel(it, zone) } ?: "Open the page", Type.small, Color(0xFFE3E7FF))
        }
    }
}

val cueComponents: List<A2uiComponent> = listOf(
    CueListComponent, CueRemindersComponent, CueHabitComponent, CuePaperComponent, CueNoteComponent, CuePersonComponent,
    CueStatComponent, CueOpenComponent, CueSpaceComponent,
)
