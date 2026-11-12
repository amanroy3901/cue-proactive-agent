package com.cue.agent

import com.cue.domain.AlbumKind
import com.cue.domain.ItemKind
import com.cue.domain.ItemType
import com.cue.domain.ReminderKind
import com.cue.domain.ReminderStatus
import org.json.JSONObject
import java.util.Locale

/**
 * Builds what Cue says first on each kind of call, and the compact context pack a model sees. Everything comes
 * from the database, so the facts in a briefing are right even when the model is small or missing.
 */
class Briefings(private val env: CueEngine) {
    private val dao get() = env.dao
    private val zone get() = env.clock.zone

    data class Facts(
        val flight: String?,
        val flightAlbumId: Long?,
        val open: List<String>,
        val dosesToday: Int,
        val expiring: String?,
        val accountability: String?,
        val pendingCount: Int,
        val lastNight: String?,
    )

    suspend fun facts(): Facts {
        val now = env.clock.now()
        val ticket = dao.itemsOfKind(ItemKind.TICKET).filter { (it.eventStart ?: 0) > now - 3_600_000 }.minByOrNull { it.eventStart ?: Long.MAX_VALUE }
        val flight = ticket?.let {
            val f = JSONObject(it.fields)
            val to = Airports.code(f.optString("to"))?.city ?: "your trip"
            "Your flight to $to is at ${TextKit.clock(it.eventStart!!, zone)} ${TextKit.dayLabel(it.eventStart!!, now, zone)}"
        }
        val albumId = ticket?.albumId ?: env.activeAlbum()?.id
        val listItem = dao.itemsOfType(ItemType.CHECKLIST).firstOrNull { it.albumId == albumId && albumId != null }
            ?: dao.itemsOfType(ItemType.CHECKLIST).firstOrNull()
        val open = listItem?.let { dao.entries(it.id).filter { e -> !e.checked }.map { e -> e.text } }.orEmpty()
        val today = TextKit.dayKey(now, zone)
        val doses = dao.remindersOfKind(ReminderKind.DOSE).count { TextKit.dayKey(it.fireAt, zone) == today && it.status == ReminderStatus.SCHEDULED }
        val exp = dao.expiring().firstOrNull { (it.expiresAt ?: 0) in now..(now + 30L * 86_400_000) }?.let {
            "${it.title.removeSuffix(" bill")}${if (it.kind == ItemKind.BILL) " warranty" else ""} ends on ${TextKit.dateLabel(it.expiresAt!!, zone)}"
        }
        val acc = dao.remindersOfKind(ReminderKind.ACCOUNTABILITY).firstOrNull { TextKit.dayKey(it.fireAt, zone) == today && it.status == ReminderStatus.SCHEDULED }
            ?.let { "I'll check in at ${TextKit.clock(it.fireAt, zone)}: ${it.text.lowercase(Locale.ROOT)}" }
        val pending = dao.allOpenPending().size
        val lastNight = dao.itemsOfType(ItemType.JOURNAL).firstOrNull()?.takeIf { now - it.createdAt < 36 * 3_600_000L }?.body
        return Facts(flight, albumId, open, doses, exp, acc, pending, lastNight)
    }

    suspend fun opening(kind: String, reminderId: Long?): String {
        val name = env.settings.userName.ifBlank { "there" }
        val now = env.clock.now()
        val rem = reminderId?.let { dao.reminder(it) }
        val f = facts()
        return when (kind) {
            ReminderKind.CHECKIN_MORNING -> buildString {
                append("Good morning, $name. ")
                f.flight?.let { append("$it, and your boarding pass is ready. ") }
                if (f.open.isNotEmpty()) append("${count(f.open.size)} left to pack: ${f.open.joinToString(" and ")}. ")
                if (f.dosesToday > 0) append("You have ${f.dosesToday} medicine reminder${if (f.dosesToday > 1) "s" else ""} today. ")
                f.accountability?.let { append("$it. ") }
                f.expiring?.let { append("Heads up, your $it. ") }
                if (f.flight == null && f.open.isEmpty() && f.dosesToday == 0) append("Nothing urgent today. ")
                append("Anything to add?")
            }
            ReminderKind.CHECKIN_NIGHT -> buildString {
                append("Hi $name, how did today go? ")
                if (f.open.isNotEmpty()) append("You still have ${f.open.joinToString(" and ")} on your list. ")
                append("Anything to remember for tomorrow?")
            }
            ReminderKind.LEAVE_BY -> buildString {
                append(rem?.text ?: "Time to leave for your flight").append(". ")
                if (f.open.isNotEmpty()) append("Still unticked: ${f.open.joinToString(" and ")}. ")
                append("Your boarding pass will pop up when you reach the airport.")
            }
            ReminderKind.DOSE -> {
                val med = rem?.meta?.let { runCatching { JSONObject(it).optString("medicine") }.getOrNull() }.orEmpty().ifBlank { "your medicine" }
                if ((rem?.attempts ?: 0) > 0) "You missed your ${TextKit.clock(rem!!.fireAt, zone)} dose of $med. Did you take it?"
                else "Time for $med. Have you taken it?"
            }
            ReminderKind.CARE -> "Namaste $name. ${rem?.text ?: "Did you take your medicine"}?"
            ReminderKind.EXPIRY -> {
                val item = rem?.itemId?.let { dao.item(it) }
                val album = item?.albumId?.let { dao.album(it) }
                "${rem?.text ?: "Something expires soon"}. " + (album?.let { "The ${item.title.lowercase(Locale.ROOT)} is in ${it.title}. " } ?: "") +
                    "Want me to remind you again the day before?"
            }
            ReminderKind.ACCOUNTABILITY -> {
                val habit = rem?.habitId?.let { dao.habit(it) }
                val streak = habit?.let { env.streak(it.id) } ?: 0
                "It's ${TextKit.clock(now, zone)}. ${rem?.text?.let { checkQuestion(it) } ?: "Did you do it?"}" +
                    if (streak > 0) " You're on a $streak-day streak." else ""
            }
            ReminderKind.PRACTICE -> "Hi $name, it's English practice time. Five minutes. Tell me, what did you do today?"
            "TALK" -> "Hi $name, I'm listening."
            else -> rem?.text?.let { "Hi $name. $it." } ?: "Hi $name."
        }
    }

    private fun count(n: Int) = when (n) { 1 -> "One thing"; 2 -> "Two things"; 3 -> "Three things"; else -> "$n things" }

    private fun checkQuestion(text: String): String {
        val t = text.lowercase(Locale.ROOT).replace(Regex("^(check|make sure)( if| that| whether)? (i )?"), "")
        return when {
            "gym" in t -> "Did you go to the gym today?"
            "study" in t -> "Did you study tonight?"
            else -> "Did you ${t.removePrefix("to ").trimEnd('.', '?')}?"
        }
    }

    /** The compact context pack for a model, within the on-device token budget. */
    suspend fun contextPack(kind: String): String {
        val now = env.clock.now()
        val f = facts()
        val album = f.flightAlbumId?.let { dao.album(it) }
        return buildString {
            appendLine("Now: ${TextKit.local(now, zone).toLocalDate()} ${TextKit.clock(now, zone)}")
            appendLine("User: ${env.settings.userName}")
            appendLine("Call: $kind")
            album?.let { appendLine("Active album: ${it.title}") }
            f.flight?.let { appendLine(it) }
            if (f.open.isNotEmpty()) appendLine("Open list: " + f.open.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("; "))
            f.lastNight?.let { appendLine("Last night: ${it.take(160)}") }
            f.expiring?.let { appendLine("Expiring: $it") }
        }.trim()
    }
}

/** Learns what is pending with each person from message notifications. */
object PeopleParser {
    private val patterns = listOf(
        Regex("(?i)(?:can|could) you (?:please )?(send|share|forward|bring|call|pay|book|check)\\s+(?:me\\s+)?(.{3,50}?)[?.!]?$") to "%1 %2",
        Regex("(?i)please (send|share|forward|bring|call|pay|book|check)\\s+(?:me\\s+)?(.{3,50}?)[.!]?$") to "%1 %2",
        Regex("(?i)(?:don'?t forget|remember) (?:to )?(.{3,50}?)[.!]?$") to "%1",
        Regex("(?i)you owe me\\s+(.{1,30})") to "you owe %1",
        Regex("(?i)(?:waiting for|need) (?:the |your )?(.{3,40}?)(?: from you)?[.!]?$") to "send %1",
    )

    fun pending(text: String): String? {
        for ((re, template) in patterns) {
            val m = re.find(text.trim()) ?: continue
            var out = template
            m.groupValues.drop(1).forEachIndexed { i, g -> out = out.replace("%${i + 1}", g.trim()) }
            return out.replaceFirstChar { it.uppercase() }.trimEnd('?', '.', '!')
        }
        return null
    }
}

/** Filing after a call: the journal entry for night calls and a practice note. Uses the brain when it can. */
class Filing(private val env: CueEngine) {
    private val moods = listOf("tired", "happy", "rushed", "ready", "calm", "stressed", "excited", "sad", "good", "busy", "relaxed", "anxious")

    suspend fun file(kind: String, userLines: List<String>, unhandled: List<String>, changes: List<String>, albumId: Long?, practiceNotes: List<String>): List<String> {
        val filed = mutableListOf<String>()
        val now = env.clock.now()
        val zone = env.clock.zone
        if (kind == ReminderKind.CHECKIN_NIGHT || (kind == "TALK" && unhandled.size >= 2)) {
            var body = unhandled.joinToString(" ") { s -> s.trim().replaceFirstChar { it.uppercase() }.let { if (it.endsWith('.')) it else "$it." } }
            val brain = env.router.forJob("file")
            if (brain.name != "rules" && brain.isReady() && userLines.isNotEmpty()) {
                val r = runCatching {
                    brain.complete(com.cue.brain.FieldsRequest("file", "Write a two-sentence first-person journal entry for today from what the user said. Reply as JOURNAL: <text>",
                        userLines.joinToString("\n")))
                }.getOrNull()
                r?.lines?.firstOrNull { it.first.equals("JOURNAL", true) }?.second?.takeIf { it.length > 10 }?.let { body = it }
            }
            if (body.isBlank()) body = if (changes.isNotEmpty()) "Planned for tomorrow: " + changes.joinToString("; ") { it.substringAfter(": ") } + "." else "A quiet day."
            val lower = (userLines.joinToString(" ")).lowercase(Locale.ROOT)
            val mood = moods.filter { Regex("\\b$it\\b").containsMatchIn(lower) }.take(3)
            val photos = env.dao.itemsOfType(com.cue.domain.ItemType.GALLERY).count { it.source == "screenshot" && TextKit.dayKey(it.createdAt, zone) == TextKit.dayKey(now, zone) }
            env.dao.insertItem(com.cue.domain.Item(
                type = ItemType.JOURNAL, title = "Journal · " + TextKit.local(now, zone).toLocalDate().let { "${it.dayOfWeek.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }}, ${TextKit.dateLabel(now, zone)}" },
                body = body, fields = JSONObject().put("mood", mood.joinToString(",")).put("photos", photos).toString(),
                eventStart = now, albumId = albumId?.takeIf { env.dao.album(it)?.kind == AlbumKind.TRIP }, source = "call", createdAt = now))
            filed += "journal_write: " + body.take(90) + if (photos > 0) " (linked to $photos photo${if (photos > 1) "s" else ""})" else ""
        }
        if (kind == ReminderKind.PRACTICE && practiceNotes.isNotEmpty()) {
            env.dao.insertItem(com.cue.domain.Item(type = ItemType.NOTE, kind = "practice", title = "English practice · ${TextKit.dateLabel(now, zone)}",
                body = practiceNotes.joinToString("\n"), source = "call", createdAt = now))
            filed += "practice_note: ${practiceNotes.size} correction${if (practiceNotes.size > 1) "s" else ""} saved"
        }
        return filed
    }
}
