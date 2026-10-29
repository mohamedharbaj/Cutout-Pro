package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.example.ui.components.BgSwatch
import com.example.ui.components.EditorTopBar
import com.example.R
import com.example.engine.MaskEditor

/** Edge smoothing preview (levels 0..5). [onSave] receives the smoothed mask. */
@Composable
fun SmoothScreen(
    original: Bitmap,
    mask: Bitmap,
    onBack: () -> Unit,
    onSave: (Bitmap) -> Unit
) {
    var level by remember { mutableIntStateOf(1) }
    var preview by remember { mutableStateOf(mask) }
    var version by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var bgMode by remember { mutableIntStateOf(0) }

    LaunchedEffect(level) {
        working = true
        preview = if (level == 0) mask else MaskEditor.smoothEdges(mask, level)
        version++
        working = false
    }

    val xf = ViewXform(canvasSize.width.toFloat(), canvasSize.height.toFloat(), original.width, original.height)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        EditorTopBar(
            onBack = onBack,
            title = stringResource(R.string.smooth_title),
            actionLabel = stringResource(R.string.save),
            actionEnabled = !working,
            onAction = { onSave(preview) }
        )

        CutoutLayers(
            original, preview, version, bgMode, xf,
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { canvasSize = it }
        )

        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BgSwatch(bgMode, stringResource(R.string.bg)) { bgMode = (bgMode + 1) % 4 }
            Text(
                stringResource(R.string.smooth),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            for (lv in 0..5) {
                val selected = lv == level
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { level = lv }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        lv.toString(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
