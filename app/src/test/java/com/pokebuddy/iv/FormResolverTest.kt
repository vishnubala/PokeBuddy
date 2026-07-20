package com.pokebuddy.iv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Form disambiguation against the REAL bundled table, because the cases that matter are
 * exactly the ones where two rows of that table collide.
 */
class FormResolverTest {

    @Before fun setUp() =
        SpeciesTable.load(java.io.File("src/main/assets/base_stats.csv").readText())

    // ---------- The type row still does the easy work ----------

    @Test fun regional_form_is_separated_by_its_types() {
        val r = FormResolver.resolve("Raichu", listOf("electric", "psychic"))
        assertEquals("Raichu (Alolan)", r.species?.name)
        assertTrue(r.nameIsCertain)
    }

    // ---------- Feasibility: same types, different stats ----------

    /**
     * Mewtwo and Armored Mewtwo are both pure psychic, so the type row cannot choose. Their
     * attack differs enormously (300 vs 182), so only one of them can produce a given CP/HP.
     */
    @Test fun armored_mewtwo_is_separated_from_mewtwo_by_feasibility() {
        val mewtwo = SpeciesTable.species("Mewtwo")!!
        val armored = SpeciesTable.formsFor("Mewtwo").first { it.name.contains("Armored") }
        // Build CP/HP from a known IV at a known level, so each is genuinely that form.
        val cpm = Cpm.byLevel[25.0]!!
        val iv = Iv(15, 15, 15)

        val asPlain = FormResolver.resolve(
            "Mewtwo", listOf("psychic"),
            IvDecoder.cp(mewtwo.stats, iv, cpm), IvDecoder.hp(mewtwo.stats, iv.stamina, cpm),
        )
        assertEquals("Mewtwo", asPlain.species?.name)

        val asArmored = FormResolver.resolve(
            "Mewtwo", listOf("psychic"),
            IvDecoder.cp(armored.stats, iv, cpm), IvDecoder.hp(armored.stats, iv.stamina, cpm),
        )
        assertEquals(armored.name, asArmored.species?.name)
    }

    /** Without CP/HP there is nothing to test feasibility against, so it must not guess. */
    @Test fun mewtwo_without_cp_and_hp_stays_unresolved() {
        val r = FormResolver.resolve("Mewtwo", listOf("psychic"))
        assertNull(r.species)
        assertTrue(r.candidates.size > 1)
    }

    // ---------- Fusions ----------

    /**
     * Necrozma's fusions each carry their own typing — psychic / psychic+ghost (Dawn Wings,
     * Lunala) / psychic+steel (Dusk Mane, Solgaleo) / psychic+dragon (Ultra) — so the
     * ordinary type row separates all four with no feasibility needed.
     */
    @Test fun necrozma_fusions_are_separated_by_type_alone() {
        assertEquals(
            "Necrozma (Dusk Mane)",
            FormResolver.resolve("Necrozma", listOf("psychic", "steel")).species?.name,
        )
        assertEquals(
            "Necrozma (Dawn Wings)",
            FormResolver.resolve("Necrozma", listOf("psychic", "ghost")).species?.name,
        )
        assertEquals(
            "Necrozma (Ultra)",
            FormResolver.resolve("Necrozma", listOf("psychic", "dragon")).species?.name,
        )
        assertEquals(
            "Necrozma",
            FormResolver.resolve("Necrozma", listOf("psychic")).species?.name,
        )
    }

    /**
     * Kyurem is the hard one: base, Black and White are ALL dragon/ice. Base has different
     * stats (246 attack vs 310), so feasibility separates it from the fusions.
     */
    @Test fun base_kyurem_is_separated_from_its_fusions_by_feasibility() {
        val base = SpeciesTable.species("Kyurem")!!
        val cpm = Cpm.byLevel[25.0]!!
        val iv = Iv(15, 15, 15)
        val r = FormResolver.resolve(
            "Kyurem", listOf("dragon", "ice"),
            IvDecoder.cp(base.stats, iv, cpm), IvDecoder.hp(base.stats, iv.stamina, cpm),
        )
        assertEquals("Kyurem", r.species?.name)
        assertTrue(r.nameIsCertain)
    }

    /**
     * Black and White Kyurem are identical in BOTH type and base stats (310/183/245), so no
     * amount of reading the screen can separate them. The right answer is not "unknown":
     * the IV maths is the same either way, so the IV is reported as exact while the NAME is
     * flagged as a tie. Same shape as the Pikachu costumes.
     */
    @Test fun fused_kyurem_reports_an_exact_iv_but_an_uncertain_name() {
        val black = SpeciesTable.species("Kyurem (Black)")!!
        val cpm = Cpm.byLevel[25.0]!!
        val iv = Iv(15, 15, 15)
        val r = FormResolver.resolve(
            "Kyurem", listOf("dragon", "ice"),
            IvDecoder.cp(black.stats, iv, cpm), IvDecoder.hp(black.stats, iv.stamina, cpm),
        )
        assertTrue("IV must still be decodable", r.ivIsSafe)
        assertTrue("but the label is a coin flip", !r.nameIsCertain)
        assertTrue(r.statsAgree)
        assertEquals(
            listOf("Kyurem (Black)", "Kyurem (White)"),
            r.candidates.map { it.name }.sorted(),
        )
    }

    // ---------- Guard rails ----------

    /** An impossible CP/HP must not empty the candidate set — that would read as a missing
     *  species rather than a bad read. */
    @Test fun an_unreadable_cp_leaves_the_candidates_intact() {
        val r = FormResolver.resolve("Kyurem", listOf("dragon", "ice"), cp = 1, hpMax = 1)
        assertTrue(r.candidates.isNotEmpty())
    }

    @Test fun a_species_with_no_alternate_forms_resolves_directly() {
        val r = FormResolver.resolve("Grimer", listOf("poison"))
        assertEquals("Grimer", r.species?.name)
        assertTrue(r.nameIsCertain)
    }
}
