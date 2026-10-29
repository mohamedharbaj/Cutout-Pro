package com.example.ui

import android.graphics.RectF
import android.widget.Toast
import android.content.Context
import androidx.compose.ui.geometry.Offset
import kotlin.math.min

/** Maps between screen pixels of a canvas and pixels of a bitmap shown "fit" inside it (+ zoom/pan). */
class ViewXform(
    canvasW: Float,
    canvasH: Float,
    val bmpW: Int,
    val bmpH: Int,
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero
) {
    private val fit: Float =
        if (canvasW > 0f && canvasH > 0f && bmpW > 0 && bmpH > 0) min(canvasW / bmpW, canvasH / bmpH) else 1f
    /** False until the canvas has been measured (avoids drawing one wrong frame at the wrong size). */
    val valid: Boolean = canvasW > 0f && canvasH > 0f
    val scale: Float = fit * zoom
    val left: Float = (canvasW - bmpW * scale) / 2f + pan.x
    val top: Float = (canvasH - bmpH * scale) / 2f + pan.y

    fun toBmpX(sx: Float): Float = (sx - left) / scale
    fun toBmpY(sy: Float): Float = (sy - top) / scale
    fun toScreenX(bx: Float): Float = left + bx * scale
    fun toScreenY(by: Float): Float = top + by * scale
    fun dst(): RectF = RectF(left, top, left + bmpW * scale, top + bmpH * scale)
}

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
