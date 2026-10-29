package com.example.engine

import android.graphics.Bitmap
import java.util.LinkedList

class UndoRedoManager(private val maxHistory: Int = 10) {
    private val undoStack = LinkedList<Bitmap>()
    private val redoStack = LinkedList<Bitmap>()

    // Counts how many times undo has been pressed (resets when a new action is pushed)
    private var _undoPressCount: Int = 0
    val undoPressCount: Int get() = _undoPressCount

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    val undoStackSize: Int
        get() = undoStack.size

    fun pushState(currentMask: Bitmap) {
        val copy = MaskEditor.copyBitmap(currentMask)
        undoStack.addLast(copy)
        if (undoStack.size > maxHistory) {
            val discarded = undoStack.removeFirst()
            discarded.recycle()
        }
        // Clear redo stack on new action
        clearRedo()
        // Reset the undo press counter when a new edit is pushed
        _undoPressCount = 0
    }

    fun undo(currentMask: Bitmap): Bitmap? {
        if (undoStack.isEmpty()) return null
        _undoPressCount++
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
        _undoPressCount = 0
    }

    private fun clearRedo() {
        while (redoStack.isNotEmpty()) {
            redoStack.removeFirst().recycle()
        }
    }
}
