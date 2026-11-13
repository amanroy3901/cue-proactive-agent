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
    private val listParam = ToolParam("list", "string", "which list, by name (like Groceries or To pack); empty for the one in use", required = false)
    val checklistAdd = ToolSpec("checklist_add", "Add entries to a list. Naming a list that doesn't exist yet creates it", listOf(ToolParam("entries", "string", "comma-separated entries"), listParam))
    val checklistTick = ToolSpec("checklist_tick", "Tick an entry the user says is done", listOf(ToolParam("entry", "string", "entry words or number"), listParam))
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

    val checklistRemove = ToolSpec("checklist_remove", "Remove an entry from the list, or every ticked entry when the user asks to clear what is done", listOf(
        ToolParam("entry", "string", "the entry's words exactly as the user said them, or 'done' for every ticked entry"), listParam))
    val habitAdd = ToolSpec("habit_add", "Start tracking a new habit", listOf(ToolParam("habit", "string", "short habit name, like Journal or Walk")))
    val listRead = ToolSpec("list_read", "Read one list (what is left and what is done). Use it whenever they ask about a list", listOf(listParam))
    val listsRead = ToolSpec("lists_read", "Name all the user's lists with how much is left on each", emptyList())
    val listCreate = ToolSpec("list_create", "Make a new list, optionally with its first entries", listOf(ToolParam("name", "string", "the new list's name"),
        ToolParam("entries", "string", "comma-separated first entries", required = false)))
    val listRename = ToolSpec("list_rename", "Rename a list", listOf(ToolParam("list", "string", "the list's name now"), ToolParam("name", "string", "new name")))
    val listDelete = ToolSpec("list_delete", "Delete a whole list (only when the user clearly asks)", listOf(ToolParam("list", "string", "the list's name")))
    val habitsRead = ToolSpec("habits_read", "Read the user's habits with their streaks. Use it for questions about habits, streaks or progress", emptyList())
    val remindersRead = ToolSpec("reminders_read", "Read what is scheduled next: calls and reminders", emptyList())
    val peopleRead = ToolSpec("people_read", "Read what is pending with people (things the user owes or was asked for)", listOf(
        ToolParam("person", "string", "a name, or empty for everyone", required = false)))
    val checkinTime = ToolSpec("checkin_time", "Move the user's daily morning or night check-in call", listOf(
        ToolParam("which", "string", "morning or night"), ToolParam("when", "string", "spoken time, like 11 pm")))
    val renewalRemind = ToolSpec("renewal_remind", "Remind the user the day before something expires", listOf(
        ToolParam("id", "string", "the document's number from renewals_read; empty on a renewal call", required = false)))
    val endCall = ToolSpec("end_call", "Hang up after saying goodbye, when the user says bye or is done", emptyList())

    // Editing what was saved. Every save and every read returns entry numbers like #14; corrections use them.
    private fun id(what: String) = ToolParam("id", "string", "the $what's number from an earlier tool result, like 14")
    val checklistEdit = ToolSpec("checklist_edit", "Change the words of a list entry", listOf(ToolParam("entry", "string", "the entry as it is now"), ToolParam("text", "string", "the new words"), listParam))
    private val pendingRef = listOf(ToolParam("id", "string", "its number from an earlier result, like 14, if known", required = false),
        ToolParam("who", "string", "else: the person it is with now", required = false), ToolParam("about", "string", "else: a few words of what it is", required = false))
    val pendingUpdate = ToolSpec("pending_update", "Correct something pending with a person: the person, the words, or both. Use it when the user corrects what you just saved, instead of adding a new one",
        pendingRef + listOf(ToolParam("person", "string", "the right person, if that changed", required = false), ToolParam("text", "string", "the right words, if they changed", required = false)))
    val pendingRemove = ToolSpec("pending_remove", "Delete something pending with a person (saved by mistake)", pendingRef)
    val pendingDone = ToolSpec("pending_done", "Mark one pending item as done", pendingRef)
    val habitRemove = ToolSpec("habit_remove", "Stop tracking a habit", listOf(ToolParam("habit", "string", "habit name")))
    val habitRename = ToolSpec("habit_rename", "Rename a habit", listOf(ToolParam("habit", "string", "habit name now"), ToolParam("name", "string", "new name")))
    val reminderCancel = ToolSpec("reminder_cancel", "Cancel a reminder or call", listOf(id("reminder")))
    val reminderMove = ToolSpec("reminder_move", "Move a reminder or call to a new time", listOf(id("reminder"), ToolParam("when", "string", "new spoken time")))
    val reminderEdit = ToolSpec("reminder_edit", "Change what a reminder says", listOf(id("reminder"), ToolParam("text", "string", "new words")))
    val notesRead = ToolSpec("notes_read", "Read the user's recent notes, or notes about something", listOf(ToolParam("query", "string", "what about, or empty for the latest", required = false)))
    val noteEdit = ToolSpec("note_edit", "Change a saved note's words", listOf(id("note"), ToolParam("text", "string", "the corrected note")))
    val noteRemove = ToolSpec("note_remove", "Delete a note saved by mistake", listOf(id("note")))
    val journalAdd = ToolSpec("journal_add", "Add to today's journal entry", listOf(ToolParam("text", "string", "what to add, in first person")))
    val albumsRead = ToolSpec("albums_read", "List the user's albums (trips, health visits, home bills...)", emptyList())
    val albumOpen = ToolSpec("album_open", "Show what is in an album", listOf(ToolParam("album", "string", "album name or number")))
    val papersRead = ToolSpec("papers_read", "List saved documents: tickets, IDs, bills, prescriptions, lab reports, insurance, vehicle papers", listOf(
        ToolParam("kind", "string", "one of ticket, id, bill, prescription, lab_report, insurance, vehicle, hotel, or empty for all", required = false)))
    val renewalsRead = ToolSpec("renewals_read", "List warranties, IDs and policies with their expiry dates", emptyList())
    val checklistUntick = ToolSpec("checklist_untick", "Un-tick an entry that isn't actually done", listOf(ToolParam("entry", "string", "entry words"), listParam))
    val albumCreate = ToolSpec("album_create", "Make a new album to group things", listOf(ToolParam("name", "string", "album name")))
    val itemMove = ToolSpec("item_move", "Put a saved document or note into an album", listOf(id("document or note"), ToolParam("album", "string", "album name or number")))
    val paperRead = ToolSpec("paper_read", "Read one saved document's details (PNR, seat, dates, amounts, doctor...)", listOf(id("document")))
    val practiceSchedule = ToolSpec("practice_schedule", "Set the daily English practice call's time, or turn it off", listOf(ToolParam("when", "string", "spoken time like 7 pm, or off")))
    val forget = ToolSpec("forget", "Delete something the user saved by mistake when you don't have its number: a list entry, something pending with a person, a note, a reminder or a habit. Describe it in the user's words", listOf(
        ToolParam("words", "string", "what it is, in the user's words, like 'the Rohit concert tickets'")))
    val focusStart = ToolSpec("focus_start", "Start a focus session; I'll quietly let the user know when time is up", listOf(
        ToolParam("minutes", "string", "length in minutes, 25 if not said"), ToolParam("what", "string", "what they will focus on", required = false)))
    val focusStop = ToolSpec("focus_stop", "Stop the focus session that is running", emptyList())
    val focusRead = ToolSpec("focus_read", "How much time is left in the focus session", emptyList())

    /** A realtime model calls tools well: it gets every app's tools, reads and edits included, and can hang up. */
    val live = listOf(checklistAdd, checklistTick, checklistRemove, checklistEdit, listRead, listsRead, listCreate, listRename, listDelete, noteSave, notesRead, noteEdit, noteRemove,
        reminderSet, reminderMove, reminderEdit, reminderCancel, remindersRead, checkinTime, eventMove,
        habitCheckin, habitAdd, habitRemove, habitRename, habitsRead, doseSchedule, doseTaken,
        pendingAdd, pendingUpdate, pendingDone, pendingRemove, pendingResolve, peopleRead,
        albumSearch, albumsRead, albumOpen, papersRead, renewalsRead, renewalRemind, journalLookup, journalAdd,
        forget, checklistUntick, albumCreate, itemMove, paperRead, practiceSchedule, focusStart, focusStop, focusRead, endCall)

    val converse = listOf(checklistAdd, checklistTick, noteSave, reminderSet, eventMove, habitCheckin, doseSchedule, doseTaken,
        pendingAdd, pendingResolve, albumSearch, journalLookup)
    /** The small model handles few tools well; it gets the core five plus the call-specific one. */
    val compact = listOf(checklistAdd, checklistTick, noteSave, reminderSet, eventMove)
}

/**
 * A card the call screen shows because the conversation asked for it ("what's on my list", "how are my habits"),
 * not because a call started. Each tool that reads or changes something names the card for it.
 */
sealed interface CallCard {
    data class Checklist(val itemId: Long, val changed: Boolean) : CallCard
    data object Habits : CallCard
    data class Journal(val itemId: Long?) : CallCard
    data object Reminders : CallCard
    data class Found(val itemIds: List<Long>) : CallCard
    data class People(val personId: Long?) : CallCard
    data object Albums : CallCard
    data object Lists : CallCard
    data object Renewals : CallCard
    data object Focus : CallCard
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
/** A reminder in plain words, for speech and cards. */
fun reminderLabel(r: Reminder): String = when (r.kind) {
    ReminderKind.CHECKIN_MORNING -> "your morning call"
    ReminderKind.CHECKIN_NIGHT -> "your night call"
    ReminderKind.LEAVE_BY -> "time to leave for the airport"
    ReminderKind.AIRPORT_BACKUP -> "airport check"
    else -> r.text.ifBlank { "a reminder" }.replaceFirstChar { it.lowercase() }.let { if (it.startsWith("i ")) it.replaceFirstChar { c -> c.uppercase() } else it }
}

/** Tools that only read: they answer and show a card, but don't count as something the call changed. */
private val READS = setOf("list_read", "habits_read", "reminders_read", "people_read", "album_search", "journal_lookup", "end_call",
    "notes_read", "albums_read", "album_open", "papers_read", "renewals_read", "focus_read")

private fun num(s: String?): Long = s?.trim()?.removePrefix("#")?.toLongOrNull() ?: -1L

class CueToolExecutor(
    private val env: AgentEnv,
    var context: CallContext,
) : ToolExecutor {
    private val dao get() = env.dao
    private val zone get() = env.clock.zone
    val changes = mutableListOf<String>()
    /** The card the latest tool asked for; the call reads and clears it after each turn. */
    var card: CallCard? = null

    override suspend fun execute(call: ToolCall): ToolResult {
        val a = call.args
        val r = when (call.name) {
            "checklist_add" -> checklistAdd(a["entries"].orEmpty(), a["list"].orEmpty())
            "checklist_tick" -> checklistTick(a["entry"].orEmpty(), a["list"].orEmpty())
            "checklist_remove" -> checklistRemove(a["entry"].orEmpty(), a["list"].orEmpty())
            "lists_read" -> listsRead()
            "list_create" -> listCreate(a["name"].orEmpty(), a["entries"].orEmpty())
            "list_rename" -> listRename(a["list"].orEmpty(), a["name"].orEmpty())
            "list_delete" -> listDelete(a["list"].orEmpty())
            "habit_add" -> habitAdd(a["habit"].orEmpty())
            "checklist_edit" -> checklistEdit(a["entry"].orEmpty(), a["text"].orEmpty(), a["list"].orEmpty())
            "pending_update" -> pendingUpdate(findPending(a), a["person"].orEmpty(), a["text"].orEmpty())
            "pending_remove" -> pendingRemove(findPending(a))
            "pending_done" -> pendingDone(findPending(a))
            "habit_remove" -> habitRemove(a["habit"].orEmpty())
            "habit_rename" -> habitRename(a["habit"].orEmpty(), a["name"].orEmpty())
            "reminder_cancel" -> reminderCancel(num(a["id"]))
            "reminder_move" -> reminderMove(num(a["id"]), a["when"].orEmpty())
            "reminder_edit" -> reminderEdit(num(a["id"]), a["text"].orEmpty())
            "notes_read" -> notesRead(a["query"].orEmpty())
            "note_edit" -> noteEdit(num(a["id"]), a["text"].orEmpty())
            "note_remove" -> noteRemove(num(a["id"]))
            "journal_add" -> journalAdd(a["text"].orEmpty())
            "albums_read" -> albumsRead()
            "album_open" -> albumOpen(a["album"].orEmpty())
            "papers_read" -> papersRead(a["kind"].orEmpty())
            "renewals_read" -> renewalsRead()
            "focus_start" -> focusStart(TextKit.number(a["minutes"]) ?: 25, a["what"].orEmpty())
            "focus_stop" -> focusStop()
            "focus_read" -> focusRead()
            "note_save" -> noteSave(a["text"].orEmpty())
            "reminder_set" -> reminderSet(a["text"].orEmpty(), a["when"].orEmpty(), a["check"] == "yes")
            "checkin_time" -> checkinTime(a["which"].orEmpty(), a["when"].orEmpty())
            "event_move" -> eventMove(a["when"].orEmpty())
            "habit_checkin" -> habitCheckin(a["habit"] ?: context.habitName ?: "", a["done"] != "no")
            "dose_schedule" -> doseSchedule(a["medicine"].orEmpty(), TextKit.number(a["per_day"]) ?: 2, TextKit.number(a["days"]) ?: 5)
            "dose_taken" -> doseTaken(a["done"] != "no")
            "pending_add" -> pendingAdd(a["person"].orEmpty(), a["text"].orEmpty())
            "pending_resolve" -> pendingResolve(a["person"].orEmpty())
            "album_search" -> albumSearch(a["query"].orEmpty())
            "journal_lookup" -> journalLookup(a["day"].orEmpty())
            "renewal_remind" -> renewalRemind(num(a["id"]))
            "forget" -> forget(a["words"].orEmpty())
            "checklist_untick" -> checklistUntick(a["entry"].orEmpty(), a["list"].orEmpty())
            "album_create" -> albumCreate(a["name"].orEmpty())
            "item_move" -> itemMove(num(a["id"]), a["album"].orEmpty())
            "paper_read" -> paperRead(num(a["id"]))
            "practice_schedule" -> practiceSchedule(a["when"].orEmpty())
            "list_read" -> listRead(a["list"].orEmpty())
            "habits_read" -> habitsRead()
            "reminders_read" -> remindersRead()
            "people_read" -> peopleRead(a["person"].orEmpty())
            "end_call" -> ToolResult("end_call", true, "hanging up after your goodbye")
            else -> ToolResult(call.name, false, "I can't do that yet.")
        }
        if (r.ok && call.name !in READS) changes += "${call.name}: ${r.message}"
        env.log("tool ${call.name} ${a} -> ${r.ok} ${r.message}")
        return r
    }

    /** The list touched last on this call: follow-ups ("and eggs too") go to the same list. */
    private var lastList: Long? = null

    /** A list by name ("groceries", "the packing list") or number. */
    private suspend fun findList(name: String): Item? {
        val lists = dao.itemsOfType(ItemType.CHECKLIST)
        num(name).takeIf { it > 0 }?.let { n -> lists.firstOrNull { it.id == n }?.let { return it } }
        val q = name.lowercase(Locale.ROOT).replace(Regex("\\b(the|my|list|checklist)\\b"), "").trim()
        if (q.isBlank()) return null
        return lists.firstOrNull { it.title.lowercase(Locale.ROOT) == q }
            ?: lists.firstOrNull { it.title.lowercase(Locale.ROOT).contains(q) || q.contains(it.title.lowercase(Locale.ROOT)) }
            ?: lists.maxByOrNull { TextKit.similarity(it.title, q) }?.takeIf { TextKit.similarity(it.title, q) >= 0.6 }
    }

    /** The list this call works on, without making one: the named one, else the last used, the trip's, today's, the newest. */
    private suspend fun currentChecklist(name: String = ""): Item? {
        if (name.isNotBlank()) return findList(name)
        lastList?.let { id -> dao.item(id)?.let { return it } }
        val now = env.clock.now()
        val albumId = context.albumId ?: env.activeAlbum()?.id
        val lists = dao.itemsOfType(ItemType.CHECKLIST)
        return lists.firstOrNull { it.albumId == albumId && albumId != null }
            ?: lists.firstOrNull { TextKit.dayKey(it.createdAt, zone) == TextKit.dayKey(now, zone) }
            ?: lists.firstOrNull()
    }

    /** When a list tool can't find the words: where they are instead, so the model uses the right tool next. */
    private suspend fun whereElse(words: String): String {
        val w = words.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 2 && it !in setOf("the", "entry", "item", "one", "test", "list") }
        if (w.isEmpty()) return ""
        for (p in dao.allOpenPending()) {
            val who = dao.person(p.personId)?.name.orEmpty()
            if (w.any { (who + " " + p.text).lowercase(Locale.ROOT).contains(it) }) return ". It isn't on a list: it's pending item #${p.id} with $who (${p.text}); use the pending tools with id ${p.id}"
        }
        dao.itemsOfType(ItemType.NOTE).firstOrNull { n -> w.any { n.body.lowercase(Locale.ROOT).contains(it) } }?.let { return ". It isn't on a list: it's note #${it.id} (${it.title}); use the note tools with id ${it.id}" }
        dao.scheduledReminders().firstOrNull { r -> w.any { r.text.lowercase(Locale.ROOT).contains(it) } }?.let { return ". It isn't on a list: it's reminder #${it.id} (${it.text}); use the reminder tools with id ${it.id}" }
        return ""
    }

    private fun noList(name: String, tool: String) = ToolResult(tool, false, "there's no list called $name; the lists are read with lists_read")

    private suspend fun newList(name: String): Item {
        val now = env.clock.now()
        val title = name.trim().replace(Regex("(?i)\\s*(list|checklist)$"), "").trim().replaceFirstChar { it.uppercase() }.ifBlank { "List" }
        val id = dao.insertItem(Item(type = ItemType.CHECKLIST, title = title, source = "call", createdAt = now))
        return dao.item(id)!!
    }

    private suspend fun listRead(name: String = ""): ToolResult {
        val item = currentChecklist(name) ?: return if (name.isNotBlank()) noList(name, "list_read") else ToolResult("list_read", true, "You don't have a list yet")
        lastList = item.id
        val all = dao.entries(item.id)
        card = CallCard.Checklist(item.id, changed = false)
        val open = all.filter { !it.checked }
        val done = all.filter { it.checked }
        if (all.isEmpty()) return ToolResult("list_read", true, "${item.title} is empty")
        return ToolResult("list_read", true, (if (open.isEmpty()) "Everything on ${item.title} is done" else "On ${item.title}: ${open.joinToString { it.text }}") +
            if (done.isNotEmpty()) ". Done: ${done.joinToString { it.text }}" else "")
    }

    private suspend fun listsRead(): ToolResult {
        val lists = dao.itemsOfType(ItemType.CHECKLIST)
        card = CallCard.Lists
        if (lists.isEmpty()) return ToolResult("lists_read", true, "You don't have any lists yet")
        val parts = mutableListOf<String>()
        for (l in lists.take(8)) { val es = dao.entries(l.id); parts += "#${l.id} ${l.title}: ${es.count { !it.checked }} left of ${es.size}" }
        return ToolResult("lists_read", true, parts.joinToString("; "))
    }

    private suspend fun listCreate(name: String, entries: String): ToolResult {
        if (name.isBlank()) return ToolResult("list_create", false, "what should the list be called?")
        findList(name)?.let { existing ->
            lastList = existing.id
            if (entries.isNotBlank()) return checklistAdd(entries, existing.title)
            card = CallCard.Checklist(existing.id, changed = false)
            return ToolResult("list_create", true, "you already have a list called ${existing.title}; I'll use that one")
        }
        val item = newList(name)
        lastList = item.id
        card = CallCard.Checklist(item.id, changed = true)
        if (entries.isNotBlank()) {
            val r = checklistAdd(entries, item.title)
            return ToolResult("list_create", true, "made a new list, ${item.title}, and ${r.message}")
        }
        return ToolResult("list_create", true, "made a new list, ${item.title}")
    }

    private suspend fun listRename(name: String, newName: String): ToolResult {
        val item = findList(name) ?: return noList(name, "list_rename")
        if (newName.isBlank()) return ToolResult("list_rename", false, "what should it be called?")
        val title = newName.trim().replace(Regex("(?i)\\s*(list|checklist)$"), "").replaceFirstChar { it.uppercase() }
        dao.updateItem(item.copy(title = title))
        lastList = item.id
        card = CallCard.Checklist(item.id, changed = true)
        return ToolResult("list_rename", true, "renamed ${item.title} to $title")
    }

    private suspend fun listDelete(name: String): ToolResult {
        val item = findList(name) ?: return noList(name, "list_delete")
        dao.entries(item.id).forEach { dao.deleteEntry(it.id) }
        dao.deleteItem(item.id)
        if (lastList == item.id) lastList = null
        card = CallCard.Lists
        return ToolResult("list_delete", true, "deleted the ${item.title} list")
    }

    private suspend fun habitsRead(): ToolResult {
        val habits = dao.habits()
        card = CallCard.Habits
        if (habits.isEmpty()) return ToolResult("habits_read", true, "You aren't tracking any habits yet")
        val today = TextKit.dayKey(env.clock.now(), zone)
        val parts = habits.map { h ->
            val streak = env.streak(h.id)
            val doneToday = dao.habitLogs(h.id).any { it.day == today && it.done }
            "${h.name}: ${if (streak > 0) "$streak-day streak" else "no streak yet"}${if (doneToday) ", done today" else ""}"
        }
        return ToolResult("habits_read", true, parts.joinToString(". "))
    }

    private suspend fun remindersRead(): ToolResult {
        val now = env.clock.now()
        val next = dao.scheduledReminders().filter { it.kind != ReminderKind.PRELOAD && it.fireAt > now }.take(6)
        card = CallCard.Reminders
        if (next.isEmpty()) return ToolResult("reminders_read", true, "Nothing is scheduled")
        return ToolResult("reminders_read", true, "Coming up: " + next.joinToString("; ") { r ->
            "#${r.id} ${TextKit.dayLabel(r.fireAt, now, zone)} at ${TextKit.clock(r.fireAt, zone)}, ${reminderLabel(r)}" })
    }

    private suspend fun peopleRead(person: String): ToolResult {
        val p = person.takeIf { it.isNotBlank() }?.let { dao.personByName(TextKit.normalize(it)) }
        val open = if (p != null) dao.openPending(p.id) else dao.allOpenPending()
        card = CallCard.People(p?.id)
        if (open.isEmpty()) return ToolResult("people_read", true, if (p != null) "Nothing open with ${p.name}" else "Nothing is waiting on you")
        val names = open.map { it.personId }.distinct().associateWith { dao.person(it)?.name ?: "someone" }
        return ToolResult("people_read", true, open.take(6).joinToString("; ") { "#${it.id} ${names[it.personId]}: ${it.text}" })
    }

    /** The list this call adds to: the named one (made if new), else the last used, the trip's packing list, today's list. */
    private suspend fun activeChecklist(name: String = ""): Item {
        if (name.isNotBlank()) return findList(name) ?: newList(name)
        lastList?.let { id -> dao.item(id)?.let { return it } }
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

    private suspend fun checklistAdd(entries: String, listName: String = ""): ToolResult {
        val list = TextKit.splitList(entries).take(10)
        if (list.isEmpty()) return ToolResult("checklist_add", false, "nothing to add")
        val isNew = listName.isNotBlank() && findList(listName) == null
        val item = activeChecklist(listName)
        lastList = item.id
        val existing = dao.entries(item.id)
        var pos = existing.size
        val added = list.filter { e -> existing.none { TextKit.similarity(it.text, e) > 0.8 } }
        added.forEach { dao.insertEntry(ChecklistEntry(itemId = item.id, text = it, position = pos++)) }
        card = CallCard.Checklist(item.id, changed = true)
        val what = if (added.isEmpty()) "${list.joinToString()} ${if (list.size == 1) "is" else "are"} already there" else "added ${added.joinToString()}"
        return ToolResult("checklist_add", true, (if (isNew) "made a new list, ${item.title}, and " else "") + "$what on ${item.title}")
    }

    private suspend fun checklistTick(entry: String, listName: String = ""): ToolResult {
        val target = if (listName.isNotBlank()) findList(listName) ?: return noList(listName, "checklist_tick") else currentChecklist()
        val inTarget = target?.let { t -> dao.entries(t.id).filter { !it.checked } }.orEmpty()
        val open = inTarget.ifEmpty { if (listName.isBlank()) dao.openEntries() else emptyList() }
        val idx = TextKit.number(entry)
        val match = if (idx != null && idx in 1..open.size) open[idx - 1]
            else open.maxByOrNull { TextKit.similarity(it.text, entry) }?.takeIf { TextKit.similarity(it.text, entry) >= 0.5 }
            ?: if (listName.isBlank()) dao.openEntries().maxByOrNull { TextKit.similarity(it.text, entry) }?.takeIf { TextKit.similarity(it.text, entry) >= 0.5 } else null
        if (match == null) return ToolResult("checklist_tick", false, "I couldn't find $entry on ${target?.title ?: "your list"}" + whereElse(entry))
        dao.updateEntry(match.copy(checked = true))
        lastList = match.itemId
        card = CallCard.Checklist(match.itemId, changed = true)
        val left = dao.entries(match.itemId).filter { !it.checked }
        return ToolResult("checklist_tick", true, "ticked ${match.text}; ${left.size} left" + if (left.isNotEmpty()) ": ${left.joinToString { it.text }}" else "")
    }

    private suspend fun checklistEdit(entry: String, text: String, listName: String = ""): ToolResult {
        val item = currentChecklist(listName) ?: return if (listName.isNotBlank()) noList(listName, "checklist_edit") else ToolResult("checklist_edit", false, "there is no list")
        lastList = item.id
        val e = dao.entries(item.id).maxByOrNull { TextKit.similarity(it.text, entry) }?.takeIf { TextKit.similarity(it.text, entry) >= 0.5 }
            ?: return ToolResult("checklist_edit", false, "I couldn't find $entry on ${item.title}" + whereElse(entry))
        if (text.isBlank()) return ToolResult("checklist_edit", false, "what should it say?")
        dao.updateEntry(e.copy(text = text.trim().replaceFirstChar { it.uppercase() }))
        card = CallCard.Checklist(item.id, changed = true)
        return ToolResult("checklist_edit", true, "changed ${e.text} to ${text.trim()}")
    }

    /** A pending item by number, or by who it is with and a few words of it (the newest match). */
    private suspend fun findPending(a: Map<String, String>): Long {
        num(a["id"]).takeIf { it > 0 }?.let { return it }
        val who = a["who"].orEmpty().ifBlank { a["person"].orEmpty() }
        val about = a["about"].orEmpty()
        val open = dao.allOpenPending()
        val byWho = if (who.isBlank()) open else {
            val p = dao.personByName(TextKit.normalize(who))
            if (p != null) open.filter { it.personId == p.id } else emptyList()
        }
        val pick = if (about.isBlank()) byWho.firstOrNull() else byWho.maxByOrNull { TextKit.similarity(it.text, about) }?.takeIf { TextKit.similarity(it.text, about) >= 0.3 || who.isNotBlank() }
        return pick?.id ?: -1
    }

    private suspend fun pendingUpdate(id: Long, person: String, text: String): ToolResult {
        val it = dao.pending(id) ?: return ToolResult("pending_update", false, "I couldn't find that; read the people list with people_read first")
        val p = if (person.isNotBlank()) env.person(person) else dao.person(it.personId)!!
        val newText = text.trim().ifBlank { it.text }.replaceFirstChar { c -> c.uppercase() }
        dao.updatePending(it.copy(personId = p.id, text = newText))
        card = CallCard.People(p.id)
        return ToolResult("pending_update", true, "#$id is now with ${p.name}: $newText")
    }

    private suspend fun pendingRemove(id: Long): ToolResult {
        val it = dao.pending(id) ?: return ToolResult("pending_remove", false, "I couldn't find that; read the people list with people_read first")
        dao.deletePending(id)
        card = CallCard.People(null)
        return ToolResult("pending_remove", true, "deleted #$id (${it.text})")
    }

    private suspend fun pendingDone(id: Long): ToolResult {
        val it = dao.pending(id) ?: return ToolResult("pending_done", false, "I couldn't find that; read the people list with people_read first")
        dao.updatePending(it.copy(status = "DONE"))
        card = CallCard.People(it.personId)
        return ToolResult("pending_done", true, "marked #$id done (${it.text})")
    }

    private suspend fun habitByWords(name: String): Habit? =
        dao.habits().maxByOrNull { TextKit.similarity(it.name, name) }?.takeIf { TextKit.similarity(it.name, name) >= 0.5 }

    private suspend fun habitRemove(name: String): ToolResult {
        val h = habitByWords(name) ?: return ToolResult("habit_remove", false, "you aren't tracking $name")
        dao.deleteHabitLogs(h.id); dao.deleteHabit(h.id)
        card = CallCard.Habits
        return ToolResult("habit_remove", true, "stopped tracking ${h.name}")
    }

    private suspend fun habitRename(name: String, newName: String): ToolResult {
        val h = habitByWords(name) ?: return ToolResult("habit_rename", false, "you aren't tracking $name")
        if (newName.isBlank()) return ToolResult("habit_rename", false, "what should it be called?")
        dao.updateHabit(h.copy(name = newName.trim().replaceFirstChar { it.uppercase() }))
        card = CallCard.Habits
        return ToolResult("habit_rename", true, "renamed ${h.name} to ${newName.trim()}")
    }

    private suspend fun userReminder(id: Long, tool: String): Pair<Reminder?, ToolResult?> {
        val r = dao.reminder(id) ?: return null to ToolResult(tool, false, "I couldn't find #$id; read the reminders first")
        if (r.status != ReminderStatus.SCHEDULED) return null to ToolResult(tool, false, "#$id isn't scheduled any more")
        return r to null
    }

    private suspend fun reminderCancel(id: Long): ToolResult {
        val (r, err) = userReminder(id, "reminder_cancel"); if (r == null) return err!!
        dao.updateReminder(r.copy(status = ReminderStatus.CANCELLED))
        env.scheduler.cancel(r.id)
        // The daily calls come back from the planner unless they are switched off; say so.
        card = CallCard.Reminders
        return ToolResult("reminder_cancel", true, "cancelled ${reminderLabel(r)}" +
            if (r.kind == ReminderKind.CHECKIN_MORNING || r.kind == ReminderKind.CHECKIN_NIGHT) " for today" else "")
    }

    private suspend fun reminderMove(id: Long, whenPhrase: String): ToolResult {
        val (r, err) = userReminder(id, "reminder_move"); if (r == null) return err!!
        val at = TextKit.parseWhen(whenPhrase, env.clock.now(), zone) ?: return ToolResult("reminder_move", false, "I didn't catch the time")
        val moved = r.copy(fireAt = at, attempts = 0)
        dao.updateReminder(moved)
        env.scheduler.schedule(moved)
        card = CallCard.Reminders
        return ToolResult("reminder_move", true, "moved ${reminderLabel(r)} to ${TextKit.dayLabel(at, env.clock.now(), zone)} at ${TextKit.clock(at, zone)}")
    }

    private suspend fun reminderEdit(id: Long, text: String): ToolResult {
        val (r, err) = userReminder(id, "reminder_edit"); if (r == null) return err!!
        if (text.isBlank()) return ToolResult("reminder_edit", false, "what should it say?")
        dao.updateReminder(r.copy(text = text.trim().replaceFirstChar { it.uppercase() }))
        card = CallCard.Reminders
        return ToolResult("reminder_edit", true, "#$id now says: ${text.trim()}")
    }

    private suspend fun notesRead(query: String): ToolResult {
        val notes = if (query.isBlank()) dao.itemsOfType(ItemType.NOTE).take(5)
            else env.search(query).filter { it.type == ItemType.NOTE }.take(5)
        if (notes.isEmpty()) return ToolResult("notes_read", true, if (query.isBlank()) "No notes yet" else "No notes about $query")
        card = CallCard.Found(notes.map { it.id })
        return ToolResult("notes_read", true, notes.joinToString("; ") { "#${it.id} ${it.body.ifBlank { it.title }.take(120)}" })
    }

    private suspend fun noteEdit(id: Long, text: String): ToolResult {
        val n = dao.item(id)?.takeIf { it.type == ItemType.NOTE } ?: return ToolResult("note_edit", false, "I couldn't find note #$id")
        if (text.isBlank()) return ToolResult("note_edit", false, "what should it say?")
        val clean = text.trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        dao.updateItem(n.copy(title = clean.take(60), body = clean))
        card = CallCard.Found(listOf(id))
        return ToolResult("note_edit", true, "#$id now says: $clean")
    }

    private suspend fun noteRemove(id: Long): ToolResult {
        val n = dao.item(id)?.takeIf { it.type == ItemType.NOTE } ?: return ToolResult("note_remove", false, "I couldn't find note #$id")
        dao.deleteItem(id)
        return ToolResult("note_remove", true, "deleted the note: ${n.title}")
    }

    private suspend fun journalAdd(text: String): ToolResult {
        if (text.isBlank()) return ToolResult("journal_add", false, "what should I write?")
        val now = env.clock.now()
        val today = TextKit.dayKey(now, zone)
        val entry = dao.itemsOfType(ItemType.JOURNAL).firstOrNull { TextKit.dayKey(it.eventStart ?: it.createdAt, zone) == today }
        val line = text.trim().trimEnd('.').replaceFirstChar { it.uppercase() } + "."
        val id = if (entry != null) { dao.updateItem(entry.copy(body = (entry.body.trimEnd() + " " + line).trim())); entry.id }
            else dao.insertItem(Item(type = ItemType.JOURNAL, title = "Journal, ${TextKit.dateLabel(now, zone)}", body = line, eventStart = now, source = "call", createdAt = now))
        card = CallCard.Journal(id)
        return ToolResult("journal_add", true, "added to today's journal")
    }

    private suspend fun albumsRead(): ToolResult {
        val albums = dao.albums().take(8)
        card = CallCard.Albums
        if (albums.isEmpty()) return ToolResult("albums_read", true, "No albums yet")
        return ToolResult("albums_read", true, albums.joinToString("; ") { "#${it.id} ${it.title}" })
    }

    private suspend fun albumOpen(name: String): ToolResult {
        val albums = dao.albums()
        val album = num(name).takeIf { it > 0 }?.let { n -> albums.firstOrNull { it.id == n } }
            ?: albums.maxByOrNull { TextKit.similarity(it.title, name) }?.takeIf { TextKit.similarity(it.title, name) >= 0.4 || it.title.lowercase(Locale.ROOT).contains(name.lowercase(Locale.ROOT)) }
            ?: return ToolResult("album_open", false, "I couldn't find an album called $name")
        val items = dao.itemsInAlbum(album.id)
        card = CallCard.Found(items.take(6).map { it.id })
        return ToolResult("album_open", true, "${album.title}: " + items.take(8).joinToString("; ") { "#${it.id} ${it.title}" }.ifBlank { "empty" })
    }

    private suspend fun papersRead(kind: String): ToolResult {
        val k = kind.trim().lowercase(Locale.ROOT).replace(" ", "_").let { if (it == "ids") "id" else it.removeSuffix("s") }
        val docs = dao.itemsOfType(ItemType.GALLERY).filter { it.kind != ItemKind.SCREENSHOT && (k.isBlank() || it.kind == k) }.take(8)
        if (docs.isEmpty()) return ToolResult("papers_read", true, "No ${kind.ifBlank { "documents" }} saved")
        card = CallCard.Found(docs.take(6).map { it.id })
        // With only a few matches, their details come along, so "what's my PNR" needs one step, not two.
        fun details(it: Item): String = if (docs.size > 3) "" else runCatching { org.json.JSONObject(it.fields) }.getOrNull()?.let { f ->
            f.keys().asSequence().mapNotNull { k -> f.optString(k).takeIf { v -> v.isNotBlank() }?.let { v -> "$k $v" } }.joinToString(", ").let { if (it.isBlank()) "" else ": $it" } } ?: ""
        return ToolResult("papers_read", true, docs.joinToString("; ") { "#${it.id} ${it.title} (${it.kind}, saved ${TextKit.dateLabel(it.createdAt, zone)})${details(it)}" })
    }

    private suspend fun renewalsRead(): ToolResult {
        val now = env.clock.now()
        val items = dao.expiring().filter { (it.expiresAt ?: 0) > now - 30 * 86_400_000L }.take(6)
        card = CallCard.Renewals
        if (items.isEmpty()) return ToolResult("renewals_read", true, "Nothing is due for renewal")
        return ToolResult("renewals_read", true, items.joinToString("; ") { "#${it.id} ${it.title} ends ${TextKit.dateLabel(it.expiresAt!!, zone)}" })
    }

    /** Deletes the one saved thing that clearly matches [words]; with several matches it lists them instead. */
    private suspend fun forget(words: String): ToolResult {
        val w = words.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 2 && it !in setOf("the", "entry", "item", "one", "that", "this", "from", "for", "with", "and", "test", "list", "please", "delete", "remove") }.toSet()
        if (w.isEmpty()) return ToolResult("forget", false, "what should I delete?")
        data class Hit(val score: Int, val label: String, val delete: suspend () -> Unit, val after: () -> Unit)
        fun score(text: String) = w.count { text.lowercase(Locale.ROOT).contains(it) }
        val hits = mutableListOf<Hit>()
        for (p in dao.allOpenPending()) {
            val who = dao.person(p.personId)?.name.orEmpty()
            hits += Hit(score("$who ${p.text}"), "#${p.id} with $who: ${p.text}", { dao.deletePending(p.id) }, { card = CallCard.People(p.personId) })
        }
        for (l in dao.itemsOfType(ItemType.CHECKLIST)) for (e in dao.entries(l.id))
            hits += Hit(score(e.text), "${e.text} on ${l.title}", { dao.deleteEntry(e.id) }, { card = CallCard.Checklist(l.id, changed = true); lastList = l.id })
        for (n in dao.itemsOfType(ItemType.NOTE)) hits += Hit(score(n.body.ifBlank { n.title }), "note #${n.id}: ${n.title}", { dao.deleteItem(n.id) }, { card = CallCard.Found(emptyList()) })
        for (r in dao.scheduledReminders().filter { it.key == null }) hits += Hit(score(r.text), "reminder #${r.id}: ${r.text}",
            { dao.updateReminder(r.copy(status = ReminderStatus.CANCELLED)); env.scheduler.cancel(r.id) }, { card = CallCard.Reminders })
        for (h in dao.habits()) hits += Hit(score(h.name), "habit ${h.name}", { dao.deleteHabitLogs(h.id); dao.deleteHabit(h.id) }, { card = CallCard.Habits })
        val best = hits.filter { it.score > 0 }.sortedByDescending { it.score }
        if (best.isEmpty()) return ToolResult("forget", false, "I couldn't find anything matching $words")
        if (best.size > 1 && best[1].score == best[0].score) return ToolResult("forget", false, "Several match; ask which one: " + best.takeWhile { it.score == best[0].score }.take(4).joinToString("; ") { it.label })
        best[0].delete(); best[0].after()
        if (card is CallCard.Found) card = null
        return ToolResult("forget", true, "deleted ${best[0].label}")
    }

    private suspend fun checklistUntick(entry: String, listName: String): ToolResult {
        val item = currentChecklist(listName) ?: return if (listName.isNotBlank()) noList(listName, "checklist_untick") else ToolResult("checklist_untick", false, "there is no list")
        val e = dao.entries(item.id).filter { it.checked }.maxByOrNull { TextKit.similarity(it.text, entry) }?.takeIf { TextKit.similarity(it.text, entry) >= 0.5 }
            ?: return ToolResult("checklist_untick", false, "$entry isn't ticked on ${item.title}")
        dao.updateEntry(e.copy(checked = false))
        lastList = item.id
        card = CallCard.Checklist(item.id, changed = true)
        return ToolResult("checklist_untick", true, "${e.text} is back on ${item.title}")
    }

    private suspend fun albumCreate(name: String): ToolResult {
        if (name.isBlank()) return ToolResult("album_create", false, "what should the album be called?")
        dao.albums().firstOrNull { it.title.equals(name.trim(), true) }?.let { return ToolResult("album_create", true, "you already have #${it.id} ${it.title}") }
        val id = dao.insertAlbum(Album(title = name.trim().replaceFirstChar { it.uppercase() }, autoCreated = false, createdAt = env.clock.now()))
        card = CallCard.Albums
        return ToolResult("album_create", true, "made album #$id ${name.trim()}")
    }

    private suspend fun itemMove(id: Long, albumName: String): ToolResult {
        val item = dao.item(id) ?: return ToolResult("item_move", false, "I couldn't find #$id")
        val albums = dao.albums()
        val album = num(albumName).takeIf { it > 0 }?.let { n -> albums.firstOrNull { it.id == n } }
            ?: albums.maxByOrNull { TextKit.similarity(it.title, albumName) }?.takeIf { TextKit.similarity(it.title, albumName) >= 0.4 || it.title.lowercase(Locale.ROOT).contains(albumName.lowercase(Locale.ROOT)) }
            ?: return ToolResult("item_move", false, "I couldn't find an album called $albumName")
        dao.updateItem(item.copy(albumId = album.id))
        card = CallCard.Found(listOf(id))
        return ToolResult("item_move", true, "put ${item.title} in ${album.title}")
    }

    private suspend fun paperRead(id: Long): ToolResult {
        val item = dao.item(id) ?: return ToolResult("paper_read", false, "I couldn't find #$id")
        val f = runCatching { org.json.JSONObject(item.fields) }.getOrNull()
        val fields = f?.keys()?.asSequence()?.mapNotNull { k -> f.optString(k).takeIf { it.isNotBlank() }?.let { "$k: $it" } }?.joinToString(", ").orEmpty()
        card = CallCard.Found(listOf(id))
        val dates = listOfNotNull(item.eventStart?.let { "on ${TextKit.dateLabel(it, zone)} at ${TextKit.clock(it, zone)}" }, item.expiresAt?.let { "expires ${TextKit.dateLabel(it, zone)}" })
        return ToolResult("paper_read", true, "${item.title} (${item.kind})" + (if (dates.isNotEmpty()) ", ${dates.joinToString(", ")}" else "") +
            (if (fields.isNotBlank()) ". $fields" else "") + (if (fields.isBlank() && item.body.isNotBlank()) ". ${item.body.take(300)}" else ""))
    }

    private suspend fun practiceSchedule(whenPhrase: String): ToolResult {
        if (whenPhrase.trim().lowercase(Locale.ROOT) in setOf("off", "stop", "none", "cancel")) {
            env.settings.practiceEnabled = false; env.planner.reconcile(); card = CallCard.Reminders
            return ToolResult("practice_schedule", true, "practice calls are off")
        }
        val at = TextKit.parseWhen(whenPhrase, env.clock.now(), zone) ?: return ToolResult("practice_schedule", false, "I didn't catch the time")
        val t = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
        env.settings.practiceMinutes = t.hour * 60 + t.minute
        env.settings.practiceEnabled = true
        env.planner.reconcile()
        card = CallCard.Reminders
        return ToolResult("practice_schedule", true, "I'll call you for English practice every day at ${TextKit.clock(at, zone)}")
    }

    private suspend fun activeFocus(): Reminder? = dao.remindersOfKind(ReminderKind.FOCUS).firstOrNull { it.status == ReminderStatus.SCHEDULED && it.fireAt > env.clock.now() }

    private suspend fun focusStart(minutes: Int, what: String): ToolResult {
        val now = env.clock.now()
        activeFocus()?.let { dao.updateReminder(it.copy(status = ReminderStatus.CANCELLED)); env.scheduler.cancel(it.id) }
        val mins = minutes.coerceIn(1, 240)
        val label = what.trim().ifBlank { "Focus" }.replaceFirstChar { it.uppercase() }
        val rem = Reminder(kind = ReminderKind.FOCUS, text = "Focus done: $label", fireAt = now + mins * 60_000L, surface = Surface.NOTIFY, createdAt = now,
            meta = org.json.JSONObject().put("minutes", mins).put("what", label).put("start", now).toString())
        val id = dao.insertReminder(rem)
        env.scheduler.schedule(rem.copy(id = id))
        card = CallCard.Focus
        return ToolResult("focus_start", true, "focusing on $label for $mins minutes, until ${TextKit.clock(rem.fireAt, zone)}")
    }

    private suspend fun focusStop(): ToolResult {
        val f = activeFocus() ?: return ToolResult("focus_stop", false, "no focus session is running")
        dao.updateReminder(f.copy(status = ReminderStatus.CANCELLED)); env.scheduler.cancel(f.id)
        card = CallCard.Focus
        val done = (env.clock.now() - org.json.JSONObject(f.meta).optLong("start", env.clock.now())) / 60_000
        return ToolResult("focus_stop", true, "stopped after $done minutes")
    }

    private suspend fun focusRead(): ToolResult {
        val f = activeFocus()
        card = CallCard.Focus
        if (f == null) return ToolResult("focus_read", true, "No focus session is running")
        val left = ((f.fireAt - env.clock.now()) / 60_000).coerceAtLeast(0)
        return ToolResult("focus_read", true, "$left minutes left on ${org.json.JSONObject(f.meta).optString("what", "focus")}")
    }

    private suspend fun habitAdd(name: String): ToolResult {
        val clean = name.trim().trimEnd('.').removePrefix("a ").removePrefix("the ").replaceFirstChar { it.uppercase() }.take(30)
        if (clean.isBlank()) return ToolResult("habit_add", false, "which habit?")
        card = CallCard.Habits
        if (dao.habitByName(clean) != null) return ToolResult("habit_add", true, "you're already tracking $clean")
        dao.insertHabit(Habit(name = clean, createdAt = env.clock.now()))
        return ToolResult("habit_add", true, "now tracking $clean")
    }

    /** Removes one entry, or every ticked one ("done"). Asked for by name; never guesses. */
    private suspend fun checklistRemove(entry: String, listName: String = ""): ToolResult {
        val item = currentChecklist(listName) ?: return if (listName.isNotBlank()) noList(listName, "checklist_remove") else ToolResult("checklist_remove", false, "there is no list")
        lastList = item.id
        val all = dao.entries(item.id)
        val targets = if (entry.trim().lowercase(Locale.ROOT) in setOf("done", "all done", "ticked", "completed", "checked")) all.filter { it.checked }
            else listOfNotNull(all.maxByOrNull { TextKit.similarity(it.text, entry) }?.takeIf { TextKit.similarity(it.text, entry) >= 0.5 })
        if (targets.isEmpty()) return ToolResult("checklist_remove", false, if (entry.trim().lowercase(Locale.ROOT) == "done") "nothing on the list is ticked" else "I couldn't find $entry on ${item.title}" + whereElse(entry))
        targets.forEach { dao.deleteEntry(it.id) }
        card = CallCard.Checklist(item.id, changed = true)
        return ToolResult("checklist_remove", true, "removed ${targets.joinToString { it.text }}")
    }

    /** Moves the daily morning or night call ("call me at 11 pm every day"). */
    private suspend fun checkinTime(which: String, whenPhrase: String): ToolResult {
        val at = TextKit.parseWhen(whenPhrase, env.clock.now(), zone) ?: return ToolResult("checkin_time", false, "I didn't catch the time")
        val t = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
        val minutes = t.hour * 60 + t.minute
        val night = when (which) { "morning" -> false; "night" -> true; else -> t.hour >= 15 || t.hour < 4 }
        if (night) env.settings.nightMinutes = minutes else env.settings.morningMinutes = minutes
        env.planner.reconcile()
        card = CallCard.Reminders
        val label = TextKit.clock(at, zone)
        return ToolResult("checkin_time", true, "I'll call you every ${if (night) "night" else "morning"} at $label")
    }

    private suspend fun noteSave(text: String): ToolResult {
        if (text.isBlank()) return ToolResult("note_save", false, "empty note")
        val now = env.clock.now()
        val albumId = context.albumId ?: env.activeAlbum()?.id
        val clean = text.trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        val nid = dao.insertItem(Item(type = ItemType.NOTE, title = clean.take(60), body = clean, albumId = albumId, source = "call", createdAt = now))
        card = CallCard.Found(listOf(nid))
        return ToolResult("note_save", true, "#$nid $clean")
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
        card = CallCard.Reminders
        return ToolResult("reminder_set", true, "#$id: I'll ring you ${TextKit.dayLabel(at, now, zone)} at ${TextKit.clock(at, zone)}" + if (check) " and ask" else "")
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
        card = CallCard.Habits
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
        card = CallCard.Reminders
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
        val pid = dao.insertPending(PendingItem(personId = p.id, text = text.trim().replaceFirstChar { it.uppercase() }, sourceApp = "call", createdAt = env.clock.now()))
        card = CallCard.People(p.id)
        return ToolResult("pending_add", true, "saved #$pid for ${p.name}: ${text.trim()}; I'll bring it up next time ${p.name} messages")
    }

    private suspend fun pendingResolve(person: String): ToolResult {
        val p = dao.personByName(TextKit.normalize(person)) ?: return ToolResult("pending_resolve", false, "nothing open with $person")
        val open = dao.openPending(p.id)
        open.forEach { dao.updatePending(it.copy(status = "DONE")) }
        card = CallCard.People(p.id)
        return ToolResult("pending_resolve", true, "cleared ${open.size} item${if (open.size == 1) "" else "s"} with ${p.name}")
    }

    private suspend fun albumSearch(query: String): ToolResult {
        val hits = env.search(query)
        if (hits.isEmpty()) return ToolResult("album_search", false, "I couldn't find anything for $query")
        val top = hits.first()
        card = CallCard.Found(hits.take(3).map { it.id })
        val album = top.albumId?.let { dao.album(it) }
        return ToolResult("album_search", true, "${top.title}" + (album?.let { ", in ${it.title}" } ?: "") + ", saved ${TextKit.dateLabel(top.createdAt, zone)}")
    }

    /** "Yes, remind me the day before" on a renewal call. */
    private suspend fun renewalRemind(id: Long = -1): ToolResult {
        val now = env.clock.now()
        val item = (if (id > 0) dao.item(id) else null) ?: context.reminderId?.let { dao.reminder(it) }?.itemId?.let { dao.item(it) } ?: dao.expiring().firstOrNull { (it.expiresAt ?: 0) > now }
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
        if (day.isBlank() || day in setOf("latest", "last", "recent")) {
            val latest = dao.itemsOfType(ItemType.JOURNAL).firstOrNull() ?: return ToolResult("journal_lookup", false, "Your journal is empty")
            card = CallCard.Journal(latest.id)
            return ToolResult("journal_lookup", true, "${TextKit.dateLabel(latest.eventStart ?: latest.createdAt, zone)}: ${latest.body}")
        }
        val target = TextKit.parseWhen(day.replace("last ", ""), now - 7 * 86_400_000L, zone)?.let { TextKit.dayKey(it, zone) }
            ?: if ("yesterday" in day) TextKit.dayKey(now - 86_400_000, zone) else TextKit.dayKey(now, zone)
        val entry = dao.itemsOfType(ItemType.JOURNAL).firstOrNull { TextKit.dayKey(it.eventStart ?: it.createdAt, zone) == target }
            ?: return ToolResult("journal_lookup", false, "I don't have a journal entry for that day")
        card = CallCard.Journal(entry.id)
        return ToolResult("journal_lookup", true, entry.body)
    }
}

/** What the agent needs from the rest of the app. Implemented by the engine. */
interface AgentEnv {
    val dao: com.cue.domain.CueDao
    val settings: com.cue.domain.Settings
    val clock: com.cue.domain.Clock
    val scheduler: com.cue.domain.TriggerScheduler
    val planner: Planner
    suspend fun activeAlbum(): Album?
    suspend fun streak(habitId: Long): Int
    suspend fun person(name: String): Person
    suspend fun search(query: String): List<Item>
    fun log(line: String)
}
