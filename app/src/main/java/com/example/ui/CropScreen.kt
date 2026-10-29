package com.example.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.CheckerboardBackground
import com.example.ui.components.EditorTopBar
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CropScreen(bitmap: Bitmap, onBack: () -> Unit, onDone: (Bitmap) -> Unit) {
    val bw = bitmap.width
    val bh = bitmap.height
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Crop rectangle in bitmap pixels.
    var l by remember { mutableFloatStateOf(0f) }
    var t by remember { mutableFloatStateOf(0f) }
    var r by remember { mutableFloatStateOf(bw.toFloat()) }
    var b by remember { mutableFloatStateOf(bh.toFloat()) }

    val xf = ViewXform(canvasSize.width.toFloat(), canvasSize.height.toFloat(), bw, bh)
    val xfNow by rememberUpdatedState(xf)
    val imgPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val minSize = 32f
    val accent = Color(0xFFFF00FF)

    fun finish() {
        val left = l.roundToInt().coerceIn(0, bw - 1)
        val top = t.roundToInt().coerceIn(0, bh - 1)
        val right = r.roundToInt().coerceIn(left + 1, bw)
        val bottom = b.roundToInt().coerceIn(top + 1, bh)
        if (left == 0 && top == 0 && right == bw && bottom == bh) {
            onDone(bitmap)
        } else {
            onDone(Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        EditorTopBar(
            onBack = onBack,
            title = stringResource(R.string.crop_title),
            actionLabel = stringResource(R.string.done),
            onAction = { finish() }
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val x = xfNow
                        val slop = 36.dp.toPx()
                        val sl = x.toScreenX(l)
                        val sr = x.toScreenX(r)
                        val st = x.toScreenY(t)
                        val sb = x.toScreenY(b)
                        val p = down.position
                        val inY = p.y in (st - slop)..(sb + slop)
                        val inX = p.x in (sl - slop)..(sr + slop)
                        val nearL = inY && abs(p.x - sl) <= slop
                        val nearR = inY && abs(p.x - sr) <= slop
                        val nearT = inX && abs(p.y - st) <= slop
                        val nearB = inX && abs(p.y - sb) <= slop
                        val edge = nearL || nearR || nearT || nearB
                        val move = !edge && p.x in sl..sr && p.y in st..sb
                        if (!edge && !move) return@awaitEachGesture
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val d = change.positionChange()
                            val dx = d.x / x.scale
                            val dy = d.y / x.scale
                            if (move) {
                                val w = r - l
                                val h = b - t
                                l = (l + dx).coerceIn(0f, bw - w)
                                t = (t + dy).coerceIn(0f, bh - h)
                                r = l + w
                                b = t + h
                            } else {
                                if (nearL) l = (l + dx).coerceIn(0f, r - minSize)
                                if (nearR) r = (r + dx).coerceIn(l + minSize, bw.toFloat())
                                if (nearT) t = (t + dy).coerceIn(0f, b - minSize)
                                if (nearB) b = (b + dy).coerceIn(t + minSize, bh.toFloat())
                            }
                            change.consume()
                        }
                    }
                }
        ) {
            CheckerboardBackground()
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c -> c.nativeCanvas.drawBitmap(bitmap, null, xf.dst(), imgPaint) }
                val sl = xf.toScreenX(l)
                val sr = xf.toScreenX(r)
                val st = xf.toScreenY(t)
                val sb = xf.toScreenY(b)
                val dim = Color(0x99000000)
                drawRect(dim, Offset(0f, 0f), Size(size.width, st))
                drawRect(dim, Offset(0f, sb), Size(size.width, size.height - sb))
                drawRect(dim, Offset(0f, st), Size(sl, sb - st))
                drawRect(dim, Offset(sr, st), Size(size.width - sr, sb - st))
                drawRect(accent, Offset(sl, st), Size(sr - sl, sb - st), style = Stroke(width = 2.dp.toPx()))
                val mx = (sl + sr) / 2f
                val my = (st + sb) / 2f
                val handles = listOf(
                    Offset(sl, st), Offset(mx, st), Offset(sr, st),
                    Offset(sl, my), Offset(sr, my),
                    Offset(sl, sb), Offset(mx, sb), Offset(sr, sb)
                )
                for (h in handles) drawCircle(accent, radius = 7.dp.toPx(), center = h)
            }
        }

        Text(
            stringResource(R.string.crop_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 12.dp)
        )
    }
}
