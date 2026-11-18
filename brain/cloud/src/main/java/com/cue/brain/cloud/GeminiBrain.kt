package com.cue.brain.cloud

import android.util.Base64
import android.util.Log
import com.cue.brain.Brain
import com.cue.brain.BrainCapabilities
import com.cue.brain.BrainEvent
import com.cue.brain.BrainSession
import com.cue.brain.DuplexListener
import com.cue.brain.DuplexSession
import com.cue.brain.FieldsRequest
import com.cue.brain.FieldsResult
import com.cue.brain.SessionConfig
import com.cue.brain.ToolCall
import com.cue.brain.ToolSpec
import com.cue.brain.TurnInput
import com.cue.brain.rules.RulesSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

private const val TAG = "CueGemini"

/**
 * Google's Gemini API (an AI Studio key), the default brain.
 *
 *  - Calls: the Live API with [LIVE_MODEL] over a WebSocket: the user's voice streams in, the model's voice streams
 *    out, Google's side decides when the user has finished and stops talking when interrupted, and the model calls
 *    Cue's tools itself (the same validated executor as every other brain).
 *  - One-shot jobs (the night journal, filing): [TEXT_MODEL] over REST.
 *
 * [isOnline] is checked before each use; without a network the router falls back to the on-device brain.
 */
class GeminiBrain(private val apiKey: String, private val isOnline: () -> Boolean) : Brain {
    override val name = "cloud"
    override val capabilities = BrainCapabilities(contextTokens = 128_000, maxTools = 20, hearsAudio = true, speaks = true, needsNetwork = true, prepareMillis = 1_000)

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()

    val configured: Boolean get() = apiKey.isNotBlank()

    override suspend fun prepare(): Boolean = isReady()
    override fun isReady(): Boolean = configured && isOnline()

    /** Turn by turn (used only if the live session can't start): rules for actions, Flash-Lite for conversation. */
    override fun openSession(config: SessionConfig): BrainSession = TextSession(this, config)

    override fun openDuplex(config: SessionConfig, listener: DuplexListener): DuplexSession? =
        if (!isReady()) null else LiveSession(apiKey, http, config, listener)

    override suspend fun complete(request: FieldsRequest): FieldsResult = withContext(Dispatchers.IO) {
        val text = runCatching { generate(request.instruction, request.text) }.onFailure { Log.w(TAG, "complete failed", it) }.getOrNull()
            ?: return@withContext FieldsResult(false, emptyList())
        val lines = text.lines().mapNotNull { l -> Regex("^([A-Z_]{2,}):\\s*(.+)$").find(l.trim())?.let { it.groupValues[1] to it.groupValues[2].trim() } }
        FieldsResult(lines.isNotEmpty(), lines.ifEmpty { listOf("TEXT" to text.trim()) }, text)
    }

    /** One Flash-Lite answer: [system] as the system instruction, [user] as the user turn. */
    internal fun generate(system: String, user: String): String? {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
        val req = Request.Builder().url("$REST/models/$TEXT_MODEL:generateContent").header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) { Log.w(TAG, "generate ${r.code}: ${s.take(300)}"); return null }
            val parts = JSONObject(s).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
            return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
        }
    }

    /**
     * A typed conversation answered as JSON (Ask Cue's generated screens). [turns] alternate user and model text,
     * oldest first. Text only: nothing here speaks or listens. Null when offline or the call fails.
     */
    suspend fun chatJson(system: String, turns: List<Pair<String, String>>, model: String = TEXT_MODEL, thinking: String? = null, timeoutSec: Long = 60): String? = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext null
        val contents = JSONArray()
        turns.forEach { (role, text) -> contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))) }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").apply {
                if (thinking != null) put("thinkingConfig", JSONObject().put("thinkingLevel", thinking)) else put("temperature", 0.4)
            })
        val req = Request.Builder().url("$REST/models/$model:generateContent").header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        runCatching {
            slowHttp.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) { Log.w(TAG, "chatJson ${r.code}: ${s.take(300)}"); return@use null }
                val parts = JSONObject(s).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@use null
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
            }
        }.onFailure { Log.w(TAG, "chatJson failed", it) }.getOrNull()
    }

    /**
     * A considered answer: the thinking model first (it weighs the user's data before answering), Flash-Lite if that
     * model is busy or slow, so a reply always comes. Returns the text and which model gave it.
     */
    suspend fun thinkJson(system: String, turns: List<Pair<String, String>>): Pair<String, String>? {
        val t0 = System.currentTimeMillis()
        chatJson(system, turns, THINK_MODEL, thinking = "low", timeoutSec = 35)?.let { Log.i(TAG, "think: $THINK_MODEL ${System.currentTimeMillis() - t0} ms"); return it to THINK_MODEL }
        Log.w(TAG, "think model unavailable; falling back to $TEXT_MODEL")
        chatJson(system, turns, TEXT_MODEL, thinking = "low", timeoutSec = 25)?.let { return it to TEXT_MODEL }
        // Busy (503) right now: one more model before giving up to the phone's own screens.
        return chatJson(system, turns, "gemini-3.5-flash-lite", thinking = "low", timeoutSec = 25)?.let { it to "gemini-3.5-flash-lite" }
    }

    /** Generated screens can take longer than a one-line answer. */
    private val slowHttp by lazy { http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }

    override suspend fun release() = Unit

    companion object {
        const val LIVE_MODEL = "gemini-3.8-live"
        const val TEXT_MODEL = "gemini-3.1-flash-lite"
        /** Ask Cue's model: reasons over the user's data before it answers (measured ~5-6 s). */
        const val THINK_MODEL = "gemini-3-flash-preview"
        const val VOICE = "Aoede"
        private const val REST = "https://generativelanguage.googleapis.com/v1beta"
        internal const val WS = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** Cue's tool specs as the Live API's function declarations. */
        internal fun declarations(tools: List<ToolSpec>): JSONArray = JSONArray().apply {
            tools.forEach { t ->
                val fn = JSONObject().put("name", t.name).put("description", t.description)
                if (t.parameters.isNotEmpty()) {
                    val props = JSONObject()
                    t.parameters.forEach { p -> props.put(p.name, JSONObject().put("type", if (p.type == "number") "NUMBER" else "STRING").put("description", p.description)) }
                    fn.put("parameters", JSONObject().put("type", "OBJECT").put("properties", props)
                        .put("required", JSONArray(t.parameters.filter { it.required }.map { it.name })))
                }
                put(fn)
            }
        }
    }
}

/** The Live API conversation: one WebSocket, audio both ways, tool calls answered through the listener. */
private class LiveSession(
    apiKey: String,
    http: OkHttpClient,
    private val config: SessionConfig,
    private val listener: DuplexListener,
) : DuplexSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ready = false
    @Volatile private var closed = false
    private val heard = StringBuilder()
    @Volatile private var userSpeaking = false
    private var sentChunks = 0
    /** Tool calls run one at a time, in the order the model sent them (they often change the same list). */
    private val toolQueue = kotlinx.coroutines.channels.Channel<JSONObject>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    init {
        scope.launch {
            for (fc in toolQueue) {
                val args = fc.optJSONObject("args")?.let { a -> a.keys().asSequence().associateWith { k -> a.opt(k)?.toString().orEmpty() } }.orEmpty()
                val call = ToolCall(fc.getString("name"), args)
                Log.i(TAG, "live: tool ${call.name} ${call.args}")
                val r = runCatching { listener.onToolCall(call) }.getOrElse { com.cue.brain.ToolResult(call.name, false, it.message ?: "error") }
                val resp = JSONObject().put("id", fc.optString("id")).put("name", call.name)
                    .put("response", JSONObject().put("result", r.message).put("ok", r.ok))
                val ok = if (closed) false else ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(resp))).toString())
                Log.i(TAG, "live: answered ${call.name} (${r.ok}), sent=$ok")
            }
        }
    }

    private val ws: WebSocket = http.newWebSocket(Request.Builder().url("${GeminiBrain.WS}?key=$apiKey").build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(setup().toString())
            Log.i(TAG, "live: connected, setup sent (${GeminiBrain.LIVE_MODEL})")
        }
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "live: closing $code $reason")
            webSocket.close(1000, null)
            finish(if (code == 1000) null else "closed $code: $reason")
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "live: failed ${response?.code} ${t.message}")
            finish(t.message ?: "connection failed")
        }
    })

    private fun setup(): JSONObject {
        val system = buildString {
            append(config.rules).append('\n')
            append("You are on a live phone call, so speak naturally and keep each reply to one to three short sentences. ")
            append("The user's screen shows a live panel next to you: whenever the talk is about a list, reminders, habits, people, spaces, papers or the journal, call the matching read tool (list_read, reminders_read, habits_read, people_read, albums_read, papers_read, journal_lookup) so it appears there, and say \"it's on your screen\" instead of reading long lists aloud. ")
            append("For anything easier to see than hear (a plan or itinerary, a comparison, a budget, a schedule, a summary of their week) call board_show with what to show; it is drawn as a screen on their board. When they're planning something, plan it WITH them: suggest a first draft and put it on the board, then ask what to change, instead of asking question after question. ")
            append("Don't just answer: think one step ahead. If something clashes, is at risk or is still open (an unpacked item before a flight, a reminder that collides, a person waiting on them), say it briefly and offer to handle it. ")
            append("Use the tools to read and change the user's lists, habits, notes, reminders and people; answer questions about their data from the tools, never from memory. ")
            append("Only say something was added, ticked, saved or scheduled after the tool returned ok. ")
            append("Tool results number what they save or list, like #14. When the user corrects something (\"no, from Muskan\", \"I meant tomorrow\"), change that same entry with the matching update tool and its number; never add a second one. ")
            append("The user can have several lists: pass the list's name when they name one, and naming a new list creates it. ")
            append("To delete something saved by mistake when you don't have its number, use forget with the user's words. ")
            append("When a tool result starts with NEEDS or CONFIRM, nothing was done yet: ask the user the ASK question and wait for the answer. ")
            append("When the user says goodbye or is done, say a short goodbye and then call end_call. ")
            append("Always speak English (the user is in India), even if what you heard sounds like another language; if you didn't catch it, ask them to say it again.\n")
            append("What you know:\n").append(config.context)
        }
        return JSONObject().put("setup", JSONObject()
            .put("model", "models/${GeminiBrain.LIVE_MODEL}")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", GeminiBrain.VOICE)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", GeminiBrain.declarations(config.tools))))
            .put("inputAudioTranscription", JSONObject().put("languageCodes", JSONArray().put("en-IN")))
            .put("outputAudioTranscription", JSONObject())
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject().put("silenceDurationMs", 700)))
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject())))
    }

    private fun handle(raw: String) {
        val m = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when {
            m.has("setupComplete") -> { ready = true; Log.i(TAG, "live: ready"); listener.onReady() }
            m.has("serverContent") -> content(m.getJSONObject("serverContent"))
            m.has("toolCall") -> {
                val calls = m.getJSONObject("toolCall").optJSONArray("functionCalls") ?: return
                for (i in 0 until calls.length()) toolQueue.trySend(calls.getJSONObject(i))
            }
            m.has("goAway") -> Log.i(TAG, "live: server will close soon (${m.getJSONObject("goAway").opt("timeLeft")})")
        }
    }

    private fun content(sc: JSONObject) {
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            heard.append(it); userSpeaking = true
            listener.onUserText(heard.toString().trim(), final = false)
        }
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        val transcript = sc.optJSONObject("outputTranscription")?.optString("text").orEmpty()
        if ((parts != null && parts.length() > 0) || transcript.isNotEmpty()) userDone()
        if (parts != null) for (i in 0 until parts.length()) {
            val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
            val bytes = Base64.decode(data, Base64.DEFAULT)
            val pcm = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
            listener.onAudio(pcm)
        }
        if (transcript.isNotEmpty()) listener.onModelText(transcript)
        if (sc.optBoolean("turnComplete")) Log.i(TAG, "live: turn complete")
        if (sc.optBoolean("interrupted")) { Log.i(TAG, "live: interrupted by the user"); listener.onInterrupted() }
        if (sc.optBoolean("turnComplete")) { userDone(); listener.onTurnComplete() }
    }

    /** The user's words so far are their whole turn once the model starts answering. */
    private fun userDone() {
        if (!userSpeaking) return
        userSpeaking = false
        val text = heard.toString().trim()
        heard.setLength(0)
        Log.i(TAG, "live: heard '$text'")
        if (text.isNotEmpty()) listener.onUserText(text, final = true)
    }

    private fun send(json: JSONObject) { if (!closed) ws.send(json.toString()) }

    override fun sendAudio(pcm16k: ShortArray) {
        if (!ready || closed) return
        val b = ByteBuffer.allocate(pcm16k.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.asShortBuffer().put(pcm16k)
        val data = Base64.encodeToString(b.array(), Base64.NO_WRAP)
        send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject().put("data", data).put("mimeType", "audio/pcm;rate=16000"))))
        if (++sentChunks == 1) Log.i(TAG, "live: microphone streaming")
    }

    override fun sendText(text: String) {
        if (closed) return
        listener.onUserText(text, final = true)
        send(JSONObject().put("realtimeInput", JSONObject().put("text", text)))
    }

    override fun sendImage(jpeg: ByteArray) {
        send(JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject().put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)).put("mimeType", "image/jpeg"))))
    }

    override fun prompt(text: String) {
        send(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text))))).put("turnComplete", true)))
    }

    private fun finish(reason: String?) {
        if (closed) return
        closed = true
        listener.onClosed(reason)
        scope.cancel()
    }

    override fun close() {
        if (closed) return
        ws.close(1000, "call ended")
        finish(null)
    }
}

/** Turn-by-turn fallback with the same key: exact actions through the rules, conversation through Flash-Lite. */
private class TextSession(private val brain: GeminiBrain, private val config: SessionConfig) : BrainSession {
    private val rules = RulesSession(config.mode)
    private val history = StringBuilder()

    override fun send(input: TurnInput): Flow<BrainEvent> = flow {
        when (input) {
            is TurnInput.Text -> {
                val events = rules.send(input).toList()
                if (events.any { it is BrainEvent.ToolCallRequested || (it is BrainEvent.Done && it.endCall) }) { events.forEach { emit(it) }; return@flow }
                val system = config.rules + "\nReply in one or two short spoken sentences. What you know:\n" + config.context +
                    (if (input.facts.isNotBlank()) "\nRight now: ${input.facts}" else "")
                val reply = withContext(Dispatchers.IO) { runCatching { brain.generate(system, history.toString() + "User: " + input.text) }.getOrNull() }
                if (reply.isNullOrBlank()) { events.forEach { emit(it) }; return@flow }
                history.append("User: ").append(input.text).append("\nCue: ").append(reply).append('\n')
                emit(BrainEvent.Text(reply)); emit(BrainEvent.Done(false))
            }
            is TurnInput.ToolResults, is TurnInput.Say -> rules.send(input).collect { emit(it) }
            is TurnInput.Audio -> emit(BrainEvent.Failed("this session takes text"))
        }
    }

    override fun close() = Unit
}
