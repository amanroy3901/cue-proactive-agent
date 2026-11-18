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
import androidx.compose.material.icons.rounded.AutoAwesome
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

/** One line of the conversation. A Cue turn can carry a generated screen ([surfaceId]). */
data class AskTurn(val fromUser: Boolean, val text: String, val surfaceId: String? = null, val note: Boolean = false)

class AskSession(private val a: MainActivity) {
    val turns = mutableStateListOf<AskTurn>()
    var thinking by mutableStateOf(false)
    private var count = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repairs = mutableSetOf<String>()

    val genUi = CueGenUi(
        onAction = { action -> scope.launch { onAction(action) } },
        onError = { surface, error -> scope.launch { repair(surface, error) } },
    )

    val env = object : CueGenEnv {
        override val dao get() = a.cue.engine.dao
        override fun open(route: String) { a.openRoute(route) }
    }

    fun ask(text: String) {
        if (text.isBlank() || thinking) return
        turns += AskTurn(true, text.trim())
        scope.launch { answer() }
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

    private fun history(): List<Pair<String, String>> = turns.filter { !it.note }.takeLast(12).map { (if (it.fromUser) "user" else "model") to it.text }

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
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(session.turns.size, session.thinking) { if (session.turns.isNotEmpty()) list.animateScrollToItem(session.turns.size + 1) }
    fun send(q: String = text) { if (q.isNotBlank()) { session.ask(q); text = "" } }
    GlowBackground(Cue.glowLavender, Cue.glowBlush, Cue.base) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { BackHeader("Ask Cue", "Type a question. I answer with a screen.", { nav.back() }) }
                if (session.turns.isEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pixel(PixelMood.Idle, 72.dp); Spacer(Modifier.width(10.dp))
                            Txt("Ask about your day, lists, papers or habits. Ask me to change something and I'll show you a button first.", Type.body, Cue.muted)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("What's my day look like?", "What's left to pack?", "How are my habits going?", "Remind me to call Mom at 7 pm", "Show my flight", "What do I owe people?").forEach { s ->
                                Txt(s, Type.smallStrong, Cue.primaryDeep, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.9f)).clickable { send(s) }.padding(horizontal = 14.dp, vertical = 9.dp))
                            }
                        }
                    }
                }
                itemsIndexed(session.turns) { _, t ->
                    when {
                        t.fromUser -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Txt(t.text, Type.body, Color.White, modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(Cue.primaryBrush).padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                        t.note -> Txt(t.text, Type.small, Cue.muted, modifier = Modifier.padding(start = 44.dp))
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                PixelHead(34.dp); Spacer(Modifier.width(10.dp))
                                Txt(t.text, Type.body, modifier = Modifier.clip(RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp))
                            }
                            t.surfaceId?.let { id -> GenSurface(session.genUi, surfaces, id, session.env, Modifier.fillMaxWidth()) }
                        }
                    }
                }
                if (session.thinking) item { Row(verticalAlignment = Alignment.CenterVertically) { Pixel(PixelMood.Idle, 40.dp); Spacer(Modifier.width(8.dp)); Txt("Drawing your answer…", Type.small, Cue.muted) } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).navigationBarsPadding().shadow(12.dp, CircleShape, spotColor = Cue.primaryDeep.copy(alpha = 0.35f))
                .clip(CircleShape).background(Color.White).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(text, { text = it }, Modifier.weight(1f).padding(vertical = 15.dp), textStyle = Type.body.copy(color = Cue.ink),
                    cursorBrush = SolidColor(Cue.primaryDeep), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner -> if (text.isEmpty()) Txt("Ask Cue…", Type.body, Cue.muted); inner() })
                Box(Modifier.size(44.dp).clip(CircleShape).background(Cue.primaryButton).clickable(role = Role.Button, onClickLabel = "Send") { send() }, contentAlignment = Alignment.Center) {
                    Icon(if (session.thinking) Icons.Rounded.AutoAwesome else Icons.AutoMirrored.Rounded.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
