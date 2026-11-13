package com.cue.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cue.agent.CallCard
import com.cue.agent.CueRuntime
import com.cue.agent.TextKit
import com.cue.agent.reminderLabel
import com.cue.domain.ChecklistEntry
import com.cue.domain.ItemType
import com.cue.domain.ReminderKind
import com.cue.ui.AppTint
import com.cue.ui.AppTints
import com.cue.ui.CheckRow
import com.cue.ui.Cue
import com.cue.ui.IconChip
import com.cue.ui.Pill
import com.cue.ui.Txt
import com.cue.ui.Type
import kotlinx.coroutines.launch

/**
 * The cards a call shows when the conversation asks for them. Each reads the data itself (so it is always what is
 * saved, never what the model remembers) and reloads when [version] changes, after every turn.
 */
@Composable
fun CallCardView(card: CallCard, version: Int, dark: Boolean) {
    when (card) {
        is CallCard.Checklist -> ChecklistCard(card, version, dark)
        CallCard.Habits -> HabitsCard(version, dark)
        is CallCard.Journal -> JournalCard(card, version, dark)
        CallCard.Reminders -> RemindersCard(version, dark)
        is CallCard.Found -> FoundCard(card, version, dark)
        is CallCard.People -> PeopleCard(card, version, dark)
        CallCard.Lists -> ListsCard(version, dark)
        CallCard.Albums -> AlbumsCard(version, dark)
        CallCard.Renewals -> RenewalsCard(version, dark)
        CallCard.Focus -> FocusCard(version, dark)
    }
}

@Composable
private fun CardShell(title: String, icon: ImageVector, tint: AppTint, dark: Boolean, badge: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp)
            .shadow(14.dp, RoundedCornerShape(28.dp), spotColor = tint.deep.copy(alpha = 0.35f))
            .clip(RoundedCornerShape(28.dp))
            .background(if (dark) Cue.nightRaised else Color.White)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip(icon, tint, size = 32.dp, iconSize = 17.dp)
            Spacer(Modifier.width(10.dp))
            Txt(title, Type.bodyStrong, if (dark) Cue.nightText else Cue.ink, Modifier.weight(1f), maxLines = 1)
            if (badge != null) Pill(badge, if (dark) Color.White.copy(alpha = 0.1f) else Cue.coralTint, if (dark) Cue.apricot else Cue.coralText)
        }
        content()
    }
}

@Composable
private fun Muted(text: String, dark: Boolean) = Txt(text, Type.small, if (dark) Cue.nightMuted else Cue.muted)

@Composable
private fun ChecklistCard(card: CallCard.Checklist, version: Int, dark: Boolean) {
    var title by remember { mutableStateOf("List") }
    var entries by remember { mutableStateOf(emptyList<ChecklistEntry>()) }
    var tick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(card, version, tick) {
        val dao = CueRuntime.engine.dao
        title = dao.item(card.itemId)?.title ?: "List"
        entries = dao.entries(card.itemId).sortedWith(compareBy({ it.checked }, { it.position }))
    }
    val open = entries.count { !it.checked }
    CardShell(title, Icons.Rounded.Checklist, AppTints.lists, dark, if (card.changed) "Just updated" else "$open left") {
        if (entries.isEmpty()) Muted("Nothing on it yet.", dark)
        entries.take(8).forEach { e ->
            CheckRow(e.text, e.checked, onToggle = {
                scope.launch { CueRuntime.engine.dao.updateEntry(e.copy(checked = !e.checked)); tick++ }
            }, dark = dark)
        }
        if (entries.size > 8) Muted("and ${entries.size - 8} more", dark)
    }
}

private data class HabitRow(val name: String, val streak: Int, val week: List<Boolean>)

@Composable
private fun HabitsCard(version: Int, dark: Boolean) {
    var rows by remember { mutableStateOf(emptyList<HabitRow>()) }
    LaunchedEffect(version) {
        val e = CueRuntime.engine
        val zone = e.clock.zone
        val now = e.clock.now()
        val days = (6 downTo 0).map { TextKit.dayKey(now - it * 86_400_000L, zone) }
        rows = e.dao.habits().map { h ->
            val logs = e.dao.habitLogs(h.id).filter { it.done }.map { it.day }.toSet()
            HabitRow(h.name, e.streak(h.id), days.map { it in logs })
        }
    }
    CardShell("Habits", Icons.Rounded.LocalFireDepartment, AppTints.habits, dark, "Last 7 days") {
        if (rows.isEmpty()) Muted("You aren't tracking any habits yet.", dark)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(r.name, Type.bodyStrong, if (dark) Cue.nightText else Cue.ink, maxLines = 1)
                    Txt(if (r.streak > 0) "${r.streak}-day streak" else "No streak yet", Type.small, AppTints.habits.deep)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    r.week.forEach { done ->
                        Box(Modifier.size(14.dp).clip(CircleShape).background(
                            if (done) AppTints.habits.deep else if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFFF0ECF8)))
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalCard(card: CallCard.Journal, version: Int, dark: Boolean) {
    var date by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    LaunchedEffect(card, version) {
        val e = CueRuntime.engine
        val item = card.itemId?.let { e.dao.item(it) } ?: e.dao.itemsOfType(ItemType.JOURNAL).firstOrNull()
        date = item?.let { TextKit.dateLabel(it.eventStart ?: it.createdAt, e.clock.zone) }.orEmpty()
        body = item?.body.orEmpty()
    }
    CardShell("Journal", Icons.Rounded.AutoStories, AppTints.journal, dark, date.ifBlank { null }) {
        if (body.isBlank()) Muted("Your journal is empty.", dark)
        else Txt(body, Type.body, if (dark) Cue.nightText else Cue.ink, maxLines = 7)
    }
}

@Composable
private fun RemindersCard(version: Int, dark: Boolean) {
    var rows by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(version) {
        val e = CueRuntime.engine
        val now = e.clock.now()
        rows = e.dao.scheduledReminders().filter { it.kind != ReminderKind.PRELOAD && it.fireAt > now }.take(5).map { r ->
            "${TextKit.dayLabel(r.fireAt, now, e.clock.zone)} · ${TextKit.clock(r.fireAt, e.clock.zone)}" to reminderLabel(r).replaceFirstChar { it.uppercase() }
        }
    }
    CardShell("Coming up", Icons.Rounded.NotificationsActive, AppTints.reminders, dark) {
        if (rows.isEmpty()) Muted("Nothing is scheduled.", dark)
        rows.forEach { (whenText, what) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(AppTints.reminders.deep))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(what, Type.bodyStrong, if (dark) Cue.nightText else Cue.ink, maxLines = 1)
                    Txt(whenText, Type.small, AppTints.reminders.deep)
                }
            }
        }
    }
}

@Composable
private fun FoundCard(card: CallCard.Found, version: Int, dark: Boolean) {
    var rows by remember { mutableStateOf(emptyList<Triple<String, String, String>>()) }
    LaunchedEffect(card, version) {
        val e = CueRuntime.engine
        rows = card.itemIds.mapNotNull { e.dao.item(it) }.map { item ->
            val album = item.albumId?.let { e.dao.album(it)?.title }
            Triple(item.title, listOfNotNull(album, TextKit.dateLabel(item.createdAt, e.clock.zone)).joinToString(" · "),
                item.body.lineSequence().firstOrNull { it.isNotBlank() && it != item.title }.orEmpty())
        }
    }
    CardShell(if (rows.size == 1) "Found it" else "Found", Icons.Rounded.Description, AppTints.papers, dark) {
        rows.forEach { (title, where, snippet) ->
            Column {
                Txt(title, Type.bodyStrong, if (dark) Cue.nightText else Cue.ink, maxLines = 1)
                Txt(where, Type.small, AppTints.papers.deep, maxLines = 1)
                if (snippet.isNotBlank()) Muted(snippet.take(90), dark)
            }
        }
    }
}

@Composable
private fun PeopleCard(card: CallCard.People, version: Int, dark: Boolean) {
    var title by remember { mutableStateOf("Waiting on you") }
    var rows by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(card, version) {
        val dao = CueRuntime.engine.dao
        val person = card.personId?.let { dao.person(it) }
        title = person?.name ?: "Waiting on you"
        val open = if (person != null) dao.openPending(person.id) else dao.allOpenPending()
        rows = open.take(5).map { (dao.person(it.personId)?.name ?: "") to it.text }
    }
    CardShell(title, Icons.Rounded.People, AppTints.people, dark) {
        if (rows.isEmpty()) Muted("Nothing open.", dark)
        rows.forEach { (who, what) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.personId == null && who.isNotBlank()) {
                    Txt(who, Type.smallStrong, AppTints.people.deep)
                    Spacer(Modifier.width(8.dp))
                }
                Txt(what, Type.body, if (dark) Cue.nightText else Cue.ink, maxLines = 2)
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** Rows of (title, detail) in one card; the shared shape of the overview cards. */
@Composable
private fun RowsCard(title: String, icon: ImageVector, tint: AppTint, dark: Boolean, rows: List<Pair<String, String>>, empty: String, badge: String? = null) {
    CardShell(title, icon, tint, dark, badge) {
        if (rows.isEmpty()) Muted(empty, dark)
        rows.forEach { (main, detail) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(tint.deep))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(main, Type.bodyStrong, if (dark) Cue.nightText else Cue.ink, maxLines = 1)
                    if (detail.isNotBlank()) Txt(detail, Type.small, tint.deep, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ListsCard(version: Int, dark: Boolean) {
    var rows by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(version) {
        val dao = CueRuntime.engine.dao
        rows = dao.itemsOfType(ItemType.CHECKLIST).take(8).map { l ->
            val es = dao.entries(l.id)
            l.title to "${es.count { !it.checked }} left of ${es.size}"
        }
    }
    RowsCard("Your lists", Icons.Rounded.Checklist, AppTints.lists, dark, rows, "No lists yet.")
}

@Composable
private fun AlbumsCard(version: Int, dark: Boolean) {
    var rows by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(version) {
        val dao = CueRuntime.engine.dao
        rows = dao.albums().take(8).map { a -> a.title to "${dao.itemsInAlbum(a.id).size} things" }
    }
    RowsCard("Albums", Icons.Rounded.PhotoLibrary, AppTints.albums, dark, rows, "No albums yet.")
}

@Composable
private fun RenewalsCard(version: Int, dark: Boolean) {
    var rows by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(version) {
        val e = CueRuntime.engine
        val now = e.clock.now()
        rows = e.dao.expiring().filter { (it.expiresAt ?: 0) > now - 30 * 86_400_000L }.take(6).map {
            it.title to "Ends ${TextKit.dateLabel(it.expiresAt!!, e.clock.zone)}"
        }
    }
    RowsCard("Renewals", Icons.Rounded.Autorenew, AppTints.renewals, dark, rows, "Nothing is due for renewal.")
}

@Composable
private fun FocusCard(version: Int, dark: Boolean) {
    var what by remember { mutableStateOf<String?>(null) }
    var endsAt by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(version) {
        val e = CueRuntime.engine
        val f = e.dao.remindersOfKind(ReminderKind.FOCUS).firstOrNull { it.status == com.cue.domain.ReminderStatus.SCHEDULED && it.fireAt > e.clock.now() }
        what = f?.let { org.json.JSONObject(it.meta).optString("what", "Focus") }
        endsAt = f?.fireAt ?: 0L
    }
    LaunchedEffect(endsAt) { while (endsAt > 0) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val left = ((endsAt - now) / 1000).coerceAtLeast(0)
    CardShell("Focus", Icons.Rounded.Timer, AppTints.focus, dark, if (what != null) "Running" else null) {
        if (what == null) Muted("No focus session running.", dark)
        else {
            Txt("%d:%02d".format(left / 60, left % 60), Type.big, AppTints.focus.deep)
            Txt(what!!, Type.bodyStrong, if (dark) Cue.nightText else Cue.ink)
        }
    }
}
