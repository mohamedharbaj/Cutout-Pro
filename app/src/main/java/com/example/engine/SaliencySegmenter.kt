package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class SaliencySegmenter : SegmentationEngine {
    override val name: String = "Built-in Saliency & Border Engine"
    override val isReady: Boolean = true

    override suspend fun segment(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val origW = bitmap.width
        val origH = bitmap.height

        // Downsample for fast analysis
        val targetDim = 320
        val scale = min(targetDim.toFloat() / origW, targetDim.toFloat() / origH)
        val w = max(32, (origW * scale).toInt())
        val h = max(32, (origH * scale).toInt())

        // scaleToNewBitmap never returns the caller's bitmap, so recycling below is safe.
        val scaled = ImageUtils.scaleToNewBitmap(bitmap, w, h)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        scaled.recycle()

        // 1. Sample border background profile
        // Take pixels along top, bottom, left, and right borders (4% margin)
        val borderColors = mutableListOf<Int>()
        val borderMarginX = max(2, (w * 0.05).toInt())
        val borderMarginY = max(2, (h * 0.05).toInt())

        for (y in 0 until h) {
            for (x in 0 until borderMarginX) {
                borderColors.add(pixels[y * w + x])
                borderColors.add(pixels[y * w + (w - 1 - x)])
            }
        }
        for (x in 0 until w) {
            for (y in 0 until borderMarginY) {
                borderColors.add(pixels[y * w + x])
                borderColors.add(pixels[(h - 1 - y) * w + x])
            }
        }

        // Calculate average background RGB and variance
        var bgRSum = 0L
        var bgGSum = 0L
        var bgBSum = 0L
        for (c in borderColors) {
            bgRSum += (c shr 16) and 0xFF
            bgGSum += (c shr 8) and 0xFF
            bgBSum += c and 0xFF
        }
        val count = borderColors.size.toDouble()
        val bgAvgR = (bgRSum / count).toFloat()
        val bgAvgG = (bgGSum / count).toFloat()
        val bgAvgB = (bgBSum / count).toFloat()

        // 2. Compute background distance and center spatial saliency map
        val centerX = w / 2f
        val centerY = h / 2f
        val maxDistFromCenter = sqrt(centerX * centerX + centerY * centerY)

        val confidence = FloatArray(w * h)
        var minConf = Float.MAX_VALUE
        var maxConf = -Float.MAX_VALUE

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val c = pixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                // Color difference from background border profile
                val dr = r - bgAvgR
                val dg = g - bgAvgG
                val db = b - bgAvgB
                val colorDist = sqrt(dr * dr + dg * dg + db * db) // 0 to 441

                // Spatial prior: subjects are usually closer to the center
                val dx = x - centerX
                val dy = y - centerY
                val distCenter = sqrt(dx * dx + dy * dy)
                val spatialWeight = 1.0f - (distCenter / maxDistFromCenter) * 0.45f

                // Raw score
                val score = colorDist * spatialWeight
                confidence[idx] = score

                if (score < minConf) minConf = score
                if (score > maxConf) maxConf = score
            }
        }

        // 3. Flood-fill background rejection from outer borders
        // Any pixel directly connected to borders with low confidence is definitely background
        val isDefiniteBg = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()

        val threshold = minConf + (maxConf - minConf) * 0.32f

        // Seed border pixels into queue
        for (x in 0 until w) {
            val topIdx = x
            val botIdx = (h - 1) * w + x
            if (confidence[topIdx] < threshold * 1.25f) {
                isDefiniteBg[topIdx] = true
                queue.add(topIdx)
            }
            if (confidence[botIdx] < threshold * 1.25f) {
                isDefiniteBg[botIdx] = true
                queue.add(botIdx)
            }
        }
        for (y in 0 until h) {
            val leftIdx = y * w
            val rightIdx = y * w + (w - 1)
            if (confidence[leftIdx] < threshold * 1.25f) {
                isDefiniteBg[leftIdx] = true
                queue.add(leftIdx)
            }
            if (confidence[rightIdx] < threshold * 1.25f) {
                isDefiniteBg[rightIdx] = true
                queue.add(rightIdx)
            }
        }

        // BFS flood fill background
        while (!queue.isEmpty()) {
            val curr = queue.poll() ?: break
            val cx = curr % w
            val cy = curr / w

            val neighbors = intArrayOf(
                if (cx > 0) curr - 1 else -1,
                if (cx < w - 1) curr + 1 else -1,
                if (cy > 0) curr - w else -1,
                if (cy < h - 1) curr + w else -1
            )

            for (n in neighbors) {
                if (n != -1 && !isDefiniteBg[n]) {
                    if (confidence[n] < threshold) {
                        isDefiniteBg[n] = true
                        queue.add(n)
                    }
                }
            }
        }

        // 4. Generate mask with soft transition
        val maskPixels = IntArray(w * h)
        val rampRange = max(1f, (maxConf - minConf) * 0.22f)

        for (i in 0 until w * h) {
            if (isDefiniteBg[i]) {
                maskPixels[i] = Color.argb(0, 255, 255, 255)
            } else {
                val score = confidence[i]
                val diff = score - threshold
                val alpha = when {
                    diff <= 0f -> 0
                    diff >= rampRange -> 255
                    else -> ((diff / rampRange) * 255).toInt().coerceIn(0, 255)
                }
                maskPixels[i] = Color.argb(alpha, 255, 255, 255)
            }
        }

        // 5. Create low-res mask bitmap and scale back to original dimensions
        val lowResMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        lowResMask.setPixels(maskPixels, 0, w, 0, 0, w, h)

        val fullMask = ImageUtils.scaleToNewBitmap(lowResMask, origW, origH)
        lowResMask.recycle()

        fullMask
    }
}
