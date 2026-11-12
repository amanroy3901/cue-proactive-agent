package com.cue.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.MediaStore
import com.cue.agent.CueEngine
import com.cue.agent.TextKit
import com.cue.domain.AlbumKind
import com.cue.domain.ChecklistEntry
import com.cue.domain.Habit
import com.cue.domain.HabitLog
import com.cue.domain.Item
import com.cue.domain.ItemType
import com.cue.domain.PendingItem
import com.cue.domain.PlaceKind
import com.cue.domain.Reminder
import com.cue.domain.ReminderKind
import com.cue.domain.Surface
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Demo data and the trigger simulator. Seeding goes through the real pipeline (pattern extraction, album assignment,
 * planner), so what you see is what the app would do with these documents. The simulator fires any trigger now, so a
 * demo never depends on real GPS or the real time.
 */
object Demo {
    private fun dateText(epoch: Long, engine: CueEngine) = TextKit.local(epoch, engine.clock.zone).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))

    fun ticketText(engine: CueEngine): String {
        val tomorrow = engine.clock.now() + 86_400_000L
        return "IndiGo\nBOARDING PASS\nPNR: K7XQ2M\nFlight 6E 2134\nBLR Bengaluru to DEL Delhi\nDate: ${dateText(tomorrow, engine)}\nDeparture 18:00\nGate 14   Seat 12A\nBoarding 17:20\nAMAN KUMAR"
    }

    suspend fun seed(engine: CueEngine) {
        val dao = engine.dao
        val now = engine.clock.now()
        val zone = engine.clock.zone
        engine.ensureDefaults()
        if (dao.albums().any { it.kind == AlbumKind.TRIP }) { engine.log("demo data already loaded; wipe first to reload"); return }
        // Travel.
        val ticket = engine.ingestDocument(ticketText(engine), null, "screenshot", "demo-ticket")
        val albumId = ticket?.albumId
        val listId = dao.insertItem(Item(type = ItemType.CHECKLIST, title = "To pack", albumId = albumId, source = "call", createdAt = now))
        listOf("Charger" to true, "Passport" to false, "Earphones" to false).forEachIndexed { i, (t, c) ->
            dao.insertEntry(ChecklistEntry(itemId = listId, text = t, checked = c, position = i))
        }
        dao.insertItem(Item(type = ItemType.NOTE, title = "Hotel is near Connaught Place", body = "Hotel is near Connaught Place", albumId = albumId, source = "call", createdAt = now))
        engine.ingestDocument("The Lalit New Delhi\nHotel booking confirmation\nCheck-in ${dateText(now + 86_400_000L, engine)}\nBarakhamba Avenue, Connaught Place", null, "share", "demo-hotel")
        // Health.
        engine.ingestDocument("Rao Family Clinic\nDr. Suresh Rao\nPatient: Aman\nRx\nTab Amoxicillin 500 mg twice a day for 5 days\nDate: ${dateText(now - 3 * 86_400_000L, engine)}", null, "share", "demo-rx")
        engine.ingestDocument("City Diagnostics\nLab report\nRef: Dr. Suresh Rao\nPatient: Aman\nHaemoglobin 14.1 g/dL\nCBC normal\nDate: ${dateText(now - 3 * 86_400_000L, engine)}", null, "share", "demo-lab")
        // Life admin.
        val bought = now - 340L * 86_400_000L
        engine.ingestDocument("Cool Air Electronics\nTax Invoice\nItem: LG 1.5 Ton Split Air Conditioner\nAmount: Rs 42,990\nDate: ${dateText(bought, engine)}\n1 year comprehensive warranty", null, "share", "demo-ac")
        engine.ingestDocument("BESCOM electricity bill\nAmount Rs 1,240\nDue date: ${dateText(now + 3 * 86_400_000L, engine)}", null, "share", "demo-electricity")
        engine.ingestDocument("Third Wave Coffee\nIndiranagar 12th Main\nCold brew 260\nAvocado toast 340", null, "screenshot", "demo-cafe")
        // People memory.
        val rahul = engine.person("Rahul")
        if (dao.openPending(rahul.id).isEmpty()) dao.insertPending(PendingItem(personId = rahul.id, text = "Send the deck", sourceApp = "com.whatsapp", createdAt = now))
        val priya = engine.person("Priya")
        if (dao.openPending(priya.id).isEmpty()) dao.insertPending(PendingItem(personId = priya.id, text = "You owe ₹500", sourceApp = "call", createdAt = now))
        // Habits: a six-day gym streak.
        val gymId = dao.habitByName("Gym")?.id ?: dao.insertHabit(Habit(name = "Gym", placeKind = PlaceKind.GYM, createdAt = now - 10 * 86_400_000L))
        for (d in 1..6) dao.upsertHabitLog(HabitLog(habitId = gymId, day = TextKit.dayKey(now - d * 86_400_000L, zone), done = true))
        if (dao.habitByName("Study") == null) dao.insertHabit(Habit(name = "Study", createdAt = now))
        // Journal.
        listOf(1 to ("Long day at work. Saved the flight ticket. Skipped the gym." to "tired"),
            6 to ("Lunch with Riya. Paid the electricity bill. A calm Sunday." to "calm")).forEach { (ago, e) ->
            val at = now - ago * 86_400_000L
            dao.insertItem(Item(type = ItemType.JOURNAL, title = "Journal", body = e.first, fields = JSONObject().put("mood", e.second).put("photos", 2).toString(),
                eventStart = at, source = "call", createdAt = at))
        }
        engine.planner.reconcile()
        engine.log("demo data loaded")
    }

    /** Fires a call of any kind right now, through the real reminder path. */
    suspend fun ringNow(engine: CueEngine, kind: String) {
        val now = engine.clock.now()
        val dao = engine.dao
        val existing = when (kind) {
            ReminderKind.LEAVE_BY, ReminderKind.EXPIRY -> dao.scheduledReminders().firstOrNull { it.kind == kind }
            ReminderKind.DOSE -> dao.scheduledReminders().firstOrNull { it.kind == kind }
            ReminderKind.ACCOUNTABILITY -> dao.scheduledReminders().firstOrNull { it.kind == kind }
            else -> null
        }
        val r = existing?.copy(fireAt = now) ?: when (kind) {
            ReminderKind.DOSE -> Reminder(kind = kind, text = "Take Amoxicillin 500 mg", fireAt = now, createdAt = now, meta = JSONObject().put("medicine", "Amoxicillin").toString())
            ReminderKind.ACCOUNTABILITY -> Reminder(kind = kind, text = "Check if you went to the gym", fireAt = now, createdAt = now,
                habitId = dao.habitByName("Gym")?.id)
            ReminderKind.CARE -> Reminder(kind = kind, text = "Did you take your BP medicine", fireAt = now, createdAt = now)
            ReminderKind.EXPIRY -> Reminder(kind = kind, text = "AC warranty ends soon", fireAt = now, createdAt = now)
            ReminderKind.LEAVE_BY -> Reminder(kind = kind, text = "Leave in 20 minutes for your 6 PM flight to Delhi", fireAt = now, createdAt = now)
            else -> Reminder(kind = kind, text = kind.lowercase().replace('_', ' '), fireAt = now, createdAt = now)
        }
        // Always ring a copy, so a test never moves the user's real reminder.
        val id = dao.insertReminder(r.copy(id = 0, key = null, status = com.cue.domain.ReminderStatus.SCHEDULED, attempts = 0, repeatDaily = false))
        engine.onReminderFired(id, attemptKey = ":sim${now}", force = true)
    }

    suspend fun missedDose(engine: CueEngine) {
        val now = engine.clock.now()
        val id = engine.dao.insertReminder(Reminder(kind = ReminderKind.DOSE, text = "Take Amoxicillin 500 mg", fireAt = now, attempts = 1, surface = Surface.RING,
            createdAt = now, meta = JSONObject().put("medicine", "Amoxicillin").toString()))
        engine.onReminderFired(id, attemptKey = ":sim${now}", force = true)
    }

    suspend fun arrive(engine: CueEngine, kind: String) {
        val place = engine.dao.places().firstOrNull { it.kind == kind } ?: return
        engine.onGeofence(place.id)
    }

    /** Draws a boarding pass and saves it as a real screenshot, so the real watcher and OCR pick it up. */
    fun dropTicketScreenshot(context: Context, engine: CueEngine): Boolean {
        val lines = ticketText(engine).replace("IndiGo\n", "").lines()
        val bmp = Bitmap.createBitmap(1080, 1500, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val head = Paint().apply { color = Color.rgb(20, 40, 120); textSize = 72f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val p = Paint().apply { color = Color.BLACK; textSize = 52f; isAntiAlias = true }
        c.drawRect(0f, 0f, 1080f, 220f, Paint().apply { color = Color.rgb(235, 238, 255) })
        c.drawText("IndiGo", 70f, 150f, head)
        var y = 330f
        for (l in lines) { c.drawText(l, 70f, y, p); y += 105f }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_${System.currentTimeMillis()}_IndiGo.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return true
    }

    suspend fun reset(context: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        com.cue.memory.CueDatabase.get(context).clearAllTables()
        java.io.File(context.filesDir, "media").deleteRecursively()
    }

    suspend fun tripAlbumId(engine: CueEngine): Long? = engine.dao.albums().firstOrNull { it.kind == AlbumKind.TRIP }?.id
}
