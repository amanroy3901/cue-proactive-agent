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

data class CallReply(val spoken: String, val endCall: Boolean, val audio: List<ShortArray> = emptyList(), val heard: String? = null, val acted: Boolean = false, val streamed: Boolean = false)

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
        session = brain.openSession(SessionConfig(
            mode = kind,
            rules = rules(kind),
            context = engine.briefings.contextPack(kind) + "\nOpening: $text",
            tools = if (brain.capabilities.maxTools >= 10) CueTools.converse else CueTools.compact,
            spokenReplies = true,
        ))
        transcript += "cue" to text
        persist()
        return text
    }

    private suspend fun pickBrain(): Brain {
        val wanted = engine.router.forJob("converse")
        if (wanted.name == "rules") return wanted
        if (wanted.isReady()) return wanted
        return engine.router.rules
    }

    private fun rules(kind: String): String = buildString {
        append("You are Cue, a warm phone assistant on a call. Reply in one or two short sentences. ")
        append("Use tools to save lists, notes and reminders the user mentions. Never invent facts that are not in the context. ")
        if (kind == ReminderKind.PRACTICE) append("This is English speaking practice: keep the conversation going and gently correct one mistake per turn. ")
    }

    suspend fun userSaid(text: String, onAudio: ((ShortArray) -> Unit)? = null): CallReply = turn(TurnInput.Text(text), text, onAudio)

    suspend fun userAudio(pcm16k: ShortArray): CallReply = turn(TurnInput.Audio(pcm16k), null)

    private suspend fun turn(input: TurnInput, typed: String?, onAudio: ((ShortArray) -> Unit)? = null): CallReply {
        val s = session ?: return CallReply("Sorry, one second.", false)
        turns++
        val before = executor.changes.size
        var streamed = false
        val outcome = loop.run(s, input, feedResults = true, onAudio = onAudio?.let { cb -> { pcm: ShortArray -> streamed = true; cb(pcm) } })
        val heard = typed ?: outcome.heard
        heard?.let { if (!outcome.endCall) userLines += it; transcript += "user" to it }
        val acted = executor.changes.size > before
        if (heard != null && !acted && !outcome.endCall) unhandled += heard
        if (kind == ReminderKind.PRACTICE && outcome.spoken.contains("say", ignoreCase = true)) practiceNotes += outcome.spoken
        val spoken = outcome.spoken.ifBlank { if (outcome.failed != null) "Sorry, I lost that. Could you say it again?" else "Okay." }
        transcript += "cue" to spoken
        persist()
        engine.log("turn[$kind] ${brain.name}: '${heard ?: "(audio)"}' -> '$spoken' tools=${outcome.toolResults.size}")
        return CallReply(spoken, outcome.endCall, outcome.audio, heard, acted, streamed)
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
        val now = engine.clock.now()
        engine.dao.call(callId)?.let { engine.dao.updateCall(it.copy(endedAt = now, outcome = "ANSWERED")) }
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
    fun transcript(): List<Pair<String, String>> = transcript.toList()
}

internal fun newCallSession(kind: String, reminderId: Long?, now: Long) = CallSession(kind = kind, reminderId = reminderId, ringAt = now, answeredAt = now, outcome = "IN_CALL")
