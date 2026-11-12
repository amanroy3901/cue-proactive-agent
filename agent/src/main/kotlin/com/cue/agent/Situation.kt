package com.cue.agent

/** Device state read at decision time. Filled in by :app from Android services. */
data class DeviceState(
    val locked: Boolean = false,
    val onPhoneCall: Boolean = false,
    val doNotDisturb: Boolean = false,
    val batteryLow: Boolean = false,
    val overlayAllowed: Boolean = true,
    val fullScreenAllowed: Boolean = true,
    val inQuietHours: Boolean = false,
    val callActive: Boolean = false,
)

sealed interface Decision {
    data object Ring : Decision
    data object RingHeadsUp : Decision
    data object Bubble : Decision
    data class Notify(val silent: Boolean) : Decision
    data object Silent : Decision
    data class Defer(val minutes: Int) : Decision
    data object BubbleOnUnlock : Decision
}

/** Plain-code rules for "the right moment". No model involved. */
object SituationEngine {
    fun forRing(d: DeviceState, declinedTimes: Int): Decision = when {
        d.callActive -> Decision.Defer(5)
        d.onPhoneCall -> Decision.Defer(10)
        d.doNotDisturb || d.inQuietHours -> Decision.Notify(silent = true)
        declinedTimes >= 2 -> Decision.Notify(silent = false)
        !d.fullScreenAllowed -> Decision.RingHeadsUp
        else -> Decision.Ring
    }

    fun forBubble(d: DeviceState, hasContent: Boolean, shownRecently: Boolean): Decision = when {
        !hasContent -> Decision.Silent
        shownRecently -> Decision.Silent
        d.callActive -> Decision.Notify(silent = true)
        d.locked -> Decision.BubbleOnUnlock
        !d.overlayAllowed -> Decision.Notify(silent = false)
        else -> Decision.Bubble
    }

    fun describe(d: Decision): String = when (d) {
        Decision.Ring -> "RING"
        Decision.RingHeadsUp -> "RING (heads-up)"
        Decision.Bubble -> "BUBBLE"
        Decision.BubbleOnUnlock -> "NOTIFY now, BUBBLE on unlock"
        is Decision.Notify -> if (d.silent) "NOTIFY (silent)" else "NOTIFY"
        Decision.Silent -> "SILENT"
        is Decision.Defer -> "DEFER ${d.minutes} min"
    }
}
