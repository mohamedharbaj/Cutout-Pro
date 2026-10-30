package com.example.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.UndoRedoManager
import com.example.ui.components.CheckerboardBackground
import com.example.ui.components.EditorTopBar
import com.example.ui.components.rememberDelayedFlag
import com.example.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { HOME, CROP, ERASE, SMOOTH, SAVED }

@Composable
fun CutoutApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(Step.HOME) }
    // [source] = the picture exactly as picked (never changed); [original] = the cropped working copy.
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var mask by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    // One history for the whole editing session: it keeps every edit back to the original picture
    // while moving between Erase / Smooth / Saved, and is cleared only when the picture changes.
    val history = remember { UndoRedoManager() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                ImageUtils.loadBitmapFromUri(context, uri)
                    .onSuccess {
                        history.clear()
                        source = it
                        original = it
                        mask = null
                        result = null
                        step = Step.CROP
                    }
                    .onFailure { context.toast(context.getString(R.string.error_open)) }
                busy = false
            }
        }
    }

    BackHandler(enabled = step != Step.HOME) {
        step = when (step) {
            Step.CROP -> Step.HOME
            Step.ERASE -> {
                history.clear()
                mask = null   // going back restores the picture as it was before any erasing
                Step.CROP
            }
            Step.SMOOTH -> Step.ERASE
            Step.SAVED -> Step.SMOOTH
            Step.HOME -> Step.HOME
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val orig = original
        val currentMask = mask
        val currentResult = result
        when (step) {
            Step.HOME -> HomeScreen(onPick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })

            Step.CROP -> if (source != null) {
                CropScreen(
                    bitmap = source!!,
                    onBack = { step = Step.HOME },
                    onDone = { cropped ->
                        history.clear()
                        original = cropped
                        mask = null
                        step = Step.ERASE
                    }
                )
            }

            Step.ERASE -> if (orig != null) {
                EraseScreen(
                    original = orig,
                    initialMask = currentMask,
                    history = history,
                    onBack = {
                        history.clear()
                        mask = null   // back = picture as it was before erasing
                        step = Step.CROP
                    },
                    onDone = { finished ->
                        mask = finished
                        step = Step.SMOOTH
                    }
                )
            }

            Step.SMOOTH -> if (orig != null && currentMask != null) {
                SmoothScreen(
                    original = orig,
                    mask = currentMask,
                    onBack = { step = Step.ERASE },
                    onSave = { smoothed ->
                        scope.launch {
                            busy = true
                            val cutout = withContext(Dispatchers.Default) {
                                runCatching { ImageUtils.applyMask(orig, smoothed) }
                            }.getOrNull()
                            if (cutout == null) {
                                context.toast(context.getString(R.string.error_process))
                            } else if (ImageUtils.saveToGallery(context, cutout, "PNG").isSuccess) {
                                result = cutout
                                step = Step.SAVED
                            } else {
                                context.toast(context.getString(R.string.error_save))
                            }
                            busy = false
                        }
                    }
                )
            }

            Step.SAVED -> if (currentResult != null) {
                SavedScreen(
                    cutout = currentResult,
                    onBack = { step = Step.SMOOTH },
                    onFinish = {
                        history.clear()
                        source = null
                        original = null
                        mask = null
                        result = null
                        step = Step.HOME
                    }
                )
            }
        }
        if (rememberDelayedFlag(busy)) BusyOverlay()
    }
}

@Composable
private fun HomeScreen(onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.app_icon),
            contentDescription = null,
            modifier = Modifier.size(104.dp).clip(RoundedCornerShape(24.dp))
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.home_hint),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.select_photo), style = MaterialTheme.typography.titleMedium)
        }
    }
}
