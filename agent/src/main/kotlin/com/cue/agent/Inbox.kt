package com.cue.agent

import com.cue.domain.Album
import com.cue.domain.AlbumKind
import com.cue.domain.BlockType
import com.cue.domain.Item
import com.cue.domain.ItemKind
import java.util.Locale

/**
 * One way in for everything the user hands Cue: a photo or file from chat, a call, or another app's share sheet.
 * Cue reads it, decides which space it belongs to, files it there, and turns a bill into an expense when the space
 * tracks money. When Cue is sure it just does it (with Undo); when it isn't, it saves the file and asks where.
 */
class Inbox(
    private val env: AgentEnv,
    /** Saves the item: the engine's ingest (fields, duplicates, tickets). Null [intoAlbum] lets the engine's own rules pick. */
    private val ingest: suspend (text: String, path: String?, source: String, hash: String?, fileName: String, intoAlbum: Long?) -> Item?,
) {
    private val dao get() = env.dao

    /** What happened to one incoming thing, and what the user can do next. */
    data class Filed(
        val item: Item?,
        val album: Album?,
        /** Cue picked the space with confidence; otherwise [options] are the places to ask about. */
        val sure: Boolean,
        val options: List<Album>,
        val expenseId: Long? = null,
        val amount: Long? = null,
        val duplicate: Boolean = false,
    ) {
        val label: String get() = when {
            item == null -> "that"
            item.kind == ItemKind.PHOTO -> "the photo"
            // "the electricity bill", but "the Karim's bill": only common words lose their capital.
            item.title.substringBefore(' ') in COMMON -> "the " + item.title.replaceFirstChar { it.lowercase() }
            else -> "the " + item.title.removePrefix("The ")
        }

        /** "Saved the Taj bill to Goa trip and added ₹4,200 to its expenses." */
        fun message(): String = when {
            item == null -> "I couldn't read that file."
            duplicate -> "You already gave me ${label}" + (album?.let { " — it's in ${it.title}." } ?: ".")
            album == null -> "Saved ${label}. Where should it go?"
            !sure -> "Saved ${label} to ${album.title}. Is that right?"
            else -> "Saved ${label} to ${album.title}" + (amount?.let { " and added ₹${Spaces.money(it)} to its expenses" } ?: "") + "."
        }
    }

    /**
     * File one thing. [hint] is what the user typed or said with it ("Goa trip", "Riya paid for this"),
     * [contextAlbum] the space the conversation is about. [photo] marks a picture rather than a document.
     */
    suspend fun file(text: String, path: String?, source: String, hash: String?, fileName: String, hint: String = "", contextAlbum: Long? = null, photo: Boolean = false): Filed {
        val now = env.clock.now()
        hash?.let { h -> dao.itemByHash(h)?.let { old -> return Filed(old, old.albumId?.let { dao.album(it) }, true, emptyList(), duplicate = true) } }
        val ex = FastFields.extract(text, now, env.clock.zone, fileName)
        val albums = dao.albums()
        val blocks = albums.associate { a -> a.id to dao.blocks(a.id) }
        val people = blocks.mapValues { (_, bs) -> bs.flatMap { Spaces.people(it) } }
        val money = blocks.filterValues { bs -> bs.any { it.type == BlockType.EXPENSES } }.keys
        val ranked = rank(albums, people, ex.kind, ex.fields, text, ex.eventStart, hint, contextAlbum, now, money, ex.title)
        val best = ranked.firstOrNull()
        val sure = best != null && isSure(ranked)
        var item = ingest(text, path, source, hash, fileName, if (sure) best!!.first.id else null) ?: return Filed(null, null, false, emptyList())
        if (item.createdAt < now) return Filed(item, item.albumId?.let { dao.album(it) }, true, emptyList(), duplicate = true)
        // A picture with (almost) no words on it is a photo for the space's gallery, not a paper.
        if (photo && ex.kind in setOf(ItemKind.GENERIC, ItemKind.SCREENSHOT) && text.count { !it.isWhitespace() } < 40) {
            item = item.copy(kind = ItemKind.PHOTO, title = "Photo", body = "")
            dao.updateItem(item)
        }
        val album = item.albumId?.let { dao.album(it) }
        // The engine's own rules (a ticket makes a trip, a prescription a health space) are sure too.
        val ruleSure = !sure && album != null && ranked.none { it.first.id != album.id && it.second >= ASK_FLOOR }
        val confident = sure || ruleSure
        val options = ranked.map { it.first }.filter { it.id != album?.id }.take(3)
        val (expenseId, amount) = if (confident && album != null) expenseFor(item, album, hint) else null to null
        env.log("inbox: ${item.kind} '${item.title}' -> ${album?.title ?: "no space"} (${if (confident) "sure" else "asking"}; top ${ranked.take(3).joinToString { "${it.first.title}=${it.second}" }})")
        return Filed(item, album, confident, options, expenseId, amount)
    }

    /** Take it back: the item and the expense it made. */
    suspend fun undo(f: Filed) {
        f.expenseId?.let { dao.deleteRecord(it) }
        f.item?.let { if (!f.duplicate) dao.deleteItem(it.id) }
        env.planner.reconcile()
        env.log("inbox: undid ${f.item?.title}")
    }

    /** The user picked a different space (or answered "where should it go?"). */
    suspend fun move(f: Filed, albumId: Long, hint: String = ""): Filed {
        val item = f.item ?: return f
        val album = dao.album(albumId) ?: return f
        f.expenseId?.let { dao.deleteRecord(it) }
        val moved = item.copy(albumId = album.id)
        dao.updateItem(moved)
        env.planner.reconcile()
        val (expenseId, amount) = expenseFor(moved, album, hint)
        env.log("inbox: moved ${item.title} to ${album.title}")
        return Filed(moved, album, true, emptyList(), expenseId, amount)
    }

    /** A bill filed into a space that tracks money becomes an expense there. */
    private suspend fun expenseFor(item: Item, album: Album, hint: String): Pair<Long?, Long?> {
        if (item.kind != ItemKind.BILL) return null to null
        val amount = Regex("\"amount\"\\s*:\\s*\"([^\"]+)\"").find(item.fields)?.groupValues?.get(1)?.let { Spaces.parseAmount(it) } ?: return null to null
        val block = dao.blocks(album.id).firstOrNull { it.type == BlockType.EXPENSES } ?: return null to null
        val payer = Regex("(?i)\\b([A-Za-z]+) paid\\b").find(hint)?.groupValues?.get(1)?.takeIf { it.lowercase(Locale.ROOT) !in setOf("i", "we", "who", "was", "had") }.orEmpty()
        val id = Spaces(env).addExpense(block.id, amount, item.title.removeSuffix(" bill").ifBlank { "Bill" }, payer) ?: return null to null
        return id to amount
    }

    companion object {
        private val COMMON = setOf("Bill", "Electricity", "Hotel", "Prescription", "Lab", "Boarding", "Flight", "Insurance", "Vehicle", "Photo", "Screenshot",
            "Ticket", "Warranty", "Vehicle", "Water", "Gas", "Phone", "Laptop", "Fridge", "Washing", "Microwave", "Receipt", "Invoice", "Report")
        /** A space needs this score to be filed into without asking. */
        const val SURE = 60
        /** A rival this close makes Cue ask instead. */
        const val MARGIN = 25
        /** Spaces scoring at least this are offered as choices. */
        const val ASK_FLOOR = 30
        private const val DAY = 86_400_000L

        private val FILLER = setOf("add", "save", "put", "file", "keep", "this", "that", "these", "those", "the", "my", "our", "to", "in", "into", "for", "of", "a", "an",
            "space", "album", "page", "please", "it", "them", "photo", "photos", "pic", "pics", "picture", "bill", "receipt", "doc", "document", "and", "with", "paid", "is", "from")
        private val GENERIC_TITLE = setOf("trip", "visits", "visit", "space", "bills", "home", "papers", "health", "new", "the", "and", "with", "plan", "list")

        fun isSure(ranked: List<Pair<Album, Int>>): Boolean {
            val best = ranked.firstOrNull() ?: return false
            val second = ranked.getOrNull(1)?.second ?: 0
            return best.second >= SURE && best.second - second >= MARGIN
        }

        private fun words(s: String) = s.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }

        /**
         * Every space scored for this item, best first, zeros dropped. Plain rules: what the user said beats what the
         * conversation is about, which beats dates, places and people in the document, which beat its kind.
         */
        fun rank(
            albums: List<Album>, people: Map<Long, List<String>>, kind: String, fields: Map<String, String>, text: String, eventStart: Long?,
            hint: String, contextAlbum: Long?, now: Long, tracksMoney: Set<Long> = emptySet(), title: String = "",
        ): List<Pair<Album, Int>> {
            val utility = Regex("(?i)electricity|water|gas|broadband|internet|rent|maintenance|phone bill").containsMatchIn(title)
            val hintWords = words(hint).filter { it !in FILLER }
            val textLower = text.lowercase(Locale.ROOT)
            val city = (fields["city"] ?: fields["to"])?.lowercase(Locale.ROOT)
            return albums.map { a ->
                var score = 0
                val titleWords = words(a.title).filter { it !in GENERIC_TITLE }
                // What the user said: "Goa", "for the wedding", "dad's surgery".
                if (hintWords.isNotEmpty() && titleWords.any { t -> hintWords.any { h -> h == t || (h.length >= 4 && t.startsWith(h)) || (t.length >= 4 && h.startsWith(t)) } }) score += 100
                if (a.kind == AlbumKind.TRIP && Regex("\\btrip\\b").containsMatchIn(hint.lowercase(Locale.ROOT))) score += 15
                // The space the conversation is about beats one that merely happens to be on today.
                if (a.id == contextAlbum) score += 75
                // Dates: happening now, or the document's own date falls in the space's days.
                val start = a.startAt; val end = a.endAt ?: a.startAt
                if (start != null && end != null) {
                    if (now in (start - DAY)..(end + DAY)) {
                        score += 40
                        // A receipt while a space that tracks money is happening is almost always one of its costs.
                        if (kind == ItemKind.BILL && !utility && a.id in tracksMoney) score += 25
                    }
                    if (eventStart != null && eventStart in (start - 2 * DAY)..(end + DAY)) score += 50
                    if (start > now && start - now < 14 * DAY) {
                        score += 10
                        // A booking or advance paid before it starts may well be one of its costs: worth asking about.
                        if (kind == ItemKind.BILL && !utility && a.id in tracksMoney) score += 20
                    }
                }
                // The document names the space's place or people.
                if (city != null && a.title.lowercase(Locale.ROOT).contains(city)) score += 40
                if (titleWords.any { it.length >= 4 && Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(textLower) }) score += 30
                if (people[a.id].orEmpty().any { p -> p.length >= 3 && Regex("\\b${Regex.escape(p.lowercase(Locale.ROOT))}\\b").containsMatchIn(textLower) }) score += 25
                // What kind of thing it is.
                score += when {
                    kind in setOf(ItemKind.TICKET, ItemKind.HOTEL) && a.kind == AlbumKind.TRIP -> 20
                    kind in setOf(ItemKind.PRESCRIPTION, ItemKind.LAB_REPORT) && a.kind == AlbumKind.HEALTH -> 30
                    kind in setOf(ItemKind.BILL, ItemKind.INSURANCE, ItemKind.VEHICLE) && a.kind == AlbumKind.ADMIN -> 15
                    else -> 0
                }
                a to score
            }.filter { it.second > 0 }.sortedByDescending { it.second }
        }

        /** "Add these to the Goa trip" is only a where-to-put-it hint; anything else is a question for the model too. */
        fun isOnlyFilingHint(text: String): Boolean {
            val t = text.trim().lowercase(Locale.ROOT)
            if (t.isBlank()) return true
            if (t.endsWith("?")) return false
            return t.split(Regex("\\s+")).size <= 8 && Regex("^(add|save|put|file|keep|move|for|to|in|into)\\b|\\b(paid)\\b|^[a-z' ]{1,30}$").containsMatchIn(t)
        }
    }
}
