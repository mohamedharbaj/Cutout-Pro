package com.example.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
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
import com.example.ui.components.CheckerboardBackground
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
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var mask by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                ImageUtils.loadBitmapFromUri(context, uri)
                    .onSuccess {
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
            Step.ERASE -> Step.CROP
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

            Step.CROP -> if (orig != null) {
                CropScreen(
                    bitmap = orig,
                    onBack = { step = Step.HOME },
                    onDone = { cropped ->
                        if (cropped !== orig) {
                            original = cropped
                            mask = null
                        }
                        step = Step.ERASE
                    }
                )
            }

            Step.ERASE -> if (orig != null) {
                EraseScreen(
                    original = orig,
                    initialMask = currentMask,
                    onBack = { step = Step.CROP },
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
                    onShare = {
                        scope.launch {
                            ImageUtils.createShareIntent(context, currentResult, true)
                                .onSuccess { context.startActivity(it) }
                                .onFailure { context.toast(context.getString(R.string.error_process)) }
                        }
                    },
                    onSaveJpeg = {
                        scope.launch {
                            busy = true
                            val flat = withContext(Dispatchers.Default) {
                                ImageUtils.compositeImage(currentResult, "COLOR", AColor.WHITE, null)
                            }
                            val saved = ImageUtils.saveToGallery(context, flat, "JPEG", 95)
                            context.toast(
                                context.getString(
                                    if (saved.isSuccess) R.string.jpeg_saved else R.string.error_save
                                )
                            )
                            busy = false
                        }
                    },
                    onFinish = {
                        original = null
                        mask = null
                        result = null
                        step = Step.HOME
                    }
                )
            }
        }
        if (busy) BusyOverlay()
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
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(150.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.home_hint),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(72.dp)) {
            Text(stringResource(R.string.select_photo), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SavedScreen(
    cutout: Bitmap,
    onShare: () -> Unit,
    onSaveJpeg: () -> Unit,
    onFinish: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        Text(
            stringResource(R.string.saved_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CheckerboardBackground()
            Image(
                bitmap = cutout.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.saved_to),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.share))
            }
            OutlinedButton(onClick = onSaveJpeg, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.save_jpeg))
            }
            OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.finish))
            }
        }
    }
}
