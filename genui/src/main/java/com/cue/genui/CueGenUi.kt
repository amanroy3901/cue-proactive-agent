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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cue.ui.Cue
import com.cue.ui.Jakarta
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
        // One object carrying several messages (createSurface + updateComponents + updateDataModel): split it.
        val keys = listOf("createSurface", "updateComponents", "updateDataModel", "deleteSurface")
        val objs = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
        if (objs.any { o -> keys.count { o.has(it) } > 1 }) {
            val split = JSONArray()
            objs.forEach { o -> val present = keys.filter { o.has(it) }; if (present.size <= 1) split.put(o) else present.forEach { k -> split.put(JSONObject().put(k, o.get(k))) } }
            return feedAll(split, surfaceId)
        }
        // A sloppy answer sometimes lists the components themselves instead of A2UI messages: wrap them.
        val bare = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }.filter { it.has("component") && it.has("id") }
        if (bare.isNotEmpty()) {
            val envelopes = JSONArray()
            (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }.filter { !it.has("component") }.forEach { envelopes.put(it) }
            envelopes.put(JSONObject().put("updateComponents", JSONObject().put("components", JSONArray(bare))))
            return feedAll(envelopes, surfaceId)
        }
        bindForms(messages)
        var created = false
        for (i in 0 until messages.length()) {
            val raw = messages.optJSONObject(i) ?: continue
            // "version" first: the parser reads the envelope in order.
            val m = JSONObject().put("version", "v0.9").also { o -> raw.keys().forEach { k -> if (k != "version") o.put(k, raw.get(k)) } }
            m.optJSONObject("updateComponents")?.optJSONArray("components")?.let { evenRows(it) }
            listOf("createSurface", "updateComponents", "updateDataModel", "deleteSurface").forEach { k -> m.optJSONObject(k)?.put("surfaceId", surfaceId) }
            m.optJSONObject("createSurface")?.let { it.put("catalogId", CueCatalog.ID); created = true }
            if (!created && (m.has("updateComponents") || m.has("updateDataModel"))) {
                feed(JSONObject().put("version", "v0.9").put("createSurface", JSONObject().put("surfaceId", surfaceId).put("catalogId", CueCatalog.ID)).toString())
                created = true
            }
            feed(m.toString())
        }
    }

    /**
     * Inputs only render once the value they're bound to exists. Models often name the data differently from the
     * fields ("from_field" vs "/form/from") or skip it, so: every field bound to /form/x gets a value (a matching key
     * if there is one, otherwise a sensible empty one), and values are sent before the components that read them.
     */
    private fun bindForms(messages: JSONArray) {
        val objs = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
        val comps = objs.mapNotNull { it.optJSONObject("updateComponents")?.optJSONArray("components") }.flatMap { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        // An input with no binding is bound to /form/<its id>; a literal value becomes that binding's first value.
        val literals = JSONObject()
        comps.filter { it.optString("component") in setOf("TextField", "DateTimeInput", "ChoicePicker", "Slider", "CheckBox") }.forEach { c ->
            val v = c.opt("value")
            if (v !is JSONObject || !v.has("path")) {
                val id = c.optString("id")
                if (v != null && v != JSONObject.NULL) literals.put(id, v)
                c.put("value", JSONObject().put("path", "/form/$id"))
            }
        }
        val bound = comps.mapNotNull { c ->
            val path = c.optJSONObject("value")?.optString("path")?.takeIf { it.startsWith("/form/") } ?: return@mapNotNull null
            path.removePrefix("/form/") to c
        }
        if (bound.isEmpty()) return
        val dataMsg = objs.firstOrNull { it.optJSONObject("updateDataModel")?.optString("path")?.trimEnd('/') == "/form" }
        val data = dataMsg?.optJSONObject("updateDataModel")?.optJSONObject("value") ?: JSONObject()
        literals.keys().forEach { k -> if (!data.has(k)) data.put(k, literals.get(k)) }
        fun norm(k: String) = k.lowercase().replace(Regex("[^a-z]"), "")
        for ((key, c) in bound) {
            if (data.has(key)) continue
            val match = data.keys().asSequence().firstOrNull { k -> norm(k).startsWith(norm(key)) || norm(key).startsWith(norm(k)) }
            val value: Any = when {
                match != null -> data.get(match)
                c.optString("component") == "DateTimeInput" -> java.time.LocalDate.now().toString()
                c.optString("component") == "Slider" -> c.optDouble("min", 0.0).let { if (it % 1.0 == 0.0) it.toLong() else it }
                c.optString("component") == "ChoicePicker" -> JSONArray()
                c.optString("component") == "CheckBox" -> false
                else -> ""
            }
            data.put(key, value)
        }
        // ChoicePicker values are lists.
        for ((key, c) in bound) if (c.optString("component") == "ChoicePicker" && data.opt(key) is String) data.put(key, JSONArray().put(data.getString(key)))
        val fixed = JSONObject().put("updateDataModel", JSONObject().put("path", "/form").put("value", data))
        // Rebuild the order: surfaces first, then the values, then everything else.
        val rest = objs.filter { it !== dataMsg }
        val ordered = rest.filter { it.has("createSurface") } + fixed + rest.filter { !it.has("createSurface") }
        while (messages.length() > 0) messages.remove(0)
        ordered.forEach { messages.put(it) }
    }

    /**
     * A Row whose children carry no weight squeezes the last one (a date field ends up one letter wide): when none
     * of a Row's children has a weight, share the width evenly. Rows of stats, rings, fields and buttons all read
     * better that way.
     */
    private fun evenRows(components: JSONArray) {
        val byId = (0 until components.length()).mapNotNull { components.optJSONObject(it) }.associateBy { it.optString("id") }
        byId.values.filter { it.optString("component") == "Row" }.forEach { row ->
            val kids = row.optJSONArray("children")?.let { a -> (0 until a.length()).mapNotNull { byId[a.optString(it)] } }.orEmpty()
            if (kids.size >= 2 && kids.none { it.has("weight") }) kids.forEach { it.put("weight", 1) }
        }
    }

    fun close() { scope.launch { surfaces.value.forEach { feed(JSONObject().put("version", "v0.9").put("deleteSurface", JSONObject().put("surfaceId", it.id)).toString()) } } }
}

/** Draws the surface with [id] if the agent made it, in Cue's look; [framed] puts it on Cue's canvas card. */
@Composable
fun GenSurface(genUi: CueGenUi, surfaces: List<A2uiSurfaceModel>, id: String, env: CueGenEnv, modifier: Modifier = Modifier, framed: Boolean = true, dark: Boolean = false) {
    val s = surfaces.firstOrNull { it.id == id } ?: return
    CompositionLocalProvider(LocalCueGen provides env) {
        CueA2uiTheme(dark) {
            if (framed) CueCanvas(modifier, dark) { A2uiSurface(surfaceModel = s, modifier = Modifier.fillMaxWidth()) }
            else A2uiSurface(surfaceModel = s, modifier = modifier)
        }
    }
}

/**
 * Material, re-dressed as Cue: Plus Jakarta Sans at a calm scale (headings never shout), Cue's violet for actions,
 * soft rounded shapes and tinted surfaces. Every basic A2UI component (Text, Button, Card, fields, chips) draws
 * through this, so a generated screen looks like the rest of the app.
 */
@Composable
fun CueA2uiTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val ink = if (dark) Color(0xFFF2F0FF) else Cue.ink
    val muted = if (dark) Color(0xFFA7A3C7) else Cue.muted
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF9D86FF), onPrimary = Color.White, primaryContainer = Color(0xFF2E2560), onPrimaryContainer = ink,
        secondary = Color(0xFF4FD8F7), secondaryContainer = Color(0xFF243052), onSecondaryContainer = ink,
        surface = Color(0xFF15122E), onSurface = ink, onSurfaceVariant = muted, surfaceVariant = Color(0xFF221D44),
        surfaceContainerLowest = Color(0xFF15122E), surfaceContainerLow = Color(0xFF1B1738), surfaceContainer = Color(0xFF1F1A3F),
        surfaceContainerHigh = Color(0xFF241F48), surfaceContainerHighest = Color(0xFF2A2452), outline = Color(0xFF4A4378), outlineVariant = Color(0xFF332D5C),
    ) else lightColorScheme(
        primary = Cue.primaryDeep, onPrimary = Color.White, primaryContainer = Cue.primaryTint, onPrimaryContainer = Cue.primaryDeep,
        secondary = Cue.primary, secondaryContainer = Color(0xFFEDE8FF), onSecondaryContainer = Cue.primaryDeep,
        surface = Color.White, onSurface = ink, onSurfaceVariant = muted, surfaceVariant = Color(0xFFF4F1FC),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFAFF), surfaceContainer = Color(0xFFF7F5FE),
        surfaceContainerHigh = Color(0xFFF3F0FD), surfaceContainerHighest = Color(0xFFEFEBFC), outline = Color(0xFFD9D3EE), outlineVariant = Color(0xFFE9E4F7),
    )
    // No colour in the styles: text takes its container's colour (white on a primary button, ink on a card).
    fun t(size: Int, weight: Int, line: Int) = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp)
    val typography = Typography(
        displayLarge = t(30, 800, 36), displayMedium = t(26, 800, 32), displaySmall = t(24, 800, 30),
        headlineLarge = t(22, 800, 28), headlineMedium = t(20, 800, 26), headlineSmall = t(18, 800, 24),
        titleLarge = t(17, 800, 23), titleMedium = t(15, 700, 21), titleSmall = t(14, 700, 20),
        bodyLarge = t(15, 500, 22), bodyMedium = t(14, 500, 20), bodySmall = t(12, 500, 17),
        labelLarge = t(14, 700, 20), labelMedium = t(12, 700, 16), labelSmall = t(11, 700, 14),
    )
    val shapes = Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp))
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}

/** The frame a generated screen sits in: a soft card with a hairline gradient edge and a small "Cue" mark. */
@Composable
fun CueCanvas(modifier: Modifier = Modifier, dark: Boolean = false, content: @Composable () -> Unit) {
    val edge = Brush.linearGradient(listOf(Color(0x557C5CFF), Color(0x334FC3F7), Color(0x11FFFFFF)))
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(if (dark) Color(0xFF15122E) else Color.White.copy(alpha = 0.72f))
            .border(1.dp, edge, RoundedCornerShape(28.dp)).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Row(Modifier.padding(start = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFF4FC3F7)))))
            Spacer(Modifier.width(6.dp))
            Text("CUE · MADE FOR YOU", style = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight(800), fontSize = 10.sp, letterSpacing = 1.4.sp, color = if (dark) Color(0xFFA7A3C7) else Cue.muted))
        }
        content()
    }
}
