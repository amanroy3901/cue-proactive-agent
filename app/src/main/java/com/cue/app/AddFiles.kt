package com.cue.app

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.cue.ingest.Ingest
import com.cue.ui.SoftButton
import java.io.File

/**
 * Adding a bill, ticket, prescription or report from inside Cue: pick photos or PDFs, or scan with the camera.
 * Files go through the same path as "Share to Cue": copied into app storage, read, filed into an album.
 */
@Composable
fun AddFilesRow(modifier: Modifier = Modifier.fillMaxWidth(), albumId: Long? = null) {
    val c = LocalContext.current
    val pending = remember { mutableStateOf<File?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> fileAll(c, uris, albumId) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending.value
        pending.value = null
        if (ok && f != null && f.length() > 0) { Ingest.enqueue(c, f.absolutePath, "share", "scan.jpg", albumId); toast(c, "Cue is filing it…") } else f?.delete()
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftButton("Add photo or PDF", Modifier.weight(1f), color = Color.White) { pick.launch(arrayOf("image/*", "application/pdf")) }
        SoftButton("Scan with camera", Modifier.weight(1f), color = Color.White) {
            val dir = File(c.filesDir, "media").apply { mkdirs() }
            val f = File(dir, "scan-${System.nanoTime()}.jpg")
            pending.value = f
            camera.launch(FileProvider.getUriForFile(c, c.packageName + ".files", f))
        }
    }
}

private fun fileAll(c: Context, uris: List<Uri>, albumId: Long?) {
    if (uris.isEmpty()) return
    var n = 0
    uris.forEach { u -> Ingest.copy(c, u)?.let { (path, name) -> Ingest.enqueue(c, path, "share", name, albumId); n++ } }
    toast(c, if (n == 0) "Couldn't open that file" else "Cue is filing ${if (n == 1) "it" else "$n files"}…")
}

private fun toast(c: Context, text: String) = Toast.makeText(c, text, Toast.LENGTH_SHORT).show()
