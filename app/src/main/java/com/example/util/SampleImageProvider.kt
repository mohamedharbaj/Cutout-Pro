package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SampleImageProvider {

    /**
     * Generates a high-quality studio portrait/product sample photo
     * with high contrast between subject and background for immediate testing.
     */
    suspend fun createSampleBitmap(context: Context): Bitmap = withContext(Dispatchers.Default) {
        val width = 900
        val height = 1200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Studio backdrop: subtle clean gradient
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.rgb(230, 235, 245),
                Color.rgb(205, 215, 230),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Backdrop studio light circle
        val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 248, 255)
            alpha = 180
        }
        canvas.drawCircle(width / 2f, height * 0.42f, width * 0.42f, lightPaint)

        // 2. Foreground Subject (Model with headphone / camera profile)
        val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 35, 51)
        }
        val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(228, 172, 140)
        }
        val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 30, 25)
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(37, 99, 235) // Electric blue headphones
        }

        val cx = width / 2f
        val cy = height * 0.45f

        // Shoulders / Torso jacket
        val torsoPath = Path().apply {
            moveTo(cx - 260f, height.toFloat())
            cubicTo(cx - 240f, cy + 240f, cx - 180f, cy + 180f, cx - 100f, cy + 160f)
            lineTo(cx + 100f, cy + 160f)
            cubicTo(cx + 180f, cy + 180f, cx + 240f, cy + 240f, cx + 260f, height.toFloat())
            close()
        }
        canvas.drawPath(torsoPath, subjectPaint)

        // Jacket zipper & collar detail
        val collarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 26, 38)
        }
        canvas.drawRect(cx - 15f, cy + 160f, cx + 15f, height.toFloat(), collarPaint)

        // Neck
        canvas.drawRect(cx - 55f, cy + 70f, cx + 55f, cy + 170f, skinPaint)

        // Head / Face
        val headRect = RectF(cx - 110f, cy - 130f, cx + 110f, cy + 90f)
        canvas.drawOval(headRect, skinPaint)

        // Hair
        val hairPath = Path().apply {
            moveTo(cx - 120f, cy - 60f)
            cubicTo(cx - 130f, cy - 170f, cx - 60f, cy - 210f, cx, cy - 200f)
            cubicTo(cx + 60f, cy - 210f, cx + 130f, cy - 170f, cx + 120f, cy - 60f)
            cubicTo(cx + 100f, cy - 120f, cx + 40f, cy - 140f, cx, cy - 135f)
            cubicTo(cx - 40f, cy - 140f, cx - 100f, cy - 120f, cx - 120f, cy - 60f)
            close()
        }
        canvas.drawPath(hairPath, hairPaint)

        // Sunglasses / Eyes
        val glassesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42)
        }
        canvas.drawRoundRect(cx - 85f, cy - 35f, cx - 15f, cy + 5f, 12f, 12f, glassesPaint)
        canvas.drawRoundRect(cx + 15f, cy - 35f, cx + 85f, cy + 5f, 12f, 12f, glassesPaint)
        canvas.drawRect(cx - 15f, cy - 20f, cx + 15f, cy - 14f, glassesPaint)

        // Headphones headband & earcups
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(37, 99, 235)
            strokeWidth = 24f
            style = Paint.Style.STROKE
        }
        canvas.drawArc(
            RectF(cx - 140f, cy - 190f, cx + 140f, cy + 30f),
            180f, 180f, false, bandPaint
        )
        // Ear cups
        canvas.drawRoundRect(cx - 150f, cy - 50f, cx - 115f, cy + 40f, 18f, 18f, accentPaint)
        canvas.drawRoundRect(cx + 115f, cy - 50f, cx + 150f, cy + 40f, 18f, 18f, accentPaint)

        bitmap
    }
}
