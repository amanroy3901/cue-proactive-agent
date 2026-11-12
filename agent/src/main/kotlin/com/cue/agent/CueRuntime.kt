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

    fun install(engine: CueEngine) { _engine = engine }
}
