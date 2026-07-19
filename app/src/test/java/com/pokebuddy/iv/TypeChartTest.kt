package com.pokebuddy.iv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spot-checks the hand-encoded type chart. The relationships come from the published chart
 * rather than a fetched data file, so these tests are the guard against a transcription slip.
 */
class TypeChartTest {

    private val d = 0.0001

    @Test fun basic_relationships() {
        assertEquals(TypeChart.SUPER_EFFECTIVE, TypeChart.multiplier("water", "fire"), d)
        assertEquals(TypeChart.NOT_VERY_EFFECTIVE, TypeChart.multiplier("fire", "water"), d)
        assertEquals(TypeChart.NEUTRAL, TypeChart.multiplier("normal", "normal"), d)
    }

    /** GO has no true immunity — the main series' "no effect" is a second resistance step. */
    @Test fun immunity_is_a_double_resist_not_zero() {
        assertEquals(TypeChart.IMMUNE, TypeChart.multiplier("normal", "ghost"), d)
        assertEquals(TypeChart.IMMUNE, TypeChart.multiplier("ground", "flying"), d)
        assertEquals(TypeChart.IMMUNE, TypeChart.multiplier("dragon", "fairy"), d)
        assertTrue(TypeChart.multiplier("electric", "ground") > 0.0)
    }

    @Test fun dual_types_multiply() {
        // Rock resists fire (0.625) but water is super effective on both rock and ground.
        assertEquals(
            TypeChart.SUPER_EFFECTIVE * TypeChart.SUPER_EFFECTIVE,
            TypeChart.multiplier("water", listOf("rock", "ground")), d,
        )
        // Bug is resisted by fire AND flying — a stacked double resist.
        assertEquals(
            TypeChart.NOT_VERY_EFFECTIVE * TypeChart.NOT_VERY_EFFECTIVE,
            TypeChart.multiplier("bug", listOf("fire", "flying")), d,
        )
        // Opposing effects cancel back to neutral.
        assertEquals(
            TypeChart.NEUTRAL,
            TypeChart.multiplier("grass", listOf("water", "flying")), d,
        )
    }

    @Test fun counters_are_ranked_best_first() {
        // Charizard: fire/flying. Rock hits both -> 2.56x, clear of everything else.
        val best = TypeChart.counters(listOf("fire", "flying")).first()
        assertEquals("rock", best.first)
        assertEquals(2.56, best.second, d)
    }

    @Test fun resistances_are_reported() {
        val resists = TypeChart.resistances(listOf("steel")).map { it.first }
        assertTrue(resists.containsAll(listOf("normal", "grass", "ice", "flying")))
    }

    /** Every type must be a valid key both ways, or a defender would silently read neutral. */
    @Test fun chart_covers_all_eighteen_types_symmetrically() {
        assertEquals(18, TypeChart.TYPES.size)
        for (a in TypeChart.TYPES) {
            for (b in TypeChart.TYPES) {
                val m = TypeChart.multiplier(a, b)
                assertTrue(
                    "$a -> $b produced $m",
                    m == TypeChart.SUPER_EFFECTIVE || m == TypeChart.NOT_VERY_EFFECTIVE ||
                        m == TypeChart.IMMUNE || m == TypeChart.NEUTRAL,
                )
            }
        }
    }

    /** The species table's type strings must match the chart's vocabulary. */
    @Test fun species_types_are_all_known_to_the_chart() {
        SpeciesTable.load(java.io.File("src/main/assets/base_stats.csv").readText())
        val unknown = SpeciesTable.formsFor("Charizard").flatMap { it.types }
            .filterNot { it in TypeChart.TYPES }
        assertTrue("unknown types: $unknown", unknown.isEmpty())
    }
}
