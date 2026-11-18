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
import com.cue.domain.MeasureKind
import com.cue.domain.Measurement
import com.cue.domain.PlanKind
import com.cue.domain.RuleTrigger
import com.cue.domain.PendingItem
import com.cue.domain.Person
import com.cue.domain.Reminder
import com.cue.domain.ReminderKind
import com.cue.domain.ReminderStatus
import com.cue.domain.Surface
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

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
    val habitCheckin = ToolSpec("habit_checkin", "Record whether a habit happened today", listOf(ToolParam("habit", "string", "habit name; empty on a habit's own call", required = false), ToolParam("done", "string", "yes or no")))
    private val confirmParam = ToolParam("confirm", "string", "yes only after the user agreed to exactly what you described", required = false)
    val doseSchedule = ToolSpec("dose_schedule", "Track a medicine: ring the user at its times every day. Calling it again for the same medicine updates that plan", listOf(
        ToolParam("medicine", "string", "medicine name, like Metformin 500"),
        ToolParam("times", "string", "when, in the user's words: '1 pm after lunch', '9 am and 9 pm', 'after breakfast and dinner'"),
        ToolParam("days", "string", "number of days, or 'ongoing' if the doctor gave no end"),
        ToolParam("food", "string", "before, after or with food, if said", required = false),
        ToolParam("track_time", "string", "yes to note the time each dose is actually taken", required = false),
        ToolParam("purpose", "string", "what it is for, like BP, sugar, thyroid, if said", required = false),
        ToolParam("per_day", "string", "times per day, only if no times were said", required = false)))
    val doseTaken = ToolSpec("dose_taken", "Record the current dose as taken or not (notes the time)", listOf(ToolParam("done", "string", "yes or no")))
    val medicinesRead = ToolSpec("medicines_read", "Read the medicines and measuring routines Cue is tracking, with their times", emptyList())
    val medicineStop = ToolSpec("medicine_stop", "Stop tracking a medicine or a measuring routine", listOf(ToolParam("medicine", "string", "medicine name, or bp / sugar for a measuring routine"), confirmParam))
    val measureLog = ToolSpec("measure_log", "Save a health reading the user tells you: blood pressure, sugar, weight or pulse", listOf(
        ToolParam("kind", "string", "bp, sugar, weight or pulse"), ToolParam("value", "string", "the reading, like 130/85 or 110"),
        ToolParam("context", "string", "fasting, after food, morning... if said", required = false)))
    val measuresRead = ToolSpec("measures_read", "Read recent health readings with the average", listOf(ToolParam("kind", "string", "bp, sugar, weight or pulse")))
    val measureSchedule = ToolSpec("measure_schedule", "Ring the user at set times to take a reading (BP, sugar...)", listOf(
        ToolParam("kind", "string", "bp, sugar, weight or pulse"), ToolParam("times", "string", "when, like '8 am' or 'before breakfast'"),
        ToolParam("days", "string", "number of days, or 'ongoing'")))
    val remember = ToolSpec("remember", "Save something about the user to their profile so you know it on every call: a preference, routine (meal, wake or sleep times), health fact, or a person in their life. It replaces an older fact on the same thing", listOf(
        ToolParam("fact", "string", "the fact in third person, like 'Has lunch at 1 PM' or 'Allergic to penicillin'"),
        ToolParam("topic", "string", "about, routine, preference, health or people")))
    val profileRead = ToolSpec("profile_read", "Read what Cue knows about the user", listOf(ToolParam("topic", "string", "about, routine, preference, health, people, or empty for all", required = false)))
    val profileForget = ToolSpec("profile_forget", "Delete a fact from the user's profile", listOf(ToolParam("id", "string", "the fact's number from profile_read")))
    val followUp = ToolSpec("follow_up", "Call the user back later to check how something went. Use it whenever you say you'll check back, ask later or follow up", listOf(
        ToolParam("about", "string", "what to ask about, like 'how the doctor visit went'"), ToolParam("when", "string", "spoken time, like tomorrow 7 pm")))
    val ruleAdd = ToolSpec("rule_add", "Set a 'when X happens' rule: when the user reaches or leaves a place, when someone messages, or on the morning or night call, Cue says this", listOf(
        ToolParam("when", "string", "arrive, leave, message, morning or night"),
        ToolParam("match", "string", "the place (home, office, gym, clinic or a saved place name) or the person; empty for morning/night", required = false),
        ToolParam("say", "string", "what Cue should remind them of"),
        ToolParam("repeat", "string", "yes for every time, otherwise just once", required = false)))
    val rulesRead = ToolSpec("rules_read", "Read the user's 'when X happens' rules", emptyList())
    val ruleRemove = ToolSpec("rule_remove", "Delete a 'when X happens' rule", listOf(ToolParam("id", "string", "the rule's number from rules_read")))
    val pendingAdd = ToolSpec("pending_add", "Remember something pending with a person", listOf(ToolParam("person", "string", "name"), ToolParam("text", "string", "what is pending")))
    val pendingResolve = ToolSpec("pending_resolve", "Mark something with a person as done", listOf(ToolParam("person", "string", "name")))
    val albumSearch = ToolSpec("album_search", "Find a saved document or note", listOf(ToolParam("query", "string", "what to find")))
    val journalLookup = ToolSpec("journal_lookup", "Read back what the user did on a day", listOf(ToolParam("day", "string", "like last Sunday or yesterday; empty for the latest", required = false)))

    val checklistRemove = ToolSpec("checklist_remove", "Remove an entry from the list, or every ticked entry when the user asks to clear what is done", listOf(
        ToolParam("entry", "string", "the entry's words exactly as the user said them, or 'done' for every ticked entry"), listParam))
    val habitAdd = ToolSpec("habit_add", "Start tracking a new habit", listOf(ToolParam("habit", "string", "short habit name, like Journal or Walk"),
        ToolParam("check_at", "string", "time to call daily and ask if it's done, like 7 pm, if the user said", required = false)))
    val habitTime = ToolSpec("habit_time", "Set or change the daily time Cue calls to ask about a habit, or 'off' for no call", listOf(
        ToolParam("habit", "string", "habit name"), ToolParam("when", "string", "spoken time like 7 pm, or off")))
    val habitPause = ToolSpec("habit_pause", "Pause a habit (travel, illness, a break): no check-in calls, and the streak is kept, not broken", listOf(
        ToolParam("habit", "string", "habit name, or 'all'"), ToolParam("until", "string", "how long, like 'a week', 'till Monday', or empty until they resume", required = false)))
    val habitResume = ToolSpec("habit_resume", "Resume a paused habit", listOf(ToolParam("habit", "string", "habit name, or 'all'")))
    val listRead = ToolSpec("list_read", "Read one list (what is left and what is done). Use it whenever they ask about a list", listOf(listParam))
    val listsRead = ToolSpec("lists_read", "Name all the user's lists with how much is left on each", emptyList())
    val listCreate = ToolSpec("list_create", "Make a new list, optionally with its first entries", listOf(ToolParam("name", "string", "the new list's name"),
        ToolParam("entries", "string", "comma-separated first entries", required = false)))
    val listRename = ToolSpec("list_rename", "Rename a list", listOf(ToolParam("list", "string", "the list's name now"), ToolParam("name", "string", "new name")))
    val listDelete = ToolSpec("list_delete", "Delete a whole list (only when the user clearly asks)", listOf(ToolParam("list", "string", "the list's name"),
        ToolParam("confirm", "string", "yes only after the user agreed to delete it", required = false)))
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
    val habitRemove = ToolSpec("habit_remove", "Stop tracking a habit (its history and check-in calls go too)", listOf(ToolParam("habit", "string", "habit name"),
        ToolParam("confirm", "string", "yes only after the user agreed", required = false)))
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
        ToolParam("words", "string", "what it is, in the user's words, like 'the Rohit concert tickets'"),
        ToolParam("confirm", "string", "yes only after the user agreed, when asked", required = false)))
    val focusStart = ToolSpec("focus_start", "Start a focus session; I'll quietly let the user know when time is up", listOf(
        ToolParam("minutes", "string", "length in minutes, 25 if not said", required = false), ToolParam("what", "string", "what they will focus on", required = false)))
    val focusStop = ToolSpec("focus_stop", "Stop the focus session that is running", emptyList())
    val focusRead = ToolSpec("focus_read", "How much time is left in the focus session", emptyList())

    private val spaceParam = ToolParam("space", "string", "which space or album, by name; empty for the one this call is about", required = false)
    val spaceCreate = ToolSpec("space_create", "Make a space: one page for something the user is planning or going through (a trip, a doctor visit or surgery, a wedding, moving house, an exam). Cue picks the blocks; you may add more. Use it when the user starts planning something with several parts. Before calling, ask at most two short questions the user's words don't already answer: when it is, who is in it with them, and what they want kept track of (money, schedule, to-dos, documents, photos). Skip any they already said; if they say just make it, call right away", listOf(
        ToolParam("goal", "string", "what they are doing, in their words, like 'Goa trip next Friday with Riya'"),
        ToolParam("name", "string", "short name, like Goa trip", required = false),
        ToolParam("start", "string", "start day, like 20 Nov or next Friday", required = false),
        ToolParam("end", "string", "end day", required = false),
        ToolParam("with", "string", "people sharing it or costs, comma-separated", required = false),
        ToolParam("add", "string", "extra blocks beyond the usual ones, comma-separated: checklist:<name>, expenses, itinerary, photos, documents, notes, journal, people, readings, medicines, countdown", required = false),
        confirmParam))
    val spaceRead = ToolSpec("space_read", "Read what is in a space: its blocks, lists, expenses, itinerary and documents", listOf(spaceParam))
    val blockAdd = ToolSpec("block_add", "Add a block to a space (a new list, expenses, itinerary, photos...)", listOf(
        ToolParam("block", "string", "checklist:<name>, expenses, itinerary, photos, documents, notes, journal, people, readings, medicines or countdown"), spaceParam))
    val expenseAdd = ToolSpec("expense_add", "Log money spent in a space's expenses (who paid, for what)", listOf(
        ToolParam("amount", "string", "amount in rupees, like 1200"), ToolParam("what", "string", "what it was for"),
        ToolParam("paid_by", "string", "who paid; empty for the user", required = false), spaceParam))
    val expensesRead = ToolSpec("expenses_read", "Read a space's spending: total, budget left, and who owes whom", listOf(spaceParam))
    val itineraryAdd = ToolSpec("itinerary_add", "Add a timed stop or event to a space's itinerary", listOf(
        ToolParam("what", "string", "the stop or event, like 'Check in at Taj'"), ToolParam("when", "string", "day and time, like Saturday 2 pm"),
        ToolParam("place", "string", "where, if said", required = false), spaceParam))

    /** A realtime model calls tools well: it gets every app's tools, reads and edits included, and can hang up. */
    val live = listOf(checklistAdd, checklistTick, checklistRemove, checklistEdit, listRead, listsRead, listCreate, listRename, listDelete, noteSave, notesRead, noteEdit, noteRemove,
        reminderSet, reminderMove, reminderEdit, reminderCancel, remindersRead, checkinTime, eventMove,
        habitCheckin, habitAdd, habitRemove, habitRename, habitsRead, habitTime, habitPause, habitResume, doseSchedule, doseTaken,
        pendingAdd, pendingUpdate, pendingDone, pendingRemove, pendingResolve, peopleRead,
        albumSearch, albumsRead, albumOpen, papersRead, renewalsRead, renewalRemind, journalLookup, journalAdd,
        forget, checklistUntick, albumCreate, itemMove, paperRead, practiceSchedule, focusStart, focusStop, focusRead,
        medicinesRead, medicineStop, measureLog, measuresRead, measureSchedule, remember, profileRead, profileForget, followUp, ruleAdd, rulesRead, ruleRemove,
        spaceCreate, spaceRead, blockAdd, expenseAdd, expensesRead, itineraryAdd, endCall)

    val converse = listOf(checklistAdd, checklistTick, noteSave, reminderSet, eventMove, habitCheckin, doseSchedule, doseTaken,
        pendingAdd, pendingResolve, albumSearch, journalLookup, remember, measureLog, followUp, expenseAdd)

    /** Every tool by name, for checking a call's required details before it runs. */
    val byName: Map<String, ToolSpec> by lazy { (live + converse + compact).associateBy { it.name } }

    /** Questions for missing details, in place of a generic "what is the X". */
    val questions = mapOf(
        "dose_schedule.medicine" to "Which medicine is it?",
        "dose_schedule.times" to "What time should you take it?",
        "dose_schedule.days" to "For how many days, or is it ongoing?",
        "measure_schedule.times" to "What time should I remind you to check it?",
        "measure_schedule.days" to "For how many days, or every day until the doctor says stop?",
        "reminder_set.when" to "When should I ring you?",
        "reminder_set.text" to "What should I remind you about?",
        "follow_up.when" to "When should I check back with you?",
        "measure_log.value" to "What was the reading?",
        "measure_log.kind" to "Is that your blood pressure, sugar, weight or pulse?",
        "rule_add.say" to "What should I remind you of then?",
        "rule_add.when" to "When should this happen: when you reach a place, leave one, when someone messages, or on the morning or night call?",
        "checkin_time.when" to "What time should I call?",
        "pending_add.person" to "Who is that with?",
        "habit_time.when" to "What time should I call to ask about it?",
        "space_create.goal" to "What are you planning?",
        "expense_add.amount" to "How much was it?",
        "expense_add.what" to "What was it for?",
        "itinerary_add.when" to "What day and time is that?",
        "itinerary_add.what" to "What should I put on the itinerary?",
        "block_add.block" to "What should I add: a list, expenses, itinerary, photos or notes?",
    )
    /** A slot that another argument can stand in for. */
    val alternatives = mapOf("dose_schedule.times" to "per_day")
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

/** A ring that repeats every day: a plan slot, a habit check-in, a daily call. */
fun isDaily(r: Reminder): Boolean = r.key?.let { it.startsWith("plan:") || it.startsWith("habit:") || it.startsWith("checkin:") || it.startsWith("practice:") } == true

/** One row per plan or daily ring (its next time), not one per day: what the user thinks of as one reminder. */
fun collapseDaily(rs: List<Reminder>): List<Reminder> {
    val seen = mutableSetOf<String>()
    return rs.sortedBy { it.fireAt }.filter { r ->
        val k = r.key ?: return@filter true
        val group = if (k.startsWith("plan:")) "plan:" + k.split(":")[1] + ":" + k.split(":").last() else k
        seen.add(group)
    }
}

/** Tools that only read: they answer and show a card, but don't count as something the call changed. */
private val READS = setOf("list_read", "habits_read", "reminders_read", "people_read", "album_search", "journal_lookup", "end_call",
    "notes_read", "albums_read", "album_open", "papers_read", "renewals_read", "focus_read", "medicines_read", "measures_read", "profile_read", "rules_read", "space_read", "expenses_read")

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
        missing(call)?.let { env.log("tool ${call.name} $a -> needs ${it.message}"); return it }
        val r = when (call.name) {
            "checklist_add" -> checklistAdd(a["entries"].orEmpty(), a["list"].orEmpty())
            "checklist_tick" -> checklistTick(a["entry"].orEmpty(), a["list"].orEmpty())
            "checklist_remove" -> checklistRemove(a["entry"].orEmpty(), a["list"].orEmpty())
            "lists_read" -> listsRead()
            "list_create" -> listCreate(a["name"].orEmpty(), a["entries"].orEmpty())
            "list_rename" -> listRename(a["list"].orEmpty(), a["name"].orEmpty())
            "list_delete" -> listDelete(a["list"].orEmpty(), a["confirm"] == "yes")
            "habit_add" -> habitAdd(a["habit"].orEmpty(), a["check_at"].orEmpty())
            "habit_time" -> habitTime(a["habit"].orEmpty(), a["when"].orEmpty())
            "habit_pause" -> habitPause(a["habit"].orEmpty(), a["until"].orEmpty())
            "habit_resume" -> habitResume(a["habit"].orEmpty())
            "checklist_edit" -> checklistEdit(a["entry"].orEmpty(), a["text"].orEmpty(), a["list"].orEmpty())
            "pending_update" -> pendingUpdate(findPending(a), a["person"].orEmpty(), a["text"].orEmpty())
            "pending_remove" -> pendingRemove(findPending(a))
            "pending_done" -> pendingDone(findPending(a))
            "habit_remove" -> habitRemove(a["habit"].orEmpty(), a["confirm"] == "yes")
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
            "dose_schedule" -> doseSchedule(a)
            "medicines_read" -> medicinesRead()
            "medicine_stop" -> medicineStop(a["medicine"].orEmpty(), a["confirm"] == "yes")
            "measure_log" -> measureLog(a["kind"].orEmpty(), a["value"].orEmpty(), a["context"].orEmpty())
            "measures_read" -> measuresRead(a["kind"].orEmpty())
            "measure_schedule" -> measureSchedule(a["kind"].orEmpty(), a["times"].orEmpty(), a["days"].orEmpty())
            "remember" -> remember(a["fact"].orEmpty(), a["topic"].orEmpty())
            "profile_read" -> profileRead(a["topic"].orEmpty())
            "profile_forget" -> profileForget(num(a["id"]))
            "follow_up" -> followUp(a["about"].orEmpty(), a["when"].orEmpty())
            "rule_add" -> ruleAdd(a["when"].orEmpty(), a["match"].orEmpty(), a["say"].orEmpty(), a["repeat"] == "yes")
            "rules_read" -> rulesRead()
            "rule_remove" -> ruleRemove(num(a["id"]))
            "dose_taken" -> doseTaken(a["done"] != "no")
            "pending_add" -> pendingAdd(a["person"].orEmpty(), a["text"].orEmpty())
            "pending_resolve" -> pendingResolve(a["person"].orEmpty())
            "album_search" -> albumSearch(a["query"].orEmpty())
            "journal_lookup" -> journalLookup(a["day"].orEmpty())
            "renewal_remind" -> renewalRemind(num(a["id"]))
            "forget" -> forget(a["words"].orEmpty(), a["confirm"] == "yes")
            "checklist_untick" -> checklistUntick(a["entry"].orEmpty(), a["list"].orEmpty())
            "album_create" -> albumCreate(a["name"].orEmpty())
            "item_move" -> itemMove(num(a["id"]), a["album"].orEmpty())
            "paper_read" -> paperRead(num(a["id"]))
            "practice_schedule" -> practiceSchedule(a["when"].orEmpty())
            "list_read" -> listRead(a["list"].orEmpty())
            "habits_read" -> habitsRead()
            "reminders_read" -> remindersRead()
            "people_read" -> peopleRead(a["person"].orEmpty())
            "space_create" -> spaceCreate(a)
            "space_read" -> spaceRead(a["space"].orEmpty())
            "block_add" -> blockAdd(a["block"].orEmpty(), a["space"].orEmpty())
            "expense_add" -> expenseAdd(a["amount"].orEmpty(), a["what"].orEmpty(), a["paid_by"].orEmpty(), a["space"].orEmpty())
            "expenses_read" -> expensesRead(a["space"].orEmpty())
            "itinerary_add" -> itineraryAdd(a["what"].orEmpty(), a["when"].orEmpty(), a["place"].orEmpty(), a["space"].orEmpty())
            "end_call" -> ToolResult("end_call", true, "hanging up after your goodbye")
            else -> ToolResult(call.name, false, "I can't do that yet.")
        }
        // The summary keeps what happened; the instructions for the model (follow-ups, how to tell the user) stay out.
        if (r.ok && call.name !in READS) changes += "${call.name}: " + r.message.substringBefore(" FOLLOW-UP").substringBefore(". FOLLOW-UP")
            .replace(Regex("IMPACT: "), "").replace(" Tell the user in a few words.", "").replace(" (I picked these times; ask if another time suits better)", "")
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

    private suspend fun listDelete(name: String, confirmed: Boolean): ToolResult {
        val item = findList(name) ?: return noList(name, "list_delete")
        val count = dao.entries(item.id).size
        if (!confirmed && count > 0) return Ask.confirm("list_delete", "Delete the whole ${item.title} list with its $count entr${if (count == 1) "y" else "ies"}?")
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
            "${h.name}: ${if (streak > 0) "$streak-day streak" else "no streak yet"}${if (doneToday) ", done today" else ""}" +
                (h.checkMinutes?.let { ", I call at ${Anchors.clock(it)}" } ?: "") +
                (if (h.pausedAt(env.clock.now())) ", paused" + (h.pausedUntil?.takeIf { it != Long.MAX_VALUE }?.let { " until ${TextKit.dateLabel(it, zone)}" } ?: "") else "")
        }
        return ToolResult("habits_read", true, parts.joinToString(". "))
    }

    private suspend fun remindersRead(): ToolResult {
        val now = env.clock.now()
        val next = collapseDaily(dao.scheduledReminders().filter { it.kind != ReminderKind.PRELOAD && it.fireAt > now }).take(8)
        card = CallCard.Reminders
        if (next.isEmpty()) return ToolResult("reminders_read", true, "Nothing is scheduled")
        return ToolResult("reminders_read", true, "Coming up: " + next.joinToString("; ") { r ->
            if (isDaily(r)) "${reminderLabel(r)} every day at ${TextKit.clock(r.fireAt, zone)}" + (r.itemId?.takeIf { r.key?.startsWith("plan:") == true }?.let { " (plan #$it)" } ?: "")
            else "#${r.id} ${TextKit.dayLabel(r.fireAt, now, zone)} at ${TextKit.clock(r.fireAt, zone)}, ${reminderLabel(r)}" })
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

    private suspend fun habitRemove(name: String, confirmed: Boolean): ToolResult {
        val h = habitByWords(name) ?: return ToolResult("habit_remove", false, "you aren't tracking $name")
        val streak = env.streak(h.id)
        if (!confirmed && dao.habitLogs(h.id).isNotEmpty()) return Ask.confirm("habit_remove", "Stop tracking ${h.name}? ${if (streak > 0) "Your $streak-day streak and its" else "Its"} history will be deleted.")
        val linked = Impact(env).habitRemoved(h.id)
        dao.deleteHabitLogs(h.id); dao.deleteHabit(h.id)
        card = CallCard.Habits
        return ToolResult("habit_remove", true, "stopped tracking ${h.name}$linked")
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
        val impact = Impact(env).check(at, setOf(r.id))
        return ToolResult("reminder_move", true, "moved ${reminderLabel(r)} to ${TextKit.dayLabel(at, env.clock.now(), zone)} at ${TextKit.clock(at, zone)}" + if (impact.isNotBlank()) ". $impact" else "")
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
    private suspend fun forget(words: String, confirmed: Boolean = false): ToolResult {
        val w = words.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 2 && it !in setOf("the", "entry", "item", "one", "that", "this", "from", "for", "with", "and", "test", "list", "please", "delete", "remove") }.toSet()
        if (w.isEmpty()) return ToolResult("forget", false, "what should I delete?")
        data class Hit(val score: Int, val label: String, val delete: suspend () -> Unit, val after: () -> Unit, val weighty: Boolean = false)
        fun score(text: String) = w.count { text.lowercase(Locale.ROOT).contains(it) }
        val hits = mutableListOf<Hit>()
        for (p in dao.allOpenPending()) {
            val who = dao.person(p.personId)?.name.orEmpty()
            hits += Hit(score("$who ${p.text}"), "#${p.id} with $who: ${p.text}", { dao.deletePending(p.id) }, { card = CallCard.People(p.personId) })
        }
        for (l in dao.itemsOfType(ItemType.CHECKLIST)) for (e in dao.entries(l.id))
            hits += Hit(score(e.text), "${e.text} on ${l.title}", { dao.deleteEntry(e.id) }, { card = CallCard.Checklist(l.id, changed = true); lastList = l.id })
        for (n in dao.itemsOfType(ItemType.NOTE)) hits += Hit(score(n.body.ifBlank { n.title }), "note #${n.id}: ${n.title}", { dao.deleteItem(n.id) }, { card = CallCard.Found(emptyList()) }, weighty = true)
        for (r in dao.scheduledReminders().filter { it.key == null }) hits += Hit(score(r.text), "reminder #${r.id}: ${r.text}",
            { dao.updateReminder(r.copy(status = ReminderStatus.CANCELLED)); env.scheduler.cancel(r.id) }, { card = CallCard.Reminders })
        for (h in dao.habits()) hits += Hit(score(h.name), "habit ${h.name}", { Impact(env).habitRemoved(h.id); dao.deleteHabitLogs(h.id); dao.deleteHabit(h.id) }, { card = CallCard.Habits }, weighty = true)
        val best = hits.filter { it.score > 0 }.sortedByDescending { it.score }
        if (best.isEmpty()) return ToolResult("forget", false, "I couldn't find anything matching $words")
        if (best.size > 1 && best[1].score == best[0].score) return ToolResult("forget", false, "Several match; ask which one: " + best.takeWhile { it.score == best[0].score }.take(4).joinToString("; ") { it.label })
        if (best[0].weighty && !confirmed) return Ask.confirm("forget", "Delete ${best[0].label}?")
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

    private suspend fun habitsNamed(name: String): List<Habit> =
        if (name.trim().lowercase(Locale.ROOT) in setOf("all", "everything", "all habits", "all my habits")) dao.habits() else listOfNotNull(habitByWords(name))

    private suspend fun habitTime(name: String, whenPhrase: String): ToolResult {
        val h = habitByWords(name) ?: return ToolResult("habit_time", false, "you aren't tracking $name")
        card = CallCard.Habits
        if (whenPhrase.trim().lowercase(Locale.ROOT) in setOf("off", "none", "stop", "no call", "cancel")) {
            dao.updateHabit(h.copy(checkMinutes = null)); env.planner.reconcile()
            return ToolResult("habit_time", true, "no more check-in calls for ${h.name}")
        }
        val m = Anchors.minutes(whenPhrase) ?: return Ask.needs("habit_time", "when", CueTools.questions["habit_time.when"]!!)
        dao.updateHabit(h.copy(checkMinutes = m))
        env.planner.reconcile()
        val impact = Impact(env).check(TextKit.nextDaily(m, env.clock.now(), zone), setOfNotNull(dao.reminderByKey("habit:${h.id}")?.id))
        return ToolResult("habit_time", true, "I'll call every day at ${Anchors.clock(m)} to ask about ${h.name}" + if (impact.isNotBlank()) ". $impact" else "")
    }

    /** Days of a pause that has ended are written down as paused, so the streak still skips them later. */
    private suspend fun closePause(h: Habit, end: Long) {
        val from = h.pausedFrom ?: return
        var d = TextKit.local(from, zone).toLocalDate()
        val last = TextKit.local(minOf(end, h.pausedUntil ?: Long.MAX_VALUE), zone).toLocalDate()
        val done = dao.habitLogs(h.id).filter { it.done }.map { it.day }.toSet()
        while (!d.isAfter(last)) {
            if (d.toString() !in done) dao.upsertHabitLog(HabitLog(habitId = h.id, day = d.toString(), done = false, paused = true))
            d = d.plusDays(1)
        }
    }

    private suspend fun habitPause(name: String, until: String): ToolResult {
        val habits = habitsNamed(name).ifEmpty { return ToolResult("habit_pause", false, "you aren't tracking $name") }
        val now = env.clock.now()
        val end = until.trim().takeIf { it.isNotBlank() }?.let { u ->
            untilOf(u)?.takeIf { it > 0 } ?: TextKit.parseWhen(u.replace(Regex("(?i)\\b(till|until|up to)\\b"), ""), now, zone)
        } ?: Long.MAX_VALUE
        for (h in habits) {
            if (h.pausedFrom != null && !h.pausedAt(now)) closePause(h, now)
            dao.updateHabit(h.copy(pausedFrom = now, pausedUntil = end))
        }
        env.planner.reconcile()
        card = CallCard.Habits
        val names = habits.joinToString { it.name }
        return ToolResult("habit_pause", true, "paused $names" + (if (end == Long.MAX_VALUE) " until you say resume" else " until ${TextKit.dateLabel(end, zone)}") +
            "; no check-in calls, and the streak is kept")
    }

    private suspend fun habitResume(name: String): ToolResult {
        val habits = habitsNamed(name).filter { it.pausedFrom != null }.ifEmpty { return ToolResult("habit_resume", false, "$name isn't paused") }
        val now = env.clock.now()
        for (h in habits) { closePause(h, now); dao.updateHabit(h.copy(pausedFrom = null, pausedUntil = null)) }
        env.planner.reconcile()
        card = CallCard.Habits
        return ToolResult("habit_resume", true, "resumed ${habits.joinToString { it.name }}" + habits.mapNotNull { it.checkMinutes }.firstOrNull()?.let { "; check-in calls are back at ${Anchors.clock(it)}" }.orEmpty())
    }

    private suspend fun habitAdd(name: String, checkAt: String = ""): ToolResult {
        val clean = name.trim().trimEnd('.').removePrefix("a ").removePrefix("the ").replaceFirstChar { it.uppercase() }.take(30)
        if (clean.isBlank()) return ToolResult("habit_add", false, "which habit?")
        card = CallCard.Habits
        if (dao.habitByName(clean) != null) return ToolResult("habit_add", true, "you're already tracking $clean")
        dao.insertHabit(Habit(name = clean, createdAt = env.clock.now()))
        if (checkAt.isNotBlank()) {
            val t = habitTime(clean, checkAt)
            return ToolResult("habit_add", true, "now tracking $clean; " + t.message)
        }
        return ToolResult("habit_add", true, "now tracking $clean. " + Playbooks.afterHabit(clean))
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
        val own = dao.reminderByKey(if (night) "checkin:night" else "checkin:morning")?.id
        val impact = Impact(env).check(TextKit.nextDaily(minutes, env.clock.now(), zone), setOfNotNull(own))
        return ToolResult("checkin_time", true, "I'll call you every ${if (night) "night" else "morning"} at $label" + if (impact.isNotBlank()) ". $impact" else "")
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
        val impact = Impact(env).check(at)
        val rem = Reminder(kind = kind, text = text.trim().replaceFirstChar { it.uppercase() }, fireAt = at, surface = Surface.RING, habitId = habitId, createdAt = now)
        val id = dao.insertReminder(rem)
        env.scheduler.schedule(rem.copy(id = id))
        card = CallCard.Reminders
        return ToolResult("reminder_set", true, "#$id: I'll ring you ${TextKit.dayLabel(at, now, zone)} at ${TextKit.clock(at, zone)}" + (if (check) " and ask" else "") +
            if (impact.isNotBlank()) ". $impact" else "")
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

    /** Required details first; a detail another argument can stand in for (times from per_day) passes. */
    // ------------------------------------------------------------------ spaces

    private val spaces by lazy { Spaces(env) }
    /** The space proposed on this call, waiting for the user's yes. */
    private var proposed: SpaceSpec? = null

    private suspend fun spaceCreate(a: Map<String, String>): ToolResult {
        val goal = a["goal"].orEmpty()
        val confirmed = a["confirm"] == "yes"
        val spec = proposed?.takeIf { confirmed } ?: spaces.fromTemplate(goal, a["name"].orEmpty(), a["start"].orEmpty(), a["end"].orEmpty(), a["with"].orEmpty(), a["add"].orEmpty())
        dao.albums().firstOrNull { it.title.equals(spec.title, true) }?.let { return ToolResult("space_create", true, "the space ${it.title} already exists (#${it.id}); use block_add to add to it") }
        if (!confirmed) {
            proposed = spec
            val dates = spec.start?.let { s -> " for " + s.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)) + (spec.end?.takeIf { it != s }?.let { e -> " to " + e.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)) } ?: "") } ?: ""
            return Ask.confirm("space_create", "I'll make a ${spec.title} space$dates with ${spec.describe()}. Shall I?")
        }
        proposed = null
        val id = spaces.create(spec)
        card = CallCard.Albums
        val missing = listOfNotNull("the dates".takeIf { spec.start == null && spec.kind in setOf(com.cue.domain.AlbumKind.TRIP, com.cue.domain.AlbumKind.EVENT, com.cue.domain.AlbumKind.MOVE, com.cue.domain.AlbumKind.STUDY) })
        return ToolResult("space_create", true, "made the space #$id ${spec.title} with ${spec.describe()}." +
            (if (missing.isNotEmpty()) " FOLLOW-UP: ask for ${missing.joinToString()}." else " Tell the user in a few words."))
    }

    private suspend fun spaceFor(name: String): com.cue.domain.Album? = spaces.find(name, context.albumId)

    private suspend fun spaceRead(name: String): ToolResult {
        val album = spaceFor(name) ?: return ToolResult("space_read", false, if (name.isBlank()) "there is no space yet" else "I couldn't find a space called $name")
        spaces.ensureBlocks(album)
        val parts = mutableListOf<String>()
        album.startAt?.let { parts += "starts ${TextKit.dateLabel(it, zone)}" }
        for (b in dao.blocks(album.id)) {
            val cfg = org.json.JSONObject(b.config)
            when (b.type) {
                com.cue.domain.BlockType.CHECKLIST -> {
                    val lists = cfg.optLong("itemId", 0).takeIf { it > 0 }?.let { listOfNotNull(dao.item(it)) } ?: dao.checklistsInAlbum(album.id)
                    lists.forEach { l -> val es = dao.entries(l.id); parts += "${l.title}: ${es.count { !it.checked }} left (${es.filter { !it.checked }.take(5).joinToString { it.text }})" }
                }
                com.cue.domain.BlockType.EXPENSES -> {
                    val ex = Spaces.expenses(dao.records(b.id))
                    parts += "${b.title}: ₹${Spaces.money(ex.sumOf { it.amount })} spent" + (Spaces.budget(b)?.let { " of ₹${Spaces.money(it)}" } ?: "")
                }
                com.cue.domain.BlockType.ITINERARY -> Spaces.stops(dao.records(b.id)).take(5).takeIf { it.isNotEmpty() }?.let { st ->
                    parts += "${b.title}: " + st.joinToString("; ") { "${TextKit.dateLabel(it.at, zone)} ${TextKit.clock(it.at, zone)} ${it.text}" } }
                com.cue.domain.BlockType.DOCUMENTS -> dao.itemsInAlbum(album.id).filter { it.type == ItemType.GALLERY && it.kind != ItemKind.PHOTO }.takeIf { it.isNotEmpty() }
                    ?.let { d -> parts += "documents: " + d.take(5).joinToString { "#${it.id} ${it.title}" } }
                else -> {}
            }
        }
        card = CallCard.Albums
        return ToolResult("space_read", true, "${album.title}: " + parts.joinToString(". ").ifBlank { "nothing in it yet" })
    }

    private suspend fun blockAdd(block: String, name: String): ToolResult {
        val album = spaceFor(name) ?: return ToolResult("block_add", false, "which space? There is none yet; use space_create")
        val type = Spaces.typeOf(block) ?: return Ask.needs("block_add", "block", CueTools.questions["block_add.block"]!!)
        spaces.ensureBlocks(album)
        val existing = dao.blocks(album.id)
        if (type != com.cue.domain.BlockType.CHECKLIST && existing.any { it.type == type }) return ToolResult("block_add", true, "${album.title} already has ${Spaces.defaultTitle(type).lowercase(Locale.ROOT)}")
        val title = if (type == com.cue.domain.BlockType.CHECKLIST) block.substringAfter(':', "").trim().ifBlank { "To do" }.replaceFirstChar { it.uppercase() } else Spaces.defaultTitle(type)
        spaces.addBlock(album.id, BlockSpec(type, title))
        card = CallCard.Albums
        return ToolResult("block_add", true, "added $title to ${album.title}")
    }

    private suspend fun expenseBlock(name: String): Pair<com.cue.domain.Album, com.cue.domain.SpaceBlock>? {
        val album = spaceFor(name) ?: return null
        spaces.ensureBlocks(album)
        val b = spaces.block(album.id, com.cue.domain.BlockType.EXPENSES) ?: dao.block(spaces.addBlock(album.id, BlockSpec(com.cue.domain.BlockType.EXPENSES, "Expenses")))!!
        return album to b
    }

    private suspend fun expenseAdd(amountText: String, what: String, paidBy: String, name: String): ToolResult {
        val amount = Spaces.parseAmount(amountText) ?: return Ask.needs("expense_add", "amount", CueTools.questions["expense_add.amount"]!!)
        val (album, b) = expenseBlock(name) ?: return ToolResult("expense_add", false, "there is no space to put it in; ask what it was for, or offer space_create")
        spaces.addExpense(b.id, amount, what, paidBy) ?: return ToolResult("expense_add", false, "that amount doesn't look right")
        val ex = Spaces.expenses(dao.records(b.id))
        val total = ex.sumOf { it.amount }
        val budget = Spaces.budget(b)
        val over = budget?.let { total - it }?.takeIf { it > 0 }
        card = CallCard.Albums
        return ToolResult("expense_add", true, "logged ₹${Spaces.money(amount)} for ${what.ifBlank { "that" }}${if (paidBy.isNotBlank() && paidBy.lowercase(Locale.ROOT) !in setOf("me", "i")) " paid by $paidBy" else ""} in ${album.title}; ₹${Spaces.money(total)} so far" +
            (budget?.let { if (over != null) ". IMPACT: that is ₹${Spaces.money(over)} over the ₹${Spaces.money(it)} budget. Tell the user." else ", ₹${Spaces.money(it - total)} of the budget left" } ?: ""))
    }

    private suspend fun expensesRead(name: String): ToolResult {
        val (album, b) = expenseBlock(name) ?: return ToolResult("expenses_read", false, "there is no space yet")
        val ex = Spaces.expenses(dao.records(b.id))
        card = CallCard.Albums
        if (ex.isEmpty()) return ToolResult("expenses_read", true, "nothing spent yet in ${album.title}")
        val top = ex.groupBy { it.what }.mapValues { e -> e.value.sumOf { it.amount } }.entries.sortedByDescending { it.value }.take(3)
        val settle = Spaces.settle(ex, Spaces.people(b))
        return ToolResult("expenses_read", true, "${album.title}: ₹${Spaces.money(ex.sumOf { it.amount })} spent over ${ex.size} expenses" +
            (Spaces.budget(b)?.let { " (budget ₹${Spaces.money(it)})" } ?: "") + ". Biggest: " + top.joinToString { "${it.key} ₹${Spaces.money(it.value)}" } +
            (if (settle.isNotEmpty()) ". " + settle.joinToString("; ") else ""))
    }

    private suspend fun itineraryAdd(what: String, whenPhrase: String, place: String, name: String): ToolResult {
        if (what.isBlank()) return Ask.needs("itinerary_add", "what", CueTools.questions["itinerary_add.what"]!!)
        val album = spaceFor(name) ?: return ToolResult("itinerary_add", false, "there is no space yet; offer space_create")
        val now = env.clock.now()
        val day = Spaces.parseDay(whenPhrase, now, zone)
        val minutes = Spaces.timeOf(whenPhrase)
        val at = when {
            day != null -> TextKit.at(day, minutes ?: 9 * 60, zone)
            minutes != null -> album.startAt?.let { s -> TextKit.at(TextKit.local(s, zone).toLocalDate(), minutes, zone) } ?: TextKit.parseWhen(whenPhrase, now, zone)
            else -> null
        } ?: return Ask.needs("itinerary_add", "when", CueTools.questions["itinerary_add.when"]!!)
        spaces.ensureBlocks(album)
        val b = spaces.block(album.id, com.cue.domain.BlockType.ITINERARY) ?: dao.block(spaces.addBlock(album.id, BlockSpec(com.cue.domain.BlockType.ITINERARY, "Itinerary")))!!
        spaces.addStop(b.id, at, what, place)
        val clash = Spaces.stops(dao.records(b.id)).filter { it.text != what.trim().replaceFirstChar { c -> c.uppercase() } && abs(it.at - at) < 60 * 60_000L }
        card = CallCard.Albums
        return ToolResult("itinerary_add", true, "added ${what.trim()} on ${TextKit.dateLabel(at, zone)} at ${TextKit.clock(at, zone)} to ${album.title}" +
            (if (clash.isNotEmpty()) ". IMPACT: within an hour of ${clash.joinToString { it.text }}. Tell the user." else ""))
    }

    private fun missing(call: ToolCall): ToolResult? {
        val spec = CueTools.byName[call.name] ?: return null
        for (p in spec.parameters) {
            if (!p.required || !Ask.isPlaceholder(call.args[p.name])) continue
            val alt = CueTools.alternatives["${call.name}.${p.name}"]
            if (alt != null && call.args[alt].orEmpty().isNotBlank()) continue
            // A tool with a fallback for a blank value (the medicine already on a plan) handles it itself.
            if (call.name == "dose_schedule" && p.name != "medicine") continue
            return Ask.needs(call.name, p.name, CueTools.questions["${call.name}.${p.name}"] ?: "What ${p.description.substringBefore(",").substringBefore(";")}?")
        }
        return null
    }

    private suspend fun planFor(kind: String, what: String): Item? = dao.plans().filter { it.kind == kind && PlanSpec.of(it).active }.let { plans ->
        plans.firstOrNull { PlanSpec.of(it).what.equals(what, true) || PlanSpec.of(it).measure.equals(what, true) }
            ?: plans.maxByOrNull { TextKit.similarity(PlanSpec.of(it).what, what) }?.takeIf { TextKit.similarity(PlanSpec.of(it).what, what) >= 0.6 }
    }

    /** "until" for a number of days from today, 0 for ongoing; null when the answer isn't a duration. */
    private fun untilOf(days: String): Long? {
        val d = days.trim().lowercase(Locale.ROOT).removePrefix("for ").replace(Regex("^(a|an|one)\\s+"), "1 ")
        if (d.isBlank()) return null
        if (Regex("ongoing|forever|always|no end|daily|every ?day|until.*(stop|say|tell)|lifelong|regular|continue").containsMatchIn(d)) return 0
        val n = TextKit.number(d.substringBefore(" ")) ?: Regex("(\\d+)").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val unit = when { "week" in d -> 7; "month" in d -> 30; else -> 1 }
        val last = TextKit.local(env.clock.now(), zone).toLocalDate().plusDays((n * unit - 1).toLong().coerceAtLeast(0))
        return TextKit.at(last, 23 * 60 + 59, zone)
    }

    /**
     * A medicine plan: asks for what is missing (which medicine, when, how long, the user's meal time when it
     * hangs off a meal), saves or updates the plan, checks clashes, and hands the model the follow-up questions.
     */
    private suspend fun doseSchedule(a: Map<String, String>): ToolResult {
        val now = env.clock.now()
        val profile = Profile(env)
        val medicine = a["medicine"].orEmpty().trim().takeUnless { Ask.isPlaceholder(it) }.orEmpty().ifBlank {
            dao.itemsOfKind(ItemKind.PRESCRIPTION).firstOrNull()?.let { org.json.JSONObject(it.fields).optString("medicine") }.orEmpty()
        }
        if (medicine.isBlank()) return Ask.needs("dose_schedule", "medicine", CueTools.questions["dose_schedule.medicine"]!!)
        // A plan made earlier on this call with no real name ("Unknown") becomes this medicine, not a second plan.
        val existing = planFor(PlanKind.MEDICINE, medicine)
            ?: dao.plans().firstOrNull { it.kind == PlanKind.MEDICINE && Ask.isPlaceholder(PlanSpec.of(it).what) && env.clock.now() - it.createdAt < 6 * 3_600_000L }
        val old = existing?.let { PlanSpec.of(it) }
        val timesWords = a["times"].orEmpty()
        var anchor = old?.anchor.orEmpty()
        var food = a["food"].orEmpty().lowercase(Locale.ROOT).let { f -> listOf("before", "after", "with").firstOrNull { f.contains(it) } ?: Anchors.food(f) }
        val times: List<Int> = when {
            timesWords.isNotBlank() -> {
                val t = Anchors.times(timesWords) { profile.routine(it) }
                if (t.anchor.isNotBlank()) anchor = t.anchor
                if (food.isBlank()) food = t.food
                // "1 pm after lunch" also teaches Cue when lunch is.
                if (t.anchor in setOf("breakfast", "lunch", "dinner") && t.minutes.size == 1 && t.unknownAnchor == null && profile.routine(t.anchor) == null &&
                    Anchors.minutes(timesWords, t.anchor) != null)
                    profile.remember("Has ${t.anchor} around ${Anchors.clock(t.minutes.first())}", com.cue.domain.FactCategory.ROUTINE, source = "call")
                if (t.unknownAnchor != null) return Ask.needs("dose_schedule", "times", "What time do you usually have ${Anchors.label(t.unknownAnchor)}? I'll set it for then.")
                if (t.minutes.isEmpty()) return Ask.needs("dose_schedule", "times", CueTools.questions["dose_schedule.times"]!!)
                t.minutes
            }
            old != null -> old.times
            TextKit.number(a["per_day"]) != null -> when (TextKit.number(a["per_day"])!!.coerceIn(1, 4)) {
                1 -> listOf(9 * 60); 2 -> listOf(9 * 60, 21 * 60); 3 -> listOf(8 * 60, 14 * 60, 20 * 60); else -> listOf(8 * 60, 12 * 60, 16 * 60, 20 * 60)
            }
            else -> return Ask.needs("dose_schedule", "times", CueTools.questions["dose_schedule.times"]!!)
        }
        val until = untilOf(a["days"].orEmpty()) ?: old?.until ?: return Ask.needs("dose_schedule", "days", CueTools.questions["dose_schedule.days"]!!)
        if (food.isBlank()) food = old?.food.orEmpty()
        val trackTime = a["track_time"]?.let { it.startsWith("y", true) } ?: (old?.trackTime ?: false)
        val purpose = a["purpose"].orEmpty().ifBlank { old?.purpose.orEmpty() }
        val name = medicine.trim().replaceFirstChar { it.uppercase() }
        val spec = PlanSpec(PlanKind.MEDICINE, name, times, until, true, anchor, food, trackTime, purpose, Playbooks.measureFor(name, purpose).orEmpty())
        val albumId = context.albumId?.takeIf { dao.album(it)?.kind == AlbumKind.HEALTH } ?: dao.albums().firstOrNull { it.kind == AlbumKind.HEALTH }?.id
        val planId = if (existing != null) { dao.updateItem(existing.copy(title = name, fields = spec.json(), body = "")); existing.id }
            else dao.insertItem(Item(type = PlanKind.TYPE, kind = PlanKind.MEDICINE, title = name, fields = spec.json(), albumId = albumId, source = "call", createdAt = now))
        // A course with an end gets a check-back on its last day: does the doctor want it continued?
        if (until > 0 && existing == null) {
            val lastDay = TextKit.at(TextKit.local(until, zone).toLocalDate(), 19 * 60, zone)
            if (lastDay > now) scheduleFollowUp(env, "Your $name course ends today. Did the doctor want you to continue it?", lastDay, planId)
        }
        if (existing != null && Ask.isPlaceholder(PlanSpec.of(existing).what)) {
            dao.remindersWithKeyPrefix("plan:${existing.id}:").filter { it.status == ReminderStatus.SCHEDULED }.forEach {
                dao.updateReminder(it.copy(text = "Take $name", meta = org.json.JSONObject(it.meta).put("medicine", name).toString()))
            }
        }
        medicineFact(name, "Takes $name" + (if (purpose.isNotBlank()) " for $purpose" else "") + " at " + times.joinToString(" and ") { Anchors.clock(it) } +
            (if (food.isNotBlank()) ", $food food" else "") + if (until == 0L) ", ongoing" else " until ${TextKit.dateLabel(until, zone)}")
        env.planner.reconcile()
        card = CallCard.Reminders
        val impact = times.map { Impact(env).checkDaily(it, excludePlan = planId) }.firstOrNull { it.isNotBlank() }.orEmpty()
        val measuring = spec.measure.isNotBlank() && planFor(PlanKind.MEASURE, spec.measure) != null
        val follow = if (existing == null) Playbooks.afterMedicine(env, spec, measuring) else ""
        val whenText = times.joinToString(" and ") { Anchors.clock(it) } + (if (food.isNotBlank()) ", $food food" else "")
        val how = if (until == 0L) "every day until you tell me to stop" else "until ${TextKit.dateLabel(until, zone)}"
        val defaulted = if (timesWords.isBlank() && old == null) " (I picked these times; ask if another time suits better)" else ""
        return ToolResult("dose_schedule", true, (if (existing != null) "updated #$planId $name: " else "#$planId tracking $name: ") + "$whenText, $how$defaulted" +
            listOf(impact, follow).filter { it.isNotBlank() }.joinToString(". ", prefix = ". ").takeIf { impact.isNotBlank() || follow.isNotBlank() }.orEmpty())
    }

    /** The profile keeps one line per medicine, so every call knows what the user takes. */
    private suspend fun medicineFact(name: String, text: String) {
        val key = "health.med.${TextKit.normalize(name).replace(" ", "_")}"
        val now = env.clock.now()
        val old = dao.factByKey(key)
        if (old != null) dao.updateFact(old.copy(text = text, confirmed = true, updatedAt = now))
        else dao.insertFact(com.cue.domain.ProfileFact(key = key, category = com.cue.domain.FactCategory.HEALTH, text = text, source = "call", createdAt = now, updatedAt = now))
    }

    private suspend fun medicinesRead(): ToolResult {
        val plans = dao.plans().filter { PlanSpec.of(it).active }
        card = CallCard.Reminders
        if (plans.isEmpty()) return ToolResult("medicines_read", true, "No medicines or readings are being tracked")
        return ToolResult("medicines_read", true, plans.joinToString("; ") { planLine(it) })
    }

    private suspend fun medicineStop(what: String, confirmed: Boolean): ToolResult {
        val kind = MeasureWords.kindOf(what)
        val plan = (if (kind != null) planFor(PlanKind.MEASURE, kind) else null) ?: planFor(PlanKind.MEDICINE, what)
            ?: num(what).takeIf { it > 0 }?.let { dao.item(it)?.takeIf { i -> i.type == PlanKind.TYPE } }
            ?: return ToolResult("medicine_stop", false, "I'm not tracking $what; medicines_read lists what I am")
        val p = PlanSpec.of(plan)
        val label = if (p.kind == PlanKind.MEASURE) "${MeasureWords.name(p.measure)} readings" else p.what
        if (!confirmed) return Ask.confirm("medicine_stop", "Stop the reminders for $label?")
        dao.updateItem(plan.copy(fields = p.copy(active = false).json()))
        env.planner.reconcile()
        card = CallCard.Reminders
        // Impact: readings that were set up because of this medicine keep running unless the user says otherwise.
        var impact = ""
        if (p.kind == PlanKind.MEDICINE) {
            medicineFact(p.what, "Stopped taking ${p.what} on ${TextKit.dateLabel(env.clock.now(), zone)}")
            val m = p.measure.ifBlank { Playbooks.measureFor(p.what, p.purpose).orEmpty() }
            val readings = if (m.isNotBlank()) planFor(PlanKind.MEASURE, m) else null
            val otherMed = dao.plans().any { it.id != plan.id && it.kind == PlanKind.MEDICINE && PlanSpec.of(it).active && PlanSpec.of(it).measure == m }
            if (readings != null && !otherMed) impact = ". IMPACT: the ${MeasureWords.name(m)} readings at ${PlanSpec.of(readings).times.joinToString(" and ") { Anchors.clock(it) }} " +
                "were set up with ${p.what} and are still on. Ask whether to keep checking ${MeasureWords.name(m)} (the doctor may still want it) or stop those too with medicine_stop medicine=${m.lowercase()}"
        }
        return ToolResult("medicine_stop", true, "stopped the reminders for $label$impact")
    }

    private suspend fun doseTaken(done: Boolean): ToolResult {
        val rem = context.reminderId?.let { dao.reminder(it) } ?: return ToolResult("dose_taken", false, "no dose in this call")
        val now = env.clock.now()
        val meta = runCatching { org.json.JSONObject(rem.meta) }.getOrDefault(org.json.JSONObject()).put(if (done) "takenAt" else "missedAt", now)
        dao.updateReminder(rem.copy(status = if (done) ReminderStatus.DONE else ReminderStatus.MISSED, meta = meta.toString()))
        return ToolResult("dose_taken", true, if (done) "marked as taken at ${TextKit.clock(now, zone)}" else "marked as missed")
    }

    private suspend fun measureLog(kindWords: String, value: String, context: String): ToolResult {
        val kind = MeasureWords.kindOf(kindWords) ?: MeasureWords.kindOf(value)
            ?: this.context.reminderId?.let { dao.reminder(it) }?.meta?.let { runCatching { org.json.JSONObject(it).optString("measure") }.getOrNull() }?.takeIf { it.isNotBlank() }
            ?: return Ask.needs("measure_log", "kind", CueTools.questions["measure_log.kind"]!!)
        val (v, v2) = MeasureWords.parse(kind, value) ?: return Ask.needs("measure_log", "value",
            if (kind == MeasureKind.BP) "What were both numbers, like 130 over 85?" else "What was the number?")
        val now = env.clock.now()
        val id = dao.insertMeasurement(Measurement(kind = kind, value = v, value2 = v2, unit = MeasureWords.unit(kind), context = context.trim(), takenAt = now))
        // A reading answers its measuring call.
        this.context.reminderId?.let { dao.reminder(it) }?.takeIf { it.kind == ReminderKind.MEASURE }?.let { dao.updateReminder(it.copy(status = ReminderStatus.DONE)) }
        val recent = dao.measurements(kind, 7)
        val avg = if (recent.size >= 3) " Last ${recent.size} average ${MeasureWords.format(kind, recent.map { it.value }.average().let { kotlin.math.round(it) }, recent.mapNotNull { it.value2 }.takeIf { it.isNotEmpty() }?.average()?.let { kotlin.math.round(it) })}." else ""
        val flag = MeasureWords.flag(kind, v, v2, context)?.let { " $it" }.orEmpty()
        val follow = if (planFor(PlanKind.MEASURE, kind) == null && dao.measurements(kind, 2).size == 1) " FOLLOW-UP: ask whether to remind them to check their ${MeasureWords.name(kind)} at a set time every day." else ""
        card = CallCard.Reminders
        return ToolResult("measure_log", true, "#$id saved ${MeasureWords.name(kind)} ${MeasureWords.format(kind, v, v2)} ${MeasureWords.unit(kind)} at ${TextKit.clock(now, zone)}.$avg$flag$follow")
    }

    private suspend fun measuresRead(kindWords: String): ToolResult {
        val kind = MeasureWords.kindOf(kindWords) ?: return Ask.needs("measures_read", "kind", CueTools.questions["measure_log.kind"]!!)
        val list = dao.measurements(kind, 14)
        if (list.isEmpty()) return ToolResult("measures_read", true, "No ${MeasureWords.name(kind)} readings yet")
        val now = env.clock.now()
        val avg = MeasureWords.format(kind, kotlin.math.round(list.map { it.value }.average()), list.mapNotNull { it.value2 }.takeIf { it.isNotEmpty() }?.average()?.let { kotlin.math.round(it) })
        return ToolResult("measures_read", true, "Average of ${list.size}: $avg. Latest: " + list.take(5).joinToString("; ") {
            "${MeasureWords.format(kind, it.value, it.value2)} ${TextKit.dayLabel(it.takenAt, now, zone)} ${TextKit.clock(it.takenAt, zone)}${if (it.context.isNotBlank()) " (${it.context})" else ""}" })
    }

    private suspend fun measureSchedule(kindWords: String, timesWords: String, days: String): ToolResult {
        val kind = MeasureWords.kindOf(kindWords) ?: return Ask.needs("measure_schedule", "kind", CueTools.questions["measure_log.kind"]!!)
        val t = Anchors.times(timesWords) { Profile(env).routine(it) }
        if (t.unknownAnchor != null) return Ask.needs("measure_schedule", "times", "What time do you usually have ${Anchors.label(t.unknownAnchor)}?")
        if (t.minutes.isEmpty()) return Ask.needs("measure_schedule", "times", CueTools.questions["measure_schedule.times"]!!)
        val until = untilOf(days) ?: return Ask.needs("measure_schedule", "days", CueTools.questions["measure_schedule.days"]!!)
        val now = env.clock.now()
        val existing = planFor(PlanKind.MEASURE, kind)
        val spec = PlanSpec(PlanKind.MEASURE, MeasureWords.name(kind), t.minutes, until, true, t.anchor, t.food, false, "", kind)
        val id = if (existing != null) { dao.updateItem(existing.copy(fields = spec.json())); existing.id }
            else dao.insertItem(Item(type = PlanKind.TYPE, kind = PlanKind.MEASURE, title = "${MeasureWords.name(kind).replaceFirstChar { it.uppercase() }} readings", fields = spec.json(), source = "call", createdAt = now))
        // After a week of readings, offer a summary for the doctor.
        if (existing == null) scheduleFollowUp(env, "You've logged a week of ${MeasureWords.name(kind)} readings. Want a summary to show your doctor?",
            TextKit.at(TextKit.local(now, zone).toLocalDate().plusDays(7), 19 * 60 + 30, zone), id)
        env.planner.reconcile()
        card = CallCard.Reminders
        val impact = t.minutes.map { Impact(env).checkDaily(it, excludePlan = id) }.firstOrNull { it.isNotBlank() }.orEmpty()
        return ToolResult("measure_schedule", true, "#$id I'll ring you to check your ${MeasureWords.name(kind)} at ${t.minutes.joinToString(" and ") { Anchors.clock(it) }}" +
            (if (until == 0L) " every day" else " until ${TextKit.dateLabel(until, zone)}") + ", and ask for the reading" +
            listOf(impact, if (existing == null) Playbooks.afterMeasure(kind) else "").filter { it.isNotBlank() }.joinToString(". ", prefix = ". ").takeIf { impact.isNotBlank() || existing == null }.orEmpty())
    }

    private suspend fun remember(fact: String, topic: String): ToolResult {
        if (fact.isBlank()) return Ask.needs("remember", "fact", "What should I remember?")
        val saved = Profile(env).remember(fact, topic, source = "call", confirmed = true)
        return ToolResult("remember", true, (if (saved.replaced != null) "updated #${saved.fact.id}: ${saved.fact.text} (was: ${saved.replaced.text})" else "#${saved.fact.id} remembered: ${saved.fact.text}") +
            if (saved.impact.isNotBlank()) ". ${saved.impact}" else "")
    }

    private suspend fun profileRead(topic: String): ToolResult {
        val t = topic.trim().lowercase(Locale.ROOT)
        val facts = dao.facts().filter { it.confirmed && (t.isBlank() || it.category == t || it.text.contains(t, true)) }
        if (facts.isEmpty()) return ToolResult("profile_read", true, "I don't know anything about that yet")
        return ToolResult("profile_read", true, facts.take(12).joinToString("; ") { "#${it.id} ${it.text}" })
    }

    private suspend fun profileForget(id: Long): ToolResult {
        val f = dao.fact(id) ?: return ToolResult("profile_forget", false, "I couldn't find #$id; read the profile first")
        dao.deleteFact(id)
        return ToolResult("profile_forget", true, "forgot: ${f.text}")
    }

    private suspend fun followUp(about: String, whenPhrase: String): ToolResult {
        val now = env.clock.now()
        val at = TextKit.parseWhen(whenPhrase, now, zone) ?: return Ask.needs("follow_up", "when", CueTools.questions["follow_up.when"]!!)
        val impact = Impact(env).check(at)
        val id = scheduleFollowUp(env, about, at)
        card = CallCard.Reminders
        return ToolResult("follow_up", true, "#$id I'll call you ${TextKit.dayLabel(at, now, zone)} at ${TextKit.clock(at, zone)} to ask ${about.trim()}" + if (impact.isNotBlank()) ". $impact" else "")
    }

    private suspend fun ruleAdd(whenWords: String, match: String, say: String, repeat: Boolean): ToolResult {
        val w = whenWords.lowercase(Locale.ROOT)
        val trigger = when {
            Regex("leav|exit|go out").containsMatchIn(w) -> RuleTrigger.LEAVE
            Regex("arriv|reach|get to|enter|at ").containsMatchIn("$w ") -> RuleTrigger.ARRIVE
            Regex("messag|text|whatsapp|mail").containsMatchIn(w) -> RuleTrigger.MESSAGE
            "morning" in w -> RuleTrigger.MORNING
            "night" in w || "evening" in w -> RuleTrigger.NIGHT
            else -> return Ask.needs("rule_add", "when", CueTools.questions["rule_add.when"]!!)
        }
        if ((trigger == RuleTrigger.ARRIVE || trigger == RuleTrigger.LEAVE)) {
            if (match.isBlank()) return Ask.needs("rule_add", "match", "Which place?")
            val places = dao.places()
            val place = places.firstOrNull { it.label.equals(match.trim(), true) || it.kind.equals(match.trim(), true) }
                ?: places.maxByOrNull { TextKit.similarity(it.label, match) }?.takeIf { TextKit.similarity(it.label, match) >= 0.5 || match.contains(it.label, true) || match.contains(it.kind, true) }
                ?: return ToolResult("rule_add", false, "I don't have a place called $match saved; the saved places are ${places.joinToString { it.label }}. Ask which one they mean")
            val r = StandingRules(env).add(trigger, place.label, say, repeat)
            return ToolResult("rule_add", true, "saved: ${StandingRules(env).describe(r)}")
        }
        if (trigger == RuleTrigger.MESSAGE && match.isBlank()) return Ask.needs("rule_add", "match", "When who messages you?")
        val r = StandingRules(env).add(trigger, if (trigger == RuleTrigger.MESSAGE) env.person(match).name else "", say, repeat)
        return ToolResult("rule_add", true, "saved: ${StandingRules(env).describe(r)}")
    }

    private suspend fun rulesRead(): ToolResult {
        val rules = dao.activeRules()
        if (rules.isEmpty()) return ToolResult("rules_read", true, "No 'when this happens' rules yet")
        return ToolResult("rules_read", true, rules.take(8).joinToString("; ") { StandingRules(env).describe(it) })
    }

    private suspend fun ruleRemove(id: Long): ToolResult {
        val r = dao.rule(id) ?: return ToolResult("rule_remove", false, "I couldn't find rule #$id; read the rules first")
        dao.updateRule(r.copy(active = false))
        return ToolResult("rule_remove", true, "deleted rule #$id")
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
