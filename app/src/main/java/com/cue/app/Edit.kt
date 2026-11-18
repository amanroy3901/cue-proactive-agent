package com.cue.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cue.agent.TextKit
import com.cue.domain.Album
import com.cue.ui.Cue
import com.cue.ui.Txt
import com.cue.ui.Type
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Manual editing for every app. Cue's tools change the same rows by voice; these let the user do it by hand, so each app
 * works without the agent: add, edit and delete from the screen.
 */

private val zone: ZoneId get() = ZoneId.systemDefault()

/** One field in an [EditDialog]. */
data class EditField(val label: String, val value: String, val singleLine: Boolean = true)

/** What an [EditDialog] is editing. Null hides it. */
data class EditRequest(
    val title: String,
    val fields: List<EditField>,
    val saveLabel: String = "Save",
    val deleteLabel: String? = null,
    /** Extra controls under the fields (a date, a switch); they keep their own state. */
    val extra: (@Composable () -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onSave: (List<String>) -> Unit,
)

/** A small form: text fields, Save, Cancel, and Delete when [EditRequest.onDelete] is set. Deleting asks once more. */
@Composable
fun EditDialog(req: EditRequest?, onDismiss: () -> Unit) {
    if (req == null) return
    val values = remember(req) { req.fields.map { mutableStateOf(it.value) } }
    var confirmDelete by remember(req) { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(req.deleteLabel ?: "Delete?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton({ req.onDelete?.invoke(); confirmDelete = false; onDismiss() }) { Text("Delete", color = Cue.coralText) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(req.title, style = Type.lead) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                req.fields.forEachIndexed { i, f ->
                    OutlinedTextField(values[i].value, { values[i].value = it }, label = { Text(f.label) }, singleLine = f.singleLine,
                        minLines = if (f.singleLine) 1 else 3, modifier = Modifier.fillMaxWidth())
                }
                req.extra?.invoke()
            }
        },
        confirmButton = {
            TextButton({ req.onSave(values.map { it.value.trim() }); onDismiss() }) { Text(req.saveLabel, color = Cue.primaryDeep, style = Type.bodyStrong) }
        },
        dismissButton = {
            Row {
                if (req.onDelete != null) TextButton({ confirmDelete = true }) { Text("Delete", color = Cue.coralText) }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Holder so a screen can open the dialog from anywhere in its list. */
class Editor { var request by mutableStateOf<EditRequest?>(null); fun open(r: EditRequest) { request = r } }

@Composable fun rememberEditor(): Editor { val e = remember { Editor() }; EditDialog(e.request) { e.request = null }; return e }

/** Android's own date then time pickers, starting at [initial]. */
fun pickDateTime(context: Context, initial: Long, withTime: Boolean = true, onPicked: (Long) -> Unit) {
    val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(initial), zone)
    DatePickerDialog(context, { _, y, m, d ->
        if (!withTime) { onPicked(LocalDateTime.of(y, m + 1, d, 10, 0).atZone(zone).toInstant().toEpochMilli()); return@DatePickerDialog }
        TimePickerDialog(context, { _, h, min -> onPicked(LocalDateTime.of(y, m + 1, d, h, min).atZone(zone).toInstant().toEpochMilli()) },
            start.hour, start.minute, false).show()
    }, start.year, start.monthValue - 1, start.dayOfMonth).show()
}

fun pickTime(context: Context, minutes: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(h * 60 + m) }, minutes / 60, minutes % 60, false).show()
}

/** A tappable "When: Tue, 6:30 PM" row that opens the pickers; holds its value in [state]. */
@Composable
fun WhenRow(state: MutableState<Long>, label: String = "When", withTime: Boolean = true) {
    val c = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Cue.primaryTint).clickable { pickDateTime(c, state.value, withTime) { state.value = it } }
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Type.smallStrong, Cue.muted, modifier = Modifier.weight(1f))
        Txt(TextKit.dateLabel(state.value, zone) + if (withTime) ", " + TextKit.clock(state.value, zone) else "", Type.bodyStrong, Cue.primaryDeep)
    }
}

@Composable
fun SwitchRow(label: String, state: MutableState<Boolean>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Type.body, modifier = Modifier.weight(1f)); Switch(state.value, { state.value = it })
    }
}

/** Pick one of a few options (a space, a kind). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(label: String, options: List<Pair<String, String>>, state: MutableState<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, Type.smallStrong, Cue.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (key, text) ->
                val on = state.value == key
                Txt(text, Type.tiny, if (on) Color.White else Cue.ink, modifier = Modifier.clip(CircleShape).background(if (on) Cue.primaryDeep else Cue.primaryTint)
                    .clickable { state.value = key }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

fun spaceOptions(albums: List<Album>): List<Pair<String, String>> = listOf("" to "None") + albums.map { it.id.toString() to it.title.substringBefore(",") }

/** A three-dot button with a menu. */
@Composable
fun MoreMenu(items: List<Pair<String, () -> Any?>>, tint: Color = Cue.muted) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "More") { open = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MoreVert, "More", tint = tint, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(open, { open = false }) {
            items.forEach { (label, action) -> DropdownMenuItem(text = { Text(label, color = if (label.startsWith("Delete")) Cue.coralText else Cue.ink) }, onClick = { open = false; action() }) }
        }
    }
}

/** A pill button for a screen's main "New …" action. */
@Composable
fun NewButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.9f)).clickable(role = Role.Button, onClick = onClick).padding(start = 10.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Cue.primaryButton), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        Spacer(Modifier.width(8.dp)); Txt(text, Type.smallStrong, Cue.primaryDeep)
    }
}

/** Tap does [onClick]; a long press opens the editor. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.editable(onClick: () -> Unit = {}, onEdit: () -> Unit): Modifier = this.combinedClickable(onClick = onClick, onLongClick = onEdit, onLongClickLabel = "Edit")
