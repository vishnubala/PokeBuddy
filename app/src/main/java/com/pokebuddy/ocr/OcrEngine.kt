package com.pokebuddy.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Thin wrapper over ML Kit's on-device Latin text recognizer.
 *
 * The recognizer client is reusable and cheap to hold; ML Kit runs the model on a
 * background thread pool. [recognizeBlocking] is meant to be called from a worker
 * thread (e.g. the capture service's HandlerThread), never the main thread.
 */
class OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun recognizeBlocking(bitmap: Bitmap): OcrResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = Tasks.await(recognizer.process(image))
        val lines = buildList {
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    val r = line.boundingBox ?: continue
                    add(OcrLine(line.text, Box(r.left, r.top, r.right, r.bottom)))
                }
            }
        }
        return OcrResult(bitmap.width, bitmap.height, lines)
    }

    fun close() = recognizer.close()
}
