package com.cue.domain

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The four memory item types. Stored as strings so the database stays readable. */
object ItemType {
    const val GALLERY = "GALLERY"
    const val NOTE = "NOTE"
    const val CHECKLIST = "CHECKLIST"
    const val JOURNAL = "JOURNAL"
}

/** What a saved document turned out to be. */
object ItemKind {
    const val TICKET = "ticket"
    const val HOTEL = "hotel"
    const val ID_DOC = "id"
    const val PRESCRIPTION = "prescription"
    const val LAB_REPORT = "lab_report"
    const val INSURANCE = "insurance"
    const val BILL = "bill"
    const val VEHICLE = "vehicle"
    const val SCREENSHOT = "screenshot"
    const val PLACE_SAVE = "place"
    const val GENERIC = "generic"
}

object AlbumKind {
    const val TRIP = "TRIP"
    const val HEALTH = "HEALTH"
    const val ADMIN = "ADMIN"
    const val GENERAL = "GENERAL"
}

object PlaceKind {
    const val HOME = "HOME"
    const val OFFICE = "OFFICE"
    const val GYM = "GYM"
    const val CLINIC = "CLINIC"
    const val AIRPORT = "AIRPORT"
    const val OTHER = "OTHER"
}

/** Every timed thing Cue can do. The kind decides the opening line and the follow-up rules. */
object ReminderKind {
    const val CHECKIN_MORNING = "CHECKIN_MORNING"
    const val CHECKIN_NIGHT = "CHECKIN_NIGHT"
    const val LEAVE_BY = "LEAVE_BY"
    const val DOSE = "DOSE"
    const val EXPIRY = "EXPIRY"
    const val ACCOUNTABILITY = "ACCOUNTABILITY"
    const val PRACTICE = "PRACTICE"
    const val CARE = "CARE"
    const val GENERIC = "GENERIC"
    const val PRELOAD = "PRELOAD"
    const val AIRPORT_BACKUP = "AIRPORT_BACKUP"
    /** A focus session: a quiet notification when the time is up. */
    const val FOCUS = "FOCUS"

    val calls = setOf(CHECKIN_MORNING, CHECKIN_NIGHT, LEAVE_BY, DOSE, EXPIRY, ACCOUNTABILITY, PRACTICE, CARE, GENERIC)
}

object Surface {
    const val RING = "RING"
    const val NOTIFY = "NOTIFY"
    const val BUBBLE = "BUBBLE"
}

object ReminderStatus {
    const val SCHEDULED = "SCHEDULED"
    const val FIRED = "FIRED"
    const val DONE = "DONE"
    const val MISSED = "MISSED"
    const val CANCELLED = "CANCELLED"
}

@Entity(tableName = "item", indices = [Index("albumId"), Index("contentHash")])
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val kind: String = ItemKind.GENERIC,
    val title: String,
    val body: String = "",
    val fields: String = "{}",
    val eventStart: Long? = null,
    val eventEnd: Long? = null,
    val expiresAt: Long? = null,
    val albumId: Long? = null,
    val mediaPath: String? = null,
    val source: String = "user",
    val contentHash: String? = null,
    val personLabel: String? = null,
    val state: String = "READY",
    val createdAt: Long,
)

@Entity(tableName = "checklist_entry", indices = [Index("itemId")])
data class ChecklistEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val text: String,
    val checked: Boolean = false,
    val position: Int = 0,
)

@Entity(tableName = "album")
data class Album(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val kind: String = AlbumKind.GENERAL,
    val startAt: Long? = null,
    val endAt: Long? = null,
    val placeId: Long? = null,
    val personLabel: String? = null,
    val autoCreated: Boolean = true,
    val createdAt: Long,
)

@Entity(tableName = "place")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val kind: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Float = 150f,
    val code: String? = null,
)

@Entity(tableName = "reminder", indices = [Index("fireAt"), Index("key")])
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val text: String,
    val fireAt: Long,
    val repeatDaily: Boolean = false,
    val surface: String = Surface.RING,
    val status: String = ReminderStatus.SCHEDULED,
    val attempts: Int = 0,
    val albumId: Long? = null,
    val itemId: Long? = null,
    val placeId: Long? = null,
    val habitId: Long? = null,
    /** Planner-owned reminders carry a stable key so reconcile can diff them. Null for user-made ones. */
    val key: String? = null,
    val meta: String = "{}",
    val createdAt: Long,
)

@Entity(tableName = "trigger_event", indices = [Index(value = ["dedupeKey"], unique = true)])
data class TriggerEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val refId: Long? = null,
    val payload: String = "{}",
    val at: Long,
    val dedupeKey: String,
    val decision: String = "",
    val outcome: String = "",
)

@Entity(tableName = "call_session")
data class CallSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val reminderId: Long? = null,
    val ringAt: Long,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val outcome: String = "RINGING",
    val transcript: String = "",
    val filed: Boolean = false,
)

@Entity(tableName = "person")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalized: String,
)

@Entity(tableName = "pending_item", indices = [Index("personId")])
data class PendingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val text: String,
    val sourceApp: String = "",
    val status: String = "OPEN",
    val createdAt: Long,
)

@Entity(tableName = "habit")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val placeKind: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "habit_log", indices = [Index(value = ["habitId", "day"], unique = true)])
data class HabitLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val day: String,
    val done: Boolean,
)

/** Kinds of things that wake the agent up. */
object TriggerKind {
    const val REMINDER = "REMINDER"
    const val GEOFENCE_ENTER = "GEOFENCE_ENTER"
    const val SCREENSHOT = "SCREENSHOT"
    const val SHARE = "SHARE"
    const val NOTIFICATION = "NOTIFICATION"
    const val CALL_MISSED = "CALL_MISSED"
    const val BOOT = "BOOT"
    const val USER_TALK = "USER_TALK"
}
