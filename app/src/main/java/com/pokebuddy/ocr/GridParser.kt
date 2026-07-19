package com.pokebuddy.ocr

import kotlin.math.abs

/** One Pokémon tile from the box/storage grid: species name + CP. */
data class GridTile(val name: String, val cp: Int, val box: Box)

/**
 * Parses the Pokémon storage GRID (box-scan source screen).
 *
 * Layout learned from real captures: 3 columns (cx ≈ 190 / 540 / 875), each tile is
 * a CP line (~244px) above a species-name line in the same column. The "CP" glyph
 * OCRs inconsistently (çP / cP / c / ce / CP) so we key on the digits, not the prefix.
 */
object GridParser {

    // Trimmed line that is a CP tile: optional CP-ish prefix then the number.
    // Matches "çP 2431", "c 2252", "cP989", "CP833", "ce922". Rejects "CP" (no number),
    // "CPO" (letter O), "176/325" and "9/12" (slash), "13:48", battery text.
    private val CP_TILE = Regex("^[a-zç]{0,2}p?\\s*([0-9][0-9,]{0,4})$", RegexOption.IGNORE_CASE)

    private val CHROME = setOf("tags", "pokémon", "pokemon", "eggs", "search", "q search")

    private const val COLUMN_TOLERANCE = 130   // px; same-column if |Δcx| < this
    private const val MAX_CP_TO_NAME_GAP = 340  // px; name sits ~244 below its CP

    fun parse(lines: List<OcrLine>): List<GridTile> {
        val cpTiles = lines.mapNotNull { line ->
            val m = CP_TILE.find(line.text.trim()) ?: return@mapNotNull null
            val cp = m.groupValues[1].replace(",", "").toIntOrNull() ?: return@mapNotNull null
            if (cp < 10) null else line to cp
        }
        val names = lines.filter { isName(it.text) }

        return cpTiles.mapNotNull { (cpLine, cp) ->
            val name = names
                .filter {
                    abs(it.cx - cpLine.cx) < COLUMN_TOLERANCE &&
                        it.cy > cpLine.cy &&
                        it.cy - cpLine.cy < MAX_CP_TO_NAME_GAP
                }
                .minByOrNull { it.cy - cpLine.cy }
                ?: return@mapNotNull null
            GridTile(name.text.trim(), cp, cpLine.box)
        }.sortedByDescending { it.cp }
    }

    private fun isName(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2 || t.lowercase() in CHROME) return false
        if (t.any { it.isDigit() }) return false
        val letters = t.count { it.isLetter() }
        return letters >= 2 && letters.toDouble() / t.length >= 0.6
    }
}
