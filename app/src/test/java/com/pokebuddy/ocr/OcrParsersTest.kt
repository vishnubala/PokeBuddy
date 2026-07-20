package com.pokebuddy.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.pokebuddy.iv.MoveTable
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parser tests run on the JVM against fixtures captured from a real device
 * (OnePlus 8T, 1080x2400). No Android framework or emulator required — this is why
 * OcrLine/Box are framework-independent.
 */
class OcrParsersTest {

    private fun load(resource: String): OcrResult {
        val json = javaClass.classLoader!!.getResourceAsStream(resource)!!
            .bufferedReader().readText()
        val (w, h) = Regex("\"width\":\\s*(\\d+),\\s*\"height\":\\s*(\\d+)").find(json)!!
            .destructured.let { (a, b) -> a.toInt() to b.toInt() }
        val lineRe = Regex(
            "\\{\"text\":\\s*\"((?:\\\\.|[^\"\\\\])*)\",\\s*" +
                "\"l\":\\s*(\\d+),\\s*\"t\":\\s*(\\d+),\\s*\"r\":\\s*(\\d+),\\s*\"b\":\\s*(\\d+)"
        )
        val lines = lineRe.findAll(json).map { m ->
            val text = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            OcrLine(
                text,
                Box(m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt())
            )
        }.toList()
        return OcrResult(w, h, lines)
    }

    // ---------- Grid / box-scan ----------

    @Test fun grid_parses_all_tiles_ignoring_chrome_and_noise() {
        val tiles = GridParser.parse(load("grid_sample.json").lines)
        // 12 complete tiles; CP833 has no on-screen name, "CP"/"CPO" have no number.
        assertEquals(12, tiles.size)
        val map = tiles.associate { it.name to it.cp }
        assertEquals(2431, map["Moltres"])   // "çP 2431" prefix noise handled
        assertEquals(2252, map["Palkia"])    // "c 2252"
        assertEquals(1785, map["Unfezant"])
        assertEquals(989, map["Zygarde"])    // "cP989" no space
        assertEquals(922, map["Miltank"])    // "ce922"
        assertEquals(893, map["Oricorio"])   // "CP893"
    }

    @Test fun grid_sorted_by_cp_descending() {
        val tiles = GridParser.parse(load("grid_sample.json").lines)
        assertEquals(tiles.map { it.cp }, tiles.map { it.cp }.sortedDescending())
    }

    @Test fun grid_does_not_invent_a_tile_for_the_sort_button() {
        val tiles = GridParser.parse(load("grid_sample.json").lines)
        assertFalse(tiles.any { it.cp == 0 })
    }

    // ---------- Detail screen ----------

    @Test fun detail_extracts_core_fields() {
        val info = DetailParser.parse(load("detail_sample.json"))
        assertEquals("Moltres", info.name)
        assertEquals(145, info.hpCurrent)
        assertEquals(145, info.hpMax)
        assertEquals("35.45kg", info.weight)
        assertEquals("1.62m", info.height)
        assertEquals(134127, info.stardust)
        assertEquals(6, info.candy)
        assertEquals(listOf("FIRE", "FLYING"), info.types)
    }

    // ---------- Per-species resources (real Pikachu capture, 2026-07-19) ----------

    @Test fun detail_reads_every_mega_energy_variant() {
        val info = DetailParser.parse(load("detail_pikachu_mega.json"))
        // The screen carries BOTH variants; neither may be dropped or merged.
        assertEquals(
            listOf(
                MegaEnergyRead("Raichu", "X", 1350),
                MegaEnergyRead("Raichu", "Y", 1250),
            ),
            info.megaEnergy.sortedBy { it.variant },
        )
    }

    /** Mega energy is the EVOLUTION's resource — a Pikachu screen reports Raichu energy. */
    @Test fun mega_energy_is_keyed_to_the_mega_species_not_the_screen() {
        val info = DetailParser.parse(load("detail_pikachu_mega.json"))
        assertEquals("Pikachu", info.name)
        assertTrue(info.megaEnergy.all { it.species == "Raichu" })
    }

    @Test fun detail_reads_candy_with_its_own_species() {
        val info = DetailParser.parse(load("detail_pikachu_mega.json"))
        assertEquals(835, info.candy)
        assertEquals("Pikachu", info.candySpecies)
        assertEquals(150227, info.stardust)
    }

    /** A species with no mega must yield no rows at all, not a zero. */
    @Test fun species_without_a_mega_reports_no_energy() {
        assertTrue(DetailParser.parse(load("detail_sample.json")).megaEnergy.isEmpty())
    }

    // ---------- Tracked flags (real captures, 2026-07-19) ----------

    @Test fun lucky_is_read_from_the_text_under_the_name() {
        val info = DetailParser.parse(load("detail_lucky.json"))
        assertTrue(info.lucky)
        // The badge line must not be mistaken for the species itself.
        assertEquals("Exeggcute", info.name)
    }

    @Test fun a_pokemon_without_the_lucky_line_is_not_lucky() {
        assertFalse(DetailParser.parse(load("detail_sample.json")).lucky)
        assertFalse(DetailParser.parse(load("detail_dynamax.json")).lucky)
    }

    @Test fun dynamax_is_read_from_its_own_row() {
        val info = DetailParser.parse(load("detail_dynamax.json"))
        assertTrue(info.dynamax)
        assertEquals("Scorbunny", info.name)
    }

    @Test fun a_pokemon_without_the_dynamax_row_is_not_dynamax() {
        assertFalse(DetailParser.parse(load("detail_sample.json")).dynamax)
        assertFalse(DetailParser.parse(load("detail_lucky.json")).dynamax)
    }

    /**
     * The regression this guards: a size badge REPLACES the WEIGHT/HEIGHT label, so
     * anchoring only on "WEIGHT"/"HEIGHT" dropped both values — and those two are what
     * identity is matched on, so a badged Pokémon stopped matching its own row.
     */
    @Test fun size_badge_labels_still_yield_weight_and_height() {
        val scorbunny = DetailParser.parse(load("detail_dynamax.json"))
        assertEquals("3.85kg", scorbunny.weight)   // labelled LIGHTEST, not WEIGHT
        assertEquals("0.32m", scorbunny.height)    // labelled TALLEST, not HEIGHT

        val exeggcute = DetailParser.parse(load("detail_lucky.json"))
        assertEquals("3.03kg", exeggcute.weight)   // plain WEIGHT
        assertEquals("0.46m", exeggcute.height)    // labelled TALLEST
    }

    @Test fun size_badge_is_reported_and_absent_when_unremarkable() {
        assertEquals("LIGHTEST", DetailParser.parse(load("detail_dynamax.json")).sizeBadge)
        assertEquals("TALLEST", DetailParser.parse(load("detail_lucky.json")).sizeBadge)
        assertNull(DetailParser.parse(load("detail_sample.json")).sizeBadge)
    }

    // ---------- Mega level panel (real Raichu capture, 2026-07-19) ----------

    @Test fun mega_level_panel_is_recognised_and_parsed() {
        val ocr = load("mega_level.json")
        assertTrue(MegaLevelParser.isMegaLevelScreen(ocr))
        val info = MegaLevelParser.parse(ocr)!!
        assertEquals("Raichu", info.species)
        assertEquals("Base Level", info.level)
        assertEquals("6 Days 17 Hours", info.restPeriod)
    }

    /**
     * Raichu has two megas and the level belongs to whichever TAB is selected — a fill
     * colour OCR cannot see. Reporting a variant here would attach the level to the wrong
     * mega half the time, so both tabs are surfaced and the variant stays null.
     */
    @Test fun multi_mega_species_reports_tabs_but_no_variant() {
        val info = MegaLevelParser.parse(load("mega_level.json"))!!
        assertEquals(listOf("X", "Y"), info.variantTabs)
        assertNull(info.variant)
    }

    @Test fun a_detail_screen_is_not_a_mega_level_screen() {
        assertFalse(MegaLevelParser.isMegaLevelScreen(load("detail_pikachu_mega.json")))
        assertNull(MegaLevelParser.parse(load("detail_pikachu_mega.json")))
    }

    // ---------- Moveset (real scrolled Alolan Grimer capture, 2026-07-19) ----------

    @Test fun scrolled_detail_reads_both_moves() {
        MoveTable.load(java.io.File("src/main/assets/moves.csv").readText())
        val info = DetailParser.parse(load("detail_scrolled_moves.json"))
        // The type icon OCRs as a leading glyph, attached ("OSludge Bomb") or not.
        assertEquals("Poison Jab", info.fastMove)
        assertEquals("Sludge Bomb", info.chargedMove)
    }

    /** Fast vs charged comes from the move table, not row order, so a layout change
     *  can't quietly swap them. */
    @Test fun moves_are_classified_by_the_table_not_position() {
        MoveTable.load(java.io.File("src/main/assets/moves.csv").readText())
        assertTrue(MoveTable.byName("Poison Jab")!!.isFast)
        assertFalse(MoveTable.byName("Sludge Bomb")!!.isFast)
    }

    /** A second, independent capture — moves are read without the icon glyph prefix too. */
    @Test fun moves_are_read_from_an_unprefixed_capture() {
        MoveTable.load(java.io.File("src/main/assets/moves.csv").readText())
        val info = DetailParser.parse(load("detail_sample.json"))
        assertEquals("Wing Attack", info.fastMove)
        assertEquals("Fire Blast", info.chargedMove)
    }

    /** A screen with no move rows reports none rather than inventing them. */
    @Test fun screen_without_moves_reports_none() {
        MoveTable.load(java.io.File("src/main/assets/moves.csv").readText())
        val info = DetailParser.parse(load("grid_sample.json"))
        assertNull(info.fastMove)
        assertNull(info.chargedMove)
    }

    @Test fun detail_flags_occluded_cp_as_not_confident() {
        // This frame had the sprite over the last CP digit -> OCR read "CP243-".
        val info = DetailParser.parse(load("detail_sample.json"))
        assertEquals(243, info.cp)          // partial value still surfaced
        assertFalse(info.cpConfident)       // ...but flagged low-confidence, never as truth
    }
}

