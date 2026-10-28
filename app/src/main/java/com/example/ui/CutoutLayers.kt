package com.example.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.example.ui.components.CheckerboardBackground

/**
 * Draws [original] masked by the alpha of [mask] over a checkerboard / white / black background.
 * The mask is applied on the GPU canvas (DST_IN), so editing the mask in place is cheap.
 * [version] only exists to force a redraw after the mask bitmap is modified in place.
 */
@Composable
fun CutoutLayers(
    original: Bitmap,
    mask: Bitmap,
    version: Int,
    bgMode: Int,
    xf: ViewXform,
    modifier: Modifier = Modifier
) {
    val imgPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val maskPaint = remember {
        Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    }
    Box(modifier) {
        when (bgMode) {
            0 -> CheckerboardBackground()
            1 -> Box(Modifier.fillMaxSize().background(Color.White))
            else -> Box(Modifier.fillMaxSize().background(Color.Black))
        }
        Canvas(Modifier.fillMaxSize()) {
            if (version < 0) return@Canvas
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                val d = xf.dst()
                val layer = nc.saveLayer(d, null)
                nc.drawBitmap(original, null, d, imgPaint)
                nc.drawBitmap(mask, null, d, maskPaint)
                nc.restoreToCount(layer)
            }
        }
    }
}
