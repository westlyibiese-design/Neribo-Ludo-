package com.westly.ludo.ui.dialogs

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which of the 3 x 3 grid cells (0..8, row by row) carry a pip for each die value. */
private val PipCells = mapOf(
    1 to listOf(4),
    2 to listOf(0, 8),
    3 to listOf(0, 4, 8),
    4 to listOf(0, 2, 6, 8),
    5 to listOf(0, 2, 4, 6, 8),
    6 to listOf(0, 2, 3, 5, 6, 8)
)

/**
 * The cream die of the tie-break page: 60dp face, 16dp corners, white-to-cream gradient, a solid
 * 4dp edge underneath and dark pips. [value] 0 shows the dim empty box with "?".
 * [shaking] wobbles it left and right while it is rolling (still when animations are off).
 */
@Composable
fun DiceFace(
    value: Int,
    modifier: Modifier = Modifier,
    faceSize: Dp = 60.dp,
    shaking: Boolean = false
) {
    val motion = LocalMotionEnabled.current
    val shake = if (shaking && motion) {
        rememberInfiniteTransition(label = "dieShake").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(180, easing = LinearEasing), RepeatMode.Restart),
            label = "dieShakeT"
        )
    } else {
        null
    }
    val wobble = Modifier.graphicsLayer {
        val t = shake?.value ?: 0f
        // 0% rest, 25% left and up, 50% rest, 75% right and down, 100% rest
        val k = when {
            t < 0.25f -> t / 0.25f
            t < 0.5f -> 1f - (t - 0.25f) / 0.25f
            t < 0.75f -> (t - 0.5f) / 0.25f
            else -> 1f - (t - 0.75f) / 0.25f
        }
        val side = if (t < 0.5f) -1f else 1f
        rotationZ = side * 9f * k
        translationY = side * 3.dp.toPx() * k
    }

    if (value !in 1..6) {
        val shape = RoundedCornerShape(faceSize * 16f / 60f)
        Box(
            modifier
                .size(faceSize)
                .then(wobble)
                .background(Color(0x12FFFFFF), shape)
                .border(1.5.dp, GoldTheme.Line, shape),
            contentAlignment = Alignment.Center
        ) {
            BasicText("?", style = displayStyle(22.sp, GoldTheme.Muted, TextAlign.Center))
        }
        return
    }

    Canvas(
        modifier
            .size(faceSize)
            .then(wobble)
            .semantics { contentDescription = "Rolled $value" }
    ) {
        val s = this.size.minDimension
        val corner = CornerRadius(s * 16f / 60f)
        // solid edge underneath (box-shadow 0 4px 0 #b9b19b), then the face
        drawRoundRect(Color(0xFFB9B19B), Offset(0f, s * 4f / 60f), this.size, corner)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFE9E4D6))),
            size = this.size,
            cornerRadius = corner
        )
        // 3 x 3 grid with 9dp padding on a 60dp die; each pip is 11dp wide
        val pad = s * 9f / 60f
        val cell = (s - pad * 2f) / 3f
        val radius = s * 5.5f / 60f
        for (index in PipCells.getValue(value)) {
            val col = index % 3
            val row = index / 3
            drawCircle(
                color = Color(0xFF1A2B2B),
                radius = radius,
                center = Offset(pad + cell * (col + 0.5f), pad + cell * (row + 0.5f))
            )
        }
    }
}
