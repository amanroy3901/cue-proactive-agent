package com.cue.agent

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Small language helpers shared by the planner, the tools and the rules brain. */
object TextKit {
    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "once" to 1, "two" to 2, "twice" to 2, "three" to 3, "thrice" to 3,
        "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "fourteen" to 14, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
    )

    fun number(word: String?): Int? {
        if (word == null) return null
        val w = word.trim().lowercase()
        return w.toIntOrNull() ?: numberWords[w]
    }

    fun normalize(s: String): String = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Splits "charger, passport and earphones" into three entries. */
    fun splitList(s: String): List<String> = s
        .replace(Regex("\\b(and|&|plus|also)\\b", RegexOption.IGNORE_CASE), ",")
        .split(",", ";")
        .map { it.trim().trimEnd('.', '!', '?').replace(Regex("^(?i)(my|the|a|an|some|our)\\s+"), "").trim() }
        .filter { it.isNotBlank() && it.length < 60 }
        .map { it.replaceFirstChar { c -> c.uppercase() } }

    /** Word-overlap similarity used to match "I've packed the charger" against the entry "Charger". */
    fun similarity(a: String, b: String): Double {
        val ta = normalize(a).split(" ").filter { it.length > 1 && it !in stop }.toSet()
        val tb = normalize(b).split(" ").filter { it.length > 1 && it !in stop }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.count { x -> tb.any { y -> y.startsWith(x.take(4)) || x.startsWith(y.take(4)) } }
        return inter.toDouble() / minOf(ta.size, tb.size)
    }

    private val stop = setOf("the", "my", "a", "an", "to", "of", "and", "i", "ve", "have", "has", "is", "it", "for", "packed", "done", "with")

    fun dayKey(epoch: Long, zone: ZoneId): String = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate().toString()

    fun clock(epoch: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(epoch).atZone(zone)
        val f = if (t.minute == 0) DateTimeFormatter.ofPattern("h a", Locale.ENGLISH) else DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        return t.format(f).replace("AM", "AM").replace("PM", "PM")
    }

    fun dayLabel(epoch: Long, now: Long, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (d) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            today.minusDays(1) -> "yesterday"
            else -> d.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH))
        }
    }

    /** "a", "a and b", "a, b and c". */
    fun joinAnd(parts: List<String>): String = when (parts.size) { 0 -> ""; 1 -> parts[0]; else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last() }

    fun dateLabel(epoch: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epoch).atZone(zone).format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))

    fun at(date: LocalDate, minutes: Int, zone: ZoneId): Long =
        date.atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(zone).toInstant().toEpochMilli()

    /** Next occurrence of a time of day, strictly after now. */
    fun nextDaily(minutes: Int, now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val t = at(today, minutes, zone)
        return if (t > now + 30_000) t else at(today.plusDays(1), minutes, zone)
    }

    /**
     * Parses spoken times: "6", "6 pm", "6:30", "18:00", "tomorrow at 7", "in 20 minutes", "tonight", "noon".
     * A bare hour picks the next future occurrence of that hour, morning or evening.
     */
    fun parseWhen(phrase: String, now: Long, zone: ZoneId): Long? {
        val p = phrase.lowercase(Locale.ROOT).replace(".", "").trim()
        val base = Instant.ofEpochMilli(now).atZone(zone)
        Regex("in (\\w+) (minute|min|hour|hr)s?").find(p)?.let { m ->
            val n = number(m.groupValues[1]) ?: return null
            val ms = if (m.groupValues[2].startsWith("h")) n * 3_600_000L else n * 60_000L
            return now + ms
        }
        var day: LocalDate = base.toLocalDate()
        var dayForced = false
        if ("tomorrow" in p) { day = day.plusDays(1); dayForced = true }
        val dow = DayOfWeek.entries.firstOrNull { p.contains(it.name.lowercase()) }
        if (dow != null) { day = day.with(TemporalAdjusters.next(dow)); dayForced = true }
        when {
            "noon" in p -> return at(day, 12 * 60, zone).let { if (!dayForced && it < now) at(day.plusDays(1), 720, zone) else it }
            "midnight" in p -> return at(day.plusDays(1), 0, zone)
        }
        val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a m|p m)?").find(p)
            ?: Regex("\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\b\\s*(am|pm)?").find(p)?.let { w ->
                return parseWhen(p.replace(w.value, "${number(w.groupValues[1])} ${w.groupValues[2]}"), now, zone)
            }
            ?: return when {
                "tonight" in p -> at(day, 21 * 60, zone)
                "morning" in p -> at(if (dayForced) day else day.plusDays(1), 9 * 60, zone)
                "evening" in p -> at(day, 18 * 60, zone)
                else -> null
            }
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[3].replace(" ", "")
        if (h > 23 || min > 59) return null
        val evening = "tonight" in p || "evening" in p || "night" in p
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        if (ampm.isEmpty() && evening && h < 12) h += 12
        val explicit = ampm.isNotEmpty() || h >= 13 || evening
        var t = at(day, h * 60 + min, zone)
        if (!explicit && h in 1..11) {
            val pm = at(day, (h + 12) * 60 + min, zone)
            t = when {
                t > now -> t
                pm > now -> pm
                else -> at(day.plusDays(1), h * 60 + min, zone)
            }
        } else if (t <= now && !dayForced) {
            t = at(day.plusDays(1), h * 60 + min, zone)
        }
        return t
    }

    /** "moved to 9" keeps the event's day and its half of the day unless am/pm is said. */
    fun moveTime(original: Long, phrase: String, zone: ZoneId): Long? {
        val p = phrase.lowercase(Locale.ROOT)
        val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?").find(p) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[3]
        val orig = Instant.ofEpochMilli(original).atZone(zone)
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        if (ampm.isEmpty() && h < 12 && orig.hour >= 12) h += 12
        var date = orig.toLocalDate()
        if ("tomorrow" in p) date = date.plusDays(1)
        return date.atTime(h, min).atZone(zone).toInstant().toEpochMilli()
    }

    fun parseDate(text: String, now: Long, zone: ZoneId): LocalDate? {
        val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        val t = text.lowercase(Locale.ROOT)
        Regex("(\\d{1,2})[\\s-]*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\\s,-]*(\\d{2,4})?").find(t)?.let { m ->
            return build(m.groupValues[1].toInt(), months.indexOf(m.groupValues[2]) + 1, m.groupValues[3], now, zone)
        }
        Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+(\\d{1,2})(?:st|nd|rd|th)?[,\\s]*(\\d{4})?").find(t)?.let { m ->
            return build(m.groupValues[2].toInt(), months.indexOf(m.groupValues[1]) + 1, m.groupValues[3], now, zone)
        }
        Regex("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})\\b").find(t)?.let { m ->
            return build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3], now, zone)
        }
        return null
    }

    private fun build(d: Int, mo: Int, y: String, now: Long, zone: ZoneId): LocalDate? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val year = when {
            y.isEmpty() -> today.year
            y.length == 2 -> 2000 + y.toInt()
            else -> y.toInt()
        }
        return runCatching { LocalDate.of(year, mo, d) }.getOrNull()?.let { if (y.isEmpty() && it.isBefore(today.minusDays(60))) it.plusYears(1) else it }
    }

    fun parseTimeOfDay(text: String): Int? {
        val m = Regex("\\b(\\d{1,2})[:.](\\d{2})\\s*(am|pm|hrs)?\\b", RegexOption.IGNORE_CASE).find(text) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val ampm = m.groupValues[3].lowercase()
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    fun local(epoch: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(epoch).atZone(zone)
    fun localDateTime(epoch: Long, zone: ZoneId): LocalDateTime = local(epoch, zone).toLocalDateTime()
}
