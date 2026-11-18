package com.cue.agent

import com.cue.brain.BrainRouter
import com.cue.domain.Album
import com.cue.domain.AlbumKind
import com.cue.domain.BubbleCard
import com.cue.domain.BubbleItem
import com.cue.domain.BubbleSpec
import com.cue.domain.CallRequest
import com.cue.domain.CaregiverNotifier
import com.cue.domain.Clock
import com.cue.domain.CueDao
import com.cue.domain.Item
import com.cue.domain.ItemKind
import com.cue.domain.ItemType
import com.cue.domain.Person
import com.cue.domain.Place
import com.cue.domain.PlaceKind
import com.cue.domain.PendingItem
import com.cue.domain.Reminder
import com.cue.domain.ReminderKind
import com.cue.domain.ReminderStatus
import com.cue.domain.Settings
import com.cue.domain.Surface
import com.cue.domain.SurfacePresenter
import com.cue.domain.TriggerEvent
import com.cue.domain.TriggerKind
import com.cue.domain.TriggerScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Locale

/**
 * The agent core. Every trigger lands here as an event, gets a situation decision, and turns into a ring, a bubble,
 * a notification or silent filing. Plain code decides; brains only talk.
 */
class CueEngine(
    override val dao: CueDao,
    override val clock: Clock,
    override val settings: Settings,
    override val scheduler: TriggerScheduler,
    val surfaces: SurfacePresenter,
    val router: BrainRouter,
    private val caregiver: CaregiverNotifier,
    private val device: () -> DeviceState,
    private val logger: (String) -> Unit,
) : AgentEnv {
    override val planner = Planner(dao, clock, settings, scheduler)
    val assigner = AlbumAssigner(dao, clock)
    val briefings = Briefings(this)
    /** Photos, files and shared text from chat, calls and other apps: filed into the right space. */
    val inbox = Inbox(this) { text, path, source, hash, name, into -> ingestDocument(text, path, source, hash, name, into, quiet = true) }
    private val mutex = Mutex()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _log
    private val lastBubble = mutableMapOf<String, Long>()
    var activeCall: CallFlow? = null
        private set

    override fun log(line: String) {
        val stamped = "${TextKit.clock(clock.now(), clock.zone)}  $line"
        logger(stamped)
        _log.value = (listOf(stamped) + _log.value).take(200)
    }

    // ---------------------------------------------------------------- memory helpers

    override suspend fun activeAlbum(): Album? {
        val now = clock.now()
        val albums = dao.albums()
        return albums.filter { it.kind == AlbumKind.TRIP && (it.endAt ?: it.startAt ?: 0) > now - 86_400_000 }.minByOrNull { it.startAt ?: Long.MAX_VALUE }
    }

    override suspend fun streak(habitId: Long): Int {
        return HabitDays.streak(dao.habit(habitId), dao.habitLogs(habitId), TextKit.local(clock.now(), clock.zone).toLocalDate(), clock.zone)
    }

    override suspend fun person(name: String): Person {
        val clean = name.trim().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val norm = TextKit.normalize(clean)
        dao.personByName(norm)?.let { return it }
        val id = dao.insertPerson(Person(name = clean, normalized = norm))
        return dao.person(id)!!
    }

    override suspend fun search(query: String): List<Item> {
        val words = TextKit.normalize(query).split(" ").filter { it.length > 1 && it !in setOf("my", "the", "where", "is", "s", "what", "was", "that", "i", "saved", "find", "show", "me", "last", "week") }
        if (words.isEmpty()) return emptyList()
        val synonyms = mapOf("ac" to listOf("ac", "air conditioner"), "cafe" to listOf("cafe", "café", "restaurant", "coffee"), "pass" to listOf("boarding", "ticket"),
            "boarding" to listOf("boarding", "ticket"), "hotel" to listOf("hotel"), "medicine" to listOf("prescription", "tablet"))
        val hits = linkedMapOf<Long, Pair<Item, Int>>()
        for (w in words) {
            for (term in synonyms[w] ?: listOf(w)) {
                for (it in dao.searchItems("%$term%")) {
                    val prev = hits[it.id]
                    hits[it.id] = it to ((prev?.second ?: 0) + if (it.title.contains(term, true)) 3 else 1)
                }
            }
        }
        return hits.values.sortedWith(compareByDescending<Pair<Item, Int>> { it.second }.thenByDescending { it.first.createdAt }).map { it.first }
    }

    // ---------------------------------------------------------------- events

    private suspend fun record(kind: String, refId: Long?, dedupe: String, payload: String = "{}"): TriggerEvent? {
        val ev = TriggerEvent(kind = kind, refId = refId, payload = payload, at = clock.now(), dedupeKey = dedupe)
        val id = dao.insertEvent(ev)
        if (id <= 0) { log("dropped duplicate $kind $dedupe"); return null }
        return ev.copy(id = id)
    }

    private suspend fun decided(ev: TriggerEvent, decision: String, outcome: String) {
        dao.updateEvent(ev.copy(decision = decision, outcome = outcome))
        log("${ev.kind} -> $decision: $outcome")
    }

    /** An alarm fired. */
    suspend fun onReminderFired(reminderId: Long, attemptKey: String = "", force: Boolean = false) = mutex.withLock {
        val r = dao.reminder(reminderId) ?: return@withLock
        if (r.status == ReminderStatus.CANCELLED || r.status == ReminderStatus.DONE) return@withLock
        val ev = record(TriggerKind.REMINDER, r.id, "rem:${r.id}:${r.fireAt}:${r.attempts}$attemptKey") ?: return@withLock
        when (r.kind) {
            ReminderKind.PRELOAD -> {
                val b = router.forJob("converse")
                if (b.name != "rules" && !b.isReady()) runCatching { b.prepare() }
                dao.updateReminder(r.copy(status = ReminderStatus.DONE))
                decided(ev, "SILENT", "pre-loaded ${b.name}")
                planner.reconcile()
                return@withLock
            }
            ReminderKind.AIRPORT_BACKUP -> {
                dao.updateReminder(r.copy(status = ReminderStatus.DONE))
                val place = r.placeId?.let { dao.place(it) }
                if (place != null && lastBubble["place:${place.id}"]?.let { clock.now() - it < 3_600_000 } == true) {
                    decided(ev, "SILENT", "geofence already fired"); return@withLock
                }
                val spec = place?.let { bubbleForPlace(it) } ?: tripBubble(r.albumId, "At the airport")
                showBubble(ev, spec, "airport backup")
                return@withLock
            }
        }
        if (r.surface == Surface.NOTIFY) {
            surfaces.notify(r.text, briefings.opening(r.kind, r.id), "rem:${r.id}")
            dao.updateReminder(r.copy(status = ReminderStatus.DONE))
            decided(ev, "NOTIFY", r.text)
            planner.reconcile()
            return@withLock
        }
        // The daily calls ring at the time the user chose, even inside Cue's quiet hours (the phone's Do Not Disturb still wins).
        val chosenTime = r.kind == ReminderKind.CHECKIN_MORNING || r.kind == ReminderKind.CHECKIN_NIGHT
        val d = SituationEngine.forRing(device().let {
            if (force) it.copy(inQuietHours = false, doNotDisturb = false) else if (chosenTime) it.copy(inQuietHours = false) else it
        }, if (force) 0 else r.attempts)
        when (d) {
            Decision.Ring, Decision.RingHeadsUp -> {
                dao.updateReminder(r.copy(status = ReminderStatus.FIRED))
                surfaces.ring(callRequest(r))
                decided(ev, SituationEngine.describe(d), r.text)
            }
            is Decision.Defer -> {
                val again = r.copy(fireAt = clock.now() + d.minutes * 60_000L, status = ReminderStatus.SCHEDULED)
                dao.updateReminder(again); scheduler.schedule(again)
                decided(ev, SituationEngine.describe(d), "user busy")
            }
            is Decision.Notify -> {
                surfaces.notify(r.text, briefings.opening(r.kind, r.id), "rem:${r.id}")
                dao.updateReminder(r.copy(status = ReminderStatus.MISSED))
                decided(ev, SituationEngine.describe(d), "quiet or declined twice")
                escalateIfCare(r)
                planner.reconcile()
            }
            else -> decided(ev, SituationEngine.describe(d), "")
        }
    }

    private suspend fun callRequest(r: Reminder): CallRequest {
        val f = briefings.facts()
        val (title, chips) = when (r.kind) {
            ReminderKind.CHECKIN_MORNING -> "Morning check-in" to listOfNotNull(f.flight?.let { "Flight " + it.substringAfter(" is at ").substringBefore(" today").substringBefore(" tomorrow") },
                f.open.takeIf { it.isNotEmpty() }?.let { "${it.size} left to pack" }, f.dosesToday.takeIf { it > 0 }?.let { "$it doses today" })
            ReminderKind.CHECKIN_NIGHT -> "Night check-in" to listOfNotNull(f.open.takeIf { it.isNotEmpty() }?.let { "${it.size} on your list" }, "Today's journal")
            ReminderKind.LEAVE_BY -> "Time to leave" to listOfNotNull("Flight " + r.text.substringAfter("your ").substringBefore(" flight"), f.open.takeIf { it.isNotEmpty() }?.let { "${it.size} unticked" })
            ReminderKind.DOSE -> (if (r.attempts > 0) "Missed dose" else "Medicine time") to listOf(r.text)
            ReminderKind.CARE -> "Medicine check" to listOf(r.text)
            ReminderKind.EXPIRY -> "Renewal coming up" to listOf(r.text)
            ReminderKind.ACCOUNTABILITY -> "Quick check-in" to listOf(r.text)
            ReminderKind.PRACTICE -> "English practice" to listOf("5 minutes")
            else -> "Reminder" to listOf(r.text)
        }
        return CallRequest(r.id, r.kind, title, "About a minute", chips)
    }

    /** The phone rang and nobody answered, or the user declined. */
    suspend fun onCallMissed(reminderId: Long, declined: Boolean) = mutex.withLock {
        val r = dao.reminder(reminderId) ?: return@withLock
        val attempts = r.attempts + 1
        if (attempts < 2) {
            val retry = r.copy(attempts = attempts, fireAt = clock.now() + (if (r.kind == ReminderKind.DOSE || r.kind == ReminderKind.CARE) 15 else 10) * 60_000L,
                status = ReminderStatus.SCHEDULED)
            dao.updateReminder(retry)
            scheduler.schedule(retry)
            log("${if (declined) "declined" else "missed"} ${r.kind}; retry at ${TextKit.clock(retry.fireAt, clock.zone)}")
        } else {
            dao.updateReminder(r.copy(attempts = attempts, status = ReminderStatus.MISSED))
            surfaces.notify(r.text, briefings.opening(r.kind, r.id), "rem:${r.id}")
            log("${r.kind} missed twice; notification instead")
            escalateIfCare(r)
            planner.reconcile()
        }
    }

    /** "Ring me in 10 minutes" from the incoming-call screen. Does not count as a miss. */
    suspend fun snooze(reminderId: Long, minutes: Int) = mutex.withLock {
        val r = dao.reminder(reminderId) ?: return@withLock
        val again = r.copy(fireAt = clock.now() + minutes * 60_000L, status = ReminderStatus.SCHEDULED)
        dao.updateReminder(again)
        scheduler.schedule(again)
        log("snoozed ${r.kind} to ${TextKit.clock(again.fireAt, clock.zone)}")
    }

    private suspend fun escalateIfCare(r: Reminder) {
        if (r.kind != ReminderKind.DOSE && r.kind != ReminderKind.CARE) return
        if (settings.caregiverNumber.isBlank()) { log("no caregiver number set; nudge skipped"); return }
        val who = settings.userName.ifBlank { "Your family member" }
        val ok = caregiver.nudge("Cue: $who didn't answer two medicine calls (${r.text}). You may want to check in.")
        log(if (ok) "caregiver ${settings.caregiverName} nudged" else "caregiver nudge failed")
    }

    /** Arrived at a known place. */
    suspend fun onGeofence(placeId: Long) = mutex.withLock {
        val place = dao.place(placeId) ?: return@withLock
        val ev = record(TriggerKind.GEOFENCE_ENTER, placeId, "geo:$placeId:${clock.now() / 3_600_000}") ?: return@withLock
        val spec = withRules(bubbleForPlace(place), StandingRules(this).fire(com.cue.domain.RuleTrigger.ARRIVE, place.label, place.kind), place.label, "You're at ${place.label}")
        showBubble(ev, spec, place.label)
        if (place.kind == PlaceKind.AIRPORT) {
            dao.scheduledReminders().filter { it.kind == ReminderKind.AIRPORT_BACKUP }.forEach {
                dao.updateReminder(it.copy(status = ReminderStatus.DONE)); scheduler.cancel(it.id)
            }
        }
    }

    /** Left a saved place: only "when I leave" rules act on it. */
    suspend fun onGeofenceExit(placeId: Long) = mutex.withLock {
        val place = dao.place(placeId) ?: return@withLock
        val rules = StandingRules(this).fire(com.cue.domain.RuleTrigger.LEAVE, place.label, place.kind)
        if (rules.isEmpty()) return@withLock
        val ev = record(TriggerKind.GEOFENCE_ENTER, placeId, "geo-exit:$placeId:${clock.now() / 3_600_000}", JSONObject().put("exit", true).toString()) ?: return@withLock
        showBubble(ev, withRules(null, rules, place.label, "Leaving ${place.label}"), "leave:${place.label}")
    }

    /** Adds what standing rules asked to be said here to a bubble, making one if there was nothing else to show. */
    private fun withRules(spec: BubbleSpec?, rules: List<com.cue.domain.StandingRule>, label: String, headline: String): BubbleSpec? {
        if (rules.isEmpty()) return spec
        val items = rules.map { BubbleItem(it.id, it.text, "rule") }
        return spec?.copy(items = items + spec.items) ?: BubbleSpec(label, headline, null, items)
    }

    private suspend fun showBubble(ev: TriggerEvent, spec: BubbleSpec?, label: String) {
        val key = spec?.let { "place:${it.placeLabel}" } ?: label
        val recently = lastBubble[key]?.let { clock.now() - it < 3_600_000 } == true
        val d = SituationEngine.forBubble(device(), spec != null && (spec.card != null || spec.items.isNotEmpty()), recently)
        when (d) {
            Decision.Bubble -> { surfaces.bubble(spec!!); lastBubble[key] = clock.now() }
            Decision.BubbleOnUnlock -> { surfaces.notify(spec!!.headline, spec.items.joinToString { it.text }, "bubble"); surfaces.bubble(spec); lastBubble[key] = clock.now() }
            is Decision.Notify -> spec?.let { surfaces.notify(it.headline, it.items.joinToString { i -> i.text }, "bubble") }
            else -> Unit
        }
        decided(ev, SituationEngine.describe(d), spec?.headline ?: "nothing relevant at $label")
    }

    suspend fun bubbleForPlace(place: Place): BubbleSpec? {
        val now = clock.now()
        return when (place.kind) {
            PlaceKind.AIRPORT -> {
                val ticket = dao.itemsOfKind(ItemKind.TICKET).filter { (it.eventStart ?: 0) > now - 6 * 3_600_000 }.minByOrNull { it.eventStart!! }
                tripBubble(ticket?.albumId, "You made it. Here's your pass.", place.label)
            }
            PlaceKind.CLINIC -> {
                val album = dao.albums().firstOrNull { it.kind == AlbumKind.HEALTH && it.placeId == place.id } ?: dao.albums().firstOrNull { it.kind == AlbumKind.HEALTH }
                val docs = album?.let { dao.itemsInAlbum(it.id) }.orEmpty().filter { it.kind == ItemKind.LAB_REPORT || it.kind == ItemKind.PRESCRIPTION || it.kind == ItemKind.INSURANCE }
                    .sortedByDescending { it.createdAt }.take(4)
                if (docs.isEmpty()) null else BubbleSpec(place.label, "Your latest reports for ${album?.title?.removeSuffix(" visits") ?: "the doctor"}", null,
                    docs.map { BubbleItem(it.id, it.title, it.kind) }, album?.id)
            }
            else -> {
                val items = mutableListOf<BubbleItem>()
                dao.scheduledReminders().filter { it.placeId == place.id }.forEach { items += BubbleItem(it.id, it.text, "reminder") }
                dao.itemsOfType(ItemType.NOTE).filter { n -> place.label.split(" ").any { w -> w.length > 3 && n.body.contains(w, true) } || n.body.contains(place.kind.lowercase(Locale.ROOT), true) }
                    .take(3).forEach { items += BubbleItem(it.id, it.body, "note") }
                if (place.kind == PlaceKind.HOME) {
                    val billReminders = mutableSetOf<Long>()
                    for (bi in items) if (bi.kind == "reminder") { val iid = dao.reminder(bi.id)?.itemId; if (iid != null && dao.item(iid)?.kind == ItemKind.BILL) billReminders += bi.id }
                    items.removeAll { it.kind == "reminder" && it.id in billReminders }
                    dao.itemsOfKind(ItemKind.BILL).filter { (it.eventStart ?: 0) > now }.forEach { items += BubbleItem(it.id, "Pay the ${it.title.lowercase(Locale.ROOT)}, due ${TextKit.dateLabel(it.eventStart!!, clock.zone)}", "bill") }
                }
                if (place.kind == PlaceKind.GYM) {
                    dao.habits().firstOrNull { it.name.equals("Gym", true) }?.let { h ->
                        items += BubbleItem(h.id, "Gym check-in · ${streak(h.id)}-day streak", "habit")
                    }
                }
                if (items.isEmpty()) null else BubbleSpec(place.label, when (place.kind) {
                    PlaceKind.HOME -> "Welcome home. You said you'd do these"
                    PlaceKind.OFFICE -> "At the office. On your list"
                    PlaceKind.GYM -> "At the gym"
                    else -> "You're at ${place.label}"
                }, null, items.distinctBy { it.text }.take(4))
            }
        }
    }

    private suspend fun tripBubble(albumId: Long?, headline: String, label: String = "Airport"): BubbleSpec? {
        val now = clock.now()
        val ticket = (albumId?.let { dao.itemsInAlbum(it) } ?: dao.itemsOfKind(ItemKind.TICKET)).firstOrNull { it.kind == ItemKind.TICKET } ?: return null
        val f = JSONObject(ticket.fields)
        val list = dao.itemsOfType(ItemType.CHECKLIST).firstOrNull { it.albumId == ticket.albumId }
        val open = list?.let { dao.entries(it.id).filter { e -> !e.checked } }.orEmpty()
        return BubbleSpec(label, headline,
            BubbleCard(f.optString("from", "—"), f.optString("to", "—"), ticket.eventStart?.let { TextKit.clock(it, clock.zone) } ?: "",
                f.optString("gate").ifBlank { null }, f.optString("seat").ifBlank { null }),
            open.map { BubbleItem(it.id, it.text, "entry") }, ticket.albumId).also { if (now < 0) log("") }
    }

    /** A message notification arrived (people memory). */
    suspend fun onNotification(app: String, sender: String, text: String) = mutex.withLock {
        if (sender.isBlank()) return@withLock
        val ev = record(TriggerKind.NOTIFICATION, null, "notif:${sender.lowercase(Locale.ROOT)}:${text.hashCode()}", JSONObject().put("app", app).put("from", sender).toString())
            ?: return@withLock
        val p = person(sender)
        PeopleParser.pending(text)?.let { pending ->
            if (dao.openPending(p.id).none { TextKit.similarity(it.text, pending) > 0.7 }) {
                dao.insertPending(PendingItem(personId = p.id, text = pending, sourceApp = app, createdAt = clock.now()))
                log("people memory: ${p.name} -> $pending")
            }
        }
        val open = dao.openPending(p.id)
        val rules = StandingRules(this).fire(com.cue.domain.RuleTrigger.MESSAGE, p.name, sender)
        val items = rules.map { BubbleItem(it.id, it.text, "rule") } + open.take(4).map { BubbleItem(it.id, it.text, "pending") }
        val spec = if (items.isEmpty()) null else BubbleSpec(app.substringAfterLast('.').replaceFirstChar { it.uppercase() },
            if (open.isEmpty()) "${p.name} messaged. You asked me to remind you" else "${p.name} messaged. Still open with ${p.name}", null, items.take(4), personId = p.id)
        showBubble(ev, spec, "person:${p.id}")
    }

    /** Phase one of ingest: read fields with plain code, file into an album, plan triggers. Returns the item. */
    suspend fun ingestDocument(text: String, mediaPath: String?, source: String, hash: String?, fileName: String = "", intoAlbum: Long? = null, quiet: Boolean = false): Item? = mutex.withLock {
        if (hash != null) dao.itemByHash(hash)?.let { log("duplicate $source ignored"); return@withLock null }
        val now = clock.now()
        val ex = FastFields.extract(text, now, clock.zone, fileName)
        if (ex.kind == ItemKind.TICKET) {
            // The same booking saved twice (a screenshot and a PDF, say) is one ticket, not two trips.
            val same = dao.itemsOfKind(ItemKind.TICKET).firstOrNull { t ->
                val f = JSONObject(t.fields)
                (ex.fields["pnr"] != null && f.optString("pnr") == ex.fields["pnr"]) ||
                    (ex.fields["flight"] != null && f.optString("flight") == ex.fields["flight"] && t.eventStart != null && ex.eventStart != null &&
                        TextKit.dayKey(t.eventStart!!, clock.zone) == TextKit.dayKey(ex.eventStart, clock.zone))
            }
            if (same != null) {
                record(if (source == "share") TriggerKind.SHARE else TriggerKind.SCREENSHOT, same.id, "doc:${hash ?: now}")?.let { decided(it, "SILENT", "already have this ticket (${ex.fields["pnr"] ?: ex.fields["flight"]}); kept the existing one") }
                if (source == "share" && !quiet) surfaces.toast("Already saved in ${same.albumId?.let { dao.album(it)?.title } ?: "your trip"}")
                return@withLock same
            }
        }
        var item = Item(type = ItemType.GALLERY, kind = ex.kind, title = ex.title, body = text.take(4000), fields = JSONObject(ex.fields as Map<*, *>).toString(),
            eventStart = ex.eventStart, expiresAt = ex.expiresAt, mediaPath = mediaPath, source = source, contentHash = hash, personLabel = ex.personLabel, createdAt = now)
        val albumId = intoAlbum?.takeIf { dao.album(it) != null } ?: assigner.assign(item, ex)
        item = item.copy(albumId = albumId)
        val id = dao.insertItem(item)
        item = item.copy(id = id)
        if (albumId != null) {
            val album = dao.album(albumId)
            if (album != null && album.kind == AlbumKind.HEALTH && album.placeId == null) {
                dao.placeOfKind(PlaceKind.CLINIC)?.let { dao.updateAlbum(album.copy(placeId = it.id)) }
            }
        }
        val summary = planner.reconcile()
        record(if (source == "share") TriggerKind.SHARE else TriggerKind.SCREENSHOT, id, "doc:${hash ?: id}")?.let {
            decided(it, "SILENT", "filed ${ex.kind} '${ex.title}'" + (albumId?.let { a -> " to ${dao.album(a)?.title}" } ?: "") + "; $summary")
        }
        if (source == "share" && !quiet) surfaces.toast("Saved" + (albumId?.let { " to ${dao.album(it)?.title}" } ?: ""))
        item
    }

    // ---------------------------------------------------------------- calls

    /** The user accepted a ring (or pressed Talk). */
    suspend fun startCall(kind: String, reminderId: Long?): CallFlow {
        val now = clock.now()
        val r = reminderId?.let { dao.reminder(it) }
        val callId = dao.insertCall(newCallSession(kind, reminderId, now))
        val flow = CallFlow(this, kind, reminderId, r?.albumId ?: activeAlbum()?.id, callId)
        activeCall = flow
        log("call started: $kind")
        return flow
    }

    internal fun afterCall(kind: String) {
        activeCall = null
        log("call ended: $kind")
    }

    fun clearActive() { activeCall = null }

    // ---------------------------------------------------------------- setup and demo

    suspend fun ensureDefaults() {
        if (dao.places().none { it.kind == PlaceKind.HOME }) {
            dao.upsertPlace(Place(label = "Home", kind = PlaceKind.HOME, lat = 12.9716, lng = 77.6412, radiusM = 120f))
            dao.upsertPlace(Place(label = "Office", kind = PlaceKind.OFFICE, lat = 12.9352, lng = 77.6245, radiusM = 150f))
            dao.upsertPlace(Place(label = "Gym", kind = PlaceKind.GYM, lat = 12.9784, lng = 77.6408, radiusM = 100f))
            dao.upsertPlace(Place(label = "Rao Clinic", kind = PlaceKind.CLINIC, lat = 12.9698, lng = 77.6500, radiusM = 100f))
        }
        planner.reconcile()
    }
}
