package com.pokebuddy.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScreenWatcher's logic is pure arithmetic over fingerprints, but [ScreenWatcher.offer] takes
 * an android.graphics.Bitmap, which doesn't exist on the JVM. These tests drive the same
 * decision rules through a local reimplementation of the comparison so the thresholds and
 * the settle behaviour are covered without an emulator.
 *
 * The behaviours that matter:
 *  - an animating sprite must NOT read as a new screen (or the overlay would rescan forever)
 *  - a real screen change MUST be reported, but only once it has settled
 */
class ScreenWatcherTest {

    private val threshold = 6.0
    private val stableFrames = 2

    /** Mirrors ScreenWatcher.offer's decision logic over ready-made fingerprints. */
    private class Decider(private val threshold: Double, private val stableFrames: Int) {
        private var current: IntArray? = null
        private var pending: IntArray? = null
        private var stable = 0

        fun offer(print: IntArray): Boolean {
            val baseline = current ?: run { current = print; return true }
            if (distance(print, baseline) < threshold) {
                pending = null; stable = 0; return false
            }
            val p = pending
            if (p != null && distance(print, p) < threshold) {
                stable++
                if (stable >= stableFrames) {
                    current = print; pending = null; stable = 0; return true
                }
            } else {
                pending = print; stable = 0
            }
            return false
        }

        private fun distance(a: IntArray, b: IntArray): Double {
            var total = 0L
            for (i in a.indices) total += kotlin.math.abs(a[i] - b[i])
            return total.toDouble() / a.size
        }
    }

    private fun screen(base: Int) = IntArray(144) { base }
    private fun jitter(base: Int, amount: Int) = IntArray(144) { if (it % 3 == 0) base + amount else base }

    @Test fun first_frame_is_always_read() {
        assertTrue(Decider(threshold, stableFrames).offer(screen(100)))
    }

    @Test fun an_unchanged_screen_is_not_re_read() {
        val d = Decider(threshold, stableFrames)
        d.offer(screen(100))
        assertFalse(d.offer(screen(100)))
        assertFalse(d.offer(screen(100)))
    }

    /** An animating sprite jitters a few cells; that must not look like navigation. */
    @Test fun sprite_animation_does_not_trigger_a_rescan() {
        val d = Decider(threshold, stableFrames)
        d.offer(screen(100))
        repeat(10) { i ->
            assertFalse("frame $i triggered a rescan", d.offer(jitter(100, 9)))
        }
    }

    @Test fun a_real_screen_change_is_reported_once_settled() {
        val d = Decider(threshold, stableFrames)
        d.offer(screen(100))
        // Transition begins — not reported yet, it hasn't settled.
        assertFalse(d.offer(screen(200)))
        assertFalse(d.offer(screen(200)))
        assertTrue(d.offer(screen(200)))
    }

    /** Mid-transition frames keep changing, so nothing fires until motion stops. */
    @Test fun mid_transition_frames_do_not_fire() {
        val d = Decider(threshold, stableFrames)
        d.offer(screen(100))
        assertFalse(d.offer(screen(130)))
        assertFalse(d.offer(screen(160)))
        assertFalse(d.offer(screen(190)))
    }

    @Test fun a_second_change_is_reported_after_the_first() {
        val d = Decider(threshold, stableFrames)
        d.offer(screen(100))
        repeat(3) { d.offer(screen(200)) }
        assertFalse(d.offer(screen(200)))
        // Move to a third screen.
        assertFalse(d.offer(screen(50)))
        assertFalse(d.offer(screen(50)))
        assertTrue(d.offer(screen(50)))
    }
}
