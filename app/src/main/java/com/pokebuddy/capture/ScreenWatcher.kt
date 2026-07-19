package com.pokebuddy.capture

import android.graphics.Bitmap

/**
 * Decides WHEN the screen is worth reading, so the overlay can follow along by itself.
 *
 * Pokémon GO is a Unity app: moving from the box to a Pokémon, to the next Pokémon, and back
 * to the map fires no accessibility events at all, so there is nothing to subscribe to. The
 * only signal available is the pixels themselves.
 *
 * OCR-ing every frame would be wasteful, so this reduces each frame to a tiny fingerprint
 * (a grid of average luminances) and only reports a change when the picture actually settles
 * into something new:
 *
 *  - [CHANGE_THRESHOLD] ignores the constant micro-motion of an animating sprite, so sitting
 *    on one Pokémon doesn't look like an endless stream of new screens.
 *  - A change must then hold still for [STABLE_FRAMES] before it counts, so we read after a
 *    transition finishes rather than mid-animation.
 *
 * This is deliberately cheap and framework-light: it makes no decision about WHAT the screen
 * is, only that it is worth looking at again.
 */
class ScreenWatcher {

    companion object {
        /** Fingerprint resolution. Coarse on purpose — layout changes, not detail, matter. */
        private const val GRID = 12
        /** Mean per-cell luminance delta (0..255) that counts as a different screen. */
        private const val CHANGE_THRESHOLD = 6.0
        /** Consecutive similar frames required before a change is reported. */
        private const val STABLE_FRAMES = 2
    }

    private var current: IntArray? = null
    private var pending: IntArray? = null
    private var stableCount = 0

    /**
     * Feeds a frame in.
     *
     * @return true when the screen has changed and settled — the caller should OCR it.
     */
    fun offer(frame: Bitmap): Boolean {
        val print = fingerprint(frame)
        val baseline = current
        if (baseline == null) {
            current = print
            return true                       // first frame is always worth reading
        }
        if (distance(print, baseline) < CHANGE_THRESHOLD) {
            pending = null                    // still the same screen
            stableCount = 0
            return false
        }
        // Something changed; wait for it to hold still so we don't read mid-transition.
        val p = pending
        if (p != null && distance(print, p) < CHANGE_THRESHOLD) {
            stableCount++
            if (stableCount >= STABLE_FRAMES) {
                current = print
                pending = null
                stableCount = 0
                return true
            }
        } else {
            pending = print
            stableCount = 0
        }
        return false
    }

    /**
     * Is [frame] still the screen that [offer] last accepted?
     *
     * Guards the gap between deciding to read and actually reading: OCR takes a few hundred
     * ms, and if the screen moved on in between we'd mix fields from two screens — which
     * showed up live as a Pokémon's name paired with the previous Pokémon's CP.
     */
    fun stillCurrent(frame: Bitmap): Boolean {
        val baseline = current ?: return false
        return distance(fingerprint(frame), baseline) < CHANGE_THRESHOLD
    }

    /** Forget the current screen, so the next frame reads as new. */
    fun reset() {
        current = null
        pending = null
        stableCount = 0
    }

    /** Average luminance per cell of a GRID x GRID grid. */
    private fun fingerprint(frame: Bitmap): IntArray {
        val out = IntArray(GRID * GRID)
        val cellW = frame.width / GRID
        val cellH = frame.height / GRID
        if (cellW == 0 || cellH == 0) return out
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                var sum = 0L
                var n = 0
                // Sample a sparse lattice inside the cell rather than every pixel.
                var y = gy * cellH
                while (y < (gy + 1) * cellH) {
                    var x = gx * cellW
                    while (x < (gx + 1) * cellW) {
                        val c = frame.getPixel(x, y)
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        sum += (r * 30 + g * 59 + b * 11) / 100
                        n++
                        x += 8
                    }
                    y += 8
                }
                out[gy * GRID + gx] = if (n == 0) 0 else (sum / n).toInt()
            }
        }
        return out
    }

    /** Mean absolute per-cell difference. */
    private fun distance(a: IntArray, b: IntArray): Double {
        if (a.size != b.size) return Double.MAX_VALUE
        var total = 0L
        for (i in a.indices) total += kotlin.math.abs(a[i] - b[i])
        return total.toDouble() / a.size
    }
}
