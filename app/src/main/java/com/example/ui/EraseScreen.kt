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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.MaskEditor
import com.example.engine.UndoRedoManager
import com.example.ui.components.BgSwatch
import com.example.ui.components.BrushIcon
import com.example.ui.components.EditorTopBar
import com.example.ui.components.EraserIcon
import com.example.ui.components.LabeledSlider
import com.example.ui.components.MagicIcon
import com.example.ui.components.StarIcon
import com.example.ui.components.UndoRedoButton
import kotlinx.coroutines.launch

enum class EraseTool { COLOR, MAGIC, MANUAL, REPAIR, ZOOM }

private class StrokeState {
    var lastX = 0f
    var lastY = 0f
}

@StringRes
private fun toolLabel(tool: EraseTool): Int = when (tool) {
    EraseTool.COLOR -> R.string.tool_color
    EraseTool.MAGIC -> R.string.tool_magic
    EraseTool.MANUAL -> R.string.tool_manual
    EraseTool.REPAIR -> R.string.tool_repair
    EraseTool.ZOOM -> R.string.tool_zoom
}

@StringRes
private fun toolHint(tool: EraseTool): Int = when (tool) {
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
    onBack: () -> Unit,
    onDone: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val undo = remember { UndoRedoManager(maxHistory = 6) }
    DisposableEffect(undo) { onDispose { undo.clear() } }
    val stroke = remember { StrokeState() }

    var mask by remember { mutableStateOf(initialMask ?: newOpaqueMask(original)) }
    var version by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(EraseTool.MANUAL) }
    var brushDp by remember { mutableFloatStateOf(28f) }
    var offsetDp by remember { mutableFloatStateOf(56f) }
    var tolerance by remember { mutableFloatStateOf(20f) }
    var bgMode by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var busy by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }

    val xf = ViewXform(
        canvasSize.width.toFloat(), canvasSize.height.toFloat(),
        original.width, original.height, zoom, pan
    )
    // Gesture lambdas outlive a single composition, so they must read the latest transform.
    val xfNow by rememberUpdatedState(xf)

    fun refreshUndo() {
        canUndo = undo.canUndo
        canRedo = undo.canRedo
    }

    fun commit(newMask: Bitmap) {
        undo.pushState(mask)
        mask = newMask
        version++
        refreshUndo()
    }

    fun doUndo() {
        undo.undo(mask)?.let {
            mask = it
            version++
            refreshUndo()
        }
    }

    fun doRedo() {
        undo.redo(mask)?.let {
            mask = it
            version++
            refreshUndo()
        }
    }

    fun tapAt(p: Offset) {
        val x = xfNow
        val bx = x.toBmpX(p.x).toInt()
        val by = x.toBmpY(p.y).toInt()
        if (bx !in 0 until original.width || by !in 0 until original.height) return
        val isColor = tool == EraseTool.COLOR
        val tol = tolerance
        scope.launch {
            busy = true
            runCatching {
                if (isColor) {
                    MaskEditor.removeByColor(original, mask, original.getPixel(bx, by), tol)
                } else {
                    MaskEditor.magicWandFloodFill(original, mask, bx, by, tol)
                }
            }.onSuccess { commit(it) }
                .onFailure { context.toast(context.getString(R.string.error_process)) }
            busy = false
        }
    }

    fun brushAt(screen: Offset, first: Boolean) {
        val x = xfNow
        val eff = Offset(screen.x, screen.y - offsetDp * density)
        cursor = eff
        val bx = x.toBmpX(eff.x)
        val by = x.toBmpY(eff.y)
        val radius = (brushDp * density / 2f) / x.scale
        if (first) {
            undo.pushState(mask)
            refreshUndo()
        }
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
                onAction = { onDone(mask) }
            )

            // Canvas
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(tool) {
                        when (tool) {
                            EraseTool.COLOR, EraseTool.MAGIC -> detectTapGestures { tapAt(it) }
                            EraseTool.MANUAL, EraseTool.REPAIR -> awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                brushAt(down.position, true)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    brushAt(change.position, false)
                                    change.consume()
                                }
                            }
                            EraseTool.ZOOM -> detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                val oldZoom = zoom
                                val newZoom = (oldZoom * zoomChange).coerceIn(1f, 8f)
                                val ratio = newZoom / oldZoom
                                val cx = canvasSize.width / 2f
                                val cy = canvasSize.height / 2f
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
                        }
                    }
            ) {
                CutoutLayers(original, mask, version, bgMode, xf, Modifier.fillMaxSize())
                val ring = cursor
                if ((tool == EraseTool.MANUAL || tool == EraseTool.REPAIR) && ring != null) {
                    val ringColor = if (tool == EraseTool.MANUAL) Color.Red else Color(0xFF22C55E)
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            color = ringColor,
                            radius = brushDp * density / 2f,
                            center = ring,
                            style = Stroke(width = 2.dp.toPx())
                        )
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
                    when (tool) {
                        EraseTool.MANUAL, EraseTool.REPAIR -> {
                            LabeledSlider(stringResource(R.string.brush_size), brushDp, 8f..80f) { brushDp = it }
                            LabeledSlider(stringResource(R.string.cursor_offset), offsetDp, 0f..120f) { offsetDp = it }
                        }
                        EraseTool.COLOR, EraseTool.MAGIC -> {
                            LabeledSlider(stringResource(R.string.tolerance), tolerance, 1f..100f) { tolerance = it }
                        }
                        else -> Unit
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BgSwatch(bgMode, stringResource(R.string.bg)) { bgMode = (bgMode + 1) % 3 }
                    Text(
                        stringResource(toolHint(tool)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                    UndoRedoButton(false, stringResource(R.string.undo), canUndo) { doUndo() }
                    UndoRedoButton(true, stringResource(R.string.redo), canRedo) { doRedo() }
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
                                    tool = t
                                }
                                .padding(vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            when (t) {
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
        if (busy) BusyOverlay()
    }
}

@Composable
fun BusyOverlay() {
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
            Text(stringResource(R.string.processing), color = Color.White)
        }
    }
}
