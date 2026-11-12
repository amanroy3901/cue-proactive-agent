package com.cue.agent

import com.cue.domain.Album
import com.cue.domain.AlbumKind
import com.cue.domain.CueDao
import com.cue.domain.Clock
import com.cue.domain.Item
import com.cue.domain.ItemKind
import com.cue.domain.Place
import com.cue.domain.PlaceKind
import com.cue.domain.Reminder
import com.cue.domain.ReminderKind
import com.cue.domain.ReminderStatus
import com.cue.domain.Settings
import com.cue.domain.Surface
import com.cue.domain.TriggerScheduler
import org.json.JSONObject

/**
 * Works out every alarm and geofence that should exist from memory, then makes the system match. Because triggers
 * are always recomputed, "my flight moved to 9" is one field update followed by reconcile().
 */
class Planner(
    private val dao: CueDao,
    private val clock: Clock,
    private val settings: Settings,
    private val scheduler: TriggerScheduler,
) {
    data class Desired(val key: String, val kind: String, val text: String, val fireAt: Long, val surface: String,
                       val albumId: Long? = null, val itemId: Long? = null, val placeId: Long? = null, val meta: String = "{}")

    /** Lead time from departure to leaving home, and how early the leave-by call rings. */
    var leaveBeforeDepartureMin = 180
    var leaveCallLeadMin = 20

    suspend fun desired(): List<Desired> {
        val now = clock.now()
        val zone = clock.zone
        val out = mutableListOf<Desired>()
        if (settings.callsEnabled) {
            val careMode = settings.careMode
            val m = TextKit.nextDaily(settings.morningMinutes, now, zone)
            val n = TextKit.nextDaily(settings.nightMinutes, now, zone)
            out += Desired("checkin:morning", ReminderKind.CHECKIN_MORNING, "Morning check-in", m, Surface.RING)
            if (!careMode) out += Desired("checkin:night", ReminderKind.CHECKIN_NIGHT, "Night check-in", n, Surface.RING)
            out += Desired("preload:morning", ReminderKind.PRELOAD, "Prepare morning call", m - 90_000, Surface.NOTIFY)
            if (!careMode) out += Desired("preload:night", ReminderKind.PRELOAD, "Prepare night call", n - 90_000, Surface.NOTIFY)
        }
        if (settings.practiceEnabled) {
            out += Desired("practice:daily", ReminderKind.PRACTICE, "English practice", TextKit.nextDaily(settings.practiceMinutes, now, zone), Surface.RING)
        }
        for (ticket in dao.itemsOfKind(ItemKind.TICKET)) {
            val dep = ticket.eventStart ?: continue
            if (dep < now) continue
            val f = runCatching { JSONObject(ticket.fields) }.getOrDefault(JSONObject())
            val leaveAt = dep - leaveBeforeDepartureMin * 60_000L
            val ringAt = leaveAt - leaveCallLeadMin * 60_000L
            val toCity = Airports.code(f.optString("to"))?.city ?: "your trip"
            if (ringAt > now) {
                out += Desired("leave:${ticket.id}", ReminderKind.LEAVE_BY,
                    "Leave in $leaveCallLeadMin minutes for your ${TextKit.clock(dep, zone)} flight to $toCity", ringAt, Surface.RING,
                    albumId = ticket.albumId, itemId = ticket.id)
            }
            val airport = f.optString("from").takeIf { it.isNotBlank() }?.let { ensureAirport(it) }
            val backup = dep - 120 * 60_000L
            if (backup > now) {
                out += Desired("airport_backup:${ticket.id}", ReminderKind.AIRPORT_BACKUP, "At the airport", backup, Surface.BUBBLE,
                    albumId = ticket.albumId, itemId = ticket.id, placeId = airport?.id)
            }
        }
        for (item in dao.expiring()) {
            val exp = item.expiresAt ?: continue
            if (exp < now) continue
            val warnAt = TextKit.at(TextKit.local(exp - 7 * 86_400_000L, zone).toLocalDate(), 10 * 60, zone)
            val at = if (warnAt > now) warnAt else if (exp - now > 3_600_000) now + 120_000 else continue
            val what = when (item.kind) {
                ItemKind.BILL -> "${item.title.removeSuffix(" bill")} warranty"
                ItemKind.INSURANCE -> item.title
                ItemKind.VEHICLE -> item.title
                else -> item.title
            }
            out += Desired("expiry:${item.id}", ReminderKind.EXPIRY, "$what ends on ${TextKit.dateLabel(exp, zone)}", at, Surface.RING,
                albumId = item.albumId, itemId = item.id)
        }
        for (bill in dao.itemsOfKind(ItemKind.BILL)) {
            val due = bill.eventStart ?: continue
            if (due < now) continue
            val home = dao.placeOfKind(PlaceKind.HOME)
            out += Desired("bill_due:${bill.id}", ReminderKind.GENERIC, "${bill.title} is due ${TextKit.dateLabel(due, zone)}",
                TextKit.at(TextKit.local(due - 2 * 86_400_000L, zone).toLocalDate(), 19 * 60, zone).coerceAtLeast(now + 120_000),
                Surface.NOTIFY, albumId = bill.albumId, itemId = bill.id, placeId = home?.id)
        }
        return out
    }

    private suspend fun ensureAirport(code: String): Place? {
        dao.placeByCode(code)?.let { return it }
        val a = Airports.code(code) ?: return null
        val id = dao.upsertPlace(Place(label = "${a.city} airport", kind = PlaceKind.AIRPORT, lat = a.lat, lng = a.lng, radiusM = 1500f, code = a.code))
        return dao.place(id)
    }

    /** Makes registered reminders and geofences match what memory implies. Returns a short summary. */
    suspend fun reconcile(): String {
        val now = clock.now()
        val want = desired()
        val wantKeys = want.map { it.key }.toSet()
        var added = 0
        var moved = 0
        var removed = 0
        for (d in want) {
            val existing = dao.reminderByKey(d.key)
            if (existing == null) {
                val r = Reminder(kind = d.kind, text = d.text, fireAt = d.fireAt, surface = d.surface, albumId = d.albumId, itemId = d.itemId,
                    placeId = d.placeId, key = d.key, meta = d.meta, createdAt = now)
                val id = dao.insertReminder(r)
                scheduler.schedule(r.copy(id = id))
                added++
            } else if (existing.fireAt != d.fireAt || existing.text != d.text || existing.status != ReminderStatus.SCHEDULED) {
                val r = existing.copy(fireAt = d.fireAt, text = d.text, status = ReminderStatus.SCHEDULED, attempts = 0, placeId = d.placeId)
                dao.updateReminder(r)
                scheduler.schedule(r)
                if (existing.fireAt != d.fireAt) moved++
            } else {
                scheduler.schedule(existing)
            }
        }
        for (r in dao.derivedReminders()) {
            if (r.key !in wantKeys && r.status == ReminderStatus.SCHEDULED) {
                dao.updateReminder(r.copy(status = ReminderStatus.CANCELLED))
                scheduler.cancel(r.id)
                removed++
            }
        }
        // User-made reminders (doses, accountability) are re-registered after reboot or update.
        dao.scheduledReminders().filter { it.key == null && it.fireAt > now }.forEach { scheduler.schedule(it) }
        dao.places().forEach { scheduler.registerGeofence(it) }
        return buildString {
            append("updated $moved reminder${if (moved == 1) "" else "s"}")
            if (added > 0) append(", added $added")
            if (removed > 0) append(", removed $removed")
        }
    }
}

/** Puts a newly read document into the right album, creating one for anchor documents. */
class AlbumAssigner(private val dao: CueDao, private val clock: Clock) {
    suspend fun assign(item: Item, ex: Extracted): Long? {
        val now = clock.now()
        val zone = clock.zone
        val albums = dao.albums()
        val f = ex.fields
        when (ex.kind) {
            ItemKind.TICKET, ItemKind.HOTEL, ItemKind.ID_DOC -> {
                val city = f["city"] ?: f["to"]?.let { Airports.code(it)?.city }
                val start = ex.eventStart ?: item.eventStart
                val match = albums.filter { it.kind == AlbumKind.TRIP }.firstOrNull { a ->
                    (start != null && a.startAt != null && kotlin.math.abs(a.startAt!! - start) < 4 * 86_400_000L) ||
                        (city != null && a.title.contains(city, ignoreCase = true)) ||
                        (ex.kind != ItemKind.TICKET && (a.endAt ?: a.startAt ?: 0) > now)
                }
                if (match != null) return match.id
                if (ex.kind != ItemKind.TICKET) return null
                val title = "${city ?: "New"} trip" + (start?.let { ", " + TextKit.dateLabel(it, zone) } ?: "")
                return dao.insertAlbum(Album(title = title, kind = AlbumKind.TRIP, startAt = start, endAt = start?.plus(3 * 86_400_000L), createdAt = now))
            }
            ItemKind.PRESCRIPTION, ItemKind.LAB_REPORT -> {
                val doctor = f["doctor"]
                val person = ex.personLabel
                val match = albums.filter { it.kind == AlbumKind.HEALTH }.firstOrNull { a ->
                    (doctor != null && a.title.contains(doctor, ignoreCase = true)) || (person != null && a.personLabel.equals(person, ignoreCase = true))
                } ?: albums.firstOrNull { it.kind == AlbumKind.HEALTH && doctor == null }
                if (match != null) return match.id
                val title = doctor?.let { "Dr. $it visits" } ?: person?.let { "$it's health" } ?: "Health"
                return dao.insertAlbum(Album(title = title, kind = AlbumKind.HEALTH, personLabel = person, createdAt = now))
            }
            ItemKind.INSURANCE -> {
                albums.firstOrNull { it.kind == AlbumKind.HEALTH }?.let { if (item.title.contains("health", true)) return it.id }
                return adminAlbum(albums, "Insurance", now)
            }
            ItemKind.BILL -> return adminAlbum(albums, "Home bills", now)
            ItemKind.VEHICLE -> return adminAlbum(albums, "Car papers", now)
            else -> return null
        }
    }

    private suspend fun adminAlbum(albums: List<Album>, title: String, now: Long): Long =
        albums.firstOrNull { it.title == title }?.id ?: dao.insertAlbum(Album(title = title, kind = AlbumKind.ADMIN, createdAt = now))
}
