package com.cue.domain

import java.time.ZoneId

/** Time source. Tests and the debug panel can shift it. */
interface Clock {
    fun now(): Long
    val zone: ZoneId get() = ZoneId.systemDefault()
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

/** Registers what the planner wants with the operating system. Implemented in :triggers. */
interface TriggerScheduler {
    fun schedule(reminder: Reminder)
    fun cancel(reminderId: Long)
    fun registerGeofence(place: Place)
    fun removeGeofence(placeId: Long)
}

/** A request to ring the phone. */
data class CallRequest(
    val reminderId: Long?,
    val kind: String,
    val title: String,
    val subtitle: String,
    val chips: List<String>,
)

/** What a bubble shows. Built from local data only, never needs the model. */
data class BubbleSpec(
    val placeLabel: String,
    val headline: String,
    val card: BubbleCard?,
    val items: List<BubbleItem>,
    val albumId: Long? = null,
    val personId: Long? = null,
)

data class BubbleCard(val from: String, val to: String, val time: String, val gate: String?, val seat: String?)
data class BubbleItem(val id: Long, val text: String, val kind: String)

/** The ways Cue reaches the user. Implemented in :app, which wires :call and :surfaces. */
interface SurfacePresenter {
    fun ring(request: CallRequest)
    fun bubble(spec: BubbleSpec)
    fun notify(title: String, text: String, tag: String)
    fun toast(text: String)
}

/** User settings. Implemented in :app over SharedPreferences. */
interface Settings {
    var userName: String
    var morningMinutes: Int
    var nightMinutes: Int
    var callsEnabled: Boolean
    var caregiverName: String
    var caregiverNumber: String
    var careMode: Boolean
    var practiceEnabled: Boolean
    var practiceMinutes: Int
    var pinnedApps: List<String>
    var onboarded: Boolean
    var brainPreference: String
    var quietStartMinutes: Int
    var quietEndMinutes: Int
}

/** Sends a short message to the caregiver. Implemented in :app. */
interface CaregiverNotifier {
    fun nudge(text: String): Boolean
}
