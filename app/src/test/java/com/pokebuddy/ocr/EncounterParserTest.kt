package com.pokebuddy.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Both fixtures are real captures of the SAME encounter screen moments apart — ML Kit
 * grouped the banner differently each time, which is exactly what the parser must absorb.
 */
class EncounterParserTest {

    private val dex = setOf("pikachu", "yamper", "ponyta")
    private val isSpecies: (String) -> Boolean =
        { it.lowercase().filter(Char::isLetterOrDigit) in dex }

    @Test fun reads_a_banner_merged_into_one_line() {
        assertEquals(Encounter("Pikachu", 208), parse("encounter_merged"))
    }

    @Test fun reads_a_banner_split_into_a_fragment_plus_a_name() {
        // "achu / CP 250" must lose to the separate "Pikachu" line — the species table is
        // what tells a real name from an OCR fragment.
        assertEquals(Encounter("Pikachu", 250), parse("encounter_split"))
    }

    @Test fun ignores_the_calcy_overlay_banner_on_the_same_screen() {
        // "L9 Iv53 2/13/9" sits well above the banner row and carries no CP.
        val e = parse("encounter_merged")!!
        assertEquals("Pikachu", e.species)
    }

    @Test fun returns_null_when_no_cp_is_present() {
        assertNull(EncounterParser.parse(OcrResult(1080, 2400, listOf(
            OcrLine("Pikachu", Box(324, 820, 526, 871)),
        )), isSpecies))
    }

    @Test fun returns_null_when_the_name_is_not_a_known_species() {
        assertNull(EncounterParser.parse(OcrResult(1080, 2400, listOf(
            OcrLine("Xyzzy / CP 208", Box(324, 817, 812, 879)),
        )), isSpecies))
    }

    private fun parse(fixture: String) = EncounterParser.parse(ocr(fixture), isSpecies)

    private fun ocr(name: String): OcrResult {
        val text = javaClass.getResourceAsStream("/$name.json")!!.reader().readText()
        fun int(k: String, s: String) = Regex("\"$k\":\\s*(\\d+)").find(s)!!.groupValues[1].toInt()
        val lines = Regex("\\{\"text\":.*?\\}").findAll(text).map {
            val s = it.value
            OcrLine(Regex("\"text\":\\s*\"(.*?)\"").find(s)!!.groupValues[1],
                Box(int("l", s), int("t", s), int("r", s), int("b", s)))
        }.toList()
        return OcrResult(int("width", text), int("height", text), lines)
    }
}
