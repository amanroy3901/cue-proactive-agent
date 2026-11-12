package com.cue.brain.ondevice

import android.app.ActivityManager
import android.content.Context
import com.cue.brain.Brain
import com.cue.brain.BrainCapabilities
import com.cue.brain.BrainEvent
import com.cue.brain.BrainSession
import com.cue.brain.FieldsRequest
import com.cue.brain.FieldsResult
import com.cue.brain.SessionConfig
import com.cue.brain.TurnInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * LiquidAI LFM3-Omni-2B on the phone, behind the brain contract. Loaded on demand (about 5.2 GB), unloaded after the
 * call. Model files live in the app's external files folder, pushed over USB for the demo.
 */
class Lfm3Brain(private val context: Context) : Brain {
    override val name = "lfm3"
    override val capabilities = BrainCapabilities(contextTokens = 4096, maxTools = 5, hearsAudio = true, speaks = true, needsNetwork = false, prepareMillis = 48_000)

    val modelDir: File get() = context.getExternalFilesDir(null) ?: context.filesDir

    fun filesPresent(): Boolean = File(modelDir, BUNDLE).exists() && SPEECH.all { File(modelDir, "speech/$it").exists() || File(modelDir, it).exists() }

    fun freeMemoryMb(): Long {
        val mi = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }

    fun status(): String = when {
        !filesPresent() -> "Model files not found in ${modelDir.absolutePath}"
        else -> "Model files present; engine not loaded"
    }

    override suspend fun prepare(): Boolean = false
    override fun isReady(): Boolean = false

    override fun openSession(config: SessionConfig): BrainSession = object : BrainSession {
        override fun send(input: TurnInput): Flow<BrainEvent> = flowOf(BrainEvent.Failed("on-device model not loaded"))
        override fun close() = Unit
    }

    override suspend fun complete(request: FieldsRequest) = FieldsResult(false, emptyList())
    override suspend fun release() = Unit

    companion object {
        const val BUNDLE = "LFM3-Omni-2B_int8.litertlm"
        val SPEECH = listOf("LFM3-Omni-2B_depth_frame2_f32.tflite", "LFM3-Omni-2B_frame_embed_f32.tflite", "LFM3-Omni-2B_codec_decoder_f12_f32.tflite")
    }
}
