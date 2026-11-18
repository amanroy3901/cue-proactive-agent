package com.cue.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.cue.agent.CueRuntime
import com.cue.agent.Inbox
import com.cue.domain.Album
import com.cue.ingest.Ingest
import com.cue.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A small square preview of a file: the picture itself, or a document icon. */
@Composable
fun FileThumb(path: String?, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val bmp = remember(path) {
        path?.takeIf { Ingest.isImage(it) }?.let { p ->
            runCatching {
                val o = BitmapFactory.Options().apply { inSampleSize = 8 }
                BitmapFactory.decodeFile(p, o)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Cue.primaryTint), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.Description, null, tint = Cue.primaryDeep, modifier = Modifier.size(size / 2))
    }
}

/** A space's name, with its date when another space has the same name. */
fun spaceLabel(a: Album, all: List<Album>): String =
    if (all.count { it.title.equals(a.title, true) } > 1) a.title + (a.startAt?.let { " · " + com.cue.agent.TextKit.dateLabel(it, java.time.ZoneId.systemDefault()) } ?: " · #${a.id}") else a.title

/** A tap-to-answer choice. */
@Composable
fun ChoiceChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Txt(label, Type.smallStrong, if (selected) Color.White else Cue.primaryDeep,
        modifier = Modifier.clip(CircleShape).background(if (selected) Cue.primaryDeep else Color.White)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp))
}

/**
 * What Cue did with one file, and the one-tap fixes: Undo, or pick another space. When Cue wasn't sure, the spaces
 * are the question ("Where should it go?").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FiledRow(f: Inbox.Filed, spaces: List<Album>, onUndo: () -> Unit, onMove: (Long) -> Unit, undone: Boolean = false) {
    var moving by remember(f) { mutableStateOf(!f.sure) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileThumb(f.item?.mediaPath)
            Spacer(Modifier.width(12.dp))
            Txt(if (undone) "Removed." else f.message(), Type.body, modifier = Modifier.weight(1f), maxLines = 3)
            if (f.sure && !undone && !f.duplicate) Icon(Icons.Rounded.CheckCircle, null, tint = Cue.primaryDeep, modifier = Modifier.size(22.dp))
        }
        if (undone || f.item == null || f.duplicate) return@Column
        val choices = (f.options + spaces).filter { it.id != f.album?.id }.distinctBy { it.id }.take(5)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (moving) {
                choices.forEachIndexed { i, a -> ChoiceChip(spaceLabel(a, spaces), selected = i == 0 && !f.sure) { onMove(a.id) } }
                f.album?.takeIf { !f.sure }?.let { al -> ChoiceChip("Keep in ${al.title}") { moving = false } }
            } else {
                Row(Modifier.clip(CircleShape).background(Color.White).clickable(role = Role.Button, onClick = onUndo).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Undo, null, tint = Cue.muted, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Txt("Undo", Type.smallStrong, Cue.muted)
                }
                if (choices.isNotEmpty()) ChoiceChip("Move to…") { moving = true }
            }
        }
    }
}

/**
 * "Share to Cue" from WhatsApp, Gmail, Gallery, Drive or Files. Cue reads each file at once, files it where it
 * belongs, and shows a small sheet: right → it closes by itself; wrong → one tap to move or undo; unsure → it asks.
 */
class ShareActivity : ComponentActivity() {
    private data class Row(val path: String?, val name: String, val filed: Inbox.Filed? = null, val undone: Boolean = false)

    private val rows = mutableStateListOf<Row>()
    private var spaces by mutableStateOf<List<Album>>(emptyList())
    private var closer: Job? = null
    private var touched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uris = mutableListOf<Uri>()
        var text: String? = null
        @Suppress("DEPRECATION")
        when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it } ?: run { text = intent.getStringExtra(Intent.EXTRA_TEXT) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        }
        // The content URI grant only lasts while this activity runs: copy first.
        uris.mapNotNull { Ingest.copy(this, it) }.forEach { (path, name) -> rows += Row(path, name) }
        text?.takeIf { it.isNotBlank() }?.let { rows += Row(null, it.take(80)) }
        if (rows.isEmpty()) { finish(); return }
        setContent { CueTheme { Sheet(text) } }
        lifecycleScope.launch {
            var waited = 0
            while (!CueRuntime.ready && waited < 50) { delay(100); waited++ }
            if (!CueRuntime.ready) { rows.clear(); finish(); return@launch }
            val engine = CueRuntime.engine
            rows.indices.forEach { i ->
                val r = rows[i]
                val f = withContext(Dispatchers.IO) {
                    if (r.path != null) Ingest.fileNow(this@ShareActivity, r.path, r.name, "share") else Ingest.fileText(this@ShareActivity, text.orEmpty(), "share")
                }
                rows[i] = r.copy(filed = f, path = f.item?.mediaPath ?: r.path)
            }
            spaces = withContext(Dispatchers.IO) { engine.dao.albums() }
            // All filed with confidence: leave by itself unless the user starts fixing something.
            if (rows.all { it.filed?.sure == true }) closer = launch { delay(4500); if (!touched) finish() }
        }
    }

    private fun touch() { touched = true; closer?.cancel() }

    @Composable
    private fun Sheet(text: String?) {
        val working = rows.any { it.filed == null }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().shadow(24.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Cue.base).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { touch() }
                .navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelHead(40.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(if (working) "Reading it…" else if (rows.any { it.filed?.sure == false }) "Where should this go?" else "Saved", Type.lead)
                        Txt(if (rows.size == 1) (if (text != null) "Shared text" else rows[0].name) else "${rows.size} files", Type.small, Cue.muted, maxLines = 1)
                    }
                }
                rows.forEachIndexed { i, r ->
                    val f = r.filed
                    if (f == null) Row(verticalAlignment = Alignment.CenterVertically) { FileThumb(r.path); Spacer(Modifier.width(12.dp)); Txt("Reading…", Type.body, Cue.muted) }
                    else FiledRow(f, spaces, undone = r.undone,
                        onUndo = { touch(); lifecycleScope.launch { withContext(Dispatchers.IO) { CueRuntime.engine.inbox.undo(f) }; rows[i] = r.copy(undone = true) } },
                        onMove = { id -> touch(); lifecycleScope.launch { val m = withContext(Dispatchers.IO) { CueRuntime.engine.inbox.move(f, id) }; rows[i] = r.copy(filed = m) } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val album = rows.firstNotNullOfOrNull { it.filed?.album?.takeIf { _ -> !it.undone } }
                    if (album != null) SoftButton("Open ${album.title}", Modifier.weight(1f), color = Color.White) {
                        startActivity(Intent(this@ShareActivity, MainActivity::class.java).putExtra("albumId", album.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    }
                    PrimaryButton("Done", Modifier.weight(1f), height = 52.dp) { finish() }
                }
            }
        }
    }
}

/** Copies the files a picker returned into Cue's storage: (path, name) pairs. */
fun copyPicked(c: android.content.Context, uris: List<Uri>): List<Pair<String, String>> = uris.mapNotNull { Ingest.copy(c, it) }

/** A fresh file for the camera to write into, and its content URI. */
fun cameraTarget(c: android.content.Context): Pair<File, Uri> {
    val dir = File(c.filesDir, "media").apply { mkdirs() }
    val f = File(dir, "photo-${System.nanoTime()}.jpg")
    return f to androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".files", f)
}
