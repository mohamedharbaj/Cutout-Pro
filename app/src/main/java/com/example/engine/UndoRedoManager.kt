package com.example.engine

import android.graphics.Bitmap
import java.util.LinkedList

class UndoRedoManager(private val maxHistory: Int = 10) {
    private val undoStack = LinkedList<Bitmap>()
    private val redoStack = LinkedList<Bitmap>()

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    fun pushState(currentMask: Bitmap) {
        val copy = MaskEditor.copyBitmap(currentMask)
        undoStack.addLast(copy)
        if (undoStack.size > maxHistory) {
            val discarded = undoStack.removeFirst()
            discarded.recycle()
        }
        // Clear redo stack on new action
        clearRedo()
    }

    fun undo(currentMask: Bitmap): Bitmap? {
        if (undoStack.isEmpty()) return null
        val previousState = undoStack.removeLast()
        redoStack.addLast(MaskEditor.copyBitmap(currentMask))
        return previousState
    }

    fun redo(currentMask: Bitmap): Bitmap? {
        if (redoStack.isEmpty()) return null
        val nextState = redoStack.removeLast()
        undoStack.addLast(MaskEditor.copyBitmap(currentMask))
        return nextState
    }

    fun clear() {
        while (undoStack.isNotEmpty()) {
            undoStack.removeFirst().recycle()
        }
        clearRedo()
    }

    private fun clearRedo() {
        while (redoStack.isNotEmpty()) {
            redoStack.removeFirst().recycle()
        }
    }
}
