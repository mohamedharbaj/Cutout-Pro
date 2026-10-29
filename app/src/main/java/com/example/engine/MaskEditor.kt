package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object MaskEditor {

    /**
     * Erases or restores pixels in the mask within a circular brush radius.
     * if isErase is true -> clears mask to alpha 0.
     * if isErase is false -> restores mask to alpha 255.
     */
    fun applyBrush(
        mask: Bitmap,
        centerX: Float,
        centerY: Float,
        radius: Float,
        isErase: Boolean
    ): Bitmap {
        val r = radius.toInt()
        val cx = centerX.toInt()
        val cy = centerY.toInt()
        val w = mask.width
        val h = mask.height
        val rSq = radius * radius

        val minX = max(0, cx - r)
        val maxX = min(w - 1, cx + r)
        val minY = max(0, cy - r)
        val maxY = min(h - 1, cy + r)

        val targetColor = if (isErase) Color.TRANSPARENT else Color.WHITE

        for (y in minY..maxY) {
            val dy = y - centerY
            for (x in minX..maxX) {
                val dx = x - centerX
                if (dx * dx + dy * dy <= rSq) {
                    mask.setPixel(x, y, targetColor)
                }
            }
        }
        return mask
    }

    /** Perceptual-ish ("redmean") colour distance, roughly on a 0..~440 scale like plain RGB distance. */
    private fun colorDist(c1: Int, r2: Int, g2: Int, b2: Int): Float {
        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF
        val rm = (r1 + r2) / 2
        val dr = (r1 - r2).toFloat()
        val dg = (g1 - g2).toFloat()
        val db = (b1 - b2).toFloat()
        val d = (2f + rm / 256f) * dr * dr + 4f * dg * dg + (2f + (255 - rm) / 256f) * db * db
        return sqrt(d) / 1.8f
    }

    /** Average colour of the 3x3 neighbourhood: a stable sample even on noisy / JPEG images. */
    private fun sampleColor(pixels: IntArray, w: Int, h: Int, x: Int, y: Int): IntArray {
        var r = 0; var g = 0; var b = 0; var n = 0
        for (yy in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(h - 1)) {
            for (xx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(w - 1)) {
                val c = pixels[yy * w + xx]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
        }
        return intArrayOf(r / n, g / n, b / n)
    }

    /** Smooth 0..1 ramp used to feather the very edge of a removed colour. */
    private fun feather(d: Float, tol: Float, soft: Float): Float {
        val t = ((d - tol) / soft).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * AUTO-COLOR:
     * Removes (alpha = 0, 100%) every pixel of the picture whose colour is close to the tapped colour.
     * Colours that are clearly different are NOT touched; only a very thin band right next to the
     * removed colour is feathered to avoid jagged edges.
     */
    suspend fun removeByColor(
        original: Bitmap,
        mask: Bitmap,
        targetColor: Int,
        tolerance: Float
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = original.width
        val height = original.height
        val origPixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        original.getPixels(origPixels, 0, width, 0, 0, width, height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        val tr = (targetColor shr 16) and 0xFF
        val tg = (targetColor shr 8) and 0xFF
        val tb = targetColor and 0xFF
        val tol = tolerance * 1.5f
        val soft = max(3f, tol * 0.3f)

        for (i in origPixels.indices) {
            val d = colorDist(origPixels[i], tr, tg, tb)
            if (d <= tol) {
                maskPixels[i] = 0
            } else if (d < tol + soft) {
                val a = (feather(d, tol, soft) * 255f).toInt()
                val old = maskPixels[i] ushr 24
                if (a < old) maskPixels[i] = Color.argb(a, 255, 255, 255)
            }
        }

        val resultMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultMask.setPixels(maskPixels, 0, width, 0, 0, width, height)
        resultMask
    }

    /** Colour at (x, y) of [original], sampled over 3x3 pixels, as packed RGB. */
    fun pickColor(original: Bitmap, x: Int, y: Int): Int {
        val w = original.width
        val h = original.height
        val x0 = (x - 1).coerceAtLeast(0)
        val y0 = (y - 1).coerceAtLeast(0)
        val x1 = (x + 1).coerceAtMost(w - 1)
        val y1 = (y + 1).coerceAtMost(h - 1)
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1
        val px = IntArray(cw * ch)
        original.getPixels(px, 0, cw, x0, y0, cw, ch)
        val c = sampleColor(px, cw, ch, x - x0, y - y0)
        return Color.rgb(c[0], c[1], c[2])
    }

    /**
     * MAGIC WAND (edge aware):
     * Connected-region fill from the touched point. It stops at colour edges: a neighbour is only
     * taken when it is close to the seed colour AND does not jump strongly from the pixel before it.
     */
    suspend fun magicWandFloodFill(
        original: Bitmap,
        mask: Bitmap,
        startX: Int,
        startY: Int,
        tolerance: Float
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = original.width
        val height = original.height
        if (startX !in 0 until width || startY !in 0 until height) return@withContext mask

        val n = width * height
        val origPixels = IntArray(n)
        val maskPixels = IntArray(n)
        original.getPixels(origPixels, 0, width, 0, 0, width, height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        val seed = sampleColor(origPixels, width, height, startX, startY)
        val sr = seed[0]; val sg = seed[1]; val sb = seed[2]
        val tol = tolerance * 1.5f
        val soft = max(3f, tol * 0.3f)
        val stepLimit = max(5f, tol * 0.55f)

        val visited = BooleanArray(n)
        val stack = IntArray(n)
        var sp = 0
        val startIdx = startY * width + startX
        stack[sp++] = startIdx
        visited[startIdx] = true

        while (sp > 0) {
            val cur = stack[--sp]
            maskPixels[cur] = 0
            val cx = cur % width
            val cy = cur / width
            val cc = origPixels[cur]
            val cr = (cc shr 16) and 0xFF
            val cg = (cc shr 8) and 0xFF
            val cb = cc and 0xFF

            for (k in 0 until 4) {
                val nx = when (k) { 0 -> cx - 1; 1 -> cx + 1; else -> cx }
                val ny = when (k) { 2 -> cy - 1; 3 -> cy + 1; else -> cy }
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                val ni = ny * width + nx
                if (visited[ni]) continue
                val c = origPixels[ni]
                val dSeed = colorDist(c, sr, sg, sb)
                if (dSeed <= tol) {
                    if (colorDist(c, cr, cg, cb) <= stepLimit) {
                        visited[ni] = true
                        stack[sp++] = ni
                    }
                } else if (dSeed < tol + soft) {
                    val a = (feather(dSeed, tol, soft) * 255f).toInt()
                    val old = maskPixels[ni] ushr 24
                    if (a < old) maskPixels[ni] = Color.argb(a, 255, 255, 255)
                }
            }
        }

        val resultMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultMask.setPixels(maskPixels, 0, width, 0, 0, width, height)
        resultMask
    }

    /** Alpha of [a] multiplied by alpha of [b] (used to combine the AI mask with manual edits). */
    fun intersect(a: Bitmap, b: Bitmap): Bitmap {
        val out = copyBitmap(a)
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        Canvas(out).drawBitmap(b, 0f, 0f, paint)
        return out
    }

    /**
     * SMOOTH EDGE (level 1..5):
     * 1) blurs the mask alpha (two box passes ~ gaussian) to round off jagged / staircase edges,
     * 2) re-sharpens with a steep curve around a threshold, so the edge stays where it was and only
     *    gets a ~2px anti-aliased transition. There is no wide glow/halo (the old version just
     *    blurred the alpha, which made background-coloured pixels show up as a white halo).
     * Higher levels smooth more and also trim a tiny fringe from the border.
     */
    suspend fun smoothEdges(mask: Bitmap, level: Int): Bitmap = withContext(Dispatchers.Default) {
        if (level <= 0) return@withContext mask

        val width = mask.width
        val height = mask.height
        val n = width * height
        val pixels = IntArray(n)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)

        var cur = IntArray(n) { (pixels[it] shr 24) and 0xFF }
        var tmp = IntArray(n)

        // Radius grows with the level and a little with the image size, but stays small.
        val base = max(1f, max(width, height) / 1200f)
        val rad = max(1, (level * 0.9f * base).toInt()).coerceAtMost(12)

        repeat(2) {
            boxBlurH(cur, tmp, width, height, rad)
            boxBlurV(tmp, cur, width, height, rad)
        }

        val threshold = 0.5f + 0.02f * level
        val gain = max(2.5f, rad * 1.6f)
        for (i in 0 until n) {
            val v = ((cur[i] / 255f - threshold) * gain + 0.5f).coerceIn(0f, 1f)
            pixels[i] = Color.argb((v * 255f + 0.5f).toInt(), 255, 255, 255)
        }

        val resultMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultMask.setPixels(pixels, 0, width, 0, 0, width, height)
        resultMask
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val size = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = sum / size
                sum += src[row + (x + r + 1).coerceIn(0, w - 1)] - src[row + (x - r).coerceIn(0, w - 1)]
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val size = 2 * r + 1
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += src[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / size
                sum += src[(y + r + 1).coerceIn(0, h - 1) * w + x] - src[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
    }

    /**
     * Fast, anti-aliased brush stroke drawn straight into the mask with a Canvas.
     * Erase clears the mask (alpha 0); restore paints it opaque white (alpha 255).
     * [first] draws a single dot, otherwise a round-capped line from the previous point.
     */
    fun brushStroke(
        mask: Bitmap,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        radius: Float,
        isErase: Boolean,
        first: Boolean
    ) {
        val canvas = Canvas(mask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (isErase) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else {
            paint.color = Color.WHITE
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        if (first) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(toX, toY, radius, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 2f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(fromX, fromY, toX, toY, paint)
        }
    }

    /**
     * Clones a bitmap deeply for undo/redo snapshots.
     */
    fun copyBitmap(bitmap: Bitmap): Bitmap {
        return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
    }

    /**
     * Crops both bitmap and mask to a sub-rectangle.
     */
    fun cropBitmaps(original: Bitmap, mask: Bitmap, cropRect: Rect): Pair<Bitmap, Bitmap> {
        val clampedRect = Rect(
            cropRect.left.coerceIn(0, original.width - 1),
            cropRect.top.coerceIn(0, original.height - 1),
            cropRect.right.coerceIn(1, original.width),
            cropRect.bottom.coerceIn(1, original.height)
        )
        val w = max(1, clampedRect.width())
        val h = max(1, clampedRect.height())

        val croppedOriginal = Bitmap.createBitmap(original, clampedRect.left, clampedRect.top, w, h)
        val croppedMask = Bitmap.createBitmap(mask, clampedRect.left, clampedRect.top, w, h)
        return Pair(croppedOriginal, croppedMask)
    }
}
