package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line icons drawn in code (the project has no icon library). Each one is drawn on a 24 x 24 grid
 * with a 2 wide round stroke, the same look as the icons in ludo-mate-dialogs.html.
 */
enum class LudoIcon {
    Close, Flag, Power, Gear, Exit, Pen, Eye, Redo, Bars, Chevron, Book, Sliders,
    Star, Sound, Mute, Vibrate, VibrateOff
}

private fun Path.line(x1: Float, y1: Float, x2: Float, y2: Float) {
    moveTo(x1, y1)
    lineTo(x2, y2)
}

/** Joined straight lines through the given x,y pairs. */
private fun Path.poly(vararg p: Float, closed: Boolean = false) {
    moveTo(p[0], p[1])
    var i = 2
    while (i + 1 < p.size) {
        lineTo(p[i], p[i + 1])
        i += 2
    }
    if (closed) close()
}

private fun Path.circle(cx: Float, cy: Float, r: Float) {
    addOval(Rect(cx - r, cy - r, cx + r, cy + r))
}

private fun buildIconPath(icon: LudoIcon): Path {
    val p = Path()
    when (icon) {
        LudoIcon.Close -> {
            p.line(6f, 6f, 18f, 18f)
            p.line(18f, 6f, 6f, 18f)
        }
        LudoIcon.Flag -> {
            p.line(5f, 21f, 5f, 4f)
            p.poly(5f, 5f, 16f, 5f, 14f, 9f, 16f, 13f, 5f, 13f)
        }
        LudoIcon.Power -> {
            p.line(12f, 3f, 12f, 11f)
            p.arcTo(Rect(5f, 3.83f, 19f, 17.83f), -141.8f, -256.4f, true)
        }
        LudoIcon.Gear -> {
            p.circle(12f, 12f, 3f)
            p.line(12f, 2f, 12f, 5f)
            p.line(12f, 19f, 12f, 22f)
            p.line(2f, 12f, 5f, 12f)
            p.line(19f, 12f, 22f, 12f)
            p.line(5f, 5f, 7f, 7f)
            p.line(17f, 17f, 19f, 19f)
            p.line(5f, 19f, 7f, 17f)
            p.line(17f, 7f, 19f, 5f)
        }
        LudoIcon.Exit -> {
            p.poly(10f, 4f, 5f, 4f, 5f, 20f, 10f, 20f)
            p.poly(16f, 8f, 20f, 12f, 16f, 16f)
            p.line(9f, 12f, 20f, 12f)
        }
        LudoIcon.Pen -> {
            p.poly(4f, 20f, 5f, 16f, 16f, 5f, 19f, 8f, 8f, 19f, closed = true)
            p.line(14f, 7f, 17f, 10f)
        }
        LudoIcon.Eye -> {
            p.moveTo(2f, 12f)
            p.cubicTo(2f, 12f, 6f, 5f, 12f, 5f)
            p.cubicTo(18f, 5f, 22f, 12f, 22f, 12f)
            p.cubicTo(22f, 12f, 18f, 19f, 12f, 19f)
            p.cubicTo(6f, 19f, 2f, 12f, 2f, 12f)
            p.close()
            p.circle(12f, 12f, 3f)
        }
        LudoIcon.Redo -> {
            p.arcTo(Rect(4f, 4f, 20f, 20f), 180f, -308.9f, true)
            p.poly(4f, 4f, 4f, 8f, 8f, 8f)
        }
        LudoIcon.Bars -> {
            p.line(5f, 20f, 5f, 10f)
            p.line(12f, 20f, 12f, 4f)
            p.line(19f, 20f, 19f, 13f)
        }
        LudoIcon.Chevron -> {
            p.poly(9f, 6f, 15f, 12f, 9f, 18f)
        }
        LudoIcon.Book -> {
            p.poly(5f, 3f, 19f, 3f, 19f, 21f, 5f, 21f, closed = true)
            p.line(9f, 3f, 9f, 21f)
            p.line(12f, 8f, 16f, 8f)
            p.line(12f, 12f, 16f, 12f)
        }
        LudoIcon.Sliders -> {
            p.line(4f, 7f, 7f, 7f)
            p.line(11f, 7f, 20f, 7f)
            p.circle(9f, 7f, 2f)
            p.line(4f, 12f, 13f, 12f)
            p.line(17f, 12f, 20f, 12f)
            p.circle(15f, 12f, 2f)
            p.line(4f, 17f, 7f, 17f)
            p.line(11f, 17f, 20f, 17f)
            p.circle(9f, 17f, 2f)
        }
        LudoIcon.Star -> {
            // five-point star: 10 points, alternating outer and inner radius
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 10f else 4.4f
                val a = Math.toRadians((-90.0 + i * 36.0))
                val x = 12f + r * Math.cos(a).toFloat()
                val y = 12.6f + r * Math.sin(a).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
        LudoIcon.Sound -> {
            p.poly(3f, 9f, 7f, 9f, 12f, 5f, 12f, 19f, 7f, 15f, 3f, 15f, closed = true)
            p.arcTo(Rect(8f, 8f, 16f, 16f), -45f, 90f, true)
            p.arcTo(Rect(5f, 5f, 19f, 19f), -45f, 90f, true)
        }
        LudoIcon.Mute -> {
            p.poly(3f, 9f, 7f, 9f, 12f, 5f, 12f, 19f, 7f, 15f, 3f, 15f, closed = true)
            p.line(16f, 9f, 21f, 15f)
            p.line(21f, 9f, 16f, 15f)
        }
        LudoIcon.Vibrate -> {
            p.poly(8f, 3f, 16f, 3f, 16f, 21f, 8f, 21f, closed = true)
            p.line(4f, 8f, 4f, 16f)
            p.line(20f, 8f, 20f, 16f)
        }
        LudoIcon.VibrateOff -> {
            p.poly(8f, 3f, 16f, 3f, 16f, 21f, 8f, 21f, closed = true)
            p.line(4f, 8f, 4f, 16f)
            p.line(20f, 8f, 20f, 16f)
            p.line(3f, 3f, 21f, 21f)
        }
    }
    return p
}

/** Draws [icon] in [tint]. Icons are decorative: put the contentDescription on the button around them. */
@Composable
fun LudoIconView(
    icon: LudoIcon,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    tint: Color = Color.White
) {
    val path = remember(icon) { buildIconPath(icon) }
    Canvas(modifier.size(iconSize)) {
        val s = this.size.minDimension / 24f
        scale(s, pivot = Offset.Zero) {
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
