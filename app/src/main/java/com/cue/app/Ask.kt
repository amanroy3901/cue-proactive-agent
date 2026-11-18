package com.cue.app

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cue.agent.CueTools
import com.cue.agent.Inbox
import com.cue.agent.PlanQuestion
import com.cue.agent.PlanTalk
import com.cue.agent.SpaceSpec
import com.cue.agent.Spaces
import com.cue.ingest.Ingest
import com.cue.agent.HabitDays
import com.cue.agent.TextKit
import com.cue.domain.*
import com.cue.genui.CueCatalog
import com.cue.genui.CueGenEnv
import com.cue.genui.CueGenUi
import com.cue.genui.GenAction
import com.cue.genui.GenSurface
import com.cue.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Ask Cue: type a question, get an answer drawn as a screen. The model answers in A2UI (Google's generated-UI
 * protocol); Cue's catalog decides what it may draw, and Cue's own components fill in the user's real data on the
 * phone. Buttons on a generated screen run Cue's tools, the same ones a call uses. Typed only: no microphone, no voice.
 */

private val zone: ZoneId get() = ZoneId.systemDefault()

/** A file waiting in the composer: copied into Cue's storage, not yet sent. */
data class Attachment(val path: String, val name: String, val photo: Boolean)

/**
 * One line of the conversation. A Cue turn can carry a generated screen ([surfaceId]), what it did with the user's
 * files ([filed]), a planning question with choices ([question]), or a space it is about to make ([preview]).
 */
data class AskTurn(
    val fromUser: Boolean, val text: String, val surfaceId: String? = null, val note: Boolean = false,
    val files: List<String> = emptyList(),
    val filed: List<Inbox.Filed> = emptyList(), val undone: Set<Int> = emptySet(),
    val question: PlanQuestion? = null, val answered: Boolean = false,
    val preview: SpaceSpec? = null, val openAlbum: Long? = null,
)

class AskSession(private val a: MainActivity) {
    val turns = mutableStateListOf<AskTurn>()
    var thinking by mutableStateOf(false)
    /** Files picked but not sent yet. */
    val pending = mutableStateListOf<Attachment>()
    /** The space this conversation is about: files and questions go there unless the user says otherwise. */
    var contextAlbum: Long? = null
    private var count = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repairs = mutableSetOf<String>()
    private val prefs = a.getSharedPreferences("ask", android.content.Context.MODE_PRIVATE)

    /** A plan being talked through: the goal, the questions, the answers so far. */
    private class PlanRun(val goal: String, val questions: List<PlanQuestion>, val answers: MutableList<Pair<PlanQuestion, String>> = mutableListOf(), var index: Int = 0)
    private var plan: PlanRun? = null

    init { restore() }

    val genUi = CueGenUi(
        onAction = { action -> scope.launch { onAction(action) } },
        onError = { surface, error -> scope.launch { repair(surface, error) } },
    )

    val env = object : CueGenEnv {
        override val dao get() = a.cue.engine.dao
        override fun open(route: String) { a.openRoute(route) }
    }

    fun ask(text: String) = send(text)

    /** The user sent [text], files, or both. Files are filed first; then planning or a normal answer. */
    fun send(text: String, files: List<Attachment> = emptyList()) {
        val t = text.trim()
        if ((t.isBlank() && files.isEmpty()) || thinking) return
        turns += AskTurn(true, t, files = files.map { it.path })
        save()
        scope.launch {
            if (files.isNotEmpty()) {
                fileAll(t, files)
                if (Inbox.isOnlyFilingHint(t)) return@launch
            }
            val p = plan
            when {
                p != null && p.index < p.questions.size -> answerPlan(t)
                PlanTalk.isPlanning(t) -> startPlan(t)
                else -> answer()
            }
        }
    }

    // ------------------------------------------------------------------ files

    private suspend fun fileAll(hint: String, files: List<Attachment>) {
        thinking = true
        val results = files.map { f -> withContext(Dispatchers.IO) { Ingest.fileNow(a, f.path, f.name, "chat", hint, contextAlbum, f.photo) } }
        results.firstOrNull { it.sure && it.album != null }?.album?.let { contextAlbum = it.id }
        val text = if (results.size == 1) "" else {
            val albums = results.mapNotNull { r -> r.album?.takeIf { r.sure } }.distinctBy { it.id }
            when {
                results.all { it.sure } && albums.size == 1 -> "Saved all ${results.size} to ${albums[0].title}."
                results.any { !it.sure } -> "Saved ${results.size} files. I need you to pick a space for ${results.count { !it.sure }} of them."
                else -> "Saved ${results.size} files."
            }
        }
        turns += AskTurn(false, text, filed = results)
        thinking = false
        save()
    }

    fun undoFiled(turnIndex: Int, fileIndex: Int) {
        val t = turns.getOrNull(turnIndex) ?: return
        val f = t.filed.getOrNull(fileIndex) ?: return
        scope.launch {
            withContext(Dispatchers.IO) { a.cue.engine.inbox.undo(f) }
            turns[turnIndex] = t.copy(undone = t.undone + fileIndex)
        }
    }

    fun moveFiled(turnIndex: Int, fileIndex: Int, albumId: Long) {
        val t = turns.getOrNull(turnIndex) ?: return
        val f = t.filed.getOrNull(fileIndex) ?: return
        scope.launch {
            val m = withContext(Dispatchers.IO) { a.cue.engine.inbox.move(f, albumId) }
            contextAlbum = albumId
            turns[turnIndex] = t.copy(filed = t.filed.toMutableList().also { it[fileIndex] = m })
        }
    }

    // ------------------------------------------------------------------ planning as a conversation

    private suspend fun startPlan(goal: String) {
        thinking = true
        val engine = a.cue.engine
        val base = Spaces(engine).fromTemplate(goal)
        val qs = withContext(Dispatchers.IO) { PlanTalk.questions(engine, goal, base) }
        plan = PlanRun(goal, qs)
        turns += AskTurn(false, "Nice. ${if (qs.size == 1) "One quick question" else "${qs.size} quick questions"} so I set it up right. Tap an answer, type your own, or skip.")
        thinking = false
        askNext()
    }

    private fun askNext() {
        val p = plan ?: return
        val q = p.questions.getOrNull(p.index)
        if (q == null) { scope.launch { finishPlan() }; return }
        turns += AskTurn(false, q.ask, question = q)
        save()
    }

    /** A chip was tapped (or several, for a multi-choice question). */
    fun choose(turnIndex: Int, value: String) {
        val t = turns.getOrNull(turnIndex) ?: return
        if (t.answered || t.question == null) return
        turns[turnIndex] = t.copy(answered = true)
        turns += AskTurn(true, value)
        recordAnswer(value)
    }

    private fun answerPlan(typed: String) {
        val i = turns.indexOfLast { it.question != null && !it.answered }
        if (i >= 0) turns[i] = turns[i].copy(answered = true)
        val q = plan?.questions?.getOrNull(plan!!.index)
        // A typed answer to "what should I keep track of" is read for the choices it names.
        val value = if (q?.multi == true) PlanTalk.NEEDS.keys.filter { k -> typed.contains(k.removeSuffix("s").lowercase(Locale.ROOT).take(5), ignoreCase = true) }.joinToString(", ").ifBlank { typed } else typed
        recordAnswer(value)
    }

    private fun recordAnswer(value: String) {
        val p = plan ?: return
        val q = p.questions.getOrNull(p.index) ?: return
        if (!value.equals("Skip", true)) p.answers += q to value
        p.index++
        askNext()
    }

    fun skipQuestions() {
        val p = plan ?: return
        turns.indices.filter { turns[it].question != null && !turns[it].answered }.forEach { turns[it] = turns[it].copy(answered = true) }
        p.index = p.questions.size
        scope.launch { finishPlan() }
    }

    private suspend fun finishPlan() {
        val p = plan ?: return
        thinking = true
        val engine = a.cue.engine
        val goal = PlanTalk.enrich(p.goal, p.answers)
        val track = p.answers.firstOrNull { it.first.key == "track" }?.second?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val spec = PlanTalk.applyNeeds(withContext(Dispatchers.IO) { Spaces(engine).plan(goal) }, track, PlanTalk.peopleIn(p.answers))
        val dates = spec.start?.let { st -> " for " + st.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)) + (spec.end?.takeIf { it != st }?.let { " – " + it.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)) } ?: "") }.orEmpty()
        turns += AskTurn(false, "Here's ${spec.title}$dates. Tap a part to leave it out, then make it.", preview = spec)
        thinking = false
        save()
    }

    fun dropBlock(turnIndex: Int, blockIndex: Int) {
        val t = turns.getOrNull(turnIndex) ?: return
        val s = t.preview ?: return
        turns[turnIndex] = t.copy(preview = s.copy(blocks = s.blocks.filterIndexed { j, _ -> j != blockIndex }))
    }

    fun makeSpace(turnIndex: Int) {
        val t = turns.getOrNull(turnIndex) ?: return
        val s = t.preview?.takeIf { it.blocks.isNotEmpty() } ?: return
        turns[turnIndex] = t.copy(preview = null, text = "Making ${s.title}…")
        scope.launch {
            val id = withContext(Dispatchers.IO) { Spaces(a.cue.engine).create(s) }
            plan = null
            contextAlbum = id
            turns[turnIndex] = t.copy(preview = null, text = "Made ${s.title}: ${s.describe()}.", openAlbum = id)
            turns += AskTurn(false, "Send me photos, bills or tickets for it here, or tell me what to add.", note = true)
            save()
        }
    }

    fun cancelPlan(turnIndex: Int) {
        val t = turns.getOrNull(turnIndex) ?: return
        plan = null
        turns[turnIndex] = t.copy(preview = null, text = "Okay, I didn't make it.")
        save()
    }

    // ------------------------------------------------------------------ history

    /** The words of the conversation survive closing the app; generated screens and buttons don't. */
    private fun save() {
        val arr = JSONArray()
        turns.takeLast(80).forEach { t ->
            val text = t.text.ifBlank { t.filed.joinToString(" ") { it.message() } }.ifBlank { t.question?.ask.orEmpty() }
            if (text.isBlank() && t.files.isEmpty()) return@forEach
            arr.put(JSONObject().put("u", t.fromUser).put("t", text).put("n", t.note).put("f", JSONArray(t.files)).apply { t.openAlbum?.let { put("o", it) } })
        }
        prefs.edit().putString("turns", arr.toString()).apply { contextAlbum?.let { putLong("context", it) } ?: remove("context") }.apply()
    }

    private fun restore() {
        val arr = runCatching { JSONArray(prefs.getString("turns", "[]")) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val files = o.optJSONArray("f")?.let { f -> (0 until f.length()).map { f.optString(it) } }.orEmpty().filter { java.io.File(it).exists() }
            turns += AskTurn(o.optBoolean("u"), o.optString("t"), note = o.optBoolean("n"), files = files, openAlbum = o.optLong("o", 0).takeIf { it > 0 })
        }
        contextAlbum = prefs.getLong("context", 0).takeIf { it > 0 }
    }

    fun clear() {
        turns.clear(); plan = null; contextAlbum = null
        prefs.edit().clear().apply()
    }

    private suspend fun answer() {
        thinking = true
        val surfaceId = "s${++count}"
        val reply = runCatching { withContext(Dispatchers.IO) { callModel(surfaceId) } }.onFailure { Log.w("CueAsk", "model", it) }.getOrNull()
        val json = reply?.let { runCatching { JSONObject(it.removePrefix("```json").removeSuffix("```").trim()) }.getOrNull() }
        if (json == null) {
            // Offline, no key, or a reply that isn't JSON: answer from the phone with a fixed screen.
            val local = LocalScreens.forQuestion(turns.last { it.fromUser }.text, a.cue.engine.dao)
            genUi.feedAll(local.second, surfaceId)
            turns += AskTurn(false, local.first, surfaceId)
            thinking = false
            save()
            return
        }
        // Changes the user plainly asked for run now, through the same tools a call uses.
        val ran = mutableListOf<String>()
        json.optJSONArray("tools")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val args = t.optJSONObject("args")?.let { o -> o.keys().asSequence().associateWith { k -> o.opt(k)?.toString().orEmpty() } }.orEmpty()
                val r = a.tool(t.optString("name"), args)
                ran += (if (r.ok) "✓ " else "✗ ") + r.message
            }
        }
        val ui = json.optJSONArray("ui")
        if (ui != null && ui.length() > 0) runCatching { genUi.feedAll(ui, surfaceId) }.onFailure { Log.w("CueAsk", "feed", it) }
        turns += AskTurn(false, json.optString("say").ifBlank { if (ran.isNotEmpty()) "Done." else "Here you go." }, if (ui != null && ui.length() > 0) surfaceId else null)
        ran.forEach { turns += AskTurn(false, it, note = true) }
        thinking = false
        save()
    }

    /** A button on a generated screen. "ask" sends a follow-up; anything else is one of Cue's tools. */
    private suspend fun onAction(action: GenAction) {
        when (action.name) {
            "ask" -> ask(action.args["text"].orEmpty())
            "open" -> a.openRoute(action.args["route"].orEmpty())
            else -> {
                val args = action.args.filterValues { it.isNotBlank() }
                val r = a.tool(action.name, args)
                turns += AskTurn(false, (if (r.ok) "✓ " else "") + r.message.replaceFirstChar { it.uppercase() }, note = true)
            }
        }
    }

    /** The renderer rejected part of a screen: tell the model once and let it redraw. */
    private suspend fun repair(surfaceId: String, error: String) {
        if (!repairs.add(surfaceId) || a.cue.gemini.configured.not()) return
        val reply = withContext(Dispatchers.IO) {
            a.cue.gemini.chatJson(systemPrompt(surfaceId), history() + ("user" to "The screen $surfaceId failed to render: $error. Send the whole screen again as corrected ui messages for surface $surfaceId."))
        } ?: return
        val ui = runCatching { JSONObject(reply).optJSONArray("ui") }.getOrNull() ?: return
        genUi.feed(JSONObject().put("version", "v0.9").put("deleteSurface", JSONObject().put("surfaceId", surfaceId)).toString())
        genUi.feedAll(ui, surfaceId)
    }

    private fun history(): List<Pair<String, String>> = turns.filter { !it.note }.takeLast(12).map { t ->
        (if (t.fromUser) "user" else "model") to t.text.ifBlank { t.filed.joinToString(" ") { it.message() } }.ifBlank { if (t.files.isNotEmpty()) "(sent ${t.files.size} file${if (t.files.size == 1) "" else "s"})" else "…" }
    }

    private suspend fun callModel(surfaceId: String): String? {
        if (!a.cue.gemini.configured) return null
        return a.cue.gemini.chatJson(systemPrompt(surfaceId), history().dropLast(1) + ("user" to (snapshot() + "\n\nQUESTION: " + turns.last { it.fromUser }.text)))
    }

    private fun systemPrompt(surfaceId: String): String = """
        You are Cue, a personal assistant on the user's phone. The user is TYPING to you; answer in text and draw the answer as a small native screen.
        Reply with ONE JSON object, nothing else:
        {"say": "1-2 short sentences", "tools": [ {"name": "...", "args": {...}} ], "ui": [ A2UI messages ]}

        TOOLS: put a tool in "tools" only when the user clearly asked you to change something now (add, tick, set, move, delete). Otherwise leave it empty and offer the change as a button.
        Tool list (args are strings):
        ${toolList()}

        UI: "ui" is a list of A2UI v0.9 messages for surface "$surfaceId":
        1. {"version":"v0.9","createSurface":{"surfaceId":"$surfaceId","catalogId":"${CueCatalog.ID}"}}
        2. {"version":"v0.9","updateComponents":{"surfaceId":"$surfaceId","components":[ ...flat list, one component must have "id":"root"... ]}}
        3. optional {"version":"v0.9","updateDataModel":{"surfaceId":"$surfaceId","path":"/form","value":{...}}} to pre-fill form fields.
        Each component is {"id":"...","component":"<Name>", ...its properties}. Children are referenced by id ("child":"id" or "children":["a","b"]). Keep it small: 3 to 12 components.
        Prefer Cue's own components (CueList, CueReminders, CueHabit, CuePaper, CueNote, CuePerson, CueSpace, CueStat, CueOpen): they show the user's real data live from the phone by its number.
        Never copy private details (PNR, seat, amounts, medicine doses, lab values) into Text; show the paper with CuePaper and it fills them in itself.
        A Button needs a child Text: {"id":"b1","component":"Button","child":"b1t","action":{"event":{"name":"<tool name>","context":{...tool args...}}}}.
        Context values can read form fields: {"path":"/form/text"}. Bind a TextField with "value":{"path":"/form/text"}.
        A button with event name "ask" and context {"text":"..."} asks you that follow-up. Offer 1-3 useful next steps as buttons when it helps.
        For a question with nothing to show, send a single Text root, or leave "ui" empty.

        CATALOG (JSON Schema of every component you may use):
        ${genUi.schema}
    """.trimIndent()

    private fun toolList(): String = CueTools.live.filter { it.name != "end_call" }.joinToString("\n") { t ->
        "- ${t.name}(${t.parameters.joinToString(", ") { p -> p.name + if (p.required) "" else "?" }}): ${t.description}"
    }

    /** What the model may know: names and numbers, not the private fields inside papers. */
    private suspend fun snapshot(): String = withContext(Dispatchers.IO) {
        val dao = a.cue.engine.dao
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("NOW: ").append(java.time.ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("EEEE d MMM yyyy, h:mm a", Locale.ENGLISH))).append('\n')
        sb.append("USER: ").append(a.cue.settings.userName).append('\n')
        contextAlbum?.let { id -> dao.album(id)?.let { sb.append("THIS CONVERSATION IS ABOUT: space #${it.id} ${it.title}\n") } }
        val albums = dao.albums()
        fun space(id: Long?) = id?.let { i -> albums.firstOrNull { it.id == i }?.let { " [space #${it.id}]" } }.orEmpty()
        val entries = dao.openEntries()
        sb.append("LISTS:\n"); dao.itemsOfType(ItemType.CHECKLIST).take(12).forEach { l ->
            val es = dao.entries(l.id); sb.append("  #${l.id} ${l.title}${space(l.albumId)}: ${es.count { !it.checked }} open, ${es.count { it.checked }} done; open: ${es.filter { !it.checked }.take(6).joinToString { it.text }}\n")
        }
        if (entries.isEmpty()) sb.append("  (no open items)\n")
        val today = java.time.LocalDate.now()
        sb.append("HABITS:\n"); dao.habits().forEach { h ->
            val logs = dao.habitLogs(h.id); sb.append("  #${h.id} ${h.name}: ${HabitDays.streak(h, logs, today, zone)}-day streak, done today: ${logs.any { it.day == today.toString() && it.done }}\n")
        }
        sb.append("REMINDERS (next):\n"); dao.scheduledReminders().filter { it.kind !in setOf(ReminderKind.PRELOAD, ReminderKind.AIRPORT_BACKUP) }.take(10).forEach { r ->
            sb.append("  #${r.id} ${TextKit.dayLabel(r.fireAt, now, zone)} ${TextKit.clock(r.fireAt, zone)}: ${r.text}${if (r.surface == Surface.RING) " (rings)" else ""}\n")
        }
        sb.append("PAPERS:\n"); dao.itemsOfType(ItemType.GALLERY).take(15).forEach { d -> sb.append("  #${d.id} ${d.kind}: ${d.title}${space(d.albumId)}${d.expiresAt?.let { ", ends " + TextKit.dateLabel(it, zone) } ?: ""}${d.eventStart?.let { ", on " + TextKit.dateLabel(it, zone) } ?: ""}\n") }
        sb.append("NOTES:\n"); dao.itemsOfType(ItemType.NOTE).filter { it.kind != "practice" }.take(10).forEach { n -> sb.append("  #${n.id} ${n.title.take(50)}${space(n.albumId)}\n") }
        sb.append("JOURNAL:\n"); dao.itemsOfType(ItemType.JOURNAL).take(5).forEach { j -> sb.append("  #${j.id} ${TextKit.dateLabel(j.eventStart ?: j.createdAt, zone)}\n") }
        val pending = dao.allOpenPending()
        sb.append("PEOPLE:\n"); pending.groupBy { it.personId }.forEach { (pid, ps) -> sb.append("  #$pid ${dao.person(pid)?.name}: ${ps.size} pending (${ps.take(3).joinToString { it.text }})\n") }
        sb.append("SPACES:\n"); albums.take(10).forEach { al -> sb.append("  #${al.id} ${al.title} (${al.kind.lowercase()}${al.startAt?.let { ", " + TextKit.dateLabel(it, zone) } ?: ""})\n") }
        sb.toString()
    }
}

/** Fixed A2UI screens built on the phone, for when there's no model: the template half of generated UI. */
object LocalScreens {
    suspend fun forQuestion(q: String, dao: CueDao): Pair<String, JSONArray> {
        val l = q.lowercase(Locale.ROOT)
        val comps = JSONArray()
        fun c(id: String, type: String, vararg p: Pair<String, Any>) = comps.put(JSONObject().put("id", id).put("component", type).apply { p.forEach { (k, v) -> put(k, v) } })
        val say: String
        val kids = mutableListOf<String>()
        when {
            "habit" in l || "streak" in l || "gym" in l -> {
                say = "Your habits."
                dao.habits().take(4).forEachIndexed { i, h -> c("h$i", "CueHabit", "habitId" to h.id); kids += "h$i" }
            }
            "list" in l || "pack" in l -> {
                say = "Your lists."
                dao.itemsOfType(ItemType.CHECKLIST).take(3).forEachIndexed { i, it -> c("l$i", "CueList", "listId" to it.id, "showDone" to false); kids += "l$i" }
            }
            else -> {
                say = "Here's your day."
                c("r", "CueReminders", "limit" to 5); kids += "r"
                dao.itemsOfType(ItemType.CHECKLIST).firstOrNull()?.let { c("l", "CueList", "listId" to it.id, "showDone" to false); kids += "l" }
                dao.habits().firstOrNull()?.let { c("h", "CueHabit", "habitId" to it.id); kids += "h" }
            }
        }
        if (kids.isEmpty()) { c("t", "Text", "text" to "Nothing saved yet."); kids += "t" }
        c("root", "Column", "children" to JSONArray(kids))
        val msgs = JSONArray()
            .put(JSONObject().put("createSurface", JSONObject().put("catalogId", CueCatalog.ID)))
            .put(JSONObject().put("updateComponents", JSONObject().put("components", comps)))
        return "$say (Offline: I can show your data, but I need the internet to answer questions.)" to msgs
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(a: MainActivity, nav: Nav) {
    val session = a.askSession
    val surfaces by session.genUi.surfaces.collectAsState()
    val spaces by a.cue.engine.dao.albumsFlow().collectAsState(emptyList())
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val c = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        copyPicked(c, uris).forEach { (path, name) -> session.pending += Attachment(path, name, photo = true) }
    }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        copyPicked(c, uris).forEach { (path, name) -> session.pending += Attachment(path, name, photo = false) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile; cameraFile = null
        if (ok && f != null && f.length() > 0) session.pending += Attachment(f.absolutePath, f.name, photo = true) else f?.delete()
    }
    val focus = LocalFocusManager.current
    LaunchedEffect(session.turns.size, session.thinking) {
        if (session.turns.isNotEmpty()) list.animateScrollToItem(session.turns.size + 1)
        // Choices to tap: get the keyboard out of their way.
        session.turns.lastOrNull()?.let { t -> if ((t.question != null && !t.answered) || t.preview != null || t.filed.any { !it.sure }) focus.clearFocus() }
    }
    fun send(q: String = text) {
        if (q.isBlank() && session.pending.isEmpty()) return
        session.send(q, session.pending.toList()); session.pending.clear(); text = ""
    }
    GlowBackground(Cue.glowLavender, Cue.glowBlush, Cue.base) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { BackHeader("Ask Cue", "Type, or tap + to add photos and files.", { nav.back() }) }
                        if (session.turns.isNotEmpty()) Txt("Clear", Type.small, Cue.muted, modifier = Modifier.clip(CircleShape).clickable { session.clear() }.padding(10.dp))
                    }
                }
                if (session.turns.isEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pixel(PixelMood.Idle, 72.dp); Spacer(Modifier.width(10.dp))
                            Txt("Ask about your day, or tell me what you're planning. Send a bill, ticket or photo and I'll put it in the right space.", Type.body, Cue.muted)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("I'm planning a trip", "I'm organising a party", "Help me plan a move", "What's my day look like?", "What's left to pack?", "What do I owe people?").forEach { s ->
                                Txt(s, Type.smallStrong, Cue.primaryDeep, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.9f)).clickable { send(s) }.padding(horizontal = 14.dp, vertical = 9.dp))
                            }
                        }
                    }
                }
                itemsIndexed(session.turns) { idx, t ->
                    when {
                        t.fromUser -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (t.files.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { t.files.take(4).forEach { FileThumb(it, 64.dp) }; if (t.files.size > 4) Txt("+${t.files.size - 4}", Type.smallStrong, Cue.muted) }
                            if (t.text.isNotBlank()) Txt(t.text, Type.body, Color.White, modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(Cue.primaryBrush).padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                        t.note -> Txt(t.text, Type.small, Cue.muted, modifier = Modifier.padding(start = 44.dp))
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (t.text.isNotBlank()) Row(verticalAlignment = Alignment.Top) {
                                PixelHead(34.dp); Spacer(Modifier.width(10.dp))
                                Txt(t.text, Type.body, modifier = Modifier.clip(RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp))
                            }
                            if (t.filed.isNotEmpty()) Column(Modifier.padding(start = 44.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.92f)).padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (t.text.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) { PixelHead(28.dp); Spacer(Modifier.width(8.dp)); Txt("Filed", Type.smallStrong, Cue.muted) }
                                t.filed.forEachIndexed { fi, f ->
                                    FiledRow(f, spaces, undone = fi in t.undone, onUndo = { session.undoFiled(idx, fi) }, onMove = { id -> session.moveFiled(idx, fi, id) })
                                }
                            }
                            t.question?.let { q -> if (!t.answered) QuestionChips(q, { v -> session.choose(idx, v) }, { session.skipQuestions() }) }
                            t.preview?.let { spec -> PlanPreview(spec, { b -> session.dropBlock(idx, b) }, { session.makeSpace(idx) }, { session.cancelPlan(idx) }) }
                            t.openAlbum?.let { id -> Box(Modifier.padding(start = 44.dp)) { ChoiceChip("Open ${spaces.firstOrNull { it.id == id }?.title ?: "space"}", selected = true) { nav.go("album/$id") } } }
                            t.surfaceId?.let { id -> GenSurface(session.genUi, surfaces, id, session.env, Modifier.fillMaxWidth()) }
                        }
                    }
                }
                if (session.thinking) item { Row(verticalAlignment = Alignment.CenterVertically) { Pixel(PixelMood.Idle, 40.dp); Spacer(Modifier.width(8.dp)); Txt("Working on it…", Type.small, Cue.muted) } }
            }
            if (session.pending.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                session.pending.toList().forEach { at ->
                    Box {
                        FileThumb(at.path, 56.dp)
                        Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(Cue.ink.copy(alpha = 0.7f)).clickable(role = Role.Button, onClickLabel = "Remove") { session.pending.remove(at) },
                            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Remove", tint = Color.White, modifier = Modifier.size(13.dp)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).navigationBarsPadding().shadow(12.dp, CircleShape, spotColor = Cue.primaryDeep.copy(alpha = 0.35f))
                .clip(CircleShape).background(Color.White).padding(start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Cue.primaryTint).clickable(role = Role.Button, onClickLabel = "Add photos or files") { menu = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, "Add photos or files", tint = Cue.primaryDeep, modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Txt("Take a photo", Type.body) }, { menu = false; cameraTarget(c).let { (f, uri) -> cameraFile = f; camera.launch(uri) } }, leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) })
                        DropdownMenuItem({ Txt("Photos", Type.body) }, { menu = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) })
                        DropdownMenuItem({ Txt("Files and PDFs", Type.body) }, { menu = false; docs.launch(arrayOf("application/pdf", "image/*")) }, leadingIcon = { Icon(Icons.Rounded.Description, null) })
                    }
                }
                BasicTextField(text, { text = it }, Modifier.weight(1f).padding(start = 10.dp).padding(vertical = 15.dp), textStyle = Type.body.copy(color = Cue.ink),
                    cursorBrush = SolidColor(Cue.primaryDeep), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner -> if (text.isEmpty()) Txt(if (session.pending.isNotEmpty()) "Add a note, like \"Goa trip\"…" else "Ask Cue…", Type.body, Cue.muted); inner() })
                Box(Modifier.size(44.dp).clip(CircleShape).background(Cue.primaryButton).clickable(role = Role.Button, onClickLabel = "Send") { send() }, contentAlignment = Alignment.Center) {
                    Icon(if (session.thinking) Icons.Rounded.AutoAwesome else Icons.AutoMirrored.Rounded.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** A planning question's choices. Multi-choice questions keep a selection and send it with Done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionChips(q: PlanQuestion, onAnswer: (String) -> Unit, onSkipAll: () -> Unit) {
    var picked by remember(q) { mutableStateOf(q.preselected.toSet()) }
    FlowRow(Modifier.padding(start = 44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        q.options.forEach { o ->
            if (q.multi) ChoiceChip((if (o in picked) "✓ " else "") + o, selected = o in picked) { picked = if (o in picked) picked - o else picked + o }
            else ChoiceChip(o) { onAnswer(o) }
        }
        if (q.multi) ChoiceChip("Done", selected = true) { onAnswer(q.options.filter { it in picked }.joinToString(", ").ifBlank { "Skip" }) }
        Txt("Skip", Type.smallStrong, Cue.muted, modifier = Modifier.clip(CircleShape).clickable { onAnswer("Skip") }.padding(horizontal = 12.dp, vertical = 9.dp))
        Txt("Just make it", Type.smallStrong, Cue.muted, modifier = Modifier.clip(CircleShape).clickable { onSkipAll() }.padding(horizontal = 12.dp, vertical = 9.dp))
    }
}

/** The space Cue is about to make: its parts as chips (tap to leave one out), then Make it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanPreview(s: SpaceSpec, onDrop: (Int) -> Unit, onMake: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.padding(start = 44.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Cue.primaryBrush).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(s.title, Type.title, Color.White)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.blocks.forEachIndexed { i, b ->
                val (_, icon) = blockLook(b.type)
                Row(Modifier.clip(CircleShape).background(Color.White).clickable(role = Role.Button, onClickLabel = "Leave out ${b.title}") { onDrop(i) }
                    .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = Cue.primaryDeep, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                    Txt(b.title + (if (b.seed.isNotEmpty()) " · ${b.seed.size}" else ""), Type.tiny, Cue.ink)
                    Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.Close, null, tint = Cue.muted, modifier = Modifier.size(13.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("Cancel", Modifier.weight(1f), color = Color.White.copy(alpha = 0.2f), textColor = Color.White, height = 46.dp) { onCancel() }
            SoftButton("Make it", Modifier.weight(1f), color = Color.White, textColor = Cue.primaryDeep, height = 46.dp) { onMake() }
        }
    }
}
