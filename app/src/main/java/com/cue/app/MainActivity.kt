package com.cue.app

import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.cue.call.CallActivity
import com.cue.call.RingController
import com.cue.ui.AppTint
import com.cue.ui.AppTints
import com.cue.ui.CueBottomBar
import com.cue.ui.CueTheme
import com.cue.ui.NavEntry

/** Every app Cue runs. Users pin up to three to the bottom bar; the rest live on the Apps screen. */
data class AppDef(val key: String, val label: String, val icon: ImageVector, val tint: AppTint)

object AppCatalog {
    val all = listOf(
        AppDef("ask", "Ask Cue", Icons.Rounded.AutoAwesome, AppTints.focus),
        AppDef("lists", "Lists", Icons.Rounded.Checklist, AppTints.lists),
        AppDef("notes", "Notes", Icons.Rounded.EditNote, AppTints.habits),
        AppDef("habits", "Habits", Icons.Rounded.LocalFireDepartment, AppTints.habits),
        AppDef("journal", "Journal", Icons.Rounded.Book, AppTints.journal),
        AppDef("albums", "Spaces", Icons.Rounded.PhotoLibrary, AppTints.albums),
        AppDef("reminders", "Reminders", Icons.Rounded.Alarm, AppTints.reminders),
        AppDef("papers", "Papers", Icons.Rounded.Description, AppTints.papers),
        AppDef("renewals", "Renewals", Icons.Rounded.Autorenew, AppTints.renewals),
        AppDef("people", "People", Icons.Rounded.People, AppTints.people),
        AppDef("practice", "Practice", Icons.Rounded.RecordVoiceOver, AppTints.practice),
        AppDef("focus", "Focus", Icons.Rounded.Timer, AppTints.focus),
        AppDef("search", "Search", Icons.Rounded.Search, AppTints.lists),
    )
    fun get(key: String) = all.firstOrNull { it.key == key }
    /** What the Apps screen lists: Spaces and Ask have their own tabs in the bar. */
    val apps get() = all.filter { it.key !in setOf("albums", "ask") }
}

/** A tiny back stack. Routes are strings like "albums" or "album/12". */
class Nav {
    val stack = mutableStateListOf("home")
    val current: String get() = stack.last()
    fun go(route: String) { if (route == "home") { stack.clear(); stack += "home" } else if (current != route) stack += route }
    fun tab(route: String) { stack.clear(); stack += "home"; if (route != "home") stack += route }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

class MainActivity : ComponentActivity() {
    private val nav = Nav()
    private var onboarded by mutableStateOf(true)
    /** Apps pinned to Home's quick row. */
    var pins by mutableStateOf(listOf<String>())
        private set
    /** Debug preview of the bubble, since Android hides overlays under the lock screen. */
    var bubblePreview by mutableStateOf<com.cue.domain.BubbleSpec?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds only: let the app show over the lock screen so it can be tested on a locked phone.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val s = cue.settings
        onboarded = s.onboarded
        pins = s.pinnedApps
        // If calls use the on-device model, start loading it now so the next call can use it.
        if (s.brainPreference == "lfm3" && !cue.lfm3.isReady() && !cue.lfm3.loading) {
            com.cue.agent.CueRuntime.scope.launch { cue.engine.log("pre-loading LFM3"); val ok = cue.lfm3.prepare(); cue.engine.log(if (ok) "LFM3 ready (${cue.lfm3.lastLoadMs / 1000} s)" else "LFM3 not loaded: ${cue.lfm3.lastError}") }
        }
        handle(intent)
        setContent { CueTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        val album = i?.getLongExtra("albumId", -1L) ?: -1L
        if (album > 0) nav.go("album/$album")
        i?.getStringExtra("route")?.let { nav.go(it) }
    }

    fun talk(kind: String = "TALK") {
        startActivity(Intent(this, CallActivity::class.java).putExtra(RingController.EXTRA_KIND, kind).putExtra(RingController.EXTRA_ACCEPT, true)
            .putExtra(RingController.EXTRA_TITLE, if (kind == "PRACTICE") "English practice" else "Talk to Cue").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Ask Cue's conversation and its generated screens; lives as long as the activity. */
    val askSession by lazy { AskSession(this) }

    /** Opens one of Cue's screens by route ("lists", "album/12"); generated screens use it. */
    fun openRoute(route: String) { if (route.isNotBlank()) nav.go(route) }

    fun updatePins(list: List<String>) { cue.settings.pinnedApps = list; pins = cue.settings.pinnedApps }

    @Composable
    private fun Root() {
        if (!onboarded) {
            Onboarding(this) { cue.settings.onboarded = true; onboarded = true }
            return
        }
        BackHandler(enabled = nav.stack.size > 1) { nav.back() }
        Box(Modifier.fillMaxSize()) {
            Route(nav.current)
            val showBar = nav.current.substringBefore('/') !in setOf("settings", "debug", "knows", "ask")
            if (showBar) {
                // Fixed tabs: Spaces is a place of its own, not one app among many. Pinned apps sit on Home.
                val selected = nav.current.substringBefore('/').let { if (it == "album") "albums" else if (it in setOf("home", "albums", "ask")) it else "apps" }
                CueBottomBar(
                    listOf(NavEntry("home", "Home", Icons.Rounded.Home), NavEntry("albums", "Spaces", Icons.Rounded.Layers)),
                    listOf(NavEntry("ask", "Ask", Icons.Rounded.AutoAwesome), NavEntry("apps", "Apps", Icons.Rounded.GridView)),
                    selected, onSelect = { nav.tab(it) }, onTalk = { talk() }, modifier = Modifier.align(Alignment.BottomCenter))
            }
            bubblePreview?.let { spec ->
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xAA2A2445)).clickable { bubblePreview = null }, contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.navigationBarsPadding()) {
                        com.cue.surfaces.BubbleCard(spec, true, onToggle = {}, onDismiss = { bubblePreview = null }, onOpen = { bubblePreview = null; spec.albumId?.let { nav.go("album/$it") } })
                    }
                }
            }
        }
    }

    @Composable
    private fun Route(route: String) {
        val head = route.substringBefore('/')
        val arg = route.substringAfter('/', "").toLongOrNull()
        when (head) {
            "home" -> HomeScreen(this, nav)
            "apps" -> AppsScreen(this, nav, pins)
            "lists" -> ListsScreen(this, nav)
            "notes" -> NotesScreen(this, nav)
            "ask" -> AskScreen(this, nav)
            "habits" -> HabitsScreen(this, nav)
            "journal" -> JournalScreen(this, nav)
            "albums" -> AlbumsScreen(this, nav)
            "album" -> SpaceScreen(this, nav, arg ?: 0)
            "reminders" -> RemindersScreen(this, nav)
            "papers" -> PapersScreen(this, nav)
            "renewals" -> RenewalsScreen(this, nav)
            "people" -> PeopleScreen(this, nav)
            "practice" -> PracticeScreen(this, nav)
            "focus" -> FocusScreen(this, nav)
            "search" -> SearchScreen(this, nav)
            "settings" -> SettingsScreen(this, nav)
            "knows" -> KnowsScreen(this, nav)
            "debug" -> DebugScreen(this, nav)
            else -> HomeScreen(this, nav)
        }
    }
}
