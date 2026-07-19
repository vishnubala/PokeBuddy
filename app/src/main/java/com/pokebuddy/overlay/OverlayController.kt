package com.pokebuddy.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * A minimal floating results panel drawn over Pokémon GO via SYSTEM_ALERT_WINDOW.
 *
 * All WindowManager mutations are marshalled onto the main thread (the capture pipeline
 * runs on a worker thread). [hideForCapture] blanks the panel so MediaProjection doesn't
 * re-capture our own overlay — the self-OCR feedback loop flagged during the OCR work.
 */
class OverlayController(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    private fun ensureAdded() {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w("PokeBuddyOverlay", "SYSTEM_ALERT_WINDOW not granted — panel suppressed")
            return
        }
        val tv = TextView(context).apply {
            setBackgroundColor(0xCC101418.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(28, 24, 28, 24)
            text = "PokeBuddy ready"
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 28
            y = 220
        }
        runCatching { wm.addView(tv, lp) }
            .onSuccess { view = tv }
            .onFailure { Log.w("PokeBuddyOverlay", "addView failed", it) }
    }

    fun show(text: String) = main.post {
        ensureAdded()
        view?.apply { this.text = text; visibility = View.VISIBLE }
    }

    /** Blank the panel so it isn't part of the next captured frame. */
    fun hideForCapture() = main.post { view?.visibility = View.INVISIBLE }

    fun destroy() = main.post {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
