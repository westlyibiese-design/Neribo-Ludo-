package com.westly.ludo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer

/** One piece to draw. Positions are in board grid units (row, col), radius in cells. */
class PieceView(
    val swatch: Swatch,
    val row: Float,
    val col: Float,
    val radius: Float,
    val glow: Boolean,
    val lift: Float = 0f,
    val tag: Any? = null,
    /** Board cell id shared by pieces standing on the same cell; -1 for pieces in a house. */
    val group: Int = -1,
    /** Winning seed: drawn with a small gold star. */
    val won: Boolean = false
)

/** Pieces that can all make the chosen move from one spot; the player picks which one moves. */
class PiecePick(val row: Float, val col: Float, val items: List<PieceView>, val capture: Boolean = false)

/**
 * Ludo board (15x15 grid) with two dice in the center.
 * Fits the largest square inside the space it is given and centers itself,
 * so it can be dropped into any layout later.
 *
 * Phase 3: pass [pieces] to draw live pieces (the yard seeds are then drawn from
 * the pieces). Leave it null for the static Phase 2 look.
 */
@Composable
fun LudoBoard(
    modifier: Modifier = Modifier,
    leftDie: Int = 3,
    rightDie: Int = 4,
    pieces: (() -> List<PieceView>)? = null,
    pulse: () -> Float = { 0f },
    rollHint: Boolean = false,
    pick: PiecePick? = null,
    onPick: (Any?) -> Unit = {},
    onBoardTap: ((row: Float, col: Float) -> Unit)? = null,
    /** Yard names in the order green, yellow, red, blue. */
    yardLabels: List<String> = listOf("Player 2", "Player 1", "Player 1", "Player 2"),
    /** Yard pictures in the order green, yellow, red, blue. Empty = the normal colored yards. */
    yardImages: List<ImageBitmap?> = emptyList(),
    /** Tournament: true = that player is knocked out (yard dimmed with OUT). Order green, yellow, red, blue. */
    outYards: List<Boolean> = emptyList()
) {
    val measurer = rememberTextMeasurer()
    val tapHandler = rememberUpdatedState(onBoardTap)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(
            Modifier
                .size(side)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { pos ->
                        val handler = tapHandler.value
                        if (handler != null) {
                            val m = boardMetrics(size.width.toFloat())
                            handler((pos.y - m.oy) / m.cell, (pos.x - m.ox) / m.cell)
                        }
                    })
                }
        ) {
            // The static board sits on its own layer so it is not redrawn while pieces move.
            Canvas(Modifier.fillMaxSize().graphicsLayer { }) {
                drawLudoBoard(leftDie, rightDie, measurer, pieces == null, yardLabels, yardImages, outYards)
            }
            if (pieces != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawPieceLayer(pieces(), pulse(), rollHint)
                }
            }
            if (pick != null) PiecePickPopup(pick, side, onPick)
        }
    }
}

// Thin margins so the 15x15 grid fills almost the whole square it is given (was 0.012 / 0.024).
private const val PAD_FRAC = 0.004f
private const val FRAME_FRAC = 0.016f

internal class Metrics(val ox: Float, val oy: Float, val cell: Float) {
    fun x(col: Float) = ox + col * cell
    fun y(row: Float) = oy + row * cell
    fun center(row: Int, col: Int) = Offset(x(col + 0.5f), y(row + 0.5f))
}

/** Same numbers as drawLudoBoard uses, so taps and pieces line up with the drawing. */
internal fun boardMetrics(s: Float): Metrics {
    val pad = s * PAD_FRAC
    val fs = s - 2f * pad
    val frame = fs * FRAME_FRAC
    val cell = (fs - 2f * frame) / 15f
    return Metrics(pad + frame, pad + frame, cell)
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

private fun DrawScope.drawLudoBoard(
    leftDie: Int,
    rightDie: Int,
    measurer: TextMeasurer,
    seeds: Boolean,
    labels: List<String>,
    images: List<ImageBitmap?>,
    outs: List<Boolean>
) {
    val s = size.minDimension
    val pad = s * PAD_FRAC
    val fs = s - 2f * pad
    val frame = fs * FRAME_FRAC
    val cell = (fs - 2f * frame) / 15f
    val m = Metrics(pad + frame, pad + frame, cell)
    val boardSide = cell * 15f
    val boardCorner = CornerRadius(cell * 0.18f)

    drawFrame(pad, fs)

    drawRoundRect(Palette.Cream, Offset(m.ox, m.oy), Size(boardSide, boardSide), boardCorner)

    drawPathCells(m)
    drawYards(m, measurer, seeds, labels, images, outs)
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
    val edge = cell * 1.38f
    val gap = cell * 0.08f
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

private fun DrawScope.drawYards(
    m: Metrics,
    measurer: TextMeasurer,
    seeds: Boolean,
    labels: List<String>,
    images: List<ImageBitmap?>,
    outs: List<Boolean>
) {
    drawYard(m, 0, 0, Palette.Green, labels[0], measurer, seeds, images.getOrNull(0), outs.getOrNull(0) == true)
    drawYard(m, 0, 9, Palette.Yellow, labels[1], measurer, seeds, images.getOrNull(1), outs.getOrNull(1) == true)
    drawYard(m, 9, 0, Palette.Red, labels[2], measurer, seeds, images.getOrNull(2), outs.getOrNull(2) == true)
    drawYard(m, 9, 9, Palette.Blue, labels[3], measurer, seeds, images.getOrNull(3), outs.getOrNull(3) == true)
}

private fun DrawScope.drawYard(
    m: Metrics,
    row: Int,
    col: Int,
    sw: Swatch,
    label: String,
    measurer: TextMeasurer,
    seeds: Boolean,
    image: ImageBitmap?,
    out: Boolean
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
    // Board theme: a picture fills the whole yard (cut to the rounded corners).
    if (image != null) {
        val clip = Path().apply {
            addRoundRect(RoundRect(tl.x, tl.y, tl.x + yardSize.width, tl.y + yardSize.height, corner))
        }
        clipPath(clip) {
            drawImage(
                image,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                dstSize = IntSize(yardSize.width.roundToInt(), yardSize.height.roundToInt()),
                filterQuality = FilterQuality.High
            )
        }
    }
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
    if (image == null) {
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
    } else {
        // Over a picture: a light tint plus four soft rings where the seeds rest.
        drawRoundRect(Color.Black.copy(alpha = 0.16f), plateTl, plateSize, plateCorner)
        drawRoundRect(Color.White.copy(alpha = 0.25f), plateTl, plateSize, plateCorner, style = Stroke(c * 0.04f))
        for (dx in listOf(2f, 4f)) {
            for (dy in listOf(2f, 4f)) {
                val spot = tl + Offset(c * dx, c * dy)
                drawCircle(Color.Black.copy(alpha = 0.22f), c * 0.56f, spot)
                drawCircle(Color.White.copy(alpha = 0.5f), c * 0.56f, spot, style = Stroke(c * 0.05f))
            }
        }
    }

    // Four seeds resting in the yard
    if (seeds) {
        for (dx in listOf(2f, 4f)) {
            for (dy in listOf(2f, 4f)) {
                drawSeed(tl + Offset(c * dx, c * dy), c * 0.66f, sw)
            }
        }
    }

    // Knocked out (Tournament): the whole yard is dimmed and a big red OUT sits in the middle.
    if (out) {
        drawRoundRect(Color.Black.copy(alpha = 0.66f), tl, yardSize, corner)
        val big = TextStyle(fontSize = (c * 2.1f).toSp(), fontWeight = FontWeight.ExtraBold)
        val outline = measurer.measure(
            text = "OUT",
            style = big.copy(color = Color.Black, drawStyle = Stroke(width = c * 0.22f, join = StrokeJoin.Round)),
            maxLines = 1,
            softWrap = false
        )
        val fill = measurer.measure(
            text = "OUT",
            style = big.copy(color = Color(0xFFFF3B30)),
            maxLines = 1,
            softWrap = false
        )
        val pos = Offset(tl.x + c * 3f - fill.size.width / 2f, tl.y + c * 3.2f - fill.size.height / 2f)
        drawText(textLayoutResult = outline, topLeft = pos)
        drawText(textLayoutResult = fill, topLeft = pos)
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
    if (image != null) {
        // Name tag so the name stays readable over the picture.
        val tagW = layout.size.width + c * 0.7f
        val tagH = layout.size.height + c * 0.2f
        drawRoundRect(
            Color.Black.copy(alpha = 0.5f),
            Offset(tl.x + c * 3f - tagW / 2f, tl.y + c * 0.5f - tagH / 2f),
            Size(tagW, tagH),
            CornerRadius(tagH / 2f)
        )
    }
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

/** Live pieces, drawn above the static board. */
private fun DrawScope.drawPieceLayer(pieces: List<PieceView>, pulse: Float, rollHint: Boolean) {
    val m = boardMetrics(size.minDimension)

    if (rollHint) {
        // Soft pulsing frame around the dice: tap them to roll.
        drawRoundRect(
            Color.White.copy(alpha = 0.35f + 0.5f * pulse),
            Offset(m.x(6.04f), m.y(6.76f)),
            Size(m.cell * 2.92f, m.cell * 1.48f),
            CornerRadius(m.cell * 0.3f),
            style = Stroke(m.cell * 0.08f)
        )
    }

    for (pv in pieces) {
        val r = pv.radius * m.cell
        val c = Offset(m.x(pv.col), m.y(pv.row) - pv.lift * m.cell * 0.22f)
        if (pv.glow) drawGlow(c + Offset(0f, r * 0.1f), r, pulse)
        drawPawn(c, r * (1f + 0.18f * pv.lift), pv.swatch)
        if (pv.won) drawWinStar(c + Offset(0f, r * 0.45f), r * (1f + 0.18f * pv.lift) * 0.36f)
    }
}

/**
 * A Ludo pawn: round head, narrower neck, bell-shaped body, white collar and base rim.
 * [c] is the middle of the board cell, [r] is half the pawn width. The pawn stands about
 * 2.3 r tall, so at normal size it stays inside its own cell.
 */
internal fun DrawScope.drawPawn(c: Offset, r: Float, sw: Swatch) {
    val rimLight = Color(0xFFFFFFFF)
    val rimDark = Color(0xFFD9D5CB)
    val outline = sw.dark.shade(0.7f).copy(alpha = 0.9f)

    // Ground shadow
    drawOval(
        Color.Black.copy(alpha = 0.32f),
        Offset(c.x - r * 1.0f, c.y + r * 0.84f),
        Size(r * 2.0f, r * 0.42f)
    )

    // White base rim
    drawOval(
        Brush.verticalGradient(listOf(rimLight, rimDark), startY = c.y + r * 0.78f, endY = c.y + r * 1.2f),
        Offset(c.x - r * 0.98f, c.y + r * 0.78f),
        Size(r * 1.96f, r * 0.42f)
    )
    drawOval(outline, Offset(c.x - r * 0.98f, c.y + r * 0.78f), Size(r * 1.96f, r * 0.42f), style = Stroke(r * 0.05f))

    // Bell-shaped body
    val body = Path().apply {
        moveTo(c.x - r * 0.84f, c.y + r * 0.98f)
        cubicTo(
            c.x - r * 0.70f, c.y + r * 0.40f,
            c.x - r * 0.42f, c.y + r * 0.05f,
            c.x - r * 0.30f, c.y - r * 0.22f
        )
        lineTo(c.x + r * 0.30f, c.y - r * 0.22f)
        cubicTo(
            c.x + r * 0.42f, c.y + r * 0.05f,
            c.x + r * 0.70f, c.y + r * 0.40f,
            c.x + r * 0.84f, c.y + r * 0.98f
        )
        close()
    }
    drawPath(
        body,
        Brush.horizontalGradient(
            0f to sw.dark,
            0.28f to sw.base,
            0.42f to sw.light,
            0.72f to sw.base,
            1f to sw.dark,
            startX = c.x - r * 0.84f,
            endX = c.x + r * 0.84f
        )
    )
    drawPath(body, outline, style = Stroke(r * 0.05f))

    // Narrow highlight stripe down the body
    drawOval(
        Color.White.copy(alpha = 0.28f),
        Offset(c.x - r * 0.40f, c.y + r * 0.12f),
        Size(r * 0.16f, r * 0.62f)
    )

    // White collar under the head
    drawOval(
        Brush.verticalGradient(listOf(rimLight, rimDark), startY = c.y - r * 0.40f, endY = c.y - r * 0.02f),
        Offset(c.x - r * 0.52f, c.y - r * 0.40f),
        Size(r * 1.04f, r * 0.36f)
    )
    drawOval(outline, Offset(c.x - r * 0.52f, c.y - r * 0.40f), Size(r * 1.04f, r * 0.36f), style = Stroke(r * 0.04f))

    // Round head
    val hc = Offset(c.x, c.y - r * 0.64f)
    val hr = r * 0.54f
    drawCircle(
        Brush.radialGradient(
            listOf(sw.light, sw.base, sw.dark),
            center = hc + Offset(-hr * 0.3f, -hr * 0.35f),
            radius = hr * 1.7f
        ),
        radius = hr,
        center = hc
    )
    drawCircle(outline, radius = hr, center = hc, style = Stroke(r * 0.05f))
    drawCircle(
        Color.White.copy(alpha = 0.55f),
        radius = hr * 0.24f,
        center = hc + Offset(-hr * 0.38f, -hr * 0.42f)
    )
}

/** Small choice bubble that appears over a spot holding several movable pieces. */
@Composable
private fun PiecePickPopup(pick: PiecePick, side: Dp, onPick: (Any?) -> Unit) {
    val density = LocalDensity.current
    val m = boardMetrics(with(density) { side.toPx() })
    val cell = with(density) { m.cell.toDp() }
    val btn = cell * 1.6f
    val gap = cell * 0.2f
    val pad = cell * 0.3f
    val rows = pick.items.chunked(4)
    val perRow = minOf(pick.items.size, 4)
    val w = btn * perRow + gap * (perRow - 1) + pad * 2f
    val labelH = if (pick.capture) cell * 0.7f else 0.dp
    val h = btn * rows.size + gap * (rows.size - 1) + pad * 2f + labelH + (if (pick.capture) gap else 0.dp)

    val cx = with(density) { m.x(pick.col).toDp() }
    val cy = with(density) { m.y(pick.row).toDp() }
    // Sit above the spot; drop below it when there is no room above. Stay inside the board.
    val above = cy - cell * 0.6f - h
    val top = if (above >= 0.dp) above else cy + cell * 0.6f
    val left = (cx - w / 2f).coerceIn(0.dp, maxOf(0.dp, side - w))

    Column(
        Modifier
            .offset(left, top)
            .background(Color(0xF00B2E2F), RoundedCornerShape(cell * 0.6f))
            .border(
                if (pick.capture) 2.dp else 1.dp,
                if (pick.capture) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.5f),
                RoundedCornerShape(cell * 0.6f)
            )
            .padding(pad),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        if (pick.capture) {
            BasicText(
                "Capture:",
                style = TextStyle(
                    color = Color.White,
                    fontSize = with(density) { (cell * 0.5f).toSp() },
                    fontWeight = FontWeight.ExtraBold
                )
            )
        }
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (pv in row) {
                    Canvas(
                        Modifier
                            .size(btn)
                            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(cell * 0.4f))
                            .clickable { onPick(pv.tag) }
                    ) {
                        drawPawn(Offset(this.size.width / 2f, this.size.height * 0.5f), this.size.width * 0.3f, pv.swatch)
                    }
                }
            }
        }
    }
}

/** Small gold star on a winning seed. */
private fun DrawScope.drawWinStar(c: Offset, r: Float) {
    val p = Path()
    for (i in 0 until 10) {
        val ang = (-PI / 2.0 + i * PI / 5.0).toFloat()
        val rad = if (i % 2 == 0) r else r * 0.45f
        val x = c.x + cos(ang) * rad
        val y = c.y + sin(ang) * rad
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, Color(0xFFFFD54A))
    drawPath(p, Color(0xFF6B4A00), style = Stroke(r * 0.18f))
}

private fun DrawScope.drawGlow(c: Offset, r: Float, pulse: Float) {
    // Soft warm halo so the seed stands out from its surroundings.
    val gr = r * (2.1f + 0.35f * pulse)
    drawCircle(
        Brush.radialGradient(
            listOf(
                Color(0xFFFFF3B0).copy(alpha = 0.85f),
                Color(0xFFFFF3B0).copy(alpha = 0.35f),
                Color.Transparent
            ),
            center = c,
            radius = gr
        ),
        radius = gr,
        center = c
    )
    // Dark outline + bright white ring: readable on the white track and on every yard colour.
    val ringR = r * (1.3f + 0.1f * pulse)
    drawCircle(
        Color(0xFF0E1A22).copy(alpha = 0.7f),
        radius = ringR,
        center = c,
        style = Stroke(r * 0.42f)
    )
    drawCircle(
        Color.White.copy(alpha = 0.8f + 0.2f * pulse),
        radius = ringR,
        center = c,
        style = Stroke(r * 0.22f)
    )
}
