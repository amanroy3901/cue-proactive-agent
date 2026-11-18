package com.cue.genui

import android.content.Context
import android.util.Log
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.toJsonSchemaString
import androidx.a2ui.model.catalog.basiccatalog.createBasicCatalogFunctions
import androidx.a2ui.model.processor.A2uiSurfaceModel
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.cue.domain.CueDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "CueGenUi"

/** What Cue's components need from the app: the database, a way to open a screen. */
interface CueGenEnv {
    val dao: CueDao
    fun open(route: String)
}

val LocalCueGen = staticCompositionLocalOf<CueGenEnv> { error("No CueGenEnv provided") }

/** A button on a generated screen was pressed: run [name] (one of Cue's tools, or "ask") with [args]. */
data class GenAction(val surfaceId: String, val name: String, val args: Map<String, String>)

object CueCatalog {
    /** Cue's catalog: the A2UI basic components (Material 3, in Cue's theme) plus Cue's own live components. */
    const val ID = "https://cue.app/a2ui/catalogs/cue/v1/catalog.json"

    fun build(): A2uiCatalog = A2uiCatalog(
        catalogId = ID,
        components = listOf(
            MaterialA2uiBasicCatalogV1Defaults.text,
            MaterialA2uiBasicCatalogV1Defaults.icon,
            MaterialA2uiBasicCatalogV1Defaults.row,
            MaterialA2uiBasicCatalogV1Defaults.column,
            MaterialA2uiBasicCatalogV1Defaults.list,
            MaterialA2uiBasicCatalogV1Defaults.card,
            MaterialA2uiBasicCatalogV1Defaults.tabs,
            MaterialA2uiBasicCatalogV1Defaults.divider,
            MaterialA2uiBasicCatalogV1Defaults.button,
            MaterialA2uiBasicCatalogV1Defaults.textField,
            MaterialA2uiBasicCatalogV1Defaults.checkBox,
            MaterialA2uiBasicCatalogV1Defaults.choicePicker,
            MaterialA2uiBasicCatalogV1Defaults.slider,
            MaterialA2uiBasicCatalogV1Defaults.dateTimeInput,
        ) + cueComponents + thinkComponents,
        // No browser from a generated screen: links are ignored.
        functions = createBasicCatalogFunctions(urlOpener = { Log.i(TAG, "openUrl ignored: $it") }, messageFormatter = { pattern, _, _ -> pattern }),
    )
}

/**
 * One A2UI renderer for a screen: feed it the agent's messages, draw its surfaces, hear back the buttons.
 * Text in, pictures out: nothing here makes a sound.
 */
class CueGenUi(private val onAction: (GenAction) -> Unit, private val onError: (String, String) -> Unit = { _, _ -> }) {
    val catalog: A2uiCatalog = CueCatalog.build()
    private val processor = A2uiMessageProcessor(catalogs = listOf(catalog))
    private val parser = A2uiMessageParser()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val surfaces: StateFlow<List<A2uiSurfaceModel>> = processor.activeSurfaces

    /** The catalog as JSON Schema, for the model's instructions. */
    val schema: String by lazy { runCatching { catalog.toJsonSchemaString() }.getOrElse { Log.w(TAG, "schema", it); "" } }

    /**
     * The same catalog in a few lines per component (about a fifth of the size): the model answers much faster with
     * it. Built from [schema], so it never drifts from what the renderer accepts.
     */
    val compactSchema: String by lazy { runCatching { compact(JSONObject(schema)) }.getOrElse { Log.w(TAG, "compact schema", it); schema } }

    private fun compact(root: JSONObject): String {
        val comps = root.getJSONObject("components")
        val sb = StringBuilder()
        sb.append("Types: DynamicString = a string, or {\"path\":\"/form/x\"} to read a form field. DynamicNumber/DynamicBoolean likewise. ")
        sb.append("ComponentId = another component's id. ChildList = array of component ids. Action = {\"event\":{\"name\":\"...\",\"context\":{...}}}. ")
        sb.append("Any component may set \"weight\" (number) when it sits directly in a Row or Column. * = required.\n")
        for (name in comps.keys()) {
            val c = comps.getJSONObject(name)
            val req = c.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
            val props = c.optJSONObject("properties") ?: JSONObject()
            val parts = props.keys().asSequence().filter { it !in setOf("component", "accessibility", "weight", "checks") }
                .map { k -> k + (if (k in req) "*" else "") + ": " + typeOf(props.getJSONObject(k), 0) }.toList()
            sb.append("- ").append(name).append(" { ").append(parts.joinToString("; ")).append(" }")
            c.optString("description").takeIf { it.isNotBlank() }?.let { sb.append(" — ").append(it.take(170)) }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun typeOf(p: JSONObject, depth: Int): String {
        val d = p.optString("description").takeIf { it.isNotBlank() && depth == 0 }?.let { " (" + it.take(90) + ")" }.orEmpty()
        p.optJSONArray("enum")?.let { e -> return (0 until e.length()).joinToString("|") { e.getString(it) } + d }
        p.optString("\$ref").takeIf { it.isNotBlank() }?.let { r -> return r.substringAfterLast('/') + d }
        p.optJSONArray("oneOf")?.let { o -> return (0 until o.length()).joinToString(" or ") { typeOf(o.getJSONObject(it), depth + 1) } + d }
        if (p.optString("type") == "array") {
            val items = p.optJSONObject("items")
            val ip = items?.optJSONObject("properties")
            val inner = if (ip != null) "{" + ip.keys().asSequence().joinToString(", ") { k -> k + ": " + typeOf(ip.getJSONObject(k), depth + 1) } + "}" else items?.let { typeOf(it, depth + 1) } ?: "any"
            return "[$inner]$d"
        }
        if (p.optString("type") == "object") p.optJSONObject("properties")?.let { op ->
            return "{" + op.keys().asSequence().joinToString(", ") { k -> k + ": " + typeOf(op.getJSONObject(k), depth + 1) } + "}" + d
        }
        return p.optString("type").ifBlank { "any" } + d
    }

    init {
        scope.launch { processor.collectMessages() }
        scope.launch {
            processor.outboundEvents.collect { m ->
                when (m) {
                    is A2uiClientEventMessage -> onAction(GenAction(m.surfaceId, m.type, m.context.mapValues { (_, v) -> v?.toString().orEmpty() }))
                    is A2uiClientErrorMessage -> { Log.w(TAG, "surface ${m.surfaceId}: ${m.code} ${m.message}"); onError(m.surfaceId, "${m.code}: ${m.message}") }
                    else -> Log.i(TAG, "outbound $m")
                }
            }
        }
    }

    /** Feeds one A2UI message (a JSON object with "version" and one of createSurface / updateComponents / updateDataModel / deleteSurface). */
    fun feed(message: String) {
        Log.i(TAG, "in: ${message.take(400)}")
        processor.processInput(parser, message)
    }

    /** Feeds every message in [messages], forcing [surfaceId] and the protocol version so a sloppy answer still lands. */
    fun feedAll(messages: JSONArray, surfaceId: String) {
        // A sloppy answer sometimes lists the components themselves instead of A2UI messages: wrap them.
        val bare = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }.filter { it.has("component") && it.has("id") }
        if (bare.isNotEmpty()) {
            val envelopes = JSONArray()
            (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }.filter { !it.has("component") }.forEach { envelopes.put(it) }
            envelopes.put(JSONObject().put("updateComponents", JSONObject().put("components", JSONArray(bare))))
            return feedAll(envelopes, surfaceId)
        }
        var created = false
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            m.put("version", "v0.9")
            listOf("createSurface", "updateComponents", "updateDataModel", "deleteSurface").forEach { k -> m.optJSONObject(k)?.put("surfaceId", surfaceId) }
            m.optJSONObject("createSurface")?.let { it.put("catalogId", CueCatalog.ID); created = true }
            if (!created && (m.has("updateComponents") || m.has("updateDataModel"))) {
                feed(JSONObject().put("version", "v0.9").put("createSurface", JSONObject().put("surfaceId", surfaceId).put("catalogId", CueCatalog.ID)).toString())
                created = true
            }
            feed(m.toString())
        }
    }

    fun close() { scope.launch { surfaces.value.forEach { feed(JSONObject().put("version", "v0.9").put("deleteSurface", JSONObject().put("surfaceId", it.id)).toString()) } } }
}

/** Draws the surface with [id] if the agent made it. */
@Composable
fun GenSurface(genUi: CueGenUi, surfaces: List<A2uiSurfaceModel>, id: String, env: CueGenEnv, modifier: Modifier = Modifier) {
    val s = surfaces.firstOrNull { it.id == id } ?: return
    CompositionLocalProvider(LocalCueGen provides env) {
        A2uiSurface(surfaceModel = s, modifier = modifier)
    }
}
