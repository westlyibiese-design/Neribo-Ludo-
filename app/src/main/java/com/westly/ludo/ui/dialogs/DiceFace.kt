package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.ludo.ui.Palette

/** A red die showing [value] (1 to 6) with white pips. [value] 0 shows a dim box with "?". */
@Composable
fun DiceFace(value: Int, modifier: Modifier = Modifier, faceSize: Dp = 56.dp) {
    if (value !in 1..6) {
        Box(
            modifier
                .size(faceSize)
                .background(GoldTheme.ChipFill, RoundedCornerShape(faceSize * 0.22f))
                .border(1.5.dp, GoldTheme.GoldChip, RoundedCornerShape(faceSize * 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            BasicText("?", style = displayStyle(24.sp, GoldTheme.Muted, TextAlign.Center))
        }
        return
    }
    Canvas(modifier.size(faceSize)) {
        val s = this.size.minDimension
        val edge = s * 0.07f
        val r = CornerRadius(s * 0.22f)
        // solid edge underneath, then the gradient face
        drawRoundRect(Palette.DieEdge, Offset(0f, edge), this.size, r)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Palette.DieLight, Palette.DieDark)),
            size = androidx.compose.ui.geometry.Size(s, s - edge),
            cornerRadius = r
        )
        val a = 0.27f
        val b = 0.5f
        val c = 0.73f
        val h = (s - edge)
        val spots: List<Pair<Float, Float>> = when (value) {
            1 -> listOf(b to b)
            2 -> listOf(a to a, c to c)
            3 -> listOf(a to a, b to b, c to c)
            4 -> listOf(a to a, c to a, a to c, c to c)
            5 -> listOf(a to a, c to a, b to b, a to c, c to c)
            else -> listOf(a to a, c to a, a to b, c to b, a to c, c to c)
        }
        for ((x, y) in spots) {
            drawCircle(Color.White, radius = s * 0.075f, center = Offset(s * x, h * y))
        }
    }
}
