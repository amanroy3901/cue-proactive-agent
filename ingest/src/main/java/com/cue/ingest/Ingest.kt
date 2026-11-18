package com.cue.ingest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cue.agent.CueRuntime
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

private const val TAG = "CueIngest"

/**
 * Phase one of ingest, on the phone with no model: read the text (ML Kit OCR for images, PDF pages rendered then OCR'd,
 * plain text as is), hash the file to drop duplicates, then hand it to the engine to extract fields and file it.
 */
object Ingest {
    suspend fun process(context: Context, path: String, source: String, fileName: String, albumId: Long? = null) {
        val file = File(path)
        if (!file.exists()) return
        val hash = sha(file)
        val lower = fileName.lowercase()
        val text = when {
            lower.endsWith(".pdf") -> pdfText(file)
            lower.endsWith(".txt") -> file.readText().take(8000)
            else -> ocr(context, Uri.fromFile(file))
        }
        Log.i(TAG, "ingest $source $fileName -> ${text.length} chars")
        CueRuntime.engine.ingestDocument(text, path, source, hash, fileName, albumId)
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    suspend fun ocr(context: Context, uri: Uri): String = runCatching { recognize(InputImage.fromFilePath(context, uri)) }.getOrElse { Log.w(TAG, "ocr failed", it); "" }

    suspend fun ocrBitmap(bitmap: Bitmap): String = runCatching { recognize(InputImage.fromBitmap(bitmap, 0)) }.getOrDefault("")

    private suspend fun recognize(image: InputImage): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { cont.resume(it.text); recognizer.close() }
            .addOnFailureListener { cont.resume(""); recognizer.close() }
    }

    private suspend fun pdfText(file: File): String {
        val out = StringBuilder()
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    for (i in 0 until minOf(2, r.pageCount)) {
                        r.openPage(i).use { page ->
                            val scale = 2
                            val bmp = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            out.appendLine(ocrBitmap(bmp))
                            bmp.recycle()
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "pdf failed", it) }
        return out.toString()
    }

    /** [albumId]: file it into this space instead of letting Cue pick the album. */
    fun enqueue(context: Context, path: String, source: String, fileName: String, albumId: Long? = null) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<IngestWorker>().setInputData(workDataOf("path" to path, "source" to source, "name" to fileName, "albumId" to (albumId ?: -1L))).build())
    }

    fun copy(context: Context, uri: Uri): Pair<String, String>? = runCatching {
        var name = "shared"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
        val type = context.contentResolver.getType(uri).orEmpty()
        if ('.' !in name) name += when { type.contains("pdf") -> ".pdf"; type.startsWith("text") -> ".txt"; else -> ".jpg" }
        val dir = File(context.filesDir, "media").apply { mkdirs() }
        val f = File(dir, "share-${System.nanoTime()}.${name.substringAfterLast('.')}")
        context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        f.absolutePath to name
    }.onFailure { Log.w(TAG, "copy failed", it) }.getOrNull()
}

class IngestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val path = inputData.getString("path") ?: return Result.failure()
        if (!CueRuntime.ready) return Result.retry()
        Ingest.process(applicationContext, path, inputData.getString("source") ?: "share", inputData.getString("name") ?: "", inputData.getLong("albumId", -1L).takeIf { it > 0 })
        return Result.success()
    }
}

/**
 * Share target: "Save to Cue" from WhatsApp, Gmail, Drive or Files. The content URI grant only lasts while this
 * activity runs, so the file is copied at once and processed in the background.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = mutableListOf<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris += it }
                if (uris.isEmpty()) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    val dir = File(filesDir, "media").apply { mkdirs() }
                    val f = File(dir, "text-${System.nanoTime()}.txt").apply { writeText(text) }
                    Ingest.enqueue(this, f.absolutePath, "share", "shared.txt")
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            }
        }
        uris.forEach { u -> Ingest.copy(this, u)?.let { (path, name) -> Ingest.enqueue(this, path, "share", name) } }
        Toast.makeText(this, "Cue is filing it…", Toast.LENGTH_SHORT).show()
        finish()
    }
}
