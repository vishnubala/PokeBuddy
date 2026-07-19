package com.pokebuddy.ocr

import kotlin.math.abs

/**
 * Structured fields from a Pokémon DETAIL screen. Nullable: any field can be missing
 * on a given frame (e.g. the animating sprite occluding the CP number).
 */
data class DetailInfo(
    val name: String? = null,
    val cp: Int? = null,
    /** false when the CP line had stray glyphs (partial read — sprite occlusion). */
    val cpConfident: Boolean = false,
    val hpCurrent: Int? = null,
    val hpMax: Int? = null,
    val weight: String? = null,
    val height: String? = null,
    val types: List<String> = emptyList(),
    val stardust: Int? = null,
    val candy: Int? = null,
    val candyXl: Int? = null,
    /** Species named by the CANDY label ("PIKACHU CANDY" → "Pikachu"). Candy is shared
     *  across an evolution family, so it is not necessarily [name]. */
    val candySpecies: String? = null,
    /** One entry per mega-energy label found; empty for species without a mega. */
    val megaEnergy: List<MegaEnergyRead> = emptyList(),
)

/**
 * A single "<SPECIES> MEGA ENERGY [X|Y]" reading.
 *
 * [species] is the MEGA's species, which is NOT the Pokémon whose screen this came from —
 * a Pikachu's detail screen reports RAICHU mega energy.
 */
data class MegaEnergyRead(val species: String, val variant: String, val amount: Int)

/**
 * Parses a Pokémon detail screen. Most fields are found via a label anchor: the value
 * sits directly above its ALL-CAPS label ("35.45kg" above "WEIGHT"), which is far more
 * robust than hard-coded coordinates across devices/resolutions.
 */
object DetailParser {

    private val HP_RE = Regex("(\\d+)\\s*/\\s*(\\d+)\\s*HP", RegexOption.IGNORE_CASE)
    private val CP_RE = Regex("c[p]?\\s*([0-9]{2,5})", RegexOption.IGNORE_CASE)
    private val CP_CLEAN = Regex("^c[p]?\\s*[0-9]{2,5}$", RegexOption.IGNORE_CASE)
    private val TYPE_RE = Regex("^([A-Za-z]{3,})\\s*/\\s*([A-Za-z]{3,})$")
    // "RAICHU MEGA ENERGY X" / "VENUSAUR MEGA ENERGY". The trailing variant is optional,
    // and any number of variants can appear on one screen.
    private val MEGA_RE = Regex("^(.+?)\\s+MEGA ENERGY\\s*([A-Z]?)$", RegexOption.IGNORE_CASE)
    // "PIKACHU CANDY" but NOT "PIKACHU CANDY XL" — XL is a separate resource.
    private val CANDY_RE = Regex("^(.+?)\\s+CANDY$", RegexOption.IGNORE_CASE)
    private val CANDY_XL_RE = Regex("^(.+?)\\s+CANDY\\s*XL$", RegexOption.IGNORE_CASE)

    fun parse(result: OcrResult): DetailInfo {
        val lines = result.lines
        val w = result.width
        val topBand = result.height * 0.22   // CP lives near the top

        val hpLine = lines.firstOrNull { HP_RE.containsMatchIn(it.text) }
        val hp = hpLine?.let { HP_RE.find(it.text) }

        // CP: a CP line in the top band. Prefer a clean one if several frames merged.
        val cpLine = lines
            .filter { it.cy < topBand && CP_RE.containsMatchIn(it.text) }
            .minByOrNull { it.cy }
        val cp = cpLine?.let { CP_RE.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
        val cpConfident = cpLine?.let { CP_CLEAN.matches(it.text.trim()) } ?: false

        // Name: tallest centered alphabetic line above the HP line.
        val name = lines
            .filter { line ->
                val alpha = line.text.trim()
                alpha.length in 2..20 &&
                    alpha.none { it.isDigit() } &&
                    alpha.count { it.isLetter() } >= 2 &&
                    abs(line.cx - w / 2) < w * 0.28 &&
                    (hpLine == null || line.cy < hpLine.cy) &&
                    line.cy > result.height * 0.40 &&
                    !TYPE_RE.matches(alpha)
            }
            .maxByOrNull { it.box.h }
            ?.text?.trim()

        val types = lines.firstNotNullOfOrNull { line ->
            TYPE_RE.find(line.text.trim())?.let { listOf(it.groupValues[1], it.groupValues[2]) }
        } ?: emptyList()

        return DetailInfo(
            name = name,
            cp = cp,
            cpConfident = cpConfident,
            hpCurrent = hp?.groupValues?.get(1)?.toIntOrNull(),
            hpMax = hp?.groupValues?.get(2)?.toIntOrNull(),
            weight = valueAbove(lines) { it.equals("WEIGHT", true) }?.text?.trim(),
            height = valueAbove(lines) { it.equals("HEIGHT", true) }?.text?.trim(),
            stardust = valueAbove(lines) { it.equals("STARDUST", true) }
                ?.text?.filter { it.isDigit() }?.toIntOrNull(),
            candy = valueAbove(lines) { CANDY_RE.matches(it) }
                ?.text?.filter { it.isDigit() }?.toIntOrNull(),
            candyXl = valueAbove(lines) { CANDY_XL_RE.matches(it) }
                ?.text?.filter { it.isDigit() }?.toIntOrNull(),
            candySpecies = lines.firstNotNullOfOrNull {
                CANDY_RE.find(it.text.trim())?.groupValues?.get(1)?.titleCase()
            },
            megaEnergy = megaEnergy(lines),
            types = types,
        )
    }

    /** Every mega-energy label on the screen, each paired with the number above it. */
    private fun megaEnergy(lines: List<OcrLine>): List<MegaEnergyRead> =
        lines.mapNotNull { line ->
            val m = MEGA_RE.find(line.text.trim()) ?: return@mapNotNull null
            val amount = valueAbove(lines) { it == line.text.trim() }
                ?.text?.filter { it.isDigit() }?.toIntOrNull() ?: return@mapNotNull null
            MegaEnergyRead(
                species = m.groupValues[1].titleCase(),
                variant = m.groupValues[2].uppercase(),
                amount = amount,
            )
        }

    /** "RAICHU" → "Raichu", matching how species are keyed elsewhere. */
    private fun String.titleCase(): String =
        trim().lowercase().replaceFirstChar { it.uppercase() }

    /** The line directly above the first label matching [labelMatch], in the same column. */
    private fun valueAbove(lines: List<OcrLine>, labelMatch: (String) -> Boolean): OcrLine? {
        val label = lines.firstOrNull { labelMatch(it.text.trim()) } ?: return null
        return lines
            .filter { it !== label && abs(it.cx - label.cx) < 150 && it.cy < label.cy && label.cy - it.cy < 130 }
            .minByOrNull { label.cy - it.cy }
    }
}
