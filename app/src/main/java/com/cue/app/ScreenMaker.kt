package com.cue.app

import android.util.Log
import com.cue.agent.CueTools
import com.cue.agent.HabitDays
import com.cue.agent.Spaces
import com.cue.agent.TextKit
import com.cue.domain.*
import com.cue.genui.CueCatalog
import com.cue.genui.CueGenUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Generated UI for every surface of the app: Ask Cue's answers, the top of a space's page, Home's brief and the
 * board beside a call. One prompt, one picture of the user's data, one thinking model; each place says what it is
 * for ([Purpose]) and gets back a screen in A2UI plus Cue's reasoning.
 */
class ScreenMaker(private val app: CueApp) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    enum class Purpose { CHAT, SPACE, HOME, CALL }

    /** What came back: Cue's words, how it got there, the tools it asked to run, and whether there is a screen. */
    data class Made(val say: String, val thoughts: List<String>, val tools: List<Pair<String, Map<String, String>>>, val hasUi: Boolean, val ms: Long, val model: String,
        /** A full plan's stops ("Fri 11 Dec 9 AM" to "Fly to Goa"), read from its timelines: Cue's own Save button uses them. */
        val stops: List<Pair<String, String>> = emptyList())

    /**
     * Asks the model and draws its screen into [genUi] as [surfaceId]. [history] is the conversation so far (user /
     * model turns); [request] is what to answer now. Null when offline or the reply wasn't usable.
     */
    suspend fun make(genUi: CueGenUi, surfaceId: String, request: String, purpose: Purpose, history: List<Pair<String, String>> = emptyList(), contextAlbum: Long? = null): Made? {
        val (json, made) = ask(surfaceId, request, purpose, history, contextAlbum) ?: return null
        json.optJSONArray("ui")?.takeIf { it.length() > 0 }?.let { ui -> runCatching { genUi.feedAll(ui, surfaceId) }.onFailure { Log.w("CueScreens", "feed", it) } }
        return made
    }

    /** For a screen drawn elsewhere (the call board): the A2UI messages as JSON text, and Cue's line. */
    suspend fun makeRaw(surfaceId: String, request: String, purpose: Purpose, contextAlbum: Long?): Pair<String, String>? {
        val (json, made) = ask(surfaceId, request, purpose, emptyList(), contextAlbum) ?: return null
        val ui = json.optJSONArray("ui")?.takeIf { it.length() > 0 } ?: return null
        return ui.toString() to made.say
    }

    /** Only for the catalogue text: every renderer shares the same catalogue. */
    private val catalogue by lazy { CueGenUi(onAction = {}) }

    private suspend fun ask(surfaceId: String, request: String, purpose: Purpose, history: List<Pair<String, String>>, contextAlbum: Long?): Pair<JSONObject, Made>? {
        if (!app.gemini.configured) return null
        val t0 = System.currentTimeMillis()
        val sys = systemPrompt(surfaceId, catalogue.compactSchema, purpose, contextAlbum)
        val convo = history + ("user" to (snapshot(contextAlbum) + "\n\nREQUEST: " + request))
        if ((app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            runCatching { java.io.File(app.filesDir, "last_ask_prompt.txt").writeText(sys + "\n\n=====\n" + convo.joinToString("\n---\n") { it.first + ": " + it.second }) }
        val (raw, model) = withContext(Dispatchers.IO) { app.gemini.thinkJson(sys, convo) } ?: return null
        if ((app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) runCatching { java.io.File(app.filesDir, "last_ask_reply.txt").writeText(raw) }
        val json = runCatching { JSONObject(raw.removePrefix("```json").removeSuffix("```").trim()) }.getOrNull() ?: return null
        val ui = json.optJSONArray("ui")
        val hasUi = ui != null && ui.length() > 0
        val thoughts = json.optJSONArray("thinking")?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }.take(6) }.orEmpty()
        val tools = json.optJSONArray("tools")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { t ->
                t.optString("name") to (t.optJSONObject("args")?.let { o -> o.keys().asSequence().associateWith { k -> o.opt(k)?.toString().orEmpty() } }.orEmpty())
            }
        }.orEmpty().takeIf { purpose == Purpose.CHAT }.orEmpty()
        Log.i("CueScreens", "$purpose by $model in ${System.currentTimeMillis() - t0} ms")
        return json to Made(json.optString("say"), thoughts, tools, hasUi, System.currentTimeMillis() - t0, model, ui?.let { planStops(it) }.orEmpty())
    }

    /**
     * The stops of a plan screen, in order: every CueTimeline step, its time prefixed with the day from the tab (or
     * the timeline's own title) it sits under. Empty unless the screen is a real plan (at least three timed steps).
     */
    private fun planStops(ui: org.json.JSONArray): List<Pair<String, String>> {
        val objs = (0 until ui.length()).mapNotNull { ui.optJSONObject(it) }
        // Wrapped in updateComponents, or (a sloppy reply) bare components.
        val comps = objs.mapNotNull { it.optJSONObject("updateComponents")?.optJSONArray("components") }.flatMap { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } +
            objs.filter { it.has("component") }
        val byId = comps.associateBy { it.optString("id") }
        val parent = HashMap<String, String>()
        val tabTitle = HashMap<String, String>()
        comps.forEach { c ->
            val id = c.optString("id")
            c.optJSONArray("children")?.let { k -> (0 until k.length()).forEach { parent[k.optString(it)] = id } }
            c.optString("child").takeIf { it.isNotBlank() }?.let { parent[it] = id }
            c.optJSONArray("tabs")?.let { t -> (0 until t.length()).mapNotNull { t.optJSONObject(it) }.forEach { tab ->
                val child = tab.optString("child"); parent[child] = id
                tabTitle[child] = tab.opt("title")?.let { v -> (v as? JSONObject)?.optString("literalString") ?: v.toString() }.orEmpty()
            } }
        }
        fun dayOf(id: String): String? { var cur: String? = id; repeat(8) { val c = cur ?: return null; tabTitle[c]?.let { return it }; cur = parent[c] }; return null }
        val stops = mutableListOf<Pair<String, String>>()
        comps.filter { it.optString("component") == "CueTimeline" }.forEach { tl ->
            val day = dayOf(tl.optString("id")) ?: tl.optString("title")
            val steps = tl.optJSONArray("steps") ?: return@forEach
            (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }.forEach { st ->
                val time = st.optString("time"); val what = st.optString("title")
                if (what.isNotBlank()) stops += (if (time.any { it.isLetter() && it.isUpperCase() } && Regex("(?i)mon|tue|wed|thu|fri|sat|sun|dec|nov|jan|day").containsMatchIn(time)) time else "$day $time").trim() to what
            }
        }
        return if (stops.size >= 3) stops else emptyList()
    }

    /** The renderer rejected a screen: one corrected redraw. */
    suspend fun repair(genUi: CueGenUi, surfaceId: String, error: String, purpose: Purpose, history: List<Pair<String, String>>) {
        val reply = withContext(Dispatchers.IO) {
            app.gemini.chatJson(systemPrompt(surfaceId, genUi.compactSchema, purpose, null), history + ("user" to "The screen $surfaceId failed to render: $error. Send the whole screen again as corrected ui messages for surface $surfaceId."))
        } ?: return
        val ui = runCatching { JSONObject(reply).optJSONArray("ui") }.getOrNull() ?: return
        genUi.feed(JSONObject().put("version", "v0.9").put("deleteSurface", JSONObject().put("surfaceId", surfaceId)).toString())
        genUi.feedAll(ui, surfaceId)
    }

    private suspend fun systemPrompt(surfaceId: String, schema: String, purpose: Purpose, contextAlbum: Long?): String {
        val where = when (purpose) {
            Purpose.CHAT -> "The user is TYPING to you in Ask Cue; answer in text and draw the answer as a native screen."
            Purpose.SPACE -> {
                val al = contextAlbum?.let { app.engine.dao.album(it) }
                "This screen sits at the TOP OF THE PAGE for space #${al?.id} ${al?.title}, as Cue's take. Use 3 to 7 components: lead with a CueInsight (what matters most now: a risk, a missing piece, or the next step), then at most two of: a Row of two CueProgress or CueStat, a CueTimeline of what's next, a CueCompare if a decision is open. End with 1-2 Buttons. Don't repeat the page's own lists or documents. \"tools\" must be empty. \"say\" is one short line."
            }
            Purpose.HOME -> "This screen is the BRIEF AT THE TOP OF HOME for today. Use 3 to 7 components: lead with a CueInsight on the single most important thing in the next 48 hours, then a Row of two CueStat or CueProgress, optionally a CueTimeline of today from the reminders, then 1-2 Buttons. \"tools\" must be empty. \"say\" is one short line."
            Purpose.CALL -> "You are drawing for the BOARD beside a live voice call. Show the FINISHED thing, never a brief or a form: no TextField, DateTimeInput, Slider or ChoicePicker (the user is talking, not typing). For a plan, draw the plan itself with sensible assumptions stated in the CueInsight: Tabs by day with a CueTimeline each, CueBars for the budget, CueCompare for the big choice. Otherwise show the request visually (CueTimeline, CueCompare, CueBars, CueInsight, live CueList or CueReminders). 3 to 12 components, no Buttons. \"tools\" must be empty. \"say\" is one short line."
        }
        return """
        You are Cue, the user's personal chief of staff on their phone. You know their lists, spaces (trips, events, health), reminders, habits, papers, people and money from the DATA below.
        $where
        THINK like a sharp assistant who knows their whole life, not a search box:
        1. What do they really need? (Read between the lines: "am I ready for Sunday" means flight + packing + cab + money + anything clashing.)
        2. Connect the dots across their data: dates that clash, things still open, money vs budget, people waiting on them, habits slipping, a paper that expires.
        3. Find what they didn't ask but should know: the risk, the clash, the next step, the pattern. Be specific, with their real names, numbers and times.
        4. Recommend. Have an opinion. Offer the next actions as buttons.
        Never invent data that isn't below. If something important is unknown (weather, prices, traffic), say so briefly or estimate clearly as an estimate.

        PLANNING (a trip, party, wedding, move, surgery, exam, any goal): be a planning PARTNER, not a form. Never ask questions one at a time.${if (purpose == Purpose.CALL) " (On a call: skip the brief and draw the plan directly.)" else ""}
        - First reply to a new plan is ONE screen that moves it forward:
          a) CueInsight (tone idea): what you already know that matters (free dates, clashes with their spaces, reminders or bills, people involved) and your suggestion.
          b) A Card titled with a Text h4 "Your brief" holding 3 to 6 inputs, only for what changes the plan and isn't known yet, each bound to /form and PRE-FILLED with smart defaults via updateDataModel (guess from their data; e.g. home city, the next free weekend): TextField "From", DateTimeInput start and end (enableDate true, enableTime false, ISO dates), ChoicePicker "Who's coming" (mutuallyExclusive, chips), Slider "Budget (₹)", ChoicePicker "Vibe" (multipleSelection, chips), ChoicePicker "Pace".
          c) A first-draft CueTimeline titled "First sketch" with 3 to 5 highlights, so they react instead of answer.
          d) Buttons: primary "Build my plan" with action {"event":{"name":"ask","context":{"text":"Build my full plan","from":{"path":"/form/from"}, ...one entry per field...}}}; borderless "Surprise me" (ask).
          "say": warm and short, like "Here's a first sketch. Tweak the brief and I'll build the full plan."
        - When they ask to build it: the full plan on one screen: a CueInsight with the plan in one line and the key tip or risk; Tabs with one tab per day, each tab's child a CueTimeline with real times; CueBars of the budget split by category (estimates); a CueCompare for the biggest choice (where to stay, how to get there); then Buttons for changes only (ask): e.g. "Make it slower", "Cheaper stay", "Change something". Do NOT draw a save button: Cue adds its own "Save as a space" under every full plan. While planning, "tools" stays EMPTY (never space_create): saving happens when they tap Save.
        - After that, when they ask for changes, redraw the whole plan with the change, keeping what they liked.

        Reply with ONE JSON object, nothing else:
        {"thinking": ["3-5 short steps, how you worked it out, citing their data by name (never the #numbers)"],
         "say": "1-3 sentences: the answer and your recommendation, warm and direct",
         "tools": [ {"name": "...", "args": {...}} ],
         "ui": [ A2UI messages ]}

        TOOLS: put a tool in "tools" only when the user clearly asked you to change something now (add, tick, set, move, delete). Otherwise leave it empty and offer the change as a button.
        Tool list (args are strings):
        ${toolList()}

        UI: "ui" is a list of A2UI v0.9 messages for surface "$surfaceId":
        1. {"version":"v0.9","createSurface":{"surfaceId":"$surfaceId","catalogId":"${CueCatalog.ID}"}}
        2. {"version":"v0.9","updateComponents":{"surfaceId":"$surfaceId","components":[ ...flat list, one component must have "id":"root"... ]}}
        3. optional {"version":"v0.9","updateDataModel":{"surfaceId":"$surfaceId","path":"/form","value":{...}}} to pre-fill form fields.
        Each component is {"id":"...","component":"<Name>", ...its properties}. Children are referenced by id ("child":"id" or "children":["a","b"]).
        DESIGN: it must look like a polished product screen, not a chat dump. Root is a Column. Lead with a CueInsight (or one Text h3 headline of max 6 words when there's no insight). Group inputs inside a Card. Put stats and progress two to a Row, each with "weight":1. Text is short (max 2 lines each, no markdown headings, no emoji). One primary Button for the main action, others default or borderless. 4 to 14 components.
        Prefer Cue's live components (CueList, CueReminders, CueHabit, CuePaper, CuePerson, CueSpace): they draw the user's real data by its number. Use CueTimeline, CueBars, CueProgress, CueCompare, CueStat to make your thinking visible.
        Never copy private details (PNR, seat, amounts on papers, medicine doses, lab values) into Text; show the paper with CuePaper and it fills them in itself.
        A Button needs a child Text: {"id":"b1","component":"Button","child":"b1t","action":{"event":{"name":"<tool name or ask>","context":{...}}}}.
        Context values can read form fields: {"path":"/form/x"}. A button with event name "ask" and context {"text":"..."} asks you that follow-up (other context entries are sent along).
        Only for small talk, send a single Text root or leave "ui" empty.

        CATALOG (every component you may use, with its properties):
        $schema
        """.trimIndent()
    }

    private fun toolList(): String = CueTools.live.filter { it.name != "end_call" }.joinToString("\n") { t ->
        "- ${t.name}(${t.parameters.joinToString(", ") { p -> p.name + if (p.required) "" else "?" }}): ${t.description}"
    }

    /** What the model may know: names and numbers, not the private fields inside papers. */
    suspend fun snapshot(contextAlbum: Long?): String = withContext(Dispatchers.IO) {
        val dao = app.engine.dao
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("NOW: ").append(java.time.ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("EEEE d MMM yyyy, h:mm a", Locale.ENGLISH))).append('\n')
        sb.append("USER: ").append(app.settings.userName).append('\n')
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
        sb.append("SPACES:\n"); albums.take(10).forEach { al ->
            sb.append("  #${al.id} ${al.title} (${al.kind.lowercase()}${al.startAt?.let { ", " + TextKit.dateLabel(it, zone) + " " + TextKit.clock(it, zone) } ?: ""}${al.endAt?.let { " to " + TextKit.dateLabel(it, zone) } ?: ""})\n")
            val bs = dao.blocks(al.id)
            val recs = bs.flatMap { dao.records(it.id) }
            val es = dao.itemsInAlbum(al.id).filter { it.type == ItemType.CHECKLIST }.flatMap { dao.entries(it.id) }
            Spaces.summary(al, bs, recs, es, now, zone).takeIf { it.isNotBlank() }?.let { sb.append("    status: $it\n") }
            bs.firstOrNull { it.type == BlockType.EXPENSES }?.let { b ->
                val ex = Spaces.expenses(dao.records(b.id))
                if (ex.isNotEmpty() || Spaces.budget(b) != null) {
                    sb.append("    money: spent ₹${Spaces.money(ex.sumOf { it.amount })}${Spaces.budget(b)?.let { " of ₹" + Spaces.money(it) + " budget" } ?: ""}; ")
                    sb.append(ex.take(8).joinToString { "${it.what} ₹${Spaces.money(it.amount)}${if (it.paidBy.isNotBlank()) " (paid by ${it.paidBy})" else ""}" }).append('\n')
                    Spaces.settle(ex, Spaces.people(b)).firstOrNull()?.let { sb.append("    split: $it\n") }
                }
            }
        }
        dao.facts().filter { it.confirmed }.take(12).takeIf { it.isNotEmpty() }?.let { fs -> sb.append("ABOUT THE USER:\n"); fs.forEach { sb.append("  - ${it.text}\n") } }
        sb.toString()
    }
}
