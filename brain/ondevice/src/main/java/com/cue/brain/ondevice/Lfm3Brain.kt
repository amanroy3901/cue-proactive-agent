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
 * LiquidAI LFM3-Omni-2B on the phone, behind the brain contract, used the way its model card documents:
 *
 *  - hearing: the user's own audio, transcribed with the card's ASR prompt ("Perform ASR in English.", the user turn
 *    = the audio only, text out), so actions can be checked against exact words;
 *  - talking: a voice-chat turn ("... Respond with interleaved text and audio.") whose user turn is the same audio,
 *    so the model hears the user, not a transcript, and answers in its own voice;
 *  - saying a fixed line (the opening, a confirmation): the card's TTS prompt ("Perform TTS in American English
 *    using a female voice.", the user turn = the text, speech out).
 *
 * The three prompts need different system turns and the model has one state, so the voice chat's state is saved
 * after each of its answers and put back before the next one. Actions still go through the rules parser on the
 * transcript: a wrong guess by the small model can never tick the wrong item or set the wrong time.
 */
class Lfm3Brain(private val context: Context) : Brain {
    override val name = "lfm3"
    override val capabilities = BrainCapabilities(contextTokens = 4096, maxTools = 5, hearsAudio = true, speaks = true, needsNetwork = false, prepareMillis = 48_000)

    @Volatile var engine: Lfm3OmniEngine? = null
        private set
    @Volatile var lastLoadMs: Long = 0
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var loading = false
        private set
    private val lock = Mutex()

    internal val filesDir: File get() = context.filesDir
    internal val debugVoice: Boolean get() = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

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
                lmOwner = null
                val c = Conversation(e)
                c.startPlain("You turn what someone said on a phone call into a short first-person journal entry. Plain text, no lists.")
                val out = c.ask(Conversation.Question(null, request.text.take(1500), "file"), request.instruction, maxNewTokens = 120, textOnly = true)
                val text = out.text.substringAfter("JOURNAL:").trim().trim('"')
                FieldsResult(text.isNotBlank(), listOf("JOURNAL" to text), out.text)
            }.getOrElse { Log.w(TAG, "complete failed", it); FieldsResult(false, emptyList()) }
        }
    }

    /** Which conversation's state the language model holds now (the voice chat's state is put back when it is not). */
    private var lmOwner: Any? = null

    /** The user's recording (16 kHz) through the audio encoder: one encoding serves the transcript and the answer. */
    internal suspend fun encode(pcm16k: FloatArray): List<FloatArray> = lock.withLock {
        val e = engine ?: error("model not loaded")
        e.audioEncoder.encode(pcm16k)
    }

    /** The model card's ASR: what the user said, word for word. */
    internal suspend fun transcribe(rows: List<FloatArray>): String = lock.withLock {
        val e = engine ?: error("model not loaded")
        lmOwner = null
        val c = Conversation(e)
        c.startPlain(ASR_SYSTEM)
        val out = c.ask(Conversation.Question(null, "", "asr", rows), "", maxNewTokens = 160, textOnly = true)
        Log.i(TAG, "asr ${out.timing.optDouble("wall_ms").toInt()} ms: '${out.text}'")
        out.text.trim()
    }

    /** The model card's TTS: [text] read aloud in the model's voice, streamed to [onAudio]. */
    internal suspend fun say(text: String, onAudio: (FloatArray) -> Unit): Conversation.Output = lock.withLock {
        val e = engine ?: error("model not loaded")
        lmOwner = null
        val c = Conversation(e)
        c.startPlain(TTS_SYSTEM)
        // 80 ms a speech frame; a generous budget so a line is never cut off mid-word
        val budget = (text.length * 2 + 120).coerceAtMost(1400)
        c.ask(Conversation.Question(null, text, "tts"), "", maxNewTokens = budget, speechOnly = true, listener = object : Conversation.Listener {
            override fun onAudio(pcm: FloatArray) = onAudio(pcm)
        })
    }

    /** One voice-chat answer in [chat]'s conversation, its state put back first if another prompt ran since. */
    internal suspend fun chatTurn(chat: ChatState, q: Conversation.Question, instruction: String, onAudio: (FloatArray) -> Unit): Conversation.Output = lock.withLock {
        val e = engine ?: error("model not loaded")
        val listener = object : Conversation.Listener { override fun onAudio(pcm: FloatArray) = onAudio(pcm) }
        fun fresh(): Conversation = Conversation(e).also { it.startPlain(chat.system); chat.conv = it; chat.snapshot = null; lmOwner = chat }
        var conv = chat.conv
        if (conv == null) conv = fresh()
        else if (lmOwner !== chat) {
            val snap = chat.snapshot
            if (snap == null) conv = fresh() else { val t0 = System.nanoTime(); e.lm.restore(snap); lmOwner = chat; Log.i(TAG, "chat state back in ${(System.nanoTime() - t0) / 1_000_000} ms") }
        }
        val out = try {
            conv.ask(q, instruction, maxNewTokens = 420, listener = listener)
        } catch (full: Conversation.Full) {
            fresh().ask(q, instruction, maxNewTokens = 420, listener = listener)
        }
        val t0 = System.nanoTime()
        chat.snapshot = e.lm.snapshot()
        lmOwner = chat
        Log.i(TAG, "chat state saved in ${(System.nanoTime() - t0) / 1_000_000} ms")
        out
    }

    override suspend fun release() {
        lock.withLock {
            lmOwner = null
            engine?.close()
            engine = null
            Log.i(TAG, "released")
        }
    }

    companion object {
        const val BUNDLE = "LFM3-Omni-2B_int8.litertlm"
        /** The model card's prompts (docs.liquid.ai, LFM3-Omni-2B): keep them word for word. */
        const val ASR_SYSTEM = "Perform ASR in English."
        const val TTS_SYSTEM = "Perform TTS in American English using a female voice."
        const val CHAT_MODE = "Respond with interleaved text and audio."
        val SPEECH = listOf("LFM3-Omni-2B_depth_frame2_f32.tflite", "LFM3-Omni-2B_frame_embed_f32.tflite", "LFM3-Omni-2B_codec_decoder_f12_f32.tflite")
    }
}

/** A voice chat's conversation and its saved model state. */
internal class ChatState(val system: String) {
    var conv: Conversation? = null
    var snapshot: com.cue.brain.ondevice.lfm.LmRunner.Snapshot? = null
}

private class Lfm3Session(private val brain: Lfm3Brain, private val config: SessionConfig) : BrainSession {
    private val rules = RulesSession(config.mode)
    private val chat = ChatState(buildString {
        append("You are Cue, a warm personal assistant talking with the user on a phone call. Keep replies to one or two short sentences. ")
        append("Never invent plans, times or facts that are not listed here. ")
        append("You cannot change lists, notes, reminders or calls yourself, so never say you added, saved, ticked, set or scheduled anything. ")
        append("If the user asks for one of those, say you didn't quite catch it and ask them to say it simply, for example: add milk to my list. ")
        if (config.mode == "PRACTICE") append("This is English speaking practice: reply naturally, gently correct one grammar mistake if there is one, then ask one follow-up question. ")
        if (config.mode == "CHECKIN_NIGHT") append("It is the night check-in: respond kindly to how the user's day went. ")
        val ctx = config.context.take(1100)
        val user = Regex("(?m)^User: (.+)$").find(ctx)?.groupValues?.get(1)?.trim()
        if (user != null) append("You are Cue, and you are talking to $user. Never call yourself $user. ")
        append("What you know:\n").append(ctx.replace(Regex("(?m)^User: .+\n?"), "")).append('\n')
        append(Lfm3Brain.CHAT_MODE)
    })
    private val goodbye = Regex("^(bye|goodbye|good night|stop|that's all|end)", RegexOption.IGNORE_CASE)

    override fun send(input: TurnInput): Flow<BrainEvent> = flow {
        when (input) {
            is TurnInput.Audio -> {
                val pcm = FloatArray(input.pcm16k.size) { input.pcm16k[it] / 32768f }
                val rows = runCatching { brain.encode(pcm) }.getOrElse { Log.w(TAG, "encode failed", it); emptyList() }
                if (rows.isEmpty()) { emit(BrainEvent.Failed("nothing heard")); return@flow }
                val heard = runCatching { brain.transcribe(rows) }.getOrElse { Log.w(TAG, "asr failed", it); "" }
                if (heard.isBlank()) { emit(BrainEvent.Failed("nothing heard")); return@flow }
                emit(BrainEvent.Heard(heard))
                userTurn(heard, rows)
            }
            is TurnInput.Text -> userTurn(input.text, null)
            is TurnInput.ToolResults -> rules.send(input).collect { ev -> if (ev is BrainEvent.Text) sayLine(ev.delta) else emit(ev) }
            is TurnInput.Say -> { sayLine(input.text); emit(BrainEvent.Done(false)) }
        }
    }.flowOn(Dispatchers.Default)

    /** What the user said: actions through the rules (exact), conversation through the model's voice chat. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<BrainEvent>.userTurn(text: String, rows: List<FloatArray>?) {
        if (config.mode == "PRACTICE" && !goodbye.containsMatchIn(text.trim())) {
            if (chatAnswer(text, rows)) return
        }
        val events = rules.send(TurnInput.Text(text)).toList()
        val acts = events.any { it is BrainEvent.ToolCallRequested }
        val ends = events.any { it is BrainEvent.Done && it.endCall }
        if (acts) { events.forEach { emit(it) }; return }
        if (ends) { events.forEach { ev -> if (ev is BrainEvent.Text) sayLine(ev.delta) else emit(ev) }; return }
        if (!chatAnswer(text, rows)) events.forEach { emit(it) }
    }

    /** A voice-chat answer to the user's own audio (or typed text); false if the model could not answer. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<BrainEvent>.chatAnswer(text: String, rows: List<FloatArray>?): Boolean {
        val t0 = System.currentTimeMillis()
        // The model card: further instructions go in the user turn, after the question.
        val hint = if (config.mode == "PRACTICE") com.cue.brain.rules.Practice.fix(text)?.let { (wrong, right) ->
            "(Kindly say that a better way to say \"$wrong\" is \"$right\", then go on with the conversation.)" } ?: "" else ""
        val q = if (rows != null) Conversation.Question(null, "", "cue", rows) else Conversation.Question(null, text, "cue")
        val o = streamed { onAudio -> brain.chatTurn(chat, q, hint, onAudio) } ?: return false
        Log.i(TAG, "chat answer in ${System.currentTimeMillis() - t0} ms, first audio ${o.timing.opt("first_audio_ms")} ms: ${o.text.take(80)}")
        emit(BrainEvent.Text(o.text.ifBlank { "Mm-hm." }))
        emit(BrainEvent.Done(false))
        return true
    }

    /** A fixed line in the model's voice (TTS prompt); the text is emitted too, and alone if the model fails. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<BrainEvent>.sayLine(text: String) {
        if (text.isNotBlank()) {
            val t0 = System.currentTimeMillis()
            val o = streamed { onAudio -> brain.say(text, onAudio) }
            if (o != null) Log.i(TAG, "said '${text.take(60)}' in ${System.currentTimeMillis() - t0} ms, first audio ${o.timing.opt("first_audio_ms")} ms")
        }
        emit(BrainEvent.Text(text))
    }

    /** Runs a model job and emits its speech as it is generated; null if the job failed. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<BrainEvent>.streamed(job: suspend ((FloatArray) -> Unit) -> Conversation.Output): Conversation.Output? {
        var o = streamedInner(job)
        // Debug builds keep the last few answers' speech (files/voice) to check how it sounds.
        if (brain.debugVoice) o?.let { out -> runCatching {
            val dir = File(brain.filesDir, "voice").apply { mkdirs() }
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(5)?.forEach { it.delete() }
            com.cue.brain.ondevice.lfm.AudioIO.writeWav(File(dir, "${System.currentTimeMillis()}.wav"), out.pcm, 24_000)
        } }
        return o
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<BrainEvent>.streamedInner(job: suspend ((FloatArray) -> Unit) -> Conversation.Output): Conversation.Output? {
        val chunks = Channel<ShortArray>(Channel.UNLIMITED)
        var out: Conversation.Output? = null
        coroutineScope {
            launch(Dispatchers.Default) {
                val onAudio: (FloatArray) -> Unit = { f -> chunks.trySend(ShortArray(f.size) { i -> (f[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }) }
                out = runCatching { job(onAudio) }.onFailure { Log.w(TAG, "model job failed", it) }.getOrNull()
                chunks.close()
            }
            for (c in chunks) emit(BrainEvent.Audio(c))
        }
        return out
    }

    override fun close() { chat.conv = null; chat.snapshot = null }
}
