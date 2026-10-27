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
    var maskBitmap by remember {
        mutableStateOf(
            Bitmap.createBitmap(
                initialBitmap.width,
                initialBitmap.height,
                Bitmap.Config.ARGB_8888
            ).apply { eraseColor(android.graphics.Color.WHITE) }
        )
    }
    var cutoutBitmap by remember {
        mutableStateOf(ImageUtils.applyMask(originalBitmap, maskBitmap))
    }

    // Compositing settings
    var bgType by remember { mutableStateOf(existingProject?.bgType ?: "TRANSPARENT") }
    var bgColor by remember { mutableIntStateOf(existingProject?.bgColor?.toInt() ?: android.graphics.Color.WHITE) }
    var bgImage by remember { mutableStateOf<Bitmap?>(null) }

    var compositedBitmap by remember {
        mutableStateOf(ImageUtils.compositeImage(cutoutBitmap, bgType, bgColor, bgImage))
    }

    val undoRedoManager = remember { UndoRedoManager(maxHistory = 10) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }

    fun refreshUndoState() {
        canUndo = undoRedoManager.canUndo
        canRedo = undoRedoManager.canRedo
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

    // Load existing mask if opening a saved project
    LaunchedEffect(existingProject) {
        if (existingProject != null) {
            val savedMask = ImageUtils.loadBitmapFromFile(existingProject.maskImagePath)
            if (savedMask != null) {
                maskBitmap = savedMask
                updateCutout()
            }
        } else {
            // First time opening: run auto segmentation!
            scope.launch {
                isProcessing = true
                processingMessage = context.getString(R.string.processing_auto)
                undoRedoManager.pushState(maskBitmap)
                val engine = modelManager.getActiveEngine()
                runCatching {
                    val autoMask = engine.segment(originalBitmap)
                    maskBitmap = autoMask
                    updateCutout()
                }.onFailure {
                    Toast.makeText(context, "Segmentation failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
                isProcessing = false
                refreshUndoState()
            }
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
                        bgType = "IMAGE"
                        updateCutout()
                    }
                }
            }
        }
    )

    // Save project on leave
    fun saveProjectAndExit() {
        scope.launch {
            val origPath = existingProject?.originalImagePath
                ?: ImageUtils.saveBitmapToFile(context, originalBitmap, "orig_${System.currentTimeMillis()}.png")
            val maskPath = existingProject?.maskImagePath
                ?: ImageUtils.saveBitmapToFile(context, maskBitmap, "mask_${System.currentTimeMillis()}.png")

            // Create thumbnail
            val thumb = Bitmap.createScaledBitmap(cutoutBitmap, 180, (180f * cutoutBitmap.height / cutoutBitmap.width).toInt().coerceAtLeast(180), true)
            val thumbPath = ImageUtils.saveBitmapToFile(context, thumb, "thumb_${System.currentTimeMillis()}.png")
            thumb.recycle()

            val title = existingProject?.title ?: "Cutout_${System.currentTimeMillis() % 10000}"
            val entity = ProjectEntity(
                id = existingProject?.id ?: 0,
                title = title,
                updatedAt = System.currentTimeMillis(),
                originalImagePath = origPath,
                maskImagePath = maskPath,
                thumbnailPath = thumbPath,
                bgType = bgType,
                bgColor = bgColor.toLong(),
                bgImagePath = null,
                width = originalBitmap.width,
                height = originalBitmap.height
            )
            projectRepository.saveProject(entity)
            onBack()
        }
    }

    BackHandler {
        saveProjectAndExit()
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
                    IconButton(onClick = { saveProjectAndExit() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                actions = {
                    // Undo
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
                                    scope.launch {
                                        isProcessing = true
                                        processingMessage = context.getString(R.string.processing_auto)
                                        undoRedoManager.pushState(maskBitmap)
                                        val engine = modelManager.getActiveEngine()
                                        maskBitmap = engine.segment(originalBitmap)
                                        updateCutout()
                                        isProcessing = false
                                        refreshUndoState()
                                    }
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

                // Convert screen touch coordinate to image coordinate
                fun screenToBitmapCoord(screenX: Float, screenY: Float): Pair<Float, Float>? {
                    val adjustedX = (screenX - canvasWidth / 2f - offset.x) / scale + canvasWidth / 2f
                    val adjustedY = (screenY - canvasHeight / 2f - offset.y) / scale + canvasHeight / 2f

                    val relX = adjustedX - leftOffset
                    val relY = adjustedY - topOffset
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
                        .pointerInput(selectedTool, scale, offset) {
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
                                            scope.launch {
                                                isProcessing = true
                                                processingMessage = "Applying Magic Wand..."
                                                undoRedoManager.pushState(maskBitmap)
                                                maskBitmap = MaskEditor.magicWandFloodFill(
                                                    original = originalBitmap,
                                                    mask = maskBitmap,
                                                    startX = coord.first.toInt(),
                                                    startY = coord.second.toInt(),
                                                    tolerance = colorTolerance
                                                )
                                                updateCutout()
                                                isProcessing = false
                                                refreshUndoState()
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
                                            scope.launch {
                                                isProcessing = true
                                                processingMessage = "Removing color..."
                                                undoRedoManager.pushState(maskBitmap)
                                                maskBitmap = MaskEditor.removeByColor(
                                                    original = originalBitmap,
                                                    mask = maskBitmap,
                                                    targetColor = targetColor,
                                                    tolerance = colorTolerance
                                                )
                                                updateCutout()
                                                isProcessing = false
                                                refreshUndoState()
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
                    Image(
                        bitmap = activeDisplayBitmap.asImageBitmap(),
                        contentDescription = "Editing Canvas",
                        modifier = Modifier.size((renderedW / fitScale).dp * fitScale, (renderedH / fitScale).dp * fitScale)
                    )

                    // Live brush cursor preview
                    touchCursorPos?.let { pos ->
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = if (selectedTool == EditorTool.ERASER) Color.Red else Color.Cyan,
                                radius = brushRadius * scale,
                                center = pos,
                                style = Stroke(width = 3.dp.toPx())
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
                    scope.launch {
                        isProcessing = true
                        processingMessage = "Smoothing edges..."
                        undoRedoManager.pushState(maskBitmap)
                        maskBitmap = MaskEditor.smoothEdges(maskBitmap, smoothRadius.toInt())
                        updateCutout()
                        isProcessing = false
                        refreshUndoState()
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

                    undoRedoManager.pushState(maskBitmap)
                    val (croppedOrig, croppedMask) = MaskEditor.cropBitmaps(originalBitmap, maskBitmap, cropRect)
                    originalBitmap = croppedOrig
                    maskBitmap = croppedMask
                    updateCutout()
                    refreshUndoState()
                    Toast.makeText(context, "Crop applied", Toast.LENGTH_SHORT).show()
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
                onRunAuto = {
                    scope.launch {
                        isProcessing = true
                        processingMessage = context.getString(R.string.processing_auto)
                        undoRedoManager.pushState(maskBitmap)
                        val engine = modelManager.getActiveEngine()
                        maskBitmap = engine.segment(originalBitmap)
                        updateCutout()
                        isProcessing = false
                        refreshUndoState()
                    }
                }
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
                        onClick = {
                            selectedTool = EditorTool.AUTO
                            scope.launch {
                                isProcessing = true
                                processingMessage = context.getString(R.string.processing_auto)
                                undoRedoManager.pushState(maskBitmap)
                                val engine = modelManager.getActiveEngine()
                                maskBitmap = engine.segment(originalBitmap)
                                updateCutout()
                                isProcessing = false
                                refreshUndoState()
                            }
                        }
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
