package com.cue.brain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList

/**
 * The brain contract. Every model provider (rules, on-device LFM3, cloud) implements it, and the rest of the app
 * cannot tell them apart. It knows nothing about Cue: no albums, no checklists, only "talk to a model, with tools".
 */
interface Brain {
    val name: String
    val capabilities: BrainCapabilities

    /** Load the model or open the connection. Returns false when the brain cannot run right now. */
    suspend fun prepare(): Boolean

    fun isReady(): Boolean

    fun openSession(config: SessionConfig): BrainSession

    /**
     * A conversation that listens and talks at the same time (a realtime voice model): the provider decides when the
     * user has finished, stops talking when interrupted, and calls tools as it goes. Null when the provider works
     * turn by turn; the call then uses [openSession].
     */
    fun openDuplex(config: SessionConfig, listener: DuplexListener): DuplexSession? = null

    /** One-shot text job (post-call filing, document enrichment). Output is labelled lines. */
    suspend fun complete(request: FieldsRequest): FieldsResult

    suspend fun release()
}

data class BrainCapabilities(
    val contextTokens: Int,
    val maxTools: Int,
    val hearsAudio: Boolean,
    val speaks: Boolean,
    val needsNetwork: Boolean,
    val prepareMillis: Long,
)

data class SessionConfig(
    val mode: String,
    val rules: String,
    val context: String,
    val tools: List<ToolSpec>,
    val spokenReplies: Boolean,
)

interface BrainSession {
    /** One user turn. Emits text, optional audio, tool calls and a done event. */
    fun send(input: TurnInput): Flow<BrainEvent>
    /** The user started talking over the answer: stop generating it as soon as possible (barge-in). */
    fun interrupt() {}
    /** A quick transcript of speech still being recorded, for live captions; null if the provider can't. */
    suspend fun preview(pcm16k: ShortArray): String? = null
    /** Abandon a [preview] in progress (the turn itself is about to start). */
    fun cancelPreview() {}
    fun close()
}

/** A live two-way voice conversation (see [Brain.openDuplex]). */
interface DuplexSession {
    /** The microphone, 16 kHz mono 16-bit, sent as it is recorded. */
    fun sendAudio(pcm16k: ShortArray)
    /** Something the user typed. */
    fun sendText(text: String)
    /** A picture the user is showing (JPEG), at most about one a second. */
    fun sendImage(jpeg: ByteArray)
    /** An instruction from the app that is not the user speaking: "say this greeting", "the user went quiet". */
    fun prompt(text: String)
    fun close()
}

/** What a [DuplexSession] reports. Called from the provider's own threads. */
interface DuplexListener {
    fun onReady() {}
    /** What the user is saying, as it is recognised; [final] once their turn is over. */
    fun onUserText(text: String, final: Boolean) {}
    /** More of the model's spoken words, as text, in step with its audio. */
    fun onModelText(delta: String) {}
    fun onAudio(pcm24k: ShortArray) {}
    /** The user started talking over the model: stop playing what is queued. */
    fun onInterrupted() {}
    /** The model finished its turn. */
    fun onTurnComplete() {}
    /** A tool the model wants run; the result goes back to it. */
    suspend fun onToolCall(call: ToolCall): ToolResult
    fun onClosed(reason: String?) {}
}

sealed interface TurnInput {
    /** [facts]: what is true right now (the list, what this call changed), so a model's answer can't drift from it. */
    data class Text(val text: String, val facts: String = "") : TurnInput
    data class Audio(val pcm16k: ShortArray, val facts: String = "") : TurnInput
    data class ToolResults(val results: List<ToolResult>) : TurnInput
    /** A line the app wants said as it is (the opening, a nudge): a provider with a voice speaks it, others return it. */
    data class Say(val text: String) : TurnInput
}

sealed interface BrainEvent {
    data class Text(val delta: String) : BrainEvent
    data class Audio(val pcm24k: ShortArray) : BrainEvent
    data class Heard(val text: String) : BrainEvent
    /** The answer's text so far, while it is being generated (for live captions). */
    data class Partial(val text: String) : BrainEvent
    data class ToolCallRequested(val call: ToolCall) : BrainEvent
    data class Done(val endCall: Boolean = false, val millis: Long = 0) : BrainEvent
    data class Failed(val reason: String) : BrainEvent
}

data class ToolSpec(val name: String, val description: String, val parameters: List<ToolParam>)
data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean = true)
data class ToolCall(val name: String, val args: Map<String, String>)
data class ToolResult(val name: String, val ok: Boolean, val message: String)

data class FieldsRequest(val job: String, val instruction: String, val text: String)
data class FieldsResult(val ok: Boolean, val lines: List<Pair<String, String>>, val raw: String = "")

/** Executes tool calls on behalf of the loop. Implemented by :agent. */
fun interface ToolExecutor {
    suspend fun execute(call: ToolCall): ToolResult
}

/** Result of one complete turn after tools ran. */
data class TurnOutcome(
    val spoken: String,
    val heard: String?,
    val toolResults: List<ToolResult>,
    val audio: List<ShortArray>,
    val endCall: Boolean,
    val failed: String?,
)

/**
 * The shared tool loop: send a turn, run every requested tool, enforce the write limit, and hand results back when
 * the provider can use them. Same for every provider.
 */
class ToolLoop(private val executor: ToolExecutor, private val maxWritesPerTurn: Int = 5) {
    suspend fun run(session: BrainSession, input: TurnInput, feedResults: Boolean, onAudio: ((ShortArray) -> Unit)? = null, onEvent: ((BrainEvent) -> Unit)? = null): TurnOutcome {
        val text = StringBuilder()
        val audio = mutableListOf<ShortArray>()
        val results = mutableListOf<ToolResult>()
        var heard: String? = null
        var end = false
        var failed: String? = null
        var calls = 0
        session.send(input).collect { ev ->
            when (ev) {
                is BrainEvent.Text -> text.append(ev.delta)
                is BrainEvent.Audio -> if (onAudio != null) onAudio(ev.pcm24k) else audio += ev.pcm24k
                is BrainEvent.Heard -> { heard = ev.text; onEvent?.invoke(ev) }
                is BrainEvent.Partial -> onEvent?.invoke(ev)
                is BrainEvent.ToolCallRequested -> {
                    calls++
                    results += if (calls > maxWritesPerTurn) ToolResult(ev.call.name, false, "too many actions in one turn")
                    else runCatching { executor.execute(ev.call) }.getOrElse { ToolResult(ev.call.name, false, it.message ?: "error") }
                }
                is BrainEvent.Done -> end = ev.endCall
                is BrainEvent.Failed -> failed = ev.reason
            }
        }
        if (feedResults && results.isNotEmpty() && failed == null) {
            session.send(TurnInput.ToolResults(results)).collect { ev ->
                when (ev) {
                    is BrainEvent.Text -> text.append(ev.delta)
                    is BrainEvent.Audio -> if (onAudio != null) onAudio(ev.pcm24k) else audio += ev.pcm24k
                    is BrainEvent.Partial -> onEvent?.invoke(ev)
                    is BrainEvent.Done -> end = end || ev.endCall
                    else -> Unit
                }
            }
        }
        return TurnOutcome(text.toString().trim(), heard, results, audio, end, failed)
    }
}

/** Picks a brain per job. Converse, File and Enrich can each use a different provider. */
class BrainRouter(private val brains: Map<String, Brain>, private val fallback: Brain) {
    var converse: String = fallback.name
    var file: String = fallback.name
    var enrich: String = fallback.name

    fun forJob(job: String): Brain {
        val wanted = when (job) {
            "converse" -> converse
            "file" -> file
            else -> enrich
        }
        val b = brains[wanted]
        return if (b != null) b else fallback
    }

    fun all(): Collection<Brain> = brains.values
    fun named(name: String): Brain? = brains[name]
    val rules: Brain get() = fallback
}
