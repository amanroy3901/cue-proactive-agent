package com.cue.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Pixel, the approved design, played from the exact SVG + CSS animations on the design canvas.
 * Each state is a small HTML file in ui/src/main/assets/pixel (exported by the design scripts), shown in a
 * transparent WebView that never takes touches, so taps reach whatever Pixel sits on.
 */
@Composable
fun Pixel(mood: PixelMood, size: Dp, modifier: Modifier = Modifier, level: Float = 0f, animate: Boolean = true) {
    PixelAsset(pixelAsset(mood), modifier.size(size), "Cue's character, Pixel")
}

/** Head only, still, for small places (home line, bubble, album button). */
@Composable
fun PixelHead(size: Dp, modifier: Modifier = Modifier, happy: Boolean = true, animate: Boolean = true) {
    PixelAsset("head", modifier.size(size), "Cue")
}

/** Which approved animation plays for each mood. */
fun pixelAsset(mood: PixelMood): String = when (mood) {
    PixelMood.Idle -> "idle"
    PixelMood.Hello -> "waving_hello"
    PixelMood.Ringing -> "ringing_morning"
    PixelMood.RingingNight -> "ringing_night"
    PixelMood.Connecting -> "connecting"
    PixelMood.Listening -> "listening"
    PixelMood.Thinking -> "thinking"
    PixelMood.Speaking -> "talking"
    PixelMood.Ticking -> "ticking_an_item"
    PixelMood.Writing -> "writing_the_journal"
    PixelMood.Filing -> "filing_a_note"
    PixelMood.Reminder -> "setting_a_reminder"
    PixelMood.Finding -> "found_it"
    PixelMood.Habit -> "celebrating"
    PixelMood.Celebrate -> "celebrating"
    PixelMood.Sleeping -> "night_wind_down"
    PixelMood.HoldPass -> "boarding_pass_bubble"
    PixelMood.Offline -> "offline"
}

/** A WebView that draws but never consumes touches. */
@SuppressLint("ViewConstructor")
private class PixelWebView(context: Context) : WebView(context) {
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean = false
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false
}

@Composable
private fun PixelAsset(name: String, modifier: Modifier, description: String) {
    AndroidView(
        modifier = modifier.semantics { contentDescription = description },
        factory = { ctx ->
            PixelWebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                isFocusable = false
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                settings.javaScriptEnabled = false
                settings.allowFileAccess = true
                tag = name
                loadUrl("file:///android_asset/pixel/$name.html")
            }
        },
        update = { view -> if (view.tag != name) { view.tag = name; view.loadUrl("file:///android_asset/pixel/$name.html") } },
    )
}
