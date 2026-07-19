package com.pokebuddy.ocr

import android.graphics.Bitmap

/**
 * Adapts a captured frame to the framework-independent [PixelSource] the appraisal
 * bar measurement works against. Keeping the Android type at this boundary is what
 * lets the measurement itself be calibrated against real PNGs in a JVM test.
 */
class BitmapPixels(private val bitmap: Bitmap) : PixelSource {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override fun pixel(x: Int, y: Int): Int = bitmap.getPixel(x, y)
}

fun Bitmap.asPixelSource(): PixelSource = BitmapPixels(this)
