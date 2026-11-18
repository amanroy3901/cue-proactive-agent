package com.cue.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cue.agent.CallContext
import com.cue.agent.CueToolExecutor
import com.cue.agent.TextKit
import com.cue.brain.ToolCall
import com.cue.brain.ToolResult
import com.cue.domain.*
import com.cue.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Every app works by hand: add, edit and delete without a call. Writes go to the same rows Cue's tools use, and
 * anything that changes a ring goes through the scheduler and the planner, the same as a voice change.
 */

private val zone get() = java.time.ZoneId.systemDefault()
private fun cap(s: String) = s.trim().replaceFirstChar { it.uppercase() }

/** Runs one of Cue's own tools from the screen (no call): the same checks and side effects as by voice. */
suspend fun MainActivity.tool(name: String, args: Map<String, String> = emptyMap()): ToolResult =
    CueToolExecutor(cue.engine, CallContext(null, "TALK", null, null)).execute(ToolCall(name, args))

// ------------------------------------------------------------------ Lists

@Composable
fun ListsScreen(a: MainActivity, nav: Nav) {
    val dao = a.cue.engine.dao
    val scope = rememberCoroutineScope()
    val ed = rememberEditor()
    val lists by dao.itemsOfTypeFlow(ItemType.CHECKLIST).collectAsState(emptyList())
    val entries by dao.allEntriesFlow().collectAsState(emptyList())
    val albums by dao.albumsFlow().collectAsState(emptyList())
    Page(glowA = Color(0xFFEBDDFF)) {
        item { BackHeader("Lists", "Tap to tick. Hold an item to edit it.", { nav.back() }) }
        item {
            NewButton("New list") {
                ed.open(EditRequest("New list", listOf(EditField("Name", "")), saveLabel = "Create") { v ->
                    if (v[0].isNotBlank()) scope.launch { dao.insertItem(Item(type = ItemType.CHECKLIST, title = cap(v[0]), source = "user", createdAt = System.currentTimeMillis())) }
                })
            }
        }
        item {
            AddField("Add to today's list") { text ->
                scope.launch {
                    val now = System.currentTimeMillis()
                    val today = lists.firstOrNull { it.albumId == null && it.title == "Today" && TextKit.dayKey(it.createdAt, zone) == TextKit.dayKey(now, zone) }
                    val id = today?.id ?: dao.insertItem(Item(type = ItemType.CHECKLIST, title = "Today", source = "user", createdAt = now))
                    val start = (entries.filter { it.itemId == id }.maxOfOrNull { it.position } ?: 0) + 1
                    TextKit.splitList(text).forEachIndexed { i, s -> dao.insertEntry(ChecklistEntry(itemId = id, text = cap(s), position = start + i)) }
                }
            }
        }
        lists.forEach { list ->
            item(key = "list-${list.id}") {
                EditableList(a, list, entries.filter { it.itemId == list.id }, albums, ed)
            }
        }
        if (lists.isEmpty()) item { EmptyHint("No lists yet. Make one above, or on a call say \"pack charger and passport\".") }
    }
}

/** A list with its own menu (rename, move, clear, delete), hold-to-edit entries and an add field. */
@Composable
fun EditableList(a: MainActivity, list: Item, es: List<ChecklistEntry>, albums: List<Album>, ed: Editor) {
    val dao = a.cue.engine.dao
    val scope = rememberCoroutineScope()
    val space = list.albumId?.let { id -> albums.firstOrNull { it.id == id } }
    LiftCard(Modifier.fillMaxWidth(), tint = Cue.primaryDeep, radius = 28.dp, padding = 6.dp) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconChip(Icons.Rounded.Checklist, AppTints.lists, 30.dp, 15.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Txt(list.title, Type.bodyStrong, maxLines = 1)
                if (space != null) Txt(space.title.substringBefore(","), Type.tiny, Cue.muted, maxLines = 1)
            }
            Txt("${es.count { it.checked }} of ${es.size}", Type.smallStrong, Cue.muted)
            MoreMenu(listOf(
                "Rename" to { ed.open(EditRequest("Rename list", listOf(EditField("Name", list.title))) { v -> if (v[0].isNotBlank()) scope.launch { dao.updateItem(list.copy(title = cap(v[0]))) } }) },
                "Move to a space" to {
                    val pick = mutableStateOf(list.albumId?.toString() ?: "")
                    ed.open(EditRequest("Move ${list.title}", emptyList(), extra = { ChoiceRow("Space", spaceOptions(albums), pick) }) {
                        scope.launch { dao.updateItem(list.copy(albumId = pick.value.toLongOrNull())) }
                    })
                },
                "Clear ticked" to { scope.launch { es.filter { it.checked }.forEach { dao.deleteEntry(it.id) } } },
                "Delete list" to {
                    ed.open(EditRequest("Delete ${list.title}?", emptyList(), saveLabel = "Delete", deleteLabel = null) {
                        scope.launch { es.forEach { dao.deleteEntry(it.id) }; dao.deleteItem(list.id) }
                    })
                },
            ))
        }
        es.sortedWith(compareBy<ChecklistEntry> { it.checked }.thenBy { it.position }).forEach { e ->
            CheckRow(e.text, e.checked, onToggle = { scope.launch { dao.updateEntry(e.copy(checked = !e.checked)) } }, onLongPress = {
                ed.open(EditRequest("Edit item", listOf(EditField("Item", e.text)), deleteLabel = "Delete \"${e.text}\"?",
                    onDelete = { scope.launch { dao.deleteEntry(e.id) } }) { v -> if (v[0].isNotBlank()) scope.launch { dao.updateEntry(e.copy(text = cap(v[0]))) } })
            })
        }
        Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            InlineAdd("Add to ${list.title}") { text ->
                scope.launch {
                    val start = (es.maxOfOrNull { it.position } ?: 0) + 1
                    TextKit.splitList(text).forEachIndexed { i, s -> dao.insertEntry(ChecklistEntry(itemId = list.id, text = cap(s), position = start + i)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Habits

@Composable
fun HabitsScreen(a: MainActivity, nav: Nav) {
    val dao = a.cue.engine.dao
    val scope = rememberCoroutineScope()
    val ed = rememberEditor()
    val c = LocalContext.current
    val habits by dao.habitsFlow().collectAsState(emptyList())
    val logs by dao.habitLogsFlow().collectAsState(emptyList())
    val today = java.time.LocalDate.now()
    val brown = Color(0xFF4E3800)
    Page(glowA = Color(0xFFFFE9B8)) {
        item { BackHeader("Habits", "Tap a day to mark it. I can also call to check.", { nav.back() }, glow = Color(0xFFC98A00)) }
        item { AddField("New habit, like Walk") { name -> scope.launch { if (dao.habitByName(name) == null) dao.insertHabit(Habit(name = cap(name), createdAt = System.currentTimeMillis())) } } }
        items(habits, key = { it.id }) { h ->
            val mine = logs.filter { it.habitId == h.id }
            val done = mine.filter { it.done }.map { it.day }.toSet()
            val streak = com.cue.agent.HabitDays.streak(h, mine, today, zone)
            val doneToday = today.toString() in done
            val paused = h.pausedAt(System.currentTimeMillis())
            fun pausedDay(day: java.time.LocalDate) = mine.any { it.day == day.toString() && it.paused } || com.cue.agent.HabitDays.pausedOn(h, day, zone)
            fun toggle(day: java.time.LocalDate) = scope.launch { dao.upsertHabitLog(HabitLog(habitId = h.id, day = day.toString(), done = day.toString() !in done)) }
            Column(Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(26.dp), spotColor = Color(0xFFC98A00)).clip(RoundedCornerShape(26.dp)).background(Cue.marigoldCard).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(Icons.Rounded.LocalFireDepartment, AppTints.habits)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(h.name, Type.bodyStrong, brown)
                        Txt("$streak-day streak" + when {
                            paused -> " · paused" + (h.pausedUntil?.takeIf { it != Long.MAX_VALUE }?.let { " till ${TextKit.dateLabel(it, zone)}" } ?: "")
                            h.checkMinutes != null -> " · I call at ${com.cue.agent.Anchors.clock(h.checkMinutes!!)}"
                            else -> ""
                        }, Type.small, brown)
                    }
                    if (!paused) SoftButton(if (doneToday) "Done today" else "Mark done", color = if (doneToday) Color.White.copy(alpha = 0.5f) else Color.White, textColor = brown, height = 40.dp) { toggle(today) }
                    MoreMenu(listOfNotNull(
                        "Rename" to { ed.open(EditRequest("Rename habit", listOf(EditField("Name", h.name))) { v -> if (v[0].isNotBlank()) scope.launch { dao.updateHabit(h.copy(name = cap(v[0]))) } }) },
                        (if (h.checkMinutes == null) "Call me daily…" else "Change call time") to {
                            pickTime(c, h.checkMinutes ?: (19 * 60)) { m -> scope.launch { dao.updateHabit(h.copy(checkMinutes = m)); a.cue.engine.planner.reconcile() } }
                        },
                        if (h.checkMinutes != null) "Stop daily call" to { scope.launch { dao.updateHabit(h.copy(checkMinutes = null)); a.cue.engine.planner.reconcile() } } else null,
                        (if (paused) "Resume" else "Pause") to {
                            scope.launch {
                                val now = System.currentTimeMillis()
                                if (paused) {
                                    var day = TextKit.local(h.pausedFrom!!, zone).toLocalDate()
                                    while (!day.isAfter(today)) { if (day.toString() !in done) dao.upsertHabitLog(HabitLog(habitId = h.id, day = day.toString(), done = false, paused = true)); day = day.plusDays(1) }
                                    dao.updateHabit(h.copy(pausedFrom = null, pausedUntil = null))
                                } else dao.updateHabit(h.copy(pausedFrom = now, pausedUntil = Long.MAX_VALUE))
                                a.cue.engine.planner.reconcile()
                            }
                        },
                        "Delete habit" to {
                            ed.open(EditRequest("Delete ${h.name}?", emptyList(), saveLabel = "Delete") {
                                scope.launch { a.tool("habit_remove", mapOf("habit" to h.name, "confirm" to "yes")) }
                            })
                        },
                    ), tint = brown)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    (6 downTo 0).forEach { back ->
                        val day = today.minusDays(back.toLong())
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val isPaused = day.toString() !in done && pausedDay(day)
                            Box(Modifier.size(32.dp).clip(CircleShape).background(if (day.toString() in done) Color(0xFFC77700) else if (isPaused) Color(0xFFE9DDB8) else Color.White.copy(alpha = 0.6f))
                                .clickable(onClickLabel = "Mark ${day.dayOfWeek.name.lowercase()}") { toggle(day) }, contentAlignment = Alignment.Center) {
                                if (day.toString() in done) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                if (isPaused) Icon(Icons.Rounded.Pause, null, tint = Color(0xFF8A6A1E), modifier = Modifier.size(14.dp))
                            }
                            Txt(day.dayOfWeek.name.take(1), Type.tiny, brown)
                        }
                    }
                }
            }
        }
        if (habits.isEmpty()) item { EmptyHint("Add a habit above. Tap a day to mark it, or let me call and ask.") }
    }
}

// ------------------------------------------------------------------ Journal

@Composable
fun JournalScreen(a: MainActivity, nav: Nav) {
    val dao = a.cue.engine.dao
    val scope = rememberCoroutineScope()
    val ed = rememberEditor()
    val t = now()
    val entries by dao.itemsOfTypeFlow(ItemType.JOURNAL).collectAsState(emptyList())
    fun edit(e: Item) = ed.open(EditRequest("Edit entry", listOf(EditField("Entry", e.body, singleLine = false)), deleteLabel = "Delete this day's entry?",
        onDelete = { scope.launch { dao.deleteItem(e.id) } }) { v -> scope.launch { dao.updateItem(e.copy(body = v[0])) } })
    Page(glowA = Color(0xFFFFD9C8)) {
        item { BackHeader("Your days", "Write here, or tell me on the night call", { nav.back() }, glow = Cue.coralDeep) }
        item { AddField("Add a line to today") { text -> scope.launch { a.tool("journal_add", mapOf("text" to text)) } } }
        if (entries.isEmpty()) item { EmptyHint("Nothing yet. Write a line above, or talk to me tonight.") }
        entries.forEachIndexed { i, e ->
            val f = ticketFields(e)
            val moods = f.optString("mood").split(",").filter { it.isNotBlank() }
            val day = TextKit.dayLabel(e.eventStart ?: e.createdAt, t, zone).replaceFirstChar { it.uppercase() }
            item(key = "j-${e.id}") {
                if (i == 0) {
                    GradientCard(Cue.journalBrush, Cue.coralDeep, Modifier.fillMaxWidth(), radius = 28.dp, padding = 18.dp, onClick = { edit(e) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Txt(day, Type.smallStrong, Color(0xFFFFF0EA))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { moods.forEach { Pill(it, Color.White.copy(alpha = 0.22f), Color.White) } }
                        }
                        Spacer(Modifier.height(10.dp))
                        Txt(e.body, Type.lead, Color.White)
                        Spacer(Modifier.height(8.dp)); Txt("Tap to edit", Type.tiny, Color(0xFFFFF0EA))
                    }
                } else {
                    LiftCard(Modifier.fillMaxWidth(), tint = AppTints.focus.deep, onClick = { edit(e) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Txt(day, Type.smallStrong, Cue.muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { moods.forEach { Pill(it, Cue.primaryTint, Cue.primaryDeep) } }
                        }
                        Spacer(Modifier.height(8.dp))
                        Txt(e.body, Type.body)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Notes

/** Notes kept by other apps (practice corrections, typed-in renewals) live there, not here. */
private val notNotes = setOf("practice", "renewal")

@Composable
fun NotesScreen(a: MainActivity, nav: Nav) {
    val dao = a.cue.engine.dao
    val scope = rememberCoroutineScope()
    val ed = rememberEditor()
    val t = now()
    val notes by dao.itemsOfTypeFlow(ItemType.NOTE).collectAsState(emptyList())
    val albums by dao.albumsFlow().collectAsState(emptyList())
    val shown = notes.filter { it.kind !in notNotes }
    Page(glowA = Color(0xFFFFEFC2)) {
        item { BackHeader("Notes", "Things to keep. Tap one to edit or move it.", { nav.back() }, glow = Color(0xFFC98A00)) }
        item {
            AddField("Write a note") { text ->
                scope.launch { dao.insertItem(Item(type = ItemType.NOTE, title = cap(text).take(60), body = cap(text), source = "user", createdAt = System.currentTimeMillis())) }
            }
        }
        if (shown.isEmpty()) item { EmptyHint("A Wi-Fi password, the doctor's advice, a gate code. Type it above or say \"remember that…\" on a call.") }
        items(shown, key = { it.id }) { n ->
            val space = n.albumId?.let { id -> albums.firstOrNull { it.id == id } }
            Column(Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(22.dp), spotColor = Color(0xFFC98A00)).clip(RoundedCornerShape(22.dp)).background(Cue.marigoldCard)
                .clickable {
                    val pick = mutableStateOf(n.albumId?.toString() ?: "")
                    ed.open(EditRequest("Edit note", listOf(EditField("Note", n.body.ifBlank { n.title }, singleLine = false)), deleteLabel = "Delete this note?",
                        extra = { ChoiceRow("Space", spaceOptions(albums), pick) }, onDelete = { scope.launch { dao.deleteItem(n.id) } }) { v ->
                        if (v[0].isNotBlank()) scope.launch { dao.updateItem(n.copy(title = cap(v[0]).take(60), body = cap(v[0]), albumId = pick.value.toLongOrNull())) }
                    })
                }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(n.body.ifBlank { n.title }, Type.body, Color(0xFF4E3800))
                Txt(listOfNotNull(space?.title?.substringBefore(","), TextKit.dayLabel(n.createdAt, t, zone)).joinToString(" · "), Type.tiny, Color(0xFF7A5A10))
            }
        }
    }
}

// ------------------------------------------------------------------ Reminders

@Composable
fun RemindersScreen(a: MainActivity, nav: Nav) {
    val engine = a.cue.engine
    val dao = engine.dao
    val scope = rememberCoroutineScope()
    val ed = rememberEditor()
    val t = now()
    val reminders by dao.scheduledRemindersFlow().collectAsState(emptyList())
    val shown = com.cue.agent.collapseDaily(reminders.filter { it.kind !in setOf(ReminderKind.PRELOAD, ReminderKind.AIRPORT_BACKUP) })

    fun newReminder() {
        val at = mutableStateOf(((System.currentTimeMillis() / 3_600_000) + 1) * 3_600_000)
        val ring = mutableStateOf(true)
        ed.open(EditRequest("New reminder", listOf(EditField("What", "")), saveLabel = "Set", extra = { WhenRow(at); SwitchRow("Ring like a call (off: a quiet notification)", ring) }) { v ->
            if (v[0].isNotBlank()) scope.launch {
                val r = Reminder(kind = ReminderKind.GENERIC, text = cap(v[0]), fireAt = at.value, surface = if (ring.value) Surface.RING else Surface.NOTIFY, createdAt = System.currentTimeMillis())
                val id = dao.insertReminder(r); engine.scheduler.schedule(r.copy(id = id))
            }
        })
    }
    fun edit(r: Reminder) {
        if (r.key != null) {
            // Planner-made: its app owns the time (a habit, a medicine, the daily calls). Skipping one is fine.
            ed.open(EditRequest(r.text.ifBlank { com.cue.agent.reminderLabel(r) }, emptyList(), saveLabel = "Skip this one",
                extra = { Txt("I made this from ${owner(r)}. Change its time there; here you can skip the next one.", Type.small, Cue.muted) }) {
                scope.launch { dao.updateReminder(r.copy(status = ReminderStatus.CANCELLED)); engine.scheduler.cancel(r.id) }
            })
            return
        }
        val at = mutableStateOf(r.fireAt)
        val ring = mutableStateOf(r.surface == Surface.RING)
        ed.open(EditRequest("Edit reminder", listOf(EditField("What", r.text)), deleteLabel = "Cancel this reminder?",
            extra = { WhenRow(at); SwitchRow("Ring like a call", ring) },
            onDelete = { scope.launch { dao.updateReminder(r.copy(status = ReminderStatus.CANCELLED)); engine.scheduler.cancel(r.id) } }) { v ->
            scope.launch {
                val moved = r.copy(text = cap(v[0].ifBlank { r.text }), fireAt = at.value, attempts = 0, surface = if (ring.value) Surface.RING else Surface.NOTIFY)
                dao.updateReminder(moved); engine.scheduler.cancel(r.id); engine.scheduler.schedule(moved)
            }
        })
    }

    Page(glowA = Color(0xFFFFD6E2)) {
        item { BackHeader("Reminders", "Tap one to change it", { nav.back() }, glow = AppTints.reminders.deep) }
        item { NewButton("New reminder") { newReminder() } }
        if (shown.isEmpty()) item { EmptyHint("Nothing planned. Add one above, or say \"remind me at 7 to call Mom\" on any call.") }
        val (daily, once) = shown.partition { com.cue.agent.isDaily(it) }
        if (daily.isNotEmpty()) {
            item { SectionLabel("Every day") }
            items(daily, key = { "d${it.id}" }) { r -> ReminderRow(r, t, daily = true) { edit(r) } }
        }
        once.groupBy { TextKit.dayLabel(it.fireAt, t, zone) }.forEach { (day, rs) ->
            item { SectionLabel(day.replaceFirstChar { it.uppercase() }) }
            items(rs, key = { "o${it.id}" }) { r -> ReminderRow(r, t) { edit(r) } }
        }
    }
}

private fun owner(r: Reminder): String = when {
    r.key?.startsWith("habit:") == true -> "a habit (Habits)"
    r.key?.startsWith("plan:") == true -> "a medicine or reading plan"
    r.key?.startsWith("checkin:") == true -> "your daily calls (Settings)"
    r.key?.startsWith("practice:") == true -> "English practice"
    r.key?.startsWith("expiry:") == true -> "a renewal (Renewals)"
    r.key?.startsWith("leave:") == true -> "your flight"
    else -> "something you saved"
}

// ------------------------------------------------------------------ Papers

@Composable
fun PapersScreen(a: MainActivity, nav: Nav) {
    val dao = a.cue.engine.dao
    val scope = rememberCoroutineScope()
    val ed = rememberEditor()
    val t = now()
    val items by dao.itemsOfTypeFlow(ItemType.GALLERY).collectAsState(emptyList())
    val albums by dao.albumsFlow().collectAsState(emptyList())
    Page(glowA = Color(0xFFFBE3D2)) {
        item { BackHeader("Papers", "Tap one to rename, move or delete it", { nav.back() }, glow = AppTints.papers.deep) }
        item { AddFilesRow() }
        if (items.isEmpty()) item { EmptyHint("Add a photo or PDF of a bill, ticket or prescription, scan one, take a screenshot, or share it to Cue from any app. I read it and file it.") }
        items(items, key = { it.id }) { d ->
            DocRow(d, t) {
                val pick = mutableStateOf(d.albumId?.toString() ?: "")
                ed.open(EditRequest("Edit paper", listOf(EditField("Name", d.title), EditField("Notes", d.body, singleLine = false)), deleteLabel = "Delete ${d.title}?",
                    extra = { ChoiceRow("Space", spaceOptions(albums), pick) }, onDelete = { scope.launch { dao.deleteItem(d.id); a.cue.engine.planner.reconcile() } }) { v ->
                    scope.launch { dao.updateItem(d.copy(title = v[0].ifBlank { d.title }, body = v[1], albumId = pick.value.toLongOrNull())) }
                })
            }
        }
    }
}

// ------------------------------------------------------------------ Renewals

@Composable
fun RenewalsScreen(a: MainActivity, nav: Nav) {
    val engine = a.cue.engine
    val dao = engine.dao
    val scope = rememberCoroutineScope()
    val ed = rememberEditor()
    val t = now()
    val items by dao.expiringFlow().collectAsState(emptyList())
    Page(glowA = Color(0xFFDCEBFF)) {
        item { BackHeader("Renewals", "I ring you a week before each one ends", { nav.back() }, glow = AppTints.renewals.deep) }
        item {
            NewButton("Add a renewal") {
                val ends = mutableStateOf(System.currentTimeMillis() + 180L * 86_400_000)
                ed.open(EditRequest("Add a renewal", listOf(EditField("What ends (insurance, passport…)", "")), saveLabel = "Add", extra = { WhenRow(ends, "Ends on", withTime = false) }) { v ->
                    if (v[0].isNotBlank()) scope.launch {
                        dao.insertItem(Item(type = ItemType.NOTE, kind = "renewal", title = cap(v[0]), body = cap(v[0]), expiresAt = ends.value, source = "user", createdAt = System.currentTimeMillis()))
                        engine.planner.reconcile()
                    }
                })
            }
        }
        if (items.isEmpty()) item { EmptyHint("Add one above, or photograph a bill with a warranty and I'll find the date.") }
        items(items, key = { it.id }) { d ->
            val days = ((d.expiresAt!! - t) / 86_400_000L)
            LiftCard(Modifier.fillMaxWidth(), tint = AppTints.renewals.deep, onClick = {
                val ends = mutableStateOf(d.expiresAt!!)
                val typed = d.kind == "renewal"
                ed.open(EditRequest("Edit renewal", listOf(EditField("Name", d.title)), deleteLabel = if (typed) "Delete ${d.title}?" else "Stop tracking when ${d.title} ends?",
                    extra = { WhenRow(ends, "Ends on", withTime = false) },
                    onDelete = { scope.launch { if (typed) dao.deleteItem(d.id) else dao.updateItem(d.copy(expiresAt = null)); engine.planner.reconcile() } }) { v ->
                    scope.launch { dao.updateItem(d.copy(title = v[0].ifBlank { d.title }, expiresAt = ends.value)); engine.planner.reconcile() }
                })
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(Icons.Rounded.Autorenew, AppTints.renewals); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Txt(d.title.removeSuffix(" bill") + if (d.kind == ItemKind.BILL) " warranty" else "", Type.bodyStrong); Txt("Ends ${TextKit.dateLabel(d.expiresAt!!, zone)}", Type.small, Cue.muted) }
                    Pill(if (days < 0) "Ended" else "$days days", if (days < 30) Cue.coralTint else Color(0xFFE1EEFF), if (days < 30) Cue.coralText else AppTints.renewals.text)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ People

@Composable
fun PeopleScreen(a: MainActivity, nav: Nav) {
    val engine = a.cue.engine
    val dao = engine.dao
    val scope = rememberCoroutineScope()
    val ed = rememberEditor()
    val people by dao.peopleFlow().collectAsState(emptyList())
    val pending by dao.openPendingFlow().collectAsState(emptyList())
    fun add(person: String = "") = ed.open(EditRequest("Something you owe someone", listOf(EditField("Who", person), EditField("What's pending", "")), saveLabel = "Add") { v ->
        if (v[0].isNotBlank() && v[1].isNotBlank()) scope.launch { val p = engine.person(v[0]); dao.insertPending(PendingItem(personId = p.id, text = cap(v[1]), sourceApp = "user", createdAt = System.currentTimeMillis())) }
    })
    Page(glowA = Color(0xFFD9E9FA)) {
        item { BackHeader("People", "Tap to mark done. Hold to edit.", { nav.back() }, glow = AppTints.people.deep) }
        item { NewButton("Add") { add() } }
        val withItems = people.filter { p -> pending.any { it.personId == p.id } }
        if (withItems.isEmpty()) item { EmptyHint("Add what you owe someone above. When someone asks you for something in a message, I'll note it here too.") }
        items(withItems, key = { it.id }) { p ->
            LiftCard(Modifier.fillMaxWidth(), tint = AppTints.people.deep) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(AppTints.people.light, AppTints.people.deep))), contentAlignment = Alignment.Center) { Txt(p.name.take(1), Type.bodyStrong, Color.White) }
                    Spacer(Modifier.width(12.dp)); Txt(p.name, Type.bodyStrong, modifier = Modifier.weight(1f))
                    MoreMenu(listOf("Add for ${p.name}" to { add(p.name) }, "Mark all done" to { scope.launch { pending.filter { it.personId == p.id }.forEach { dao.updatePending(it.copy(status = "DONE")) } } }))
                }
                Spacer(Modifier.height(6.dp))
                pending.filter { it.personId == p.id }.forEach { item ->
                    CheckRow(item.text, false, onToggle = { scope.launch { dao.updatePending(item.copy(status = "DONE")) } }, onLongPress = {
                        ed.open(EditRequest("Edit", listOf(EditField("What's pending", item.text)), deleteLabel = "Delete this?",
                            onDelete = { scope.launch { dao.deletePending(item.id) } }) { v -> if (v[0].isNotBlank()) scope.launch { dao.updatePending(item.copy(text = cap(v[0]))) } })
                    })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Focus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusScreen(a: MainActivity, nav: Nav) {
    // The session is saved (a quiet reminder), so one started by voice on a call shows here too, and the other way round.
    val engine = a.cue.engine
    val scope = rememberCoroutineScope()
    val reminders by engine.dao.scheduledRemindersFlow().collectAsState(emptyList())
    val session = reminders.firstOrNull { it.kind == ReminderKind.FOCUS && it.fireAt > System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var minutes by remember { mutableIntStateOf(25) }
    var what by remember { mutableStateOf("") }
    LaunchedEffect(session?.id) { while (session != null) { now = System.currentTimeMillis(); delay(1000) } }
    val left = if (session != null) ((session.fireAt - now) / 1000).coerceAtLeast(0) else minutes * 60L
    val label = session?.let { org.json.JSONObject(it.meta).optString("what", "Focus") }
    Page(glowA = Color(0xFFE4DCF7)) {
        item { BackHeader("Focus", "Pick a length, start, and I'll stay quiet", { nav.back() }, glow = AppTints.focus.deep) }
        item {
            GradientCard(Brush.linearGradient(listOf(AppTints.focus.light, AppTints.focus.deep, AppTints.focus.text)), AppTints.focus.deep, Modifier.fillMaxWidth()) {
                Txt("%d:%02d".format(left / 60, left % 60), Type.big.copy(fontSize = sp(72f), lineHeight = sp(76f)), Color.White)
                Txt(if (session != null) "Stay with it · $label" else "Ready when you are", Type.body, Color.White.copy(alpha = 0.85f))
                if (session == null) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 25, 45, 60, 90).forEach { m ->
                            Txt("$m min", Type.smallStrong, if (m == minutes) AppTints.focus.text else Color.White, modifier = Modifier.clip(CircleShape)
                                .background(if (m == minutes) Color.White else Color.White.copy(alpha = 0.2f)).clickable { minutes = m }.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().clip(CircleShape).background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        androidx.compose.foundation.text.BasicTextField(what, { what = it }, Modifier.fillMaxWidth(), textStyle = Type.body.copy(color = Cue.ink), singleLine = true,
                            decorationBox = { inner -> if (what.isEmpty()) Txt("On what? (optional)", Type.body, Cue.muted); inner() })
                    }
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(if (session != null) "Stop" else "Start $minutes min", Modifier.fillMaxWidth(), brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFF1ECFF))),
                    shadow = AppTints.focus.deep, textColor = AppTints.focus.text) {
                    scope.launch { if (session != null) a.tool("focus_stop") else a.tool("focus_start", mapOf("minutes" to "$minutes", "what" to what.trim())) }
                }
            }
        }
    }
}
