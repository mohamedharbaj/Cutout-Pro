package com.example.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.AutoCutout
import com.example.engine.MaskEditor
import com.example.engine.UndoRedoManager
import com.example.ui.components.BgSwatch
import com.example.ui.components.BrushIcon
import com.example.ui.components.EditorTopBar
import com.example.ui.components.EraserIcon
import com.example.ui.components.LabeledSlider
import com.example.ui.components.MagicIcon
import com.example.ui.components.PersonIcon
import com.example.ui.components.rememberDelayedFlag
import com.example.ui.components.StarIcon
import com.example.ui.components.UndoRedoButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EraseTool { AUTO, COLOR, MAGIC, MANUAL, REPAIR, ZOOM }

/** Last Magic / Auto-Color tap. The Tolerance slider re-applies it live: 0 = untouched picture, 100 = full effect. */
private class PendingOp(val isColor: Boolean, val bx: Int, val by: Int, val color: Int, val ai: Bitmap? = null)

private class StrokeState {
    var lastX = 0f
    var lastY = 0f
    var before: ByteArray? = null
}

@StringRes
private fun toolLabel(tool: EraseTool): Int = when (tool) {
    EraseTool.AUTO -> R.string.tool_ai
    EraseTool.COLOR -> R.string.tool_color
    EraseTool.MAGIC -> R.string.tool_magic
    EraseTool.MANUAL -> R.string.tool_manual
    EraseTool.REPAIR -> R.string.tool_repair
    EraseTool.ZOOM -> R.string.tool_zoom
}

@StringRes
private fun toolHint(tool: EraseTool): Int = when (tool) {
    EraseTool.AUTO -> R.string.tool_ai
    EraseTool.COLOR -> R.string.hint_color
    EraseTool.MAGIC -> R.string.hint_magic
    EraseTool.MANUAL -> R.string.hint_manual
    EraseTool.REPAIR -> R.string.hint_repair
    EraseTool.ZOOM -> R.string.hint_zoom
}

private fun newOpaqueMask(original: Bitmap): Bitmap =
    Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888).also {
        it.eraseColor(AColor.WHITE)
    }

@Composable
fun EraseScreen(
    original: Bitmap,
    initialMask: Bitmap?,
    history: UndoRedoManager,
    onBack: () -> Unit,
    onDone: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    // The history is owned by CutoutApp so it survives Erase -> Smooth -> Saved -> Erase.
    val undo = history
    val stroke = remember { StrokeState() }

    var mask by remember { mutableStateOf(initialMask ?: newOpaqueMask(original)) }
    var version by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(EraseTool.MANUAL) }
    var brushDp by remember { mutableFloatStateOf(28f) }
    var offsetDp by remember { mutableFloatStateOf(48f) }
    var tolerance by remember { mutableFloatStateOf(30f) }
    var bgMode by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var busy by remember { mutableStateOf(false) }
    var undoCount by remember { mutableIntStateOf(undo.undoCount) }
    var redoCount by remember { mutableIntStateOf(undo.redoCount) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingOp?>(null) }
    // live (not yet recorded) result of the last Magic / Auto-Color tap, shown instead of the mask
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var aiStrength by remember { mutableFloatStateOf(100f) }
    val workerHolder = remember { arrayOfNulls<Job>(1) }
    val wanted = remember { floatArrayOf(30f) }

    val xf = ViewXform(
        canvasSize.width.toFloat(), canvasSize.height.toFloat(),
        original.width, original.height, zoom, pan
    )
    // Gesture lambdas outlive a single composition, so they must read the latest transform.
    val xfNow by rememberUpdatedState(xf)

    /** True while a tool operation, a live slider preview or a settle step is running. */
    fun blocked(): Boolean = busy || workerHolder[0]?.isActive == true

    fun refreshUndo() {
        undoCount = undo.undoCount + if (preview != null) 1 else 0
        redoCount = if (preview != null) 0 else undo.redoCount
    }

    /** Replaces the mask with [newMask] and records the change as ONE undo step (done off the UI thread). */
    suspend fun commit(newMask: Bitmap) {
        val old = mask
        withContext(Dispatchers.Default) {
            val before = undo.snapshotAlpha(old)
            undo.record(before, newMask)
        }
        mask = newMask
        version++
        refreshUndo()
    }

    /** Turns the live preview of the last Magic / Auto-Color tap into one real undo step (no visual change). */
    suspend fun settlePending() {
        val pv = preview
        pending = null
        if (pv != null) {
            commit(pv)
            preview = null
        }
        refreshUndo()
    }

    /** Settles the live preview (if any) and then runs [then]; always goes through the worker so nothing overlaps. */
    fun settleThen(then: (() -> Unit)? = null) {
        val prev = workerHolder[0]
        workerHolder[0] = scope.launch {
            prev?.join()
            try {
                settlePending()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                context.toast(context.getString(R.string.error_process))
            }
            then?.invoke()
        }
    }

    fun hasLivePreview(): Boolean = pending != null || preview != null

    fun performUndo() {
        if (undo.undo(mask)) {
            version++
            refreshUndo()
        }
    }

    fun performRedo() {
        if (undo.redo(mask)) {
            version++
            refreshUndo()
        }
    }

    fun performUndoAll() {
        if (undo.undoAll(mask) > 0) {
            version++
            refreshUndo()
        }
    }

    fun doUndo() {
        if (blocked()) return
        if (hasLivePreview()) settleThen { performUndo() } else performUndo()
    }

    fun doRedo() {
        if (blocked()) return
        if (hasLivePreview()) settleThen { performRedo() } else performRedo()
    }

    /** Long-press on Undo: back to the original picture, before any background removal. */
    fun doUndoAll() {
        if (blocked()) return
        if (hasLivePreview()) settleThen { performUndoAll() } else performUndoAll()
    }

    /**
     * AI-Auto: the cut is computed once, then shown as a live preview whose strength is the "Strength" slider
     * (0 = picture as before, 100 = full cut). It becomes ONE undo step when settled.
     */
    fun runAutoNow() {
        workerHolder[0] = scope.launch {
            busy = true
            busyMessage = null
            try {
                val outcome = AutoCutout.run(original) {
                    busyMessage = context.getString(R.string.ai_downloading)
                }
                busyMessage = null
                busy = false
                if (outcome.usedFallback) {
                    context.toast(context.getString(R.string.ai_fallback) + (outcome.reason?.let { "\n($it)" } ?: ""))
                }
                settlePending()
                aiStrength = 100f
                pending = PendingOp(false, 0, 0, 0, outcome.mask)
                wanted[0] = 100f
                pendingLoop()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                context.toast(context.getString(R.string.error_process))
            } finally {
                busyMessage = null
                busy = false
            }
        }
    }

    fun runAuto() {
        if (blocked()) return
        if (hasLivePreview()) settleThen { runAutoNow() } else runAutoNow()
    }

    /**
     * Live strength of the last Magic / Auto-Color tap: every slider value recomputes the result from the
     * untouched mask and swaps the preview in one go (no revert in between, so no flicker).
     * Tolerance 0 = picture as before the tap, 100 = full effect. It becomes ONE undo step when settled.
     */
    suspend fun pendingLoop() {
        var done = -1f
        while (wanted[0] != done) {
            val t = wanted[0]
            done = t
            val op = pending ?: break
            val base = mask
            val result = if (t >= 0.5f) {
                val ai = op.ai
                if (ai != null) {
                    withContext(Dispatchers.Default) { AutoCutout.blend(base, ai, t / 100f) }
                } else if (op.isColor) {
                    MaskEditor.removeByColor(original, base, op.color, t)
                } else {
                    MaskEditor.magicWandFloodFill(original, base, op.bx, op.by, t)
                }
            } else null
            preview = result
            version++
            refreshUndo()
        }
    }

    fun requestPending(tol: Float) {
        wanted[0] = tol
        if (workerHolder[0]?.isActive == true) return
        workerHolder[0] = scope.launch {
            try {
                pendingLoop()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                context.toast(context.getString(R.string.error_process))
            }
        }
    }

    // switching tool settles the live preview of the last Magic / Auto-Color tap
    LaunchedEffect(tool) {
        if (hasLivePreview()) settleThen()
    }

    // Colour / Magic: the crosshair sits above the finger (Cursor Offset), so the finger never hides
    // the exact pixel. The colour is picked and removed where the crosshair is when the finger lifts.
    fun applyAtCursor() {
        if (blocked()) return
        val p = cursor ?: return
        val x = xfNow
        val bx = x.toBmpX(p.x).toInt()
        val by = x.toBmpY(p.y).toInt()
        if (bx !in 0 until original.width || by !in 0 until original.height) return
        val op = PendingOp(tool == EraseTool.COLOR, bx, by, MaskEditor.pickColor(original, bx, by))
        workerHolder[0] = scope.launch {
            try {
                settlePending()          // the previous tap becomes its own undo step
                pending = op
                wanted[0] = tolerance
                pendingLoop()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                context.toast(context.getString(R.string.error_process))
            }
        }
    }

    /** Cursor Offset slider: the crosshair follows it live, even before the screen is touched. */
    fun setOffset(v: Float) {
        val old = offsetDp
        offsetDp = v
        val c = cursor
        cursor = if (c != null) {
            Offset(c.x, c.y - (v - old) * density)
        } else {
            Offset(canvasSize.width / 2f, canvasSize.height / 2f)
        }
    }

    fun beginStroke() {
        stroke.before = undo.snapshotAlpha(mask)
    }

    fun endStroke() {
        val before = stroke.before ?: return
        stroke.before = null
        undo.record(before, mask)
        refreshUndo()
    }

    /** A second finger arrived while a brush stroke was open: throw that stroke away (it was a pinch). */
    fun cancelStroke() {
        val before = stroke.before ?: return
        stroke.before = null
        if (undo.record(before, mask)) undo.cancelLast(mask)
        version++
        refreshUndo()
    }

    fun brushAt(screen: Offset, first: Boolean) {
        val x = xfNow
        val eff = Offset(screen.x, screen.y - offsetDp * density)
        cursor = eff
        val bx = x.toBmpX(eff.x)
        val by = x.toBmpY(eff.y)
        val radius = (brushDp * density / 2f) / x.scale
        MaskEditor.brushStroke(
            mask, stroke.lastX, stroke.lastY, bx, by, radius,
            isErase = tool == EraseTool.MANUAL, first = first
        )
        stroke.lastX = bx
        stroke.lastY = by
        version++
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            EditorTopBar(
                onBack = onBack,
                actionLabel = stringResource(R.string.done),
                onAction = {
                    if (!blocked()) {
                        if (hasLivePreview()) settleThen { onDone(mask) } else onDone(mask)
                    }
                }
            )

            // Canvas
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(tool) {
                        // One finger = the selected tool. Two fingers = pinch-zoom + pan, in EVERY tool
                        // (no need to switch to the Zoom tool). The Zoom tool itself also pans with one finger.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val isWand = tool == EraseTool.COLOR || tool == EraseTool.MAGIC
                            val isBrush = tool == EraseTool.MANUAL || tool == EraseTool.REPAIR
                            val toolActs = isWand || isBrush
                            var acting = toolActs && !blocked()
                            if (acting && isBrush && hasLivePreview()) {
                                // an AI preview is still open: settle it first, this touch is skipped
                                settleThen()
                                acting = false
                            }
                            var strokeOpen = false
                            if (acting) {
                                down.consume()
                                if (isWand) {
                                    cursor = Offset(down.position.x, down.position.y - offsetDp * density)
                                } else {
                                    beginStroke()
                                    strokeOpen = true
                                    brushAt(down.position, true)
                                }
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) {
                                        if (acting && isWand) applyAtCursor()
                                        break
                                    }
                                    if (pressed.size >= 2 || !toolActs) {
                                        if (acting) {
                                            // a second finger arrived: it was a zoom, not a tool action
                                            acting = false
                                            if (strokeOpen) {
                                                strokeOpen = false
                                                cancelStroke()
                                            }
                                            cursor = null
                                        }
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        val centroid = event.calculateCentroid(useCurrent = false)
                                        val oldZoom = zoom
                                        val newZoom = (oldZoom * zoomChange).coerceIn(1f, 8f)
                                        val ratio = newZoom / oldZoom
                                        val cx = canvasSize.width / 2f
                                        val cy = canvasSize.height / 2f
                                        if (centroid.isSpecified) {
                                            pan = if (newZoom <= 1.001f) {
                                                Offset.Zero
                                            } else {
                                                Offset(
                                                    centroid.x + panChange.x - cx - (centroid.x - cx - pan.x) * ratio,
                                                    centroid.y + panChange.y - cy - (centroid.y - cy - pan.y) * ratio
                                                )
                                            }
                                            zoom = newZoom
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (acting) {
                                        val change = pressed[0]
                                        if (isWand) {
                                            cursor = Offset(change.position.x, change.position.y - offsetDp * density)
                                        } else {
                                            brushAt(change.position, false)
                                        }
                                        change.consume()
                                    }
                                }
                            } finally {
                                if (strokeOpen) endStroke()
                            }
                        }
                    }
            ) {
                CutoutLayers(original, preview ?: mask, version, bgMode, xf, Modifier.fillMaxSize())
                val ring = cursor
                if (ring != null && tool != EraseTool.ZOOM && tool != EraseTool.AUTO) {
                    val isBrush = tool == EraseTool.MANUAL || tool == EraseTool.REPAIR
                    val ringColor = if (tool == EraseTool.REPAIR) Color(0xFF22C55E) else Color.Red
                    Canvas(Modifier.fillMaxSize()) {
                        val r = if (isBrush) brushDp * density / 2f else 16.dp.toPx()
                        drawCircle(
                            color = ringColor,
                            radius = r,
                            center = ring,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        if (!isBrush) {
                            drawLine(ringColor, Offset(ring.x - r, ring.y), Offset(ring.x + r, ring.y), 1.5.dp.toPx())
                            drawLine(ringColor, Offset(ring.x, ring.y - r), Offset(ring.x, ring.y + r), 1.5.dp.toPx())
                        }
                    }
                }
            }

            // Controls (compact)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 4.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    if (pending?.ai != null) {
                        LabeledSlider(stringResource(R.string.ai_strength), aiStrength, 0f..100f) {
                            aiStrength = it
                            requestPending(it)
                        }
                    }
                    when (tool) {
                        EraseTool.MANUAL, EraseTool.REPAIR -> {
                            LabeledSlider(stringResource(R.string.brush_size), brushDp, 8f..80f) { brushDp = it }
                            LabeledSlider(stringResource(R.string.cursor_offset), offsetDp, 0f..120f) { setOffset(it) }
                        }
                        EraseTool.COLOR, EraseTool.MAGIC -> {
                            LabeledSlider(stringResource(R.string.tolerance), tolerance, 0f..100f) {
                                tolerance = it
                                if (pending != null) requestPending(it)
                            }
                            LabeledSlider(stringResource(R.string.cursor_offset), offsetDp, 0f..120f) { setOffset(it) }
                        }
                        else -> Unit
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BgSwatch(bgMode, stringResource(R.string.bg)) { bgMode = (bgMode + 1) % 4 }
                    Text(
                        stringResource(toolHint(tool)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                    UndoRedoButton(
                        false, stringResource(R.string.undo), undoCount > 0,
                        count = undoCount, onLongClick = { doUndoAll() }
                    ) { doUndo() }
                    UndoRedoButton(true, stringResource(R.string.redo), redoCount > 0, count = redoCount) { doRedo() }
                }
                Row(Modifier.fillMaxWidth()) {
                    for (t in EraseTool.entries) {
                        val selected = t == tool
                        val tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                        Column(
                            Modifier
                                .weight(1f)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable(enabled = !busy) {
                                    cursor = null
                                    if (t == EraseTool.AUTO) runAuto() else tool = t
                                }
                                .padding(vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            when (t) {
                                EraseTool.AUTO -> PersonIcon(tint, 20.dp)
                                EraseTool.COLOR -> StarIcon(tint, 20.dp)
                                EraseTool.MAGIC -> MagicIcon(tint, 20.dp)
                                EraseTool.MANUAL -> EraserIcon(tint, 20.dp)
                                EraseTool.REPAIR -> BrushIcon(tint, 20.dp)
                                EraseTool.ZOOM -> Icon(
                                    Icons.Filled.Search, contentDescription = null,
                                    tint = tint, modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                stringResource(toolLabel(t)),
                                fontSize = 11.sp,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                color = tint
                            )
                        }
                    }
                }
            }
        }
        if (rememberDelayedFlag(busy)) BusyOverlay(busyMessage)
    }
}

@Composable
fun BusyOverlay(message: String? = null) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(6.dp))
            Text(
                message ?: stringResource(R.string.processing),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}
