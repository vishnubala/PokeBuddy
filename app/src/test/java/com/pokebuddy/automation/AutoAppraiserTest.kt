package com.pokebuddy.automation

import com.pokebuddy.ocr.Box
import com.pokebuddy.ocr.OcrLine
import com.pokebuddy.ocr.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automation is driven entirely through injected lambdas, so the whole control
 * flow is exercised here against scripted screens — no device, no Pokémon GO.
 */
class AutoAppraiserTest {

    private val taps = mutableListOf<Pair<Int, Int>>()

    private fun screen(vararg texts: String) = OcrResult(
        width = 1080, height = 2400,
        lines = texts.mapIndexed { i, t -> OcrLine(t, Box(400, 100 * i, 700, 100 * i + 40)) },
    )

    private val detail = screen("CP671", "Pikachu", "86/86 HP")
    private val menu = screen("POKÉDEX", "ITEMS", "APPRAISE", "TRANSFER")
    private val dialogue = screen("Hello, trainer. I am available to analyze")
    private val bars = screen("Attack", "Defense", "HP")

    /** Feeds a fixed sequence of screens, repeating the last one forever. */
    private fun appraiser(vararg screens: OcrResult): AutoAppraiser {
        var i = 0
        return AutoAppraiser(
            tap = { x, y -> taps.add(x to y); true },
            probe = { screens[minOf(i++, screens.lastIndex)] },
            sleep = {},
        )
    }

    @Test fun walks_detail_menu_dialogue_to_the_bars() {
        val outcome = appraiser(detail, menu, dialogue, bars).run()
        assertEquals(AutoAppraiser.Outcome.REACHED_APPRAISAL, outcome)
    }

    /**
     * The bug found on device: a fixed tap sequence sails past the bars and closes them.
     * Once the bars are up, the appraiser must stop tapping entirely.
     */
    @Test fun stops_tapping_the_moment_the_bars_appear() {
        appraiser(detail, menu, bars).run()
        val tapsBeforeBars = taps.size
        assertEquals("menu button + APPRAISE only", 2, tapsBeforeBars)
    }

    @Test fun does_nothing_when_already_on_the_appraisal_screen() {
        val outcome = appraiser(bars).run()
        assertEquals(AutoAppraiser.Outcome.ALREADY_THERE, outcome)
        assertTrue("must not tap a screen it is already on", taps.isEmpty())
    }

    @Test fun taps_the_appraise_label_where_ocr_found_it() {
        appraiser(detail, menu).run()
        val appraiseLine = menu.lines.first { it.text == "APPRAISE" }
        assertTrue(
            "should tap the OCR'd label, not a fixed position",
            taps.contains(appraiseLine.cx to appraiseLine.cy),
        )
    }

    @Test fun gives_up_when_the_menu_never_opens() {
        val outcome = appraiser(detail, detail).run()
        assertEquals(AutoAppraiser.Outcome.MENU_NOT_FOUND, outcome)
    }

    @Test fun gives_up_rather_than_tapping_forever_on_endless_dialogue() {
        val outcome = appraiser(detail, menu, dialogue).run()
        assertEquals(AutoAppraiser.Outcome.DIALOGUE_DID_NOT_END, outcome)
    }

    @Test fun reports_tap_failure_when_gestures_are_rejected() {
        val a = AutoAppraiser(tap = { _, _ -> false }, probe = { detail }, sleep = {})
        assertEquals(AutoAppraiser.Outcome.TAP_FAILED, a.run())
    }
}
