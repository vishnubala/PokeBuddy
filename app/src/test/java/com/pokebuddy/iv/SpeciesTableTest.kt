package com.pokebuddy.iv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Form resolution against the REAL bundled base_stats.csv — a hand-written fixture could
 * drift from the asset the app actually ships, and the whole point here is agreeing with it.
 */
class SpeciesTableTest {

    @Before fun setUp() =
        SpeciesTable.load(File("src/main/assets/base_stats.csv").readText())

    @Test fun plain_species_still_resolve() {
        assertEquals(BaseStats(193, 151, 155), SpeciesTable["Raichu"])
    }

    @Test fun regional_forms_resolve_despite_leading_word() {
        // The game says "Alolan Raichu"; the table says "raichualolan".
        assertEquals(SpeciesTable["Alolan Raichu"], SpeciesTable["raichualolan"])
        assertNotNull(SpeciesTable["Galarian Meowth"])
        assertNotNull(SpeciesTable["Hisuian Zorua"])
    }

    /** The form must win over the base species — that's the entire point. */
    @Test fun alolan_marowak_is_not_plain_marowak() {
        val alolan = SpeciesTable["Alolan Marowak"]
        assertNotNull(alolan)
        assertEquals(SpeciesTable["marowakalolan"], alolan)
    }

    @Test fun mega_forms_resolve_and_keep_their_variant_letter() {
        assertNotNull(SpeciesTable["Mega Venusaur"])
        // "Mega Charizard X" -> charizardmegax, distinct from the Y variant.
        val x = SpeciesTable["Mega Charizard X"]
        val y = SpeciesTable["Mega Charizard Y"]
        assertNotNull(x)
        assertNotNull(y)
        assertEquals(SpeciesTable["charizardmegax"], x)
        assertEquals(SpeciesTable["charizardmegay"], y)
    }

    @Test fun primal_forms_resolve() {
        assertNotNull(SpeciesTable["Primal Kyogre"])
    }

    /** Unknown names stay null rather than falling back to a wrong-but-plausible species. */
    @Test fun unknown_name_is_null_not_a_guess() {
        assertNull(SpeciesTable["Definitely Not A Pokemon"])
        assertNull(SpeciesTable["Alolan Moltres"])   // no such form
    }

    // ---------- Type-based form disambiguation ----------
    //
    // The case that matters: the detail screen shows a bare "Raichu" and only the type row
    // reveals it's the Alolan form.

    @Test fun bare_name_plus_types_picks_the_right_form() {
        val alolan = SpeciesTable.resolve("Raichu", listOf("ELECTRIC", "PSYCHIC"))
        assertEquals("Raichu (Alolan)", alolan?.name)

        val plain = SpeciesTable.resolve("Raichu", listOf("ELECTRIC"))
        assertEquals("Raichu", plain?.name)
    }

    @Test fun form_resolution_changes_the_stats_used() {
        val alolan = SpeciesTable.resolve("Raichu", listOf("ELECTRIC", "PSYCHIC"))!!
        val plain = SpeciesTable.resolve("Raichu", listOf("ELECTRIC"))!!
        // Different stats is the entire reason this matters for IV decoding.
        assertNotEquals(plain.stats, alolan.stats)
    }

    @Test fun explicit_form_name_resolves_without_needing_types() {
        assertEquals("Marowak (Alolan)", SpeciesTable.resolve("Alolan Marowak", emptyList())?.name)
    }

    @Test fun regional_forms_are_told_apart_by_type() {
        assertEquals("Moltres", SpeciesTable.resolve("Moltres", listOf("FIRE", "FLYING"))?.name)
        assertEquals(
            "Moltres (Galarian)",
            SpeciesTable.resolve("Moltres", listOf("DARK", "FLYING"))?.name,
        )
    }

    /** Moltres has a Galarian form, so a bare name with no types is honestly ambiguous. */
    @Test fun a_species_with_forms_needs_types() {
        assertNull(SpeciesTable.resolve("Moltres", emptyList()))
    }

    /** Mega forms are a battle state, not an owned species — they must not make every
     *  ordinary Raichu ambiguous, since Raichu and both its megas are pure electric. */
    @Test fun mega_forms_do_not_compete_in_disambiguation() {
        assertEquals("Raichu", SpeciesTable.resolve("Raichu", listOf("ELECTRIC"))?.name)
        // ...but an explicit mega name still resolves.
        assertEquals("Raichu (Mega X)", SpeciesTable.resolve("Mega Raichu X", emptyList())?.name)
    }

    /** Forms sharing a type must resolve to nothing rather than to a coin flip. */
    @Test fun indistinguishable_forms_return_null() {
        val deoxys = SpeciesTable.resolve("Deoxys", listOf("PSYCHIC"))
        assertNull(deoxys)
    }

    @Test fun forms_for_reports_the_choices_behind_an_ambiguous_name() {
        val forms = SpeciesTable.formsFor("Raichu").map { it.name }
        assertEquals(listOf("Raichu", "Raichu (Alolan)"), forms.sorted())
        // A species with no alternate forms isn't ambiguous.
        assertEquals(1, SpeciesTable.formsFor("Bulbasaur").size)
    }

    @Test fun family_is_shared_across_an_evolution_line() {
        assertEquals(
            SpeciesTable.species("Pikachu")?.family,
            SpeciesTable.species("Raichu")?.family,
        )
    }
}
