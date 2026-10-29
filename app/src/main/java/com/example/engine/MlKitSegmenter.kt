package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Free, on-device AI subject segmentation (Google ML Kit, runs through Google Play services, so it
 * adds almost nothing to the APK). The model is downloaded once by Play services; afterwards it
 * works offline. Returns an alpha mask: 255 = subject, 0 = background.
 */
class MlKitSegmenter : SegmentationEngine {
    override val name: String = "ML Kit Subject Segmentation"
    override val isReady: Boolean = true

    override suspend fun segment(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val w = bitmap.width
        val h = bitmap.height
        // ML Kit works best on ~512..1024 px images.
        val longest = max(w, h).toFloat()
        val scale = when {
            longest > 1024f -> 1024f / longest
            longest < 512f -> 512f / longest
            else -> 1f
        }
        val inW = max(1, (w * scale).roundToInt())
        val inH = max(1, (h * scale).roundToInt())
        val input = if (inW == w && inH == h) bitmap else Bitmap.createScaledBitmap(bitmap, inW, inH, true)

        val options = SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
        val segmenter = SubjectSegmentation.getClient(options)
        try {
            val result = segmenter.process(InputImage.fromBitmap(input, 0)).await()
            val conf = result.foregroundConfidenceMask
                ?: throw IllegalStateException("No foreground mask")
            val count = inW * inH
            if (conf.capacity() < count) throw IllegalStateException("Unexpected mask size")

            val low = IntArray(count)
            for (i in 0 until count) {
                val t = ((conf.get(i) - 0.3f) / 0.4f).coerceIn(0f, 1f)
                val a = t * t * (3f - 2f * t)
                low[i] = Color.argb((a * 255f).toInt(), 255, 255, 255)
            }
            val lowMask = Bitmap.createBitmap(low, inW, inH, Bitmap.Config.ARGB_8888)
            val full = if (inW == w && inH == h) lowMask else Bitmap.createScaledBitmap(lowMask, w, h, true)
            if (full !== lowMask) lowMask.recycle()

            // Re-sharpen the up-scaled soft edge so the cut is crisp (about 1-2 px transition).
            val ratio = max(1f, max(w, h) / max(inW, inH).toFloat())
            val gain = max(1.8f, ratio * 1.4f)
            val px = IntArray(w * h)
            full.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val v = (((px[i] ushr 24) / 255f - 0.5f) * gain + 0.5f).coerceIn(0f, 1f)
                px[i] = Color.argb((v * 255f + 0.5f).toInt(), 255, 255, 255)
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            full.recycle()
            out
        } finally {
            segmenter.close()
            if (input !== bitmap) input.recycle()
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
