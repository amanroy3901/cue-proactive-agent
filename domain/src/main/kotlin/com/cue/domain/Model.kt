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
    /** A photo added to a space's gallery: kept as is, not read for fields. */
    const val PHOTO = "photo"
    const val GENERIC = "generic"
}

object AlbumKind {
    const val TRIP = "TRIP"
    const val HEALTH = "HEALTH"
    const val ADMIN = "ADMIN"
    const val EVENT = "EVENT"
    const val MOVE = "MOVE"
    const val STUDY = "STUDY"
    const val GENERAL = "GENERAL"
    val all = listOf(TRIP, HEALTH, EVENT, MOVE, STUDY, ADMIN, GENERAL)
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
    /** Cue checking back on something it said it would ("I'll ask you tomorrow how the visit went"). */
    const val FOLLOW_UP = "FOLLOW_UP"
    /** Time to take a reading the doctor asked for (BP, sugar). */
    const val MEASURE = "MEASURE"

    val calls = setOf(CHECKIN_MORNING, CHECKIN_NIGHT, LEAVE_BY, DOSE, EXPIRY, ACCOUNTABILITY, PRACTICE, CARE, GENERIC, FOLLOW_UP, MEASURE)
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
    /** Paused from this moment; the streak skips paused days instead of breaking. Null when not paused. */
    val pausedFrom: Long? = null,
    /** Paused until this moment; Long.MAX_VALUE means until the user resumes. */
    val pausedUntil: Long? = null,
    /** Daily check-in call time, minutes after midnight; null for no call. */
    val checkMinutes: Int? = null,
) {
    fun pausedAt(t: Long): Boolean = pausedFrom != null && t >= pausedFrom && t < (pausedUntil ?: Long.MAX_VALUE)
}

@Entity(tableName = "habit_log", indices = [Index(value = ["habitId", "day"], unique = true)])
data class HabitLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val day: String,
    val done: Boolean,
    /** A day the habit was paused: it neither counts nor breaks the streak. */
    @androidx.room.ColumnInfo(defaultValue = "0") val paused: Boolean = false,
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

/** A plan that keeps making reminders: a medicine course or a measuring routine. Stored as an Item of this type. */
object PlanKind {
    const val TYPE = "PLAN"
    const val MEDICINE = "medicine"
    const val MEASURE = "measure"
}

/**
 * One thing Cue knows about the user ("I have lunch at 1", "allergic to penicillin", "don't call before 8").
 * [key] lets a newer fact replace an older one in place (routine.lunch). Facts Cue picked up on its own start
 * unconfirmed and show on the "What Cue learned" card until the user keeps or removes them.
 */
@Entity(tableName = "profile_fact", indices = [Index("key")])
data class ProfileFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val category: String,
    val text: String,
    val source: String = "call",
    val confirmed: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

object FactCategory {
    const val ABOUT = "about"
    const val PREFERENCE = "preference"
    const val ROUTINE = "routine"
    const val HEALTH = "health"
    const val PEOPLE = "people"
    val all = listOf(ABOUT, ROUTINE, PREFERENCE, HEALTH, PEOPLE)
}

/** A health reading: blood pressure (value/value2), sugar, weight or pulse. */
@Entity(tableName = "measurement", indices = [Index("kind"), Index("takenAt")])
data class Measurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val value: Double,
    val value2: Double? = null,
    val unit: String,
    val context: String = "",
    val takenAt: Long,
    val source: String = "call",
)

object MeasureKind {
    const val BP = "BP"
    const val SUGAR = "SUGAR"
    const val WEIGHT = "WEIGHT"
    const val PULSE = "PULSE"
}

/**
 * "When X happens, do Y": a standing rule the user set by voice. Plain code matches the trigger; limits keep it
 * from nagging: a cooldown between fires, an optional fire cap, and an expiry.
 */
@Entity(tableName = "standing_rule")
data class StandingRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trigger: String,
    val match: String,
    val text: String,
    val fires: Int = 0,
    val maxFires: Int = 0,
    val lastFiredAt: Long? = null,
    val expiresAt: Long? = null,
    val active: Boolean = true,
    val createdAt: Long,
)

object RuleTrigger {
    const val ARRIVE = "arrive"
    const val LEAVE = "leave"
    const val MESSAGE = "message"
    const val MORNING = "morning"
    const val NIGHT = "night"
    val all = listOf(ARRIVE, LEAVE, MESSAGE, MORNING, NIGHT)
}

/**
 * A Space is an album built for one goal (a trip, a doctor visit, a wedding, a move). Its page is a stack of blocks,
 * each a fixed mini-app (packing list, expenses, itinerary, documents...). Cue picks the blocks; plain code checks them.
 */
@Entity(tableName = "space_block", indices = [Index("albumId")])
data class SpaceBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumId: Long,
    val type: String,
    val title: String,
    val position: Int,
    /** Block settings as JSON, checked against the block type's rules before saving. */
    val config: String = "{}",
    val createdAt: Long,
)

/** One row of a block's own data (an expense, an itinerary stop), as JSON checked against the block type. */
@Entity(tableName = "block_record", indices = [Index("blockId"), Index("albumId")])
data class BlockRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blockId: Long,
    val albumId: Long,
    val data: String,
    /** When it happened or happens: the expense's time, the stop's time. */
    val at: Long,
    val createdAt: Long,
)

/** The fixed block library. Each type has one Compose component and one set of allowed settings. */
object BlockType {
    const val COUNTDOWN = "countdown"
    const val DOCUMENTS = "documents"
    const val CHECKLIST = "checklist"
    const val ITINERARY = "itinerary"
    const val EXPENSES = "expenses"
    const val GALLERY = "gallery"
    const val NOTES = "notes"
    const val JOURNAL = "journal"
    const val PEOPLE = "people"
    const val READINGS = "readings"
    const val MEDICINES = "medicines"
    const val REMINDERS = "reminders"
    val all = listOf(COUNTDOWN, DOCUMENTS, CHECKLIST, ITINERARY, EXPENSES, GALLERY, NOTES, JOURNAL, PEOPLE, READINGS, MEDICINES, REMINDERS)
}
