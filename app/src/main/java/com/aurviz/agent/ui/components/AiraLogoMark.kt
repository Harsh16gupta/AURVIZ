package com.aurviz.agent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * AIRA logo mark: light arch with an Electric Blue dot, matching the
 * adaptive-launcher foreground vector.
 */
@Composable
fun AiraLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    archColor: Color = Color(0xFFF5F7FA),
    dotColor: Color = Color(0xFF1E78FF),
) {
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / 46f
        fun sx(v: Float) = (v - 31f) * scale
        fun sy(v: Float) = (v - 29f) * scale
        val outerRect = Rect(sx(31f), sy(29f), sx(77f), sy(75f))
        val innerRect = Rect(sx(41.5f), sy(39.5f), sx(66.5f), sy(64.5f))
        val path = Path().apply {
            moveTo(sx(31f), sy(75f))
            lineTo(sx(31f), sy(52f))
            arcTo(outerRect, startAngleDegrees = 180f, sweepAngleDegrees = 180f, forceMoveTo = false)
            lineTo(sx(77f), sy(75f))
            lineTo(sx(66.5f), sy(75f))
            lineTo(sx(66.5f), sy(52f))
            arcTo(innerRect, startAngleDegrees = 0f, sweepAngleDegrees = -180f, forceMoveTo = false)
            lineTo(sx(41.5f), sy(75f))
            close()
        }
        drawPath(path = path, color = archColor)
        drawCircle(color = dotColor, radius = 4.8f * scale, center = Offset(sx(54f), sy(67f)))
    }
}
