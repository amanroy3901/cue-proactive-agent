package com.cue.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Where Android entry points (receivers, services, activities in library modules) find the engine. :app sets it in
 * Application.onCreate, which always runs before any receiver or service in the process.
 */
object CueRuntime {
    @Volatile private var _engine: CueEngine? = null
    val engine: CueEngine get() = _engine ?: error("Cue engine not initialised")
    val ready: Boolean get() = _engine != null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Hooks for platform work the JVM modules cannot do themselves. */
    @Volatile var ingestFile: (suspend (path: String, source: String, fileName: String) -> Unit)? = null
    @Volatile var openCall: ((kind: String, reminderId: Long?) -> Unit)? = null
    /** Draws a generated screen for a call's board: A2UI messages (JSON array text) and Cue's line; null if it can't. */
    @Volatile var drawScreen: (suspend (surfaceId: String, request: String, albumId: Long?) -> Pair<String, String>?)? = null

    fun install(engine: CueEngine) { _engine = engine }
}
