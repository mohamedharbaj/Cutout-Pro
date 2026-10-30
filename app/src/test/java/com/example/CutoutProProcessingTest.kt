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
    fun testUndoRedoManager_unlimitedStepsBackToOriginal() {
        val undoManager = UndoRedoManager()
        assertFalse(undoManager.canUndo)
        assertFalse(undoManager.canRedo)

        val mask = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        // 20 separate edits (more than the old 6-step limit): each one erases a different column
        for (i in 0 until 20) {
            val before = undoManager.snapshotAlpha(mask)
            for (y in 0 until 40) mask.setPixel(i, y, Color.TRANSPARENT)
            assertTrue(undoManager.record(before, mask))
        }
        assertEquals(20, undoManager.undoCount)
        assertEquals(0, mask.getPixel(5, 5) ushr 24)

        // one undo restores exactly one edit
        assertTrue(undoManager.undo(mask))
        assertEquals(255, mask.getPixel(19, 5) ushr 24)
        assertEquals(0, mask.getPixel(18, 5) ushr 24)
        assertTrue(undoManager.canRedo)
        assertTrue(undoManager.redo(mask))
        assertEquals(0, mask.getPixel(19, 5) ushr 24)

        // undo everything: back to the untouched original
        assertEquals(20, undoManager.undoAll(mask))
        for (x in 0 until 40) assertEquals(255, mask.getPixel(x, 7) ushr 24)
        assertFalse(undoManager.canUndo)
        assertEquals(20, undoManager.redoCount)
    }

    @Test
    fun testSaliencySegmenter_sameSizeAsAnalysisResolution_doesNotRecycleInputOrResult() = runBlocking {
        // 320x240 == the segmenter's internal analysis size. createScaledBitmap() returns the SAME
        // instance in that case; the old code then recycled the caller's bitmap and the returned mask.
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (y in 80 until 160) for (x in 100 until 220) bitmap.setPixel(x, y, Color.BLUE)

        val mask = SaliencySegmenter().segment(bitmap)

        assertFalse("input bitmap must not be recycled", bitmap.isRecycled)
        assertFalse("returned mask must not be recycled", mask.isRecycled)
        assertEquals(320, mask.width)
        assertEquals(240, mask.height)
        assertTrue("mask must be editable", mask.isMutable)
    }

    @Test
    fun testScaleToNewBitmap_neverReturnsSourceInstance() {
        val src = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val same = ImageUtils.scaleToNewBitmap(src, 64, 64)
        assertTrue(same !== src)
        val bigger = ImageUtils.scaleToNewBitmap(src, 128, 96)
        assertEquals(128, bigger.width)
        assertEquals(96, bigger.height)
        assertTrue(bigger.isMutable)
    }

    @Test
    fun testCompositeImage_backgroundPhotoIsCenterCroppedNotStretched() {
        // Wide 200x100 photo: left half red, right half blue, composited under a 100x100 cutout.
        val bg = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 200) bg.setPixel(x, y, if (x < 100) Color.RED else Color.BLUE)
        val cutout = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) // fully transparent

        val result = ImageUtils.compositeImage(cutout, "IMAGE", Color.WHITE, bg)

        // Center-crop keeps the middle 100x100: red on the left half, blue on the right half.
        assertEquals(Color.RED, result.getPixel(10, 50))
        assertEquals(Color.BLUE, result.getPixel(90, 50))
    }
}
