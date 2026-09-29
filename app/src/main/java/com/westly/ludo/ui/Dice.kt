package com.westly.ludo.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

private val PipLayouts: Map<Int, List<Pair<Float, Float>>> = mapOf(
    1 to listOf(0.5f to 0.5f),
    2 to listOf(0.29f to 0.29f, 0.71f to 0.71f),
    3 to listOf(0.29f to 0.29f, 0.5f to 0.5f, 0.71f to 0.71f),
    4 to listOf(0.29f to 0.29f, 0.71f to 0.29f, 0.29f to 0.71f, 0.71f to 0.71f),
    5 to listOf(0.29f to 0.29f, 0.71f to 0.29f, 0.5f to 0.5f, 0.29f to 0.71f, 0.71f to 0.71f),
    6 to listOf(
        0.29f to 0.24f, 0.29f to 0.5f, 0.29f to 0.76f,
        0.71f to 0.24f, 0.71f to 0.5f, 0.71f to 0.76f
    )
)

/** Draws one static red die. [edge] is the side length in pixels. */
internal fun DrawScope.drawDie(topLeft: Offset, edge: Float, value: Int) {
    val body = Size(edge, edge)
    val corner = CornerRadius(edge * 0.2f)

    // Soft drop shadow
    drawRoundRect(
        Color.Black.copy(alpha = 0.38f),
        topLeft + Offset(edge * 0.02f, edge * 0.07f),
        body,
        corner
    )

    // Body
    drawRoundRect(
        Brush.verticalGradient(
            listOf(Palette.DieLight, Palette.DieDark),
            startY = topLeft.y,
            endY = topLeft.y + edge
        ),
        topLeft,
        body,
        corner
    )

    // Bevel: light from top-left, shade to bottom-right
    val bevel = edge * 0.035f
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.5f), Color.Transparent, Color.Black.copy(alpha = 0.28f)),
            start = topLeft,
            end = topLeft + Offset(edge, edge)
        ),
        topLeft = topLeft + Offset(bevel / 2f, bevel / 2f),
        size = Size(edge - bevel, edge - bevel),
        cornerRadius = CornerRadius(edge * 0.2f - bevel / 2f),
        style = Stroke(bevel)
    )

    // Outline
    drawRoundRect(Palette.DieEdge, topLeft, body, corner, style = Stroke(edge * 0.022f))

    // Pips
    val pipR = edge * 0.085f
    PipLayouts.getValue(value.coerceIn(1, 6)).forEach { (fx, fy) ->
        val c = topLeft + Offset(edge * fx, edge * fy)
        drawCircle(Color.Black.copy(alpha = 0.28f), radius = pipR * 1.12f, center = c)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White, Color(0xFFE6E1D8)),
                center = c + Offset(-pipR * 0.2f, -pipR * 0.25f),
                radius = pipR * 1.3f
            ),
            radius = pipR,
            center = c + Offset(0f, -pipR * 0.04f)
        )
    }
}
