package com.pokebuddy.automation

import com.pokebuddy.ocr.AppraisalReader
import com.pokebuddy.ocr.OcrResult
import kotlin.random.Random

/**
 * Drives Pokémon GO's appraisal flow: detail screen → menu → APPRAISE → past the team
 * leader's dialogue → the screen with the stat bars, where the normal capture pipeline
 * takes over.
 *
 * CLOSED LOOP, not a fixed script. A scripted "tap, tap, tap" overshoots — the dialogue
 * runs a variable number of beats and a fixed sequence sails straight past the bars and
 * closes them again (observed on device). So every step re-reads the screen and decides
 * from what's actually there.
 *
 * Targets come from OCR wherever possible: the menu is a variable-length list (TRANSFER
 * is absent for favourited Pokémon, shifting everything below it), so APPRAISE is found
 * by its label rather than by a memorised position. Only the menu button itself and the
 * dialogue-advance point are expressed as screen ratios, both being fixed UI furniture.
 *
 * Dependencies are injected so the whole control flow is unit-testable with no device.
 */
class AutoAppraiser(
    private val tap: (x: Int, y: Int) -> Boolean,
    private val probe: () -> OcrResult?,
    private val sleep: (Long) -> Unit,
    private val random: Random = Random.Default,
) {

    enum class Outcome {
        /** The bars are on screen — read them now. */
        REACHED_APPRAISAL,
        /** Already there when we started; nothing was tapped. */
        ALREADY_THERE,
        NO_FRAME,
        MENU_NOT_FOUND,
        DIALOGUE_DID_NOT_END,
        TAP_FAILED,
    }

    companion object {
        // Fixed UI furniture, as fractions of the screen (multi-resolution safety).
        private const val MENU_X_FRAC = 0.863
        private const val MENU_Y_FRAC = 0.922
        private const val DIALOGUE_X_FRAC = 0.50
        private const val DIALOGUE_Y_FRAC = 0.90

        private const val MENU_PROBES = 5
        private const val DIALOGUE_TAPS = 6

        // Randomised so the input doesn't carry a machine-perfect rhythm.
        private const val PAUSE_MIN_MS = 550L
        private const val PAUSE_MAX_MS = 1150L
    }

    fun run(): Outcome {
        val start = probe() ?: return Outcome.NO_FRAME
        if (AppraisalReader.isAppraisalScreen(start)) return Outcome.ALREADY_THERE

        if (!tapFrac(start, MENU_X_FRAC, MENU_Y_FRAC)) return Outcome.TAP_FAILED
        pause()

        // The menu animates in, so give it a few reads before giving up.
        val appraise = findAppraise() ?: return Outcome.MENU_NOT_FOUND
        if (!tap(appraise.first, appraise.second)) return Outcome.TAP_FAILED
        pause()

        // Advance the leader's dialogue, checking after every beat — stop the instant
        // the bars appear so we don't tap past them.
        repeat(DIALOGUE_TAPS) {
            val ocr = probe() ?: return Outcome.NO_FRAME
            if (AppraisalReader.isAppraisalScreen(ocr)) return Outcome.REACHED_APPRAISAL
            if (!tapFrac(ocr, DIALOGUE_X_FRAC, DIALOGUE_Y_FRAC)) return Outcome.TAP_FAILED
            pause()
        }
        val last = probe() ?: return Outcome.NO_FRAME
        return if (AppraisalReader.isAppraisalScreen(last)) Outcome.REACHED_APPRAISAL
        else Outcome.DIALOGUE_DID_NOT_END
    }

    /** @return centre of the APPRAISE menu entry, or null if the menu never appeared. */
    private fun findAppraise(): Pair<Int, Int>? {
        repeat(MENU_PROBES) {
            val ocr = probe()
            val line = ocr?.lines?.firstOrNull { it.text.trim().equals("APPRAISE", ignoreCase = true) }
            if (line != null) return line.cx to line.cy
            pause()
        }
        return null
    }

    private fun tapFrac(ocr: OcrResult, xFrac: Double, yFrac: Double): Boolean =
        tap((ocr.width * xFrac).toInt(), (ocr.height * yFrac).toInt())

    private fun pause() = sleep(random.nextLong(PAUSE_MIN_MS, PAUSE_MAX_MS))
}
