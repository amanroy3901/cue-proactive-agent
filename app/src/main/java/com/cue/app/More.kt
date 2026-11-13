package com.cue.app

import com.cue.brain.cloud.GeminiBrain
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AS
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.cue.agent.TextKit
import com.cue.domain.PlaceKind
import com.cue.domain.ReminderKind
import com.cue.triggers.PeopleListener
import com.cue.ui.*
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ permissions

data class Perm(val key: String, val title: String, val why: String, val fallback: String)

val PERMS = listOf(
    Perm("notif", "Notifications", "So I can ring you and show reminders", "Cue only works while open"),
    Perm("mic", "Microphone", "So you can talk to me on calls", "Calls become read-only briefings"),
    Perm("alarm", "Exact alarms", "So calls ring on time", "Calls may be a few minutes late"),
    Perm("fsi", "Full-screen calls", "So the call screen shows over the lock screen", "A call banner instead"),
    Perm("battery", "Unrestricted battery", "So Android doesn't stop my reminders", "Some may be delayed"),
    Perm("photos", "Photos", "So I can file your screenshots", "Share to Cue instead"),
    Perm("location", "Location, all the time", "So I can help when you arrive somewhere", "Time-based backups only"),
    Perm("overlay", "Show over other apps", "So I can pop a bubble at the right moment", "A notification instead"),
    Perm("listener", "Notification access", "So I remember what people asked you for", "People memory stays off"),
)

fun permGranted(c: Context, key: String): Boolean = when (key) {
    "notif" -> c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    "mic" -> c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    "alarm" -> c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    "fsi" -> c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    "battery" -> c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
    "photos" -> c.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    "location" -> c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    "overlay" -> AS.canDrawOverlays(c)
    "listener" -> AS.Secure.getString(c.contentResolver, "enabled_notification_listeners")?.contains(ComponentName(c, PeopleListener::class.java).flattenToString()) == true
    "sms" -> c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    else -> false
}

@Composable
fun PermissionList(compact: Boolean = false) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    fun request(key: String) {
        val pkg = Uri.parse("package:${c.packageName}")
        when (key) {
            "notif" -> runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            "mic" -> runtime.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            "alarm" -> c.startActivity(Intent(AS.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            "fsi" -> c.startActivity(Intent(AS.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            "battery" -> c.startActivity(Intent(AS.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            "photos" -> runtime.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
            "location" -> if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                runtime.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else runtime.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            "overlay" -> c.startActivity(Intent(AS.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            "listener" -> c.startActivity(Intent(AS.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "sms" -> runtime.launch(arrayOf(Manifest.permission.SEND_SMS))
        }
    }
    key(tick) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color.White).padding(6.dp)) {
            PERMS.forEach { p ->
                val ok = permGranted(c, p.key)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(enabled = !ok, role = Role.Button) { request(p.key) }.padding(horizontal = 12.dp, vertical = if (compact) 10.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (ok) Cue.tealBrush else Brush.linearGradient(listOf(Cue.coralTint, Cue.coralTint))), contentAlignment = Alignment.Center) {
                        Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.PriorityHigh, null, tint = if (ok) Color.White else Cue.coralText, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(p.title, Type.bodyStrong)
                        Txt(if (ok) p.why else "${p.why}. Without it: ${p.fallback.lowercase()}.", Type.small, Cue.muted, maxLines = 2)
                    }
                    if (!ok) Txt("Allow", Type.smallStrong, Cue.tealDeep)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ onboarding

@Composable
fun Onboarding(a: MainActivity, done: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val s = a.cue.settings
    var morning by remember { mutableIntStateOf(s.morningMinutes) }
    var night by remember { mutableIntStateOf(s.nightMinutes) }
    var name by remember { mutableStateOf(s.userName) }
    val scope = rememberCoroutineScope()
    val c = LocalContext.current
    GlowBackground(Color(0xFFFFD9C4), Color(0xFFD3F1E8)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i -> Box(Modifier.width(22.dp).height(6.dp).clip(CircleShape).background(if (i <= step) Cue.tealDeep else Color(0xFFDCD5CA))) }
                Spacer(Modifier.width(6.dp)); Txt("Step ${step + 1} of 3", Type.smallStrong, Cue.muted)
            }
            when (step) {
                0 -> {
                    Spacer(Modifier.height(20.dp))
                    CueOrb(96.dp, "Cue", ring = true)
                    Txt("I remember your life and show up on my own.", Type.display)
                    Txt("I ring you like a call at the right moment, or pop up when you arrive somewhere. Everything stays on this phone.", Type.body, Cue.muted)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Txt("What should I call you?", Type.small, Cue.muted)
                        BasicTextField(name, { name = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), textStyle = Type.title.copy(color = Cue.ink), singleLine = true, cursorBrush = SolidColor(Cue.tealDeep))
                    }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Let's set up", Modifier.fillMaxWidth()) { s.userName = name.ifBlank { "there" }; step = 1 }
                }
                1 -> {
                    Txt("When should I ring you?", Type.display)
                    Txt("Two short calls a day, about a minute each. I always ring first, and only talk once you pick up.", Type.body, Cue.muted)
                    TimeCard("Morning check-in", "Your plan and your list", morning, Brush.linearGradient(listOf(Color(0xFFFFE3C8), Color(0xFFFFB48E), Color(0xFFFF8E6B))), Color(0xFF3A1A14), Cue.coralDeep) {
                        TimePickerDialog(c, { _, h, m -> morning = h * 60 + m }, morning / 60, morning % 60, false).show()
                    }
                    TimeCard("Night check-in", "You talk, I file it", night, Cue.tealBrush, Color.White, Cue.tealNight) {
                        TimePickerDialog(c, { _, h, m -> night = h * 60 + m }, night / 60, night % 60, false).show()
                    }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Sounds good", Modifier.fillMaxWidth()) { s.morningMinutes = morning; s.nightMinutes = night; scope.launch { a.cue.engine.planner.reconcile() }; step = 2 }
                }
                else -> {
                    Txt("A few permissions", Type.display)
                    Txt("Each one has a reason. Skip any; I'll fall back to something simpler.", Type.body, Cue.muted)
                    Box(Modifier.weight(1f)) { androidx.compose.foundation.lazy.LazyColumn { item { PermissionList(compact = true) } } }
                    PrimaryButton("Start using Cue", Modifier.fillMaxWidth()) { scope.launch { a.cue.engine.planner.reconcile() }; done() }
                }
            }
        }
    }
}

@Composable
fun TimeCard(title: String, sub: String, minutes: Int, brush: Brush, fg: Color, shadow: Color, onClick: () -> Unit) {
    GradientCard(brush, shadow, Modifier.fillMaxWidth().height(132.dp), onClick = onClick) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) { Txt(title, Type.bodyStrong, fg); Txt(sub, Type.small, fg.copy(alpha = 0.8f)) }
            val t = java.time.LocalTime.of(minutes / 60, minutes % 60)
            Txt(t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm")), Type.big.copy(fontSize = sp(40f)), fg)
            Spacer(Modifier.width(4.dp))
            Txt(if (t.hour < 12) "AM" else "PM", Type.bodyStrong, fg, Modifier.padding(bottom = 6.dp))
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
fun SettingsScreen(a: MainActivity, nav: Nav) {
    val s = a.cue.settings
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var morning by remember { mutableIntStateOf(s.morningMinutes) }
    var night by remember { mutableIntStateOf(s.nightMinutes) }
    var calls by remember { mutableStateOf(s.callsEnabled) }
    var care by remember { mutableStateOf(s.careMode) }
    var cgName by remember { mutableStateOf(s.caregiverName) }
    var cgNumber by remember { mutableStateOf(s.caregiverNumber) }
    var brain by remember { mutableStateOf(s.brainPreference) }
    fun replan() { scope.launch { a.cue.engine.planner.reconcile() } }
    Page(glowA = Color(0xFFD3F1E8)) {
        item { BackHeader("Settings", null, { nav.back() }) }
        item { SectionLabel("Calls") }
        item {
            LiftCard(Modifier.fillMaxWidth()) {
                ToggleRow("Check-in calls", "Ring me morning and night", calls) { calls = it; s.callsEnabled = it; replan() }
                SettingRow("Morning call", TextKit.clock(TextKit.nextDaily(morning, System.currentTimeMillis(), java.time.ZoneId.systemDefault()), java.time.ZoneId.systemDefault())) {
                    TimePickerDialog(c, { _, h, m -> morning = h * 60 + m; s.morningMinutes = morning; replan() }, morning / 60, morning % 60, false).show() }
                SettingRow("Night call", TextKit.clock(TextKit.nextDaily(night, System.currentTimeMillis(), java.time.ZoneId.systemDefault()), java.time.ZoneId.systemDefault())) {
                    TimePickerDialog(c, { _, h, m -> night = h * 60 + m; s.nightMinutes = night; replan() }, night / 60, night % 60, false).show() }
            }
        }
        item { SectionLabel("Brain") }
        item {
            LiftCard(Modifier.fillMaxWidth()) {
                fun use(p: String) { brain = p; s.brainPreference = p; a.cue.router.applyPreference(p) }
                val offline = brain != "cloud"
                ToggleRow("Work offline", if (offline) "Nothing leaves the phone. Calls use the on-device brain below."
                    else "Calls use Google Gemini (live voice). Turn on to keep everything on the phone.", offline) { use(if (it) "lfm3" else "cloud") }
                if (!offline) {
                    val g = a.cue.gemini
                    Txt(when {
                        !g.configured -> "No Gemini key in this build. Add GEMINI_API_KEY to local.properties and rebuild. Calls work offline until then."
                        !online(c) -> "No internet right now: calls fall back to the on-device brain."
                        else -> "Online: ${GeminiBrain.LIVE_MODEL} for calls, ${GeminiBrain.TEXT_MODEL} for journals and filing."
                    }, Type.small, Cue.muted, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                } else {
                    BrainOption("LFM3-Omni-2B on the phone", a.cue.lfm3.status(), brain == "lfm3") { use("lfm3") }
                    BrainOption("Rules (instant, always works)", "Understands lists, notes, reminders, flights, medicines and more.", brain == "rules") { use("rules") }
                }
            }
        }
        item { SectionLabel("Places") }
        item {
            val places by a.cue.engine.dao.placesFlow().collectAsState(emptyList())
            LiftCard(Modifier.fillMaxWidth(), tint = AppTints.people.deep) {
                Txt("Stand at a place and tap to save it. I'll pop up when you arrive.", Type.small, Cue.muted)
                places.filter { it.kind in setOf(PlaceKind.HOME, PlaceKind.OFFICE, PlaceKind.GYM, PlaceKind.CLINIC) }.forEach { p ->
                    SettingRow(p.label, "Set to here") {
                        @android.annotation.SuppressLint("MissingPermission")
                        val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(c)
                        runCatching {
                            fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                                if (loc == null) { a.cue.engine.surfaces.toast("Couldn't get your location"); return@addOnSuccessListener }
                                scope.launch {
                                    a.cue.engine.dao.upsertPlace(p.copy(lat = loc.latitude, lng = loc.longitude))
                                    a.cue.engine.scheduler.registerGeofence(p.copy(lat = loc.latitude, lng = loc.longitude))
                                    a.cue.engine.log("${p.label} set to ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}")
                                    a.cue.engine.surfaces.toast("${p.label} saved")
                                }
                            }
                        }.onFailure { a.cue.engine.surfaces.toast("Allow location first") }
                    }
                }
            }
        }
        item { SectionLabel("Family circle") }
        item {
            LiftCard(Modifier.fillMaxWidth(), tint = AppTints.reminders.deep) {
                ToggleRow("Care mode", "For a parent's phone: medicine calls, no night call", care) { care = it; s.careMode = it; replan() }
                Txt("If two medicine calls go unanswered, I'll text:", Type.small, Cue.muted)
                InlineField("Caregiver's name", cgName) { cgName = it; s.caregiverName = it }
                InlineField("Caregiver's phone number", cgNumber) { cgNumber = it; s.caregiverNumber = it }
                if (cgNumber.isNotBlank() && !permGranted(c, "sms")) Txt("Allow SMS below so the nudge can be sent.", Type.small, Cue.coralText)
            }
        }
        item { SectionLabel("Permissions") }
        item { PermissionList() }
        item {
            val sms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            if (!permGranted(c, "sms")) SoftButton("Allow SMS for caregiver nudges", Modifier.fillMaxWidth(), color = Color.White) { sms.launch(Manifest.permission.SEND_SMS) }
        }
        item { SoftButton("Debug panel and trigger simulator", Modifier.fillMaxWidth(), color = Color.White) { nav.go("debug") } }
    }
}

@Composable fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Txt(title, Type.bodyStrong); Txt(sub, Type.small, Cue.muted) }
        Switch(value, onChange)
    }
}

@Composable fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, Type.body, modifier = Modifier.weight(1f)); Txt(value, Type.bodyStrong, Cue.tealDeep)
    }
}

@Composable fun BrainOption(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) Cue.tealTint else Color.Transparent).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (selected) Cue.tealDeep else Color(0xFFDCD5CA)))
        Spacer(Modifier.width(12.dp))
        Column { Txt(title, Type.bodyStrong); Txt(sub, Type.small, Cue.muted, maxLines = 3) }
    }
}

@Composable fun InlineField(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF7F3EC)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Txt(label, Type.tiny, Cue.muted)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().padding(vertical = 4.dp), textStyle = Type.body.copy(color = Cue.ink), singleLine = true, cursorBrush = SolidColor(Cue.tealDeep))
    }
}

// ------------------------------------------------------------------ debug and simulator

@Composable
fun DebugScreen(a: MainActivity, nav: Nav) {
    val engine = a.cue.engine
    val scope = rememberCoroutineScope()
    val log by engine.logLines.collectAsState()
    val c = LocalContext.current
    fun run(block: suspend () -> Unit) { scope.launch { runCatching { block() }.onFailure { engine.log("simulator error: ${it.message}") } } }
    Page(glowA = Color(0xFFE4DCF7)) {
        item { BackHeader("Debug", "Fire any trigger now. The demo never waits for GPS or the clock.", { nav.back() }) }
        item { SectionLabel("Data") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Load demo data") { run { Demo.seed(engine) } }
                Chip("Drop ticket screenshot") { if (Demo.dropTicketScreenshot(c, engine)) engine.log("sample ticket screenshot saved; watcher will file it") }
                Chip("Reconcile triggers") { run { engine.log("reconcile: " + engine.planner.reconcile()) } }
                Chip("Wipe everything") { run { Demo.reset(c); engine.ensureDefaults(); engine.log("all data wiped") } }
            }
        }
        item { SectionLabel("On-device model (LFM3-Omni-2B)") }
        item {
            var status by remember { mutableStateOf(a.cue.lfm3.status()) }
            LaunchedEffect(Unit) { while (true) { status = a.cue.lfm3.status(); kotlinx.coroutines.delay(1000) } }
            LiftCard(Modifier.fillMaxWidth()) {
                Txt(status, Type.small, Cue.muted)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Load model now") { run { engine.log("loading LFM3…"); val ok = a.cue.lfm3.prepare(); engine.log(if (ok) "LFM3 loaded in ${a.cue.lfm3.lastLoadMs / 1000} s" else "LFM3 failed: ${a.cue.lfm3.lastError}") } }
                    Chip("Use it on calls") { a.cue.settings.brainPreference = "lfm3"; a.cue.router.applyPreference("lfm3"); engine.log("calls now use LFM3 when it is loaded") }
                    Chip("Rules only") { a.cue.settings.brainPreference = "rules"; a.cue.router.applyPreference("rules"); engine.log("calls use the rules brain") }
                    Chip("Unload model") { run { a.cue.lfm3.release(); engine.log("LFM3 unloaded") } }
                }
            }
        }
        item { SectionLabel("Ring now") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Morning" to ReminderKind.CHECKIN_MORNING, "Night" to ReminderKind.CHECKIN_NIGHT, "Leave for flight" to ReminderKind.LEAVE_BY,
                    "Medicine" to ReminderKind.DOSE, "Gym check" to ReminderKind.ACCOUNTABILITY, "Warranty" to ReminderKind.EXPIRY,
                    "Practice" to ReminderKind.PRACTICE, "Parent's medicine" to ReminderKind.CARE).forEach { (label, kind) ->
                    Chip(label) { run { Demo.ringNow(engine, kind) } }
                }
                Chip("Missed dose retry") { run { Demo.missedDose(engine) } }
            }
        }
        item { SectionLabel("Arrive at") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Airport" to PlaceKind.AIRPORT, "Home" to PlaceKind.HOME, "Office" to PlaceKind.OFFICE, "Gym" to PlaceKind.GYM, "Clinic" to PlaceKind.CLINIC).forEach { (label, kind) ->
                    Chip(label) { run { Demo.arrive(engine, kind) } }
                }
            }
        }
        item { SectionLabel("Preview a bubble (overlays are hidden while the phone is locked)") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Airport" to PlaceKind.AIRPORT, "Home" to PlaceKind.HOME, "Clinic" to PlaceKind.CLINIC, "Gym" to PlaceKind.GYM).forEach { (label, kind) ->
                    Chip("$label bubble") { run { val p = engine.dao.places().firstOrNull { it.kind == kind }; val spec = p?.let { engine.bubbleForPlace(it) }; if (spec == null) engine.log("nothing to show at $label"); a.bubblePreview = spec } }
                }
                Chip("Rahul bubble") { run {
                    val p = engine.person("Rahul"); val open = engine.dao.openPending(p.id)
                    a.bubblePreview = com.cue.domain.BubbleSpec("WhatsApp", "Rahul messaged. Still open with Rahul", null, open.map { com.cue.domain.BubbleItem(it.id, it.text, "pending") }, personId = p.id)
                } }
            }
        }
        item { SectionLabel("Messages") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Rahul: send the deck?") { run { engine.onNotification("com.whatsapp", "Rahul", "Can you send the deck before tomorrow?") } }
                Chip("Priya: you owe me 500") { run { engine.onNotification("com.whatsapp", "Priya", "You owe me 500 for dinner") } }
            }
        }
        item { SectionLabel("Event log") }
        items(log.take(80)) { line -> Txt(line, Type.small.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), Cue.ink) }
    }
}

@Composable fun Chip(text: String, onClick: () -> Unit) {
    Box(Modifier.height(40.dp).clip(CircleShape).background(Color.White).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Txt(text, Type.smallStrong, Cue.tealDeep)
    }
}
