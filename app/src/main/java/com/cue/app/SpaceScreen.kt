package com.cue.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cue.agent.BlockSpec
import com.cue.agent.MeasureWords
import com.cue.agent.PlanSpec
import com.cue.agent.SpaceSpec
import com.cue.agent.Spaces
import com.cue.agent.TextKit
import com.cue.agent.planLine
import com.cue.domain.*
import com.cue.ingest.Ingest
import com.cue.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val zone get() = java.time.ZoneId.systemDefault()

/** Each block type's colour and icon. */
fun blockLook(type: String): Pair<AppTint, ImageVector> = when (type) {
    BlockType.DOCUMENTS -> AppTints.papers to Icons.Rounded.Description
    BlockType.CHECKLIST -> AppTints.lists to Icons.Rounded.Checklist
    BlockType.ITINERARY -> AppTints.albums to Icons.Rounded.Route
    BlockType.EXPENSES -> AppTints.renewals to Icons.Rounded.Payments
    BlockType.GALLERY -> AppTints.focus to Icons.Rounded.PhotoLibrary
    BlockType.NOTES -> AppTints.habits to Icons.Rounded.StickyNote2
    BlockType.JOURNAL -> AppTints.journal to Icons.Rounded.Book
    BlockType.PEOPLE -> AppTints.people to Icons.Rounded.People
    BlockType.READINGS -> AppTints.reminders to Icons.Rounded.MonitorHeart
    BlockType.MEDICINES -> AppTints.reminders to Icons.Rounded.Medication
    BlockType.REMINDERS -> AppTints.reminders to Icons.Rounded.Alarm
    else -> AppTints.albums to Icons.Rounded.Event
}

fun kindLook(kind: String): Pair<AppTint, ImageVector> = when (kind) {
    AlbumKind.TRIP -> AppTints.albums to Icons.Rounded.Flight
    AlbumKind.HEALTH -> AppTints.reminders to Icons.Rounded.MedicalServices
    AlbumKind.EVENT -> AppTints.journal to Icons.Rounded.Celebration
    AlbumKind.MOVE -> AppTints.papers to Icons.Rounded.LocalShipping
    AlbumKind.STUDY -> AppTints.focus to Icons.Rounded.School
    AlbumKind.ADMIN -> AppTints.papers to Icons.Rounded.Home
    else -> AppTints.renewals to Icons.Rounded.Folder
}

// ------------------------------------------------------------------ the Space page

@Composable
fun SpaceScreen(a: MainActivity, nav: Nav, albumId: Long) {
    val engine = a.cue.engine
    val dao = engine.dao
    val scope = rememberCoroutineScope()
    val spaces = remember { Spaces(engine) }
    val album by dao.albumFlow(albumId).collectAsState(null)
    val blocks by dao.blocksFlow(albumId).collectAsState(null)
    val items by dao.itemsInAlbumFlow(albumId).collectAsState(emptyList())
    val records by dao.recordsFlow(albumId).collectAsState(emptyList())
    val entries by dao.allEntriesFlow().collectAsState(emptyList())
    var editing by remember { mutableStateOf(false) }
    val ed = rememberEditor()
    val c = androidx.compose.ui.platform.LocalContext.current
    val t = now()
    // Albums from before Spaces get their kind's blocks the first time they open.
    LaunchedEffect(album?.id, blocks) { val al = album; if (al != null && blocks != null && blocks!!.isEmpty()) spaces.ensureBlocks(al) }
    val al = album ?: return Page { item { BackHeader("Space", null, { nav.back() }) } }
    val (tint, _) = kindLook(al.kind)
    val bs = blocks.orEmpty()
    Page(glowA = tint.light.copy(alpha = 0.45f), glowB = Cue.glowBlush) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { BackHeader(al.title, spaceSubtitle(al, t), { nav.back() }, glow = tint.deep) }
                Txt(if (editing) "Done" else "Edit", Type.smallStrong, Cue.primaryDeep, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.8f))
                    .clickable(role = Role.Button) { editing = !editing }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        val summary = Spaces.summary(al, bs, records, entries, t, zone)
        if (summary.isNotBlank() && !editing) item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.85f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelHead(30.dp); Spacer(Modifier.width(10.dp))
                Txt(summary, Type.body, modifier = Modifier.weight(1f))
            }
        }
        if (editing) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Rename", color = Color.White.copy(alpha = 0.9f), height = 40.dp) {
                    ed.open(EditRequest("Rename space", listOf(EditField("Name", al.title))) { v -> if (v[0].isNotBlank()) scope.launch { dao.updateAlbum(al.copy(title = v[0].trim())) } })
                }
                SoftButton("Change date", color = Color.White.copy(alpha = 0.9f), height = 40.dp) {
                    pickDateTime(c, al.startAt ?: System.currentTimeMillis(), withTime = false) { at -> scope.launch { dao.updateAlbum(al.copy(startAt = at)); engine.planner.reconcile() } }
                }
                SoftButton("Delete space", color = Cue.coralTint, textColor = Cue.coralText, height = 40.dp) {
                    ed.open(EditRequest("Delete ${al.title}?", emptyList(), saveLabel = "Delete",
                        extra = { Txt("Its blocks go. Your papers, lists and notes stay in their apps.", Type.small, Cue.muted) }) {
                        scope.launch {
                            dao.blocks(al.id).forEach { dao.deleteRecords(it.id); dao.deleteBlock(it.id) }
                            dao.unlinkAlbumItems(al.id); dao.deleteAlbum(al.id); engine.planner.reconcile()
                            nav.back()
                        }
                    })
                }
            }
        }
        bs.forEachIndexed { i, b ->
            item(key = "block-${b.id}") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (editing) EditBar(b, first = i == 0, last = i == bs.lastIndex,
                        up = { scope.launch { spaces.moveBlock(b.id, -1) } }, down = { scope.launch { spaces.moveBlock(b.id, 1) } },
                        remove = { scope.launch { spaces.removeBlock(b.id) } })
                    Block(a, al, b, bs, items, records.filter { it.blockId == b.id }, entries, t, editing)
                }
            }
        }
        item { AddBlockRow(bs) { type -> scope.launch { spaces.addBlock(al.id, BlockSpec(type, Spaces.defaultTitle(type))) } } }
        item {
            Row(Modifier.fillMaxWidth().clip(CircleShape).background(Color.White.copy(alpha = 0.85f))
                .clickable { a.askSession.contextAlbum = al.id; nav.go("ask") }.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Send Cue photos or bills for ${al.title}", Type.bodyStrong, modifier = Modifier.weight(1f), maxLines = 2)
                PixelHead(40.dp)
            }
        }
    }
}

private fun spaceSubtitle(al: Album, t: Long): String {
    val s = al.startAt ?: return if (al.autoCreated) "Filed by Cue" else "Made by Cue"
    val d = TextKit.dateLabel(s, zone) + (al.endAt?.takeIf { TextKit.dayKey(it, zone) != TextKit.dayKey(s, zone) }?.let { " – " + TextKit.dateLabel(it, zone) } ?: "")
    return d
}

@Composable
private fun EditBar(b: SpaceBlock, first: Boolean, last: Boolean, up: () -> Unit, down: () -> Unit, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(b.title, Type.smallStrong, Cue.muted, modifier = Modifier.weight(1f))
        if (!first) SmallIcon(Icons.Rounded.KeyboardArrowUp, "Move up", up)
        if (!last) SmallIcon(Icons.Rounded.KeyboardArrowDown, "Move down", down)
        SmallIcon(Icons.Rounded.Close, "Remove ${b.title}", remove, Cue.coralText)
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, label: String, onClick: () -> Unit, color: Color = Cue.ink) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) { Icon(icon, label, tint = color, modifier = Modifier.size(18.dp)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddBlockRow(have: List<SpaceBlock>, onAdd: (String) -> Unit) {
    val options = BlockType.all.filter { it == BlockType.CHECKLIST || have.none { b -> b.type == it } }
    if (options.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Add to this space")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { type ->
                val (tint, icon) = blockLook(type)
                Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.85f)).clickable(role = Role.Button) { onAdd(type) }.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconChip(icon, tint, 26.dp, 14.dp); Spacer(Modifier.width(8.dp))
                    Txt(if (type == BlockType.CHECKLIST) "List" else Spaces.defaultTitle(type), Type.smallStrong)
                }
            }
        }
    }
}

/** A block's frame: icon chip, title, a short status on the right, then its content. */
@Composable
private fun BlockCard(type: String, title: String, status: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val (tint, icon) = blockLook(type)
    LiftCard(Modifier.fillMaxWidth(), tint = tint.deep, radius = 28.dp, padding = 14.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconChip(icon, tint, 32.dp, 16.dp); Spacer(Modifier.width(10.dp))
            Txt(title, Type.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1)
            if (status != null) Txt(status, Type.smallStrong, Cue.muted)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Block(a: MainActivity, al: Album, b: SpaceBlock, all: List<SpaceBlock>, items: List<Item>, records: List<BlockRecord>, entries: List<ChecklistEntry>, t: Long, editing: Boolean) {
    when (b.type) {
        BlockType.COUNTDOWN -> CountdownBlock(al, b, all, items, t)
        BlockType.DOCUMENTS -> DocumentsBlock(al, b, items, t)
        BlockType.CHECKLIST -> ChecklistBlock(a, al, b, all, items, entries)
        BlockType.ITINERARY -> ItineraryBlock(a, al, b, records, editing)
        BlockType.EXPENSES -> ExpensesBlock(a, b, records, editing)
        BlockType.GALLERY -> GalleryBlock(a, al, b, items)
        BlockType.NOTES -> NotesBlock(a, al, b, items)
        BlockType.JOURNAL -> JournalBlock(a, al, b, items)
        BlockType.PEOPLE -> PeopleBlock(a, b)
        BlockType.READINGS -> ReadingsBlock(a, b)
        BlockType.MEDICINES -> MedicinesBlock(a, b)
        BlockType.REMINDERS -> RemindersBlock(a, al, b, t)
        else -> {}
    }
}

// ------------------------------------------------------------------ blocks

@Composable
private fun CountdownBlock(al: Album, b: SpaceBlock, all: List<SpaceBlock>, items: List<Item>, t: Long) {
    items.firstOrNull { it.kind == ItemKind.TICKET }?.let { TripPass(it); return }
    val start = al.startAt
    val days = start?.let { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), TextKit.local(it, zone).toLocalDate()) }
    val people = all.firstOrNull { it.type == BlockType.EXPENSES || it.type == BlockType.PEOPLE }?.let { Spaces.people(it) }.orEmpty()
    val (tint, icon) = kindLook(al.kind)
    GradientCard(Brush.linearGradient(listOf(tint.light, tint.deep, tint.text)), tint.deep, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White) }
            if (people.isNotEmpty()) Pill("With " + TextKit.joinAnd(people), Color.White.copy(alpha = 0.2f), Color.White)
        }
        Spacer(Modifier.height(14.dp))
        when {
            days == null -> { Txt(b.title, Type.title, Color.White); Txt("Tell Cue the date and I'll count down", Type.small, Color.White.copy(alpha = 0.85f)) }
            days > 1 -> { Txt("$days days", Type.big, Color.White); Txt("until ${al.title}, ${TextKit.dateLabel(start, zone)}" + (al.endAt?.takeIf { TextKit.dayKey(it, zone) != TextKit.dayKey(start, zone) }?.let { " – " + TextKit.dateLabel(it, zone) } ?: ""), Type.small, Color.White.copy(alpha = 0.9f)) }
            days == 1L -> { Txt("Tomorrow", Type.big, Color.White); Txt(TextKit.dateLabel(start, zone), Type.small, Color.White.copy(alpha = 0.9f)) }
            days == 0L -> { Txt("Today", Type.big, Color.White); Txt(al.title, Type.small, Color.White.copy(alpha = 0.9f)) }
            else -> {
                val end = al.endAt
                val over = end != null && end < t
                Txt(if (over) "Done" else "Day ${-days + 1}", Type.big, Color.White)
                Txt(if (over) "${al.title} ended ${TextKit.dateLabel(end!!, zone)}" else al.title, Type.small, Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

@Composable
private fun DocumentsBlock(al: Album, b: SpaceBlock, items: List<Item>, t: Long) {
    val docs = items.filter { it.type == ItemType.GALLERY && it.kind != ItemKind.PHOTO && it.kind != ItemKind.TICKET }
    val tickets = items.filter { it.kind == ItemKind.TICKET }
    BlockCard(b.type, b.title, if (docs.size + tickets.size > 0) "${docs.size + tickets.size}" else null) {
        if (docs.isEmpty() && tickets.isEmpty()) Txt("Add tickets, bookings or bills. Cue reads them and keeps the details.", Type.small, Cue.muted)
        (tickets + docs).forEach { d -> MiniDoc(d, t) }
        Spacer(Modifier.height(8.dp))
        AddFilesRow(albumId = al.id)
    }
}

@Composable
private fun MiniDoc(d: Item, t: Long) {
    val f = ticketFields(d)
    val sub = listOfNotNull(f.optString("pnr").takeIf { it.isNotBlank() }?.let { "PNR $it" }, f.optString("amount").takeIf { it.isNotBlank() }?.let { "₹$it" },
        d.eventStart?.let { TextKit.dateLabel(it, zone) }, d.expiresAt?.let { "until ${TextKit.dateLabel(it, zone)}" }).joinToString(" · ").ifBlank { "saved ${TextKit.dayLabel(d.createdAt, t, zone)}" }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(AppTints.papers.deep)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Txt(d.title, Type.body, maxLines = 1); Txt(sub, Type.small, Cue.muted, maxLines = 1) }
    }
}

@Composable
private fun ChecklistBlock(a: MainActivity, al: Album, b: SpaceBlock, all: List<SpaceBlock>, items: List<Item>, entries: List<ChecklistEntry>) {
    val dao = a.cue.engine.dao
    val scope = rememberCoroutineScope()
    val bound = JSONObject(b.config).optLong("itemId", 0).takeIf { it > 0 }
    // A block without its own list shows the space's lists no other block claims (lists Cue filed before Spaces).
    val claimed = all.mapNotNull { x -> JSONObject(x.config).optLong("itemId", 0).takeIf { it > 0 } }.toSet()
    val lists = if (bound != null) items.filter { it.id == bound } else items.filter { it.type == ItemType.CHECKLIST && it.id !in claimed }
    val es = entries.filter { e -> lists.any { it.id == e.itemId } }
    BlockCard(b.type, b.title, if (es.isNotEmpty()) "${es.count { it.checked }} of ${es.size}" else null) {
        es.sortedWith(compareBy<ChecklistEntry> { it.checked }.thenBy { it.position }).forEach { e -> CheckRow(e.text, e.checked, onToggle = { scope.launch { dao.updateEntry(e.copy(checked = !e.checked)) } }) }
        if (es.isEmpty()) Txt("Nothing on it yet.", Type.small, Cue.muted)
        Spacer(Modifier.height(8.dp))
        InlineAdd("Add to ${b.title.lowercase(Locale.ROOT)}") { text ->
            scope.launch {
                val id = bound ?: lists.firstOrNull()?.id ?: dao.insertItem(Item(type = ItemType.CHECKLIST, title = b.title, albumId = al.id, source = "space", createdAt = System.currentTimeMillis()))
                    .also { newId -> if (bound == null) dao.updateBlock(b.copy(config = JSONObject(b.config).put("itemId", newId).toString())) }
                val start = (dao.entries(id).maxOfOrNull { it.position } ?: 0) + 1
                TextKit.splitList(text).forEachIndexed { i, s -> dao.insertEntry(ChecklistEntry(itemId = id, text = s, position = start + i)) }
            }
        }
    }
}

@Composable
private fun ItineraryBlock(a: MainActivity, al: Album, b: SpaceBlock, records: List<BlockRecord>, editing: Boolean) {
    val engine = a.cue.engine
    val scope = rememberCoroutineScope()
    val c = LocalContext.current
    val stops = Spaces.stops(records)
    BlockCard(b.type, b.title, if (stops.isNotEmpty()) "${stops.size} stops" else null) {
        if (stops.isEmpty()) Txt("Add plans with a day and time, like \"Sat 2 pm check in at the hotel\".", Type.small, Cue.muted)
        stops.groupBy { TextKit.dayKey(it.at, zone) }.forEach { (_, day) ->
            Txt(TextKit.local(day.first().at, zone).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)), Type.smallStrong, AppTints.albums.text, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            day.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
                    Txt(TextKit.clock(s.at, zone), Type.smallStrong, Cue.muted, modifier = Modifier.width(64.dp).padding(top = 2.dp))
                    Column(Modifier.width(14.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(AppTints.albums.deep))
                        if (i < day.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(AppTints.albums.light.copy(alpha = 0.6f)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(bottom = 10.dp)) {
                        Txt(s.text, Type.body)
                        if (s.place.isNotBlank()) Txt(s.place, Type.small, Cue.muted)
                    }
                    if (editing) SmallIcon(Icons.Rounded.Close, "Remove ${s.text}", { scope.launch { engine.dao.deleteRecord(s.id) } }, Cue.coralText)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        InlineAdd("Sat 2 pm check in at the hotel") { text ->
            scope.launch {
                val stop = Spaces.parseStop(text, System.currentTimeMillis(), zone, al.startAt?.let { TextKit.local(it, zone).toLocalDate() })
                if (stop == null) Toast.makeText(c, "Add what it is, like \"2 pm lunch at Fisherman's Wharf\"", Toast.LENGTH_SHORT).show()
                else Spaces(engine).addStop(b.id, stop.first, stop.second)
            }
        }
    }
}

@Composable
private fun ExpensesBlock(a: MainActivity, b: SpaceBlock, records: List<BlockRecord>, editing: Boolean) {
    val engine = a.cue.engine
    val scope = rememberCoroutineScope()
    val c = LocalContext.current
    val ex = Spaces.expenses(records).sortedByDescending { it.at }
    val total = ex.sumOf { it.amount }
    val budget = Spaces.budget(b)
    val people = Spaces.people(b)
    BlockCard(b.type, b.title, if (people.isNotEmpty()) "Split with ${TextKit.joinAnd(people)}" else null) {
        Row(verticalAlignment = Alignment.Bottom) {
            Txt("₹" + Spaces.money(total), Type.big.copy(fontSize = sp(34f)))
            Spacer(Modifier.width(8.dp))
            Txt(if (budget != null) "of ₹${Spaces.money(budget)}" else "spent", Type.small, Cue.muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (budget != null) {
            val frac = (total.toFloat() / budget).coerceIn(0f, 1f)
            val over = total > budget
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(8.dp).clip(CircleShape).background(Cue.line)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(if (over) Cue.coralButton else Brush.horizontalGradient(listOf(AppTints.renewals.light, AppTints.renewals.deep))))
            }
            Txt(if (over) "₹${Spaces.money(total - budget)} over budget" else "₹${Spaces.money(budget - total)} left", Type.smallStrong, if (over) Cue.coralText else AppTints.renewals.text)
        }
        Spaces.settle(ex, people).forEach { line -> Txt(line, Type.smallStrong, AppTints.renewals.text, modifier = Modifier.padding(top = 4.dp)) }
        if (ex.isNotEmpty()) Spacer(Modifier.height(6.dp))
        ex.take(if (editing) 50 else 5).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(e.what, Type.body, maxLines = 1)
                    Txt((if (e.paidBy == "Me") "You paid" else "${e.paidBy} paid") + " · " + TextKit.dateLabel(e.at, zone), Type.small, Cue.muted)
                }
                Txt("₹" + Spaces.money(e.amount), Type.bodyStrong)
                if (editing) { Spacer(Modifier.width(6.dp)); SmallIcon(Icons.Rounded.Close, "Remove ${e.what}", { scope.launch { engine.dao.deleteRecord(e.id) } }, Cue.coralText) }
            }
        }
        if (ex.size > 5 && !editing) Txt("and ${ex.size - 5} more", Type.small, Cue.muted)
        Spacer(Modifier.height(8.dp))
        InlineAdd(if (people.isNotEmpty()) "1200 dinner, ${people.first()} paid" else "1200 dinner") { text ->
            val p = Spaces.parseExpense(text)
            if (p == null) Toast.makeText(c, "Start with the amount, like \"450 cab\"", Toast.LENGTH_SHORT).show()
            else scope.launch { Spaces(engine).addExpense(b.id, p.first, p.second, p.third) }
        }
        if (people.isEmpty() || editing) {
            Spacer(Modifier.height(8.dp))
            InlineAdd("Split with (names)") { names ->
                scope.launch { engine.dao.updateBlock(b.copy(config = JSONObject(b.config).put("people", JSONArray((people + Spaces.people(names)).distinct())).toString())) }
            }
            InlineAdd("Budget, like 20000") { v -> Spaces.parseAmount(v)?.let { n -> scope.launch { engine.dao.updateBlock(b.copy(config = JSONObject(b.config).put("budget", n).toString())) } } }
        }
    }
}

@Composable
private fun GalleryBlock(a: MainActivity, al: Album, b: SpaceBlock, items: List<Item>) {
    val c = LocalContext.current
    val dao = a.cue.engine.dao
    val scope = rememberCoroutineScope()
    val photos = items.filter { it.mediaPath != null && (it.kind == ItemKind.PHOTO || it.mediaPath!!.lowercase(Locale.ROOT).let { p -> p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp") }) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                uris.mapNotNull { u -> Ingest.copy(c, u) }.onEach { (path, name) ->
                    dao.insertItem(Item(type = ItemType.GALLERY, kind = ItemKind.PHOTO, title = name.substringBeforeLast('.').ifBlank { "Photo" }, albumId = al.id, mediaPath = path, source = "user", createdAt = System.currentTimeMillis()))
                }.size
            }
            Toast.makeText(c, if (n == 0) "Couldn't open those photos" else "Added ${if (n == 1) "a photo" else "$n photos"}", Toast.LENGTH_SHORT).show()
        }
    }
    BlockCard(b.type, b.title, if (photos.isNotEmpty()) "${photos.size}" else null) {
        if (photos.isEmpty()) Txt("Add photos from this, or I'll ask for the best ones on the night call.", Type.small, Cue.muted)
        photos.takeLast(9).reversed().chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { p -> Thumb(p.mediaPath!!, Modifier.weight(1f).aspectRatio(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(4.dp))
        SoftButton("Add photos", Modifier.fillMaxWidth(), color = AppTints.focus.light.copy(alpha = 0.35f)) { pick.launch("image/*") }
    }
}

@Composable
private fun Thumb(path: String, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 240 && o.outHeight / (s * 2) >= 240) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(AppTints.focus.light.copy(alpha = 0.3f))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun NotesBlock(a: MainActivity, al: Album, b: SpaceBlock, items: List<Item>) {
    val dao = a.cue.engine.dao
    val scope = rememberCoroutineScope()
    val notes = items.filter { it.type == ItemType.NOTE }.sortedByDescending { it.createdAt }
    BlockCard(b.type, b.title, if (notes.isNotEmpty()) "${notes.size}" else null) {
        if (notes.isEmpty()) Txt("Hotel address, the doctor's advice, a Wi-Fi password: anything to keep with this.", Type.small, Cue.muted)
        val ed = rememberEditor()
        notes.take(6).forEach { n ->
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Cue.marigoldCard).clickable {
                ed.open(EditRequest("Edit note", listOf(EditField("Note", n.body.ifBlank { n.title }, singleLine = false)), deleteLabel = "Delete this note?",
                    onDelete = { scope.launch { dao.deleteItem(n.id) } }) { v -> if (v[0].isNotBlank()) scope.launch { dao.updateItem(n.copy(title = v[0].take(60), body = v[0])) } })
            }.padding(12.dp)) {
                Txt(n.body.ifBlank { n.title }, Type.body, Color(0xFF4E3800), maxLines = 4)
            }
        }
        Spacer(Modifier.height(6.dp))
        InlineAdd("Add a note") { text ->
            scope.launch { dao.insertItem(Item(type = ItemType.NOTE, title = text.take(40), body = text, albumId = al.id, source = "user", createdAt = System.currentTimeMillis())) }
        }
    }
}

@Composable
private fun JournalBlock(a: MainActivity, al: Album, b: SpaceBlock, items: List<Item>) {
    val dao = a.cue.engine.dao
    val all by dao.itemsOfTypeFlow(ItemType.JOURNAL).collectAsState(emptyList())
    // The space's own entries, plus any day's journal that falls inside its dates.
    val from = al.startAt?.let { it - 86_400_000 }
    val to = (al.endAt ?: al.startAt)?.let { it + 86_400_000 }
    val days = all.filter { j -> j.albumId == al.id || (from != null && to != null && (j.eventStart ?: j.createdAt) in from..to) }.sortedBy { it.eventStart ?: it.createdAt }
    BlockCard(b.type, b.title, if (days.isNotEmpty()) "${days.size} days" else null) {
        if (days.isEmpty()) Txt("Your night calls during this go here, day by day.", Type.small, Cue.muted)
        days.forEach { j ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Color(0xFFFFF1EA), Color(0xFFFFDCCD)))).padding(12.dp)) {
                Txt(TextKit.dateLabel(j.eventStart ?: j.createdAt, zone), Type.smallStrong, Color(0xFF8E2713))
                Txt(j.body, Type.body, Color(0xFF5A1E10), maxLines = 5)
            }
        }
    }
}

@Composable
private fun PeopleBlock(a: MainActivity, b: SpaceBlock) {
    val dao = a.cue.engine.dao
    val scope = rememberCoroutineScope()
    val people = Spaces.people(b)
    val pending by dao.openPendingFlow().collectAsState(emptyList())
    val known by dao.peopleFlow().collectAsState(emptyList())
    BlockCard(b.type, b.title, if (people.isNotEmpty()) "${people.size}" else null) {
        if (people.isEmpty()) Txt("Who's part of this?", Type.small, Cue.muted)
        people.forEach { name ->
            val pid = known.firstOrNull { it.name.equals(name, true) }?.id
            val open = pending.filter { it.personId == pid }
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(AppTints.people.light, AppTints.people.deep))), contentAlignment = Alignment.Center) {
                    Txt(name.take(1).uppercase(), Type.smallStrong, Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(name, Type.body)
                    if (open.isNotEmpty()) Txt(open.joinToString(" · ") { it.text }, Type.small, Cue.muted, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        InlineAdd("Add names") { names -> scope.launch { dao.updateBlock(b.copy(config = JSONObject(b.config).put("people", JSONArray((people + Spaces.people(names)).distinct())).toString())) } }
    }
}

@Composable
private fun ReadingsBlock(a: MainActivity, b: SpaceBlock) {
    val dao = a.cue.engine.dao
    val readings by dao.measurementsFlow().collectAsState(emptyList())
    val kinds = runCatching { JSONObject(b.config).optJSONArray("kinds") }.getOrNull()?.let { k -> (0 until k.length()).map { k.optString(it) } }.orEmpty().ifEmpty { listOf(MeasureKind.BP, MeasureKind.SUGAR) }
    BlockCard(b.type, b.title) {
        val any = readings.any { it.kind in kinds }
        if (!any) Txt("Tell me a reading on any call, like \"BP 130 over 85\", and it shows here.", Type.small, Cue.muted)
        kinds.forEach { kind ->
            val list = readings.filter { it.kind == kind }.sortedBy { it.takenAt }.takeLast(14)
            if (list.isEmpty()) return@forEach
            val last = list.last()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(MeasureWords.name(kind).replaceFirstChar { it.uppercase() }, Type.bodyStrong)
                    Txt("${TextKit.dateLabel(last.takenAt, zone)} · ${list.size} readings", Type.small, Cue.muted)
                }
                Spark(list.map { it.value.toFloat() }, Modifier.width(90.dp).height(32.dp))
                Spacer(Modifier.width(10.dp))
                Txt(MeasureWords.format(kind, last.value, last.value2), Type.bodyStrong, AppTints.reminders.text)
            }
        }
    }
}

@Composable
private fun Spark(values: List<Float>, modifier: Modifier) {
    if (values.size < 2) { Spacer(modifier); return }
    val lo = values.min(); val hi = values.max().let { if (it == lo) lo + 1 else it }
    Canvas(modifier) {
        val step = size.width / (values.size - 1)
        for (i in 1 until values.size) {
            val y0 = size.height * (1 - (values[i - 1] - lo) / (hi - lo)); val y1 = size.height * (1 - (values[i] - lo) / (hi - lo))
            drawLine(AppTints.reminders.deep, Offset((i - 1) * step, y0), Offset(i * step, y1), 5f, StrokeCap.Round)
        }
    }
}

@Composable
private fun MedicinesBlock(a: MainActivity, b: SpaceBlock) {
    val plans by a.cue.engine.dao.plansFlow().collectAsState(emptyList())
    val active = plans.filter { PlanSpec.of(it).active }
    BlockCard(b.type, b.title, if (active.isNotEmpty()) "${active.size}" else null) {
        if (active.isEmpty()) Txt("Tell me the prescription on a call (\"Telma 40 after lunch for 30 days\") and I'll ring you for each dose.", Type.small, Cue.muted)
        active.forEach { p -> Txt(planLine(p).substringAfter(' '), Type.body, modifier = Modifier.padding(vertical = 4.dp), maxLines = 2) }
    }
}

@Composable
private fun RemindersBlock(a: MainActivity, al: Album, b: SpaceBlock, t: Long) {
    val reminders by a.cue.engine.dao.scheduledRemindersFlow().collectAsState(emptyList())
    val mine = com.cue.agent.collapseDaily(reminders.filter { it.albumId == al.id && it.kind != ReminderKind.PRELOAD && it.kind != ReminderKind.AIRPORT_BACKUP }).take(6)
    BlockCard(b.type, b.title, if (mine.isNotEmpty()) "${mine.size}" else null) {
        if (mine.isEmpty()) Txt("Nothing planned for this yet. Ask me on a call to ring you about it.", Type.small, Cue.muted)
        mine.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(TextKit.dayLabel(r.fireAt, t, zone).replaceFirstChar { it.uppercase() } + ", " + TextKit.clock(r.fireAt, zone), Type.smallStrong, AppTints.reminders.text, modifier = Modifier.width(120.dp))
                Txt(com.cue.agent.reminderLabel(r).replaceFirstChar { it.uppercase() }, Type.body, modifier = Modifier.weight(1f), maxLines = 2)
            }
        }
    }
}

/** A compact add-a-line field inside a block. */
@Composable
fun InlineAdd(hint: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun submit() { if (text.isNotBlank()) { onAdd(text.trim()); text = "" } }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(CircleShape).background(Cue.base).border(1.dp, Cue.line, CircleShape).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(text, { text = it }, Modifier.weight(1f).padding(vertical = 11.dp), textStyle = Type.body.copy(color = Cue.ink), singleLine = true,
            cursorBrush = SolidColor(Cue.primaryDeep), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
            decorationBox = { inner -> if (text.isEmpty()) Txt(hint, Type.body, Cue.muted, maxLines = 1); inner() })
        Box(Modifier.size(36.dp).clip(CircleShape).background(if (text.isBlank()) Cue.line else Cue.primaryDeep).clickable(role = Role.Button, onClickLabel = "Add") { submit() },
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Add", tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}

// ------------------------------------------------------------------ planning a new space

/**
 * "What are you planning?" The user types a goal; Cue plans the page (the model picks blocks, or a template when
 * offline); the user sees the plan, drops blocks they don't want, and makes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewSpaceCard(a: MainActivity, nav: Nav) {
    val engine = a.cue.engine
    val scope = rememberCoroutineScope()
    var goal by remember { mutableStateOf("") }
    var planning by remember { mutableStateOf(false) }
    var spec by remember { mutableStateOf<SpaceSpec?>(null) }
    fun plan(text: String) {
        if (text.isBlank() || planning) return
        planning = true
        scope.launch { spec = Spaces(engine).plan(text.trim()); planning = false }
    }
    GradientCard(Cue.primaryBrush, Cue.primaryDeep, Modifier.fillMaxWidth(), padding = 18.dp) {
        val s = spec
        if (s == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("What are you planning?", Type.lead, Color.White)
                    Txt("A trip, a doctor visit, a wedding, a move. I'll build its page.", Type.small, Color.White.copy(alpha = 0.85f))
                }
                PixelHead(48.dp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(CircleShape).background(Color.White).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(goal, { goal = it }, Modifier.weight(1f).padding(vertical = 13.dp), textStyle = Type.body.copy(color = Cue.ink), singleLine = true,
                    cursorBrush = SolidColor(Cue.primaryDeep), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { plan(goal) }),
                    decorationBox = { inner -> if (goal.isEmpty()) Txt("Goa trip next Friday with Riya", Type.body, Cue.muted, maxLines = 1); inner() })
                Box(Modifier.size(42.dp).clip(CircleShape).background(Cue.primaryButton).clickable(role = Role.Button, onClickLabel = "Plan it") { plan(goal) }, contentAlignment = Alignment.Center) {
                    if (planning) Txt("…", Type.bodyStrong, Color.White) else Icon(Icons.Rounded.AutoAwesome, "Plan it", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            if (planning) Txt("Planning your space…", Type.small, Color.White, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Weekend trip", "Doctor visit", "Wedding", "Moving house", "Exam prep").forEach { ex ->
                    Txt(ex, Type.tiny, Color.White, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable { goal = ex; plan(ex) }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
        } else {
            val (_, icon) = kindLook(s.kind)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(s.title, Type.title, Color.White)
                    val d = s.start?.let { st -> st.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)) + (s.end?.takeIf { it != st }?.let { " – " + it.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)) } ?: "") }
                    Txt(d ?: "No date yet", Type.small, Color.White.copy(alpha = 0.85f))
                }
            }
            Spacer(Modifier.height(12.dp))
            Txt("I'll set it up with these. Tap one to leave it out.", Type.small, Color.White.copy(alpha = 0.9f))
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.blocks.forEachIndexed { i, b ->
                    val (_, bi) = blockLook(b.type)
                    Row(Modifier.clip(CircleShape).background(Color.White).clickable(role = Role.Button, onClickLabel = "Leave out ${b.title}") {
                        spec = s.copy(blocks = s.blocks.filterIndexed { j, _ -> j != i })
                    }.padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(bi, null, tint = Cue.primaryDeep, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                        Txt(b.title + (if (b.seed.isNotEmpty()) " · ${b.seed.size}" else ""), Type.tiny, Cue.ink)
                        Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.Close, null, tint = Cue.muted, modifier = Modifier.size(13.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Cancel", Modifier.weight(1f), color = Color.White.copy(alpha = 0.2f), textColor = Color.White) { spec = null }
                SoftButton("Make it", Modifier.weight(1f), color = Color.White, textColor = Cue.primaryDeep) {
                    if (s.blocks.isEmpty()) return@SoftButton
                    scope.launch { val id = Spaces(engine).create(s); spec = null; goal = ""; nav.go("album/$id") }
                }
            }
        }
    }
}
