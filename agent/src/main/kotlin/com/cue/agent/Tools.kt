package com.cue.agent

import com.cue.brain.ToolCall
import com.cue.brain.ToolExecutor
import com.cue.brain.ToolParam
import com.cue.brain.ToolResult
import com.cue.brain.ToolSpec
import com.cue.domain.Album
import com.cue.domain.AlbumKind
import com.cue.domain.ChecklistEntry
import com.cue.domain.Habit
import com.cue.domain.HabitLog
import com.cue.domain.Item
import com.cue.domain.ItemKind
import com.cue.domain.ItemType
import com.cue.domain.PendingItem
import com.cue.domain.Person
import com.cue.domain.Reminder
import com.cue.domain.ReminderKind
import com.cue.domain.ReminderStatus
import com.cue.domain.Surface
import java.time.LocalDate
import java.util.Locale

/** The tools a brain may call. Defined once; every provider writes them into its own prompt format. */
object CueTools {
    val checklistAdd = ToolSpec("checklist_add", "Add entries to the active list", listOf(ToolParam("entries", "string", "comma-separated entries")))
    val checklistTick = ToolSpec("checklist_tick", "Tick an entry the user says is done", listOf(ToolParam("entry", "string", "entry words or number")))
    val noteSave = ToolSpec("note_save", "Save a fact the user wants remembered", listOf(ToolParam("text", "string", "the note")))
    val reminderSet = ToolSpec("reminder_set", "Ring the user later", listOf(
        ToolParam("text", "string", "what to ask or say"), ToolParam("when", "string", "spoken time, like 6 pm or in 20 minutes"),
        ToolParam("check", "string", "yes if Cue should check whether it happened", required = false)))
    val eventMove = ToolSpec("event_move", "Move the active trip's flight to a new time", listOf(ToolParam("when", "string", "new time")))
    val habitCheckin = ToolSpec("habit_checkin", "Record whether a habit happened today", listOf(ToolParam("habit", "string", "habit name"), ToolParam("done", "string", "yes or no")))
    val doseSchedule = ToolSpec("dose_schedule", "Set medicine reminders", listOf(
        ToolParam("medicine", "string", "medicine name"), ToolParam("per_day", "string", "times per day"), ToolParam("days", "string", "number of days")))
    val doseTaken = ToolSpec("dose_taken", "Record the current dose as taken or not", listOf(ToolParam("done", "string", "yes or no")))
    val pendingAdd = ToolSpec("pending_add", "Remember something pending with a person", listOf(ToolParam("person", "string", "name"), ToolParam("text", "string", "what is pending")))
    val pendingResolve = ToolSpec("pending_resolve", "Mark something with a person as done", listOf(ToolParam("person", "string", "name")))
    val albumSearch = ToolSpec("album_search", "Find a saved document or note", listOf(ToolParam("query", "string", "what to find")))
    val journalLookup = ToolSpec("journal_lookup", "Read back what the user did on a day", listOf(ToolParam("day", "string", "like last Sunday or yesterday")))

    val converse = listOf(checklistAdd, checklistTick, noteSave, reminderSet, eventMove, habitCheckin, doseSchedule, doseTaken,
        pendingAdd, pendingResolve, albumSearch, journalLookup)
    /** The small model handles few tools well; it gets the core five plus the call-specific one. */
    val compact = listOf(checklistAdd, checklistTick, noteSave, reminderSet, eventMove)
}

/** What a call or a Talk session is about. Tools act relative to it. */
data class CallContext(
    val callId: Long?,
    val kind: String,
    val albumId: Long?,
    val reminderId: Long?,
    val habitName: String? = null,
)

/**
 * Validates and executes tool calls against memory. No delete tool exists; everything a model proposes is checked here.
 */
class CueToolExecutor(
    private val env: AgentEnv,
    var context: CallContext,
) : ToolExecutor {
    private val dao get() = env.dao
    private val zone get() = env.clock.zone
    val changes = mutableListOf<String>()

    override suspend fun execute(call: ToolCall): ToolResult {
        val a = call.args
        val r = when (call.name) {
            "checklist_add" -> checklistAdd(a["entries"].orEmpty())
            "checklist_tick" -> checklistTick(a["entry"].orEmpty())
            "note_save" -> noteSave(a["text"].orEmpty())
            "reminder_set" -> reminderSet(a["text"].orEmpty(), a["when"].orEmpty(), a["check"] == "yes")
            "event_move" -> eventMove(a["when"].orEmpty())
            "habit_checkin" -> habitCheckin(a["habit"] ?: context.habitName ?: "", a["done"] != "no")
            "dose_schedule" -> doseSchedule(a["medicine"].orEmpty(), TextKit.number(a["per_day"]) ?: 2, TextKit.number(a["days"]) ?: 5)
            "dose_taken" -> doseTaken(a["done"] != "no")
            "pending_add" -> pendingAdd(a["person"].orEmpty(), a["text"].orEmpty())
            "pending_resolve" -> pendingResolve(a["person"].orEmpty())
            "album_search" -> albumSearch(a["query"].orEmpty())
            "journal_lookup" -> journalLookup(a["day"].orEmpty())
            "renewal_remind" -> renewalRemind()
            else -> ToolResult(call.name, false, "I can't do that yet.")
        }
        if (r.ok) changes += "${call.name}: ${r.message}"
        env.log("tool ${call.name} ${a} -> ${r.ok} ${r.message}")
        return r
    }

    /** The list this call adds to: the trip's packing list, else today's list. */
    private suspend fun activeChecklist(): Item {
        val now = env.clock.now()
        val albumId = context.albumId ?: env.activeAlbum()?.id
        val candidates = dao.itemsOfType(ItemType.CHECKLIST)
        val existing = candidates.firstOrNull { it.albumId == albumId && albumId != null }
            ?: if (albumId == null) candidates.firstOrNull { it.albumId == null && TextKit.dayKey(it.createdAt, zone) == TextKit.dayKey(now, zone) } else null
        if (existing != null) return existing
        val album = albumId?.let { dao.album(it) }
        val title = if (album?.kind == AlbumKind.TRIP) "To pack" else "Today"
        val id = dao.insertItem(Item(type = ItemType.CHECKLIST, title = title, albumId = albumId, source = "call", createdAt = now))
        return dao.item(id)!!
    }

    private suspend fun checklistAdd(entries: String): ToolResult {
        val list = TextKit.splitList(entries).take(10)
        if (list.isEmpty()) return ToolResult("checklist_add", false, "nothing to add")
        val item = activeChecklist()
        val existing = dao.entries(item.id)
        var pos = existing.size
        val added = list.filter { e -> existing.none { TextKit.similarity(it.text, e) > 0.8 } }
        added.forEach { dao.insertEntry(ChecklistEntry(itemId = item.id, text = it, position = pos++)) }
        return ToolResult("checklist_add", true, "added ${added.joinToString()}")
    }

    private suspend fun checklistTick(entry: String): ToolResult {
        val open = dao.openEntries()
        val idx = TextKit.number(entry)
        val match = if (idx != null && idx in 1..open.size) open[idx - 1]
        else open.maxByOrNull { TextKit.similarity(it.text, entry) }?.takeIf { TextKit.similarity(it.text, entry) >= 0.5 }
        if (match == null) return ToolResult("checklist_tick", false, "I couldn't find that on your list")
        dao.updateEntry(match.copy(checked = true))
        val left = dao.openEntries().filter { it.itemId == match.itemId }
        return ToolResult("checklist_tick", true, "ticked ${match.text}; ${left.size} left" + if (left.isNotEmpty()) ": ${left.joinToString { it.text }}" else "")
    }

    private suspend fun noteSave(text: String): ToolResult {
        if (text.isBlank()) return ToolResult("note_save", false, "empty note")
        val now = env.clock.now()
        val albumId = context.albumId ?: env.activeAlbum()?.id
        val clean = text.trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        dao.insertItem(Item(type = ItemType.NOTE, title = clean.take(60), body = clean, albumId = albumId, source = "call", createdAt = now))
        return ToolResult("note_save", true, "noted")
    }

    private suspend fun reminderSet(text: String, whenPhrase: String, check: Boolean): ToolResult {
        val now = env.clock.now()
        val at = TextKit.parseWhen(whenPhrase, now, zone) ?: return ToolResult("reminder_set", false, "I didn't catch the time")
        var habitId: Long? = null
        val lower = text.lowercase(Locale.ROOT)
        val kind = if (check) ReminderKind.ACCOUNTABILITY else ReminderKind.GENERIC
        if (check) {
            val habitName = listOf("gym", "study", "walk", "run", "yoga", "read", "meditate", "water", "workout")
                .firstOrNull { lower.contains(it) }?.replaceFirstChar { it.uppercase() } ?: text.take(20).replaceFirstChar { it.uppercase() }
            habitId = (dao.habitByName(habitName) ?: Habit(name = habitName, createdAt = now).let { dao.insertHabit(it); dao.habitByName(habitName) })?.id
        }
        val rem = Reminder(kind = kind, text = text.trim().replaceFirstChar { it.uppercase() }, fireAt = at, surface = Surface.RING, habitId = habitId, createdAt = now)
        val id = dao.insertReminder(rem)
        env.scheduler.schedule(rem.copy(id = id))
        return ToolResult("reminder_set", true, "I'll ring you ${TextKit.dayLabel(at, now, zone)} at ${TextKit.clock(at, zone)}" + if (check) " and ask" else "")
    }

    private suspend fun eventMove(whenPhrase: String): ToolResult {
        val ticket = (context.albumId?.let { dao.itemsInAlbum(it) } ?: env.activeAlbum()?.let { dao.itemsInAlbum(it.id) } ?: emptyList())
            .firstOrNull { it.kind == ItemKind.TICKET && it.eventStart != null }
            ?: dao.itemsOfKind(ItemKind.TICKET).firstOrNull { it.eventStart != null && it.eventStart!! > env.clock.now() - 86_400_000 }
            ?: return ToolResult("event_move", false, "I couldn't find a flight to move")
        val newStart = TextKit.moveTime(ticket.eventStart!!, whenPhrase, zone) ?: return ToolResult("event_move", false, "I didn't catch the new time")
        dao.updateItem(ticket.copy(eventStart = newStart))
        val moved = env.planner.reconcile()
        return ToolResult("event_move", true, "moved your flight to ${TextKit.clock(newStart, zone)} and $moved")
    }

    private suspend fun habitCheckin(name: String, done: Boolean): ToolResult {
        val now = env.clock.now()
        val habitName = name.ifBlank { context.habitName ?: "Habit" }.replaceFirstChar { it.uppercase() }
        val habit = dao.habitByName(habitName) ?: run { dao.insertHabit(Habit(name = habitName, createdAt = now)); dao.habitByName(habitName)!! }
        dao.upsertHabitLog(HabitLog(habitId = habit.id, day = TextKit.dayKey(now, zone), done = done))
        val streak = env.streak(habit.id)
        return ToolResult("habit_checkin", true, if (done) "$habitName streak is $streak day${if (streak == 1) "" else "s"}" else "logged; streak reset")
    }

    private suspend fun doseSchedule(medicine: String, perDay: Int, days: Int): ToolResult {
        val now = env.clock.now()
        val med = medicine.ifBlank {
            dao.itemsOfKind(ItemKind.PRESCRIPTION).firstOrNull()?.let { org.json.JSONObject(it.fields).optString("medicine") }.orEmpty().ifBlank { "your medicine" }
        }
        val slots = when (perDay.coerceIn(1, 4)) { 1 -> listOf(9 * 60); 2 -> listOf(9 * 60, 21 * 60); 3 -> listOf(8 * 60, 14 * 60, 20 * 60); else -> listOf(8 * 60, 12 * 60, 16 * 60, 20 * 60) }
        val today = TextKit.local(now, zone).toLocalDate()
        var made = 0
        var first: Long? = null
        val albumId = context.albumId ?: dao.albums().firstOrNull { it.kind == AlbumKind.HEALTH }?.id
        var d: LocalDate = today
        while (made < perDay * days.coerceIn(1, 30)) {
            for (s in slots) {
                if (made >= perDay * days) break
                val at = TextKit.at(d, s, zone)
                if (at <= now + 60_000) continue
                val rem = Reminder(kind = ReminderKind.DOSE, text = "Take $med", fireAt = at, surface = Surface.RING, albumId = albumId, createdAt = now,
                    meta = org.json.JSONObject().put("medicine", med).toString())
                val id = dao.insertReminder(rem)
                env.scheduler.schedule(rem.copy(id = id))
                if (first == null) first = at
                made++
            }
            d = d.plusDays(1)
        }
        return ToolResult("dose_schedule", true, "$made reminders for $med, first ${first?.let { TextKit.dayLabel(it, now, zone) + " at " + TextKit.clock(it, zone) } ?: "soon"}")
    }

    private suspend fun doseTaken(done: Boolean): ToolResult {
        val rem = context.reminderId?.let { dao.reminder(it) } ?: return ToolResult("dose_taken", false, "no dose in this call")
        dao.updateReminder(rem.copy(status = if (done) ReminderStatus.DONE else ReminderStatus.MISSED))
        return ToolResult("dose_taken", true, if (done) "marked as taken" else "marked as missed")
    }

    private suspend fun pendingAdd(person: String, text: String): ToolResult {
        if (person.isBlank() || text.isBlank()) return ToolResult("pending_add", false, "who and what?")
        val p = env.person(person)
        if (dao.openPending(p.id).any { TextKit.similarity(it.text, text) >= 0.6 }) return ToolResult("pending_add", true, "I already have that for ${p.name}")
        dao.insertPending(PendingItem(personId = p.id, text = text.trim().replaceFirstChar { it.uppercase() }, sourceApp = "call", createdAt = env.clock.now()))
        return ToolResult("pending_add", true, "I'll bring it up next time ${p.name} messages")
    }

    private suspend fun pendingResolve(person: String): ToolResult {
        val p = dao.personByName(TextKit.normalize(person)) ?: return ToolResult("pending_resolve", false, "nothing open with $person")
        val open = dao.openPending(p.id)
        open.forEach { dao.updatePending(it.copy(status = "DONE")) }
        return ToolResult("pending_resolve", true, "cleared ${open.size} item${if (open.size == 1) "" else "s"} with ${p.name}")
    }

    private suspend fun albumSearch(query: String): ToolResult {
        val hits = env.search(query)
        if (hits.isEmpty()) return ToolResult("album_search", false, "I couldn't find anything for $query")
        val top = hits.first()
        val album = top.albumId?.let { dao.album(it) }
        return ToolResult("album_search", true, "${top.title}" + (album?.let { ", in ${it.title}" } ?: "") + ", saved ${TextKit.dateLabel(top.createdAt, zone)}")
    }

    /** "Yes, remind me the day before" on a renewal call. */
    private suspend fun renewalRemind(): ToolResult {
        val now = env.clock.now()
        val item = context.reminderId?.let { dao.reminder(it) }?.itemId?.let { dao.item(it) } ?: dao.expiring().firstOrNull { (it.expiresAt ?: 0) > now }
        val exp = item?.expiresAt ?: return ToolResult("renewal_remind", false, "I couldn't find the date")
        val at = TextKit.at(TextKit.local(exp - 86_400_000L, zone).toLocalDate(), 10 * 60, zone).let { if (it <= now) now + 3_600_000 else it }
        val rem = Reminder(kind = ReminderKind.EXPIRY, text = "${item.title.removeSuffix(" bill")}${if (item.kind == ItemKind.BILL) " warranty" else ""} ends tomorrow", fireAt = at,
            itemId = item.id, albumId = item.albumId, createdAt = now)
        val id = dao.insertReminder(rem)
        env.scheduler.schedule(rem.copy(id = id))
        return ToolResult("renewal_remind", true, "I'll ring you again on ${TextKit.dateLabel(at, zone)}")
    }

    private suspend fun journalLookup(day: String): ToolResult {
        val now = env.clock.now()
        val target = TextKit.parseWhen(day.replace("last ", ""), now - 7 * 86_400_000L, zone)?.let { TextKit.dayKey(it, zone) }
            ?: if ("yesterday" in day) TextKit.dayKey(now - 86_400_000, zone) else TextKit.dayKey(now, zone)
        val entry = dao.itemsOfType(ItemType.JOURNAL).firstOrNull { TextKit.dayKey(it.eventStart ?: it.createdAt, zone) == target }
            ?: return ToolResult("journal_lookup", false, "I don't have a journal entry for that day")
        return ToolResult("journal_lookup", true, entry.body)
    }
}

/** What the agent needs from the rest of the app. Implemented by the engine. */
interface AgentEnv {
    val dao: com.cue.domain.CueDao
    val clock: com.cue.domain.Clock
    val scheduler: com.cue.domain.TriggerScheduler
    val planner: Planner
    suspend fun activeAlbum(): Album?
    suspend fun streak(habitId: Long): Int
    suspend fun person(name: String): Person
    suspend fun search(query: String): List<Item>
    fun log(line: String)
}
