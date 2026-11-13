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
import com.cue.agent.CallFlow
import com.cue.agent.CueRuntime
import com.cue.agent.TextKit
import com.cue.domain.ChecklistEntry
import com.cue.domain.ItemType
import com.cue.domain.ReminderKind
import com.cue.ui.CheckRow
import com.cue.ui.Cue
import com.cue.ui.CueOrb
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

/** Plain-words label and colour for each thing a call saved. */
fun summaryLabel(tool: String): Pair<String, Pair<Color, Color>> = when (tool) {
    "checklist_add" -> "Added to your list" to (Color(0xFF2A9A90) to Color(0xFF0D4B48))
    "checklist_tick" -> "Ticked off" to (Color(0xFF2A9A90) to Color(0xFF0D4B48))
    "note_save" -> "Saved a note" to (Color(0xFFFFC861) to Color(0xFFF08A00))
    "checkin_time" -> "Daily call moved" to (Color(0xFFFFB3CB) to Color(0xFFE2547F))
    "reminder_set" -> "Reminder set" to (Color(0xFFFFB3CB) to Color(0xFFE2547F))
    "event_move" -> "Flight moved" to (Color(0xFFB9C6FF) to Color(0xFF6C7FE8))
    "habit_checkin" -> "Habit logged" to (Color(0xFFFFC861) to Color(0xFFF08A00))
    "dose_schedule" -> "Medicine reminders set" to (Color(0xFFFFB3CB) to Color(0xFFE2547F))
    "dose_taken" -> "Dose recorded" to (Color(0xFFFFB3CB) to Color(0xFFE2547F))
    "pending_add" -> "Remembered for later" to (Color(0xFFC6E6F5) to Color(0xFF3D8DB8))
    "pending_resolve" -> "Marked done" to (Color(0xFFC6E6F5) to Color(0xFF3D8DB8))
    "album_search" -> "Found it" to (Color(0xFFB9C6FF) to Color(0xFF6C7FE8))
    "journal_lookup" -> "Read your journal" to (Color(0xFFFFB49C) to Color(0xFFF0663F))
    "journal_write" -> "Today's journal written" to (Color(0xFFFFB49C) to Color(0xFFF0663F))
    "renewal_remind" -> "Renewal reminder set" to (Color(0xFFA8E6CC) to Color(0xFF3E9E7A))
    "practice_note" -> "Practice notes" to (Color(0xFFFFD3B5) to Color(0xFFFF8E6B))
    else -> "Nothing new to file" to (Color(0xFFA7B3B1) to Color(0xFF5D6A68))
}
enum class Live { SPEAKING, LISTENING, THINKING, IDLE }

/** The call screen: shown over the lock screen for a ring, and for Talk-to-Cue from the app. */
class CallActivity : ComponentActivity() {
    private var phase by mutableStateOf(Phase.RINGING)
    private var live by mutableStateOf(Live.IDLE)
    private var partial by mutableStateOf("")
    private var level by mutableStateOf(0f)
    private var seconds by mutableStateOf(0)
    private var typing by mutableStateOf(false)
    private var brainLabel by mutableStateOf("")
    private val lines = mutableStateListOf<Pair<String, String>>()
    private val entries = mutableStateListOf<ChecklistEntry>()
    private var listTitle by mutableStateOf("")
    private var justTicked by mutableStateOf<Long?>(null)
    private val changes = mutableStateListOf<String>()

    private var kind = "TALK"
    private var reminderId: Long? = null
    private var title = "Talk to Cue"
    private var chips: List<String> = emptyList()
    private var flow: CallFlow? = null
    private var voice: VoiceIO? = null
    private val pcm = PcmIO()
    private var loopJob: Job? = null
    private var typedQueue: String? = null

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
            lines.clear(); entries.clear(); changes.clear(); seconds = 0; partial = ""; typing = false; justTicked = null; brainLabel = ""
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
            brainLabel = if (f.usingModel) "LFM3 on device" else if (v.onDevice) "on-device voice" else "phone voice"
            phase = Phase.IN_CALL
            refreshList()
            lines += "cue" to opening
            speak(opening)
            var silentSince = System.currentTimeMillis()
            var nudged = false
            while (phase == Phase.IN_CALL) {
                live = Live.LISTENING
                partial = ""
                val queued = typedQueue.also { typedQueue = null }
                val listenStart = System.currentTimeMillis()
                var heardText: String? = null
                var audio: ShortArray? = null
                if (queued != null) heardText = queued
                else if (typing) { delay(250); silentSince = System.currentTimeMillis(); continue }
                else if (f.usingModel && f.hearsAudio) {
                    // The model hears the user's own voice (the model card's spoken-question form).
                    audio = pcm.record(stop = { typing || typedQueue != null || phase != Phase.IN_CALL }, onLevel = { level = it })
                } else heardText = v.listen()
                if (phase != Phase.IN_CALL) break
                if (heardText == null && audio == null) {
                    if (typedQueue != null || typing) continue
                    // A recognizer error that comes back instantly is not silence; wait a moment and listen again.
                    if (System.currentTimeMillis() - listenStart < 1500) { delay(600); continue }
                    val quiet = System.currentTimeMillis() - silentSince
                    if (quiet > 22_000 && nudged) { speak("I'll let you go. Talk soon."); endCall(); break }
                    if (quiet > 7_000 && !nudged) { nudged = true; speak("Are you there?") }
                    continue
                }
                live = Live.THINKING
                v.cue()
                if (heardText != null) { lines += "user" to heardText; partial = "" } else partial = "…"
                val player = if (f.usingModel) StreamPlayer() else null
                val onAudio = player?.let { p -> { chunk: ShortArray -> live = Live.SPEAKING; p.push(chunk) } }
                val reply = if (audio != null) f.userAudio(audio, onAudio) else f.userSaid(heardText!!, onAudio)
                partial = ""
                if (audio != null) {
                    val heard = reply.heard
                    if (heard == null) { player?.cancel(); continue } // a sound with no words in it: keep listening
                    lines += "user" to heard
                }
                silentSince = System.currentTimeMillis()
                nudged = false
                lines += "cue" to reply.spoken
                refreshList()
                live = Live.SPEAKING
                if (player != null && reply.streamed) player.finish()
                else { player?.cancel(); voice?.speak(reply.spoken) }
                live = Live.IDLE
                if (reply.endCall) { endCall(); break }
            }
        }
    }

    /** Says a fixed line: in the model's own voice when it is on the call, else with the phone's voice. */
    private suspend fun speak(text: String) {
        live = Live.SPEAKING
        val f = flow
        if (f != null && f.usingModel) {
            val player = StreamPlayer()
            val r = f.say(text) { player.push(it) }
            if (r.streamed) player.finish() else { player.cancel(); voice?.speak(text) }
        } else voice?.speak(text)
        live = Live.IDLE
    }

    private suspend fun refreshList() {
        val engine = CueRuntime.engine
        val albumId = flow?.albumId ?: engine.activeAlbum()?.id
        val lists = engine.dao.itemsOfType(ItemType.CHECKLIST)
        val item = lists.firstOrNull { it.albumId == albumId && albumId != null } ?: lists.firstOrNull { TextKit.dayKey(it.createdAt, engine.clock.zone) == TextKit.dayKey(engine.clock.now(), engine.clock.zone) }
        val before = entries.associate { it.id to it.checked }
        val now = item?.let { engine.dao.entries(it.id) }.orEmpty().sortedBy { it.position }
        justTicked = now.firstOrNull { it.checked && before[it.id] == false }?.id ?: now.firstOrNull { it.id !in before.keys && before.isNotEmpty() }?.id
        entries.clear(); entries += now
        listTitle = item?.title ?: ""
    }

    private fun endCall() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        voice?.cancelListening()
        voice?.stopSpeaking()
        // Filing runs on the app's scope: leaving the summary screen early must not cancel it.
        val f = flow
        val app = applicationContext
        CueRuntime.scope.launch {
            val c = f?.end().orEmpty()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { changes.clear(); changes += c }
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
        val bg = if (night) Brush.verticalGradient(listOf(Color(0xFF123B3A), Color(0xFF0B1B1C))) else Cue.sunrise
        val fg = if (night) Cue.nightText else Color(0xFF3A1A14)
        Column(Modifier.fillMaxSize().background(bg)) {
            Column(Modifier.weight(1f).fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Pill(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, h:mm a")), Color.White.copy(alpha = if (night) 0.12f else 0.65f),
                    if (night) Cue.nightText else Cue.coralText, dot = Cue.coralDeep)
                Spacer(Modifier.height(28.dp))
                Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(220.dp).scale(pulse.value).border(1.5.dp, Cue.coralDeep.copy(alpha = 0.25f), CircleShape))
                    Box(Modifier.size(176.dp).border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape))
                    Box(Modifier.size(170.dp).drawBehind { drawCircle(Brush.radialGradient(listOf(Cue.coral.copy(alpha = 0.45f), Color.Transparent))) })
                    CueOrb(116.dp, "Cue", ring = true)
                }
                Spacer(Modifier.height(20.dp))
                Txt("Cue is calling", Type.bodyStrong, if (night) Cue.apricot else Cue.coralText)
                Txt(title, Type.display.copy(fontSize = androidx.compose.ui.unit.TextUnit(32f, androidx.compose.ui.unit.TextUnitType.Sp)), fg)
                Txt("About a minute", Type.body, if (night) Cue.nightMuted else Color(0xFF6B3A2E))
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
                    .clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp)).background(Color(0xFFFFFBF7)).navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(68.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFFFE6DE), Color(0xFFFFD2C4)))).clickable(role = Role.Button, onClickLabel = "Decline") { decline() },
                        contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Decline", tint = Cue.coralText, modifier = Modifier.size(28.dp)) }
                    PrimaryButton("Pick up", Modifier.weight(1f), icon = Icons.Rounded.Call, height = 68.dp) { accept() }
                }
                SoftButton("Ring me in 10 minutes", Modifier.fillMaxWidth(), color = Color(0xFFF3EEE7)) { snooze() }
            }
        }
    }

    @Composable
    private fun InCall() {
        val bgColor = if (night) Cue.night else Cue.cream
        val fg = if (night) Cue.nightText else Cue.ink
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size, partial) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size) }
        Column(
            Modifier.fillMaxSize().background(bgColor).drawBehind {
                drawRect(Brush.radialGradient(listOf(if (night) Cue.tealLight.copy(alpha = 0.35f) else Color(0xFFFFDCC8), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width * 1.1f))
            }.statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().shadow(12.dp, CircleShape, spotColor = Cue.tealDeep.copy(alpha = 0.3f)).clip(CircleShape)
                    .background(if (night) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.85f)).padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CueOrb(44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(title, Type.bodyStrong, fg, maxLines = 1)
                    val state = when (phase) { Phase.CONNECTING -> "Connecting"; else -> when (live) { Live.SPEAKING -> "Speaking"; Live.LISTENING -> "Listening"; Live.THINKING -> "Thinking"; Live.IDLE -> "On call" } }
                    Txt("$state · ${seconds / 60}:${"%02d".format(seconds % 60)}" + if (brainLabel.isNotEmpty()) " · $brainLabel" else "", Type.smallStrong, if (night) Cue.apricot else Cue.tealDeep, maxLines = 1)
                }
                Wave(live == Live.LISTENING, level)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)) {
                items(lines) { (who, t) -> Bubble(who == "user", t) }
                if (partial.isNotBlank()) item { Bubble(true, partial, faded = true) }
                if (entries.isNotEmpty() && kind != ReminderKind.PRACTICE) item { ListCard() }
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
            Modifier.fillMaxWidth().shadow(10.dp, CircleShape, spotColor = Cue.tealDeep.copy(alpha = 0.3f)).clip(CircleShape).background(Color.White).padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(text, { text = it }, Modifier.weight(1f).padding(vertical = 16.dp), textStyle = Type.body.copy(color = Cue.ink), singleLine = true,
                cursorBrush = SolidColor(Cue.tealDeep), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendTyped(text); text = "" }),
                decorationBox = { inner -> if (text.isEmpty()) Txt("Type to Cue…", Type.body, Cue.muted); inner() })
            Box(Modifier.size(44.dp).clip(CircleShape).background(Cue.tealButton).clickable(role = Role.Button, onClickLabel = "Send") { sendTyped(text); text = "" }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }

    @Composable
    private fun Bubble(user: Boolean, text: String, faded: Boolean = false) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            val shape = if (user) RoundedCornerShape(24.dp, 24.dp, 8.dp, 24.dp) else RoundedCornerShape(24.dp, 24.dp, 24.dp, 8.dp)
            Box(
                Modifier.widthIn(max = 290.dp)
                    .shadow(if (faded) 0.dp else 10.dp, shape, spotColor = if (user) Cue.tealDeep.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.18f))
                    .clip(shape)
                    .background(if (user) Cue.tealBrush else Brush.linearGradient(listOf(if (night) Color(0xFF1A2D2D) else Color.White, if (night) Color(0xFF1A2D2D) else Color.White)))
                    .padding(horizontal = 18.dp, vertical = 13.dp),
            ) { Txt(text, Type.body, if (user) Color.White.copy(alpha = if (faded) 0.7f else 1f) else if (night) Cue.nightText else Cue.ink) }
        }
    }

    @Composable
    private fun ListCard() {
        Column(
            Modifier.fillMaxWidth().padding(top = 6.dp).shadow(14.dp, RoundedCornerShape(28.dp), spotColor = Color(0xFF33408C).copy(alpha = 0.35f)).clip(RoundedCornerShape(28.dp))
                .background(if (night) Color(0xFF142626) else Color.White).padding(6.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(Cue.tealBrush), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Txt(listTitle.ifBlank { "List" }, Type.bodyStrong, if (night) Cue.nightText else Cue.ink, Modifier.weight(1f))
                if (justTicked != null) Pill("Just updated", Cue.coralTint, Cue.coralText)
            }
            entries.forEach { e ->
                CheckRow(e.text, e.checked, onToggle = {
                    lifecycleScope.launch { CueRuntime.engine.dao.updateEntry(e.copy(checked = !e.checked)); refreshList() }
                }, dark = night, highlight = e.id == justTicked)
            }
        }
    }

    @Composable
    private fun RoundControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.size(62.dp).shadow(10.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.25f)).clip(CircleShape)
                .background(Brush.verticalGradient(if (active) listOf(Color.White, Color(0xFFE6F5F1)) else listOf(Color.White, Color(0xFFF3EEE7))))
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (active) Cue.tealDeep else Cue.ink, modifier = Modifier.size(24.dp)) }
    }

    @Composable
    private fun Wave(active: Boolean, level: Float) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp)) {
            listOf(0.4f, 0.8f, 1f, 0.6f, 0.3f).forEachIndexed { i, base ->
                val h = if (active) (8 + 20 * (base * (0.4f + level))).coerceAtMost(28f) else 6f + i
                Box(Modifier.width(4.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(if (i == 2) Cue.coral else Cue.teal))
            }
        }
    }

    @Composable
    private fun Ended() {
        val dark = night || kind == ReminderKind.CHECKIN_NIGHT
        val fg = if (dark) Cue.nightText else Cue.ink
        Column(
            Modifier.fillMaxSize().background(if (dark) Cue.night else Cue.cream).drawBehind {
                drawRect(Brush.radialGradient(listOf(Cue.tealLight.copy(alpha = if (dark) 0.45f else 0.18f), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width * 1.1f))
            }.statusBarsPadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Box(Modifier.size(64.dp).shadow(16.dp, CircleShape, spotColor = Cue.tealLight).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF5FD1C4), Cue.teal, Cue.tealDeep))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Txt(if (dark) "All tucked away. Sleep well." else "Done. Here's what I saved.", Type.display, fg)
            Txt("From this ${seconds / 60}:${"%02d".format(seconds % 60)} call", Type.body, if (dark) Cue.nightMuted else Cue.muted)
            val shown = changes.ifEmpty { listOf("none: Nothing new this time") }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            PrimaryButton(if (dark) "Good night" else "Done", Modifier.fillMaxWidth(), brush = if (dark) Brush.verticalGradient(listOf(Color.White, Color(0xFFF1ECE4))) else Cue.tealButton,
                textColor = if (dark) Cue.tealDeep else Color.White) { finish() }
        }
    }
}
