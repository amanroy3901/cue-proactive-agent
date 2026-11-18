package com.cue.agent

import com.cue.brain.ToolCall
import com.cue.domain.Album
import com.cue.domain.Clock
import com.cue.domain.CueDao
import com.cue.domain.Item
import com.cue.domain.MeasureKind
import com.cue.domain.Measurement
import com.cue.domain.Person
import com.cue.domain.ProfileFact
import com.cue.domain.Reminder
import com.cue.domain.ReminderKind
import com.cue.domain.Settings
import com.cue.domain.TriggerScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import java.time.ZoneId

class WorkflowTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2026, 11, 13, 10, 0)

    // ------------------------------------------------------------ pure parts

    @Test fun timesOfDay() = runTest {
        val lunchKnown = Anchors.times("1 pm after lunch") { null }
        assertEquals(listOf(13 * 60), lunchKnown.minutes); assertEquals("lunch", lunchKnown.anchor); assertEquals("after", lunchKnown.food); assertNull(lunchKnown.unknownAnchor)
        assertEquals(listOf(9 * 60, 21 * 60), Anchors.times("9 am and 9 pm") { null }.minutes)
        val unknown = Anchors.times("after lunch") { null }
        assertEquals("lunch", unknown.unknownAnchor)
        assertEquals(listOf(14 * 60), Anchors.times("after lunch") { if (it == "lunch") 14 * 60 else null }.minutes)
        assertEquals(listOf(8 * 60 + 30, 21 * 60), Anchors.times("morning and night") { mapOf("breakfast" to 510, "dinner" to 1260)[it] }.minutes)
        assertEquals(15 * 60, Anchors.minutes("3")); assertEquals(9 * 60, Anchors.minutes("9")); assertEquals(22 * 60, Anchors.minutes("10 at night"))
    }

    @Test fun readings() {
        assertEquals(130.0 to 85.0, MeasureWords.parse(MeasureKind.BP, "130 over 85"))
        assertEquals(MeasureKind.SUGAR, MeasureWords.kindOf("my sugar"))
        assertTrue(MeasureWords.flag(MeasureKind.BP, 185.0, 95.0, "")!!.startsWith("URGENT"))
        assertNull(MeasureWords.flag(MeasureKind.BP, 120.0, 80.0, ""))
        assertTrue(MeasureWords.flag(MeasureKind.SUGAR, 140.0, null, "fasting") != null)
    }

    @Test fun learnerPicksUpFacts() {
        val got = Learner.candidates(listOf("I usually have lunch at 1 pm and I'm allergic to penicillin", "don't call me before 8 am please"))
        assertTrue(got.any { it.first == "routine" && it.second.contains("lunch at 1 pm") })
        assertTrue(got.any { it.first == "health" && it.second.contains("penicillin") })
        assertTrue(got.any { it.first == "preference" && it.second.contains("before 8 am") })
    }

    @Test fun promiseCheck() {
        val broken = PromiseCheck.broken(listOf("Sure, I'll check with you tomorrow at 7 pm about the visit."), listOf("note_save: x"))
        assertEquals(1, broken.size)
        assertTrue(PromiseCheck.broken(listOf("I'll remind you at 6."), listOf("reminder_set: #3 ...")).isEmpty())
        assertTrue(PromiseCheck.broken(listOf("If you miss one, I'll call you."), emptyList()).isEmpty())
    }

    // ------------------------------------------------------------ the executor against an in-memory store

    private class Store {
        val facts = mutableListOf<ProfileFact>()
        val items = mutableListOf<Item>()
        val reminders = mutableListOf<Reminder>()
        val readings = mutableListOf<Measurement>()
        val habits = mutableListOf<com.cue.domain.Habit>()
        val logs = mutableListOf<com.cue.domain.HabitLog>()
        val albums = mutableListOf<Album>()
        val blocks = mutableListOf<com.cue.domain.SpaceBlock>()
        val records = mutableListOf<com.cue.domain.BlockRecord>()
        val entries = mutableListOf<com.cue.domain.ChecklistEntry>()
        var nextId = 1L
    }

    private val lists = setOf("itemsInAlbum", "allItems", "itemsOfType", "itemsOfKind", "expiring", "searchItems", "entries", "openEntries", "albums", "places",
        "derivedReminders", "scheduledReminders", "remindersForAlbum", "remindersOfKind", "unfiledCalls", "openPending", "allOpenPending", "habits", "habitLogs",
        "remindersWithKeyPrefix", "plans", "facts", "measurements", "activeRules")

    private fun dao(s: Store): CueDao = Proxy.newProxyInstance(CueDao::class.java.classLoader, arrayOf(CueDao::class.java)) { _, m, args ->
        val a = args ?: emptyArray()
        when (m.name) {
            "insertFact" -> { val id = s.nextId++; s.facts += (a[0] as ProfileFact).copy(id = id); id }
            "updateFact" -> { val f = a[0] as ProfileFact; s.facts.replaceAll { if (it.id == f.id) f else it }; Unit }
            "facts" -> s.facts.toList()
            "factByKey" -> s.facts.firstOrNull { it.key == a[0] }
            "insertItem" -> { val id = s.nextId++; s.items += (a[0] as Item).copy(id = id); id }
            "updateItem" -> { val i = a[0] as Item; s.items.replaceAll { if (it.id == i.id) i else it }; Unit }
            "item" -> s.items.firstOrNull { it.id == a[0] }
            "plans" -> s.items.filter { it.type == "PLAN" }
            "insertReminder" -> { val id = s.nextId++; s.reminders += (a[0] as Reminder).copy(id = id); id }
            "updateReminder" -> { val r = a[0] as Reminder; s.reminders.replaceAll { if (it.id == r.id) r else it }; Unit }
            "reminderByKey" -> s.reminders.firstOrNull { it.key == a[0] }
            "remindersWithKeyPrefix" -> s.reminders.filter { it.key?.startsWith(a[0] as String) == true }
            "scheduledReminders" -> s.reminders.filter { it.status == "SCHEDULED" }.sortedBy { it.fireAt }
            "remindersOfKind" -> s.reminders.filter { it.kind == a[0] }
            "insertMeasurement" -> { val id = s.nextId++; s.readings += (a[0] as Measurement).copy(id = id); id }
            "habits" -> s.habits.toList()
            "habit" -> s.habits.firstOrNull { it.id == a[0] }
            "insertHabit" -> { val id = s.nextId++; s.habits += (a[0] as com.cue.domain.Habit).copy(id = id); id }
            "updateHabit" -> { val h = a[0] as com.cue.domain.Habit; s.habits.replaceAll { if (it.id == h.id) h else it }; Unit }
            "habitByName" -> s.habits.firstOrNull { it.name.equals(a[0] as String, true) }
            "habitLogs" -> s.logs.filter { it.habitId == a[0] }
            "upsertHabitLog" -> { val l = a[0] as com.cue.domain.HabitLog; s.logs.removeAll { it.habitId == l.habitId && it.day == l.day }; s.logs += l; 1L }
            "derivedReminders" -> s.reminders.filter { it.key != null }
            "measurements" -> s.readings.filter { it.kind == a[0] }.sortedByDescending { it.takenAt }.take(a[1] as Int)
            "insertAlbum" -> { val id = s.nextId++; s.albums += (a[0] as Album).copy(id = id); id }
            "album" -> s.albums.firstOrNull { it.id == a[0] }
            "albums" -> s.albums.toList()
            "insertBlock" -> { val id = s.nextId++; s.blocks += (a[0] as com.cue.domain.SpaceBlock).copy(id = id); id }
            "updateBlock" -> { val b = a[0] as com.cue.domain.SpaceBlock; s.blocks.replaceAll { if (it.id == b.id) b else it }; Unit }
            "block" -> s.blocks.firstOrNull { it.id == a[0] }
            "blocks" -> s.blocks.filter { it.albumId == a[0] }.sortedBy { it.position }
            "deleteBlock" -> { s.blocks.removeAll { it.id == a[0] }; Unit }
            "insertRecord" -> { val id = s.nextId++; s.records += (a[0] as com.cue.domain.BlockRecord).copy(id = id); id }
            "records" -> s.records.filter { it.blockId == a[0] }.sortedBy { it.at }
            "deleteRecords" -> { s.records.removeAll { it.blockId == a[0] }; Unit }
            "insertEntry" -> { val id = s.nextId++; s.entries += (a[0] as com.cue.domain.ChecklistEntry).copy(id = id); id }
            "entries" -> s.entries.filter { it.itemId == a[0] }
            "itemsInAlbum" -> s.items.filter { it.albumId == a[0] }
            "checklistsInAlbum" -> s.items.filter { it.albumId == a[0] && it.type == "CHECKLIST" }
            "deleteItem" -> { s.items.removeAll { it.id == a[0] }; Unit }
            "toString" -> "FakeDao"
            "hashCode" -> 0
            "equals" -> false
            else -> when {
                m.name in lists -> emptyList<Any>()
                m.name.startsWith("insert") || m.name.startsWith("upsert") -> s.nextId++
                m.name.startsWith("update") || m.name.startsWith("delete") -> Unit
                else -> null
            }
        }
    } as CueDao

    private fun env(s: Store, time: Long = now): AgentEnv {
        val d = dao(s)
        val clock = object : Clock { override fun now() = time; override val zone = this@WorkflowTest.zone }
        val settings = Proxy.newProxyInstance(Settings::class.java.classLoader, arrayOf(Settings::class.java)) { _, m, _ ->
            when (m.returnType) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 600; String::class.java -> ""; else -> null }
        } as Settings
        val sched = object : TriggerScheduler {
            override fun schedule(reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
            override fun registerGeofence(place: com.cue.domain.Place) {}
            override fun removeGeofence(placeId: Long) {}
        }
        val planner = Planner(d, clock, settings, sched)
        return object : AgentEnv {
            override val dao = d
            override val settings = settings
            override val clock = clock
            override val scheduler = sched
            override val planner = planner
            override suspend fun activeAlbum(): Album? = null
            override suspend fun streak(habitId: Long) = 0
            override suspend fun person(name: String) = Person(1, name, name.lowercase())
            override suspend fun search(query: String) = emptyList<Item>()
            override fun log(line: String) {}
        }
    }

    private fun exec(s: Store) = CueToolExecutor(env(s), CallContext(1, "TALK", null, null))

    @Test fun medicineAsksInsteadOfGuessing() = runTest {
        val s = Store()
        val x = exec(s)
        val noMed = x.execute(ToolCall("dose_schedule", mapOf("times" to "1 pm", "days" to "ongoing")))
        assertFalse(noMed.ok); assertTrue(noMed.message, noMed.message.startsWith("NEEDS medicine"))
        val noTime = x.execute(ToolCall("dose_schedule", mapOf("medicine" to "Metformin", "times" to "after lunch", "days" to "ongoing")))
        assertTrue(noTime.message, noTime.message.contains("usually have lunch"))
        val noDays = x.execute(ToolCall("dose_schedule", mapOf("medicine" to "Metformin", "times" to "1 pm after lunch")))
        assertTrue(noDays.message, noDays.message.startsWith("NEEDS days"))
        assertTrue(s.items.isEmpty())
    }

    @Test fun medicinePlanLearnsLunchFollowsUpAndMovesWithLunch() = runTest {
        val s = Store()
        val x = exec(s)
        val r = x.execute(ToolCall("dose_schedule", mapOf("medicine" to "Metformin", "times" to "1 pm after lunch", "days" to "ongoing")))
        assertTrue(r.message, r.ok)
        assertTrue(r.message, r.message.contains("FOLLOW-UP") && r.message.contains("blood sugar"))
        // It learned when lunch is, and made the next days' 1 PM rings.
        assertTrue(s.facts.any { it.key == "routine.lunch" })
        val doses = s.reminders.filter { it.kind == ReminderKind.DOSE }
        assertEquals(3, doses.size)
        assertTrue(doses.all { TextKit.local(it.fireAt, zone).hour == 13 })
        // Lunch moves to 2: the plan moves with it, the 1 PM rings are cancelled and 2 PM ones made.
        val moved = x.execute(ToolCall("remember", mapOf("fact" to "I have lunch at 2 pm now", "topic" to "routine")))
        assertTrue(moved.message, moved.message.contains("IMPACT") && moved.message.contains("2 PM"))
        val live = s.reminders.filter { it.kind == ReminderKind.DOSE && it.status == "SCHEDULED" }
        assertTrue(live.isNotEmpty() && live.all { TextKit.local(it.fireAt, zone).hour == 14 })
        assertEquals(1, s.facts.count { it.key == "routine.lunch" })
    }

    @Test fun readingsAndFlags() = runTest {
        val s = Store()
        val r = exec(s).execute(ToolCall("measure_log", mapOf("kind" to "bp", "value" to "150/95")))
        assertTrue(r.ok); assertTrue(r.message, r.message.contains("150/95") && r.message.contains("above"))
        assertEquals(1, s.readings.size)
    }

    @Test fun genericMissingSlot() = runTest {
        val r = exec(Store()).execute(ToolCall("reminder_set", mapOf("text" to "call mom")))
        assertTrue(r.message, r.message.startsWith("NEEDS when") && Ask.spoken(r.message) == "When should I ring you?")
    }

    @Test fun clashWithAnotherCall() = runTest {
        val s = Store()
        s.reminders += Reminder(id = 99, kind = ReminderKind.CHECKIN_NIGHT, text = "Night", fireAt = at(2026, 11, 13, 22, 0), createdAt = now)
        val r = exec(s).execute(ToolCall("reminder_set", mapOf("text" to "take the trash out", "when" to "10:05 pm")))
        assertTrue(r.message, r.ok && r.message.contains("close to your night call"))
    }

    @Test fun placeholderMedicineIsAskedNotSaved() = runTest {
        val s = Store()
        val r = exec(s).execute(ToolCall("dose_schedule", mapOf("medicine" to "Unknown", "times" to "11 pm", "days" to "ongoing")))
        assertTrue(r.message, r.message.startsWith("NEEDS medicine"))
        assertTrue(s.items.isEmpty())
    }

    @Test fun stoppingBpMedicineAsksAboutReadings() = runTest {
        val s = Store()
        val x = exec(s)
        assertTrue(x.execute(ToolCall("dose_schedule", mapOf("medicine" to "Telma", "times" to "11 pm", "days" to "ongoing", "purpose" to "BP"))).ok)
        assertTrue(x.execute(ToolCall("measure_schedule", mapOf("kind" to "bp", "times" to "11:10 pm", "days" to "ongoing"))).ok)
        assertTrue(s.facts.any { it.key == "health.med.telma" && it.text.contains("Telma") })
        val stop = x.execute(ToolCall("medicine_stop", mapOf("medicine" to "Telma", "confirm" to "yes")))
        assertTrue(stop.message, stop.ok && stop.message.contains("IMPACT") && stop.message.contains("blood pressure"))
        assertTrue(s.facts.first { it.key == "health.med.telma" }.text.startsWith("Stopped"))
    }

    @Test fun dailyRingsShowOnce() = runTest {
        val s = Store()
        exec(s).execute(ToolCall("dose_schedule", mapOf("medicine" to "Telma", "times" to "11 pm", "days" to "ongoing")))
        assertEquals(3, s.reminders.size)
        assertEquals(1, collapseDaily(s.reminders).size)
    }

    @Test fun pausedHabitKeepsStreakAndStopsCalls() = runTest {
        val s = Store()
        val x = exec(s)
        x.execute(ToolCall("habit_add", mapOf("habit" to "Reading", "check_at" to "7 pm")))
        assertTrue(s.reminders.any { it.key?.startsWith("habit:") == true && it.status == "SCHEDULED" })
        val h = s.habits.first()
        val today = TextKit.local(now, zone).toLocalDate()
        s.logs += com.cue.domain.HabitLog(habitId = h.id, day = today.minusDays(3).toString(), done = true)
        s.logs += com.cue.domain.HabitLog(habitId = h.id, day = today.minusDays(2).toString(), done = true)
        s.logs += com.cue.domain.HabitLog(habitId = h.id, day = today.minusDays(1).toString(), done = false, paused = true)
        assertEquals(2, HabitDays.streak(h, s.logs, today, zone))
        val p = x.execute(ToolCall("habit_pause", mapOf("habit" to "reading", "until" to "a week")))
        assertTrue(p.message, p.ok && p.message.contains("paused Reading until"))
        assertTrue(s.reminders.none { it.key?.startsWith("habit:") == true && it.status == "SCHEDULED" })
        assertTrue(x.execute(ToolCall("habit_resume", mapOf("habit" to "reading"))).ok)
        assertTrue(s.reminders.any { it.key?.startsWith("habit:") == true && it.status == "SCHEDULED" })
    }

    // ------------------------------------------------------------ spaces

    @Test fun spaceFromTemplate() {
        val sp = Spaces(env(Store())).fromTemplate("Goa trip next Friday with Riya for 3 days")
        assertEquals(com.cue.domain.AlbumKind.TRIP, sp.kind)
        assertEquals("Goa trip", sp.title)
        assertEquals(java.time.LocalDate.of(2026, 11, 20), sp.start)
        assertEquals(java.time.LocalDate.of(2026, 11, 22), sp.end)
        assertEquals(listOf("Riya"), sp.blocks.first { it.type == com.cue.domain.BlockType.EXPENSES }.people)
        assertEquals(20_000L, Spaces(env(Store())).fromTemplate("Goa trip, budget 20000").blocks.first { it.type == com.cue.domain.BlockType.EXPENSES }.budget)
        assertEquals(15_000L, Spaces.budgetIn("15k budget for the party"))
        assertEquals(20_000L, Spaces(env(Store())).fromTemplate("Goa trip next Friday with Riya for 3 days, budget 20000").blocks.first { it.type == com.cue.domain.BlockType.EXPENSES }.budget)
        assertEquals(com.cue.domain.AlbumKind.HEALTH, Spaces.kindOf("Dad's knee surgery on 2 Dec"))
        assertEquals(com.cue.domain.AlbumKind.EVENT, Spaces.kindOf("Riya's wedding"))
        assertEquals(com.cue.domain.AlbumKind.MOVE, Spaces.kindOf("moving to the new flat"))
        val extra = Spaces(env(Store())).fromTemplate("doctor visit", add = "expenses, checklist: Bring along")
        assertTrue(extra.blocks.any { it.type == com.cue.domain.BlockType.EXPENSES })
        assertTrue(extra.blocks.any { it.title == "Bring along" })
    }

    @Test fun modelPlanIsCleaned() {
        val raw = """Here you go:
            ```json
            {"kind":"trip","title":"Manali road trip with a really long name that goes on","start":"2026-12-05","end":"2026-12-01",
             "blocks":[{"type":"countdown"},{"type":"weather"},{"type":"expenses","split_with":["Sam","me"],"budget":"25k"},
                       {"type":"expenses"},{"type":"list","title":"Warm clothes","seed":["Jacket","Gloves","Jacket"]},{"type":"photos"}]}
            ```"""
        val sp = SpaceSpec.validate(raw)!!
        assertEquals(com.cue.domain.AlbumKind.TRIP, sp.kind)
        assertTrue(sp.title.length <= 40)
        assertNull(sp.end)   // before the start: dropped
        assertEquals(listOf("countdown", "expenses", "checklist", "gallery"), sp.blocks.map { it.type })
        val ex = sp.blocks[1]
        assertEquals(listOf("Sam"), ex.people); assertEquals(25_000L, ex.budget)
        assertEquals(listOf("Jacket", "Gloves"), sp.blocks[2].seed)
        assertNull(SpaceSpec.validate("no json here"))
        assertNull(SpaceSpec.validate("{\"blocks\":[{\"type\":\"rocket\"}]}"))
    }

    @Test fun expensesParseAndSettle() {
        assertEquals(Triple(1200L, "Dinner", "Riya"), Spaces.parseExpense("1200 dinner, Riya paid"))
        assertEquals(Triple(450L, "Cab", ""), Spaces.parseExpense("₹450 cab"))
        assertEquals(1500L, Spaces.parseExpense("paid 1.5k for the hotel deposit")!!.first)
        assertEquals("1,20,000", Spaces.money(120000))
        val ex = listOf(Spaces.Expense(1, 3000, "Hotel", "Me", 0), Spaces.Expense(2, 600, "Dinner", "Riya", 0))
        assertEquals(listOf("Riya owes you ₹1,200"), Spaces.settle(ex, listOf("Riya")))
        val stop = Spaces.parseStop("Sat 2 pm check in at Taj", now, zone, null)!!
        assertEquals("Check in at Taj", stop.second)
        val falls = Spaces.parseStop("21 Nov 10:30 Dudhsagar falls", now, zone, null)!!
        assertEquals("Dudhsagar falls", falls.second)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 21, 10, 30), TextKit.local(falls.first, zone).toLocalDateTime())
        val lunch = Spaces.parseStop("1 pm lunch at Britto's", now, zone, java.time.LocalDate.of(2026, 11, 20))!!
        assertEquals("Lunch at Britto's", lunch.second)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 20, 13, 0), TextKit.local(lunch.first, zone).toLocalDateTime())
        assertEquals(java.time.LocalDateTime.of(2026, 11, 14, 14, 0), TextKit.local(stop.first, zone).toLocalDateTime())
    }

    @Test fun spaceByVoiceAsksThenBuildsAndTracksMoney() = runTest {
        val s = Store()
        val x = exec(s)
        val ask = x.execute(ToolCall("space_create", mapOf("goal" to "Goa trip next Friday with Riya", "add" to "")))
        assertFalse(ask.ok); assertTrue(ask.message, ask.message.startsWith("CONFIRM") && ask.message.contains("split with Riya"))
        assertTrue(s.albums.isEmpty())
        val made = x.execute(ToolCall("space_create", mapOf("goal" to "Goa trip next Friday with Riya", "confirm" to "yes")))
        assertTrue(made.message, made.ok)
        val album = s.albums.single()
        assertEquals("Goa trip", album.title)
        assertTrue(s.blocks.any { it.type == "itinerary" } && s.blocks.any { it.type == "expenses" })
        // The packing list block owns a real list with its first entries.
        assertTrue(s.entries.any { it.text == "Phone charger" })
        val ex = s.blocks.first { it.type == "expenses" }
        s.blocks.replaceAll { if (it.id == ex.id) it.copy(config = org.json.JSONObject(it.config).put("budget", 2000).toString()) else it }
        val spent = x.execute(ToolCall("expense_add", mapOf("amount" to "1,500", "what" to "hotel deposit")))
        assertTrue(spent.message, spent.ok && spent.message.contains("₹500 of the budget left"))
        val over = x.execute(ToolCall("expense_add", mapOf("amount" to "900", "what" to "dinner", "paid_by" to "Riya")))
        assertTrue(over.message, over.message.contains("IMPACT") && over.message.contains("over"))
        val read = x.execute(ToolCall("expenses_read", emptyMap()))
        assertTrue(read.message, read.message.contains("₹2,400") && read.message.contains("Riya owes you ₹300"))
        val stop = x.execute(ToolCall("itinerary_add", mapOf("what" to "Check in at Taj", "when" to "20 Nov 2 pm")))
        assertTrue(stop.message, stop.ok && stop.message.contains("Nov 20") && stop.message.contains("2 PM"))
        assertTrue(x.execute(ToolCall("expense_add", mapOf("what" to "snacks"))).message.startsWith("NEEDS amount"))
    }
}
