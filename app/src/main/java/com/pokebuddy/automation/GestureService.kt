package com.pokebuddy.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Injects taps into the foreground app. This is the ONLY component that sends synthetic
 * input, and it is deliberately dumb: it decides nothing, it just taps where told.
 * All perception and control flow lives in [AutoAppraiser], which keeps the risk-bearing
 * surface small and auditable.
 *
 * This is the opt-in "auto-navigate" mode from the project constraints — the user must
 * enable it in Settings → Accessibility, and it does nothing until explicitly triggered.
 *
 * Pokémon GO is a Unity app: its accessibility tree is a single opaque surface with no
 * per-widget nodes, so gestures are the only route and targets have to come from OCR.
 */
class GestureService : AccessibilityService() {

    companion object {
        private const val TAG = "PokeBuddyGesture"
        private const val TAP_MS = 60L
        private const val POGO = "com.nianticlabs.pokemongo"

        @Volatile
        private var instance: GestureService? = null

        val isConnected: Boolean get() = instance != null

        /** @return false when the service isn't enabled, or the gesture didn't dispatch. */
        fun tap(x: Int, y: Int): Boolean = instance?.dispatchTap(x, y) ?: false

        /** True when Pokémon GO is the app we last saw come to the foreground. */
        val isPogoForeground: Boolean get() = instance?.lastPackage == POGO
    }

    @Volatile private var lastPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Gesture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Used only to know which app is in front, so automation can refuse to tap
        // into anything other than Pokémon GO.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { lastPackage = it }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Dispatches a tap and blocks until the system reports it completed. */
    private fun dispatchTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        val done = CountDownLatch(1)
        var ok = false
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; done.countDown() }
            override fun onCancelled(d: GestureDescription?) { done.countDown() }
        }, null)
        if (!dispatched) return false
        done.await(2, TimeUnit.SECONDS)
        return ok
    }
}
