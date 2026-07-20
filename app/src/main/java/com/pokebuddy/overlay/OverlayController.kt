package com.pokebuddy.overlay

import android.annotation.SuppressLint
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
// Aliased: android.provider.Settings is already imported here for canDrawOverlays.
import com.pokebuddy.settings.Settings as PokeSettings
import com.pokebuddy.settings.SettingsSpec
import kotlin.math.abs

/**
 * The floating results panel drawn over Pokémon GO via SYSTEM_ALERT_WINDOW.
 *
 * Visibility rules, which are deliberately strict:
 *
 *  - It appears ONLY while Pokémon GO is in front. [onForegroundApp] hides it the moment
 *    anything else comes forward, and [show] refuses to draw if PoGO isn't in front, so it
 *    never surfaces over another app even briefly.
 *  - A panel describes ONE scanned screen, so it auto-dismisses after [AUTO_DISMISS_MS]
 *    rather than lingering over a screen it no longer describes. PoGO is a Unity app whose
 *    internal navigation fires no accessibility events, so a timeout is the only way to
 *    catch "user moved on inside the game" without continuously re-capturing the screen.
 *
 * All WindowManager mutations are marshalled onto the main thread (the capture pipeline runs
 * on a worker thread). [hideForCapture] blanks the panel so MediaProjection doesn't
 * re-capture our own overlay.
 */
class OverlayController(private val context: Context) {

    companion object {
        private const val TAG = "PokeBuddyOverlay"
        /** A panel outlives its screen quickly; long enough to read, short enough not to lie. */
        /** Default only — the live value is [SettingsSpec.PANEL_DISMISS_SECONDS]. */
        private const val AUTO_DISMISS_MS = 12_000L
        /** Movement beyond this is a drag, not a tap — keeps the close button clickable. */
        private const val DRAG_SLOP_PX = 12
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Set false by the user tapping ✕; no panel is drawn again until the next scan. */
    @Volatile private var dismissed = false
    /** True while a ScreenWatcher is driving show/hide from actual screen changes. */
    @Volatile var screenWatched = false
    @Volatile private var pogoInFront = true

    /** Invoked by the ⟳ button — re-reads the screen on demand. */
    @Volatile var onRescan: (() -> Unit)? = null

    private val autoDismiss = Runnable { hide() }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureAdded() {
        if (root != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — panel suppressed")
            return
        }

        val prefs = PokeSettings.get(context)
        val body = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                prefs.get(SettingsSpec.OVERLAY_TEXT_SP).toFloat(),
            )
            text = "PokeBuddy ready"
        }
        val rescan = TextView(context).apply {
            text = "⟳"
            setTextColor(0xFF8FD3FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(28, 0, 8, 0)
            setOnClickListener { onRescan?.invoke() }
        }
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(0xFFBBBBBB.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(32, 0, 8, 0)
            setOnClickListener { dismissed = true; hide() }
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC101418.toInt())
            setPadding(28, 24, 20, 24)
            addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(rescan)
            addView(close)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // NOT_FOCUSABLE keeps keyboard/back with the game underneath; the panel still
            // receives its own touches for dragging and the close button.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Restore where it was last dragged, unless the user asked for a fixed start.
            val remember = prefs.get(SettingsSpec.REMEMBER_PANEL_POSITION)
            x = if (remember) prefs.get(SettingsSpec.PANEL_X) else SettingsSpec.PANEL_X.default
            y = if (remember) prefs.get(SettingsSpec.PANEL_Y) else SettingsSpec.PANEL_Y.default
        }

        container.setOnTouchListener(dragListener(lp))

        runCatching { wm.addView(container, lp) }
            .onSuccess { root = container; label = body; params = lp }
            .onFailure { Log.w(TAG, "addView failed", it) }
    }

    /** Drag-to-move. Returns false for small movements so the ✕ still registers as a click. */
    private fun dragListener(lp: WindowManager.LayoutParams) = object : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (!dragging && (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // Persist on release rather than on every MOVE — dragging fires
                    // continuously, and committing each frame would hammer prefs.
                    if (dragging) {
                        val prefs = PokeSettings.get(context)
                        if (prefs.get(SettingsSpec.REMEMBER_PANEL_POSITION)) {
                            prefs.set(SettingsSpec.PANEL_X, lp.x)
                            prefs.set(SettingsSpec.PANEL_Y, lp.y)
                        }
                    }
                    return dragging   // consume only if we dragged
                }
            }
            return false
        }
    }

    /** Shows a panel for the screen just scanned. No-op if PoGO isn't in front. */
    fun show(text: String) = main.post {
        dismissed = false
        if (!pogoInFront) {
            Log.i(TAG, "Suppressed: Pokémon GO is not in the foreground")
            return@post
        }
        ensureAdded()
        label?.text = text
        root?.visibility = View.VISIBLE
        main.removeCallbacks(autoDismiss)
        // Only fall back to a timeout when nothing is watching the screen for us.
        // 0 means "stay until dismissed", so no timeout is posted at all.
        val seconds = PokeSettings.get(context).get(SettingsSpec.PANEL_DISMISS_SECONDS)
        if (!screenWatched && seconds > 0) main.postDelayed(autoDismiss, seconds * 1000L)
    }

    /** Blank the panel so it isn't part of the next captured frame. */
    fun hideForCapture() = main.post { root?.visibility = View.INVISIBLE }

    /** Hide without tearing down the window. */
    fun hide(): Unit = run {
        main.post {
            main.removeCallbacks(autoDismiss)
            root?.visibility = View.INVISIBLE
        }
    }

    /**
     * Called when the foreground app changes. Anything other than Pokémon GO hides the
     * panel immediately — PokeBuddy must leave no trace over other apps.
     */
    fun onForegroundApp(isPogo: Boolean) {
        pogoInFront = isPogo
        if (!isPogo) hide()
    }

    fun destroy() = main.post {
        main.removeCallbacks(autoDismiss)
        root?.let { runCatching { wm.removeView(it) } }
        root = null; label = null; params = null
    }
}
