package com.cue.brain.rules

import com.cue.brain.Brain
import com.cue.brain.BrainCapabilities
import com.cue.brain.BrainEvent
import com.cue.brain.BrainSession
import com.cue.brain.FieldsRequest
import com.cue.brain.FieldsResult
import com.cue.brain.SessionConfig
import com.cue.brain.ToolCall
import com.cue.brain.ToolResult
import com.cue.brain.TurnInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/**
 * The guaranteed brain: a deterministic intent parser. Instant, offline, never wrong about what it did, and it
 * speaks through the same tool contract as the on-device model. The demo always works on it.
 */
class RulesBrain : Brain {
    override val name = "rules"
    override val capabilities = BrainCapabilities(contextTokens = 100_000, maxTools = 12, hearsAudio = false, speaks = false, needsNetwork = false, prepareMillis = 0)
    override suspend fun prepare() = true
    override fun isReady() = true
    override fun openSession(config: SessionConfig): BrainSession = RulesSession(config.mode)
    override suspend fun release() = Unit

    override suspend fun complete(request: FieldsRequest): FieldsResult {
        val sentences = request.text.split('\n', '.').map { it.trim() }.filter { it.length > 3 }
        val journal = sentences.take(3).joinToString(". ") { it.replaceFirstChar { c -> c.uppercase() } }.let { if (it.isEmpty()) it else "$it." }
        return FieldsResult(journal.isNotEmpty(), listOf("JOURNAL" to journal), journal)
    }
}

class RulesSession(private val mode: String) : BrainSession {
    private var practiceTurn = 0
    private var lastIntentEnds = false

    override fun send(input: TurnInput): Flow<BrainEvent> = flow {
        when (input) {
            is TurnInput.Text -> {
                val parsed = Intents.parse(input.text, mode, practiceTurn)
                if (mode == "PRACTICE") practiceTurn++
                lastIntentEnds = parsed.end
                if (parsed.calls.isEmpty()) {
                    emit(BrainEvent.Text(parsed.reply))
                    emit(BrainEvent.Done(parsed.end))
                } else {
                    parsed.calls.forEach { emit(BrainEvent.ToolCallRequested(it)) }
                    emit(BrainEvent.Done(false))
                }
            }
            is TurnInput.ToolResults -> {
                emit(BrainEvent.Text(Intents.replyFor(input.results, mode)))
                emit(BrainEvent.Done(lastIntentEnds || Intents.singleQuestion(mode)))
            }
            is TurnInput.Audio -> emit(BrainEvent.Failed("the rules brain needs text"))
        }
    }

    override fun close() = Unit
}

data class Parsed(val calls: List<ToolCall>, val reply: String, val end: Boolean = false)

object Intents {
    private fun call(name: String, vararg kv: Pair<String, String>) = ToolCall(name, mapOf(*kv))

    fun singleQuestion(mode: String) = mode in setOf("DOSE", "CARE", "ACCOUNTABILITY", "LEAVE_BY", "EXPIRY")

    private val yes = Regex("^(yes|yeah|yep|yup|haan|ha|done|did|i did|i have|i went|i took|taken|of course|sure|ok|okay)\\b", RegexOption.IGNORE_CASE)
    private val no = Regex("^(no|nope|nahi|not yet|i didn'?t|i did not|i haven'?t|missed|skipped)\\b", RegexOption.IGNORE_CASE)
    private val bye = Regex("^(that'?s all|that is all|nothing( else)?|no,? that'?s it|that'?s it|bye|good ?night|thanks|thank you|ok(ay)? bye|done for (today|now)|nothing more|all good)\\b", RegexOption.IGNORE_CASE)

    fun parse(raw: String, mode: String, practiceTurn: Int): Parsed {
        val text = raw.trim().trimEnd('.', '!')
        val lower = text.lowercase(Locale.ROOT)
        if (lower.isEmpty()) return Parsed(emptyList(), "Sorry, I didn't catch that.")

        if (mode == "PRACTICE") return Practice.reply(text, practiceTurn)

        when (mode) {
            "DOSE", "CARE" -> {
                if (yes.containsMatchIn(lower)) return Parsed(listOf(call("dose_taken", "done" to "yes")), "", end = true)
                if (no.containsMatchIn(lower)) return Parsed(listOf(call("dose_taken", "done" to "no")), "", end = true)
            }
            "ACCOUNTABILITY" -> {
                if (yes.containsMatchIn(lower) || Regex("\\b(went|did it|finished|completed)\\b").containsMatchIn(lower))
                    return Parsed(listOf(call("habit_checkin", "habit" to "", "done" to "yes")), "", end = true)
                if (no.containsMatchIn(lower)) return Parsed(listOf(call("habit_checkin", "habit" to "", "done" to "no")), "", end = true)
            }
            "EXPIRY" -> {
                if (yes.containsMatchIn(lower)) return Parsed(listOf(call("renewal_remind")), "", end = true)
                if (no.containsMatchIn(lower)) return Parsed(emptyList(), "Alright. It's saved in your album whenever you need it.", end = true)
            }
        }
        if (bye.containsMatchIn(lower)) {
            val r = when (mode) {
                "CHECKIN_NIGHT" -> "Got it. Everything's saved. Good night!"
                "CHECKIN_MORNING" -> "Have a great day!"
                else -> "Okay, bye!"
            }
            return Parsed(emptyList(), r, end = true)
        }

        val calls = mutableListOf<ToolCall>()
        for (clause in clauses(text)) calls += clauseCalls(clause)
        if (calls.isNotEmpty()) return Parsed(calls, "")

        return Parsed(emptyList(), when (mode) {
            "CHECKIN_NIGHT" -> "Got it, that goes in today's journal. Anything else for tomorrow?"
            "CHECKIN_MORNING" -> "Okay. Anything else?"
            "TALK" -> "I can add to a list, set a reminder, remember a note, or find something you saved. What would you like?"
            else -> "Okay."
        })
    }

    /** Splits "pack charger and passport, and remember the hotel is near CP" into two clauses. */
    private fun clauses(text: String): List<String> =
        text.split(Regex("(?i)(?:,|\\.|;)?\\s*\\b(?:and|also)\\s+(?=remember|remind|note|call me|ring me|add|pack|my flight|i owe|tick)|[.;]\\s+"))
            .map { it.trim().trim(',') }.filter { it.isNotBlank() }

    private fun clauseCalls(c: String): List<ToolCall> {
        val l = c.lowercase(Locale.ROOT)
        // Flight moved.
        Regex("(?:flight|it|departure)\\b.*?\\b(?:moved|changed|is now|delayed|rescheduled|pushed|shifted)\\b(?:\\s+(?:to|at|till))?\\s+(.+)").find(l)?.let {
            return listOf(call("event_move", "when" to it.groupValues[1]))
        }
        // Medicine schedule.
        Regex("(?:take|taking|have)\\s+(?:this|it|the medicine|(.+?))\\s+(once|twice|thrice|\\w+ times|\\d+ times)\\s+(?:a|per|every)\\s+day(?:\\s+for\\s+(\\w+)\\s+days?)?").find(l)?.let { m ->
            val perWord = m.groupValues[2]
            val per = when { perWord == "once" -> "1"; perWord == "twice" -> "2"; perWord == "thrice" -> "3"; else -> perWord.substringBefore(" ") }
            return listOf(call("dose_schedule", "medicine" to m.groupValues[1].trim(), "per_day" to per, "days" to m.groupValues[3].ifBlank { "5" }))
        }
        // Accountability and reminders.
        Regex("(?:call|ring|remind)\\s+me\\s+((?:at|in|tomorrow|tonight|by)\\b[^,]*?)\\s+(?:and|to)\\s+(check|make sure|ask)\\s+(?:if |whether |that )?(.+)").find(l)?.let { m ->
            return listOf(call("reminder_set", "text" to "check if ${m.groupValues[3]}", "when" to m.groupValues[1], "check" to "yes"))
        }
        Regex("(?:remind|call|ring)\\s+me\\s+(?:to\\s+)?(.+?)\\s+((?:at|in|tomorrow|tonight|by)\\b.+)").find(l)?.let { m ->
            return listOf(call("reminder_set", "text" to m.groupValues[1].removePrefix("to "), "when" to m.groupValues[2]))
        }
        Regex("(?:remind|call|ring)\\s+me\\s+((?:at|in|tomorrow|tonight)\\b[^,]*?)\\s+(?:to|about)\\s+(.+)").find(l)?.let { m ->
            return listOf(call("reminder_set", "text" to m.groupValues[2], "when" to m.groupValues[1]))
        }
        Regex("make sure i\\s+(.+?)(\\s+(?:tonight|today|tomorrow|at .+))?$").find(l)?.let { m ->
            val whenP = m.groupValues[2].trim().ifBlank { "tonight" }.let { if (it == "tonight" || it == "today") "9 pm tonight" else it }
            return listOf(call("reminder_set", "text" to "check if you ${m.groupValues[1]}", "when" to whenP, "check" to "yes"))
        }
        // People memory.
        Regex("^(\\w+)\\s+(?:asked|wants|needs)\\s+(?:me\\s+)?(?:for|to)?\\s*(.+)").find(l)?.takeIf { it.groupValues[1] !in setOf("i", "he", "she", "they", "we", "it") }?.let { m ->
            val what = m.groupValues[2].let { if (Regex("^(send|share|call|pay|bring|book)").containsMatchIn(it)) it else "send $it" }
            return listOf(call("pending_add", "person" to m.groupValues[1], "text" to what))
        }
        Regex("i owe\\s+(\\w+)\\s+(.+)").find(l)?.let { m -> return listOf(call("pending_add", "person" to m.groupValues[1], "text" to "you owe ${m.groupValues[2]}")) }
        Regex("(?:sent|gave|paid|shared)\\s+(?:.+?\\s+)?(?:to\\s+)?(\\w+)$").find(l)?.takeIf { Regex("^i (sent|gave|paid|shared)").containsMatchIn(l) }?.let { m ->
            return listOf(call("pending_resolve", "person" to m.groupValues[1]))
        }
        // Search and journal.
        Regex("(?:where(?:'s| is| are)|find|show me|what was that|look for)\\s+(?:my\\s+|the\\s+)?(.+)").find(l)?.let { m ->
            return listOf(call("album_search", "query" to m.groupValues[1]))
        }
        Regex("what did i do\\s+(?:on\\s+)?(.+)").find(l)?.let { m -> return listOf(call("journal_lookup", "day" to m.groupValues[1])) }
        // Ticking.
        Regex("^(?:i(?:'ve| have)?|already)\\s+(?:packed|bought|got|finished|done|completed|kept|added)\\s+(?:the\\s+|my\\s+)?(.+)").find(l)?.let { m ->
            return TextList.split(m.groupValues[1]).map { call("checklist_tick", "entry" to it) }
        }
        Regex("^(?:tick|check)\\s+(?:off\\s+)?(?:the\\s+)?(.+)").find(l)?.let { m -> return listOf(call("checklist_tick", "entry" to m.groupValues[1])) }
        Regex("^(?:the\\s+|my\\s+)?(.+?)\\s+(?:is|are)\\s+(?:packed|done)$").find(l)?.let { m -> return listOf(call("checklist_tick", "entry" to m.groupValues[1])) }
        // Habits said in passing.
        Regex("i\\s+(?:went to|did)\\s+(?:the\\s+)?(gym|yoga|run|walk|workout)").find(l)?.let { m ->
            return listOf(call("habit_checkin", "habit" to m.groupValues[1], "done" to "yes"))
        }
        // Lists.
        Regex("(?:remember to\\s+|i need to\\s+|don'?t let me forget to\\s+)?(?:pack|bring|carry|buy|get)\\s+(.+)").find(l)?.takeIf { Regex("^(remember to |i need to |don'?t let me forget to )?(pack|bring|carry|buy|get)\\b").containsMatchIn(l) }?.let { m ->
            return listOf(call("checklist_add", "entries" to m.groupValues[1]))
        }
        Regex("add\\s+(.+?)\\s+to\\s+(?:my|the)\\s+(?:list|packing list|checklist)").find(l)?.let { m -> return listOf(call("checklist_add", "entries" to m.groupValues[1])) }
        // Notes.
        Regex("^(?:remember|note|keep in mind|save)\\s+(?:that\\s+)?(.+)").find(c)?.let { m -> return listOf(call("note_save", "text" to m.groupValues[1])) }
        Regex("^(?:the\\s+)?(hotel|address|meeting|pnr|booking|wifi|room)\\b.+").find(l)?.let { return listOf(call("note_save", "text" to c)) }
        return emptyList()
    }

    fun replyFor(results: List<ToolResult>, mode: String): String {
        val parts = results.map { r ->
            if (!r.ok) r.message.replaceFirstChar { it.uppercase() } + "."
            else when (r.name) {
                "checklist_add" -> "Added " + r.message.removePrefix("added ").lowercase(Locale.ROOT).split(", ").let { l -> if (l.size > 1) l.dropLast(1).joinToString(", ") + " and " + l.last() else l.first() } + "."
                "checklist_tick" -> "Ticked. " + r.message.substringAfter("; ").replaceFirstChar { it.uppercase() } + "."
                "note_save" -> "Noted."
                "reminder_set" -> r.message.replaceFirstChar { it.uppercase() } + "."
                "event_move" -> "Done, I've " + r.message + "."
                "dose_schedule" -> "Okay: " + r.message + ". If you miss one, I'll call you."
                "dose_taken" -> if (r.message.contains("taken")) "Great, marked as taken. Talk soon." else "Okay, noted. Please take it when you can."
                "habit_checkin" -> if (r.message.contains("streak is")) "Nice! " + r.message.replaceFirstChar { it.uppercase() } + "." else "No worries, tomorrow's a new day."
                "pending_add" -> "Got it, " + r.message + "."
                "pending_resolve" -> r.message.replaceFirstChar { it.uppercase() } + "."
                "album_search" -> "Found it: " + r.message + "."
                "journal_lookup" -> r.message
                "renewal_remind" -> "Okay, " + r.message + ". Talk soon."
                else -> r.message
            }
        }.distinct()
        val tail = when {
            singleQuestion(mode) -> ""
            mode == "CHECKIN_NIGHT" || mode == "CHECKIN_MORNING" -> " Anything else?"
            else -> ""
        }
        return parts.joinToString(" ") + tail
    }
}

object TextList {
    fun split(s: String): List<String> = s.replace(Regex("\\b(and|&)\\b"), ",").split(",").map { it.trim() }.filter { it.isNotBlank() }
}

/** Scripted English practice for when no model is available: spot common mistakes, then keep the chat going. */
object Practice {
    private val fixes = listOf(
        Regex("(?i)\\bi am going to (.+?) yesterday") to "I went to %1 yesterday",
        Regex("(?i)\\byesterday i go\\b") to "yesterday I went",
        Regex("(?i)\\bi have went\\b") to "I have gone",
        Regex("(?i)\\bi did went\\b") to "I went",
        Regex("(?i)\\b(he|she) don'?t\\b") to "%1 doesn't",
        Regex("(?i)\\bi am agree\\b") to "I agree",
        Regex("(?i)\\bmore better\\b") to "better",
        Regex("(?i)\\bsince (\\w+) (years|months|days)\\b") to "for %1 %2",
        Regex("(?i)\\bi am having (\\w+ )?(car|phone|laptop|house|bike)\\b") to "I have %1%2",
        Regex("(?i)\\bdo the needful\\b") to "please take care of it",
        Regex("(?i)\\bi go to (\\w+) yesterday\\b") to "I went to %1 yesterday",
        Regex("(?i)\\bwe was\\b") to "we were",
        Regex("(?i)\\bi didn'?t went\\b") to "I didn't go",
    )
    private val questions = listOf(
        "What was the best part of your day?",
        "What are you planning for this weekend?",
        "Tell me about a place you'd like to visit, and why.",
        "What did you eat for lunch today?",
        "Describe your work in two or three sentences.",
    )

    fun reply(text: String, turn: Int): Parsed {
        val l = text.lowercase(Locale.ROOT)
        if (Regex("^(bye|stop|that's all|end|enough)\\b").containsMatchIn(l)) return Parsed(emptyList(), "Good practice today. See you tomorrow!", end = true)
        for ((re, template) in fixes) {
            val m = re.find(text) ?: continue
            var fix = template
            m.groupValues.drop(1).forEachIndexed { i, g -> fix = fix.replace("%${i + 1}", g) }
            return Parsed(emptyList(), "Nice. A small fix: say \"$fix\" instead of \"${m.value}\". ${questions[turn % questions.size]}")
        }
        val praise = if (text.split(" ").size >= 6) "Well said." else "Good. Try a longer sentence this time."
        return Parsed(emptyList(), "$praise ${questions[turn % questions.size]}", end = turn >= 6)
    }
}
