package com.cue.surfaces

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.cue.agent.CueRuntime
import com.cue.domain.BubbleSpec
import com.cue.ui.CheckRow
import com.cue.ui.Cue
import com.cue.ui.PixelHead
import com.cue.ui.CueTheme
import com.cue.ui.Pill
import com.cue.ui.PrimaryButton
import com.cue.ui.SoftButton
import com.cue.ui.Txt
import com.cue.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Plain notifications: the fallback for every other surface. */
object Notifier {
    private const val CHANNEL = "cue_updates"

    fun post(context: Context, title: String, text: String, tag: String, open: Intent?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cue updates", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = open?.let { PendingIntent.getActivity(context, tag.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val n = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setColor(0xFF4B2FB5.toInt()).setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }.build()
        runCatching { nm.notify(tag, 1, n) }
    }
}

/**
 * The bubble: an overlay card above whatever app is open. Owns a short-lived foreground service, removes itself
 * after two minutes, and never needs the model: everything comes from local data.
 */
class BubbleService : LifecycleService(), SavedStateRegistryOwner {
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    private var view: ComposeView? = null
    private var expanded by mutableStateOf(true)

    override fun onCreate() {
        savedState.performRestore(null)
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val ch = "cue_bubble"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ch) == null) nm.createNotificationChannel(NotificationChannel(ch, "Cue bubble", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, ch).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Cue is showing a bubble").build()
        runCatching { ServiceCompat.startForeground(this, 78, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        val spec = pending ?: run { stopSelf(); return START_NOT_STICKY }
        pending = null
        show(spec)
        return START_NOT_STICKY
    }

    private fun show(spec: BubbleSpec) {
        val wm = getSystemService(WindowManager::class.java)
        view?.let { runCatching { wm.removeView(it) } }
        expanded = true
        val cv = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BubbleService)
            setViewTreeSavedStateRegistryOwner(this@BubbleService)
            setContent { CueTheme { Bubble(spec) } }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        runCatching { wm.addView(cv, lp); view = cv }.onFailure { stopSelf() }
        lifecycleScope.launch { delay(120_000); dismiss() }
    }

    private fun dismiss() {
        view?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
        stopSelf()
    }

    @Composable
    private fun Bubble(spec: BubbleSpec) {
        BubbleCard(spec, expanded, onToggle = { expanded = !expanded }, onDismiss = { dismiss() }, onOpen = {
            packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("albumId", spec.albumId ?: -1L)) }
            dismiss()
        })
    }

    companion object {
        @Volatile private var pending: BubbleSpec? = null

        fun canShow(context: Context) = Settings.canDrawOverlays(context)

        fun show(context: Context, spec: BubbleSpec): Boolean {
            if (!canShow(context)) return false
            pending = spec
            return runCatching { context.startForegroundService(Intent(context, BubbleService::class.java)) }.isSuccess
        }
    }
}

@Composable
fun BubbleCard(spec: BubbleSpec, expanded: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp), horizontalAlignment = Alignment.End) {
        Box(Modifier.size(64.dp).shadow(14.dp, CircleShape, spotColor = Cue.primaryDeep).clip(CircleShape).background(Cue.night).clickable(role = Role.Button, onClickLabel = "Collapse or expand") { onToggle() }, contentAlignment = Alignment.Center) { PixelHead(60.dp) }
        if (!expanded) return@Column
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth().shadow(30.dp, RoundedCornerShape(34.dp), spotColor = Cue.primaryNight).clip(RoundedCornerShape(34.dp))
                .background(Cue.base).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Pill(spec.placeLabel, Cue.coralTint, Cue.coralText, dot = Cue.coralDeep)
            Txt(spec.headline, Type.title)
            spec.card?.let { c ->
                Column(Modifier.fillMaxWidth().shadow(14.dp, RoundedCornerShape(24.dp), spotColor = Color(0xFF3F45B8)).clip(RoundedCornerShape(24.dp)).background(Cue.tripBrush)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Txt(c.from, Type.big.copy(fontSize = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp)), Color.White)
                        Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Flight, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Txt(c.to, Type.big.copy(fontSize = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp)), Color.White)
                    }
                    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Txt(c.time, Type.smallStrong, Color.White)
                        c.gate?.let { Txt("Gate $it", Type.small, Color(0xFFE3E7FF)) }
                        c.seat?.let { Txt("Seat $it", Type.small, Color(0xFFE3E7FF)) }
                    }
                }
            }
            if (spec.items.isNotEmpty()) {
                Txt(if (spec.card != null) "Did you bring these?" else if (spec.personId != null) "Open items" else "On your list", Type.smallStrong, Cue.muted)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White).padding(4.dp)) {
                    spec.items.forEach { item ->
                        var checked by androidx.compose.runtime.remember { mutableStateOf(false) }
                        if (item.kind == "entry") CheckRow(item.text, checked, onToggle = {
                            checked = !checked
                            CueRuntime.scope.launch { CueRuntime.engine.dao.entry(item.id)?.let { CueRuntime.engine.dao.updateEntry(it.copy(checked = true)) } }
                        })
                        else Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (item.kind) { "pending" -> Icons.Rounded.Person; "bill" -> Icons.Rounded.Receipt; "habit" -> Icons.Rounded.LocalFireDepartment
                                "prescription" -> Icons.Rounded.Medication; "lab_report" -> Icons.Rounded.Science; "note" -> Icons.Rounded.StickyNote2; "reminder" -> Icons.Rounded.Alarm
                                else -> Icons.Rounded.Description }, null, tint = Cue.coralDeep, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Txt(item.text, Type.body, maxLines = 2)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Thanks", Modifier.weight(1f)) { onDismiss() }
                PrimaryButton(if (spec.albumId != null) "Open" else "Open Cue", Modifier.weight(1f), height = 52.dp) { onOpen() }
            }
        }
    }
}


