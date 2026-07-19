package com.pokebuddy.ocr

import com.pokebuddy.iv.Appraisal
import com.pokebuddy.iv.Iv
import com.pokebuddy.iv.IvDecoder
import com.pokebuddy.iv.SpeciesTable
import com.pokebuddy.iv.Stat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Validates the appraisal LOGIC chain: measured stat bars → coarse constraints →
 * (with CP + HP) exact IV — plus the PIXEL measurement itself, against a real
 * device capture ([appraisal_ponyta.png]).
 */
class AppraisalReaderTest {

    @Before fun setUp() = SpeciesTable.load("moltres,251,181,207\nponyta,170,127,137")

    @Test fun bar_estimates_map_to_correct_constraints() {
        val a = AppraisalReader.fromEstimates(14, 13, 11)
        assertEquals(setOf(Stat.ATTACK), a.bestStats)
        assertEquals(Appraisal.BEST_13_14, a.bestValueRange)
        assertEquals(IntRange(37, 45), a.ivSumRange)
    }

    @Test fun appraisal_from_bars_plus_cp_hp_pins_exact_iv() {
        val moltres = SpeciesTable["Moltres"]!!
        val appraisal = AppraisalReader.fromEstimates(14, 13, 11)
        val r = IvDecoder.decode(moltres, 2431, 145, appraisal, levels = listOf(25.0))
        assertEquals(Iv(14, 13, 11), r.exactIv)
    }

    // --- Pixel measurement, calibrated against a real capture ---
    //
    // Ground truth comes from the Calcy IV banner caught in the same frame
    // ("L15 IV80 13/10/13"), so the fixture carries its own answer key.

    @Test fun measures_real_appraisal_bars() {
        val (atk, def, sta) = measure("appraisal_ponyta")!!
        assertEquals("attack bar", 13, atk)
        assertEquals("defense bar", 10, def)
        assertEquals("stamina bar", 13, sta)
    }

    @Test fun real_capture_is_recognised_as_an_appraisal_screen() {
        assertNotNull(read("appraisal_ponyta"))
    }

    /** The whole point of the appraisal: bars + CP + HP must pin the IV exactly,
     *  and to the value Calcy independently reported. */
    @Test fun real_capture_plus_cp_hp_pins_the_calcy_iv() {
        val appraisal = read("appraisal_ponyta")!!
        val r = IvDecoder.decode(SpeciesTable["Ponyta"]!!, 702, 77, appraisal)
        assertEquals(Iv(13, 10, 13), r.exactIv)
    }

    /** End to end on real pixels: measured bars, corroborated by CP+HP, give the exact IV. */
    @Test fun real_capture_corroborates_to_exact_iv() {
        val (a, d, s) = measure("appraisal_ponyta")!!
        val r = IvDecoder.decodeCorroborated(SpeciesTable["Ponyta"]!!, 702, 77, Iv(a, d, s))
        assertEquals(Iv(13, 10, 13), r.exactIv)
    }

    private fun measure(name: String) = ocr(name).let { AppraisalReader.measure(bars(name, it), it) }

    private fun read(name: String) = ocr(name).let { AppraisalReader.read(bars(name, it), it) }

    /**
     * The bar band of a real capture, as gzipped raw RGB (see scripts/MakeCrop.java).
     * A cropped fixture rather than the source PNG because ImageIO/AWT are unavailable
     * when compiling against android.jar; gzip keeps it exact and small.
     *
     * Reports the FULL screen dimensions, since the reader derives its search windows
     * from screen fractions. Pixels outside the crop read as white — the card colour,
     * and [Px.OTHER] to the classifier.
     */
    private fun bars(name: String, screen: OcrResult): PixelSource {
        val d = java.io.DataInputStream(
            java.util.zip.GZIPInputStream(javaClass.getResourceAsStream("/$name.bars.gz"))
        )
        val ox = d.readInt(); val oy = d.readInt(); val w = d.readInt(); val h = d.readInt()
        val rgb = ByteArray(w * h * 3).also { d.readFully(it) }
        d.close()
        return object : PixelSource {
            override val width = screen.width
            override val height = screen.height
            override fun pixel(x: Int, y: Int): Int {
                val cx = x - ox; val cy = y - oy
                if (cx !in 0 until w || cy !in 0 until h) return 0xFFFFFF
                val i = (cy * w + cx) * 3
                return (rgb[i].toInt() and 0xFF shl 16) or
                    (rgb[i + 1].toInt() and 0xFF shl 8) or (rgb[i + 2].toInt() and 0xFF)
            }
        }
    }

    /** Reads back the fixture written by [OcrResult.toJson] — enough of a parser for a
     *  format we ourselves emit, avoiding a JSON dependency in unit tests. */
    private fun ocr(name: String): OcrResult {
        val text = javaClass.getResourceAsStream("/$name.json")!!.reader().readText()
        fun int(key: String, src: String) = Regex("\"$key\":\\s*(\\d+)").find(src)!!.groupValues[1].toInt()
        val lines = Regex("\\{\"text\":.*?\\}").findAll(text).map { m ->
            val s = m.value
            OcrLine(
                Regex("\"text\":\\s*\"(.*?)\"").find(s)!!.groupValues[1],
                Box(int("l", s), int("t", s), int("r", s), int("b", s)),
            )
        }.toList()
        return OcrResult(int("width", text), int("height", text), lines)
    }

    @Test fun star_tiers_by_total_iv() {
        assertEquals(IntRange(37, 45), AppraisalReader.tierForSum(45))
        assertEquals(IntRange(37, 45), AppraisalReader.tierForSum(37))
        assertEquals(IntRange(30, 36), AppraisalReader.tierForSum(30))
        assertEquals(IntRange(23, 29), AppraisalReader.tierForSum(23))
        assertEquals(IntRange(0, 22), AppraisalReader.tierForSum(22))
    }
}
