package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.ProjectEntity
import com.example.data.repository.ProjectRepository
import com.example.engine.MaskEditor
import com.example.engine.ModelManager
import com.example.engine.UndoRedoManager
import com.example.ui.components.CheckerboardBackground
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ErrorRed
import com.example.util.ImageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

enum class EditorTool {
    AUTO,
    MAGIC_WAND,
    AUTO_COLOR,
    ERASER,
    REPAIR,
    SMOOTH,
    CROP,
    BACKGROUND,
    ZOOM
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    initialBitmap: Bitmap,
    existingProject: ProjectEntity? = null,
    modelManager: ModelManager,
    projectRepository: ProjectRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var originalBitmap by remember { mutableStateOf(initialBitmap) }
    // Snapshot of the mask as it was when the editor first opened (all-white = untouched).
    // Updated once when an existing project is loaded, then stays fixed.
    // Used to restore the original state when the user presses Back once.
    var initialMaskSnapshot by remember {
        mutableStateOf(
            Bitmap.createBitmap(
                initialBitmap.width,
                initialBitmap.height,
                Bitmap.Config.ARGB_8888
            ).apply { eraseColor(android.graphics.Color.WHITE) }
        )
    }
    var maskBitmap by remember {
        mutableStateOf(
            Bitmap.createBitmap(
                initialBitmap.width,
                initialBitmap.height,
                Bitmap.Config.ARGB_8888
            ).apply { eraseColor(android.graphics.Color.WHITE) }
        )
    }
    // Whether the user has made any edits since opening (used to decide what Back does).
    var hasUnsavedEdits by remember { mutableStateOf(false) }
    var cutoutBitmap by remember {
        mutableStateOf(ImageUtils.applyMask(originalBitmap, maskBitmap))
    }

    // Compositing settings
    var bgType by remember { mutableStateOf(existingProject?.bgType ?: "TRANSPARENT") }
    var bgColor by remember { mutableIntStateOf(existingProject?.bgColor?.toInt() ?: android.graphics.Color.WHITE) }
    var bgImage by remember { mutableStateOf<Bitmap?>(null) }
    var bgImageChanged by remember { mutableStateOf(false) }
    // Set when crop replaces the original bitmap, so the saved original file must be rewritten too.
    var originalChanged by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveFailedOnce by remember { mutableStateOf(false) }

    var compositedBitmap by remember {
        mutableStateOf(ImageUtils.compositeImage(cutoutBitmap, bgType, bgColor, bgImage))
    }

    val undoRedoManager = remember { UndoRedoManager(maxHistory = 10) }
    // Free the (up to 10 + redo) full-size mask snapshots when leaving the editor.
    DisposableEffect(undoRedoManager) {
        onDispose { undoRedoManager.clear() }
    }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var undoPressCount by remember { mutableIntStateOf(0) }

    fun refreshUndoState() {
        canUndo = undoRedoManager.canUndo
        canRedo = undoRedoManager.canRedo
        undoPressCount = undoRedoManager.undoPressCount
        // Mark edits as present whenever a new state is pushed onto the undo stack.
        if (undoRedoManager.undoStackSize > 0) hasUnsavedEdits = true
    }

    fun updateCutout() {
        cutoutBitmap = ImageUtils.applyMask(originalBitmap, maskBitmap)
        compositedBitmap = ImageUtils.compositeImage(cutoutBitmap, bgType, bgColor, bgImage)
    }

    // Tools state
    var selectedTool by remember { mutableStateOf(EditorTool.AUTO) }
    var brushRadius by remember { mutableFloatStateOf(24f) }
    var colorTolerance by remember { mutableFloatStateOf(35f) }
    var smoothRadius by remember { mutableFloatStateOf(4f) }
    var selectedCropAspect by remember { mutableStateOf("ORIGINAL") } // ORIGINAL, 1:1, 4:3, 16:9, 9:16, 3:4

    // Interaction states
    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("Processing...") }
    var showExportSheet by remember { mutableStateOf(false) }
    var isHoldingCompare by remember { mutableStateOf(false) }
    var showResetMenu by remember { mutableStateOf(false) }

    // Touch cursor position for brush
    var touchCursorPos by remember { mutableStateOf<Offset?>(null) }

    // Canvas zoom & pan
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5.0f)
        offset += offsetChange
    }

    /**
     * Runs the segmentation engine. Falls back to the built-in engine if the neural engine
     * fails (corrupt model, out of memory, ...) instead of crashing the app, and always clears
     * the "processing" state. The undo snapshot is only pushed once a new mask really exists.
     */
    fun runAutoCutout() {
        if (isProcessing) return
        scope.launch {
            isProcessing = true
            processingMessage = context.getString(R.string.processing_auto)
            try {
                val primary = modelManager.getActiveEngine()
                val newMask = try {
                    withContext(Dispatchers.Default) { primary.segment(originalBitmap) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Primary engine failed — try the built-in fallback before giving up.
                    val fallback = modelManager.getFallbackEngine()
                    if (primary === fallback) throw e
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "AI model failed, switching to built-in engine…",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    try {
                        withContext(Dispatchers.Default) { fallback.segment(originalBitmap) }
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (e2: Throwable) {
                        throw e2
                    }
                }
                undoRedoManager.pushState(maskBitmap)
                maskBitmap = newMask
                updateCutout()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Auto cutout failed: ${e.message ?: "Unknown error"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                isProcessing = false
                refreshUndoState()
            }
        }
    }

    /** Runs a (suspending) mask operation with progress, error handling and one undo step. */
    fun runMaskOperation(message: String, operation: suspend (Bitmap) -> Bitmap) {
        if (isProcessing) return
        scope.launch {
            isProcessing = true
            processingMessage = message
            try {
                val newMask = operation(maskBitmap)
                undoRedoManager.pushState(maskBitmap)
                maskBitmap = newMask
                updateCutout()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.makeText(context, "Operation failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isProcessing = false
                refreshUndoState()
            }
        }
    }

    // Load existing mask if opening a saved project, otherwise run auto segmentation
    LaunchedEffect(existingProject) {
        if (existingProject != null) {
            val savedMask = ImageUtils.loadBitmapFromFile(existingProject.maskImagePath)
            if (savedMask != null) {
                // Guard against a mask whose size no longer matches the original.
                val correctedMask = if (savedMask.width == originalBitmap.width && savedMask.height == originalBitmap.height) {
                    savedMask
                } else {
                    ImageUtils.scaleToNewBitmap(savedMask, originalBitmap.width, originalBitmap.height)
                }
                maskBitmap = correctedMask
                // Snapshot the saved mask so Back restores it rather than a blank mask.
                initialMaskSnapshot = MaskEditor.copyBitmap(correctedMask)
            }
            // The background photo was never restored before (bgImagePath was always saved as null).
            existingProject.bgImagePath?.let { path ->
                ImageUtils.loadBitmapFromFile(path)?.let { bgImage = it }
            }
            updateCutout()
        } else {
            runAutoCutout()
        }
    }

    // Background Image Picker
    val bgPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    ImageUtils.loadBitmapFromUri(context, uri).onSuccess { pickedBg ->
                        bgImage = pickedBg
                        bgImageChanged = true
                        bgType = "IMAGE"
                        updateCutout()
                    }
                }
            }
        }
    )

    // Save project on leave
    fun saveProjectAndExit() {
        if (isSaving) return
        if (saveFailedOnce) {
            // Saving already failed once (e.g. storage full): let the user leave on the second try.
            onBack()
            return
        }
        isSaving = true
        val orig = originalBitmap
        val mask = maskBitmap
        val cutout = cutoutBitmap
        val bg = bgImage
        scope.launch {
            try {
                val stamp = System.currentTimeMillis()

                // Existing projects keep their file names and are OVERWRITTEN. Previously the mask of an
                // existing project was never re-saved, so every edit was lost after reopening it.
                val origName = existingProject?.let { File(it.originalImagePath).name } ?: "orig_$stamp.png"
                val maskName = existingProject?.let { File(it.maskImagePath).name } ?: "mask_$stamp.png"

                val origPath = if (existingProject == null || originalChanged) {
                    ImageUtils.saveBitmapToFile(context, orig, origName)
                } else {
                    existingProject.originalImagePath
                }
                val maskPath = ImageUtils.saveBitmapToFile(context, mask, maskName)

                // Thumbnail (new file name each save so Coil never serves a stale cached one).
                val thumbPath = withContext(Dispatchers.Default) {
                    val thumbW = 180
                    val thumbH = (thumbW.toFloat() * cutout.height / cutout.width).toInt().coerceAtLeast(1)
                    val thumb = ImageUtils.scaleToNewBitmap(cutout, thumbW, thumbH)
                    try {
                        ImageUtils.saveBitmapToFile(context, thumb, "thumb_$stamp.png")
                    } finally {
                        thumb.recycle()
                    }
                }
                existingProject?.thumbnailPath?.let { old ->
                    if (old != thumbPath) withContext(Dispatchers.IO) { File(old).delete() }
                }

                // Persist the custom background photo.
                var bgPath = existingProject?.bgImagePath
                if (bg != null && (bgImageChanged || bgPath == null)) {
                    val newBgPath = ImageUtils.saveBitmapToFile(context, bg, "bg_$stamp.png")
                    bgPath?.let { old -> withContext(Dispatchers.IO) { File(old).delete() } }
                    bgPath = newBgPath
                }

                val entity = ProjectEntity(
                    id = existingProject?.id ?: 0,
                    title = existingProject?.title ?: "Cutout_${stamp % 10000}",
                    createdAt = existingProject?.createdAt ?: stamp, // was reset on every save
                    updatedAt = stamp,
                    originalImagePath = origPath,
                    maskImagePath = maskPath,
                    thumbnailPath = thumbPath,
                    bgType = bgType,
                    bgColor = bgColor.toLong(),
                    bgImagePath = bgPath,
                    width = orig.width,
                    height = orig.height
                )
                projectRepository.saveProject(entity)
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                saveFailedOnce = true
                Toast.makeText(
                    context,
                    "Could not save project: ${e.message}. Press back again to leave without saving.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isSaving = false
            }
        }
    }

    BackHandler {
        if (hasUnsavedEdits) {
            undoRedoManager.pushState(maskBitmap)
            maskBitmap = MaskEditor.copyBitmap(initialMaskSnapshot)
            updateCutout()
            refreshUndoState()
            hasUnsavedEdits = false
            Toast.makeText(context, "Restored to original. Press back again to exit.", Toast.LENGTH_SHORT).show()
        } else {
            saveProjectAndExit()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasUnsavedEdits) {
                            // First back press: restore the mask to the original (pre-edit) state.
                            undoRedoManager.pushState(maskBitmap)
                            maskBitmap = MaskEditor.copyBitmap(initialMaskSnapshot)
                            updateCutout()
                            refreshUndoState()
                            hasUnsavedEdits = false
                            Toast.makeText(context, "Restored to original. Press back again to exit.", Toast.LENGTH_SHORT).show()
                        } else {
                            saveProjectAndExit()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                actions = {
                    // Undo (with press-count badge)
                    BadgedBox(
                        badge = {
                            if (undoPressCount > 0) {
                                Badge(
                                    containerColor = ElectricBlue,
                                    contentColor = Color.White
                                ) {
                                    Text(
                                        text = undoPressCount.toString(),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    ) {
                        IconButton(
                            onClick = {
                                val prev = undoRedoManager.undo(maskBitmap)
                                if (prev != null) {
                                    maskBitmap = prev
                                    updateCutout()
                                    refreshUndoState()
                                }
                            },
                            enabled = canUndo,
                            modifier = Modifier.testTag("undo_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = stringResource(R.string.undo),
                                tint = if (canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }

                    // Redo
                    IconButton(
                        onClick = {
                            val next = undoRedoManager.redo(maskBitmap)
                            if (next != null) {
                                maskBitmap = next
                                updateCutout()
                                refreshUndoState()
                            }
                        },
                        enabled = canRedo,
                        modifier = Modifier.testTag("redo_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = stringResource(R.string.redo),
                            tint = if (canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }

                    // Reset Menu
                    Box {
                        IconButton(onClick = { showResetMenu = true }) {
                            Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.reset))
                        }
                        DropdownMenu(
                            expanded = showResetMenu,
                            onDismissRequest = { showResetMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Re-run Auto Cutout") },
                                onClick = {
                                    showResetMenu = false
                                    runAutoCutout()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reset to Original Image") },
                                onClick = {
                                    showResetMenu = false
                                    undoRedoManager.pushState(maskBitmap)
                                    maskBitmap.eraseColor(android.graphics.Color.WHITE)
                                    updateCutout()
                                    refreshUndoState()
                                }
                            )
                        }
                    }

                    // Export Button
                    Button(
                        onClick = { showExportSheet = true },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("export_button")
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.export), fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Main Editing Canvas (Checkerboard + Cutout / Original)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                    .background(Color(0xFF0F172A)),
                contentAlignment = Alignment.Center
            ) {
                val canvasWidth = constraints.maxWidth.toFloat()
                val canvasHeight = constraints.maxHeight.toFloat()

                // Checkerboard background
                CheckerboardBackground(squareSize = 12.dp)

                // The composited image or original comparison
                val activeDisplayBitmap = if (isHoldingCompare) originalBitmap else compositedBitmap

                // Aspect ratio fitting calculations
                val imgWidth = activeDisplayBitmap.width.toFloat()
                val imgHeight = activeDisplayBitmap.height.toFloat()
                val fitScale = min(canvasWidth / imgWidth, canvasHeight / imgHeight) * 0.92f
                val renderedW = imgWidth * fitScale
                val renderedH = imgHeight * fitScale

                val leftOffset = (canvasWidth - renderedW) / 2f
                val topOffset = (canvasHeight - renderedH) / 2f

                // Convert a touch coordinate to an image coordinate.
                // The pointerInput below is declared AFTER graphicsLayer in the modifier chain, so Compose
                // already hands it positions in the layer's own (un-zoomed, un-panned) space. The old code
                // inverted the zoom/pan a second time, so brush / wand taps landed in the wrong place
                // whenever the canvas was zoomed or panned.
                fun screenToBitmapCoord(screenX: Float, screenY: Float): Pair<Float, Float>? {
                    val relX = screenX - leftOffset
                    val relY = screenY - topOffset
                    if (relX < 0 || relX >= renderedW || relY < 0 || relY >= renderedH) return null

                    val bmpX = (relX / renderedW) * imgWidth
                    val bmpY = (relY / renderedH) * imgHeight
                    return Pair(bmpX, bmpY)
                }

                // Image Container with Zoom & Pan + Touch Modifiers
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (selectedTool == EditorTool.ZOOM) {
                                Modifier.transformable(state = transformState)
                            } else {
                                Modifier
                            }
                        )
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .pointerInput(selectedTool, fitScale, leftOffset, topOffset, renderedW, renderedH) {
                            when (selectedTool) {
                                EditorTool.ERASER, EditorTool.REPAIR -> {
                                    detectDragGestures(
                                        onDragStart = { startPos ->
                                            undoRedoManager.pushState(maskBitmap)
                                            touchCursorPos = startPos
                                            val coord = screenToBitmapCoord(startPos.x, startPos.y)
                                            if (coord != null) {
                                                val radiusInBmp = (brushRadius / fitScale) / scale
                                                MaskEditor.applyBrush(
                                                    mask = maskBitmap,
                                                    centerX = coord.first,
                                                    centerY = coord.second,
                                                    radius = radiusInBmp,
                                                    isErase = (selectedTool == EditorTool.ERASER)
                                                )
                                                updateCutout()
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            touchCursorPos = change.position
                                            val coord = screenToBitmapCoord(change.position.x, change.position.y)
                                            if (coord != null) {
                                                val radiusInBmp = (brushRadius / fitScale) / scale
                                                MaskEditor.applyBrush(
                                                    mask = maskBitmap,
                                                    centerX = coord.first,
                                                    centerY = coord.second,
                                                    radius = radiusInBmp,
                                                    isErase = (selectedTool == EditorTool.ERASER)
                                                )
                                                updateCutout()
                                            }
                                        },
                                        onDragEnd = {
                                            touchCursorPos = null
                                            refreshUndoState()
                                        },
                                        onDragCancel = {
                                            touchCursorPos = null
                                            refreshUndoState()
                                        }
                                    )
                                }
                                EditorTool.MAGIC_WAND -> {
                                    detectTapGestures { tapPos ->
                                        val coord = screenToBitmapCoord(tapPos.x, tapPos.y)
                                        if (coord != null) {
                                            val startX = coord.first.toInt()
                                            val startY = coord.second.toInt()
                                            runMaskOperation("Applying Magic Wand...") { currentMask ->
                                                MaskEditor.magicWandFloodFill(
                                                    original = originalBitmap,
                                                    mask = currentMask,
                                                    startX = startX,
                                                    startY = startY,
                                                    tolerance = colorTolerance
                                                )
                                            }
                                        }
                                    }
                                }
                                EditorTool.AUTO_COLOR -> {
                                    detectTapGestures { tapPos ->
                                        val coord = screenToBitmapCoord(tapPos.x, tapPos.y)
                                        if (coord != null) {
                                            val targetColor = originalBitmap.getPixel(
                                                coord.first.toInt().coerceIn(0, originalBitmap.width - 1),
                                                coord.second.toInt().coerceIn(0, originalBitmap.height - 1)
                                            )
                                            runMaskOperation("Removing color...") { currentMask ->
                                                MaskEditor.removeByColor(
                                                    original = originalBitmap,
                                                    mask = currentMask,
                                                    targetColor = targetColor,
                                                    tolerance = colorTolerance
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    // Other tools handled via toolbar
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // renderedW/H are in PIXELS. The old code did `px.dp`, i.e. treated pixels as dp, so on any
                    // screen with density > 1 the image was oversized and no longer matched the touch maths.
                    val density = LocalDensity.current
                    Image(
                        bitmap = activeDisplayBitmap.asImageBitmap(),
                        contentDescription = "Editing Canvas",
                        modifier = Modifier.size(
                            with(density) { renderedW.toDp() },
                            with(density) { renderedH.toDp() }
                        )
                    )

                    // Live brush cursor preview
                    touchCursorPos?.let { pos ->
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            // This Canvas is drawn inside the zoomed layer, so divide by `scale` to keep the
                            // cursor the same on-screen size as the brush (it was multiplied before = scaled twice).
                            drawCircle(
                                color = if (selectedTool == EditorTool.ERASER) Color.Red else Color.Cyan,
                                radius = brushRadius / scale,
                                center = pos,
                                style = Stroke(width = 3.dp.toPx() / scale)
                            )
                        }
                    }
                }

                // Floating Comparison Button (Hold to Compare)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        isHoldingCompare = true
                                        tryAwaitRelease()
                                        isHoldingCompare = false
                                    }
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Compare,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isHoldingCompare) "Showing Original" else stringResource(R.string.compare),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Center Loading Indicator
                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.8f))
                            .padding(horizontal = 24.dp, vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = ElectricBlue,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = processingMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Tool Options Bar (Sliders, Controls per selected tool)
            ToolOptionsPanel(
                selectedTool = selectedTool,
                brushRadius = brushRadius,
                onBrushRadiusChange = { brushRadius = it },
                colorTolerance = colorTolerance,
                onColorToleranceChange = { colorTolerance = it },
                smoothRadius = smoothRadius,
                onSmoothRadiusChange = { smoothRadius = it },
                onApplySmooth = {
                    val radius = smoothRadius.toInt()
                    runMaskOperation("Smoothing edges...") { currentMask ->
                        MaskEditor.smoothEdges(currentMask, radius)
                    }
                },
                selectedCropAspect = selectedCropAspect,
                onCropAspectSelect = { selectedCropAspect = it },
                onApplyCrop = {
                    val w = originalBitmap.width
                    val h = originalBitmap.height
                    val cropRect = when (selectedCropAspect) {
                        "1:1" -> {
                            val dim = min(w, h)
                            Rect((w - dim) / 2, (h - dim) / 2, (w + dim) / 2, (h + dim) / 2)
                        }
                        "4:3" -> {
                            val targetW = min(w, (h * 4) / 3)
                            val targetH = (targetW * 3) / 4
                            Rect((w - targetW) / 2, (h - targetH) / 2, (w + targetW) / 2, (h + targetH) / 2)
                        }
                        "16:9" -> {
                            val targetW = min(w, (h * 16) / 9)
                            val targetH = (targetW * 9) / 16
                            Rect((w - targetW) / 2, (h - targetH) / 2, (w + targetW) / 2, (h + targetH) / 2)
                        }
                        "9:16" -> {
                            val targetH = min(h, (w * 16) / 9)
                            val targetW = (targetH * 9) / 16
                            Rect((w - targetW) / 2, (h - targetH) / 2, (w + targetW) / 2, (h + targetH) / 2)
                        }
                        "3:4" -> {
                            val targetH = min(h, (w * 4) / 3)
                            val targetW = (targetH * 3) / 4
                            Rect((w - targetW) / 2, (h - targetH) / 2, (w + targetW) / 2, (h + targetH) / 2)
                        }
                        else -> Rect(0, 0, w, h)
                    }

                    // Nothing to crop -> don't create a pointless undo step / rewrite the original.
                    val isNoOp = selectedCropAspect == "ORIGINAL" || (cropRect.width() >= w && cropRect.height() >= h)
                    if (!isNoOp) {
                        val (croppedOrig, croppedMask) = MaskEditor.cropBitmaps(originalBitmap, maskBitmap, cropRect)
                        originalBitmap = croppedOrig
                        maskBitmap = croppedMask
                        originalChanged = true
                        // The undo history only stores masks of the OLD size; restoring one onto the cropped
                        // original would stretch it. Crop is therefore a history barrier.
                        undoRedoManager.clear()
                        updateCutout()
                        refreshUndoState()
                        Toast.makeText(context, "Crop applied", Toast.LENGTH_SHORT).show()
                    }
                },
                bgType = bgType,
                onBgTypeChange = {
                    bgType = it
                    updateCutout()
                },
                bgColor = bgColor,
                onBgColorChange = {
                    bgColor = it
                    bgType = "COLOR"
                    updateCutout()
                },
                onPickBgImage = {
                    bgPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRunAuto = { runAutoCutout() }
            )

            // Bottom Tools Scrollable Selector
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ToolButton(
                        icon = Icons.Default.AutoFixHigh,
                        label = stringResource(R.string.tool_auto),
                        isSelected = selectedTool == EditorTool.AUTO,
                        // Only selects the tool. It used to re-run segmentation on every tap, silently
                        // replacing manual edits; the panel below has an explicit "Re-Run Cutout" button.
                        onClick = { selectedTool = EditorTool.AUTO }
                    )
                }
                item {
                    ToolButton(
                        icon = Icons.Default.Tune,
                        label = stringResource(R.string.tool_magic),
                        isSelected = selectedTool == EditorTool.MAGIC_WAND,
                        onClick = { selectedTool = EditorTool.MAGIC_WAND }
                    )
                }
                item {
                    ToolButton(
                        icon = Icons.Default.ColorLens,
                        label = stringResource(R.string.tool_auto_color),
                        isSelected = selectedTool == EditorTool.AUTO_COLOR,
                        onClick = { selectedTool = EditorTool.AUTO_COLOR }
                    )
                }
                item {
                    ToolButton(
                        icon = Icons.Default.Delete,
                        label = stringResource(R.string.tool_eraser),
                        isSelected = selectedTool == EditorTool.ERASER,
                        onClick = { selectedTool = EditorTool.ERASER }
                    )
                }
                item {
                    ToolButton(
                        icon = Icons.Default.Brush,
                        label = stringResource(R.string.tool_repair),
                        isSelected = selectedTool == EditorTool.REPAIR,
                        onClick = { selectedTool = EditorTool.REPAIR }
                    )
                }
                item {
                    ToolButton(
                        icon = Icons.Default.FilterDrama,
                        label = stringResource(R.string.tool_smooth),
                        isSelected = selectedTool == EditorTool.SMOOTH,
                        onClick = { selectedTool = EditorTool.SMOOTH }
                    )
                }
                item {
                    ToolButton(
                        icon = Icons.Default.Crop,
                        label = stringResource(R.string.tool_crop),
                        isSelected = selectedTool == EditorTool.CROP,
                        onClick = { selectedTool = EditorTool.CROP }
                    )
                }
                item {
                    ToolButton(
                        icon = Icons.Default.Image,
                        label = stringResource(R.string.tool_bg),
                        isSelected = selectedTool == EditorTool.BACKGROUND,
                        onClick = { selectedTool = EditorTool.BACKGROUND }
                    )
                }
                item {
                    ToolButton(
                        icon = Icons.Default.ZoomIn,
                        label = stringResource(R.string.tool_zoom),
                        isSelected = selectedTool == EditorTool.ZOOM,
                        onClick = { selectedTool = EditorTool.ZOOM }
                    )
                }
            }
        }
    }

    // Export Bottom Sheet
    if (showExportSheet) {
        ExportBottomSheet(
            cutoutBitmap = cutoutBitmap,
            compositedBitmap = compositedBitmap,
            bgType = bgType,
            onDismiss = { showExportSheet = false }
        )
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isSelected) ElectricBlue else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) ElectricBlue else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ToolOptionsPanel(
    selectedTool: EditorTool,
    brushRadius: Float,
    onBrushRadiusChange: (Float) -> Unit,
    colorTolerance: Float,
    onColorToleranceChange: (Float) -> Unit,
    smoothRadius: Float,
    onSmoothRadiusChange: (Float) -> Unit,
    onApplySmooth: () -> Unit,
    selectedCropAspect: String,
    onCropAspectSelect: (String) -> Unit,
    onApplyCrop: () -> Unit,
    bgType: String,
    onBgTypeChange: (String) -> Unit,
    bgColor: Int,
    onBgColorChange: (Int) -> Unit,
    onPickBgImage: () -> Unit,
    onRunAuto: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        when (selectedTool) {
            EditorTool.AUTO -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Tap below to re-detect subject and remove background",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onRunAuto,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Re-Run Cutout")
                    }
                }
            }
            EditorTool.ERASER, EditorTool.REPAIR -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.brush_size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Slider(
                        value = brushRadius,
                        onValueChange = onBrushRadiusChange,
                        valueRange = 8f..80f,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${brushRadius.toInt()}px",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricBlue,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            EditorTool.MAGIC_WAND, EditorTool.AUTO_COLOR -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (selectedTool == EditorTool.MAGIC_WAND) "Tap connected background area to remove"
                        else "Tap any color in image to erase matching tones",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.tolerance),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Slider(
                            value = colorTolerance,
                            onValueChange = onColorToleranceChange,
                            valueRange = 5f..80f,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${colorTolerance.toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricBlue,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            EditorTool.SMOOTH -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.smoothness),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Slider(
                        value = smoothRadius,
                        onValueChange = onSmoothRadiusChange,
                        valueRange = 1f..15f,
                        steps = 14,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onApplySmooth,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Apply")
                    }
                }
            }
            EditorTool.CROP -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val aspects = listOf("ORIGINAL", "1:1", "4:3", "16:9", "9:16", "3:4")
                        items(aspects) { aspect ->
                            FilterChip(
                                selected = selectedCropAspect == aspect,
                                onClick = { onCropAspectSelect(aspect) },
                                label = { Text(aspect) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onApplyCrop,
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.apply_crop))
                    }
                }
            }
            EditorTool.BACKGROUND -> {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Transparent
                    item {
                        ColorSwatch(
                            label = "Clear",
                            isTransparent = true,
                            isSelected = bgType == "TRANSPARENT",
                            onClick = { onBgTypeChange("TRANSPARENT") }
                        )
                    }
                    // Solid Colors
                    val colors = listOf(
                        Pair("White", android.graphics.Color.WHITE),
                        Pair("Black", android.graphics.Color.BLACK),
                        Pair("Gray", android.graphics.Color.rgb(100, 116, 139)),
                        Pair("Blue", android.graphics.Color.rgb(37, 99, 235)),
                        Pair("Red", android.graphics.Color.rgb(220, 38, 38)),
                        Pair("Mint", android.graphics.Color.rgb(16, 185, 129)),
                        Pair("Gold", android.graphics.Color.rgb(245, 158, 11))
                    )
                    items(colors) { (label, col) ->
                        ColorSwatch(
                            label = label,
                            colorInt = col,
                            isSelected = bgType == "COLOR" && bgColor == col,
                            onClick = { onBgColorChange(col) }
                        )
                    }
                    // Custom Image Backdrop
                    item {
                        OutlinedButton(
                            onClick = onPickBgImage,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Custom Photo", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            EditorTool.ZOOM -> {
                Text(
                    text = "Use pinch and two fingers to zoom and pan the canvas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    label: String,
    colorInt: Int = 0,
    isTransparent: Boolean = false,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) ElectricBlue else MaterialTheme.colorScheme.outline,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isTransparent) {
            CheckerboardBackground(squareSize = 4.dp)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(colorInt))
            )
        }
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = if (colorInt == android.graphics.Color.WHITE || isTransparent) Color.Black else Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
