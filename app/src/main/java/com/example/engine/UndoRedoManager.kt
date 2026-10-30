package com.example.engine

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Unlimited undo / redo for the erase mask.
 *
 * Instead of storing a full bitmap per step (which forced the old 6-step limit), every edit stores only the
 * rectangle that really changed, before and after, deflate-compressed. A mask is mostly 0 / 255, so a step
 * typically costs a few KB. Every single edit is one step, and the first step always goes back to the
 * untouched original picture (no erased pixels). The history lives in CutoutApp, so it survives moving
 * between Erase -> Smooth -> Saved -> back to Erase.
 */
class UndoRedoManager(
    /** Safety net only: when the compressed history grows past this, the OLDEST steps are dropped. */
    private val maxBytes: Long = 160L * 1024 * 1024
) {
    private class Edit(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val before: ByteArray, val after: ByteArray
    ) {
        val bytes: Long get() = (before.size + after.size).toLong()
    }

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var totalBytes = 0L

    val undoCount: Int get() = synchronized(this) { undoStack.size }
    val redoCount: Int get() = synchronized(this) { redoStack.size }
    val canUndo: Boolean get() = undoCount > 0
    val canRedo: Boolean get() = redoCount > 0

    /** Alpha channel of [mask] as w*h bytes (row-major, no padding). Call BEFORE changing the mask. */
    fun snapshotAlpha(mask: Bitmap): ByteArray {
        val a = mask.extractAlpha()
        try {
            val rowBytes = a.rowBytes
            val w = a.width
            val h = a.height
            val buf = ByteBuffer.allocate(rowBytes * h)
            a.copyPixelsToBuffer(buf)
            val raw = buf.array()
            if (rowBytes == w) return raw
            val out = ByteArray(w * h)
            for (y in 0 until h) System.arraycopy(raw, y * rowBytes, out, y * w, w)
            return out
        } finally {
            a.recycle()
        }
    }

    /**
     * Records the edit that turned [beforeAlpha] (from [snapshotAlpha]) into the current content of [mask].
     * Returns false (and records nothing) when nothing actually changed.
     */
    @Synchronized
    fun record(beforeAlpha: ByteArray, mask: Bitmap): Boolean {
        val w = mask.width
        val h = mask.height
        val afterAlpha = snapshotAlpha(mask)
        if (afterAlpha.size != beforeAlpha.size) return false

        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            var firstX = -1
            var lastX = -1
            for (x in 0 until w) {
                if (beforeAlpha[row + x] != afterAlpha[row + x]) {
                    if (firstX < 0) firstX = x
                    lastX = x
                }
            }
            if (firstX >= 0) {
                if (firstX < minX) minX = firstX
                if (lastX > maxX) maxX = lastX
                if (y < minY) minY = y
                maxY = y
            }
        }
        if (maxX < 0) return false

        val rw = maxX - minX + 1
        val rh = maxY - minY + 1
        val edit = Edit(
            minX, minY, rw, rh,
            deflate(cut(beforeAlpha, w, minX, minY, rw, rh)),
            deflate(cut(afterAlpha, w, minX, minY, rw, rh))
        )
        undoStack.addLast(edit)
        totalBytes += edit.bytes
        dropAll(redoStack)
        while (totalBytes > maxBytes && undoStack.size > 1) {
            totalBytes -= undoStack.removeFirst().bytes
        }
        return true
    }

    /** Reverts the last edit directly inside [mask]. Returns false if there is nothing to undo. */
    @Synchronized
    fun undo(mask: Bitmap): Boolean {
        val e = undoStack.pollLast() ?: return false
        apply(mask, e, e.before)
        redoStack.addLast(e)
        return true
    }

    /** Reverts the last edit AND forgets it (used when a stroke turns out to be a pinch gesture). */
    @Synchronized
    fun cancelLast(mask: Bitmap): Boolean {
        val e = undoStack.pollLast() ?: return false
        apply(mask, e, e.before)
        totalBytes -= e.bytes
        return true
    }

    /** Re-applies the last undone edit directly inside [mask]. */
    @Synchronized
    fun redo(mask: Bitmap): Boolean {
        val e = redoStack.pollLast() ?: return false
        apply(mask, e, e.after)
        undoStack.addLast(e)
        return true
    }

    /** Undoes every step: the picture goes back to the original, before any background removal. */
    @Synchronized
    fun undoAll(mask: Bitmap): Int {
        var n = 0
        while (undo(mask)) n++
        return n
    }

    @Synchronized
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        totalBytes = 0L
    }

    private fun dropAll(stack: ArrayDeque<Edit>) {
        while (true) {
            val e = stack.pollFirst() ?: break
            totalBytes -= e.bytes
        }
    }

    private fun apply(mask: Bitmap, e: Edit, packed: ByteArray) {
        val alpha = inflate(packed, e.w * e.h)
        val px = IntArray(e.w * e.h)
        for (i in px.indices) px[i] = ((alpha[i].toInt() and 0xFF) shl 24) or 0x00FFFFFF
        mask.setPixels(px, 0, e.w, e.x, e.y, e.w, e.h)
    }

    private fun cut(src: ByteArray, stride: Int, x: Int, y: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (row in 0 until h) System.arraycopy(src, (y + row) * stride + x, out, row * w, w)
        return out
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_SPEED)
        d.setInput(data)
        d.finish()
        val out = java.io.ByteArrayOutputStream(maxOf(64, data.size / 8))
        val buf = ByteArray(16 * 1024)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray, rawSize: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArray(rawSize)
        var off = 0
        while (off < rawSize && !inf.finished()) {
            val n = inf.inflate(out, off, rawSize - off)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            off += n
        }
        inf.end()
        return out
    }
}
