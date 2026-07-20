package com.pokebuddy.automation

import com.pokebuddy.ocr.GridParser
import com.pokebuddy.ocr.OcrResult

/**
 * Full box scan: visits every Pokémon's DETAIL page one at a time and runs the normal
 * detail-scan pipeline on each, so the index ends up with the same rich record (IV, moves,
 * identity, flags) it would get from scanning them by hand.
 *
 * A box TILE only shows species + CP — no weight/height, HP or IV — so a tile is never
 * persisted directly. It is only a tap target. The value comes from opening the detail page
 * behind it, which is why this drives navigation rather than reading the grid.
 *
 * CLOSED LOOP, like [AutoAppraiser]: every step re-reads the screen and decides from what's
 * actually there, because Pokémon GO is Unity — no accessibility tree, no transition events,
 * and animations mean a fixed-delay script drifts out of sync.
 *
 * Safe to over-scan. The detail pipeline dedups on the immutable weight/height identity
 * (`findByIdentity`, proven on device to survive a power-up), so re-visiting a Pokémon
 * UPDATES its row rather than duplicating it. That turns the hard problem (tracking exactly
 * which tiles were done across scrolls) into a soft one: overlap between pages is merely
 * slower, never wrong.
 *
 * Dependencies are injected so the whole traversal is unit-testable with no device.
 */
class BoxScanner(
    private val tap: (x: Int, y: Int) -> Boolean,
    private val back: () -> Boolean,
    private val scrollDown: () -> Boolean,
    private val probe: () -> OcrResult?,
    /** Runs the burst+persist on the current detail screen; true if a Pokémon was recorded. */
    private val scanCurrent: () -> Boolean,
    private val sleep: (Long) -> Unit,
    private val onProgress: (Progress) -> Unit = {},
) {

    data class Progress(val scanned: Int, val lastName: String)

    data class Result(val scanned: Int, val outcome: Outcome)

    enum class Outcome {
        /** Reached the bottom of the box — a scroll revealed no new tiles. */
        DONE,
        /** Hit [maxTiles] before the end; the box is larger than the cap. */
        STOPPED_AT_LIMIT,
        /** The starting screen wasn't the box grid (no tiles, and nothing scanned). */
        NOT_ON_BOX,
        NO_FRAME,
    }

    companion object {
        // How far below a tile's CP line to tap — lands on the sprite/name, safely inside
        // the tile rather than on the CP glyph at its top edge. Fraction of screen height.
        private const val TILE_TAP_DY_FRAC = 0.05

        // A detail page animates in; give it a few reads before deciding the tap missed.
        private const val DETAIL_PROBES = 6
        private const val GRID_PROBES = 6
        private const val PROBE_PAUSE_MS = 350L
        private const val SCROLL_SETTLE_MS = 700L

        // Safety cap so a misread (e.g. the grid never reappears) can't loop forever.
        private const val MAX_PAGES = 40
    }

    /**
     * @param maxTiles hard cap on detail pages visited, so an unexpectedly huge box (or a
     *   traversal that isn't advancing) can't run unbounded.
     */
    fun run(maxTiles: Int = 300): Result {
        // (name|cp) already scanned THIS run, so page overlap after a partial scroll doesn't
        // re-tap the same tiles. Two Pokémon identical in species AND cp collapse here — the
        // grid can't tell them apart anyway — which under-scans that rare pair by one.
        val scannedSigs = HashSet<String>()
        // Page fingerprints seen, to notice when a scroll stops moving (bottom of box).
        val seenPages = HashSet<String>()
        var scanned = 0

        repeat(MAX_PAGES) {
            val grid = probe() ?: return Result(scanned, Outcome.NO_FRAME)
            val tiles = ordered(GridParser.parse(grid.lines))
            if (tiles.isEmpty()) {
                return Result(scanned, if (scanned > 0) Outcome.DONE else Outcome.NOT_ON_BOX)
            }

            val pageFp = tiles.joinToString("&") { "${it.name}|${it.cp}" }
            // A scroll that reveals a page we've already seen means we've hit the bottom (or
            // the grid didn't move at all) — either way there's nothing new to scan.
            if (!seenPages.add(pageFp)) return Result(scanned, Outcome.DONE)

            for (tile in tiles) {
                val sig = "${tile.name}|${tile.cp}"
                if (!scannedSigs.add(sig)) continue   // overlap from the previous page

                val tx = tile.box.cx
                val ty = tile.box.cy + (grid.height * TILE_TAP_DY_FRAC).toInt()
                if (!tap(tx, ty)) continue

                if (waitForDetail()) {
                    if (scanCurrent()) {
                        scanned++
                        onProgress(Progress(scanned, tile.name))
                    }
                }
                // Always return to the grid, even if the tap opened nothing useful, so the
                // next iteration starts from a known screen.
                back()
                if (!waitForGrid()) return Result(scanned, Outcome.NO_FRAME)

                if (scanned >= maxTiles) return Result(scanned, Outcome.STOPPED_AT_LIMIT)
            }

            scrollDown()
            sleep(SCROLL_SETTLE_MS)
        }
        return Result(scanned, Outcome.STOPPED_AT_LIMIT)
    }

    /** Poll until the current screen looks like a detail page (a species name is present). */
    private fun waitForDetail(): Boolean = waitFor(DETAIL_PROBES) { ocr ->
        // A detail screen has a name line but is NOT the grid (which has many CP tiles).
        GridParser.parse(ocr.lines).size < 2 &&
            ocr.lines.any { it.text.trim().length >= 3 && it.text.none(Char::isDigit) }
    }

    /** Poll until the box grid is back (several CP tiles visible again). */
    private fun waitForGrid(): Boolean = waitFor(GRID_PROBES) { ocr ->
        GridParser.parse(ocr.lines).size >= 2
    }

    private fun waitFor(times: Int, predicate: (OcrResult) -> Boolean): Boolean {
        repeat(times) {
            val ocr = probe()
            if (ocr != null && predicate(ocr)) return true
            sleep(PROBE_PAUSE_MS)
        }
        return false
    }

    /** Top-to-bottom, left-to-right — the natural reading order, so progress feels ordered.
     *  GridParser sorts by CP, which is spatially arbitrary. */
    private fun ordered(tiles: List<com.pokebuddy.ocr.GridTile>) =
        tiles.sortedWith(compareBy({ it.box.t / 200 }, { it.box.l }))
}
