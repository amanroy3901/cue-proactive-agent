package com.cue.agent

import com.cue.brain.ToolResult
import com.cue.domain.FactCategory
import com.cue.domain.Item
import com.cue.domain.MeasureKind
import com.cue.domain.PlanKind
import com.cue.domain.ProfileFact
import com.cue.domain.Reminder
import com.cue.domain.ReminderKind
import com.cue.domain.ReminderStatus
import com.cue.domain.RuleTrigger
import com.cue.domain.StandingRule
import com.cue.domain.Surface
import java.util.Locale

/*
 * The request pipeline around every tool call:
 *   gather context (profile, linked plans) -> check in code (missing details, confirm, conflicts) -> act
 *   -> impact check (what else this change moves) -> follow-ups (questions a careful assistant would ask next)
 *   -> learn (facts worth keeping) -> reply.
 * Tool results carry the next step for the model in a fixed shape it can follow:
 *   NEEDS <slot>. ASK: <question>          the tool did nothing; ask, then call again with the answer
 *   CONFIRM. ASK: <question>               the tool did nothing; call again with confirm=yes only after a yes
 *   IMPACT: <what else changed or clashes>  tell the user in a few words
 *   FOLLOW-UP: <questions>                  ask them one at a time, skipping any already answered
 * The rules brain speaks only the ASK part, so the same results work without a model.
 */
object Ask {
    fun needs(tool: String, slot: String, question: String) = ToolResult(tool, false, "NEEDS $slot. ASK: $question")
    fun confirm(tool: String, question: String) =
        ToolResult(tool, false, "CONFIRM. ASK: $question Call $tool again with confirm=yes only if they say yes.")

    /** The words a voice-only brain should say for a result that stopped to ask. */
    fun spoken(message: String): String = message.substringAfter("ASK: ", message).substringBefore(" Call ").trim()

    fun isQuestion(r: ToolResult) = !r.ok && (r.message.startsWith("NEEDS ") || r.message.startsWith("CONFIRM."))

    /** Words a model puts in a slot when it doesn't know the answer; treated as missing. */
    private val placeholders = setOf("unknown", "none", "null", "n/a", "na", "tbd", "not specified", "unspecified", "not sure", "not given", "medicine",
        "the medicine", "my medicine", "your medicine", "medication", "tablet", "pill", "?", "-", "x", "something", "it")

    fun isPlaceholder(v: String?): Boolean = v == null || v.trim().lowercase(Locale.ROOT).trim('.', '"', '\'') in placeholders || v.isBlank()
}

/** Streaks that skip paused days instead of breaking on them. */
object HabitDays {
    fun pausedOn(h: com.cue.domain.Habit?, day: java.time.LocalDate, zone: java.time.ZoneId): Boolean {
        val from = h?.pausedFrom ?: return false
        val until = h.pausedUntil ?: Long.MAX_VALUE
        return TextKit.at(day, 0, zone) < until && TextKit.at(day, 23 * 60 + 59, zone) >= from
    }

    fun streak(h: com.cue.domain.Habit?, logs: List<com.cue.domain.HabitLog>, today: java.time.LocalDate, zone: java.time.ZoneId): Int {
        val byDay = logs.associateBy { it.day }
        var day = today
        if (byDay[day.toString()]?.done != true) day = day.minusDays(1)
        var n = 0
        repeat(400) {
            val l = byDay[day.toString()]
            when {
                l?.done == true -> n++
                l?.paused == true || pausedOn(h, day, zone) -> Unit
                else -> return n
            }
            day = day.minusDays(1)
        }
        return n
    }
}

/** Words for health readings, and the ranges past which Cue says "check with your doctor" (it never diagnoses). */
object MeasureWords {
    fun name(kind: String): String = when (kind) {
        MeasureKind.BP -> "blood pressure"; MeasureKind.SUGAR -> "blood sugar"; MeasureKind.WEIGHT -> "weight"; MeasureKind.PULSE -> "pulse"; else -> "reading"
    }

    fun unit(kind: String): String = when (kind) { MeasureKind.BP -> "mmHg"; MeasureKind.SUGAR -> "mg/dL"; MeasureKind.WEIGHT -> "kg"; MeasureKind.PULSE -> "bpm"; else -> "" }

    fun kindOf(words: String): String? {
        val w = words.lowercase(Locale.ROOT)
        return when {
            Regex("\\b(bp|blood pressure|pressure)\\b").containsMatchIn(w) -> MeasureKind.BP
            Regex("\\b(sugar|glucose|diabet\\w*)\\b").containsMatchIn(w) -> MeasureKind.SUGAR
            Regex("\\b(weight|weigh)\\b").containsMatchIn(w) -> MeasureKind.WEIGHT
            Regex("\\b(pulse|heart rate)\\b").containsMatchIn(w) -> MeasureKind.PULSE
            else -> null
        }
    }

    /** "130/85", "130 over 85", "one thirty over eighty five" (digits only), "110". */
    fun parse(kind: String, text: String): Pair<Double, Double?>? {
        val t = text.lowercase(Locale.ROOT)
        if (kind == MeasureKind.BP) {
            Regex("(\\d{2,3})\\s*(?:/|over|by|-)\\s*(\\d{2,3})").find(t)?.let { return it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
            return null
        }
        return Regex("(\\d{1,3}(?:\\.\\d)?)").find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it to null }
    }

    fun format(kind: String, v: Double, v2: Double?): String =
        if (kind == MeasureKind.BP && v2 != null) "${v.toInt()}/${v2.toInt()}" else if (v % 1.0 == 0.0) "${v.toInt()}" else "$v"

    /** A note for the model when a reading is outside the usual range. Null when it is in range. */
    fun flag(kind: String, v: Double, v2: Double?, context: String): String? = when (kind) {
        MeasureKind.BP -> when {
            v >= 180 || (v2 ?: 0.0) >= 120 -> "URGENT: this is very high. Say so calmly and tell them to contact their doctor now, or emergency services if they feel chest pain, breathlessness or weakness."
            v >= 140 || (v2 ?: 0.0) >= 90 -> "This is above the usual 140/90. Mention it gently, don't diagnose, and ask whether the doctor gave a number to call about."
            v < 90 || (v2 ?: 99.0) < 60 -> "This is on the low side. Ask how they feel and suggest telling the doctor if they feel dizzy."
            else -> null
        }
        MeasureKind.SUGAR -> {
            val fasting = context.contains("fast", true) || context.contains("empty", true) || context.contains("morning", true)
            when {
                v < 54 || v > 300 -> "URGENT: this is far outside the usual range. Tell them to follow their doctor's plan for this now and contact the doctor."
                v < 70 -> "This is low. Ask how they feel and suggest following what the doctor said for low sugar."
                (fasting && v > 130) || v > 180 -> "This is above the usual range. Mention it gently, don't diagnose, and ask if the doctor set a target."
                else -> null
            }
        }
        else -> null
    }
}

/** Meals and parts of the day that a time can hang off ("after lunch"), and reading times out of speech. */
object Anchors {
    val names = listOf("breakfast", "lunch", "dinner", "wake", "bed", "office", "gym")

    fun label(anchor: String) = when (anchor) { "wake" -> "waking up"; "bed" -> "bedtime"; else -> anchor }

    fun find(text: String): String? {
        val t = text.lowercase(Locale.ROOT)
        return when {
            Regex("\\bbreakfast\\b").containsMatchIn(t) -> "breakfast"
            Regex("\\blunch\\b").containsMatchIn(t) -> "lunch"
            Regex("\\b(dinner|supper)\\b").containsMatchIn(t) -> "dinner"
            Regex("\\b(wake|waking|get up|getting up)\\b").containsMatchIn(t) -> "wake"
            Regex("\\b(bed|bedtime|sleep)\\b").containsMatchIn(t) -> "bed"
            else -> null
        }
    }

    fun food(text: String): String {
        val t = text.lowercase(Locale.ROOT)
        return when {
            Regex("\\b(after|post)\\b").containsMatchIn(t) && Regex("\\b(food|meal|breakfast|lunch|dinner|eating|supper)\\b").containsMatchIn(t) -> "after"
            Regex("\\b(before|pre|empty stomach)\\b").containsMatchIn(t) && Regex("\\b(food|meal|breakfast|lunch|dinner|eating|supper|stomach)\\b").containsMatchIn(t) -> "before"
            Regex("\\bwith\\b").containsMatchIn(t) && Regex("\\b(food|meal|breakfast|lunch|dinner)\\b").containsMatchIn(t) -> "with"
            else -> ""
        }
    }

    /** One time of day in minutes: "1 pm", "13:00", "9" (a bare hour: 7-11 morning, 12 noon, 1-6 afternoon). */
    fun minutes(text: String, hint: String = ""): Int? {
        val t = text.lowercase(Locale.ROOT).replace(".", "")
        if (Regex("\\bnoon\\b").containsMatchIn(t)) return 12 * 60
        val m = Regex("\\b(\\d{1,2})(?:[:](\\d{2}))?\\s*(am|pm|a m|p m)?\\b").find(t)
            ?: Regex("\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\b\\s*(am|pm)?").find(t)?.let { w ->
                return minutes(t.replace(w.value, "${TextKit.number(w.groupValues[1])} ${w.groupValues[2]}"), hint)
            } ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[3].replace(" ", "")
        if (h > 23 || min > 59) return null
        val ctx = "$t $hint"
        when {
            ampm == "pm" && h < 12 -> h += 12
            ampm == "am" && h == 12 -> h = 0
            ampm.isEmpty() && h < 12 && Regex("\\b(night|evening|dinner|bed|tonight|pm)\\b").containsMatchIn(ctx) -> h += 12
            ampm.isEmpty() && h in 1..6 && !Regex("\\b(morning|breakfast|am|wake)\\b").containsMatchIn(ctx) -> h += 12
        }
        return h * 60 + min
    }

    data class Times(val minutes: List<Int>, val anchor: String, val food: String, val unknownAnchor: String?)

    /**
     * "1 pm after lunch", "9 am and 9 pm", "after breakfast and dinner", "morning and night". Meal words use the
     * user's own meal times from the profile; a meal Cue doesn't know yet comes back in [Times.unknownAnchor] so
     * the tool asks instead of guessing.
     */
    suspend fun times(phrase: String, known: suspend (String) -> Int?): Times {
        val food = food(phrase)
        val parts = phrase.lowercase(Locale.ROOT).split(Regex("\\s*(?:,|\\band\\b|&|\\bthen\\b)\\s*")).filter { it.isNotBlank() }
        val out = mutableListOf<Int>()
        var firstAnchor = ""
        var unknown: String? = null
        for (part in parts) {
            val anchor = find(part) ?: when {
                Regex("\\bmorning\\b").containsMatchIn(part) && minutes(part) == null -> "breakfast"
                Regex("\\b(night|evening)\\b").containsMatchIn(part) && minutes(part) == null -> "dinner"
                else -> null
            }
            if (anchor != null && firstAnchor.isEmpty()) firstAnchor = anchor
            val explicit = minutes(part, phrase)
            when {
                explicit != null -> out += explicit
                anchor != null -> { val k = known(anchor); if (k != null) out += k else if (unknown == null) unknown = anchor }
            }
        }
        return Times(out.distinct().sorted(), firstAnchor, food, unknown)
    }

    fun clock(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (m == 0) "$h12 ${if (h < 12) "AM" else "PM"}" else "$h12:${"%02d".format(m)} ${if (h < 12) "AM" else "PM"}"
    }
}

/**
 * What Cue knows about the user. Always loaded into a call's context (a small capped block), replaced in place
 * when the user corrects it, and linked to plans through routine anchors so a change ripples to what depends on it.
 */
class Profile(private val env: AgentEnv) {
    private val dao get() = env.dao

    /** A meal or day-anchor time the user told Cue, in minutes after midnight. */
    suspend fun routine(anchor: String): Int? = dao.factByKey("routine.$anchor")?.let { Anchors.minutes(it.text, anchor) }

    suspend fun lines(max: Int = 10): List<String> =
        dao.facts().filter { it.confirmed }.sortedBy { FactCategory.all.indexOf(it.category) }.take(max).map { "#${it.id} ${it.text}" }

    /** A stable key: routine.lunch for "lunch at 1", quiet.before for "don't call before 8", else topic + first words. */
    fun keyFor(text: String, category: String): String {
        val t = text.lowercase(Locale.ROOT)
        val anchor = Anchors.find(t)
        if (anchor != null && Anchors.minutes(t, anchor) != null && category in setOf(FactCategory.ROUTINE, FactCategory.ABOUT, FactCategory.PREFERENCE))
            return "routine.$anchor"
        if (Regex("\\b(don'?t|do not|never|no)\\b.*\\b(call|ring|disturb)\\b").containsMatchIn(t)) {
            if (Regex("\\b(before|until|till)\\b").containsMatchIn(t)) return "quiet.before"
            if (Regex("\\b(after|past)\\b").containsMatchIn(t)) return "quiet.after"
        }
        Regex("\\bmy (doctor|dentist|wife|husband|mom|mother|dad|father|son|daughter|brother|sister|boss|manager)\\b").find(t)?.let { return "people.${it.groupValues[1]}" }
        val words = TextKit.normalize(t).split(" ").filter { it.length > 2 && it !in STOP }.take(3)
        return "$category." + words.joinToString("_")
    }

    data class Saved(val fact: ProfileFact, val replaced: ProfileFact?, val impact: String)

    /** Saves or replaces a fact, then runs the impact check for what hangs off it. */
    suspend fun remember(text: String, category: String, source: String = "call", confirmed: Boolean = true): Saved {
        val now = env.clock.now()
        val cat = category.lowercase(Locale.ROOT).takeIf { it in FactCategory.all } ?: guessCategory(text)
        val clean = text.trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        val key = keyFor(clean, cat)
        val old = dao.factByKey(key) ?: dao.facts().filter { it.category == cat }.maxByOrNull { TextKit.similarity(it.text, clean) }
            ?.takeIf { TextKit.similarity(it.text, clean) >= 0.6 }
        val fact = if (old != null) {
            val f = old.copy(key = key, category = cat, text = clean, source = if (old.confirmed) old.source else source, confirmed = old.confirmed || confirmed, updatedAt = now)
            dao.updateFact(f); f
        } else {
            val f = ProfileFact(key = key, category = cat, text = clean, source = source, confirmed = confirmed, createdAt = now, updatedAt = now)
            f.copy(id = dao.insertFact(f))
        }
        val impact = if (key.startsWith("routine.")) {
            val anchor = key.removePrefix("routine.")
            Anchors.minutes(clean, anchor)?.let { Impact(env).routineChanged(anchor, it) }.orEmpty()
        } else ""
        return Saved(fact, old, impact)
    }

    fun guessCategory(text: String): String {
        val t = text.lowercase(Locale.ROOT)
        return when {
            Regex("\\b(allerg\\w*|diabet\\w*|bp|blood pressure|doctor|medicine|tablet|thyroid|asthma|pregnan\\w*|surgery|sugar)\\b").containsMatchIn(t) -> FactCategory.HEALTH
            Anchors.find(t) != null && Anchors.minutes(t) != null -> FactCategory.ROUTINE
            Regex("\\b(prefer|like|love|hate|don'?t like|don'?t call|never call|rather)\\b").containsMatchIn(t) -> FactCategory.PREFERENCE
            Regex("\\bmy (wife|husband|mom|mother|dad|father|son|daughter|brother|sister|boss|manager|friend)\\b").containsMatchIn(t) -> FactCategory.PEOPLE
            Regex("\\b(usually|every day|daily|always)\\b").containsMatchIn(t) -> FactCategory.ROUTINE
            else -> FactCategory.ABOUT
        }
    }

    /** Hours the user asked not to be rung, from "don't call before 8" / "don't call after 10 pm". */
    suspend fun quietHours(): Pair<Int?, Int?> =
        dao.factByKey("quiet.before")?.let { Anchors.minutes(it.text, "morning") } to dao.factByKey("quiet.after")?.let { Anchors.minutes(it.text, "night") }

    companion object {
        private val STOP = setOf("the", "and", "for", "with", "that", "this", "have", "has", "his", "her", "who", "user", "they", "are", "was", "she", "him", "you", "your", "about")
    }
}

/**
 * After every change: what else it touches. Plans that hang off a routine move with it (they were set "after
 * lunch", so they follow lunch); new times are checked against the other calls and the user's quiet hours.
 */
class Impact(private val env: AgentEnv) {
    private val dao get() = env.dao
    private val zone get() = env.clock.zone

    /** The user's lunch (or wake, bed...) moved: move every plan that hangs off it by the same amount. */
    suspend fun routineChanged(anchor: String, minutes: Int): String {
        val moved = mutableListOf<String>()
        for (item in dao.plans()) {
            val p = PlanSpec.of(item)
            if (!p.active || p.anchor != anchor || p.times.isEmpty()) continue
            val old = p.times.first()
            val delta = minutes - old
            if (delta == 0) continue
            val times = p.times.map { ((it + delta) % 1440 + 1440) % 1440 }.sorted()
            dao.updateItem(item.copy(fields = p.copy(times = times).json()))
            moved += "${p.what} from ${Anchors.clock(old)} to ${Anchors.clock(times.first())}"
        }
        if (moved.isEmpty()) return ""
        env.planner.reconcile()
        return "IMPACT: moved ${moved.joinToString("; ")} to stay after ${Anchors.label(anchor)}. Tell the user in a few words."
    }

    /** Other rings within 15 minutes of [at], and the user's quiet hours. Empty when nothing clashes. */
    suspend fun check(at: Long, excludeIds: Set<Long> = emptySet(), excludePlan: Long? = null): String {
        val notes = mutableListOf<String>()
        val near = dao.scheduledReminders().filter {
            it.id !in excludeIds && it.surface == Surface.RING && it.kind != ReminderKind.PRELOAD && kotlin.math.abs(it.fireAt - at) < 15 * 60_000 &&
                (excludePlan == null || it.itemId != excludePlan)
        }
        near.firstOrNull()?.let { notes += "it is close to ${reminderLabel(it)} at ${TextKit.clock(it.fireAt, zone)}; ask whether to combine them into one call" }
        val (before, after) = Profile(env).quietHours()
        val m = TextKit.local(at, zone).let { it.hour * 60 + it.minute }
        if (before != null && m < before) notes += "the user asked not to be called before ${Anchors.clock(before)}; ask whether to keep this time"
        if (after != null && m > after) notes += "the user asked not to be called after ${Anchors.clock(after)}; ask whether to keep this time"
        return if (notes.isEmpty()) "" else "IMPACT: " + notes.joinToString("; ") + "."
    }

    /** Same check for a daily time (minutes after midnight), against plans and the daily calls. */
    suspend fun checkDaily(minutes: Int, excludePlan: Long? = null): String =
        check(TextKit.nextDaily(minutes, env.clock.now(), zone), excludePlan = excludePlan)

    /** A habit stopped: its check-in calls go too. */
    suspend fun habitRemoved(habitId: Long): String {
        val linked = dao.scheduledReminders().filter { it.habitId == habitId }
        linked.forEach { dao.updateReminder(it.copy(status = ReminderStatus.CANCELLED)); env.scheduler.cancel(it.id) }
        return if (linked.isEmpty()) "" else " and cancelled ${linked.size} check-in call${if (linked.size == 1) "" else "s"} for it"
    }
}

/**
 * Topic playbooks: the questions a careful assistant asks after a request, so "track my medicine at 1 after lunch"
 * leads to which medicine, food, whether to note the time taken, and BP or sugar readings when it is for those.
 */
object Playbooks {
    private val bpMeds = Regex("\\b(amlodipine|telmisartan|losartan|olmesartan|metoprolol|atenolol|ramipril|enalapril|cilnidipine|nebivolol|hydrochlorothiazide|chlorthalidone|bp|blood pressure|hypertension|pressure)\\b", RegexOption.IGNORE_CASE)
    private val sugarMeds = Regex("\\b(metformin|glimepiride|gliclazide|sitagliptin|vildagliptin|teneligliptin|insulin|dapagliflozin|empagliflozin|voglibose|sugar|diabet\\w*)\\b", RegexOption.IGNORE_CASE)
    private val thyroidMeds = Regex("\\b(thyroxine|levothyroxine|eltroxin|thyronorm|thyroid)\\b", RegexOption.IGNORE_CASE)

    fun measureFor(medicine: String, purpose: String): String? {
        val t = "$medicine $purpose"
        return when { bpMeds.containsMatchIn(t) -> MeasureKind.BP; sugarMeds.containsMatchIn(t) -> MeasureKind.SUGAR; else -> null }
    }

    /** Questions after a medicine plan is set, skipping what is already known. */
    suspend fun afterMedicine(env: AgentEnv, p: PlanSpec, alreadyMeasuring: Boolean): String {
        val qs = mutableListOf<String>()
        if (p.food.isBlank()) qs += "Before or after food?"
        if (thyroidMeds.containsMatchIn(p.what) && p.food.isBlank()) qs[qs.size - 1] = "Did the doctor say to take it on an empty stomach, before breakfast?"
        if (!p.trackTime) qs += "Should I note the time you actually take it each day, in case the doctor asks?"
        val m = measureFor(p.what, p.purpose)
        if (m != null && !alreadyMeasuring) qs += "Since it's for ${MeasureWords.name(m)}, do you want me to track your ${MeasureWords.name(m)} readings too? When did the doctor ask you to measure it?"
        if (p.until > 0) qs += "Should I check with you on the last day whether the doctor wants you to continue?"
        if (env.dao.facts().none { it.key == "people.doctor" }) qs += "Which doctor prescribed it?"
        return if (qs.isEmpty()) "" else "FOLLOW-UP: ask one at a time, skip any the user already answered: " + qs.joinToString(" ")
    }

    fun afterMeasure(kind: String): String =
        "FOLLOW-UP: ask whether the doctor wants the readings at a particular time (like fasting, or before breakfast), and whether to share a weekly summary before the next visit."

    fun afterHabit(name: String): String = "FOLLOW-UP: ask what time Cue should call each day to ask about $name, and set it with habit_time."
}

/** Facts worth keeping, picked out of what the user said with plain rules. They start unconfirmed. */
object Learner {
    private val patterns: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\bi(?:'m| am) allergic to ([\\w ,-]{3,40})") to FactCategory.HEALTH,
        Regex("(?i)\\bi(?:'m| am) (?:a )?(vegetarian|vegan|diabetic|pregnant|eggetarian|jain)\\b") to FactCategory.HEALTH,
        Regex("(?i)\\bi have (diabetes|high bp|high blood pressure|thyroid|asthma|sugar|bp|migraines?|pcod|pcos)\\b") to FactCategory.HEALTH,
        Regex("(?i)\\bmy (doctor|dentist) is (?:dr\\.? )?([a-z]+(?: [a-z]+)?)") to FactCategory.PEOPLE,
        Regex("(?i)\\bmy (wife|husband|mom|mother|dad|father|son|daughter|brother|sister|boss|manager)(?:'s name)? is ([a-z]+)") to FactCategory.PEOPLE,
        Regex("(?i)\\bi (?:usually |normally |always )?(?:have|eat|take) (breakfast|lunch|dinner) (?:at|around|by) (\\d{1,2}(?:[:.]\\d{2})?(?:\\s*(?:am|pm))?|noon|[a-z]+(?:\\s*(?:am|pm))?)") to FactCategory.ROUTINE,
        Regex("(?i)\\bi (?:usually |normally |always )?(?:wake up|get up) (?:at|around|by) (\\d{1,2}(?:[:.]\\d{2})?(?:\\s*(?:am|pm))?|noon|[a-z]+(?:\\s*(?:am|pm))?)") to FactCategory.ROUTINE,
        Regex("(?i)\\bi (?:usually |normally |always )?(?:sleep|go to bed|go to sleep) (?:at|around|by) (\\d{1,2}(?:[:.]\\d{2})?(?:\\s*(?:am|pm))?|noon|[a-z]+(?:\\s*(?:am|pm))?)") to FactCategory.ROUTINE,
        Regex("(?i)\\b(?:don'?t|do not|never) (?:call|ring) me (before|after|during) ([\\w: ]{2,20})") to FactCategory.PREFERENCE,
        Regex("(?i)\\bi prefer ([\\w ,'-]{3,50})") to FactCategory.PREFERENCE,
        Regex("(?i)\\bi work (?:at|as|in) ([\\w ,&'-]{2,40})") to FactCategory.ABOUT,
    )

    fun candidates(lines: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (line in lines) for ((re, cat) in patterns) {
            val m = re.find(line) ?: continue
            out += cat to m.value.trim().trimEnd('.', ',', '!').replaceFirstChar { it.uppercase() }
        }
        return out.distinctBy { it.second.lowercase(Locale.ROOT) }
    }
}

/**
 * "Promised work": if Cue said it would remind, call or check back and no tool set it up, plain code catches it
 * after the call. A promise with a time becomes a follow-up; one without a time becomes a notification asking for one.
 */
object PromiseCheck {
    private val promise = Regex("(?i)\\bI(?:'ll| will| am going to|'m going to) (remind|call|ring|check(?: in)?(?: with you)?|follow up|ask)(?: you)?(?: (?:about|on|to|if|whether|again))? ?(.{0,80})")
    private val scheduling = setOf("reminder_set", "dose_schedule", "follow_up", "measure_schedule", "checkin_time", "renewal_remind", "practice_schedule", "rule_add", "reminder_move")

    data class Broken(val sentence: String, val about: String)

    fun broken(cueLines: List<String>, changes: List<String>): List<Broken> {
        if (changes.any { c -> scheduling.any { c.startsWith("$it:") } }) return emptyList()
        return cueLines.mapNotNull { line ->
            val m = promise.find(line) ?: return@mapNotNull null
            if (Regex("(?i)\\bif you (miss|forget)").containsMatchIn(line)) return@mapNotNull null
            Broken(m.value.trim(), m.groupValues[2].trim().trimEnd('.', '!', '?'))
        }.distinctBy { it.about }
    }
}

/** "When I reach the office, remind me to..." Plain code matches the trigger; limits stop it nagging. */
class StandingRules(private val env: AgentEnv) {
    private val dao get() = env.dao

    suspend fun add(trigger: String, match: String, text: String, repeat: Boolean): StandingRule {
        val now = env.clock.now()
        val r = StandingRule(trigger = trigger, match = match.trim(), text = text.trim().trimEnd('.').replaceFirstChar { it.uppercase() },
            maxFires = if (repeat) 0 else 1, expiresAt = now + 90L * 86_400_000, createdAt = now)
        return r.copy(id = dao.insertRule(r))
    }

    /** Rules for this trigger whose subject matches (a place label or kind, a person); marks them fired. */
    suspend fun fire(trigger: String, vararg subjects: String): List<StandingRule> {
        val now = env.clock.now()
        val hits = dao.activeRules().filter { r ->
            r.trigger == trigger && (r.expiresAt == null || r.expiresAt!! > now) &&
                (r.lastFiredAt == null || now - r.lastFiredAt!! > 12 * 3_600_000L) &&
                (r.match.isBlank() || subjects.any { s -> s.isNotBlank() && (s.contains(r.match, true) || r.match.contains(s, true) || TextKit.similarity(s, r.match) >= 0.6) })
        }
        for (r in hits) {
            val fires = r.fires + 1
            dao.updateRule(r.copy(fires = fires, lastFiredAt = now, active = r.maxFires == 0 || fires < r.maxFires))
            env.log("rule #${r.id} fired on $trigger: ${r.text}")
        }
        return hits
    }

    fun describe(r: StandingRule): String {
        val whenText = when (r.trigger) {
            RuleTrigger.ARRIVE -> "when you reach ${r.match.ifBlank { "a saved place" }}"
            RuleTrigger.LEAVE -> "when you leave ${r.match.ifBlank { "a saved place" }}"
            RuleTrigger.MESSAGE -> "when ${r.match.ifBlank { "someone" }} messages you"
            RuleTrigger.MORNING -> "on the morning call"
            RuleTrigger.NIGHT -> "on the night call"
            else -> r.trigger
        }
        return "#${r.id} $whenText: ${r.text}${if (r.maxFires == 1) " (once)" else ""}"
    }
}

/** Shared by the tools and the summary screen: a plan in a short line. */
fun planLine(item: Item): String {
    val p = PlanSpec.of(item)
    val times = p.times.joinToString(" and ") { Anchors.clock(it) }
    return if (p.kind == PlanKind.MEASURE) "#${item.id} ${MeasureWords.name(p.measure)} at $times"
    else "#${item.id} ${p.what} at $times" + (if (p.food.isNotBlank()) ", ${p.food} food" else "") +
        (if (p.anchor.isNotBlank()) " (follows ${Anchors.label(p.anchor)})" else "")
}

/** A one-off "I'll check back" ring. */
suspend fun scheduleFollowUp(env: AgentEnv, about: String, at: Long, itemId: Long? = null): Long {
    val r = Reminder(kind = ReminderKind.FOLLOW_UP, text = about.trim().trimEnd('.').replaceFirstChar { it.uppercase() }, fireAt = at, surface = Surface.RING,
        itemId = itemId, createdAt = env.clock.now())
    val id = env.dao.insertReminder(r)
    env.scheduler.schedule(r.copy(id = id))
    return id
}
