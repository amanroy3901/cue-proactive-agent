package com.cue.app

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings as AndroidSettings
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import com.cue.agent.CueEngine
import com.cue.agent.CueRuntime
import com.cue.agent.DeviceState
import com.cue.brain.BrainRouter
import com.cue.brain.ondevice.Lfm3Brain
import com.cue.brain.rules.RulesBrain
import com.cue.call.RingController
import com.cue.domain.BubbleSpec
import com.cue.domain.CallRequest
import com.cue.domain.CaregiverNotifier
import com.cue.domain.Settings
import com.cue.domain.SurfacePresenter
import com.cue.domain.SystemClock
import com.cue.ingest.Ingest
import com.cue.memory.CueDatabase
import com.cue.surfaces.BubbleService
import com.cue.surfaces.Notifier
import com.cue.triggers.AndroidScheduler
import com.cue.triggers.ScreenshotWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CueGraph: a hand-written service locator. Builds every component once, wires the platform implementations of the
 * domain ports, and installs the engine where receivers and services can find it.
 */
class CueApp : Application() {
    lateinit var engine: CueEngine
        private set
    lateinit var settings: PrefSettings
        private set
    lateinit var lfm3: Lfm3Brain
        private set
    lateinit var router: BrainRouter
        private set

    override fun onCreate() {
        super.onCreate()
        settings = PrefSettings(this)
        val dao = CueDatabase.get(this).dao()
        val rules = RulesBrain()
        lfm3 = Lfm3Brain(this)
        router = BrainRouter(mapOf("rules" to rules, "lfm3" to lfm3), rules).apply { applyPreference(settings.brainPreference) }
        engine = CueEngine(
            dao = dao,
            clock = SystemClock,
            settings = settings,
            scheduler = AndroidScheduler(this),
            surfaces = AppSurfaces(this),
            router = router,
            caregiver = SmsCaregiver(this, settings),
            device = { readDevice(this, settings) },
            logger = { Log.i("Cue", it) },
        )
        CueRuntime.install(engine)
        CueRuntime.ingestFile = { path, source, name -> Ingest.process(this, path, source, name) }
        RingController.ensureChannel(this)
        ScreenshotWatch.start(this)
        CueRuntime.scope.launch { engine.ensureDefaults() }
    }

    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); trim(level) }
}

/** Hands the 5 GB back when Android asks for memory and no call is live. */
fun CueApp.trim(level: Int) {
    if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && lfm3.isReady() && engine.activeCall == null) {
        CueRuntime.scope.launch { lfm3.release(); engine.log("released the on-device model (memory pressure)") }
    }
}

fun BrainRouter.applyPreference(pref: String) {
    val p = if (pref == "lfm3") "lfm3" else "rules"
    converse = p; file = p; enrich = "rules"
}

val Context.cue: CueApp get() = applicationContext as CueApp

/** Reads what the situation engine needs from Android at decision time. */
fun readDevice(context: Context, settings: Settings): DeviceState {
    val km = context.getSystemService(KeyguardManager::class.java)
    val am = context.getSystemService(AudioManager::class.java)
    val nm = context.getSystemService(NotificationManager::class.java)
    val bm = context.getSystemService(BatteryManager::class.java)
    val minutes = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    val qs = settings.quietStartMinutes
    val qe = settings.quietEndMinutes
    val quiet = if (qs == qe) false else if (qs < qe) minutes in qs until qe else minutes >= qs || minutes < qe
    return DeviceState(
        locked = km.isKeyguardLocked,
        onPhoneCall = am.mode == AudioManager.MODE_IN_CALL,
        doNotDisturb = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL,
        batteryLow = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 1..14,
        overlayAllowed = AndroidSettings.canDrawOverlays(context),
        fullScreenAllowed = nm.canUseFullScreenIntent(),
        inQuietHours = quiet,
        callActive = CueRuntime.ready && CueRuntime.engine.activeCall != null,
    )
}

/** Wires the engine's surfaces to the call, bubble and notification modules. */
class AppSurfaces(private val context: Context) : SurfacePresenter {
    override fun ring(request: CallRequest) = RingController.ring(context, request)

    override fun bubble(spec: BubbleSpec) {
        if (!BubbleService.show(context, spec)) Notifier.post(context, spec.headline, spec.items.joinToString(" · ") { it.text }, "bubble", openApp())
    }

    override fun notify(title: String, text: String, tag: String) = Notifier.post(context, title, text, tag, openApp())

    override fun toast(text: String) {
        CueRuntime.scope.launch { withContext(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() } }
    }

    private fun openApp(): Intent? = context.packageManager.getLaunchIntentForPackage(context.packageName)
}

/** Family circle: two missed medicine calls send the caregiver a short SMS, only if a number is configured. */
class SmsCaregiver(private val context: Context, private val settings: Settings) : CaregiverNotifier {
    override fun nudge(text: String): Boolean {
        val number = settings.caregiverNumber.filter { it.isDigit() || it == '+' }
        if (number.length < 6) return false
        if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Notifier.post(context, "Couldn't nudge ${settings.caregiverName.ifBlank { "your caregiver" }}", "Allow SMS in Cue settings so missed medicine calls reach them.", "caregiver", null)
            return false
        }
        return runCatching { context.getSystemService(SmsManager::class.java).sendTextMessage(number, null, text, null, null) }.isSuccess
    }
}

/** User settings over SharedPreferences. */
class PrefSettings(context: Context) : Settings {
    private val p = context.getSharedPreferences("cue_settings", Context.MODE_PRIVATE)
    override var userName: String get() = p.getString("name", "Aman")!!; set(v) = p.edit().putString("name", v).apply()
    override var morningMinutes: Int get() = p.getInt("morning", 7 * 60 + 30); set(v) = p.edit().putInt("morning", v).apply()
    override var nightMinutes: Int get() = p.getInt("night", 22 * 60); set(v) = p.edit().putInt("night", v).apply()
    override var callsEnabled: Boolean get() = p.getBoolean("calls", true); set(v) = p.edit().putBoolean("calls", v).apply()
    override var caregiverName: String get() = p.getString("cgName", "")!!; set(v) = p.edit().putString("cgName", v).apply()
    override var caregiverNumber: String get() = p.getString("cgNumber", "")!!; set(v) = p.edit().putString("cgNumber", v).apply()
    override var careMode: Boolean get() = p.getBoolean("care", false); set(v) = p.edit().putBoolean("care", v).apply()
    override var practiceEnabled: Boolean get() = p.getBoolean("practice", false); set(v) = p.edit().putBoolean("practice", v).apply()
    override var practiceMinutes: Int get() = p.getInt("practiceAt", 19 * 60); set(v) = p.edit().putInt("practiceAt", v).apply()
    override var pinnedApps: List<String>
        get() = p.getString("pins", "lists,habits")!!.split(",").filter { it.isNotBlank() }
        set(v) = p.edit().putString("pins", v.take(3).joinToString(",")).apply()
    override var onboarded: Boolean get() = p.getBoolean("onboarded", false); set(v) = p.edit().putBoolean("onboarded", v).apply()
    override var brainPreference: String get() = p.getString("brain", "rules")!!; set(v) = p.edit().putString("brain", v).apply()
    override var quietStartMinutes: Int get() = p.getInt("quietStart", 23 * 60); set(v) = p.edit().putInt("quietStart", v).apply()
    override var quietEndMinutes: Int get() = p.getInt("quietEnd", 6 * 60); set(v) = p.edit().putInt("quietEnd", v).apply()
}
