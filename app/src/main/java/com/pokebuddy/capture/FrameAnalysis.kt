package com.pokebuddy.capture

import android.graphics.Bitmap

/**
 * Verdict on a single captured frame: is it usable for OCR, or blacked out by FLAG_SECURE?
 *
 * MediaProjection returns all-black (or all-zero-alpha) frames for any window that sets
 * FLAG_SECURE. If Pokémon GO ever sets it, the entire screenshot+OCR architecture is dead —
 * so this is the one measurement the whole project is gated on.
 */
data class FrameVerdict(
    val width: Int,
    val height: Int,
    val blackRatio: Float,      // fraction of sampled pixels that are near-black
    val avgLuminance: Float,    // 0..255 mean luminance of sampled pixels
    val sampledPixels: Int,
) {
    /** Heuristic: a real game screen has plenty of non-black pixels. */
    val looksBlackedOut: Boolean get() = blackRatio > 0.97f

    fun summary(): String = buildString {
        appendLine("Frame: ${width}x$height")
        appendLine("Sampled pixels: $sampledPixels")
        appendLine("Near-black ratio: ${"%.3f".format(blackRatio)}")
        appendLine("Avg luminance: ${"%.1f".format(avgLuminance)} / 255")
        appendLine()
        if (looksBlackedOut) {
            appendLine("VERDICT: BLACKED OUT ❌")
            appendLine("Screen appears FLAG_SECURE-protected. OCR approach is NOT viable")
            appendLine("against this app/screen. Stop before building on it.")
        } else {
            appendLine("VERDICT: READABLE ✅")
            appendLine("Frame has real content. Screenshot+OCR is viable.")
        }
    }
}

object FrameAnalysis {

    private const val NEAR_BLACK_LUMA = 12f   // luminance <= this counts as "black"

    /**
     * Samples a grid of up to ~maxSamples pixels (full-frame scan is unnecessary and slow)
     * and reports how black the frame is.
     */
    fun analyze(bitmap: Bitmap, maxSamples: Int = 40_000): FrameVerdict {
        val w = bitmap.width
        val h = bitmap.height
        val total = w.toLong() * h.toLong()
        // Stride so total samples land near maxSamples.
        val step = maxOf(1, Math.sqrt(total.toDouble() / maxSamples).toInt())

        var blackCount = 0
        var sampled = 0
        var lumaSum = 0.0

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // Rec. 601 luma
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                lumaSum += luma
                if (luma <= NEAR_BLACK_LUMA) blackCount++
                sampled++
                x += step
            }
            y += step
        }

        val ratio = if (sampled == 0) 1f else blackCount.toFloat() / sampled
        val avg = if (sampled == 0) 0f else (lumaSum / sampled).toFloat()
        return FrameVerdict(w, h, ratio, avg, sampled)
    }
}
