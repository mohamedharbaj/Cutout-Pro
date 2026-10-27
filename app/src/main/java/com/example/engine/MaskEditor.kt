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
     * SMOOTH EDGE:
     * Softens and feathers the mask alpha boundaries using a separable blur pass.
     */
    suspend fun smoothEdges(mask: Bitmap, radius: Int): Bitmap = withContext(Dispatchers.Default) {
        if (radius <= 0) return@withContext mask

        val width = mask.width
        val height = mask.height
        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)

        val alphas = IntArray(width * height)
        for (i in pixels.indices) {
            alphas[i] = (pixels[i] shr 24) and 0xFF
        }

        val temp = IntArray(width * height)
        val rad = radius.coerceIn(1, 15)

        // Horizontal blur pass on alphas
        for (y in 0 until height) {
            val rowOffset = y * width
            var windowSum = 0
            val windowSize = 2 * rad + 1

            for (i in -rad..rad) {
                val clampedX = i.coerceIn(0, width - 1)
                windowSum += alphas[rowOffset + clampedX]
            }

            for (x in 0 until width) {
                temp[rowOffset + x] = windowSum / windowSize

                val leftX = (x - rad).coerceIn(0, width - 1)
                val rightX = (x + rad + 1).coerceIn(0, width - 1)
                windowSum += alphas[rowOffset + rightX] - alphas[rowOffset + leftX]
            }
        }

        // Vertical blur pass on alphas
        for (x in 0 until width) {
            var windowSum = 0
            val windowSize = 2 * rad + 1

            for (i in -rad..rad) {
                val clampedY = i.coerceIn(0, height - 1)
                windowSum += temp[clampedY * width + x]
            }

            for (y in 0 until height) {
                val smoothedAlpha = (windowSum / windowSize).coerceIn(0, 255)
                pixels[y * width + x] = Color.argb(smoothedAlpha, 255, 255, 255)

                val topY = (y - rad).coerceIn(0, height - 1)
                val botY = (y + rad + 1).coerceIn(0, height - 1)
                windowSum += temp[botY * width + x] - temp[topY * width + x]
            }
        }

        val resultMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultMask.setPixels(pixels, 0, width, 0, 0, width, height)
        resultMask
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
