package com.pokebuddy.ocr

import kotlin.math.abs

/**
 * A wild Pokémon on the encounter (pre-catch) screen.
 *
 * There is no HP and no appraisal here, so the IV genuinely CANNOT be decoded before the
 * catch — only CP is knowable. Callers must not imply otherwise.
 */
data class Encounter(val species: String, val cp: Int)

/**
 * Reads the encounter screen's "<Name>  CP <n>" banner.
 *
 * ML Kit groups that banner inconsistently across frames — sometimes one line
 * ("Pikachu / CP 208"), sometimes an overlapping fragment plus a separate name
 * ("achu / CP 250" + "Pikachu"). So rather than trusting the grouping, we find the CP
 * first and then look for a name on the same banner row, accepting the first candidate
 * that resolves to a real species. That validation is what rejects OCR fragments.
 */
object EncounterParser {

    private val CP = Regex("""CP\s*(\d{1,5})""", RegexOption.IGNORE_CASE)
    /** How far from the CP's row a name may sit, as a fraction of screen height. */
    private const val BAND_FRAC = 0.02

    /**
     * @param isSpecies resolves a candidate name against the base-stat table — injected so
     *   this parser stays JVM-testable and free of the iv package.
     */
    fun parse(ocr: OcrResult, isSpecies: (String) -> Boolean): Encounter? {
        val (line, cp) = ocr.lines.firstNotNullOfOrNull { l ->
            CP.find(l.text)?.let { l to it.groupValues[1].toInt() }
        } ?: return null

        val band = ocr.height * BAND_FRAC
        val species = ocr.lines
            .filter { abs(it.cy - line.cy) <= band }
            .flatMap { nameCandidates(it.text) }
            .firstOrNull(isSpecies) ?: return null
        return Encounter(species, cp)
    }

    /** Splits a banner line into the name-shaped pieces worth testing against the table. */
    private fun nameCandidates(text: String): List<String> =
        text.split('/', '|')
            .map { CP.replace(it, "").trim() }
            .filter { it.isNotEmpty() && it.any(Char::isLetter) }
}
