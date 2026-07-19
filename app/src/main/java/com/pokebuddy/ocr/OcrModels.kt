package com.pokebuddy.ocr

/**
 * Framework-independent geometry + OCR result types.
 *
 * Deliberately NOT using android.graphics.Rect so the parsing layer
 * ([GridParser], [DetailParser]) is plain Kotlin and unit-testable on the JVM
 * with no Android/emulator dependency.
 */
data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val cx: Int get() = (l + r) / 2
    val cy: Int get() = (t + b) / 2
    val w: Int get() = r - l
    val h: Int get() = b - t
}

data class OcrLine(val text: String, val box: Box) {
    val cx: Int get() = box.cx
    val cy: Int get() = box.cy
}

/**
 * Read-only pixel access, framework-independent for the same reason as [Box]: the
 * appraisal stat bars are measured in pixels rather than OCR'd, and that measurement
 * has to be calibratable/testable against a real capture on the JVM.
 *
 * [pixel] returns packed ARGB, matching android.graphics.Bitmap.getPixel.
 */
interface PixelSource {
    val width: Int
    val height: Int
    fun pixel(x: Int, y: Int): Int
}

data class OcrResult(
    val width: Int,
    val height: Int,
    val lines: List<OcrLine>,
) {
    /** Compact JSON for offline analysis (correlate boxes against the saved PNG). */
    fun toJson(image: String): String = buildString {
        append("{\n")
        append("  \"image\": \"").append(image).append("\",\n")
        append("  \"width\": ").append(width).append(", \"height\": ").append(height).append(",\n")
        append("  \"lines\": [\n")
        lines.forEachIndexed { i, l ->
            val t = l.text.replace("\\", "\\\\").replace("\"", "\\\"")
            append("    {\"text\": \"").append(t).append("\", ")
            append("\"l\": ").append(l.box.l).append(", \"t\": ").append(l.box.t).append(", ")
            append("\"r\": ").append(l.box.r).append(", \"b\": ").append(l.box.b).append(", ")
            append("\"cx\": ").append(l.cx).append(", \"cy\": ").append(l.cy).append("}")
            append(if (i == lines.lastIndex) "\n" else ",\n")
        }
        append("  ]\n}")
    }
}
