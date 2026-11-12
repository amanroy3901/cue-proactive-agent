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
                    val india = Locale.forLanguageTag("en-IN")
                    if (engine!!.isLanguageAvailable(india) >= TextToSpeech.LANG_AVAILABLE) engine!!.language = india else engine!!.language = Locale.US
                    engine!!.setSpeechRate(1.02f)
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

    /** Listens for one utterance. Returns null on silence or error. */
    suspend fun listen(): String? = withContext(Dispatchers.Main) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@withContext null
        if (!SpeechRecognizer.isRecognitionAvailable(context) && !onDevice) return@withContext null
        val rec = recognizer ?: (if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)).also { recognizer = it }
        suspendCancellableCoroutine { cont ->
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { onLevel?.invoke(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (cont.isActive) cont.resume(text?.takeIf { it.isNotBlank() })
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onPartial?.invoke(it) }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            rec.startListening(intent)
            cont.invokeOnCancellation { runCatching { rec.cancel() } }
        }
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

/** Raw audio for the on-device model: 16 kHz mono capture with an energy end-of-speech check, 24 kHz playback. */
class PcmIO {
    @SuppressLint("MissingPermission")
    suspend fun record(maxMs: Int = 15_000, silenceMs: Int = 900, onLevel: (Float) -> Unit = {}): ShortArray? = withContext(Dispatchers.IO) {
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4) }.getOrNull() ?: return@withContext null
        val out = ArrayList<Short>(rate * 8)
        val buf = ShortArray(rate / 50)
        var heardSpeech = false
        var quietMs = 0
        var total = 0
        rec.startRecording()
        try {
            while (total < maxMs) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                var sum = 0.0
                for (i in 0 until n) { out += buf[i]; sum += buf[i] * buf[i].toDouble() }
                val rms = kotlin.math.sqrt(sum / n)
                onLevel((rms / 3000).toFloat().coerceIn(0f, 1f))
                val ms = n * 1000 / rate
                total += ms
                if (rms > 900) { heardSpeech = true; quietMs = 0 } else if (heardSpeech) quietMs += ms
                if (heardSpeech && quietMs >= silenceMs) break
                if (!heardSpeech && total > 7_000) break
            }
        } finally { rec.stop(); rec.release() }
        if (!heardSpeech) null else out.toShortArray()
    }

    suspend fun play(chunks: List<ShortArray>) = withContext(Dispatchers.IO) {
        val all = chunks.flatMap { it.asList() }
        if (all.isEmpty()) return@withContext
        // Skip the model's near-silent lead so the first word comes sooner.
        val start = all.indexOfFirst { kotlin.math.abs(it.toInt()) > 600 }.coerceAtLeast(0)
        val pcm = all.subList(start, all.size).toShortArray()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        track.write(pcm, 0, pcm.size)
        track.play()
        kotlinx.coroutines.delay(pcm.size * 1000L / 24_000 + 150)
        track.release()
    }
}

/**
 * Plays the model's speech as it is generated (24 kHz), so the first word is heard as soon as it exists rather than
 * after the whole answer. Drops the near-silent lead the model starts with.
 */
class StreamPlayer {
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(24_000 * 2 * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
    private val queue = java.util.concurrent.LinkedBlockingQueue<ShortArray>()
    @Volatile private var closed = false
    @Volatile var started = false
        private set
    private var written = 0L
    private val thread = Thread {
        var lead = true
        while (true) {
            val chunk = queue.take()
            if (chunk.isEmpty()) break
            var c = chunk
            if (lead) {
                val i = c.indexOfFirst { kotlin.math.abs(it.toInt()) > 600 }
                if (i < 0) continue
                c = c.copyOfRange(i, c.size); lead = false
            }
            if (!started) { track.play(); started = true }
            track.write(c, 0, c.size)
            written += c.size
        }
    }.apply { isDaemon = true; start() }

    fun push(pcm: ShortArray) { if (!closed && pcm.isNotEmpty()) queue.put(pcm) }

    /** Waits until everything pushed has been heard, then frees the track. */
    suspend fun finish() = withContext(Dispatchers.IO) {
        closed = true
        queue.put(ShortArray(0))
        thread.join()
        if (started) {
            while (track.playbackHeadPosition < written && track.playState == AudioTrack.PLAYSTATE_PLAYING) kotlinx.coroutines.delay(50)
            track.stop()
        }
        track.release()
    }

    fun cancel() { closed = true; queue.clear(); queue.put(ShortArray(0)); runCatching { track.pause(); track.flush() } }
}

fun isNightKind(kind: String, hour: Int): Boolean = kind == ReminderKind.CHECKIN_NIGHT || (kind == "TALK" && (hour >= 20 || hour < 5))
