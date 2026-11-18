package com.cue.agent

import com.cue.brain.FieldsRequest
import com.cue.domain.Album
import com.cue.domain.AlbumKind
import com.cue.domain.BlockRecord
import com.cue.domain.BlockType
import com.cue.domain.ChecklistEntry
import com.cue.domain.Item
import com.cue.domain.ItemType
import com.cue.domain.SpaceBlock
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Spaces: Cue builds the page a goal needs out of a fixed library of blocks. A model (or a template) proposes a
 * [SpaceSpec]; [SpaceSpec.validate] keeps only known block types and allowed settings, so a bad plan can never make a
 * broken page or bad data.
 */
data class BlockSpec(
    val type: String,
    val title: String,
    /** Checklist: first entries. */
    val seed: List<String> = emptyList(),
    /** Expenses: who costs are split with (besides the user). People: who is involved. */
    val people: List<String> = emptyList(),
    /** Expenses: the budget, in rupees. */
    val budget: Long? = null,
    val currency: String = "INR",
    /** Readings: which kinds (BP, SUGAR, WEIGHT, PULSE). */
    val kinds: List<String> = emptyList(),
) {
    fun config(): JSONObject = JSONObject().apply {
        if (people.isNotEmpty()) put("people", JSONArray(people))
        budget?.let { put("budget", it) }
        if (type == BlockType.EXPENSES) put("currency", currency)
        if (kinds.isNotEmpty()) put("kinds", JSONArray(kinds))
    }
}

data class SpaceSpec(
    val kind: String,
    val title: String,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val blocks: List<BlockSpec>,
    /** Where it came from: "model" or "template". Shown in the log, not to the user. */
    val by: String = "template",
) {
    /** "a packing list, itinerary, expenses split with Riya, documents and photos" */
    fun describe(): String {
        val parts = blocks.filter { it.type != BlockType.COUNTDOWN }.map { b ->
            when (b.type) {
                BlockType.EXPENSES -> "expenses" + (if (b.people.isNotEmpty()) " split with ${TextKit.joinAnd(b.people)}" else "") + (b.budget?.let { " (budget ₹${Spaces.money(it)})" } ?: "")
                BlockType.CHECKLIST -> b.title.lowercase(Locale.ROOT).let { if (it.endsWith("list")) "a $it" else "a '$it' list" }
                BlockType.GALLERY -> "photos"
                else -> b.title.lowercase(Locale.ROOT)
            }
        }
        return TextKit.joinAnd(parts)
    }

    fun json(): JSONObject = JSONObject().put("kind", kind).put("title", title).apply {
        start?.let { put("start", it.toString()) }; end?.let { put("end", it.toString()) }
        put("blocks", JSONArray(blocks.map { b -> b.config().put("type", b.type).put("title", b.title).apply { if (b.seed.isNotEmpty()) put("seed", JSONArray(b.seed)) } }))
    }

    companion object {
        private const val MAX_BLOCKS = 10

        /** The model's JSON, cleaned: unknown types dropped, sizes capped, dates checked. Null if nothing usable is left. */
        fun validate(raw: String, fallbackTitle: String = "New space"): SpaceSpec? {
            // Models wrap JSON in prose or code fences: take the outermost object.
            val a = raw.indexOf('{'); val z = raw.lastIndexOf('}')
            if (a < 0 || z <= a) return null
            val text = raw.substring(a, z + 1)
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val kind = o.optString("kind").uppercase(Locale.ROOT).takeIf { it in AlbumKind.all } ?: AlbumKind.GENERAL
            val title = clean(o.optString("title"), 40).ifBlank { fallbackTitle }
            val start = date(o.optString("start"))
            val end = date(o.optString("end"))?.takeIf { start == null || !it.isBefore(start) }
            val arr = o.optJSONArray("blocks") ?: return null
            val blocks = mutableListOf<BlockSpec>()
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val type = b.optString("type").lowercase(Locale.ROOT).trim().let { BLOCK_ALIASES[it] ?: it }
                if (type !in BlockType.all) continue
                // One of each, except lists: a trip can have "To pack" and "Before we leave".
                if (type != BlockType.CHECKLIST && blocks.any { it.type == type }) continue
                if (type == BlockType.CHECKLIST && blocks.count { it.type == BlockType.CHECKLIST } >= 3) continue
                blocks += BlockSpec(
                    type = type,
                    title = clean(b.optString("title"), 32).ifBlank { Spaces.defaultTitle(type) },
                    seed = strings(b.optJSONArray("seed")).map { clean(it, 60) }.filter { it.isNotBlank() }.distinct().take(25),
                    people = (strings(b.optJSONArray("people")) + strings(b.optJSONArray("split_with"))).map { clean(it, 24) }
                        .filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in setOf("me", "i", "myself", "user", "you") }.distinct().take(8),
                    budget = b.opt("budget")?.toString()?.let { Spaces.parseAmount(it) }?.takeIf { it in 1..100_000_000 },
                    currency = b.optString("currency").uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-Z]{3}")) } ?: "INR",
                    kinds = strings(b.optJSONArray("kinds")).map { it.uppercase(Locale.ROOT) }.filter { it in setOf("BP", "SUGAR", "WEIGHT", "PULSE") }.distinct(),
                )
                if (blocks.size >= MAX_BLOCKS) break
            }
            if (blocks.isEmpty()) return null
            return SpaceSpec(kind, title, start, end, blocks, by = "model")
        }

        private val BLOCK_ALIASES = mapOf("list" to BlockType.CHECKLIST, "todo" to BlockType.CHECKLIST, "packing" to BlockType.CHECKLIST, "expense" to BlockType.EXPENSES,
            "budget" to BlockType.EXPENSES, "photos" to BlockType.GALLERY, "pictures" to BlockType.GALLERY, "docs" to BlockType.DOCUMENTS, "papers" to BlockType.DOCUMENTS,
            "timeline" to BlockType.ITINERARY, "schedule" to BlockType.ITINERARY, "plan" to BlockType.ITINERARY, "note" to BlockType.NOTES, "contacts" to BlockType.PEOPLE,
            "guests" to BlockType.PEOPLE, "health" to BlockType.READINGS, "bp" to BlockType.READINGS, "medicine" to BlockType.MEDICINES, "calls" to BlockType.REMINDERS)

        private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it)?.toString() }
        private fun clean(s: String, max: Int) = s.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s+"), " ").trim().take(max)
        private fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim().take(10)) }.getOrNull()
    }
}

class Spaces(private val env: AgentEnv) {
    private val dao get() = env.dao
    private val zone get() = env.clock.zone

    /** A spec for [goal] from the fixed templates: instant, offline, and the fallback when the model's plan fails. */
    fun fromTemplate(goal: String, name: String = "", start: String = "", end: String = "", with: String = "", add: String = ""): SpaceSpec {
        val kind = kindOf(goal + " " + name)
        val now = env.clock.now()
        val people = people(with.ifBlank { Regex("\\bwith ([A-Za-z ,&]+?)(?:\\bon\\b|\\bnext\\b|\\bthis\\b|\\bfrom\\b|\\bfor\\b|\\bin\\b|[.!?]|$)").find(goal)?.groupValues?.get(1).orEmpty() })
        val s = parseDay(start, now, zone) ?: parseDay(goal, now, zone)
        val e = parseDay(end, now, zone)?.takeIf { s == null || !it.isBefore(s) }
            ?: Regex("for (\\w+) (days|nights)").find(goal.lowercase(Locale.ROOT))?.let { m -> TextKit.number(m.groupValues[1])?.let { n -> s?.plusDays(n.toLong() - if (m.groupValues[2] == "days") 1 else 0) } }
        val budget = budgetIn(goal)
        var blocks = template(kind, people).map { if (it.type == BlockType.EXPENSES && budget != null) it.copy(budget = budget) else it }
        // Blocks the model or the user asked for on top of the template ("add a budget", "and a photo album").
        for (t in add.split(',', ';', '+').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }) {
            val type = typeOf(t) ?: continue
            if (type != BlockType.CHECKLIST && blocks.any { it.type == type }) continue
            val title = if (type == BlockType.CHECKLIST) t.substringAfter(':', "").trim().ifBlank { "To do" }.replaceFirstChar { it.uppercase() } else defaultTitle(type)
            blocks = blocks + BlockSpec(type, title, people = if (type == BlockType.EXPENSES || type == BlockType.PEOPLE) people else emptyList())
        }
        return SpaceSpec(kind, name.trim().ifBlank { titleFor(goal, kind) }.take(40), s, e, blocks)
    }

    /**
     * The generative path: the one-shot model reads the goal and picks blocks, titles, seeds and settings.
     * Anything it gets wrong is cleaned by [SpaceSpec.validate]; if nothing usable comes back, the template is used.
     */
    suspend fun plan(goal: String): SpaceSpec {
        val base = fromTemplate(goal)
        // Planning needs the cloud model; the on-device model and the rules brain use the templates.
        val brain = (env as? CueEngine)?.router?.forJob("file")
        if (brain == null || brain.name != "cloud") return base
        val today = TextKit.local(env.clock.now(), zone).toLocalDate()
        val r = runCatching { brain.complete(FieldsRequest("space_plan", planPrompt(today), goal)) }.getOrNull()
        val spec = r?.raw?.takeIf { it.isNotBlank() }?.let { SpaceSpec.validate(it, base.title) }
        env.log("space plan: ${if (spec != null) "model, ${spec.blocks.size} blocks" else "template (model gave nothing usable)"}")
        return spec?.let { merge(it, base, goal) } ?: base
    }

    /**
     * What plain code read from the goal wins over the model where code is sure: the people named after "with",
     * the budget, and an end date from "for 3 days". The model can drop or blur those; code doesn't.
     */
    private fun merge(model: SpaceSpec, base: SpaceSpec, goal: String): SpaceSpec {
        val people = base.blocks.firstOrNull { it.type == BlockType.EXPENSES }?.people.orEmpty()
        val budget = base.blocks.firstOrNull { it.type == BlockType.EXPENSES }?.budget
        val g = goal.lowercase(Locale.ROOT)
        // Models fill gaps with guesses ("Parents", today's date). Keep only names the user actually said.
        fun said(names: List<String>) = names.filter { n -> Regex("\\b${Regex.escape(n.lowercase(Locale.ROOT))}\\b").containsMatchIn(g) }
        val blocks = model.blocks.map { b ->
            when (b.type) {
                BlockType.EXPENSES -> b.copy(people = (said(b.people) + people).distinctBy { it.lowercase(Locale.ROOT) }, budget = budget ?: b.budget)
                BlockType.PEOPLE -> b.copy(people = (said(b.people) + people).distinctBy { it.lowercase(Locale.ROOT) })
                else -> b
            }
        }
        // ...and only a date if the user gave one.
        val start = (if (mentionsDate(goal)) model.start else null) ?: base.start
        val end = (base.end ?: model.end?.takeIf { mentionsDate(goal) })?.takeIf { start == null || !it.isBefore(start) }
        return model.copy(start = start, end = end, blocks = blocks)
    }

    /** Whether the user's words name a day at all: a number, a month, a weekday, or today/tomorrow/next... */
    private fun mentionsDate(goal: String): Boolean = Regex("(?i)\\d|\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b|\\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b|\\b(today|tonight|tomorrow|next|this|weekend|week|month)\\b").containsMatchIn(goal)

    /** Saves the space: the album, its blocks in order, and a real list for every checklist block. */
    suspend fun create(spec: SpaceSpec): Long {
        val now = env.clock.now()
        val albumId = dao.insertAlbum(Album(title = spec.title, kind = spec.kind, startAt = spec.start?.let { TextKit.at(it, 9 * 60, zone) },
            endAt = spec.end?.let { TextKit.at(it, 21 * 60, zone) }, autoCreated = false, createdAt = now))
        spec.blocks.forEachIndexed { i, b -> addBlock(albumId, b, position = i) }
        env.log("space created: ${spec.title} (${spec.by}) with ${spec.blocks.joinToString { it.type }}")
        env.planner.reconcile()
        return albumId
    }

    suspend fun addBlock(albumId: Long, b: BlockSpec, position: Int? = null): Long {
        val now = env.clock.now()
        val config = b.config()
        if (b.type == BlockType.CHECKLIST) {
            val listId = dao.insertItem(Item(type = ItemType.CHECKLIST, title = b.title, albumId = albumId, source = "space", createdAt = now))
            b.seed.forEachIndexed { i, s -> dao.insertEntry(ChecklistEntry(itemId = listId, text = s, position = i)) }
            config.put("itemId", listId)
        }
        val pos = position ?: ((dao.blocks(albumId).maxOfOrNull { it.position } ?: -1) + 1)
        return dao.insertBlock(SpaceBlock(albumId = albumId, type = b.type, title = b.title, position = pos, config = config.toString(), createdAt = now))
    }

    suspend fun removeBlock(blockId: Long) {
        val b = dao.block(blockId) ?: return
        dao.deleteRecords(blockId)
        dao.deleteBlock(blockId)
        // A list made for this block goes with it only if it is still empty; anything the user wrote stays.
        JSONObject(b.config).optLong("itemId", 0).takeIf { it > 0 }?.let { id -> if (dao.entries(id).isEmpty()) dao.deleteItem(id) }
    }

    suspend fun moveBlock(blockId: Long, by: Int) {
        val b = dao.block(blockId) ?: return
        val list = dao.blocks(b.albumId).toMutableList()
        val i = list.indexOfFirst { it.id == blockId }
        val j = (i + by).coerceIn(0, list.lastIndex)
        if (i == j) return
        list.add(j, list.removeAt(i))
        list.forEachIndexed { k, x -> if (x.position != k) dao.updateBlock(x.copy(position = k)) }
    }

    /** Albums made before Spaces (or filed automatically) get the blocks for their kind the first time they open. */
    suspend fun ensureBlocks(album: Album) {
        if (dao.blocks(album.id).isNotEmpty()) return
        val now = env.clock.now()
        template(album.kind, emptyList()).forEachIndexed { i, b ->
            // Existing lists show in an unbound checklist block; no new empty list is made.
            val config = b.config()
            dao.insertBlock(SpaceBlock(albumId = album.id, type = b.type, title = b.title, position = i, config = config.toString(), createdAt = now))
        }
    }

    // ------------------------------------------------------------------ expenses and itinerary

    data class Expense(val id: Long, val amount: Long, val what: String, val paidBy: String, val at: Long)
    data class Stop(val id: Long, val at: Long, val text: String, val place: String)

    suspend fun addExpense(blockId: Long, amount: Long, what: String, paidBy: String = "", at: Long = env.clock.now()): Long? {
        val b = dao.block(blockId)?.takeIf { it.type == BlockType.EXPENSES } ?: return null
        if (amount <= 0 || amount > 100_000_000) return null
        val people = people(b)
        val payer = paidBy.trim().let { p -> if (p.isBlank() || p.lowercase(Locale.ROOT) in ME) "Me" else people.firstOrNull { it.equals(p, true) } ?: p.replaceFirstChar { it.uppercase() } }
        val data = JSONObject().put("amount", amount).put("what", what.trim().take(60).ifBlank { "Expense" }.replaceFirstChar { it.uppercase() }).put("paidBy", payer)
        return dao.insertRecord(BlockRecord(blockId = blockId, albumId = b.albumId, data = data.toString(), at = at, createdAt = env.clock.now()))
    }

    suspend fun addStop(blockId: Long, at: Long, text: String, place: String = ""): Long? {
        val b = dao.block(blockId)?.takeIf { it.type == BlockType.ITINERARY } ?: return null
        if (text.isBlank()) return null
        val data = JSONObject().put("text", text.trim().take(80).replaceFirstChar { it.uppercase() }).put("place", place.trim().take(40))
        return dao.insertRecord(BlockRecord(blockId = blockId, albumId = b.albumId, data = data.toString(), at = at, createdAt = env.clock.now()))
    }

    suspend fun block(albumId: Long, type: String): SpaceBlock? = dao.blocks(albumId).firstOrNull { it.type == type }

    /** The space this call or screen means: by name, else the one in context, else the soonest upcoming one. */
    suspend fun find(name: String, contextAlbum: Long?): Album? {
        val albums = dao.albums()
        name.trim().removePrefix("#").toLongOrNull()?.let { n -> albums.firstOrNull { it.id == n }?.let { return it } }
        val q = name.lowercase(Locale.ROOT).replace(Regex("\\b(the|my|space|album|trip)\\b"), "").trim()
        if (q.isNotBlank()) return albums.firstOrNull { it.title.lowercase(Locale.ROOT).contains(q) }
            ?: albums.maxByOrNull { TextKit.similarity(it.title, q) }?.takeIf { TextKit.similarity(it.title, q) >= 0.5 }
        contextAlbum?.let { id -> albums.firstOrNull { it.id == id }?.let { return it } }
        val now = env.clock.now()
        return albums.filter { (it.endAt ?: it.startAt ?: 0) > now - 86_400_000 && it.startAt != null }.minByOrNull { it.startAt!! }
            ?: albums.firstOrNull { !it.autoCreated }
    }

    companion object {
        private val ME = setOf("me", "i", "myself", "self", "you", "mine")

        /**
         * Where the space stands in one line, so nobody has to scroll through its blocks: "In 6 days · ₹19,200 of
         * ₹20,000 spent · Riya owes you ₹8,400 · Next: Check in at Taj, Sat 2 PM · 5 left on To pack".
         */
        fun summary(album: Album, blocks: List<SpaceBlock>, records: List<BlockRecord>, entries: List<ChecklistEntry>, now: Long, zone: ZoneId): String {
            val parts = mutableListOf<String>()
            val today = TextKit.local(now, zone).toLocalDate()
            album.startAt?.let { s ->
                val start = TextKit.local(s, zone).toLocalDate()
                val end = album.endAt?.let { TextKit.local(it, zone).toLocalDate() } ?: start
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, start)
                parts += when {
                    days > 1 -> "In $days days"
                    days == 1L -> "Tomorrow"
                    !today.isAfter(end) -> if (end == start) "Today" else "Day ${java.time.temporal.ChronoUnit.DAYS.between(start, today) + 1} of ${java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1}"
                    else -> "Done"
                }
            }
            blocks.firstOrNull { it.type == BlockType.EXPENSES }?.let { b ->
                val ex = expenses(records.filter { it.blockId == b.id })
                if (ex.isNotEmpty()) {
                    val total = ex.sumOf { it.amount }
                    parts += "₹${money(total)}" + (budget(b)?.let { " of ₹${money(it)}" } ?: "") + " spent"
                    settle(ex, people(b)).firstOrNull()?.let { parts += it }
                }
            }
            blocks.firstOrNull { it.type == BlockType.ITINERARY }?.let { b ->
                stops(records.filter { it.blockId == b.id }).firstOrNull { it.at > now }?.let { st ->
                    parts += "Next: ${st.text}, ${TextKit.dayLabel(st.at, now, zone)} ${TextKit.clock(st.at, zone)}"
                }
            }
            blocks.filter { it.type == BlockType.CHECKLIST }.forEach { b ->
                val id = runCatching { JSONObject(b.config).optLong("itemId", 0) }.getOrDefault(0)
                val open = entries.count { it.itemId == id && !it.checked }
                if (id > 0 && open > 0) parts += "$open left on ${b.title}"
            }
            return parts.joinToString(" · ")
        }

        fun expenses(records: List<BlockRecord>): List<Expense> = records.mapNotNull { r ->
            val o = runCatching { JSONObject(r.data) }.getOrNull() ?: return@mapNotNull null
            Expense(r.id, o.optLong("amount"), o.optString("what"), o.optString("paidBy", "Me").ifBlank { "Me" }, r.at)
        }

        fun stops(records: List<BlockRecord>): List<Stop> = records.mapNotNull { r ->
            val o = runCatching { JSONObject(r.data) }.getOrNull() ?: return@mapNotNull null
            Stop(r.id, r.at, o.optString("text"), o.optString("place"))
        }.sortedBy { it.at }

        fun people(b: SpaceBlock): List<String> = runCatching { JSONObject(b.config).optJSONArray("people") }.getOrNull()?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        fun budget(b: SpaceBlock): Long? = runCatching { JSONObject(b.config) }.getOrNull()?.optLong("budget", 0)?.takeIf { it > 0 }

        /**
         * Who owes whom, splitting every expense equally between the user and [people]. Positive balance: they owe
         * the user. Returns lines like "Riya owes you ₹600".
         */
        fun settle(expenses: List<Expense>, people: List<String>): List<String> {
            if (people.isEmpty() || expenses.isEmpty()) return emptyList()
            val everyone = listOf("Me") + people
            val paid = everyone.associateWith { p -> expenses.filter { it.paidBy.equals(p, true) }.sumOf { it.amount } }.toMutableMap()
            // Someone not in the split who paid still counts as a payer.
            expenses.map { it.paidBy }.filter { p -> everyone.none { it.equals(p, true) } }.distinct().forEach { paid[it] = expenses.filter { e -> e.paidBy == it }.sumOf { e -> e.amount } }
            val share = expenses.sumOf { it.amount }.toDouble() / everyone.size
            return people.mapNotNull { p ->
                val bal = share - (paid[p] ?: 0)   // > 0: they used more than they paid, so they owe
                when {
                    abs(bal) < 1 -> null
                    bal > 0 -> "$p owes you ₹${money(bal.roundToLong())}"
                    else -> "You owe $p ₹${money((-bal).roundToLong())}"
                }
            }
        }

        /** "1,200", "1.2k", "₹500", "rs 300", "2 lakh" → rupees. */
        fun parseAmount(s: String): Long? {
            val t = s.lowercase(Locale.ROOT).replace(",", "")
            val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(k|thousand|lakh|lac|l\\b)?").find(t) ?: return null
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            val mul = when (m.groupValues[2]) { "k", "thousand" -> 1_000.0; "lakh", "lac", "l" -> 100_000.0; else -> 1.0 }
            return (n * mul).roundToLong().takeIf { it > 0 }
        }

        /** "1200 dinner Riya paid", "paid 300 for the cab", "₹450 snacks" → amount, what, payer. */
        fun parseExpense(text: String): Triple<Long, String, String>? {
            val amount = parseAmount(text) ?: return null
            val lower = text.trim()
            val payer = Regex("(?i)(?:paid by|by) ([A-Za-z]+)\\s*$").find(lower)?.groupValues?.get(1)
                ?: Regex("(?i)\\b([A-Za-z]+) paid\\b").find(lower)?.groupValues?.get(1)?.takeIf { it.lowercase(Locale.ROOT) !in setOf("i", "we", "who", "was", "had") }
                ?: ""
            val what = lower.replace(Regex("(?i)(₹|rs\\.?|inr|rupees?)"), " ").replace(Regex("(?i)\\d[\\d,]*(?:\\.\\d+)?\\s*(k|thousand|lakh|lac)?\\b"), " ")
                .replace(Regex("(?i)\\b(paid by|by) [A-Za-z]+\\s*$"), " ").replace(Regex("(?i)\\b[A-Za-z]+ paid\\b"), " ")
                .replace(Regex("(?i)\\b(i paid|paid|spent|for|on|the|a|an|add|expense)\\b"), " ").replace(Regex("\\s+"), " ").trim().trim(',', '.', '-', ':', ';').trim()
            return Triple(amount, what.ifBlank { "Expense" }.replaceFirstChar { it.uppercase() }, payer)
        }

        /** "budget 20000", "20k budget", "budget of ₹1.5 lakh" → rupees. */
        fun budgetIn(text: String): Long? {
            val t = text.lowercase(Locale.ROOT)
            val m = Regex("budget\\s*(?:of|is|:|around|about)?\\s*(?:rs\\.?|₹|inr)?\\s*(\\d[\\d,.]*\\s*(?:k|thousand|lakh|lac)?)").find(t)
                ?: Regex("(?:rs\\.?|₹|inr)?\\s*(\\d[\\d,.]*\\s*(?:k|thousand|lakh|lac)?)\\s*budget").find(t) ?: return null
            return parseAmount(m.groupValues[1])
        }

        /** Indian grouping: 120000 → 1,20,000. */
        fun money(n: Long): String {
            val s = abs(n).toString()
            if (s.length <= 3) return (if (n < 0) "-" else "") + s
            val last3 = s.takeLast(3)
            val rest = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
            return (if (n < 0) "-" else "") + "$rest,$last3"
        }

        fun kindOf(goal: String): String {
            val g = goal.lowercase(Locale.ROOT)
            return when {
                Regex("\\b(surgery|operation|doctor|dr\\.?|hospital|clinic|check ?-?up|treatment|physio|therapy|pregnan|diabetes|bp|blood pressure|dentist|scan|recovery)\\b").containsMatchIn(g) -> AlbumKind.HEALTH
                Regex("\\b(wedding|marriage|shaadi|party|birthday|anniversary|function|ceremony|reception|puja|pooja|festival|diwali|event)\\b").containsMatchIn(g) -> AlbumKind.EVENT
                Regex("\\b(moving|move house|shifting|shift(ing)? (house|home|flat)|new (flat|house|home|apartment)|relocat\\w*)\\b").containsMatchIn(g) -> AlbumKind.MOVE
                Regex("\\b(exam|exams|study|studying|test prep|course|interview|semester|gate|upsc|cat|jee|neet|certification)\\b").containsMatchIn(g) -> AlbumKind.STUDY
                Regex("\\b(trip|travel|travell?ing|flight|fly|flying|vacation|holiday|tour|trek|going to|visit(ing)?|weekend in|road ?trip|honeymoon)\\b").containsMatchIn(g) -> AlbumKind.TRIP
                else -> AlbumKind.GENERAL
            }
        }

        fun defaultTitle(type: String) = when (type) {
            BlockType.COUNTDOWN -> "Countdown"; BlockType.DOCUMENTS -> "Documents"; BlockType.CHECKLIST -> "To do"; BlockType.ITINERARY -> "Itinerary"
            BlockType.EXPENSES -> "Expenses"; BlockType.GALLERY -> "Photos"; BlockType.NOTES -> "Notes"; BlockType.JOURNAL -> "Journal"
            BlockType.PEOPLE -> "People"; BlockType.READINGS -> "Readings"; BlockType.MEDICINES -> "Medicines"; BlockType.REMINDERS -> "Calls from Cue"
            else -> type.replaceFirstChar { it.uppercase() }
        }

        fun typeOf(word: String): String? {
            val w = word.lowercase(Locale.ROOT).substringBefore(':').trim()
            return BlockType.all.firstOrNull { it == w || it.removeSuffix("s") == w.removeSuffix("s") } ?: when {
                Regex("pack|list|to ?do|checklist|question").containsMatchIn(w) -> BlockType.CHECKLIST
                Regex("expens|budget|spend|money|cost|split").containsMatchIn(w) -> BlockType.EXPENSES
                Regex("photo|picture|gallery|pics").containsMatchIn(w) -> BlockType.GALLERY
                Regex("doc|ticket|paper|bill|report").containsMatchIn(w) -> BlockType.DOCUMENTS
                Regex("itiner|plan|schedule|timeline|stops").containsMatchIn(w) -> BlockType.ITINERARY
                Regex("count").containsMatchIn(w) -> BlockType.COUNTDOWN
                Regex("note").containsMatchIn(w) -> BlockType.NOTES
                Regex("journal|diary").containsMatchIn(w) -> BlockType.JOURNAL
                Regex("people|guest|contact").containsMatchIn(w) -> BlockType.PEOPLE
                Regex("reading|bp|sugar|weight").containsMatchIn(w) -> BlockType.READINGS
                Regex("medic|dose|pill").containsMatchIn(w) -> BlockType.MEDICINES
                Regex("remind|call").containsMatchIn(w) -> BlockType.REMINDERS
                else -> null
            }
        }

        /** The starting blocks for each kind of goal. */
        fun template(kind: String, people: List<String>): List<BlockSpec> = when (kind) {
            AlbumKind.TRIP -> listOf(
                BlockSpec(BlockType.COUNTDOWN, "Countdown"),
                BlockSpec(BlockType.DOCUMENTS, "Tickets and bookings"),
                BlockSpec(BlockType.ITINERARY, "Itinerary"),
                BlockSpec(BlockType.CHECKLIST, "To pack", seed = listOf("ID card", "Phone charger", "Medicines", "Cash and cards")),
                BlockSpec(BlockType.EXPENSES, "Expenses", people = people),
                BlockSpec(BlockType.GALLERY, "Photos"),
                BlockSpec(BlockType.JOURNAL, "Trip journal"))
            AlbumKind.HEALTH -> listOf(
                BlockSpec(BlockType.COUNTDOWN, "Next visit"),
                BlockSpec(BlockType.DOCUMENTS, "Reports and prescriptions"),
                BlockSpec(BlockType.CHECKLIST, "Ask the doctor", seed = listOf("Side effects to watch for", "When to come back")),
                BlockSpec(BlockType.MEDICINES, "Medicines"),
                BlockSpec(BlockType.READINGS, "Readings", kinds = listOf("BP", "SUGAR")),
                BlockSpec(BlockType.NOTES, "Doctor's notes"),
                BlockSpec(BlockType.REMINDERS, "Calls from Cue"))
            AlbumKind.EVENT -> listOf(
                BlockSpec(BlockType.COUNTDOWN, "Countdown"),
                BlockSpec(BlockType.CHECKLIST, "To do"),
                BlockSpec(BlockType.PEOPLE, "Guests", people = people),
                BlockSpec(BlockType.EXPENSES, "Budget", people = emptyList()),
                BlockSpec(BlockType.DOCUMENTS, "Bookings and bills"),
                BlockSpec(BlockType.GALLERY, "Photos"))
            AlbumKind.MOVE -> listOf(
                BlockSpec(BlockType.COUNTDOWN, "Moving day"),
                BlockSpec(BlockType.CHECKLIST, "To do", seed = listOf("Book movers", "Update address at the bank", "Transfer electricity and internet", "Pack the kitchen last")),
                BlockSpec(BlockType.EXPENSES, "Moving costs", people = people),
                BlockSpec(BlockType.DOCUMENTS, "Agreement and bills"),
                BlockSpec(BlockType.NOTES, "Notes"))
            AlbumKind.STUDY -> listOf(
                BlockSpec(BlockType.COUNTDOWN, "Exam day"),
                BlockSpec(BlockType.CHECKLIST, "Topics"),
                BlockSpec(BlockType.NOTES, "Notes"),
                BlockSpec(BlockType.DOCUMENTS, "Admit card and papers"),
                BlockSpec(BlockType.REMINDERS, "Calls from Cue"))
            AlbumKind.ADMIN -> listOf(
                BlockSpec(BlockType.DOCUMENTS, "Bills and papers"),
                BlockSpec(BlockType.EXPENSES, "Payments"),
                BlockSpec(BlockType.NOTES, "Notes"),
                BlockSpec(BlockType.REMINDERS, "Calls from Cue"))
            else -> listOf(
                BlockSpec(BlockType.CHECKLIST, "To do"),
                BlockSpec(BlockType.NOTES, "Notes"),
                BlockSpec(BlockType.DOCUMENTS, "Documents"),
                BlockSpec(BlockType.GALLERY, "Photos"))
        }

        /** "Riya and Sam", "Riya, Sam & Dad" → names. */
        fun people(s: String): List<String> = s.split(Regex(",|&|\\band\\b")).map { it.trim().trimEnd('.') }
            .filter { it.isNotBlank() && it.length <= 24 && it.lowercase(Locale.ROOT) !in ME && it.lowercase(Locale.ROOT) !in setOf("family", "friends") }
            .map { w -> w.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } } }.distinct().take(8)

        /** "going to Goa next Friday" → "Goa trip"; "dad's knee surgery" → "Dad's knee surgery". */
        fun titleFor(goal: String, kind: String): String {
            val g = goal.trim().trimEnd('.', '!', '?')
            if (kind == AlbumKind.TRIP) {
                Regex("\\b(?:to|in|visit(?:ing)?)\\s+([A-Z][a-zA-Z]+(?:\\s[A-Z][a-zA-Z]+)?)").find(g)?.let { return "${it.groupValues[1]} trip" }
                Regex("\\b(?:to|in|visit(?:ing)?)\\s+([a-z]{3,})\\b").find(g)?.groupValues?.get(1)?.takeIf { it !in setOf("the", "see", "meet", "my", "our") }
                    ?.let { return "${it.replaceFirstChar { c -> c.uppercase() }} trip" }
            }
            val cut = g.replace(Regex("(?i)^(i'?m|i am|we'?re|we are|planning|plan|help me (plan|with)|organi[sz]e|my|our)\\s+"), "")
                .replace(Regex("(?i)^(planning|going|having|getting)\\s+(a|an|the|my|our)?\\s*"), "")
                .replace(Regex("(?i)\\s+(on|next|this|from|in)\\s+(mon|tues|wednes|thurs|fri|satur|sun)day.*$"), "")
                .replace(Regex("(?i)\\s+(next|this) (week|month|weekend).*$"), "")
                .replace(Regex("(?i)\\s+with\\s+.*$"), "")
            return cut.trim().ifBlank { "New space" }.replaceFirstChar { it.uppercase() }.take(40)
        }

        /**
         * A typed itinerary line: "Sat 2 pm Check in at Taj", "21 Nov 10:30 Dudhsagar falls". The day defaults to
         * [defaultDay] (the space's first day), the time to 9 am. Returns the moment and the words left over.
         */
        fun parseStop(text: String, now: Long, zone: ZoneId, defaultDay: LocalDate?): Pair<Long, String>? {
            val day = parseDay(text, now, zone) ?: defaultDay ?: TextKit.local(now, zone).toLocalDate()
            val minutes = timeOf(text) ?: 9 * 60
            val words = text
                .replace(Regex("(?i)\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"), " ")
                .replace(Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2}(?:st|nd|rd|th)?\\b"), " ")
                .replace(Regex("(?i)\\b(?:at\\s+)?\\d{1,2}(?:[:.]\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)?(?=\\s|$)"), " ")
                .replace(Regex("(?i)\\b(on\\s+)?(next\\s+)?(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(day|nesday|sday|urday)?\\b"), " ")
                .replace(Regex("(?i)\\b(today|tomorrow|tonight|noon)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim().trim(',', '-', ':').trim()
            if (words.isBlank()) return null
            return TextKit.at(day, minutes, zone) to words.replaceFirstChar { it.uppercase() }
        }

        /** The time of day in a phrase that may also hold a date: "20 Nov 2 pm" → 14:00, "Saturday" → null. */
        fun timeOf(phrase: String): Int? {
            val t = phrase.lowercase(Locale.ROOT)
                .replace(Regex("\\d{4}-\\d{2}-\\d{2}"), " ")
                .replace(Regex("\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"), " ")
                .replace(Regex("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2}(?:st|nd|rd|th)?\\b"), " ")
                .replace(Regex("\\bin \\w+ (day|week|month)s?"), " ")
            if (!Regex("\\d|\\bnoon\\b|\\b(am|pm)\\b|o'?clock").containsMatchIn(t)) return null
            return Anchors.minutes(t)
        }

        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        /** A day in plain words: "2026-11-20", "20 Nov", "Nov 20", "tomorrow", "next friday", "in 5 days", "next week". */
        fun parseDay(phrase: String, now: Long, zone: ZoneId): LocalDate? {
            val p = phrase.lowercase(Locale.ROOT).trim()
            if (p.isBlank()) return null
            val today = TextKit.local(now, zone).toLocalDate()
            Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(p)?.let { return runCatching { LocalDate.parse(it.value) }.getOrNull() }
            fun dm(d: Int, mon: String): LocalDate? {
                val m = MONTHS.indexOf(mon.take(3)) + 1
                if (m == 0 || d !in 1..31) return null
                val y = runCatching { LocalDate.of(today.year, m, d) }.getOrNull() ?: return null
                return if (y.isBefore(today.minusDays(1))) y.plusYears(1) else y
            }
            Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*").find(p)?.let { return dm(it.groupValues[1].toInt(), it.groupValues[2]) }
            Regex("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b").find(p)?.let { return dm(it.groupValues[2].toInt(), it.groupValues[1]) }
            if (Regex("\\btoday\\b").containsMatchIn(p)) return today
            if ("day after tomorrow" in p) return today.plusDays(2)
            if (Regex("\\btomorrow\\b").containsMatchIn(p)) return today.plusDays(1)
            Regex("\\bin (\\w+) (day|week|month)s?").find(p)?.let { m ->
                val n = TextKit.number(m.groupValues[1])?.toLong() ?: return@let
                return when (m.groupValues[2]) { "day" -> today.plusDays(n); "week" -> today.plusWeeks(n); else -> today.plusMonths(n) }
            }
            DayOfWeek.entries.firstOrNull { Regex("\\b(${it.name.lowercase(Locale.ROOT)}|${it.name.lowercase(Locale.ROOT).take(3)})\\b").containsMatchIn(p) }?.let { dow ->
                return today.with(TemporalAdjusters.next(dow))
            }
            if ("next weekend" in p || "this weekend" in p) return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            if ("next week" in p) return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            if ("next month" in p) return today.plusMonths(1).withDayOfMonth(1)
            return null
        }

        fun planPrompt(today: LocalDate): String = """
            You plan a personal "space": one page in the user's phone app for something they are doing. Pick the blocks the page needs.
            Today is ${today.format(DateTimeFormatter.ISO_LOCAL_DATE)} (${today.dayOfWeek.name.lowercase(Locale.ROOT)}).
            Reply with JSON only, no prose:
            {"kind": "TRIP|HEALTH|EVENT|MOVE|STUDY|ADMIN|GENERAL", "title": "short name, max 30 chars, like 'Goa trip'",
             "start": "YYYY-MM-DD or empty", "end": "YYYY-MM-DD or empty",
             "blocks": [{"type": "...", "title": "...", ...settings}]}
            Block types (use only these; 4 to 8 blocks; most useful first):
            - countdown: days left until start.
            - documents: tickets, bookings, reports, bills.
            - checklist: a list; settings: "seed": up to 8 short first entries specific to this goal. Up to 3 checklists.
            - itinerary: timed plan of stops and events.
            - expenses: costs; settings: "split_with": names of people sharing costs (not the user), "budget": number in rupees if said.
            - gallery: photos.
            - notes, journal: text.
            - people: who is involved; settings: "people": names.
            - readings: health readings; settings: "kinds": any of BP, SUGAR, WEIGHT, PULSE.
            - medicines: medicine schedule.
            - reminders: Cue's calls for this.
            Only use names, dates and amounts the user actually said. Seed entries must be concrete for this goal (a beach trip packs sunscreen; a knee surgery asks about walking aids).
        """.trimIndent()
    }
}
