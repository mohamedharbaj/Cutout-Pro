package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline subject extraction used whenever the Google AI model is not available.
 * GrabCut-style: colour models of the background (taken from the picture borders) and of the subject
 * (taken from the centre) are refined over several rounds, a contrast-aware smoothing keeps the cut on real
 * edges, and the edge band is re-decided at full resolution so the outline is crisp.
 */
class GrabCutLiteSegmenter : SegmentationEngine {
    override val name: String = "Built-in colour-model segmentation"
    override val isReady: Boolean = true

    private class Gmm(val mu: Array<FloatArray>, val invVar: Array<FloatArray>, val konst: FloatArray)

    override suspend fun segment(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val fullW = bitmap.width
        val fullH = bitmap.height
        val scale = min(1f, WORK_DIM / max(fullW, fullH).toFloat())
        val w = max(16, (fullW * scale).roundToInt())
        val h = max(16, (fullH * scale).roundToInt())
        val n = w * h

        val small = ImageUtils.scaleToNewBitmap(bitmap, w, h)
        val px = IntArray(n)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val data = FloatArray(n * 3)
        for (i in 0 until n) {
            data[i * 3] = ((px[i] shr 16) and 0xFF).toFloat()
            data[i * 3 + 1] = ((px[i] shr 8) and 0xFF).toFloat()
            data[i * 3 + 2] = (px[i] and 0xFF).toFloat()
        }

        // Seeds
        val band = max(2, (0.04f * min(w, h)).toInt())
        val border = BooleanArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            if (x < band || y < band || x >= w - band || y >= h - band) border[y * w + x] = true
        }
        val center = BooleanArray(n)
        for (y in (h * 0.25f).toInt() until (h * 0.75f).toInt())
            for (x in (w * 0.25f).toInt() until (w * 0.75f).toInt()) center[y * w + x] = true
        // Background seed = only the border colours that also appear along the TOP edge: the subject almost
        // never touches it, while the bottom / lower sides often hold shoulders, clothes, a table...
        val hardBg = border.copyOf()
        fit(data, border, 4)?.let { gm ->
            val share = IntArray(gm.mu.size)
            var topCount = 0
            for (y in 0 until band) for (x in 0 until w) {
                val i = y * w + x
                share[nearest(gm.mu, data[i * 3], data[i * 3 + 1], data[i * 3 + 2])]++
                topCount++
            }
            val keep = BooleanArray(gm.mu.size) { topCount > 0 && share[it] >= 0.10f * topCount }
            if (keep.none { it }) keep[share.indices.maxByOrNull { share[it] } ?: 0] = true
            for (i in 0 until n) {
                if (border[i]) hardBg[i] = keep[nearest(gm.mu, data[i * 3], data[i * 3 + 1], data[i * 3 + 2])]
            }
        }
        var bgSel = hardBg.copyOf()
        var fgSel = center.copyOf()
        val bgc = BooleanArray(n)
        val queue = IntArray(n)

        // Contrast-sensitive neighbour weights
        val wx = FloatArray(n)
        val wy = FloatArray(n)
        var sum = 0.0
        var cnt = 0
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (x < w - 1) { wx[i] = dist2(data, i, i + 1); sum += wx[i]; cnt++ }
            if (y < h - 1) { wy[i] = dist2(data, i, i + w); sum += wy[i]; cnt++ }
        }
        val beta = 1f / (2f * max(1e-3f, (sum / max(1, cnt)).toFloat()))
        for (i in 0 until n) { wx[i] = exp(-beta * wx[i]); wy[i] = exp(-beta * wy[i]) }

        val q = FloatArray(n)
        val unary = FloatArray(n)
        val msg = FloatArray(n)
        val s = FloatArray(n)
        var gb: Gmm? = null
        var gf: Gmm? = null

        for (round in 0 until ROUNDS) {
            gb = fit(data, bgSel) ?: gb
            gf = fit(data, fgSel) ?: gf
            val b = gb ?: break
            val f = gf ?: break
            for (i in 0 until n) {
                val l = logp(f, data[i * 3], data[i * 3 + 1], data[i * 3 + 2]) -
                    logp(b, data[i * 3], data[i * 3 + 1], data[i * 3 + 2])
                unary[i] = l.coerceIn(-12f, 12f) + FG_BIAS
                q[i] = sigmoid(unary[i])
            }
            for (it in 0 until 14) {
                for (i in 0 until n) { s[i] = 2f * q[i] - 1f; msg[i] = 0f }
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    if (x < w - 1) { val t = wx[i]; msg[i] += t * s[i + 1]; msg[i + 1] += t * s[i] }
                    if (y < h - 1) { val t = wy[i]; msg[i] += t * s[i + w]; msg[i + w] += t * s[i] }
                }
                for (i in 0 until n) q[i] = if (hardBg[i]) 0f else sigmoid(0.6f * unary[i] + 2.2f * msg[i])
            }
            // The background must be connected to the picture border: anything enclosed by the subject
            // (face, eyebrows, collar, tie...) is subject, even if its colour looks like the background.
            java.util.Arrays.fill(bgc, false)
            var qh = 0
            var qt = 0
            for (i in 0 until n) if (border[i] && q[i] < 0.5f) { bgc[i] = true; queue[qt++] = i }
            while (qh < qt) {
                val c = queue[qh++]
                val x = c % w
                val y = c / w
                if (x > 0 && !bgc[c - 1] && q[c - 1] < 0.5f) { bgc[c - 1] = true; queue[qt++] = c - 1 }
                if (x < w - 1 && !bgc[c + 1] && q[c + 1] < 0.5f) { bgc[c + 1] = true; queue[qt++] = c + 1 }
                if (y > 0 && !bgc[c - w] && q[c - w] < 0.5f) { bgc[c - w] = true; queue[qt++] = c - w }
                if (y < h - 1 && !bgc[c + w] && q[c + w] < 0.5f) { bgc[c + w] = true; queue[qt++] = c + w }
            }
            var fgCount = 0
            for (i in 0 until n) {
                if (!bgc[i]) q[i] = 1f
                val fg = !bgc[i] && !border[i]
                fgSel[i] = fg
                bgSel[i] = bgc[i] || hardBg[i]
                if (fg) fgCount++
            }
            if (fgCount < 50) fgSel = center.copyOf()
        }

        // Low-res probability -> full-res alpha
        val lowPx = IntArray(n)
        for (i in 0 until n) lowPx[i] = Color.argb((q[i] * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
        val low = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        low.setPixels(lowPx, 0, w, 0, 0, w, h)
        val full = ImageUtils.scaleToNewBitmap(low, fullW, fullH)
        low.recycle()

        val fn = fullW * fullH
        val alphaPx = IntArray(fn)
        full.getPixels(alphaPx, 0, fullW, 0, 0, fullW, fullH)
        full.recycle()

        // Re-decide the soft edge band using the colour models at full resolution.
        val fb = gb
        val ff = gf
        if (fb != null && ff != null) {
            val orig = IntArray(fn)
            bitmap.getPixels(orig, 0, fullW, 0, 0, fullW, fullH)
            for (i in 0 until fn) {
                val a = alphaPx[i] ushr 24
                if (a in 6..249) {
                    val c = orig[i]
                    val r = ((c shr 16) and 0xFF).toFloat()
                    val g = ((c shr 8) and 0xFF).toFloat()
                    val bl = (c and 0xFF).toFloat()
                    val u = (logp(ff, r, g, bl) - logp(fb, r, g, bl)).coerceIn(-12f, 12f)
                    val p = (a / 255f).coerceIn(0.01f, 0.99f)
                    val prior = ln(p / (1f - p))
                    alphaPx[i] = Color.argb((sigmoid((prior + 0.35f * u) * 1.6f) * 255f + 0.5f).toInt(), 255, 255, 255)
                } else {
                    alphaPx[i] = Color.argb(if (a > 127) 255 else 0, 255, 255, 255)
                }
            }
        }
        val out = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        out.setPixels(alphaPx, 0, fullW, 0, 0, fullW, fullH)
        out
    }

    private fun dist2(d: FloatArray, i: Int, j: Int): Float {
        val a = d[i * 3] - d[j * 3]
        val b = d[i * 3 + 1] - d[j * 3 + 1]
        val c = d[i * 3 + 2] - d[j * 3 + 2]
        return a * a + b * b + c * c
    }

    private fun sigmoid(v: Float): Float = 1f / (1f + exp(-v))

    /** Gaussian mixture (K diagonal components) initialised by farthest-point k-means on a sample. */
    private fun fit(data: FloatArray, sel: BooleanArray, components: Int = COMPONENTS): Gmm? {
        var total = 0
        for (v in sel) if (v) total++
        if (total < 24) return null
        val sampleN = min(total, SAMPLE)
        val step = total.toFloat() / sampleN
        val pts = FloatArray(sampleN * 3)
        var seen = 0
        var next = 0f
        var k = 0
        for (i in sel.indices) {
            if (!sel[i]) continue
            if (seen >= next && k < sampleN) {
                pts[k * 3] = data[i * 3]; pts[k * 3 + 1] = data[i * 3 + 1]; pts[k * 3 + 2] = data[i * 3 + 2]
                k++
                next += step
            }
            seen++
        }
        val m = k
        val kc = min(components, max(1, m / 8))
        val mu = Array(kc) { FloatArray(3) }
        // farthest-point init
        for (c in 0..2) mu[0][c] = pts[(m / 2) * 3 + c]
        val minD = FloatArray(m) { Float.MAX_VALUE }
        for (j in 1 until kc) {
            var best = 0
            var bestD = -1f
            for (i in 0 until m) {
                val dx = pts[i * 3] - mu[j - 1][0]
                val dy = pts[i * 3 + 1] - mu[j - 1][1]
                val dz = pts[i * 3 + 2] - mu[j - 1][2]
                val d = dx * dx + dy * dy + dz * dz
                if (d < minD[i]) minD[i] = d
                if (minD[i] > bestD) { bestD = minD[i]; best = i }
            }
            for (c in 0..2) mu[j][c] = pts[best * 3 + c]
        }
        val assign = IntArray(m)
        repeat(6) {
            for (i in 0 until m) assign[i] = nearest(mu, pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2])
            val sums = Array(kc) { FloatArray(3) }
            val cnts = IntArray(kc)
            for (i in 0 until m) {
                val a = assign[i]
                sums[a][0] += pts[i * 3]; sums[a][1] += pts[i * 3 + 1]; sums[a][2] += pts[i * 3 + 2]
                cnts[a]++
            }
            for (j in 0 until kc) if (cnts[j] > 0) for (c in 0..2) mu[j][c] = sums[j][c] / cnts[j]
        }
        for (i in 0 until m) assign[i] = nearest(mu, pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2])
        val cnts = IntArray(kc)
        val vars = Array(kc) { FloatArray(3) }
        for (i in 0 until m) {
            val a = assign[i]
            cnts[a]++
            for (c in 0..2) { val d = pts[i * 3 + c] - mu[a][c]; vars[a][c] += d * d }
        }
        val inv = Array(kc) { FloatArray(3) }
        val konst = FloatArray(kc)
        for (j in 0 until kc) {
            val cn = cnts[j]
            var logDet = 0f
            for (c in 0..2) {
                val v = if (cn > 1) vars[j][c] / cn + 60f else 400f
                inv[j][c] = 1f / v
                logDet += ln(v)
            }
            val wgt = max(cn, 1).toFloat() / m
            konst[j] = ln(wgt + 1e-9f) - 0.5f * logDet
        }
        return Gmm(mu, inv, konst)
    }

    private fun nearest(mu: Array<FloatArray>, r: Float, g: Float, b: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (j in mu.indices) {
            val dx = r - mu[j][0]; val dy = g - mu[j][1]; val dz = b - mu[j][2]
            val d = dx * dx + dy * dy + dz * dz
            if (d < bestD) { bestD = d; best = j }
        }
        return best
    }

    private fun comp(gm: Gmm, j: Int, r: Float, g: Float, b: Float): Float {
        val dx = r - gm.mu[j][0]; val dy = g - gm.mu[j][1]; val dz = b - gm.mu[j][2]
        return gm.konst[j] - 0.5f * (dx * dx * gm.invVar[j][0] + dy * dy * gm.invVar[j][1] + dz * dz * gm.invVar[j][2])
    }

    private fun logp(gm: Gmm, r: Float, g: Float, b: Float): Float {
        val k = gm.mu.size
        var mx = -Float.MAX_VALUE
        for (j in 0 until k) { val v = comp(gm, j, r, g, b); if (v > mx) mx = v }
        var s = 0f
        for (j in 0 until k) s += exp(comp(gm, j, r, g, b) - mx)
        return mx + ln(s)
    }

    private companion object {
        const val WORK_DIM = 256f
        const val ROUNDS = 5
        const val COMPONENTS = 5
        const val SAMPLE = 6000
        const val FG_BIAS = 0.8f
    }
}
