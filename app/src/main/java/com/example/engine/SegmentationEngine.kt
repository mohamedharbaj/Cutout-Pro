package com.example.engine

import android.graphics.Bitmap

interface SegmentationEngine {
    val name: String
    val isReady: Boolean

    /**
     * Segments the foreground subject from the background.
     * Returns an alpha mask Bitmap where 255 = foreground and 0 = background.
     */
    suspend fun segment(bitmap: Bitmap): Bitmap
}
