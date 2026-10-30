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

    class Outcome(val mask: Bitmap, val usedFallback: Boolean, val reason: String? = null)

    suspend fun run(original: Bitmap, onModelDownloading: () -> Unit): Outcome {
        val input = softwareCopy(original)
        var aiMask: Bitmap? = null
        val deadline = SystemClock.elapsedRealtime() + MODEL_WAIT_MS
        var notified = false
        var reason: String? = null
        val ml = MlKitSegmenter()
        while (true) {
            try {
                aiMask = ml.segment(input)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                reason = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(90)
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
        if (ai != null) reason = "no subject detected"
        ai?.recycle()

        val fb = GrabCutLiteSegmenter().segment(input)
        val cleaned = MaskCleanup.clean(fb)
        if (cleaned !== fb) fb.recycle()
        if (input !== original) input.recycle()
        return Outcome(cleaned, usedFallback = true, reason = reason)
    }

    /**
     * Applies the AI cut with a strength: 0 = [base] unchanged, 1 = full cut (base x ai), in between the
     * background fades out gradually. Always starts from the untouched [base], so it can be re-run live.
     */
    fun blend(base: Bitmap, ai: Bitmap, strength: Float): Bitmap {
        val w = base.width
        val h = base.height
        val n = w * h
        val b = IntArray(n)
        val a = IntArray(n)
        base.getPixels(b, 0, w, 0, 0, w, h)
        ai.getPixels(a, 0, w, 0, 0, w, h)
        for (i in 0 until n) {
            val ba = b[i] ushr 24
            val aa = a[i] ushr 24
            val na = (ba * (1f - strength * (1f - aa / 255f)) + 0.5f).toInt().coerceIn(0, 255)
            b[i] = (na shl 24) or 0x00FFFFFF
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(b, 0, w, 0, 0, w, h)
        return out
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
