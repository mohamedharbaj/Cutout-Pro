package com.example.engine

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * One-tap "AI-Auto". Order: (1) Google ML Kit AI, waiting for its one-time model download if needed;
 * (2) if the AI cannot run (no Play services / no internet yet / nothing detected) the built-in offline
 * engine. The button therefore always produces a usable cut.
 */
object AutoCutout {

    class Outcome(val mask: Bitmap, val usedFallback: Boolean)

    suspend fun run(original: Bitmap, onModelDownloading: () -> Unit): Outcome {
        val input = softwareCopy(original)
        var aiMask: Bitmap? = null
        val deadline = SystemClock.elapsedRealtime() + MODEL_WAIT_MS
        var notified = false
        val ml = MlKitSegmenter()
        while (true) {
            try {
                aiMask = ml.segment(input)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val waiting = e.message?.contains("download", ignoreCase = true) == true
                if (!waiting || SystemClock.elapsedRealtime() > deadline) break
                if (!notified) { notified = true; onModelDownloading() }
                delay(2500)
            }
        }

        val ai = aiMask
        if (ai != null && plausible(ai)) {
            val cleaned = MaskCleanup.clean(ai)
            if (cleaned !== ai) ai.recycle()
            if (input !== original) input.recycle()
            return Outcome(cleaned, usedFallback = false)
        }
        ai?.recycle()

        val fb = GrabCutLiteSegmenter().segment(input)
        val cleaned = MaskCleanup.clean(fb)
        if (cleaned !== fb) fb.recycle()
        if (input !== original) input.recycle()
        return Outcome(cleaned, usedFallback = true)
    }

    private fun softwareCopy(src: Bitmap): Bitmap =
        if (src.config == Bitmap.Config.ARGB_8888) src
        else src.copy(Bitmap.Config.ARGB_8888, false) ?: src

    /** The mask must contain a subject that is neither empty nor the whole picture. */
    private fun plausible(mask: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(mask, 64, 64, true)
        val px = IntArray(64 * 64)
        s.getPixels(px, 0, 64, 0, 0, 64, 64)
        s.recycle()
        var on = 0
        for (p in px) if ((p ushr 24) >= 128) on++
        val frac = on / (64f * 64f)
        return frac in 0.004f..0.995f
    }

    private const val MODEL_WAIT_MS = 30_000L
}
