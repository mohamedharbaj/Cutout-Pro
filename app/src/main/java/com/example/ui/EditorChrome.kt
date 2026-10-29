package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Compact top bar: a clearly visible blue back ARROW on the left, optional title and action on the right. */
@Composable
fun EditorTopBar(
    onBack: () -> Unit,
    title: String? = null,
    actionLabel: String? = null,
    actionIcon: ImageVector? = Icons.Filled.Check,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = primary,
                modifier = Modifier.size(24.dp)
            )
        }
        if (title != null) {
            Text(
                title,
                color = primary,
                fontSize = 18.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        if (onAction != null && actionLabel != null) {
            TextButton(
                onClick = onAction,
                enabled = actionEnabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                if (actionIcon != null) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(actionLabel, fontSize = 14.sp)
            }
        }
    }
}

/** Thin, compact slider (about 28dp tall) with a clear track and thumb. */
@Composable
fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    val onChange by rememberUpdatedState(onValueChange)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Canvas(
        modifier
            .height(28.dp)
            .pointerInput(range) {
                val pad = 10.dp.toPx()
                fun valueAt(x: Float): Float {
                    val f = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                    return range.start + f * (range.endInclusive - range.start)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onChange(valueAt(down.position.x))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        onChange(valueAt(change.position.x))
                        change.consume()
                    }
                }
            }
    ) {
        val pad = 10.dp.toPx()
        val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        val x = pad + frac * (size.width - 2 * pad)
        val cy = size.height / 2f
        drawLine(track, Offset(pad, cy), Offset(size.width - pad, cy), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(primary, Offset(pad, cy), Offset(x, cy), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(primary, radius = 8.dp.toPx(), center = Offset(x, cy))
    }
}

/** Slider with its label on the left, in one compact row. */
@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.width(96.dp)
        )
        CompactSlider(value, onChange, range, Modifier.weight(1f))
    }
}

/** Small colored square showing the current preview background (checkerboard / white / black). */
@Composable
fun BgSwatch(mode: Int, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Column(
        Modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), shape)
        ) {
            when (mode) {
                0 -> CheckerboardBackground(squareSize = 5.dp)
                1 -> CheckerboardBackground(squareSize = 5.dp, lightColor = Color.White, darkColor = Color(0xFFD9D9D9))
                2 -> Box(Modifier.fillMaxSize().background(Color.White))
                else -> Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * Undo / Redo icon button. [count] = number of edits that can still be undone / redone (shown next to the
 * label); long-press runs [onLongClick] (Undo: go all the way back to the original picture).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UndoRedoButton(
    redo: Boolean,
    label: String,
    enabled: Boolean,
    count: Int = 0,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val tint = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Column(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        UndoIcon(mirror = redo, tint = tint, iconSize = 22.dp)
        Text(if (count > 0) "$label ($count)" else label, fontSize = 10.sp, color = tint, maxLines = 1)
    }
}

private const val UNDO_PATH =
    "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 " +
        "3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z"

@Composable
fun UndoIcon(mirror: Boolean, tint: Color, iconSize: Dp = 22.dp) {
    val path = remember { PathParser().parsePathString(UNDO_PATH).toPath() }
    Canvas(Modifier.size(iconSize)) {
        scale(size.width / 24f, pivot = Offset.Zero) {
            if (mirror) {
                scale(-1f, 1f, pivot = Offset(12f, 12f)) { drawPath(path, tint) }
            } else {
                drawPath(path, tint)
            }
        }
    }
}

private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float): Path = Path().apply {
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + i * PI / 5
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Star (Auto-Color tool). */
@Composable
fun StarIcon(tint: Color, iconSize: Dp = 22.dp) {
    val path = remember { starPath(12f, 12.6f, 10f, 4.2f) }
    Canvas(Modifier.size(iconSize)) {
        scale(size.width / 24f, pivot = Offset.Zero) { drawPath(path, tint) }
    }
}

/** Star inside a circle (Magic tool). */
@Composable
fun MagicIcon(tint: Color, iconSize: Dp = 22.dp) {
    val path = remember { starPath(12f, 12.5f, 6f, 2.5f) }
    Canvas(Modifier.size(iconSize)) {
        scale(size.width / 24f, pivot = Offset.Zero) {
            drawCircle(tint, radius = 10f, center = Offset(12f, 12f), style = Stroke(width = 2f))
            drawPath(path, tint)
        }
    }
}

/** Eraser (Manual tool). */
@Composable
fun EraserIcon(tint: Color, iconSize: Dp = 22.dp) {
    Canvas(Modifier.size(iconSize)) {
        scale(size.width / 24f, pivot = Offset.Zero) {
            rotate(-45f, pivot = Offset(12f, 12f)) {
                drawRoundRect(
                    tint, Offset(6f, 2f), Size(12f, 20f),
                    cornerRadius = CornerRadius(2.5f), style = Stroke(width = 2f)
                )
                drawRoundRect(tint, Offset(6f, 12f), Size(12f, 10f), cornerRadius = CornerRadius(2.5f))
            }
        }
    }
}

/** Brush (Repair tool). */
@Composable
fun BrushIcon(tint: Color, iconSize: Dp = 22.dp) {
    Canvas(Modifier.size(iconSize)) {
        scale(size.width / 24f, pivot = Offset.Zero) {
            drawLine(tint, Offset(20f, 4f), Offset(10f, 14f), strokeWidth = 3.4f, cap = StrokeCap.Round)
            drawCircle(tint, radius = 4.6f, center = Offset(8f, 16.2f))
        }
    }
}

/** Person silhouette (AI-Auto tool). */
@Composable
fun PersonIcon(tint: Color, iconSize: Dp = 22.dp) {
    val body = remember {
        Path().apply {
            moveTo(4f, 21f)
            lineTo(4f, 19f)
            cubicTo(4f, 15f, 8f, 14f, 12f, 14f)
            cubicTo(16f, 14f, 20f, 15f, 20f, 19f)
            lineTo(20f, 21f)
            close()
        }
    }
    Canvas(Modifier.size(iconSize)) {
        scale(size.width / 24f, pivot = Offset.Zero) {
            drawCircle(tint, radius = 4.4f, center = Offset(12f, 7.6f))
            drawPath(body, tint)
        }
    }
}

/** True only after [flag] has been true for [delayMs]: keeps a busy overlay from flashing on fast actions. */
@Composable
fun rememberDelayedFlag(flag: Boolean, delayMs: Long = 400L): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(flag) {
        if (flag) {
            delay(delayMs)
            shown = true
        } else {
            shown = false
        }
    }
    return shown
}
