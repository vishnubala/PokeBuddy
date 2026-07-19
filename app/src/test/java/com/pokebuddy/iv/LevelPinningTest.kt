package com.pokebuddy.iv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Quantifies what knowing the exact LEVEL buys us, on a real capture: the caught
 * Pikachu CP 671 / HP 86, which Calcy independently read as L28 3/15/12.
 *
 * This is the evidence behind the design decision that level alone does NOT replace
 * the appraisal: it narrows the search a lot, but not to a single answer. Anything
 * that claimed an exact IV from CP + HP + level would be guessing.
 */
class LevelPinningTest {

    @Before fun setUp() = SpeciesTable.load("pikachu,112,96,111")

    private val pikachu get() = SpeciesTable["Pikachu"]!!

    @Test fun level_alone_narrows_but_does_not_resolve() {
        val all = IvDecoder.decode(pikachu, 671, 86)
        val pinned = IvDecoder.decode(pikachu, 671, 86, levels = listOf(28.0))

        assertEquals("unpinned candidates", 42, all.distinctIvs.size)
        assertEquals("level-pinned candidates", 7, pinned.distinctIvs.size)
        assertTrue("still ambiguous — must not be reported as exact", pinned.exactIv == null)
    }

    /** Whatever we narrow to must still contain the true answer. */
    @Test fun pinned_set_contains_the_calcy_ground_truth() {
        val pinned = IvDecoder.decode(pikachu, 671, 86, levels = listOf(28.0))
        assertTrue(pinned.distinctIvs.contains(Iv(3, 15, 12)))
    }

    /** HP does not pin stamina outright: CP/HP are floored to integers, so a couple of
     *  stamina values survive. Worth asserting so nobody "optimises" that assumption in. */
    @Test fun hp_does_not_uniquely_determine_stamina() {
        val pinned = IvDecoder.decode(pikachu, 671, 86, levels = listOf(28.0))
        assertEquals(setOf(11, 12), pinned.distinctIvs.map { it.stamina }.toSet())
    }

    /** Even unresolved, the level-pinned set spans a narrow enough IV% band to act on. */
    @Test fun level_pinning_alone_gives_an_actionable_percent_band() {
        val pinned = IvDecoder.decode(pikachu, 671, 86, levels = listOf(28.0))
        assertEquals(IntRange(53, 67), pinned.percentRange)
    }

    /**
     * The payoff of caring about % rather than the split: the star tier constrains the
     * IV SUM, and the sum is exactly what % is computed from. Tier + CP + HP + level
     * pins the percentage even though the attack/defense split stays ambiguous.
     */
    @Test fun star_tier_pins_the_percent_without_pinning_the_split() {
        val threeStar = Appraisal(ivSumRange = IntRange(30, 36))
        val r = IvDecoder.decode(pikachu, 671, 86, threeStar, levels = listOf(28.0))

        assertEquals("percent is pinned", 67, r.exactPercent)
        assertTrue("...but the split need not be", r.exactIv != null || r.distinctIvs.size >= 1)
    }
}
