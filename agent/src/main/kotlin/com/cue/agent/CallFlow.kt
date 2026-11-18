package com.cue.agent

import com.cue.brain.Brain
import com.cue.brain.BrainSession
import com.cue.brain.SessionConfig
import com.cue.brain.ToolLoop
import com.cue.brain.TurnInput
import com.cue.domain.CallSession
import com.cue.domain.ReminderKind
import com.cue.domain.ReminderStatus
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch

data class CallReply(val spoken: String, val endCall: Boolean, val audio: List<ShortArray> = emptyList(), val heard: String? = null, val acted: Boolean = false,
    val streamed: Boolean = false, val card: CallCard? = null)

/** What a live two-way call tells the screen (see [CallFlow.startDuplex]). Called from network threads. */
interface DuplexUi {
    fun onReady() {}
    fun onUserText(text: String, final: Boolean) {}
    fun onModelText(delta: String) {}
    fun onAudio(pcm: ShortArray) {}
    fun onInterrupted() {}
    fun onTurnComplete() {}
    fun onCard(card: CallCard) {}
    /** The model said goodbye and asked to hang up. */
    fun onEndRequested() {}
    fun onClosed(reason: String?) {}
}

/** Live updates during a turn, for the call screen: what was heard, the answer's text so far, its speech. */
interface TurnListener {
    fun onHeard(text: String) {}
    fun onPartial(text: String) {}
    fun onAudio(pcm: ShortArray) {}
}

/**
 * One conversation: a ring-first call or a Talk-to-Cue session. The call screen drives it with what the user said;
 * this class picks the brain, runs the shared tool loop, keeps the transcript and files everything at the end.
 */
class CallFlow internal constructor(
    private val engine: CueEngine,
    val kind: String,
    val reminderId: Long?,
    var albumId: Long?,
    val callId: Long,
) {
    private val executor = CueToolExecutor(engine, CallContext(callId, kind, albumId, reminderId, habitName(kind)))
    private val loop = ToolLoop(executor)
    private var brain: Brain = engine.router.rules
    private var session: BrainSession? = null
    private var config: SessionConfig? = null
    /** The live two-way session when the brain has one (a realtime cloud model). */
    var duplex: com.cue.brain.DuplexSession? = null
        private set
    private val transcript = mutableListOf<Pair<String, String>>()
    private val userLines = mutableListOf<String>()
    private val unhandled = mutableListOf<String>()
    private val practiceNotes = mutableListOf<String>()
    var turns = 0
        private set
    var ended = false
        private set

    val usingModel: Boolean get() = brain.name != "rules"
    val brainName: String get() = brain.name
    val hearsAudio: Boolean get() = brain.capabilities.hearsAudio

    private fun habitName(kind: String): String? = null

    suspend fun opening(): String {
        val reminder = reminderId?.let { engine.dao.reminder(it) }
        if (reminder?.habitId != null) executor.context = executor.context.copy(habitName = engine.dao.habit(reminder.habitId!!)?.name)
        val text = engine.briefings.opening(kind, reminderId)
        brain = pickBrain()
        val cfg = SessionConfig(
            mode = kind,
            rules = rules(kind),
            context = engine.briefings.contextPack(kind) + "\nOpening: $text",
            tools = if (brain.capabilities.maxTools >= 15) CueTools.live else if (brain.capabilities.maxTools >= 10) CueTools.converse else CueTools.compact,
            spokenReplies = true,
        )
        config = cfg
        session = brain.openSession(cfg)
        transcript += "cue" to text
        persist()
        return text
    }

    private suspend fun pickBrain(): Brain {
        val wanted = engine.router.forJob("converse")
        if (wanted.name == "rules") return wanted
        if (wanted.isReady()) return wanted
        // The cloud brain needs a network: without one, the on-device model if it is loaded, else the rules.
        if (wanted.capabilities.needsNetwork) {
            engine.log("${wanted.name} not reachable; working offline")
            return engine.router.named("lfm3")?.takeIf { it.isReady() } ?: engine.router.rules
        }
        // The model is still loading (the app was just opened): wait for it rather than spend the call on rules.
        val ready = kotlinx.coroutines.withTimeoutOrNull(30_000) { wanted.prepare() } == true
        return if (ready) wanted else engine.router.rules
    }

    private fun rules(kind: String): String = buildString {
        append("You are Cue, a warm phone assistant on a call. Reply in one or two short sentences. ")
        append("Use tools to save lists, notes and reminders the user mentions. Never invent facts that are not in the context. ")
        append(WORKFLOW_RULES)
        if (kind == ReminderKind.PRACTICE) append("This is English speaking practice: keep the conversation going and gently correct one mistake per turn. ")
    }

    suspend fun userSaid(text: String, listener: TurnListener? = null): CallReply = turn(TurnInput.Text(text, facts()), text, listener)

    suspend fun userAudio(pcm16k: ShortArray, listener: TurnListener? = null): CallReply = turn(TurnInput.Audio(pcm16k, facts()), null, listener)

    /** A quick transcript of what the user is still saying (live captions); null when the brain can't. */
    suspend fun preview(pcm16k: ShortArray): String? = session?.preview(pcm16k)

    fun cancelPreview() { session?.cancelPreview() }

    /** What is true right now, sent with every turn so the model's answer matches the screen. */
    private suspend fun facts(): String = buildString {
        val albumId = albumId ?: engine.activeAlbum()?.id
        val lists = engine.dao.itemsOfType(com.cue.domain.ItemType.CHECKLIST)
        val list = lists.firstOrNull { it.albumId == albumId && albumId != null } ?: lists.firstOrNull()
        if (list != null) {
            val entries = engine.dao.entries(list.id)
            val open = entries.filter { !it.checked }.map { it.text }
            val done = entries.filter { it.checked }.map { it.text }
            append("${list.title} list: ").append(if (open.isEmpty()) "nothing left" else open.joinToString(", "))
            if (done.isNotEmpty()) append(" (done: ${done.joinToString(", ")})")
            append(". ")
        }
        val changed = executor.changes.takeLast(4)
        append(if (changed.isEmpty()) "Nothing has been changed on this call." else "Changed on this call: " + changed.joinToString("; ") { it.substringAfter(": ") } + ".")
    }

    /**
     * Starts a live two-way conversation if the brain has one: the brain hears, decides turns, handles interruptions
     * and calls the tools itself (through the same checked executor). Returns false when the call goes turn by turn.
     */
    fun startDuplex(ui: DuplexUi): Boolean {
        val cfg = config ?: return false
        var turnActs = 0
        var modelText = StringBuilder()
        var lastUser: String? = null
        // The same call repeated within a few seconds is the model repeating itself: answer it, don't do it twice.
        val recent = HashMap<String, Pair<Long, com.cue.brain.ToolResult>>()
        val d = brain.openDuplex(cfg, object : com.cue.brain.DuplexListener {
            override fun onReady() = ui.onReady()
            override fun onUserText(text: String, final: Boolean) {
                ui.onUserText(text, final)
                if (final) {
                    lastUser?.let { if (turnActs == 0) unhandled += it }
                    lastUser = text; turnActs = 0; turns++
                    userLines += text; transcript += "user" to text
                }
            }
            override fun onModelText(delta: String) { modelText.append(delta); ui.onModelText(delta) }
            override fun onAudio(pcm24k: ShortArray) = ui.onAudio(pcm24k)
            override fun onInterrupted() = ui.onInterrupted()
            override fun onTurnComplete() {
                val said = modelText.toString().trim()
                if (said.isNotEmpty()) { transcript += "cue" to said; CueRuntime.scope.launch { persist() } }
                modelText = StringBuilder()
                ui.onTurnComplete()
            }
            override suspend fun onToolCall(call: com.cue.brain.ToolCall): com.cue.brain.ToolResult {
                val key = call.name + call.args.toSortedMap().toString().lowercase()
                recent[key]?.takeIf { System.currentTimeMillis() - it.first < 8_000 }?.let {
                    engine.log("live tool ${call.name} ${call.args} repeated; not done twice")
                    return it.second
                }
                return run(call).also { if (it.ok) recent[key] = System.currentTimeMillis() to it }
            }

            private suspend fun run(call: com.cue.brain.ToolCall): com.cue.brain.ToolResult {
                // Hang up only after the user said goodbye: the model sometimes calls end_call right after greeting.
                if (call.name == "end_call") {
                    val last = (lastUser ?: userLines.lastOrNull()).orEmpty().lowercase()
                    val bye = Regex("\\b(bye|goodbye|good night|that'?s (all|it)|nothing else|talk (to you )?later|see you|i'?m done|we'?re done|hang up|end the call|that will be all)\\b").containsMatchIn(last)
                    if (!bye) {
                        engine.log("live end_call refused: the user hasn't said goodbye ('$last')")
                        return com.cue.brain.ToolResult("end_call", false, "The user hasn't said goodbye. Don't hang up; keep listening.")
                    }
                }
                // Plain code checks the model: a list entry is only ticked or removed if the user named it.
                if (call.name in setOf("checklist_tick", "checklist_remove", "list_delete", "habit_remove", "forget")) {
                    val entry = call.args["entry"] ?: call.args["list"] ?: call.args["habit"] ?: call.args["words"] ?: ""
                    val said = (userLines.takeLast(2) + (lastUser ?: "")).joinToString(" ")
                    val named = entry.isBlank() || TextKit.number(entry) != null || entry.trim().lowercase() in setOf("done", "all done", "ticked", "completed", "checked") ||
                        entry.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.any { w -> said.lowercase().contains(w) }
                    if (!named) {
                        engine.log("live tool ${call.name} $entry refused: the user didn't name it ('$said')")
                        return com.cue.brain.ToolResult(call.name, false, "The user didn't say \"$entry\". Don't change the list; ask them which entry they mean.")
                    }
                }
                executor.card = null
                val r = executor.execute(call)
                if (r.ok) turnActs++
                executor.card?.let { ui.onCard(it) }
                if (call.name == "end_call") ui.onEndRequested()
                engine.log("live tool ${call.name} ${call.args} -> ${r.ok} ${r.message}")
                return r
            }
            override fun onClosed(reason: String?) = ui.onClosed(reason)
        }) ?: return false
        duplex = d
        engine.log("call is live on ${brain.name}")
        return true
    }

    /** The user talked over Cue: the brain stops the answer it is generating. */
    fun interrupt() { session?.interrupt() }

    /** A fixed line (the opening, a nudge) in the brain's own voice when it has one; [CallReply.streamed] tells. */
    suspend fun say(text: String, listener: TurnListener? = null): CallReply {
        val s = session ?: return CallReply(text, false)
        var streamed = false
        val outcome = loop.run(s, TurnInput.Say(text), feedResults = false, onAudio = listener?.let { l -> { pcm: ShortArray -> streamed = true; l.onAudio(pcm) } })
        return CallReply(outcome.spoken.ifBlank { text }, false, outcome.audio, streamed = streamed)
    }

    private suspend fun turn(input: TurnInput, typed: String?, listener: TurnListener? = null): CallReply {
        val s = session ?: return CallReply("Sorry, one second.", false)
        turns++
        val before = executor.changes.size
        executor.card = null
        var streamed = false
        val outcome = loop.run(s, input, feedResults = true,
            onAudio = listener?.let { l -> { pcm: ShortArray -> streamed = true; l.onAudio(pcm) } },
            onEvent = listener?.let { l -> { ev: com.cue.brain.BrainEvent ->
                when (ev) { is com.cue.brain.BrainEvent.Heard -> l.onHeard(ev.text); is com.cue.brain.BrainEvent.Partial -> l.onPartial(ev.text); else -> Unit }
            } })
        // A recording with no words in it is silence, not a turn.
        if (input is TurnInput.Audio && outcome.heard == null) { turns--; return CallReply("", false) }
        val heard = typed ?: outcome.heard
        heard?.let { if (!outcome.endCall) userLines += it; transcript += "user" to it }
        val acted = executor.changes.size > before
        if (heard != null && !acted && !outcome.endCall) unhandled += heard
        if (kind == ReminderKind.PRACTICE && outcome.spoken.contains("say", ignoreCase = true)) practiceNotes += outcome.spoken
        val spoken = outcome.spoken.ifBlank { if (outcome.failed != null) "Sorry, I lost that. Could you say it again?" else "Okay." }
        transcript += "cue" to spoken
        persist()
        engine.log("turn[$kind] ${brain.name}: '${heard ?: "(audio)"}' -> '$spoken' tools=${outcome.toolResults.size}")
        return CallReply(spoken, outcome.endCall, outcome.audio, heard, acted, streamed, executor.card)
    }

    private suspend fun persist() {
        val call = engine.dao.call(callId) ?: return
        val arr = JSONArray()
        transcript.forEach { (who, t) -> arr.put(JSONObject().put("who", who).put("text", t)) }
        engine.dao.updateCall(call.copy(transcript = arr.toString()))
    }

    /** Hang-up: file the call, close the brain session, mark the reminder done, and plan what comes next. */
    suspend fun end(): List<String> {
        if (ended) return executor.changes
        ended = true
        session?.close()
        duplex?.close()
        val now = engine.clock.now()
        engine.dao.call(callId)?.let { engine.dao.updateCall(it.copy(endedAt = now, outcome = "ANSWERED")) }
        runCatching { afterCall() }.onFailure { engine.log("after-call checks failed: ${it.message}") }
        runCatching { Filing(engine).file(kind, userLines, unhandled, executor.changes, albumId ?: engine.activeAlbum()?.id, practiceNotes) }
            .onSuccess { executor.changes += it }
            .onFailure { engine.log("filing failed: ${it.message}") }
        engine.dao.call(callId)?.let { engine.dao.updateCall(it.copy(filed = true)) }
        reminderId?.let { id ->
            engine.dao.reminder(id)?.let { r ->
                if (r.status == ReminderStatus.FIRED || r.status == ReminderStatus.SCHEDULED) engine.dao.updateReminder(r.copy(status = ReminderStatus.DONE))
            }
        }
        engine.planner.reconcile()
        engine.afterCall(kind)
        return executor.changes.toList()
    }

    fun changes(): List<String> = executor.changes.toList()

    /** Facts Cue picked up on this call that the user hasn't confirmed yet: the "What Cue learned" card. */
    var learned: List<com.cue.domain.ProfileFact> = emptyList()
        private set

    /** After the call: catch promises no tool kept, and pick up facts worth remembering (unconfirmed). */
    private suspend fun afterCall() {
        val cueLines = transcript.filter { it.first == "cue" }.drop(1).map { it.second }
        for (b in PromiseCheck.broken(cueLines, executor.changes)) {
            val at = TextKit.parseWhen(b.about, engine.clock.now(), engine.clock.zone)
            if (at != null && at > engine.clock.now()) {
                val id = scheduleFollowUp(engine, b.about.substringBefore(" at ").substringBefore(" tomorrow").ifBlank { "what we talked about" }, at)
                executor.changes += "follow_up: #$id kept a promise from the call (\"${b.sentence}\")"
            } else {
                engine.surfaces.notify("Cue said it would follow up", "\"${b.sentence}\" No time was set. Open Cue to set one.", "promise")
                executor.changes += "promise_check: no time for \"${b.sentence}\"; asked you in a notification"
            }
            engine.log("promise check: ${b.sentence}")
        }
        val profile = Profile(engine)
        val got = mutableListOf<com.cue.domain.ProfileFact>()
        for ((cat, text) in Learner.candidates(userLines)) {
            val known = engine.dao.facts().any { TextKit.similarity(it.text, text) >= 0.7 }
            if (known) continue
            got += profile.remember(text, cat, source = "learned", confirmed = false).fact
        }
        learned = got
        if (got.isNotEmpty()) executor.changes += "learned: ${got.size} thing${if (got.size == 1) "" else "s"} about you to keep or remove"
    }
    fun transcript(): List<Pair<String, String>> = transcript.toList()
}

/** How Cue works through a request; shared by every brain's prompt. */
const val WORKFLOW_RULES = "Before acting, check what you already know (the profile and context below) and use it instead of asking again. " +
    "Never fill in a detail the user didn't give: if a tool answers NEEDS, ask the user its ASK question in your own words, then call the tool again with the answer. " +
    "If a tool answers CONFIRM, ask exactly that and call again with confirm=yes only after a clear yes. " +
    "If a result has IMPACT, tell the user in a few words what else changed or clashes. " +
    "If a result has FOLLOW-UP, ask those questions one at a time over the next turns, skipping any already answered, and save each answer with the matching tool. " +
    "When the user tells you something lasting about themselves (a routine, preference, health fact, a person), save it with remember. " +
    "Promised work: never say you'll remind, call, check back or follow up unless you set it up with a tool in the same turn (reminder_set, follow_up, rule_add). " +
    "For health, never diagnose; for worrying readings, suggest the doctor. "

internal fun newCallSession(kind: String, reminderId: Long?, now: Long) = CallSession(kind = kind, reminderId = reminderId, ringAt = now, answeredAt = now, outcome = "IN_CALL")
