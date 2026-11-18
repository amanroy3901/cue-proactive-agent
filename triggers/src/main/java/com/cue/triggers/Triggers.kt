package com.cue.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cue.agent.CueRuntime
import com.cue.domain.Place
import com.cue.domain.Reminder
import com.cue.domain.ReminderKind
import com.cue.domain.TriggerScheduler
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch

private const val TAG = "CueTriggers"

/** Registers alarms and geofences with Android. Plain code: costs nothing while waiting. */
class AndroidScheduler(private val context: Context) : TriggerScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val geofencing by lazy { LocationServices.getGeofencingClient(context) }

    private fun alarmIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(), Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM).putExtra(EXTRA_REMINDER, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun schedule(reminder: Reminder) {
        if (reminder.id == 0L) return
        val pi = alarmIntent(reminder.id)
        val at = maxOf(reminder.fireAt, System.currentTimeMillis() + 1_000)
        try {
            val userFacing = reminder.kind == ReminderKind.CHECKIN_MORNING || reminder.kind == ReminderKind.CHECKIN_NIGHT
            when {
                userFacing -> alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pi)
                alarms.canScheduleExactAlarms() -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    override fun cancel(reminderId: Long) = alarms.cancel(alarmIntent(reminderId))

    private val geofencePi: PendingIntent by lazy {
        PendingIntent.getBroadcast(context, 7001, Intent(context, GeofenceReceiver::class.java).setAction(ACTION_GEOFENCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    @SuppressLint("MissingPermission")
    override fun registerGeofence(place: Place) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine || !bg) return
        val fence = Geofence.Builder()
            .setRequestId("place:${place.id}")
            .setCircularRegion(place.lat, place.lng, place.radiusM.coerceAtLeast(100f))
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setLoiteringDelay(30_000)
            .build()
        val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
        runCatching {
            geofencing.addGeofences(req, geofencePi)
                .addOnSuccessListener { Log.i(TAG, "geofence registered: ${place.label} (${place.radiusM.toInt()} m)") }
                .addOnFailureListener { Log.w(TAG, "geofence ${place.label} failed: ${it.message}") }
        }
    }

    override fun removeGeofence(placeId: Long) {
        runCatching { geofencing.removeGeofences(listOf("place:$placeId")) }
    }

    companion object {
        const val ACTION_ALARM = "com.cue.ALARM"
        const val ACTION_GEOFENCE = "com.cue.GEOFENCE"
        const val EXTRA_REMINDER = "reminderId"
    }
}

/** Runs suspend work from a receiver without blocking the main thread. */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CueRuntime.scope.launch {
        try { if (CueRuntime.ready) block() } catch (t: Throwable) { Log.e(TAG, "trigger failed", t) } finally { pending.finish() }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AndroidScheduler.EXTRA_REMINDER, -1)
        if (id <= 0) return
        async { CueRuntime.engine.onReminderFired(id) }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val ids = event.triggeringGeofences.orEmpty().mapNotNull { it.requestId.removePrefix("place:").toLongOrNull() }
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> async { ids.forEach { CueRuntime.engine.onGeofence(it) } }
            Geofence.GEOFENCE_TRANSITION_EXIT -> async { ids.forEach { CueRuntime.engine.onGeofenceExit(it) } }
        }
    }
}

/** Reboot, app update and clock changes all silently remove triggers; reconcile puts them back. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        async { CueRuntime.engine.planner.reconcile(); CueRuntime.engine.log("reconciled after ${intent.action?.substringAfterLast('.')}") }
        ScreenshotWatch.enqueue(context)
    }
}

/**
 * Watches the screenshots folder. A ContentObserver catches new screenshots while the process is alive; a WorkManager
 * content-URI job wakes the app when it is not.
 */
object ScreenshotWatch {
    private const val PREFS = "cue_screenshots"
    private var observer: ContentObserver? = null

    fun start(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { CueRuntime.scope.launch { scan(app) } }
        }.also { app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, it) }
        enqueue(app)
    }

    fun enqueue(context: Context) {
        val req = OneTimeWorkRequestBuilder<ScreenshotWorker>()
            .setConstraints(Constraints.Builder().addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("cue-screenshots", ExistingWorkPolicy.REPLACE, req)
    }

    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Finds screenshots newer than the last one handled and hands each to ingest. */
    suspend fun scan(context: Context) = lock.withLockSafe {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) return@withLockSafe
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong("last", System.currentTimeMillis() / 1000 - 60)
        var newest = since
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.IS_PENDING)
        val found = mutableListOf<Pair<Uri, String>>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Images.Media.DATE_ADDED} > ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf(since.toString(), "%Screenshot%"),
            "${MediaStore.Images.Media.DATE_ADDED} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(4) == 1) continue
                val id = c.getLong(0)
                newest = maxOf(newest, c.getLong(1))
                found += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id) to c.getString(3)
            }
        }
        prefs.edit().putLong("last", newest).apply()
        for ((uri, name) in found) {
            val path = copyToPrivate(context, uri, name) ?: continue
            CueRuntime.ingestFile?.invoke(path, "screenshot", name)
        }
    }

    fun copyToPrivate(context: Context, uri: Uri, name: String): String? = runCatching {
        val dir = java.io.File(context.filesDir, "media").apply { mkdirs() }
        val ext = name.substringAfterLast('.', "jpg").lowercase().take(5)
        val f = java.io.File(dir, "in-${System.nanoTime()}.$ext")
        context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        f.absolutePath
    }.onFailure { Log.w(TAG, "copy failed $uri", it) }.getOrNull()
}

private suspend inline fun <T> kotlinx.coroutines.sync.Mutex.withLockSafe(crossinline block: suspend () -> T): T {
    lock(); try { return block() } finally { unlock() }
}

class ScreenshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ScreenshotWatch.scan(applicationContext)
        ScreenshotWatch.enqueue(applicationContext)
        return Result.success()
    }
}

/**
 * People memory: reads message notifications to learn what is pending with each person. Needs notification access,
 * which the user grants with a clear consent screen.
 */
class PeopleListener : NotificationListenerService() {
    private val apps = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging",
        "org.thoughtcrime.securesms", "com.instagram.android", "com.facebook.orca", "com.slack", "com.microsoft.teams")

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val messaging = sbn.packageName in apps || n.category == Notification.CATEGORY_MESSAGE
        if (!messaging) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() || text.isBlank()) return
        val sender = title.substringBefore(" (").substringBefore(":").trim()
        if (!CueRuntime.ready) return
        CueRuntime.scope.launch { runCatching { CueRuntime.engine.onNotification(sbn.packageName, sender, text) } }
    }
}
