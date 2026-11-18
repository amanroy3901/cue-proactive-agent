package com.cue.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cue.genui.CueGenEnv
import com.cue.genui.CueGenUi
import com.cue.genui.GenSurface
import com.cue.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A generated screen that lives on a page (the top of a space, Home's brief): Cue looks at the user's data, decides
 * what matters, and draws it in A2UI. Made once and kept for half an hour; Refresh asks again. Its buttons run
 * Cue's tools here, or carry on the conversation in Ask Cue.
 */
class GenBlock(private val a: MainActivity, val key: String, private val purpose: ScreenMaker.Purpose, private val albumId: Long?, private val request: () -> String) {
    enum class State { IDLE, LOADING, READY, FAILED }

    var state by mutableStateOf(State.IDLE)
        private set
    var say by mutableStateOf("")
        private set
    var thoughts by mutableStateOf(listOf<String>())
        private set
    private var madeAt = 0L
    private var version = 0
    val surfaceId: String get() = "$key-$version"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val genUi: CueGenUi = CueGenUi(
        onAction = { act -> scope.launch { handle(act.name, act.args) } },
        onError = { sid, err -> scope.launch { a.screens.repair(genUi, sid, err, purpose, emptyList()) } },
    )
    val env = object : CueGenEnv {
        override val dao get() = a.cue.engine.dao
        override fun open(route: String) = a.openRoute(route)
    }

    /** Makes the screen if it's missing or older than half an hour (or [force]). */
    fun ensure(force: Boolean = false) {
        if (state == State.LOADING) return
        if (!force && state == State.READY && System.currentTimeMillis() - madeAt < 30 * 60_000) return
        if (!a.cue.gemini.configured) { state = State.FAILED; return }
        state = State.LOADING
        scope.launch {
            version++
            val made = runCatching { a.screens.make(genUi, surfaceId, request(), purpose, contextAlbum = albumId) }.getOrNull()
            if (made?.hasUi == true) { say = made.say; thoughts = made.thoughts; madeAt = System.currentTimeMillis(); state = State.READY } else state = State.FAILED
        }
    }

    private suspend fun handle(name: String, args: Map<String, String>): Unit {
        when (name) {
            // A follow-up becomes a conversation in Ask Cue, about this space.
            "ask" -> {
                val extra = args.filterKeys { it != "text" }.filterValues { it.isNotBlank() && it != "null" }
                val s = a.askSession
                albumId?.let { s.contextAlbum = it }
                a.openRoute("ask")
                s.ask(args["text"].orEmpty() + if (extra.isEmpty()) "" else "\n" + extra.entries.joinToString("\n") { (k, v) -> "$k: $v" })
            }
            "open" -> a.openRoute(args["route"].orEmpty())
            else -> {
                val r = a.tool(name, args.filterValues { it.isNotBlank() })
                a.cue.engine.surfaces.toast(r.message.replaceFirstChar { it.uppercase() })
                ensure(force = true)
            }
        }
    }
}

/** Draws a [GenBlock]: a "Cue's take" header with Refresh, a thinking shimmer while it works, then the screen. */
@Composable
fun GenBlockView(block: GenBlock, title: String, thinkingLine: String, modifier: Modifier = Modifier) {
    LaunchedEffect(block.key) { block.ensure() }
    val surfaces by block.genUi.surfaces.collectAsState()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFF7C5CFF), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Txt(title, Type.smallStrong, Cue.primaryDeep, modifier = Modifier.weight(1f))
            if (block.state != GenBlock.State.LOADING) Box(Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Refresh") { block.ensure(force = true) },
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Refresh, "Refresh", tint = Cue.muted, modifier = Modifier.size(18.dp)) }
        }
        AnimatedContent(block.state, label = "gen") { st ->
            when (st) {
                GenBlock.State.READY -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (block.say.isNotBlank()) Txt(block.say, Type.body, Cue.ink)
                    GenSurface(block.genUi, surfaces, block.surfaceId, block.env, Modifier.fillMaxWidth())
                }
                GenBlock.State.FAILED -> Txt("I'll put my take here when I'm online.", Type.small, Cue.muted)
                else -> ThinkingShimmer(thinkingLine)
            }
        }
    }
}

/** A placeholder card that sweeps while Cue thinks. */
@Composable
fun ThinkingShimmer(line: String) {
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "s")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.7f))
            .border(1.dp, Brush.linearGradient(listOf(Color(0x557C5CFF), Color(0x334FC3F7))), RoundedCornerShape(28.dp))
            .drawBehind {
                val x = size.width * (sweep * 1.6f - 0.6f)
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x227C5CFF), Color(0x224FC3F7), Color.Transparent), startX = x, endX = x + size.width * 0.6f))
            }.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(line, Type.smallStrong, Cue.primaryDeep)
        listOf(0.9f, 0.7f, 0.8f).forEach { w -> Box(Modifier.fillMaxWidth(w).height(12.dp).clip(CircleShape).background(Color(0xFFEDE8FF))) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { repeat(2) { Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFF3F0FD))) } }
    }
}
