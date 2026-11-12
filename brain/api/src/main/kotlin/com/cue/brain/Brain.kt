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
    fun close()
}

sealed interface TurnInput {
    data class Text(val text: String) : TurnInput
    data class Audio(val pcm16k: ShortArray) : TurnInput
    data class ToolResults(val results: List<ToolResult>) : TurnInput
}

sealed interface BrainEvent {
    data class Text(val delta: String) : BrainEvent
    data class Audio(val pcm24k: ShortArray) : BrainEvent
    data class Heard(val text: String) : BrainEvent
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
    suspend fun run(session: BrainSession, input: TurnInput, feedResults: Boolean, onAudio: ((ShortArray) -> Unit)? = null): TurnOutcome {
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
                is BrainEvent.Heard -> heard = ev.text
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
            val follow = session.send(TurnInput.ToolResults(results)).toList()
            follow.forEach { ev ->
                when (ev) {
                    is BrainEvent.Text -> text.append(ev.delta)
                    is BrainEvent.Audio -> audio += ev.pcm24k
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
    val rules: Brain get() = fallback
}
