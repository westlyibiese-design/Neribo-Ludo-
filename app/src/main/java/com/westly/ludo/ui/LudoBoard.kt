package com.westly.ludo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * Static Ludo board (15x15 grid) with two dice in the center.
 * Fits the largest square inside the space it is given and centers itself,
 * so it can be dropped into any layout later.
 */
@Composable
fun LudoBoard(
    modifier: Modifier = Modifier,
    leftDie: Int = 3,
    rightDie: Int = 4
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Canvas(Modifier.size(side)) { drawLudoBoard(leftDie, rightDie, measurer) }
    }
}

private class Metrics(val ox: Float, val oy: Float, val cell: Float) {
    fun x(col: Float) = ox + col * cell
    fun y(row: Float) = oy + row * cell
    fun center(row: Int, col: Int) = Offset(x(col + 0.5f), y(row + 0.5f))
}

// Colored path cells: five home-column cells per arm plus one start cell per color.
private val coloredCells: Map<Pair<Int, Int>, Swatch> = buildMap<Pair<Int, Int>, Swatch> {
    for (i in 1..5) {
        put(i to 7, Palette.Yellow)   // top arm
        put(7 to i, Palette.Green)    // left arm
    }
    for (i in 9..13) {
        put(i to 7, Palette.Red)      // bottom arm
        put(7 to i, Palette.Blue)     // right arm
    }
    put(1 to 8, Palette.Yellow)
    put(6 to 1, Palette.Green)
    put(13 to 6, Palette.Red)
    put(8 to 13, Palette.Blue)
}

private fun DrawScope.drawLudoBoard(leftDie: Int, rightDie: Int, measurer: TextMeasurer) {
    val s = size.minDimension
    val pad = s * 0.012f
    val fs = s - 2f * pad
    val frame = fs * 0.024f
    val cell = (fs - 2f * frame) / 15f
    val m = Metrics(pad + frame, pad + frame, cell)
    val boardSide = cell * 15f
    val boardCorner = CornerRadius(cell * 0.18f)

    drawFrame(pad, fs)

    drawRoundRect(Palette.Cream, Offset(m.ox, m.oy), Size(boardSide, boardSide), boardCorner)

    drawPathCells(m)
    drawYards(m, measurer)
    drawCenter(m)
    drawPathArrows(m)

    drawRoundRect(
        Color.Black.copy(alpha = 0.35f),
        Offset(m.ox, m.oy),
        Size(boardSide, boardSide),
        boardCorner,
        style = Stroke(fs * 0.003f)
    )

    // Two dice, centered in the middle 3x3 area
    val mid = m.center(7, 7)
    val edge = cell * 1.22f
    val gap = cell * 0.14f
    drawDie(Offset(mid.x - gap / 2f - edge, mid.y - edge / 2f), edge, leftDie)
    drawDie(Offset(mid.x + gap / 2f, mid.y - edge / 2f), edge, rightDie)
}

private fun DrawScope.drawFrame(pad: Float, fs: Float) {
    val corner = CornerRadius(fs * 0.032f)
    drawRoundRect(
        Color.Black.copy(alpha = 0.35f),
        Offset(pad, pad + fs * 0.008f),
        Size(fs, fs),
        corner
    )
    drawRoundRect(
        Brush.verticalGradient(
            listOf(Palette.FrameLight, Palette.FrameDark),
            startY = pad,
            endY = pad + fs
        ),
        Offset(pad, pad),
        Size(fs, fs),
        corner
    )
    val w = fs * 0.004f
    drawRoundRect(
        Color.White.copy(alpha = 0.25f),
        Offset(pad + w / 2f, pad + w / 2f),
        Size(fs - w, fs - w),
        corner,
        style = Stroke(w)
    )
}

private fun DrawScope.drawPathCells(m: Metrics) {
    val line = maxOf(1f, m.cell * 0.028f)
    val cellSize = Size(m.cell, m.cell)
    for (r in 0..14) {
        for (c in 0..14) {
            val inCenter = r in 6..8 && c in 6..8
            val inCross = r in 6..8 || c in 6..8
            if (!inCross || inCenter) continue

            val tl = Offset(m.x(c.toFloat()), m.y(r.toFloat()))
            val sw = coloredCells[r to c]
            if (sw == null) {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Palette.CreamLight, Palette.Cream),
                        startY = tl.y,
                        endY = tl.y + m.cell
                    ),
                    tl,
                    cellSize
                )
                drawRect(Palette.GridLine, tl, cellSize, style = Stroke(line))
            } else {
                drawRect(
                    Brush.verticalGradient(
                        listOf(sw.light, sw.base),
                        startY = tl.y,
                        endY = tl.y + m.cell
                    ),
                    tl,
                    cellSize
                )
                drawRect(sw.dark.copy(alpha = 0.6f), tl, cellSize, style = Stroke(line))
            }
        }
    }
}

private fun DrawScope.drawYards(m: Metrics, measurer: TextMeasurer) {
    drawYard(m, 0, 0, Palette.Green, "Player 2", measurer)
    drawYard(m, 0, 9, Palette.Yellow, "Player 1", measurer)
    drawYard(m, 9, 0, Palette.Red, "Player 1", measurer)
    drawYard(m, 9, 9, Palette.Blue, "Player 2", measurer)
}

private fun DrawScope.drawYard(
    m: Metrics,
    row: Int,
    col: Int,
    sw: Swatch,
    label: String,
    measurer: TextMeasurer
) {
    val c = m.cell
    val tl = Offset(m.x(col.toFloat()), m.y(row.toFloat()))
    val yardSize = Size(c * 6f, c * 6f)
    val corner = CornerRadius(c * 0.18f)

    drawRoundRect(
        Brush.verticalGradient(
            listOf(sw.light, sw.base),
            startY = tl.y,
            endY = tl.y + yardSize.height
        ),
        tl,
        yardSize,
        corner
    )
    val edgeW = c * 0.05f
    drawRoundRect(
        sw.dark,
        tl + Offset(edgeW / 2f, edgeW / 2f),
        Size(yardSize.width - edgeW, yardSize.height - edgeW),
        corner,
        style = Stroke(edgeW)
    )

    // Recessed plate
    val plateTl = tl + Offset(c, c)
    val plateSize = Size(c * 4f, c * 4f)
    val plateCorner = CornerRadius(c * 0.4f)
    drawRoundRect(
        Brush.verticalGradient(
            listOf(sw.dark.shade(0.8f), sw.dark),
            startY = plateTl.y,
            endY = plateTl.y + plateSize.height
        ),
        plateTl,
        plateSize,
        plateCorner
    )
    drawRoundRect(Color.Black.copy(alpha = 0.22f), plateTl, plateSize, plateCorner, style = Stroke(c * 0.05f))

    // Four seeds resting in the yard
    for (dx in listOf(2f, 4f)) {
        for (dy in listOf(2f, 4f)) {
            drawSeed(tl + Offset(c * dx, c * dy), c * 0.66f, sw)
        }
    }

    // Player name in the top band of the yard
    val layout = measurer.measure(
        text = label,
        style = TextStyle(
            color = Color.White,
            fontSize = (c * 0.58f).toSp(),
            fontWeight = FontWeight.ExtraBold,
            shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, c * 0.03f), c * 0.06f)
        ),
        maxLines = 1,
        softWrap = false
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            tl.x + c * 3f - layout.size.width / 2f,
            tl.y + c * 0.5f - layout.size.height / 2f
        )
    )
}

private fun DrawScope.drawSeed(center: Offset, r: Float, sw: Swatch) {
    drawCircle(Color.Black.copy(alpha = 0.35f), radius = r, center = center + Offset(0f, r * 0.14f))
    drawCircle(
        Brush.verticalGradient(
            listOf(Color.White, Color(0xFFD9D5CB)),
            startY = center.y - r,
            endY = center.y + r
        ),
        radius = r,
        center = center
    )
    val ir = r * 0.8f
    drawCircle(
        Brush.radialGradient(
            listOf(sw.light, sw.base, sw.dark),
            center = center + Offset(-ir * 0.25f, -ir * 0.3f),
            radius = ir * 1.5f
        ),
        radius = ir,
        center = center
    )
    drawCircle(
        Color.White.copy(alpha = 0.28f),
        radius = ir * 0.28f,
        center = center + Offset(-ir * 0.32f, -ir * 0.38f)
    )
}

private fun octagon(x: Float, y: Float, s: Float, cut: Float) = Path().apply {
    moveTo(x + cut, y)
    lineTo(x + s - cut, y)
    lineTo(x + s, y + cut)
    lineTo(x + s, y + s - cut)
    lineTo(x + s - cut, y + s)
    lineTo(x + cut, y + s)
    lineTo(x, y + s - cut)
    lineTo(x, y + cut)
    close()
}

private fun DrawScope.drawCenter(m: Metrics) {
    val c = m.cell
    val x0 = m.x(6f)
    val y0 = m.y(6f)
    val x1 = m.x(9f)
    val y1 = m.y(9f)
    val mid = Offset((x0 + x1) / 2f, (y0 + y1) / 2f)

    fun triangle(a: Offset, b: Offset, sw: Swatch) {
        val p = Path().apply {
            moveTo(mid.x, mid.y)
            lineTo(a.x, a.y)
            lineTo(b.x, b.y)
            close()
        }
        drawPath(p, sw.base)
    }
    triangle(Offset(x0, y0), Offset(x1, y0), Palette.Yellow)
    triangle(Offset(x1, y0), Offset(x1, y1), Palette.Blue)
    triangle(Offset(x1, y1), Offset(x0, y1), Palette.Red)
    triangle(Offset(x0, y1), Offset(x0, y0), Palette.Green)

    drawRect(
        Color.Black.copy(alpha = 0.25f),
        Offset(x0, y0),
        Size(c * 3f, c * 3f),
        style = Stroke(maxOf(1f, c * 0.028f))
    )

    // Dark plate that holds the dice
    val cut = c * 0.8f
    val plate = octagon(x0, y0, c * 3f, cut)
    drawPath(
        plate,
        Brush.verticalGradient(listOf(Palette.CenterLight, Palette.CenterDark), startY = y0, endY = y1)
    )
    drawPath(plate, Color.White.copy(alpha = 0.35f), style = Stroke(c * 0.035f))

    val inset = c * 0.14f
    val inner = octagon(x0 + inset, y0 + inset, c * 3f - 2f * inset, cut - inset * 0.586f)
    drawPath(inner, Color.Black.copy(alpha = 0.22f), style = Stroke(c * 0.03f))
}

private fun DrawScope.drawPathArrows(m: Metrics) {
    val c = m.cell
    // Entry arrows on the first home-column cell of each arm, all pointing to the center
    drawArrow(m.center(13, 7), Offset(0f, -1f), c, both = false)  // red, up
    drawArrow(m.center(1, 7), Offset(0f, 1f), c, both = false)    // yellow, down
    drawArrow(m.center(7, 1), Offset(1f, 0f), c, both = false)    // green, right
    drawArrow(m.center(7, 13), Offset(-1f, 0f), c, both = false)  // blue, left
    // Double-headed markers on the outer lanes
    drawArrow(m.center(10, 6), Offset(0f, 1f), c, both = true)
    drawArrow(m.center(4, 8), Offset(0f, 1f), c, both = true)
    drawArrow(m.center(6, 4), Offset(1f, 0f), c, both = true)
    drawArrow(m.center(8, 10), Offset(1f, 0f), c, both = true)
}

private fun DrawScope.drawArrow(center: Offset, dir: Offset, c: Float, both: Boolean) {
    val perp = Offset(-dir.y, dir.x)
    val headLen = c * 0.26f
    val headHalf = c * 0.17f
    val shaftW = c * 0.08f

    fun head(tip: Offset, pointing: Offset): Offset {
        val base = tip - pointing * headLen
        val p = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(base.x + perp.x * headHalf, base.y + perp.y * headHalf)
            lineTo(base.x - perp.x * headHalf, base.y - perp.y * headHalf)
            close()
        }
        drawPath(p, Palette.Arrow)
        return base
    }

    if (both) {
        val baseA = head(center + dir * (c * 0.36f), dir)
        val baseB = head(center - dir * (c * 0.36f), -dir)
        drawLine(Palette.Arrow, baseA, baseB, strokeWidth = shaftW)
    } else {
        val tail = center - dir * (c * 0.30f)
        val base = head(center + dir * (c * 0.32f), dir)
        drawLine(Palette.Arrow, tail, base, strokeWidth = shaftW)
    }
}
