package com.example.ui

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.CheckerboardBackground
import com.example.ui.components.EditorTopBar
import com.example.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BG_COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
    0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFEC407A, 0xFF795548, 0xFF9E9E9E
).map { it.toInt() }

/**
 * Last screen. The transparent PNG is already in the gallery. From here: share, send to WhatsApp, save a
 * white-background JPEG, or change the background (solid colour or a photo from the gallery) and save / share
 * the result. Feedback is shown in a status line (no toast covering the buttons).
 */
@Composable
fun SavedScreen(
    cutout: Bitmap,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary

    var bgColor by remember { mutableStateOf<Int?>(null) }
    var bgPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var panelOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    fun say(text: String, error: Boolean = false) {
        status = text
        statusIsError = error
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                ImageUtils.loadBitmapFromUri(context, uri)
                    .onSuccess { bgPhoto = it; bgColor = null; status = null }
                    .onFailure { say(context.getString(R.string.error_open), true) }
                working = false
            }
        }
    }

    val hasBg = bgColor != null || bgPhoto != null

    /** The picture with the chosen background (or the transparent cutout when none is chosen). */
    suspend fun flat(): Bitmap {
        val color = bgColor
        val photo = bgPhoto
        return withContext(Dispatchers.Default) {
            when {
                photo != null -> ImageUtils.compositeImage(cutout, "IMAGE", AColor.WHITE, photo)
                color != null -> ImageUtils.compositeImage(cutout, "COLOR", color, null)
                else -> cutout
            }
        }
    }

    fun shareTo(packageName: String?) {
        if (working) return
        scope.launch {
            working = true
            status = null
            val bmp = flat()
            val png = !hasBg
            val direct = ImageUtils.createShareIntent(context, bmp, png, packageName)
            direct.onSuccess { intent ->
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    // app not installed -> normal share sheet
                    ImageUtils.createShareIntent(context, bmp, png)
                        .onSuccess { context.startActivity(it) }
                        .onFailure { say(context.getString(R.string.error_process), true) }
                }
            }.onFailure { say(context.getString(R.string.error_process), true) }
            working = false
        }
    }

    fun saveJpeg(color: Int?, photo: Bitmap?) {
        if (working) return
        scope.launch {
            working = true
            status = null
            val flat = withContext(Dispatchers.Default) {
                when {
                    photo != null -> ImageUtils.compositeImage(cutout, "IMAGE", AColor.WHITE, photo)
                    else -> ImageUtils.compositeImage(cutout, "COLOR", color ?: AColor.WHITE, null)
                }
            }
            val saved = ImageUtils.saveToGallery(context, flat, "JPEG", 95)
            if (saved.isSuccess) say(context.getString(R.string.jpeg_saved_to))
            else say(context.getString(R.string.error_save), true)
            working = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        EditorTopBar(
            onBack = onBack,
            title = stringResource(R.string.saved_title),
            actionLabel = stringResource(R.string.finish),
            onAction = onFinish
        )

        // Preview
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CheckerboardBackground()
            val photo = bgPhoto
            val color = bgColor
            if (photo == null && color == null) {
                Image(
                    bitmap = cutout.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.aspectRatio(cutout.width.toFloat() / cutout.height.toFloat())) {
                        if (photo != null) {
                            Image(
                                bitmap = photo.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (color != null) {
                            Box(Modifier.fillMaxSize().background(Color(color)))
                        }
                        Image(
                            bitmap = cutout.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                status ?: stringResource(R.string.saved_to),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    status == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    statusIsError -> MaterialTheme.colorScheme.error
                    else -> Color(0xFF81C995)
                },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            if (panelOpen) {
                Text(
                    stringResource(R.string.change_bg_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // transparent (no background)
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (!hasBg) primary else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { bgColor = null; bgPhoto = null; status = null },
                        contentAlignment = Alignment.Center
                    ) {
                        CheckerboardBackground()
                    }
                    for (c in BG_COLORS) {
                        val selected = bgColor == c && bgPhoto == null
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) primary else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                                .clickable { bgColor = c; bgPhoto = null; status = null }
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        border = BorderStroke(1.dp, if (bgPhoto != null) primary else MaterialTheme.colorScheme.outline)
                    ) { Text(stringResource(R.string.bg_photo), color = primary) }
                }
                if (hasBg) {
                    Button(
                        onClick = { saveJpeg(bgColor, bgPhoto) },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.save_with_bg)) }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { shareTo(null) },
                    enabled = !working,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.share)) }
                OutlinedButton(
                    onClick = { shareTo("com.whatsapp") },
                    enabled = !working,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, primary)
                ) { Text(stringResource(R.string.whatsapp), color = primary) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { saveJpeg(AColor.WHITE, null) },
                    enabled = !working,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, primary)
                ) { Text(stringResource(R.string.save_jpeg), color = primary, textAlign = TextAlign.Center, fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { panelOpen = !panelOpen },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, primary)
                ) { Text(stringResource(R.string.change_bg), color = primary, textAlign = TextAlign.Center, fontSize = 12.sp) }
            }
        }
    }
}
