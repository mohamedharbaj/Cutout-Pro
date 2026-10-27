package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.engine.MaskEditor
import com.example.engine.SaliencySegmenter
import com.example.engine.UndoRedoManager
import com.example.util.ImageUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CutoutProProcessingTest {

    @Test
    fun testApplyMask_createsAlphaChannelCorrectly() {
        val width = 100
        val height = 100
        val original = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.RED)

        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Set top half white (foreground), bottom half transparent (background)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (y < 50) {
                    mask.setPixel(x, y, Color.WHITE)
                } else {
                    mask.setPixel(x, y, Color.TRANSPARENT)
                }
            }
        }

        val cutout = ImageUtils.applyMask(original, mask)
        assertEquals(width, cutout.width)
        assertEquals(height, cutout.height)

        // Top half should have alpha 255
        val topPixel = cutout.getPixel(50, 25)
        assertEquals(255, Color.alpha(topPixel))
        assertEquals(255, Color.red(topPixel))

        // Bottom half should have alpha 0
        val bottomPixel = cutout.getPixel(50, 75)
        assertEquals(0, Color.alpha(bottomPixel))
    }

    @Test
    fun testSaliencySegmenter_runsSuccessfully() = runBlocking {
        val segmenter = SaliencySegmenter()
        assertTrue(segmenter.isReady)

        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        // Background: White
        bitmap.eraseColor(Color.WHITE)
        // Subject in center: Dark Blue circle
        for (y in 50 until 150) {
            for (x in 50 until 150) {
                bitmap.setPixel(x, y, Color.BLUE)
            }
        }

        val mask = segmenter.segment(bitmap)
        assertNotNull(mask)
        assertEquals(200, mask.width)
        assertEquals(200, mask.height)
    }

    @Test
    fun testMaskEditor_brushEraseAndRestore() {
        val mask = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)

        // Erase circle at center (50, 50) with radius 20
        MaskEditor.applyBrush(mask, 50f, 50f, 20f, isErase = true)
        val erasedCenter = mask.getPixel(50, 50)
        assertEquals(0, Color.alpha(erasedCenter))

        // Corner outside radius should still be opaque
        val corner = mask.getPixel(5, 5)
        assertEquals(255, Color.alpha(corner))

        // Restore center
        MaskEditor.applyBrush(mask, 50f, 50f, 20f, isErase = false)
        val restoredCenter = mask.getPixel(50, 50)
        assertEquals(255, Color.alpha(restoredCenter))
    }

    @Test
    fun testMaskEditor_magicWandFloodFill() = runBlocking {
        val width = 60
        val height = 60
        val original = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.GREEN) // background green
        // center square blue
        for (y in 20 until 40) {
            for (x in 20 until 40) {
                original.setPixel(x, y, Color.BLUE)
            }
        }

        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.WHITE)

        // Tap on green background at (5, 5) with tolerance 20
        val resultMask = MaskEditor.magicWandFloodFill(original, mask, 5, 5, 20f)

        // Background at (5, 5) should be erased (alpha 0)
        val bgPixel = resultMask.getPixel(5, 5)
        assertEquals(0, Color.alpha(bgPixel))

        // Blue center at (30, 30) should remain intact (alpha 255)
        val fgPixel = resultMask.getPixel(30, 30)
        assertEquals(255, Color.alpha(fgPixel))
    }

    @Test
    fun testMaskEditor_cropBitmaps() {
        val original = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val cropRect = Rect(10, 10, 60, 50)

        val (croppedOrig, croppedMask) = MaskEditor.cropBitmaps(original, mask, cropRect)
        assertEquals(50, croppedOrig.width)
        assertEquals(40, croppedOrig.height)
        assertEquals(50, croppedMask.width)
        assertEquals(40, croppedMask.height)
    }

    @Test
    fun testUndoRedoManager() {
        val undoManager = UndoRedoManager(maxHistory = 5)
        assertFalse(undoManager.canUndo)
        assertFalse(undoManager.canRedo)

        val state1 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val state2 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }

        undoManager.pushState(state1)
        assertTrue(undoManager.canUndo)
        assertFalse(undoManager.canRedo)

        val restored = undoManager.undo(state2)
        assertNotNull(restored)
        assertTrue(undoManager.canRedo)

        val redone = undoManager.redo(restored!!)
        assertNotNull(redone)
    }
}
