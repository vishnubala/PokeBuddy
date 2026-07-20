package com.pokebuddy.automation

import com.pokebuddy.ocr.Box
import com.pokebuddy.ocr.OcrLine
import com.pokebuddy.ocr.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [BoxScanner] against a simulated Pokémon GO box — a paged grid of tiles that
 * transitions to a detail screen on tap and back to the grid on back — so the whole
 * traversal is exercised with no device.
 */
class BoxScannerTest {

    private companion object {
        const val W = 1080
        const val H = 2400
        val COLUMNS = listOf(190, 540, 875)   // the three tile columns, cx values
    }

    /** A fake box: pages of (name, cp) tiles, navigable exactly as the real UI is. */
    private class FakeBox(private val pages: List<List<Pair<String, Int>>>) {
        private var page = 0
        private var detail: Pair<String, Int>? = null
        val scanned = mutableListOf<Pair<String, Int>>()

        private fun gridLines(): List<OcrLine> = buildList {
            pages[page].forEachIndexed { i, (name, cp) ->
                val col = COLUMNS[i % 3]
                val top = 400 + (i / 3) * 500
                // CP line, then the name ~244px below it in the same column.
                add(line("CP $cp", col, top))
                add(line(name, col, top + 244))
            }
        }

        private fun line(text: String, cx: Int, cy: Int): OcrLine {
            val halfW = 110; val halfH = 20
            return OcrLine(text, Box(cx - halfW, cy - halfH, cx + halfW, cy + halfH))
        }

        fun probe(): OcrResult {
            val d = detail
            val lines = if (d != null) {
                // A detail screen: the name (no digits) plus a single CP line.
                listOf(line("CP ${d.second}", 540, 200), line(d.first, 540, 900))
            } else gridLines()
            return OcrResult(W, H, lines)
        }

        fun tap(x: Int, y: Int): Boolean {
            // Match the tapped tile the way the real grid would: same column, CP line just
            // above the tap point.
            val tile = com.pokebuddy.ocr.GridParser.parse(gridLines()).firstOrNull {
                it.box.cx == x && y in it.box.cy..(it.box.cy + 400)
            } ?: return false
            detail = tile.name to tile.cp
            return true
        }

        fun back(): Boolean { detail = null; return true }

        fun scrollDown(): Boolean {
            if (page < pages.lastIndex) page++   // clamp at the last page (bottom of box)
            return true
        }

        fun scanCurrent(): Boolean {
            val d = detail ?: return false
            scanned += d
            return true
        }
    }

    private fun scannerFor(box: FakeBox, progress: MutableList<String> = mutableListOf()) =
        BoxScanner(
            tap = box::tap, back = box::back, scrollDown = box::scrollDown,
            probe = box::probe, scanCurrent = box::scanCurrent, sleep = {},
            onProgress = { progress += it.lastName },
        )

    @Test fun scans_every_tile_on_a_single_page() {
        val box = FakeBox(listOf(listOf("Pikachu" to 671, "Grimer" to 377, "Exeggcute" to 359)))
        val r = scannerFor(box).run()
        assertEquals(3, r.scanned)
        assertEquals(BoxScanner.Outcome.DONE, r.outcome)
        assertEquals(
            setOf("Pikachu" to 671, "Grimer" to 377, "Exeggcute" to 359),
            box.scanned.toSet(),
        )
    }

    @Test fun scans_across_multiple_pages_and_stops_at_the_bottom() {
        val box = FakeBox(
            listOf(
                listOf("Pikachu" to 671, "Grimer" to 377, "Exeggcute" to 359),
                listOf("Steelix" to 994, "Beedrill" to 761),
            )
        )
        val r = scannerFor(box).run()
        assertEquals(5, r.scanned)
        assertEquals(BoxScanner.Outcome.DONE, r.outcome)
        assertTrue(("Steelix" to 994) in box.scanned && ("Beedrill" to 761) in box.scanned)
    }

    /** A partial scroll leaves some tiles visible on both pages; they must not scan twice. */
    @Test fun overlapping_pages_do_not_double_scan() {
        val box = FakeBox(
            listOf(
                listOf("Pikachu" to 671, "Grimer" to 377, "Exeggcute" to 359),
                listOf("Exeggcute" to 359, "Steelix" to 994),   // Exeggcute repeats
            )
        )
        val r = scannerFor(box).run()
        assertEquals("Exeggcute scanned once despite appearing on both pages", 4, r.scanned)
        assertEquals(1, box.scanned.count { it == ("Exeggcute" to 359) })
    }

    @Test fun reports_not_on_box_when_no_tiles_are_present() {
        val box = FakeBox(listOf(emptyList()))
        val r = scannerFor(box).run()
        assertEquals(0, r.scanned)
        assertEquals(BoxScanner.Outcome.NOT_ON_BOX, r.outcome)
    }

    @Test fun honours_the_max_tiles_cap() {
        val box = FakeBox(listOf(listOf("Pikachu" to 671, "Grimer" to 377, "Exeggcute" to 359)))
        val r = scannerFor(box).run(maxTiles = 2)
        assertEquals(2, r.scanned)
        assertEquals(BoxScanner.Outcome.STOPPED_AT_LIMIT, r.outcome)
    }

    @Test fun reports_progress_per_pokemon() {
        val box = FakeBox(listOf(listOf("Pikachu" to 671, "Grimer" to 377)))
        val progress = mutableListOf<String>()
        scannerFor(box, progress).run()
        assertEquals(2, progress.size)
        assertTrue("Pikachu" in progress && "Grimer" in progress)
    }
}
