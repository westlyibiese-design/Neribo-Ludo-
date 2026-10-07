package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A simple four-colour "G" drawn in code, for the white Google button. Decorative only. */
@Composable
fun GoogleMark(modifier: Modifier = Modifier, markSize: Dp = 22.dp) {
    Canvas(modifier.size(markSize)) {
        val s = this.size.minDimension
        val stroke = s * 0.17f
        val inset = stroke / 2f
        val arcSize = Size(s - stroke, s - stroke)
        val topLeft = Offset(inset, inset)
        val style = Stroke(width = stroke)
        // Angles: 0 = 3 o'clock, going clockwise. The gap at the right is the opening of the G.
        drawArc(Color(0xFFEA4335), 225f, 90f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFFFBBC05), 135f, 90f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFF34A853), 45f, 90f, false, topLeft, arcSize, style = style)
        drawArc(Color(0xFF4285F4), -15f, 60f, false, topLeft, arcSize, style = style)
        // blue bar of the G
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(s * 0.5f, s * 0.5f),
            end = Offset(s - inset, s * 0.5f),
            strokeWidth = stroke
        )
    }
}
