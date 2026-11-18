package com.cue.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cue.agent.CueRuntime
import com.cue.domain.CallRequest
import com.cue.domain.ReminderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "CueCall"

/**
 * Ring first, never talk unannounced. A call-style notification with a full-screen intent: the system plays the
 * ringtone from the channel (insistent, so it loops), shows the call screen over the lock screen, and times out
 * after 30 seconds, which counts as a missed call.
 */
object RingController {
    const val CHANNEL = "cue_calls_v2"
    const val NOTIF_ID = 4242
    const val EXTRA_KIND = "kind"
    const val EXTRA_REMINDER = "reminderId"
    const val EXTRA_ACCEPT = "accept"
    const val EXTRA_TITLE = "title"
    const val EXTRA_CHIPS = "chips"
    const val ACTION_DECLINE = "com.cue.call.DECLINE"
    const val ACTION_TIMEOUT = "com.cue.call.TIMEOUT"
    private const val RING_MS = 30_000L

    /** Rings that are showing and not yet answered, by reminder id. */
    val ringing: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Cue calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Check-in calls and reminders that ring like a phone call"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 900, 700, 900, 700, 900)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    fun callIntent(context: Context, req: CallRequest, accept: Boolean): Intent =
        Intent(context, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_KIND, req.kind).putExtra(EXTRA_REMINDER, req.reminderId ?: -1L).putExtra(EXTRA_ACCEPT, accept)
            .putExtra(EXTRA_TITLE, req.title).putExtra(EXTRA_CHIPS, req.chips.toTypedArray())

    fun ring(context: Context, req: CallRequest) {
        ensureChannel(context)
        val id = req.reminderId ?: -1L
        ringing += id
        val code = (id % 100_000).toInt()
        val full = PendingIntent.getActivity(context, 10 + code, callIntent(context, req, false), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val answer = PendingIntent.getActivity(context, 20 + code, callIntent(context, req, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val decline = PendingIntent.getBroadcast(context, 30 + code, Intent(context, CallActionReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_REMINDER, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val caller = Person.Builder().setName("Cue").setImportant(true).build()
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Cue · ${req.title}")
            .setContentText(req.chips.joinToString(" · ").ifBlank { req.subtitle })
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .setTimeoutAfter(RING_MS)
            .setColor(0xFF4B2FB5.toInt())
            .setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        val am = context.getSystemService(AlarmManager::class.java)
        val timeout = PendingIntent.getBroadcast(context, 40 + code, Intent(context, CallActionReceiver::class.java).setAction(ACTION_TIMEOUT).putExtra(EXTRA_REMINDER, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RING_MS + 2_000, timeout) }
            .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RING_MS + 2_000, timeout) }
        Log.i(TAG, "ringing ${req.kind} reminder=$id")
    }

    fun stopRinging(context: Context, reminderId: Long) {
        ringing -= reminderId
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(RingController.EXTRA_REMINDER, -1L)
        val wasRinging = id in RingController.ringing
        Log.i(TAG, "call action ${intent.action} reminder=$id wasRinging=$wasRinging")
        RingController.stopRinging(context, id)
        if (id <= 0 || !CueRuntime.ready) return
        if (intent.action == RingController.ACTION_TIMEOUT && !wasRinging) return
        val pending = goAsync()
        CueRuntime.scope.launch {
            try { CueRuntime.engine.onCallMissed(id, declined = intent.action == RingController.ACTION_DECLINE) } finally { pending.finish() }
        }
    }
}

/** Keeps the process at foreground priority with the microphone while a call is live. */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "cue_live"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ch) == null) nm.createNotificationChannel(NotificationChannel(ch, "Cue call in progress", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, ch).setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("Cue call in progress")
            .setContentIntent(open).setOngoing(true).setColor(0xFF4B2FB5.toInt()).build()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        runCatching {
            ServiceCompat.startForeground(this, 77, n, if (hasMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        }.onFailure { Log.w(TAG, "foreground start failed", it); stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) = runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
        fun stop(context: Context) = runCatching { context.stopService(Intent(context, CallService::class.java)) }
    }
}

/**
 * Voice for the rules brain and the cloud text path: Android's speech recognizer (on-device when available) and
 * text-to-speech. Half-duplex: never listens while speaking, so the agent does not hear itself.
 */
class VoiceIO(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    var onPartial: ((String) -> Unit)? = null
    var onLevel: ((Float) -> Unit)? = null
    var speakerOn = true
    val onDevice: Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun init(): Boolean = withContext(Dispatchers.Main) {
        if (ttsReady) return@withContext true
        suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val e = engine!!
                    val india = Locale.forLanguageTag("en-IN")
                    if (e.isLanguageAvailable(india) >= TextToSpeech.LANG_AVAILABLE) e.language = india else e.language = Locale.US
                    // The default voice is often a low-quality one: pick the best installed, offline English voice.
                    runCatching {
                        val best = e.voices.orEmpty()
                            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && "notInstalled" !in it.features }
                            .maxWithOrNull(compareBy<android.speech.tts.Voice>({ it.quality }, { if (it.locale.country == "IN") 2 else if (it.locale.country == "US") 1 else 0 }, { -it.latency }))
                        if (best != null) { e.voice = best; Log.i(TAG, "tts voice ${best.name} quality ${best.quality}") }
                    }
                    e.setSpeechRate(1.0f)
                    ttsReady = true
                }
                if (cont.isActive) cont.resume(ttsReady)
            }
            tts = engine
        }
    }

    suspend fun speak(text: String, onRange: ((Int) -> Unit)? = null) {
        if (text.isBlank()) return
        val t = tts ?: return
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val id = "u${System.nanoTime()}"
                t.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (speakerOn) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) { if (utteranceId == id) onRange?.invoke(end) }
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (cont.isActive) cont.resume(Unit) }
                })
                t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
                cont.invokeOnCancellation { t.stop() }
            }
        }
    }

    fun stopSpeaking() { tts?.stop() }

    /**
     * Which recognizer and language to listen with. The phone's on-device recognizer only works for languages whose
     * pack is installed (this razr fold had no English (India) pack, so every listen failed with error 13); a
     * language error moves to the next setup and the one that works is kept for the rest of the call.
     */
    private data class Asr(val onDevice: Boolean, val locale: String)
    private val setups: List<Asr> = buildList {
        if (onDevice) { add(Asr(true, "en-IN")); add(Asr(true, "en-US")) }
        if (SpeechRecognizer.isRecognitionAvailable(context)) { add(Asr(false, "en-IN")); add(Asr(false, "en-US")) }
    }
    private var setup = 0
    private var downloadAsked = false
    val asrLabel: String get() = setups.getOrNull(setup)?.let { (if (it.onDevice) "on-device " else "") + it.locale } ?: "none"

    private fun recognizerFor(a: Asr): SpeechRecognizer =
        if (a.onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)

    /** Asks the system once to fetch the on-device English (India) pack, so later calls can listen offline. */
    private fun askForPack() {
        if (downloadAsked || !onDevice || android.os.Build.VERSION.SDK_INT < 33) return
        downloadAsked = true
        runCatching {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN"))
            Log.i(TAG, "asked for the on-device en-IN speech pack")
            r.destroy()
        }.onFailure { Log.w(TAG, "pack download request failed", it) }
    }

    /** Listens for one utterance. Returns null on silence or error. */
    suspend fun listen(): String? = withContext(Dispatchers.Main) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "asr: no microphone permission"); return@withContext null
        }
        var busy = 0
        while (setup < setups.size) {
            val a = setups[setup]
            val rec = recognizer ?: recognizerFor(a).also { recognizer = it }
            val (text, error) = listenOnce(rec, a)
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && busy++ < 5) {
                // The previous session is still closing; give it a moment rather than giving up on this setup.
                runCatching { rec.cancel() }
                kotlinx.coroutines.delay(300)
                continue
            }
            if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) {
                if (a.onDevice && a.locale == "en-IN") askForPack()
                runCatching { rec.destroy() }; recognizer = null
                setup++; busy = 0
                kotlinx.coroutines.delay(250)
                Log.w(TAG, "asr: $a failed with $error, trying ${setups.getOrNull(setup)}")
                continue
            }
            return@withContext text
        }
        Log.e(TAG, "asr: no recognizer works on this phone")
        null
    }

    private suspend fun listenOnce(rec: SpeechRecognizer, a: Asr): Pair<String?, Int?> = suspendCancellableCoroutine { cont ->
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "asr ready ($a)") }
            override fun onBeginningOfSpeech() { Log.i(TAG, "asr speech begins") }
            override fun onRmsChanged(rmsdB: Float) { onLevel?.invoke(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { Log.i(TAG, "asr error $error ($a)"); if (cont.isActive) cont.resume(null to error) }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                Log.i(TAG, "asr heard '$text'")
                if (cont.isActive) cont.resume(text?.takeIf { it.isNotBlank() } to null)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onPartial?.invoke(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, a.locale)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        rec.startListening(intent)
        cont.invokeOnCancellation { runCatching { rec.cancel() } }
    }

    fun cancelListening() { runCatching { recognizer?.cancel() } }

    fun cue() = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 35).startTone(ToneGenerator.TONE_PROP_BEEP2, 120) }

    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}

/** Raw audio for the on-device model: 16 kHz mono from the microphone (as the spike app records), cut by an energy check. */
class PcmIO {
    /**
     * Records one utterance: waits up to [waitMs] for speech, then stops after [silenceMs] of quiet (or [maxMs], the
     * model's 30 s limit). The noise floor is measured first, so a quiet room and a noisy one both work. Returns the
     * speech with 300 ms either side, or null if nobody spoke. [stop] ends it early (the user switched to typing).
     */
    @SuppressLint("MissingPermission")
    suspend fun record(maxMs: Int = 30_000, waitMs: Int = 8_000, silenceMs: Int = 1_000, stop: () -> Boolean = { false }, onLevel: (Float) -> Unit = {},
                       onProgress: ((ShortArray) -> Unit)? = null): ShortArray? = withContext(Dispatchers.IO) {
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate)) }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { Log.w(TAG, "mic: could not open"); rec?.release(); return@withContext null }
        val out = ShortArrayBuilder(rate * 6)
        val buf = ShortArray(rate / 50) // 20 ms
        var base = -1.0 // the room's noise level (RMS), followed while nobody speaks
        var speechStart = -1
        var lastLoud = -1
        var total = 0
        rec.startRecording()
        try {
            while (total < maxMs && !stop()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                out.add(buf, n)
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val rms = kotlin.math.sqrt(sum / n)
                total += n * 1000 / rate
                base = if (base < 0) rms else base
                if (total <= 300) { base = base * 0.8 + rms * 0.2; continue }
                val noise = maxOf(base, 60.0)
                val loud = rms > maxOf(noise * 3.0, 350.0)
                onLevel((rms / (noise * 12)).toFloat().coerceIn(0f, 1f))
                if (loud) { if (speechStart < 0) speechStart = out.size - n; lastLoud = out.size }
                else if (speechStart < 0) base = base * 0.95 + rms * 0.05
                // The speech so far, every 1.5 s while the user talks (for a live caption).
                if (onProgress != null && speechStart >= 0 && (out.size - speechStart) >= rate && total % 1500 < 20 && (out.size - lastLoud) < rate / 3)
                    onProgress(out.slice(maxOf(0, speechStart - rate * 3 / 10), out.size))
                if (speechStart >= 0 && (out.size - lastLoud) * 1000 / rate >= silenceMs) break
                if (speechStart < 0 && total > waitMs) break
            }
        } finally { runCatching { rec.stop() }; rec.release() }
        if (speechStart < 0 || stop()) return@withContext null
        val pad = rate * 3 / 10
        val from = maxOf(0, speechStart - pad)
        val to = minOf(out.size, lastLoud + pad)
        if ((lastLoud - speechStart) < rate / 4) return@withContext null // under 250 ms of sound: a knock, not words
        Log.i(TAG, "mic: ${(to - from) * 1000 / rate} ms of speech")
        out.slice(from, to)
    }
}

private class ShortArrayBuilder(cap: Int) {
    private var a = ShortArray(cap)
    var size = 0
        private set
    fun add(src: ShortArray, n: Int) {
        if (size + n > a.size) a = a.copyOf(maxOf(a.size * 2, size + n))
        System.arraycopy(src, 0, a, size, n); size += n
    }
    fun slice(from: Int, to: Int): ShortArray = a.copyOfRange(from, to)
}

/**
 * Plays the model's speech (24 kHz) as it is generated, the way the spike app's player does: a one-second buffer
 * that starts playing once it is full, and nothing cut from the speech. Starting on the first chunk, as an earlier
 * version did, made the speech stall between chunks. It also keeps how loud each 10 ms of the speech is, so the
 * barge-in detector can tell Cue's own voice coming back into the microphone from the user talking over it.
 */
class StreamPlayer(voiceCall: Boolean = false) {
    private val rate = 24_000
    private val block = rate / 100 // 10 ms
    // On a live call the speech plays as a voice call, so the phone's echo canceller can take it out of the microphone.
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(if (voiceCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate * 2))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    private val queue = java.util.concurrent.LinkedBlockingQueue<ShortArray>()
    @Volatile var stopped = false
        private set
    @Volatile var started = false
        private set
    @Volatile private var written = 0L
    /** RMS of each 10 ms block pushed so far, in order. */
    private var env = FloatArray(1024)
    @Volatile private var envSize = 0
    private var partSum = 0.0
    private var partN = 0
    private val thread = Thread {
        track.play()
        while (true) {
            val chunk = queue.take()
            if (chunk.isEmpty() || stopped) break
            var off = 0
            // Non-blocking writes, so a stop never leaves this thread stuck on a paused track.
            while (off < chunk.size && !stopped) {
                val n = track.write(chunk, off, chunk.size - off, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) break
                off += n
                written += n
                if (off < chunk.size) Thread.sleep(10)
            }
        }
    }.apply { name = "cue-player"; isDaemon = true; start() }

    /** Samples pushed so far, and (sample, characters) marks: this much of the text belongs to the audio up to here. */
    @Volatile private var pushed = 0L
    private val marks = ArrayList<Pair<Long, Int>>()
    @Volatile private var done = false
    private var shownChars = 0

    /** The text so far ([chars] long) goes with the audio pushed so far (a live model sends both together). */
    fun mark(chars: Int) = synchronized(marks) { marks += pushed to chars }

    /** All the speech has been pushed; the highlight can spread the text over its real length. */
    fun markDone() { done = true }

    /** Characters heard so far, from the marks (live model). */
    fun spokenChars(): Int {
        val h = head.toLong()
        synchronized(marks) {
            if (marks.isEmpty()) return 0
            var i = marks.indexOfLast { it.first <= h }
            if (i < 0) return 0
            val (s0, c0) = marks[i]
            val next = marks.getOrNull(i + 1) ?: return c0
            val f = ((h - s0).toDouble() / (next.first - s0).coerceAtLeast(1)).coerceIn(0.0, 1.0)
            return (c0 + (next.second - c0) * f).toInt()
        }
    }

    /** Characters of a [total]-long line heard so far, by time (the on-device model writes its text ahead of its voice). */
    fun estimatedChars(total: Int): Int {
        val (first, last) = synchronized(this) {
            var a = -1; var b = -1
            for (j in 0 until envSize) if (env[j] > 400) { if (a < 0) a = j; b = j }
            a to b
        }
        if (first < 0) return 0
        val spokenMs = head * 1000L / rate - first * 10L
        if (spokenMs <= 0) return 0
        val est = if (done && last > first) (total * spokenMs / ((last - first) * 10L)).toInt() else (spokenMs * 14 / 1000).toInt()
        shownChars = maxOf(shownChars, est.coerceAtMost(total))
        return shownChars
    }

    fun push(pcm: ShortArray) {
        if (stopped || pcm.isEmpty()) return
        started = true
        pushed += pcm.size
        synchronized(this) {
            for (v in pcm) {
                partSum += v * v.toDouble(); partN++
                if (partN == block) {
                    if (envSize == env.size) env = env.copyOf(env.size * 2)
                    env[envSize] = kotlin.math.sqrt(partSum / partN).toFloat()
                    envSize++
                    partSum = 0.0; partN = 0
                }
            }
        }
        queue.put(pcm)
    }

    @Volatile var released = false
        private set
    private val head: Int get() = if (released) 0 else runCatching { track.playbackHeadPosition }.getOrDefault(0)

    /** Whether speech is coming out of the speaker now. */
    val audible: Boolean get() = !stopped && !released && started && head in 1 until written

    /** How loud Cue is at the speaker now (RMS, 16-bit scale): the loudest 10 ms around the playback head. */
    fun level(): Float {
        if (!audible) return 0f
        val i = head / block
        synchronized(this) {
            var m = 0f
            for (j in maxOf(0, i - 15)..minOf(envSize - 1, i + 5)) m = maxOf(m, env[j])
            return m
        }
    }

    /** Waits until everything pushed has been heard (or the player is stopped), then frees the track. */
    suspend fun finish() = withContext(Dispatchers.IO) {
        queue.put(ShortArray(0))
        thread.join()
        if (!stopped && written > 0) {
            // A short line may not fill the one-second start buffer: start playing whatever is there.
            runCatching { track.setStartThresholdInFrames(1) }
            val t0 = System.currentTimeMillis()
            while (!stopped && head < written && System.currentTimeMillis() - t0 < written * 1000 / rate + 1_500) kotlinx.coroutines.delay(20)
            Log.i(TAG, "player: played out ${System.currentTimeMillis() - t0} ms after the last chunk")
        }
        Log.i(TAG, "player: ${written * 1000 / rate} ms of speech${if (stopped) " (stopped)" else ""}, underruns ${runCatching { track.underrunCount }.getOrDefault(-1)}")
        released = true
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    /** Stops the speech now (the user talked over it). */
    fun cancel() {
        stopped = true
        queue.clear(); queue.put(ShortArray(0))
        runCatching { track.pause(); track.flush() }
    }
}

/**
 * Barge-in: listens while Cue speaks and reports when the user talks over it, then records what they say.
 *
 * The microphone also hears Cue's own voice from the speaker. The capture uses the voice-call input with the phone's
 * echo canceller where it has one, and on top of that the detector knows how loud Cue is at each moment
 * ([StreamPlayer.level]) and learns how much of it leaks back into the microphone. The user is talking over Cue only
 * when the microphone is clearly louder than that leak for a quarter of a second.
 */
class BargeIn(private val player: StreamPlayer) {
    private val rate = 16_000
    @Volatile var triggered = false
        private set
    @Volatile private var closed = false
    private val result = kotlinx.coroutines.CompletableDeferred<ShortArray?>()
    var onTrigger: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    private val thread = Thread {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate)) }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); result.complete(null); return@Thread }
        val aec = runCatching { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val buf = ShortArray(rate / 50) // 20 ms
        val keep = ShortArrayBuilder(rate * 4)
        // Microphone level per unit of Cue's level: the upper end of the leak, learned while Cue talks and the user
        // doesn't. Starts cautious (on the razr fold the leak measured 0.15-0.2 with the echo canceller on).
        var coupling = 0.3
        var noise = -1.0
        var run = 0
        var gap = 0
        var speechStart = -1
        var lastLoud = -1
        var frames = 0
        var statM = 0.0; var statR = 0.0; var statN = 0
        rec.startRecording()
        try {
            while (!closed) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                keep.add(buf, n)
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val m = kotlin.math.sqrt(sum / n)
                frames++
                if (triggered) {
                    // Recording what the user is saying until they pause.
                    val quiet = m < maxOf(noise * 3, 300.0)
                    if (!quiet) lastLoud = keep.size
                    if ((keep.size - lastLoud) * 1000 / rate >= 1_000 || keep.size - speechStart > rate * 30) break
                    continue
                }
                val r = player.level().toDouble()
                if (noise < 0) noise = m
                if (r < 50) { if (run == 0) noise = noise * 0.95 + m * 0.05 }
                else if (run == 0 && r > 300) {
                    // Rises fast and falls slowly, so it follows the loud end of the leak, not its average; a jump of
                    // more than 3x is more likely the user than the leak, so it only counts as 3x.
                    val ratio = minOf(m / r, coupling * 3)
                    coupling = if (ratio > coupling) coupling + 0.08 * (ratio - coupling) else coupling - 0.005 * (coupling - ratio)
                    coupling = coupling.coerceIn(0.05, 1.5)
                }
                val expected = coupling * r + maxOf(noise, 40.0)
                val loud = m > maxOf(expected * 2.0, maxOf(noise, 40.0) * 4, 450.0)
                statM += m; statR += r; statN++
                if (frames % 50 == 0) { Log.i(TAG, "barge: mic %.0f, cue %.0f, leak %.3f, noise %.0f".format(statM / statN, statR / statN, coupling, noise)); statM = 0.0; statR = 0.0; statN = 0 }
                if (loud) { if (run == 0) speechStart = keep.size - n; run++; gap = 0; lastLoud = keep.size }
                else if (run > 0 && ++gap > 2) { run = 0; gap = 0 }
                if (run >= 12 && player.started) {
                    triggered = true
                    Log.i(TAG, "barge: the user is talking over Cue (mic %.0f, cue %.0f, leak %.3f)".format(m, r, coupling))
                    onTrigger?.invoke()
                }
            }
        } finally {
            runCatching { rec.stop() }; rec.release(); aec?.release()
        }
        if (!triggered) { result.complete(null); return@Thread }
        val from = maxOf(0, speechStart - rate * 3 / 10)
        val to = minOf(keep.size, lastLoud + rate * 3 / 10)
        Log.i(TAG, "barge: ${(to - from) * 1000 / rate} ms of the user's speech")
        result.complete(keep.slice(from, to))
    }.apply { name = "cue-barge-in"; isDaemon = true; start() }

    /** Cue finished speaking without being interrupted: stop listening. */
    fun close() { if (!triggered) closed = true }

    /** What the user said over Cue, once they pause; null if they didn't. */
    suspend fun utterance(): ShortArray? = result.await()
}

/**
 * The microphone on a live call, 16 kHz in 40 ms chunks. It records with the speech-recognition input, which keeps
 * the user's voice clean (the voice-call input's processing chopped far-away speech into fragments on the razr
 * fold). That input has no echo cancelling, so while Cue is talking ([cueLevel] > 0) the model gets silence instead,
 * unless the user is clearly talking over Cue: the same leak-aware check as [BargeIn] (the microphone well above
 * how much of Cue's voice comes back into it, for 240 ms). Then the last 300 ms and everything after go to the model,
 * and [onBargeIn] stops Cue at once.
 */
class MicStream(
    private val onChunk: (ShortArray) -> Unit,
    private val onLevel: (Float) -> Unit = {},
    private val dump: java.io.File? = null,
    private val mode: String = "gated",
    private val cueLevel: () -> Float = { 0f },
    private val onBargeIn: () -> Unit = {},
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val rate = 16_000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val source = when (mode) { "vc" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION; "mic" -> MediaRecorder.AudioSource.MIC; else -> MediaRecorder.AudioSource.VOICE_RECOGNITION }
            val rec = runCatching { AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate)) }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { Log.w(TAG, "mic stream: could not open"); rec?.release(); return@Thread }
            val gated = mode == "gated"
            Log.i(TAG, "mic stream: on, mode $mode")
            // Debug builds keep the last call's microphone audio as the model got it (files/voice/mic.pcm).
            val dumpOut = dump?.let { runCatching { it.parentFile?.mkdirs(); java.io.BufferedOutputStream(java.io.FileOutputStream(it)) }.getOrNull() }
            val buf = ShortArray(rate / 25)
            val recent = ArrayDeque<ShortArray>() // the last 300 ms, sent first when the user talks over Cue
            var coupling = 0.3
            var noise = -1.0
            var run = 0
            var gap = 0
            var talkingOver = false
            var cueQuietSince = 0L
            var secSum = 0.0; var secN = 0; var secPeak = 0.0; var secSent = 0
            // Automatic gain for what is sent: the speech-recognition input has none, and quiet speech (rms 200-350
            // a metre away) was often misheard by the live model. Brings speech towards rms 2500, at most 8x.
            var speechLevel = 600.0
            var gain = 1.0
            // Noise gate: between words the model gets true silence, so the room's hum (rms ~200 here, ~800 after
            // the gain) can't hide the end of the user's turn. Opens on speech with the 300 ms before it.
            var lastSpeechAt = 0L
            var gateOpen = false
            rec.startRecording()
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val chunk = buf.copyOf(n)
                    var sum = 0.0
                    for (i in 0 until n) sum += chunk[i] * chunk[i].toDouble()
                    val m = kotlin.math.sqrt(sum / n)
                    onLevel((m / 2500).toFloat().coerceIn(0f, 1f))
                    val r = if (gated) cueLevel().toDouble() else 0.0
                    val now = System.currentTimeMillis()
                    if (r >= 50) cueQuietSince = 0L else if (cueQuietSince == 0L) cueQuietSince = now
                    // Cue's voice keeps echoing for a moment after it stops.
                    val cueTalking = r >= 50 || (cueQuietSince != 0L && now - cueQuietSince < 250)
                    var send = true
                    if (cueTalking && !talkingOver) {
                        if (r > 300 && run == 0) {
                            val ratio = minOf(m / r, coupling * 3)
                            coupling = (if (ratio > coupling) coupling + 0.08 * (ratio - coupling) else coupling - 0.005 * (coupling - ratio)).coerceIn(0.05, 2.0)
                        }
                        val floor = if (noise < 0) 200.0 else maxOf(noise, 40.0)
                        val loud = m > maxOf((coupling * r + floor) * 2.0, floor * 4, 450.0)
                        if (loud) { run++; gap = 0 } else if (run > 0 && ++gap > 1) { run = 0; gap = 0 }
                        if (run >= 6) {
                            talkingOver = true
                            Log.i(TAG, "mic stream: the user is talking over Cue (mic %.0f, cue %.0f, leak %.2f)".format(m, r, coupling))
                            onBargeIn()
                            recent.forEach { val o = amplify(it, gain); onChunk(o); dumpOut?.write(bytes(o)) }
                            recent.clear()
                        } else send = false
                    } else if (!cueTalking) {
                        talkingOver = false; run = 0
                        // The room's level, learned from the quiet moments only.
                        noise = when { noise < 0 -> m; m < noise -> noise * 0.85 + m * 0.15; m < noise * 1.5 -> noise * 0.98 + m * 0.02; else -> noise }
                    }
                    val speechNow = noise > 0 && m > maxOf(noise * 1.5, 150.0)
                    if (!cueTalking && speechNow) speechLevel = speechLevel * 0.9 + m * 0.1
                    gain = gain * 0.8 + (2500.0 / speechLevel).coerceIn(1.0, 8.0) * 0.2
                    if (speechNow) lastSpeechAt = now
                    val voiced = talkingOver || now - lastSpeechAt < 450
                    if (send && voiced && !gateOpen) { recent.forEach { onChunk(amplify(it, gain)) } } // the start of the word
                    gateOpen = send && voiced
                    val out = if (gateOpen) amplify(chunk, gain) else ShortArray(n)
                    if (gateOpen) secSent++
                    onChunk(out)
                    dumpOut?.write(bytes(out))
                    recent.addLast(chunk); while (recent.size > 8) recent.removeFirst()
                    secSum += m; secN++; secPeak = maxOf(secPeak, m)
                    if (secN == 25) { Log.i(TAG, "mic stream: level %.0f, peak %.0f, sent %d/25, room %.0f, leak %.2f, gain %.1f".format(secSum / secN, secPeak, secSent, noise, coupling, gain)); secSum = 0.0; secN = 0; secPeak = 0.0; secSent = 0 }
                }
            } finally { runCatching { rec.stop() }; rec.release(); runCatching { dumpOut?.close() } }
        }.apply { name = "cue-mic"; isDaemon = true; start() }
    }

    private fun amplify(pcm: ShortArray, g: Double): ShortArray =
        if (g <= 1.01) pcm else ShortArray(pcm.size) { i -> (pcm[i] * g).toInt().coerceIn(-32768, 32767).toShort() }

    private fun bytes(pcm: ShortArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pcm)
        return bb.array()
    }

    fun stop() { running = false }
}

/** Puts the phone in voice-call audio mode on the loudspeaker for a live call (so echo cancelling applies), and back after. */
class CallAudio(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val before = am.mode
    init {
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
        }.onFailure { Log.w(TAG, "call audio: ${it.message}") }
    }
    fun release() { runCatching { am.clearCommunicationDevice(); am.mode = before } }
}

fun isNightKind(kind: String, hour: Int): Boolean = kind == ReminderKind.CHECKIN_NIGHT || (kind == "TALK" && (hour >= 20 || hour < 5))
