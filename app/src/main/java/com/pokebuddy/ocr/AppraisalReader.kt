package com.pokebuddy.ocr

import com.pokebuddy.iv.Appraisal
import com.pokebuddy.iv.Stat
import kotlin.math.roundToInt

/**
 * Reads the in-game APPRAISAL screen into decoder constraints.
 *
 * The appraisal shows three horizontal stat bars (Attack / Defense / HP), each filled to
 * IV/15 — these are graphics, not text, so we measure pixel fill rather than OCR them.
 * We anchor on the OCR'd "Attack"/"Defense"/"HP" labels (robust across resolutions) and
 * measure the bar in the strip to each label's right.
 *
 * The resulting [Appraisal] is intentionally COARSE (which stat is best, best-stat band,
 * total-IV tier) — matching what the in-game appraisal actually conveys. Exact IVs come
 * from feeding this into IvDecoder together with CP + HP, so a ±1 bar-read error is
 * absorbed by the CP/HP disambiguation rather than producing a wrong "exact" claim.
 *
 * CALIBRATION TODO(appraisal): the bar-fill color threshold and the track's right extent
 * ([TRACK_WIDTH_FRAC]) are first-pass values. They need validating against a live
 * appraisal capture on-device (a charged phone) before this is trusted — the pure mapping
 * below (bars → constraints) is unit-tested; the pixel measurement is not yet calibrated.
 */
object AppraisalReader {

    // The bar sits BELOW its label and is left-aligned with it, so we search downward
    // from the label's bottom edge rather than rightward from its side.
    private const val BAR_SEARCH_FRAC = 0.04     // of screen height, below the label
    private const val TRACK_SCAN_FRAC = 0.50     // of screen width, generous scan cap
    private const val LEAD_IN_PX = 24            // slack around the label's left edge
    // The three segments are separated by card-coloured gaps a few px wide; tolerate
    // them while walking the track, but stop once the card proper resumes.
    private const val GAP_TOLERANCE_PX = 14
    private const val MIN_TRACK_FRAC = 0.15      // of screen width, else "not a bar"

    // Filled segment is a strong orange (#f1a64a); the empty track is a near-neutral
    // light grey (#e2e2e0); the card behind both is white. Measured off a real capture.
    private const val FILL_SATURATION_MIN = 0.35f
    private const val TRACK_SATURATION_MAX = 0.15f
    private const val TRACK_LEVEL_MIN = 195
    private const val TRACK_LEVEL_MAX = 245

    private enum class Px { FILL, TRACK, OTHER }

    /** Is this an appraisal screen? Requires the three stat labels stacked together. */
    fun isAppraisalScreen(ocr: OcrResult): Boolean =
        label(ocr, "Attack") != null && label(ocr, "Defense") != null &&
            labelHp(ocr) != null

    /** @return decoder constraints, or null if this isn't a readable appraisal screen. */
    fun read(pixels: PixelSource, ocr: OcrResult): Appraisal? {
        val (a, d, h) = measure(pixels, ocr) ?: return null
        return fromEstimates(a, d, h)
    }

    /** The three raw bar reads (attack, defense, stamina), each 0..15. Null if not an
     *  appraisal screen. Exposed separately so calibration can assert on the bars
     *  themselves rather than only on the coarse constraints they collapse into. */
    fun measure(pixels: PixelSource, ocr: OcrResult): Triple<Int, Int, Int>? {
        val atk = label(ocr, "Attack") ?: return null
        val def = label(ocr, "Defense") ?: return null
        val hp = labelHp(ocr) ?: return null
        // A missing bar yields null rather than 0 — reporting "IV 0" for a bar we simply
        // failed to find would be presenting a failure as a confident answer.
        val a = barIv(pixels, atk) ?: return null
        val d = barIv(pixels, def) ?: return null
        val h = barIv(pixels, hp) ?: return null
        return Triple(a, d, h)
    }

    /** Pure: three IV estimates (0..15) → coarse appraisal constraints. */
    fun fromEstimates(atk: Int, def: Int, hp: Int): Appraisal {
        val max = maxOf(atk, def, hp)
        val best = buildSet {
            if (atk == max) add(Stat.ATTACK)
            if (def == max) add(Stat.DEFENSE)
            if (hp == max) add(Stat.STAMINA)
        }
        return Appraisal(
            ivSumRange = tierForSum(atk + def + hp),
            bestStats = best,
            bestValueRange = bandFor(max),
        )
    }

    /** In-game "best stat" descriptor bands. */
    private fun bandFor(v: Int): IntRange = when {
        v >= 15 -> Appraisal.BEST_15
        v >= 13 -> Appraisal.BEST_13_14
        v >= 8 -> Appraisal.BEST_8_12
        else -> Appraisal.BEST_0_7
    }

    /** In-game appraisal star tiers by total IV. */
    fun tierForSum(sum: Int): IntRange = when {
        sum >= 37 -> IntRange(37, 45)
        sum >= 30 -> IntRange(30, 36)
        sum >= 23 -> IntRange(23, 29)
        else -> IntRange(0, 22)
    }

    /**
     * Measures one stat bar: scans the rows below [label] for the widest bar-like run,
     * then reads that row as filled-vs-empty.
     *
     * The bar is drawn as three segments of five IV each, separated by card-coloured
     * gaps. Rather than treating the track as one continuous span (which would let the
     * dead gap pixels skew the ratio), we count only *bar* pixels — filled and empty —
     * and let the gaps fall out of both sides of the fraction.
     *
     * We take the FIRST contiguous band of bar-like rows, not the widest one anywhere in
     * the window: a bar belongs to the label directly above it, while other, wider UI
     * elements live further down the card — the HP label's window reaches one, and it
     * would otherwise win on width alone.
     *
     * @return 0..15, or null if no bar was found below the label.
     */
    private fun barIv(pixels: PixelSource, label: OcrLine): Int? {
        val searchEnd = (label.box.b + pixels.height * BAR_SEARCH_FRAC).toInt()
            .coerceAtMost(pixels.height - 1)
        val minTrack = pixels.width * MIN_TRACK_FRAC
        var best: Pair<Int, Int>? = null   // filled, empty — widest row of the first band
        for (y in label.box.b.coerceAtLeast(0)..searchEnd) {
            val row = scanRow(pixels, y, label.box.l)
            if (row != null && row.first + row.second >= minTrack) {
                if (best == null || row.first + row.second > best.first + best.second) best = row
            } else if (best != null) {
                break   // band ended — stop before any later, unrelated wide element
            }
        }
        val (filled, empty) = best ?: return null
        return (filled.toFloat() / (filled + empty) * 15f).roundToInt().coerceIn(0, 15)
    }

    /** Walks one row left-to-right from the label's left edge. @return (filled, empty)
     *  bar-pixel counts, or null if the row holds no bar. */
    private fun scanRow(pixels: PixelSource, y: Int, labelLeft: Int): Pair<Int, Int>? {
        val scanEnd = (labelLeft + pixels.width * TRACK_SCAN_FRAC).toInt()
            .coerceAtMost(pixels.width - 1)
        var x = (labelLeft - LEAD_IN_PX).coerceAtLeast(0)
        // Skip card whitespace before the track actually starts.
        while (x <= scanEnd && classify(pixels.pixel(x, y)) == Px.OTHER) x++
        if (x > scanEnd) return null

        var filled = 0
        var empty = 0
        var gap = 0
        while (x <= scanEnd) {
            when (classify(pixels.pixel(x, y))) {
                Px.FILL -> { filled++; gap = 0 }
                Px.TRACK -> { empty++; gap = 0 }
                Px.OTHER -> { gap++; if (gap > GAP_TOLERANCE_PX) break }
            }
            x++
        }
        return if (filled + empty > 0) filled to empty else null
    }

    private fun classify(color: Int): Px {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val sat = if (max == 0) 0f else (max - min).toFloat() / max
        return when {
            sat >= FILL_SATURATION_MIN -> Px.FILL
            sat <= TRACK_SATURATION_MAX && max in TRACK_LEVEL_MIN..TRACK_LEVEL_MAX -> Px.TRACK
            else -> Px.OTHER
        }
    }

    private fun label(ocr: OcrResult, text: String): OcrLine? =
        ocr.lines.firstOrNull { it.text.trim().equals(text, ignoreCase = true) }

    // On the appraisal screen "HP" is a standalone label; guard against the detail-screen
    // "145 / 145 HP" line by requiring the label to be essentially just "HP".
    private fun labelHp(ocr: OcrResult): OcrLine? =
        ocr.lines.firstOrNull { it.text.trim().equals("HP", ignoreCase = true) }
}
