package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

/** Removes isolated specks and fills tiny holes in an alpha mask (cleans up AI / fallback results). */
object MaskCleanup {

    fun clean(mask: Bitmap): Bitmap {
        val w = mask.width
        val h = mask.height
        val n = w * h
        val px = IntArray(n)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        val fg = BooleanArray(n) { (px[it] ushr 24) >= 128 }

        val label = IntArray(n)
        val stack = IntArray(n)

        // 1) foreground components: drop the small ones
        val sizes = ArrayList<Int>()
        sizes.add(0)
        var next = 0
        for (start in 0 until n) {
            if (!fg[start] || label[start] != 0) continue
            next++
            sizes.add(flood(start, next, w, h, label, stack) { fg[it] })
        }
        if (next == 0) return mask
        var largest = 0
        for (i in 1..next) if (sizes[i] > largest) largest = sizes[i]
        val minKeep = max((n * 0.0015f).toInt(), (largest * 0.03f).toInt())
        for (i in 0 until n) {
            val l = label[i]
            if (l != 0 && sizes[l] < minKeep) px[i] = Color.argb(0, 255, 255, 255)
            else if (l == 0 && (px[i] ushr 24) in 1..127) {
                // soft fringe: keep only when it touches a kept subject pixel (cheap 4-neighbour test)
                val x = i % w; val y = i / w
                val near = (x > 0 && label[i - 1] != 0 && sizes[label[i - 1]] >= minKeep) ||
                    (x < w - 1 && label[i + 1] != 0 && sizes[label[i + 1]] >= minKeep) ||
                    (y > 0 && label[i - w] != 0 && sizes[label[i - w]] >= minKeep) ||
                    (y < h - 1 && label[i + w] != 0 && sizes[label[i + w]] >= minKeep)
                if (!near) px[i] = Color.argb(0, 255, 255, 255)
            }
        }

        // 2) background components that do not touch the border and are tiny = holes -> fill
        java.util.Arrays.fill(label, 0)
        val maxHole = max(16, (n * 0.0008f).toInt())
        var hn = 0
        for (start in 0 until n) {
            if (fg[start] || label[start] != 0) continue
            hn++
            val size = flood(start, hn, w, h, label, stack) { !fg[it] }
            if (size <= maxHole) {
                var touches = false
                for (k in 0 until size) {
                    val p = stack[k]
                    val x = p % w; val y = p / w
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) { touches = true; break }
                }
                if (!touches) for (k in 0 until size) px[stack[k]] = Color.argb(255, 255, 255, 255)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 4-connected flood fill. Visited pixels are listed in stack[0 until returned size]. */
    private inline fun flood(
        start: Int, id: Int, w: Int, h: Int,
        label: IntArray, stack: IntArray, member: (Int) -> Boolean
    ): Int {
        var sp = 0
        var count = 0
        label[start] = id
        stack[sp++] = start
        var head = 0
        // BFS using the same array as queue so the visited list stays in stack[0 until count]
        while (head < sp) {
            val cur = stack[head++]
            count++
            val x = cur % w
            val y = cur / w
            if (x > 0) { val j = cur - 1; if (label[j] == 0 && member(j)) { label[j] = id; stack[sp++] = j } }
            if (x < w - 1) { val j = cur + 1; if (label[j] == 0 && member(j)) { label[j] = id; stack[sp++] = j } }
            if (y > 0) { val j = cur - w; if (label[j] == 0 && member(j)) { label[j] = id; stack[sp++] = j } }
            if (y < h - 1) { val j = cur + w; if (label[j] == 0 && member(j)) { label[j] = id; stack[sp++] = j } }
        }
        return count
    }
}
