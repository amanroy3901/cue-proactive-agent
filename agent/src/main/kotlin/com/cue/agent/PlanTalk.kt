package com.cue.agent

import com.cue.brain.FieldsRequest
import com.cue.domain.AlbumKind
import com.cue.domain.BlockType
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Planning as a short conversation: "I'm planning X" gets at most three quick questions, only ones whose answers
 * change what Cue builds, each with tap-to-answer choices and a Skip. Then the space is planned from the goal plus
 * the answers. Works for any goal, not only trips.
 */
data class PlanQuestion(val key: String, val ask: String, val options: List<String>, val multi: Boolean = false, val preselected: List<String> = emptyList())

object PlanTalk {
    const val MAX_QUESTIONS = 3

    /** The "what should I keep track of" choices and the blocks each one means. */
    val NEEDS: Map<String, List<String>> = linkedMapOf(
        "Money" to listOf(BlockType.EXPENSES),
        "Schedule" to listOf(BlockType.ITINERARY),
        "To-dos" to listOf(BlockType.CHECKLIST),
        "Documents" to listOf(BlockType.DOCUMENTS),
        "Photos" to listOf(BlockType.GALLERY),
        "People" to listOf(BlockType.PEOPLE),
        "Health" to listOf(BlockType.READINGS, BlockType.MEDICINES),
        "Notes" to listOf(BlockType.NOTES),
        "Journal" to listOf(BlockType.JOURNAL),
    )

    private val PLANNING = Regex(
        "\\b(i'?m|i am|we'?re|we are)\\s+(planning|organi[sz]ing|preparing|getting ready|moving|hosting|throwing|arranging)\\b" +
            "|\\bhelp me (plan|organi[sz]e|prepare|get ready)\\b|\\bplan(ning)? (a|an|my|our|the)\\b" +
            "|\\b(make|create|set up|start|build) (a |an |me a )?(new )?(space|page)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isPlanning(text: String): Boolean = PLANNING.containsMatchIn(text)

    /** The needs a template already covers, so the question starts with them ticked. */
    fun needsIn(spec: SpaceSpec): List<String> = NEEDS.filter { (_, types) -> spec.blocks.any { it.type in types } }.keys.toList()

    /**
     * Questions plain code can ask without a model: when (if no date was said), who (if nobody was named and it's
     * the kind of thing people share), and what to keep track of. Skips anything [goal] already answers.
     */
    fun ruleQuestions(goal: String, base: SpaceSpec): List<PlanQuestion> {
        val qs = mutableListOf<PlanQuestion>()
        if (base.start == null && base.kind != AlbumKind.ADMIN) qs += PlanQuestion("when", "When is it?", listOf("This weekend", "Next week", "Next month", "Not sure yet"))
        val named = base.blocks.any { it.people.isNotEmpty() } || Regex("\\b(with|for) [A-Z][a-z]+").containsMatchIn(goal)
        if (!named && base.kind in setOf(AlbumKind.TRIP, AlbumKind.EVENT, AlbumKind.MOVE, AlbumKind.GENERAL)) {
            qs += PlanQuestion("who", "Who's in it with you?", listOf("Just me", "Family", "Friends", "Partner"))
        }
        qs += trackQuestion(base)
        return qs.take(MAX_QUESTIONS)
    }

    fun trackQuestion(base: SpaceSpec): PlanQuestion {
        val pre = needsIn(base)
        val options = NEEDS.keys.filter { it in pre || it in setOf("Money", "Schedule", "To-dos", "Documents", "Photos") || (it == "Health" && base.kind == AlbumKind.HEALTH) }
        return PlanQuestion("track", "What should I keep track of?", options, multi = true, preselected = pre)
    }

    /**
     * Up to two goal-specific questions from the cloud model ("Are you hosting or a guest?", "Is the surgery for you
     * or someone else?"), then the keep-track-of question. Falls back to [ruleQuestions] offline or on a bad reply.
     */
    suspend fun questions(env: AgentEnv, goal: String, base: SpaceSpec): List<PlanQuestion> {
        val rules = ruleQuestions(goal, base)
        val brain = (env as? CueEngine)?.router?.forJob("file")
        if (brain == null || brain.name != "cloud") return rules
        val today = TextKit.local(env.clock.now(), env.clock.zone).toLocalDate()
        val raw = runCatching { brain.complete(FieldsRequest("space_questions", questionPrompt(today.format(DateTimeFormatter.ISO_LOCAL_DATE)), goal)) }.getOrNull()?.raw
        val model = raw?.let { parseQuestions(it) }.orEmpty()
        if (model.isEmpty()) return rules
        // When stays Cue's own question (the date matters most); then one question only this goal needs; then
        // who, if there's room; then what to keep track of.
        val whenQ = rules.filter { it.key == "when" }
        val whoQ = rules.filter { it.key == "who" }
        val middle = (model.take(1) + whoQ).take(MAX_QUESTIONS - 1 - whenQ.size)
        return whenQ + middle + trackQuestion(base)
    }

    /** The model's questions, cleaned: short text, 2 to 5 short options, no duplicates of the keep-track-of question. */
    fun parseQuestions(raw: String): List<PlanQuestion> {
        val a = raw.indexOf('{'); val z = raw.lastIndexOf('}')
        if (a < 0 || z <= a) return emptyList()
        val arr = runCatching { JSONObject(raw.substring(a, z + 1)).optJSONArray("questions") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ask = o.optString("ask").trim().take(70)
            val opts = o.optJSONArray("options")?.let { op -> (0 until op.length()).map { op.optString(it).trim().take(24) } }.orEmpty().filter { it.isNotBlank() }.distinct().take(5)
            if (ask.isBlank() || opts.size < 2 || Regex("(?i)keep track|track of").containsMatchIn(ask)) null
            else PlanQuestion(o.optString("key").ifBlank { "q$i" }.take(16), ask, opts)
        }.take(MAX_QUESTIONS - 1)
    }

    /** Names from the "who" answer ("Riya and Sam"); none for "Just me", "Family" or "Friends". */
    fun peopleIn(answers: List<Pair<PlanQuestion, String>>): List<String> =
        answers.filter { it.first.key == "who" }.flatMap { Spaces.people(it.second) }.filter { it.lowercase(Locale.ROOT) !in setOf("just me", "partner") }

    /** The goal with the answers folded in, in words the planner already reads ("with Riya", "next week"). */
    fun enrich(goal: String, answers: List<Pair<PlanQuestion, String>>): String {
        val sb = StringBuilder(goal.trim().trimEnd('.'))
        for ((q, a) in answers) {
            val ans = a.trim()
            if (ans.isBlank() || ans.equals("Skip", true) || ans.equals("Not sure yet", true)) continue
            when (q.key) {
                "when" -> sb.append(", ").append(ans.lowercase(Locale.ROOT))
                "who" -> if (!ans.equals("Just me", true)) sb.append(", with ").append(ans)
                "track" -> {}
                else -> sb.append(". ").append(q.ask.trimEnd('?')).append(": ").append(ans)
            }
        }
        return sb.toString()
    }

    /** The user's keep-track-of choices win: add blocks for picked needs, drop blocks for needs they left out. */
    fun applyNeeds(spec: SpaceSpec, picked: List<String>?, named: List<String> = emptyList()): SpaceSpec {
        if (picked == null) return spec
        val wanted = picked.flatMap { NEEDS[it].orEmpty() }.toSet()
        val offered = NEEDS.filterKeys { it !in picked }.values.flatten().toSet() - wanted
        var blocks = spec.blocks.filter { it.type !in offered }
        val people = (spec.blocks.flatMap { it.people } + named).distinctBy { it.lowercase(Locale.ROOT) }
        for (t in wanted) if (blocks.none { it.type == t }) {
            blocks = blocks + BlockSpec(t, Spaces.defaultTitle(t), people = if (t == BlockType.EXPENSES || t == BlockType.PEOPLE) people else emptyList())
        }
        if (blocks.isEmpty()) blocks = spec.blocks
        return spec.copy(blocks = blocks)
    }

    private fun questionPrompt(today: String) = """
        The user is planning something and Cue, their phone assistant, will build a page for it. Today is $today.
        Ask at most 2 short questions whose answers change what the page needs, and only what their words don't already say.
        Good: "Is this for you or someone else?" (surgery), "Hosting or attending?" (wedding), "Flying or driving?" (trip).
        Never ask when it is, who is in it, or what to keep track of: Cue asks those itself.
        Reply with JSON only: {"questions": [{"key": "short_id", "ask": "question, max 60 chars", "options": ["2 to 4 short answers"]}]}
        If nothing needs asking, reply {"questions": []}.
    """.trimIndent()
}
