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
            .setColor(0xFF0D4B48.toInt())
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
            .setContentIntent(open).setOngoing(true).setColor(0xFF0D4B48.toInt()).build()
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

    suspend fun speak(text: String) {
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
    suspend fun record(maxMs: Int = 30_000, waitMs: Int = 8_000, silenceMs: Int = 1_000, stop: () -> Boolean = { false }, onLevel: (Float) -> Unit = {}): ShortArray? = withContext(Dispatchers.IO) {
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
 * that starts playing once it is full, blocking writes, and nothing cut from the speech. Starting on the first
 * chunk, as an earlier version did, made the speech stall between chunks.
 */
class StreamPlayer {
    private val rate = 24_000
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate * 2))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    private val queue = java.util.concurrent.LinkedBlockingQueue<ShortArray>()
    @Volatile private var stopped = false
    @Volatile var started = false
        private set
    @Volatile private var written = 0L
    private val thread = Thread {
        track.play()
        while (true) {
            val chunk = queue.take()
            if (chunk.isEmpty()) break
            track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            written += chunk.size
        }
    }.apply { name = "cue-player"; isDaemon = true; start() }

    fun push(pcm: ShortArray) { if (!stopped && pcm.isNotEmpty()) { started = true; queue.put(pcm) } }

    /** Waits until everything pushed has been heard, then frees the track. */
    suspend fun finish() = withContext(Dispatchers.IO) {
        queue.put(ShortArray(0))
        thread.join()
        if (!stopped && written > 0) {
            runCatching { track.stop() } // in stream mode, stop() plays out what is buffered
            val t0 = System.currentTimeMillis()
            while (track.playbackHeadPosition < written && System.currentTimeMillis() - t0 < written * 1000 / rate + 2_000) kotlinx.coroutines.delay(20)
        }
        Log.i(TAG, "player: ${written * 1000 / rate} ms of speech, underruns ${runCatching { track.underrunCount }.getOrDefault(-1)}")
        track.release()
    }

    fun cancel() { stopped = true; queue.clear(); queue.put(ShortArray(0)); runCatching { track.pause(); track.flush() } }
}

fun isNightKind(kind: String, hour: Int): Boolean = kind == ReminderKind.CHECKIN_NIGHT || (kind == "TALK" && (hour >= 20 || hour < 5))
