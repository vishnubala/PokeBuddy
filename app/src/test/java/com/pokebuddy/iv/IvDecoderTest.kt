package com.pokebuddy.iv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Validated against a real Pokémon: Moltres, CP 2431 / HP 145 / Level 25, whose true
 * IVs (14/13/11, 84%) were confirmed from a Calcy IV overlay in the same capture.
 */
class IvDecoderTest {

    @Before fun setUp() {
        // Inline CSV — the full base_stats.csv asset isn't on the JVM test classpath.
        SpeciesTable.load("moltres,251,181,207\npalkia,280,215,189")
    }

    private val moltres get() = SpeciesTable["Moltres"]!!
    private val truth = Iv(14, 13, 11)

    @Test fun formula_reproduces_ingame_cp_and_hp() {
        val cpm25 = Cpm.byLevel[25.0]!!
        assertEquals(2431, IvDecoder.cp(moltres, truth, cpm25))
        assertEquals(145, IvDecoder.hp(moltres, truth.stamina, cpm25))
    }

    @Test fun percent_matches_ingame() {
        assertEquals(84, truth.percent)
    }

    // --- Corroborating measured appraisal bars against CP + HP ---

    @Test fun correct_bars_are_confirmed_and_give_an_exact_iv() {
        val r = IvDecoder.decodeCorroborated(moltres, 2431, 145, truth)
        assertEquals(truth, r.exactIv)
    }

    /**
     * The safety property: a misread bar must never yield a confident wrong answer. CP+HP
     * can't produce that triple, so the bars are dropped and we fall back to candidates.
     */
    @Test fun a_misread_bar_degrades_to_candidates_rather_than_lying() {
        val misread = Iv(15, 13, 11)   // attack bar read one too high
        val r = IvDecoder.decodeCorroborated(moltres, 2431, 145, misread)
        assertTrue("must not report the misread triple as exact", r.exactIv != misread)
        assertTrue("bad bars must widen the answer, not empty it", !r.isEmpty)
        assertTrue("the true IV must survive the fallback", r.distinctIvs.contains(truth))
    }

    /** The fallback must be CP+HP alone — identical to decoding with no appraisal at all. */
    @Test fun fallback_matches_a_plain_cp_hp_decode() {
        val fallback = IvDecoder.decodeCorroborated(moltres, 2431, 145, Iv(15, 13, 11))
        assertEquals(IvDecoder.decode(moltres, 2431, 145).distinctIvs, fallback.distinctIvs)
    }

    @Test fun true_iv_is_among_candidates_from_cp_hp_alone() {
        val r = IvDecoder.decode(moltres, cp = 2431, hp = 145)
        assertTrue("distinct=${r.distinctIvs}", truth in r.distinctIvs)
    }

    @Test fun appraisal_plus_known_level_pins_exact_iv() {
        // Full in-game appraisal for this Moltres: 4-star, Attack best, best-stat band 13–14.
        val appraisal = Appraisal(
            ivSumRange = Appraisal.FOUR_STAR,
            bestStats = setOf(Stat.ATTACK),
            bestValueRange = Appraisal.BEST_13_14,
        )
        val r = IvDecoder.decode(moltres, 2431, 145, appraisal, levels = listOf(25.0))
        assertEquals("solutions=${r.solutions}", truth, r.exactIv)
    }
}
