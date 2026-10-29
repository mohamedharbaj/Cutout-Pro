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
import java.util.ArrayDeque
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

    /**
     * AUTO-COLOR:
     * Removes all pixels across the image matching targetColor within tolerance (0..100).
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

        val targetR = (targetColor shr 16) and 0xFF
        val targetG = (targetColor shr 8) and 0xFF
        val targetB = targetColor and 0xFF

        // Max distance is ~441. Tolerance 0..100 maps to 0..255 distance threshold
        val maxDist = (tolerance * 3.5f)

        for (i in origPixels.indices) {
            val c = origPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            val dr = r - targetR
            val dg = g - targetG
            val db = b - targetB
            val dist = sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()

            if (dist <= maxDist) {
                maskPixels[i] = Color.argb(0, 0, 0, 0)
            }
        }

        val resultMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultMask.setPixels(maskPixels, 0, width, 0, 0, width, height)
        resultMask
    }

    /**
     * MAGIC WAND:
     * Connected-region flood fill starting at touch (startX, startY).
     * Clears mask alpha to 0 for connected pixels matching seed color within tolerance.
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

        if (startX !in 0 until width || startY !in 0 until height) {
            return@withContext mask
        }

        val origPixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        original.getPixels(origPixels, 0, width, 0, 0, width, height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        val seedColor = origPixels[startY * width + startX]
        val seedR = (seedColor shr 16) and 0xFF
        val seedG = (seedColor shr 8) and 0xFF
        val seedB = seedColor and 0xFF

        val maxDist = (tolerance * 3.2f)

        val visited = BooleanArray(width * height)
        val queue = ArrayDeque<Int>()

        val startIdx = startY * width + startX
        queue.add(startIdx)
        visited[startIdx] = true

        while (!queue.isEmpty()) {
            val curr = queue.poll() ?: break
            val cx = curr % width
            val cy = curr / width

            // Clear this pixel in the mask
            maskPixels[curr] = Color.argb(0, 0, 0, 0)

            val neighbors = intArrayOf(
                if (cx > 0) curr - 1 else -1,
                if (cx < width - 1) curr + 1 else -1,
                if (cy > 0) curr - width else -1,
                if (cy < height - 1) curr + width else -1
            )

            for (n in neighbors) {
                if (n != -1 && !visited[n]) {
                    val c = origPixels[n]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    val dr = r - seedR
                    val dg = g - seedG
                    val db = b - seedB
                    val dist = sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()

                    if (dist <= maxDist) {
                        visited[n] = true
                        queue.add(n)
                    }
                }
            }
        }

        val resultMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultMask.setPixels(maskPixels, 0, width, 0, 0, width, height)
        resultMask
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
