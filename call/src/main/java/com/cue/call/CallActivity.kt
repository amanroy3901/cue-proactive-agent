package com.cue.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.text.withStyle
import com.cue.agent.CallCard
import com.cue.agent.CallFlow
import com.cue.agent.DuplexUi
import com.cue.agent.TurnListener
import com.cue.agent.CueRuntime
import com.cue.agent.TextKit
import com.cue.domain.ChecklistEntry
import com.cue.domain.ItemType
import com.cue.domain.ReminderKind
import com.cue.ui.AppTint
import com.cue.ui.AppTints
import com.cue.ui.CheckRow
import com.cue.ui.Cue
import com.cue.ui.Pixel
import com.cue.ui.PixelMood
import com.cue.ui.pixelMoodForTool
import com.cue.ui.CueTheme
import com.cue.ui.Pill
import com.cue.ui.PrimaryButton
import com.cue.ui.SoftButton
import com.cue.ui.Txt
import com.cue.ui.Type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Phase { RINGING, CONNECTING, IN_CALL, ENDED }

private fun AppTint.pair() = light to deep

/** Plain-words label and colour for each thing a call saved. */
fun summaryLabel(tool: String): Pair<String, Pair<Color, Color>> = when (tool) {
    "checklist_add" -> "Added to your list" to AppTints.lists.pair()
    "checklist_tick" -> "Ticked off" to AppTints.lists.pair()
    "habit_add" -> "New habit" to AppTints.habits.pair()
    "habit_remove", "habit_rename" -> "Habit updated" to AppTints.habits.pair()
    "checklist_edit", "checklist_untick", "list_create", "list_rename", "list_delete" -> "List updated" to AppTints.lists.pair()
    "pending_update", "pending_done", "pending_remove" -> "People updated" to AppTints.people.pair()
    "reminder_move", "reminder_edit", "reminder_cancel", "practice_schedule" -> "Reminder changed" to AppTints.reminders.pair()
    "note_edit", "note_remove" -> "Note updated" to AppTints.habits.pair()
    "journal_add" -> "Added to your journal" to AppTints.journal.pair()
    "album_create", "item_move" -> "Albums updated" to AppTints.albums.pair()
    "focus_start", "focus_stop" -> "Focus" to AppTints.focus.pair()
    "checklist_remove" -> "Removed from your list" to AppTints.lists.pair()
    "note_save" -> "Saved a note" to AppTints.habits.pair()
    "checkin_time" -> "Daily call moved" to AppTints.reminders.pair()
    "reminder_set" -> "Reminder set" to AppTints.reminders.pair()
    "event_move" -> "Flight moved" to AppTints.albums.pair()
    "habit_checkin" -> "Habit logged" to AppTints.habits.pair()
    "dose_schedule" -> "Medicine reminders set" to AppTints.reminders.pair()
    "dose_taken" -> "Dose recorded" to AppTints.reminders.pair()
    "pending_add" -> "Remembered for later" to AppTints.people.pair()
    "pending_resolve" -> "Marked done" to AppTints.people.pair()
    "album_search" -> "Found it" to AppTints.albums.pair()
    "journal_lookup" -> "Read your journal" to AppTints.journal.pair()
    "journal_write" -> "Today's journal written" to AppTints.journal.pair()
    "renewal_remind" -> "Renewal reminder set" to AppTints.renewals.pair()
    "practice_note" -> "Practice notes" to AppTints.practice.pair()
    "medicine_stop" -> "Medicine reminders stopped" to AppTints.reminders.pair()
    "measure_log" -> "Reading saved" to AppTints.renewals.pair()
    "measure_schedule" -> "Reading reminders set" to AppTints.renewals.pair()
    "remember" -> "Cue will remember" to AppTints.albums.pair()
    "profile_forget" -> "Forgotten" to AppTints.albums.pair()
    "follow_up" -> "I'll check back" to AppTints.reminders.pair()
    "rule_add", "rule_remove" -> "When-this-happens rule" to AppTints.focus.pair()
    "promise_check" -> "Needs a time" to AppTints.reminders.pair()
    "learned" -> "Picked up about you" to AppTints.albums.pair()
    else -> "Nothing new to file" to (Color(0xFFADA6C4) to Cue.muted)
}
enum class Live { SPEAKING, LISTENING, THINKING, IDLE }

/** One line of the conversation on screen. Its text grows while it is being said; [spoken] = characters heard so far. */
class Line(val who: String, text: String, val card: CallCard? = null) {
    var text by mutableStateOf(text)
    /** -1 when this line is not being spoken now; else how many characters of it have been heard. */
    var spoken by mutableStateOf(-1)
}

/** The call screen: shown over the lock screen for a ring, and for Talk-to-Cue from the app. */
class CallActivity : ComponentActivity() {
    private var phase by mutableStateOf(Phase.RINGING)
    private var live by mutableStateOf(Live.IDLE)
    /** What the user is saying right now, before their turn is over (live caption). */
    private var partial by mutableStateOf("")
    private var level by mutableStateOf(0f)
    private var seconds by mutableStateOf(0)
    private var typing by mutableStateOf(false)
    private var brainLabel by mutableStateOf("")
    private val lines = mutableStateListOf<Line>()
    /** Bumped after every turn so the cards on screen reload what is saved. */
    private var cardVersion by mutableStateOf(0)
    /** Pixel acts out the last tool call for a couple of seconds, then goes back to the live state. */
    private var action by mutableStateOf<PixelMood?>(null)
    private var actionStamp by mutableStateOf(0L)
    private val changes = mutableStateListOf<String>()
    /** Facts Cue picked up on this call, waiting for Keep or Remove. */
    private val learned = mutableStateListOf<com.cue.domain.ProfileFact>()

    private var kind = "TALK"
    private var reminderId: Long? = null
    private var title = "Talk to Cue"
    private var chips: List<String> = emptyList()
    private var flow: CallFlow? = null
    private var voice: VoiceIO? = null
    private val pcm = PcmIO()
    private var loopJob: Job? = null
    private var typedQueue: String? = null
    /** What the user said over Cue (barge-in): the next turn, already recorded. */
    private var bargedAudio: ShortArray? = null
    private var mic: MicStream? = null
    private var callAudio: CallAudio? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startCall() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        readIntent(intent)
        setContent { CueTheme { Screen() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (phase == Phase.ENDED) {
            // A new call arrived while the last call's summary was showing: start fresh.
            loopJob?.cancel(); voice?.release(); voice = null; flow = null
            lines.clear(); changes.clear(); seconds = 0; partial = ""; typing = false; brainLabel = ""
            phase = Phase.RINGING
        }
        if (phase == Phase.RINGING) readIntent(intent)
    }

    private fun readIntent(i: android.content.Intent) {
        kind = i.getStringExtra(RingController.EXTRA_KIND) ?: "TALK"
        reminderId = i.getLongExtra(RingController.EXTRA_REMINDER, -1L).takeIf { it > 0 }
        title = i.getStringExtra(RingController.EXTRA_TITLE) ?: if (kind == "TALK") "Talk to Cue" else "Cue"
        chips = i.getStringArrayExtra(RingController.EXTRA_CHIPS)?.toList().orEmpty()
        if (i.getBooleanExtra(RingController.EXTRA_ACCEPT, false)) accept()
    }

    private val night: Boolean get() = isNightKind(kind, java.time.LocalTime.now().hour)

    private fun accept() {
        if (phase != Phase.RINGING) return
        RingController.stopRinging(this, reminderId ?: -1L)
        phase = Phase.CONNECTING
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        else startCall()
    }

    private fun decline() {
        RingController.stopRinging(this, reminderId ?: -1L)
        val id = reminderId
        if (id != null && CueRuntime.ready) CueRuntime.scope.launch { CueRuntime.engine.onCallMissed(id, declined = true) }
        finish()
    }

    private fun snooze() {
        RingController.stopRinging(this, reminderId ?: -1L)
        val id = reminderId
        if (id != null && CueRuntime.ready) CueRuntime.scope.launch { CueRuntime.engine.snooze(id, 10) }
        finish()
    }

    // ------------------------------------------------------------------ conversation on screen

    private fun addLine(who: String, text: String): Line = Line(who, text).also { lines += it }

    /** Shows a card in the conversation; the same card asked for again moves down to the latest place. */
    private fun addCard(card: CallCard) {
        lines.removeAll { it.card != null && sameCard(it.card, card) }
        lines += Line("card", "", card)
        cardVersion++
        action = pixelMoodForTool(card::class.simpleName.orEmpty().lowercase())
        actionStamp = System.currentTimeMillis()
    }

    private fun sameCard(a: CallCard, b: CallCard): Boolean = when {
        a is CallCard.Checklist && b is CallCard.Checklist -> a.itemId == b.itemId
        a is CallCard.Journal && b is CallCard.Journal -> true
        a is CallCard.People && b is CallCard.People -> a.personId == b.personId
        a is CallCard.Found && b is CallCard.Found -> a.itemIds == b.itemIds
        else -> a::class == b::class
    }

    /** Keeps [line]'s highlight on the word being heard, from [spokenNow], until [done] says the speech is over. */
    private fun follow(line: Line, spokenNow: () -> Int, done: () -> Boolean): Job = lifecycleScope.launch {
        while (!done()) { line.spoken = spokenNow().coerceIn(0, line.text.length); delay(60) }
        line.spoken = -1
    }

    // ------------------------------------------------------------------ the call

    private fun startCall() {
        CallService.start(this)
        val v = VoiceIO(this).also { voice = it }
        v.onPartial = { partial = it }
        v.onLevel = { level = ((it + 2f) / 12f).coerceIn(0f, 1f) }
        loopJob = lifecycleScope.launch {
            launch { while (true) { delay(1000); if (phase == Phase.IN_CALL) seconds++ } }
            v.init()
            val engine = CueRuntime.engine
            val f = engine.startCall(kind, reminderId)
            flow = f
            val opening = f.opening()
            if (f.brainName == "cloud" && runDuplex(f, opening)) return@launch
            brainLabel = if (f.usingModel) "LFM3 on device" else if (v.onDevice) "on-device voice" else "phone voice"
            phase = Phase.IN_CALL
            speak(addLine("cue", opening))
            turnLoop(f, v)
        }
    }

    /**
     * A live call with a realtime model (Gemini Live): the microphone streams the whole time, the model decides when
     * the user has finished, talks back, and stops by itself when interrupted. Returns false if the session didn't
     * start, so the call goes on turn by turn.
     */
    private suspend fun runDuplex(f: CallFlow, opening: String): Boolean {
        val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var player: StreamPlayer? = null
        var cueLine: Line? = null
        var spokenChars = 0
        var endRequested = false
        var lastActivity = System.currentTimeMillis()
        fun ui(block: () -> Unit) = runOnUiThread(block)
        fun finishTurn() {
            val p = player ?: return
            player = null
            val line = cueLine
            lifecycleScope.launch {
                p.finish()
                line?.spoken = -1
                if (player == null) live = Live.LISTENING
                if (endRequested && player == null) endCall()
            }
        }
        val started = f.startDuplex(object : DuplexUi {
            override fun onReady() { ready.complete(true) }
            override fun onUserText(text: String, final: Boolean) = ui {
                lastActivity = System.currentTimeMillis()
                if (final) { partial = ""; addLine("user", text) } else { partial = text; live = Live.LISTENING }
            }
            override fun onModelText(delta: String) = ui {
                val line = cueLine ?: addLine("cue", "").also { cueLine = it }
                line.text += delta
                spokenChars += delta.length
                player?.mark(spokenChars)
            }
            override fun onAudio(pcm: ShortArray) {
                lastActivity = System.currentTimeMillis()
                val p = player ?: StreamPlayer().also { np ->
                    np.mark(spokenChars)
                    player = np
                    ui {
                        live = Live.SPEAKING
                        val line = cueLine ?: addLine("cue", "").also { cueLine = it }
                        follow(line, { np.spokenChars() }, { np.released })
                    }
                }
                p.push(pcm)
            }
            override fun onInterrupted() = ui { stopCue() }
            fun stopCue() {
                player?.cancel(); player = null
                cueLine?.let { it.spoken = -1; if (it.text.isNotBlank()) it.text = it.text.trimEnd() + " —" }
                cueLine = null; spokenChars = 0
                live = Live.LISTENING
            }
            override fun onTurnComplete() = ui {
                finishTurn()
                cueLine = null; spokenChars = 0
                cardVersion++
            }
            override fun onCard(card: CallCard) = ui { addCard(card) }
            override fun onEndRequested() = ui { endRequested = true; if (player == null) lifecycleScope.launch { delay(400); if (player == null) endCall() } }
            override fun onClosed(reason: String?) = ui {
                if (!ready.isCompleted) ready.complete(false)
                else if (phase == Phase.IN_CALL && reason != null) {
                    CueRuntime.engine.log("live call dropped: $reason")
                    addLine("cue", "The connection dropped. Call me again in a moment.")
                    lifecycleScope.launch { delay(1500); endCall() }
                }
            }
        })
        if (!started) return false
        val ok = kotlinx.coroutines.withTimeoutOrNull(8_000) { ready.await() } == true
        if (!ok) { f.duplex?.close(); CueRuntime.engine.log("live call didn't start; going turn by turn"); return false }
        brainLabel = "Gemini live"
        phase = Phase.IN_CALL
        live = Live.THINKING
        f.duplex?.prompt("(The call just connected. Greet the user by saying this, naturally: \"$opening\")")
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        mic = MicStream(onChunk = { if (!typing) f.duplex?.sendAudio(it) }, onLevel = { level = it },
            cueLevel = { player?.level() ?: 0f },
            onBargeIn = { runOnUiThread { player?.cancel(); player = null; cueLine?.let { it.spoken = -1; if (it.text.isNotBlank()) it.text = it.text.trimEnd() + " —" }; cueLine = null; spokenChars = 0; live = Live.LISTENING } },
            dump = if (debuggable) java.io.File(filesDir, "voice/mic.pcm") else null,
            mode = java.io.File(filesDir, "mic_mode").takeIf { debuggable && it.exists() }?.readText()?.trim() ?: "gated").also { it.start() }
        // Silence: a gentle check after 20 s, then hang up after another 20 s.
        var nudged = false
        while (phase == Phase.IN_CALL) {
            delay(1000)
            typedQueue?.let { t -> typedQueue = null; f.duplex?.sendText(t); lastActivity = System.currentTimeMillis(); nudged = false }
            val quiet = System.currentTimeMillis() - lastActivity
            if (player != null || live == Live.SPEAKING) continue
            if (quiet > 20_000 && !nudged) { nudged = true; lastActivity = System.currentTimeMillis(); f.duplex?.prompt("(The user has been quiet for a while. Briefly check whether they are still there.)") }
            else if (quiet > 20_000 && nudged) { endCall(); break }
        }
        return true
    }

    /** Turn by turn, for the on-device model and the rules: listen, answer, listen. */
    private suspend fun turnLoop(f: CallFlow, v: VoiceIO) {
        var silentSince = System.currentTimeMillis()
        var nudged = false
        while (phase == Phase.IN_CALL) {
            live = Live.LISTENING
            partial = ""
            val queued = typedQueue.also { typedQueue = null }
            val listenStart = System.currentTimeMillis()
            var heardText: String? = null
            var audio: ShortArray? = null
            val barged = bargedAudio.also { bargedAudio = null }
            if (barged != null) audio = barged
            else if (queued != null) heardText = queued
            else if (typing) { delay(250); silentSince = System.currentTimeMillis(); continue }
            else if (f.usingModel && f.hearsAudio) {
                // The model hears the user's own voice; a quick transcript of it so far is shown while they talk.
                var previewing = false
                audio = pcm.record(stop = { typing || typedQueue != null || phase != Phase.IN_CALL }, onLevel = { level = it }, onProgress = { soFar ->
                    if (!previewing) {
                        previewing = true
                        lifecycleScope.launch { f.preview(soFar)?.takeIf { it.isNotBlank() }?.let { if (live == Live.LISTENING) partial = it }; previewing = false }
                    }
                })
                f.cancelPreview()
            } else heardText = v.listen()
            if (phase != Phase.IN_CALL) break
            if (heardText == null && audio == null) {
                if (typedQueue != null || typing) continue
                // A recognizer error that comes back instantly is not silence; wait a moment and listen again.
                if (System.currentTimeMillis() - listenStart < 1500) { delay(600); continue }
                val quiet = System.currentTimeMillis() - silentSince
                if (quiet > 22_000 && nudged) { speak(addLine("cue", "I'll let you go. Talk soon.")); endCall(); break }
                if (quiet > 7_000 && !nudged) { nudged = true; speak(addLine("cue", "Are you there?")) }
                continue
            }
            live = Live.THINKING
            v.cue()
            var userLine: Line? = heardText?.let { partial = ""; addLine("user", it) }
            if (userLine == null && partial.isBlank()) partial = "…"
            val player = if (f.usingModel) StreamPlayer() else null
            val barge = player?.let { listenOver(it) }
            var cueLine: Line? = null
            var following: Job? = null
            val listener = object : TurnListener {
                override fun onHeard(text: String) = runOnUiThread { if (userLine == null) { partial = ""; userLine = addLine("user", text) } }
                override fun onPartial(text: String) = runOnUiThread {
                    val line = cueLine ?: addLine("cue", "").also { cueLine = it }
                    line.text = text
                }
                override fun onAudio(pcm: ShortArray) {
                    val p = player ?: return
                    if (!p.stopped) live = Live.SPEAKING
                    p.push(pcm)
                    if (following == null) runOnUiThread {
                        if (following == null) {
                            val line = cueLine ?: addLine("cue", "").also { cueLine = it }
                            following = follow(line, { p.estimatedChars(line.text.length) }, { p.released })
                        }
                    }
                }
            }
            val reply = if (audio != null) f.userAudio(audio, listener) else f.userSaid(heardText!!, listener)
            partial = ""
            if (audio != null && reply.heard == null) { player?.cancel(); cueLine?.let { lines.remove(it) }; continue } // a sound with no words
            silentSince = System.currentTimeMillis()
            nudged = false
            val line = (cueLine ?: addLine("cue", reply.spoken)).also { it.text = reply.spoken }
            reply.card?.let { addCard(it) }
            cardVersion++
            if (player != null && reply.streamed) {
                live = Live.SPEAKING
                player.markDone()
                player.finish()
            } else {
                player?.cancel()
                live = Live.SPEAKING
                voice?.speak(reply.spoken) { end -> line.spoken = end }
                line.spoken = -1
            }
            if (barge != null && afterSpeech(barge)) { line.text = reply.spoken.trimEnd('.', ' ') + " —"; continue }
            live = Live.IDLE
            if (reply.endCall) { endCall(); break }
        }
    }

    /** Says a fixed line: in the model's own voice when it is on the call, else with the phone's voice. */
    private suspend fun speak(line: Line) {
        live = Live.SPEAKING
        val f = flow
        val text = line.text
        if (f != null && f.usingModel) {
            val player = StreamPlayer()
            val barge = listenOver(player)
            follow(line, { player.estimatedChars(text.length) }, { player.released })
            val r = f.say(text, object : TurnListener { override fun onAudio(pcm: ShortArray) = player.push(pcm) })
            player.markDone()
            if (r.streamed) player.finish() else { player.cancel(); voice?.speak(text) { line.spoken = it }; line.spoken = -1 }
            afterSpeech(barge)
        } else { voice?.speak(text) { line.spoken = it }; line.spoken = -1 }
        live = Live.IDLE
    }

    /** Starts listening for the user talking over Cue while [player] speaks; talking over it stops the answer. */
    private fun listenOver(player: StreamPlayer): BargeIn? {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        return BargeIn(player).also { b ->
            b.onTrigger = {
                flow?.interrupt()
                player.cancel()
                runOnUiThread { live = Live.LISTENING; partial = "…" }
            }
        }
    }

    /** After Cue's speech: true if the user talked over it (their words are then the next turn). */
    private suspend fun afterSpeech(barge: BargeIn?): Boolean {
        if (barge == null) return false
        if (!barge.triggered) { barge.close(); return false }
        live = Live.LISTENING
        bargedAudio = barge.utterance()
        partial = ""
        return bargedAudio != null
    }

    private fun endCall() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        voice?.cancelListening()
        voice?.stopSpeaking()
        mic?.stop(); mic = null
        callAudio?.release(); callAudio = null
        // Filing runs on the app's scope: leaving the summary screen early must not cancel it.
        val f = flow
        val app = applicationContext
        CueRuntime.scope.launch {
            val c = f?.end().orEmpty()
            val got = f?.learned.orEmpty()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { changes.clear(); changes += c.filterNot { it.startsWith("learned:") }; learned.clear(); learned += got }
            CallService.stop(app)
        }
    }

    private fun sendTyped(text: String) {
        if (text.isBlank()) return
        typedQueue = text.trim()
        voice?.cancelListening()
    }

    override fun onDestroy() {
        if (phase == Phase.IN_CALL) { phase = Phase.ENDED; CueRuntime.scope.launch { flow?.end() } }
        mic?.stop(); callAudio?.release()
        voice?.release()
        CallService.stop(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun Screen() {
        when (phase) {
            Phase.RINGING -> Ringing()
            Phase.CONNECTING, Phase.IN_CALL -> InCall()
            Phase.ENDED -> Ended()
        }
    }

    @Composable
    private fun Ringing() {
        LaunchedEffect(Unit) { delay(32_000); if (phase == Phase.RINGING) finish() }
        val pulse = rememberInfiniteTransition(label = "ring").animateFloat(1f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
        val bg = if (night) Brush.verticalGradient(listOf(Color(0xFF2E2652), Cue.night)) else Cue.sunrise
        val fg = if (night) Cue.nightText else Color(0xFF3B1630)
        Column(Modifier.fillMaxSize().background(bg)) {
            Column(Modifier.weight(1f).fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Pill(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, h:mm a")), Color.White.copy(alpha = if (night) 0.12f else 0.65f),
                    if (night) Cue.nightText else Cue.coralText, dot = Cue.coralDeep)
                Spacer(Modifier.height(28.dp))
                Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(220.dp).scale(pulse.value).border(1.5.dp, Cue.coralDeep.copy(alpha = 0.25f), CircleShape))
                    Box(Modifier.size(200.dp).drawBehind { drawCircle(Brush.radialGradient(listOf((if (night) Cue.nightGlow else Cue.coral).copy(alpha = 0.4f), Color.Transparent))) })
                    Pixel(if (night) PixelMood.RingingNight else PixelMood.Ringing, 220.dp)
                }
                Spacer(Modifier.height(20.dp))
                Txt("Cue is calling", Type.bodyStrong, if (night) Cue.apricot else Cue.coralText)
                Txt(title, Type.display.copy(fontSize = androidx.compose.ui.unit.TextUnit(32f, androidx.compose.ui.unit.TextUnitType.Sp)), fg)
                Txt("About a minute", Type.body, if (night) Cue.nightMuted else Color(0xFF6E3A5C))
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.take(2).forEach {
                        Box(Modifier.shadow(6.dp, CircleShape, spotColor = Cue.coralDeep.copy(alpha = 0.3f)).clip(CircleShape).background(Color.White.copy(alpha = if (night) 0.12f else 0.78f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Txt(it, Type.smallStrong, if (night) Cue.nightText else Cue.ink, maxLines = 1)
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().shadow(30.dp, RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp), spotColor = Cue.coralDeep.copy(alpha = 0.4f))
                    .clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp)).background(Color(0xFFFDFBFF)).navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(68.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Cue.coralTint, Color(0xFFFFD3CE)))).clickable(role = Role.Button, onClickLabel = "Decline") { decline() },
                        contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Decline", tint = Cue.coralText, modifier = Modifier.size(28.dp)) }
                    PrimaryButton("Pick up", Modifier.weight(1f), icon = Icons.Rounded.Call, height = 68.dp) { accept() }
                }
                SoftButton("Ring me in 10 minutes", Modifier.fillMaxWidth(), color = Color(0xFFF1EDFA)) { snooze() }
            }
        }
    }

    @Composable
    private fun InCall() {
        val bgColor = if (night) Cue.night else Cue.base
        val fg = if (night) Cue.nightText else Cue.ink
        val listState = rememberLazyListState()
        val tail = lines.lastOrNull()?.text?.length ?: 0
        LaunchedEffect(lines.size, partial, tail / 40) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size) }
        Column(
            Modifier.fillMaxSize().background(bgColor).drawBehind {
                drawRect(Brush.radialGradient(listOf(if (night) Cue.nightGlow.copy(alpha = 0.35f) else Cue.glowLavender, Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width * 1.1f))
            }.statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().shadow(12.dp, CircleShape, spotColor = Cue.primaryDeep.copy(alpha = 0.3f)).clip(CircleShape)
                    .background(if (night) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.85f)).padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(title, Type.bodyStrong, fg, maxLines = 1)
                    val state = when (phase) { Phase.CONNECTING -> "Connecting"; else -> when (live) { Live.SPEAKING -> "Speaking"; Live.LISTENING -> "Listening"; Live.THINKING -> "Thinking"; Live.IDLE -> "On call" } }
                    Txt("$state · ${seconds / 60}:${"%02d".format(seconds % 60)}" + if (brainLabel.isNotEmpty()) " · $brainLabel" else "", Type.smallStrong, if (night) Cue.apricot else Cue.primaryDeep, maxLines = 1)
                }
                Wave(live == Live.LISTENING, level)
            }
            LaunchedEffect(actionStamp) { if (action != null) { delay(2200); action = null } }
            val mood = action ?: when (phase) {
                Phase.CONNECTING -> PixelMood.Connecting
                else -> when (live) { Live.SPEAKING -> PixelMood.Speaking; Live.LISTENING -> PixelMood.Listening; Live.THINKING -> PixelMood.Thinking; Live.IDLE -> PixelMood.Idle }
            }
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) { Pixel(mood, 150.dp, level = level) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)) {
                items(lines) { l ->
                    if (l.card != null) CallCardView(l.card, cardVersion, night)
                    else if (l.text.isNotBlank()) Bubble(l.who == "user", l.text, spoken = l.spoken)
                }
                if (partial.isNotBlank()) item { Bubble(true, partial, faded = true) }
            }
            if (typing) TypeBar()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundControl(Icons.AutoMirrored.Rounded.VolumeUp, if (voice?.speakerOn != false) "Speaker on" else "Speaker off", active = voice?.speakerOn != false) { voice?.let { it.speakerOn = !it.speakerOn } }
                RoundControl(if (typing) Icons.Rounded.Mic else Icons.Rounded.Keyboard, if (typing) "Talk instead" else "Type instead", active = typing) {
                    typing = !typing; if (typing) voice?.cancelListening()
                }
                PrimaryButton("End call", Modifier.weight(1f), brush = Cue.coralButton, shadow = Cue.coralDeep, icon = Icons.Rounded.CallEnd, height = 62.dp) { endCall() }
            }
        }
    }

    @Composable
    private fun TypeBar() {
        var text by remember { mutableStateOf("") }
        Row(
            Modifier.fillMaxWidth().shadow(10.dp, CircleShape, spotColor = Cue.primaryDeep.copy(alpha = 0.3f)).clip(CircleShape).background(Color.White).padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(text, { text = it }, Modifier.weight(1f).padding(vertical = 16.dp), textStyle = Type.body.copy(color = Cue.ink), singleLine = true,
                cursorBrush = SolidColor(Cue.primaryDeep), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendTyped(text); text = "" }),
                decorationBox = { inner -> if (text.isEmpty()) Txt("Type to Cue…", Type.body, Cue.muted); inner() })
            Box(Modifier.size(44.dp).clip(CircleShape).background(Cue.primaryButton).clickable(role = Role.Button, onClickLabel = "Send") { sendTyped(text); text = "" }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }

    /**
     * A line of the conversation. While Cue speaks it, the words already heard are solid, the word being said now is
     * marked, and the rest is faded, so the text follows the voice.
     */
    @Composable
    private fun Bubble(user: Boolean, text: String, faded: Boolean = false, spoken: Int = -1) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            val shape = if (user) RoundedCornerShape(24.dp, 24.dp, 8.dp, 24.dp) else RoundedCornerShape(24.dp, 24.dp, 24.dp, 8.dp)
            val ink = if (user) Color.White.copy(alpha = if (faded) 0.7f else 1f) else if (night) Cue.nightText else Cue.ink
            Box(
                Modifier.widthIn(max = 290.dp)
                    .shadow(if (faded) 0.dp else 10.dp, shape, spotColor = if (user) Cue.primaryDeep.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.18f))
                    .clip(shape)
                    .background(if (user) Cue.primaryBrush else Brush.linearGradient(listOf(if (night) Cue.nightRaised else Color.White, if (night) Cue.nightRaised else Color.White)))
                    .padding(horizontal = 18.dp, vertical = 13.dp),
            ) {
                if (user || spoken < 0) Txt(text, Type.body, ink)
                else Text(highlighted(text, spoken, ink), style = Type.body)
            }
        }
    }

    private fun highlighted(text: String, spoken: Int, ink: Color): androidx.compose.ui.text.AnnotatedString {
        val at = spoken.coerceIn(0, text.length)
        val wordStart = text.lastIndexOf(' ', (at - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }.coerceAtMost(at)
        val wordEnd = text.indexOf(' ', at).let { if (it < 0) text.length else it }
        val mark = if (night) Cue.apricot else Cue.coralText
        return androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = ink)) { append(text.substring(0, wordStart)) }
            withStyle(androidx.compose.ui.text.SpanStyle(color = mark, fontWeight = androidx.compose.ui.text.font.FontWeight(800),
                background = mark.copy(alpha = 0.14f))) { append(text.substring(wordStart, wordEnd)) }
            withStyle(androidx.compose.ui.text.SpanStyle(color = ink.copy(alpha = 0.38f))) { append(text.substring(wordEnd)) }
        }
    }

    @Composable
    private fun RoundControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.size(62.dp).shadow(10.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.25f)).clip(CircleShape)
                .background(Brush.verticalGradient(if (active) listOf(Color.White, Cue.primaryTint) else listOf(Color.White, Color(0xFFF1EDFA))))
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (active) Cue.primaryDeep else Cue.ink, modifier = Modifier.size(24.dp)) }
    }

    @Composable
    private fun Wave(active: Boolean, level: Float) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp)) {
            listOf(0.4f, 0.8f, 1f, 0.6f, 0.3f).forEachIndexed { i, base ->
                val h = if (active) (8 + 20 * (base * (0.4f + level))).coerceAtMost(28f) else 6f + i
                Box(Modifier.width(4.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(if (i == 2) Cue.coral else Cue.primary))
            }
        }
    }

    /** "What Cue learned": facts picked up on the call. Nothing is kept until the user says so. */
    @Composable
    private fun LearnedCard(facts: List<com.cue.domain.ProfileFact>, dark: Boolean, fg: Color, keep: (com.cue.domain.ProfileFact) -> Unit, remove: (com.cue.domain.ProfileFact) -> Unit) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (dark) Color.White.copy(alpha = 0.10f) else Cue.primaryTint).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Txt("What Cue learned", Type.bodyStrong, fg)
            Txt("Keep these so I know them on every call?", Type.small, if (dark) Cue.nightMuted else Cue.muted)
            facts.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(f.text, Type.body, fg, modifier = Modifier.weight(1f), maxLines = 2)
                    Spacer(Modifier.width(8.dp))
                    Txt("Remove", Type.small, if (dark) Cue.nightMuted else Cue.muted, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { remove(f) }.padding(8.dp))
                    Txt("Keep", Type.bodyStrong, if (dark) Color.White else Cue.primaryDeep, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { keep(f) }.padding(8.dp))
                }
            }
        }
    }

    @Composable
    private fun Ended() {
        val dark = night || kind == ReminderKind.CHECKIN_NIGHT
        val fg = if (dark) Cue.nightText else Cue.ink
        Column(
            Modifier.fillMaxSize().background(if (dark) Cue.night else Cue.base).drawBehind {
                drawRect(Brush.radialGradient(listOf((if (dark) Cue.nightGlow else Cue.primaryLight).copy(alpha = if (dark) 0.45f else 0.18f), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width * 1.1f))
            }.statusBarsPadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Pixel(if (dark) PixelMood.Sleeping else PixelMood.Celebrate, 150.dp) }
            Txt(if (dark) "All tucked away. Sleep well." else "Done. Here's what I saved.", Type.display, fg)
            Txt("From this ${seconds / 60}:${"%02d".format(seconds % 60)} call", Type.body, if (dark) Cue.nightMuted else Cue.muted)
            val shown = changes.ifEmpty { listOf("none: Nothing new this time") }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (learned.isNotEmpty()) item {
                    LearnedCard(learned.toList(), dark, fg,
                        keep = { fact -> CueRuntime.scope.launch { CueRuntime.engine.dao.updateFact(fact.copy(confirmed = true)) }; learned.remove(fact) },
                        remove = { fact -> CueRuntime.scope.launch { CueRuntime.engine.dao.deleteFact(fact.id) }; learned.remove(fact) })
                }
                items(shown) { c ->
                    val (label, tint) = summaryLabel(c.substringBefore(":"))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (dark) Color.White.copy(alpha = 0.06f) else Color.White).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(40.dp).shadow(6.dp, RoundedCornerShape(14.dp), spotColor = tint.second).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(tint.first, tint.second))), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Txt(label, Type.bodyStrong, fg)
                            Txt(c.substringAfter(": ", "").replaceFirstChar { it.uppercase() }, Type.small, if (dark) Cue.nightMuted else Cue.muted, maxLines = 2)
                        }
                    }
                }
            }
            PrimaryButton(if (dark) "Good night" else "Done", Modifier.fillMaxWidth(), brush = if (dark) Brush.verticalGradient(listOf(Color.White, Color(0xFFF1EDFA))) else Cue.primaryButton,
                textColor = if (dark) Cue.primaryDeep else Color.White) { finish() }
        }
    }
}
