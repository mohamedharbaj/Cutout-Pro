package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CheckerboardDark
import com.example.ui.theme.CheckerboardLight

@Composable
fun CheckerboardBackground(
    modifier: Modifier = Modifier,
    squareSize: Dp = 12.dp,
    lightColor: Color = CheckerboardLight,
    darkColor: Color = CheckerboardDark
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val squarePx = squareSize.toPx()
        val numCols = (size.width / squarePx).toInt() + 1
        val numRows = (size.height / squarePx).toInt() + 1

        drawRect(color = lightColor)

        for (row in 0 until numRows) {
            for (col in 0 until numCols) {
                if ((row + col) % 2 == 1) {
                    drawRect(
                        color = darkColor,
                        topLeft = Offset(col * squarePx, row * squarePx),
                        size = Size(squarePx, squarePx)
                    )
                }
            }
        }
    }
}
