package com.pokebuddy.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun detail_flags_occluded_cp_as_not_confident() {
        // This frame had the sprite over the last CP digit -> OCR read "CP243-".
        val info = DetailParser.parse(load("detail_sample.json"))
        assertEquals(243, info.cp)          // partial value still surfaced
        assertFalse(info.cpConfident)       // ...but flagged low-confidence, never as truth
    }
}
