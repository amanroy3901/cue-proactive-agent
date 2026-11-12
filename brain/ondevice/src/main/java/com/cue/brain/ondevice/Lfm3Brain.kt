package com.cue.brain.ondevice

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.cue.brain.Brain
import com.cue.brain.BrainCapabilities
import com.cue.brain.BrainEvent
import com.cue.brain.BrainSession
import com.cue.brain.FieldsRequest
import com.cue.brain.FieldsResult
import com.cue.brain.SessionConfig
import com.cue.brain.TurnInput
import com.cue.brain.ondevice.lfm.Conversation
import com.cue.brain.ondevice.lfm.Lfm3OmniEngine
import com.cue.brain.rules.RulesSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "CueLfm3"

/**
 * LiquidAI LFM3-Omni-2B on the phone, behind the brain contract.
 *
 * How it splits the work on a call: the phone's own speech recognizer gives the transcript (needed for filing and
 * tools anyway), the rules parser turns actions into exact, validated tool calls, and the model answers everything
 * that is conversation (practice, "how was your day", open questions) in its own generated voice. A wrong guess by
 * the small model can never tick the wrong item or set the wrong time.
 */
class Lfm3Brain(private val context: Context) : Brain {
    override val name = "lfm3"
    override val capabilities = BrainCapabilities(contextTokens = 4096, maxTools = 5, hearsAudio = false, speaks = true, needsNetwork = false, prepareMillis = 48_000)

    @Volatile var engine: Lfm3OmniEngine? = null
        private set
    @Volatile var lastLoadMs: Long = 0
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var loading = false
        private set
    private val lock = Mutex()

    val modelDir: File get() = context.getExternalFilesDir(null) ?: context.filesDir

    fun filesPresent(): Boolean = File(modelDir, BUNDLE).exists() && SPEECH.all { File(modelDir, "speech/$it").exists() || File(modelDir, it).exists() }

    fun freeMemoryMb(): Long {
        val mi = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }

    fun status(): String = when {
        engine != null -> "Loaded in ${lastLoadMs / 1000} s. ${engine?.placement()}"
        loading -> "Loading the model…"
        !filesPresent() -> "Model files not found in ${modelDir.absolutePath}"
        lastError != null -> "Couldn't load: $lastError"
        else -> "Model files present (4.0 GB). Loads on demand, about a minute the first time. ${freeMemoryMb()} MB free now."
    }

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (engine != null) return@withLock true
            if (!filesPresent()) { lastError = "model files missing"; return@withLock false }
            loading = true
            val t0 = System.currentTimeMillis()
            try {
                engine = Lfm3OmniEngine.open(context, Lfm3OmniEngine.Config()) { Log.i(TAG, it) }
                lastLoadMs = System.currentTimeMillis() - t0
                lastError = null
                Log.i(TAG, "loaded in $lastLoadMs ms: ${engine?.placement()}")
                true
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "load failed", t)
                false
            } finally {
                loading = false
            }
        }
    }

    override fun isReady(): Boolean = engine != null

    override fun openSession(config: SessionConfig): BrainSession = Lfm3Session(this, config)

    override suspend fun complete(request: FieldsRequest): FieldsResult = withContext(Dispatchers.Default) {
        val e = engine ?: return@withContext FieldsResult(false, emptyList())
        lock.withLock {
            runCatching {
                val c = Conversation(e)
                c.startPlain("You turn what someone said on a phone call into a short first-person journal entry. Plain text, no lists.")
                val out = c.ask(Conversation.Question(null, request.text.take(1500), "file"), request.instruction, maxNewTokens = 120, textOnly = true)
                val text = out.text.substringAfter("JOURNAL:").trim().trim('"')
                FieldsResult(text.isNotBlank(), listOf("JOURNAL" to text), out.text)
            }.getOrElse { Log.w(TAG, "complete failed", it); FieldsResult(false, emptyList()) }
        }
    }

    /** Generates one spoken answer in the conversation. Serialised: one model, one job at a time. */
    internal suspend fun answer(conv: Conversation, text: String, instruction: String = "", onAudio: (FloatArray) -> Unit = {}): Conversation.Output = lock.withLock {
        conv.ask(Conversation.Question(null, text, "cue"), instruction, maxNewTokens = 420, listener = object : Conversation.Listener {
            override fun onAudio(pcm: FloatArray) = onAudio(pcm)
        })
    }

    override suspend fun release() {
        lock.withLock {
            engine?.close()
            engine = null
            Log.i(TAG, "released")
        }
    }

    companion object {
        const val BUNDLE = "LFM3-Omni-2B_int8.litertlm"
        val SPEECH = listOf("LFM3-Omni-2B_depth_frame2_f32.tflite", "LFM3-Omni-2B_frame_embed_f32.tflite", "LFM3-Omni-2B_codec_decoder_f12_f32.tflite")
    }
}

private class Lfm3Session(private val brain: Lfm3Brain, private val config: SessionConfig) : BrainSession {
    private val rules = RulesSession(config.mode)
    private var conv: Conversation? = null

    private fun system(): String = buildString {
        append("You are Cue, a warm personal assistant speaking with the user on a phone call. Reply in one or two short spoken sentences. ")
        append("Never invent plans, times or facts that are not listed below. ")
        if (config.mode == "PRACTICE") append("This is English speaking practice: reply naturally, gently correct one grammar mistake if there is one, then ask one follow-up question. ")
        if (config.mode == "CHECKIN_NIGHT") append("It is the night check-in: respond kindly to how the user's day went. ")
        append("What you know:\n").append(config.context.take(1100)).append('\n')
        append("Respond with interleaved text and audio.")
    }

    private fun conversation(): Conversation {
        conv?.let { return it }
        val e = brain.engine ?: error("model not loaded")
        return Conversation(e).also { it.startPlain(system()); conv = it }
    }

    override fun send(input: TurnInput): Flow<BrainEvent> = flow {
        when (input) {
            is TurnInput.Text -> {
                if (config.mode == "PRACTICE" && !Regex("^(bye|stop|that's all|end)", RegexOption.IGNORE_CASE).containsMatchIn(input.text.trim())) {
                    if (speak(input.text)) return@flow
                }
                val events = rules.send(input).toList()
                val acts = events.any { it is BrainEvent.ToolCallRequested }
                val ends = events.any { it is BrainEvent.Done && it.endCall }
                if (acts || ends) { events.forEach { emit(it) }; return@flow }
                // Conversation, not an action: the model answers in its own voice. Falls back to the rules reply.
                if (!speak(input.text)) events.forEach { emit(it) }
            }
            is TurnInput.ToolResults -> rules.send(input).collect { emit(it) }
            is TurnInput.Audio -> emit(BrainEvent.Failed("this provider takes transcripts"))
        }
    }.flowOn(Dispatchers.Default)

    /** Emits the model's text and audio for one answer; false if the model could not answer. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<BrainEvent>.speak(text: String): Boolean {
        val t0 = System.currentTimeMillis()
        val hint = if (config.mode == "PRACTICE") com.cue.brain.rules.Practice.fix(text)?.let { (wrong, right) ->
            "(Start by kindly saying: a better way to say \"$wrong\" is \"$right\". Then continue the conversation.)" } ?: "" else "(Answer in one short, warm sentence.)"
        // Speech is streamed out chunk by chunk while the model is still generating.
        val chunks = Channel<ShortArray>(Channel.UNLIMITED)
        var out: Conversation.Output? = null
        var failed: Throwable? = null
        coroutineScope {
            launch(Dispatchers.Default) {
                val onAudio: (FloatArray) -> Unit = { f -> chunks.trySend(ShortArray(f.size) { i -> (f[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }) }
                out = runCatching { brain.answer(conversation(), text, hint, onAudio) }.recoverCatching { t ->
                    if (t is Conversation.Full) { conv = null; brain.answer(conversation(), text, hint, onAudio) } else throw t
                }.onFailure { failed = it }.getOrNull()
                chunks.close()
            }
            for (c in chunks) emit(BrainEvent.Audio(c))
        }
        val o = out ?: run { Log.w(TAG, "answer failed", failed); return false }
        Log.i(TAG, "answer in ${System.currentTimeMillis() - t0} ms, first audio ${o.timing.opt("first_audio_ms")} ms, ${o.text.take(80)}")
        emit(BrainEvent.Text(o.text.ifBlank { "Mm-hm." }))
        emit(BrainEvent.Done(false))
        return true
    }

    override fun close() { conv = null }
}
