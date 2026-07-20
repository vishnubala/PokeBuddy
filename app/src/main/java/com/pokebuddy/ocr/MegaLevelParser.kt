package com.pokebuddy.ocr

/**
 * Mega level, read off the panel behind the DNA icon on a mega-capable Pokémon's detail
 * screen (tap the icon showing "7 DAYS" / "07:57 left").
 *
 * The level is NOT on the detail screen itself — that was checked by scrolling a
 * mega-evolved Raichu top to bottom — which is why this is a separate screen and parser.
 *
 * The panel is titled "<Species>'s Mega Level" and is SPECIES-scoped, not per-individual:
 * levelling any Raichu levels the species. Where a species has several megas it shows one
 * tab per variant ("MEGA RAICHU X" / "MEGA RAICHU Y") and the level belongs to the
 * SELECTED tab — hence the key here is species+variant, matching
 * [com.pokebuddy.db.MegaEnergy] exactly.
 */
data class MegaLevelInfo(
    /** Species the panel is about, from its title ("Raichu's Mega Level" → "Raichu"). */
    val species: String,
    /** Level name as shown on the banner: "Base Level", "High Level", "Max Level". */
    val level: String,
    /**
     * Which mega variant the level applies to ("X", "Y", or "" for an unsuffixed mega).
     *
     * Null when the screen shows several variant tabs and OCR alone cannot say which is
     * selected — the tabs differ only by fill colour, and both read as plain text. Callers
     * must not store a level against a guessed variant; see [variantTabs].
     */
    val variant: String?,
    /** Every variant tab on screen, in reading order. Single-entry when unambiguous. */
    val variantTabs: List<String>,
    /** "6 Days 17 Hours" beside REST PERIOD, or null when the species is ready to mega. */
    val restPeriod: String? = null,
)

object MegaLevelParser {

    private val TITLE_RE = Regex("^(.+?)'s Mega Level$", RegexOption.IGNORE_CASE)
    private val LEVEL_RE = Regex("^(Base|High|Max) Level$", RegexOption.IGNORE_CASE)
    // "MEGA RAICHU X" / "MEGA RAICHU" — the trailing variant letter is optional. OCR
    // clips the right edge of a tab often enough ("MEGA RAICHU Y N") that trailing noise
    // beyond a single letter is tolerated rather than failing the whole read.
    private val TAB_RE = Regex("^MEGA\\s+([A-Za-z]+)(?:\\s+([A-Z]))?\\b.*$")
    private val REST_RE = Regex("^\\d+\\s+Days?(\\s+\\d+\\s+Hours?)?$", RegexOption.IGNORE_CASE)

    /** Is this the mega-level panel? Requires the title, which nothing else renders. */
    fun isMegaLevelScreen(ocr: OcrResult): Boolean =
        ocr.lines.any { TITLE_RE.matches(it.text.trim()) }

    /** @return the panel's contents, or null if this isn't a mega-level screen. */
    fun parse(ocr: OcrResult): MegaLevelInfo? {
        val lines = ocr.lines
        val titleLine = lines.firstOrNull { TITLE_RE.matches(it.text.trim()) } ?: return null
        val species = TITLE_RE.find(titleLine.text.trim())!!.groupValues[1].trim()

        val levelLine = lines.firstOrNull { LEVEL_RE.matches(it.text.trim()) } ?: return null
        val level = levelLine.text.trim()

        // Tabs sit in the band BETWEEN the title and the level banner. That band matters:
        // the variant's mega-energy label at the foot of the panel reads
        // "MEGA RAICHU Y MEGA ENERGY", which matches the tab shape and would otherwise be
        // counted as a third tab and reorder the list.
        val tabs = lines
            .filter { it.cy > titleLine.cy && it.cy < levelLine.cy }
            .filter { it.text.trim().startsWith("MEGA ", ignoreCase = true) }
            .mapNotNull { line ->
                val m = TAB_RE.find(line.text.trim()) ?: return@mapNotNull null
                if (!m.groupValues[1].equals(species, ignoreCase = true)) null
                else line to m.groupValues[2].uppercase()
            }
            .sortedBy { it.first.box.l }
            .map { it.second }
            .distinct()

        return MegaLevelInfo(
            species = species,
            level = level,
            // One tab means one mega, so the level is unambiguously its. More than one and
            // the selected tab is a fill-colour difference OCR cannot see: report null and
            // let the caller decline to store rather than pick a variant at random.
            variant = tabs.singleOrNull(),
            variantTabs = tabs,
            restPeriod = lines.firstOrNull { REST_RE.matches(it.text.trim()) }?.text?.trim(),
        )
    }
}
