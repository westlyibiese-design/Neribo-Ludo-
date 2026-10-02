package com.westly.ludo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit

// ---------------------------------------------------------------------------
// Everything in this file is static: no click handlers, no state, no animation.
// ---------------------------------------------------------------------------

@Composable
private fun Dp.asSp(): TextUnit = with(LocalDensity.current) { this@asSp.toSp() }

private fun hudTextStyle(size: TextUnit) = TextStyle(
    color = Color.White,
    fontSize = size,
    fontWeight = FontWeight.ExtraBold,
    shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 3f), 5f)
)

@Composable
private fun Pill(text: String, textSize: Dp, modifier: Modifier = Modifier, edge: Color = Palette.PillEdge) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .background(Brush.verticalGradient(listOf(Palette.PillLight, Palette.PillDark)), shape)
            .border(1.dp, edge, shape),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text, style = hudTextStyle(textSize.asSp()), maxLines = 1, softWrap = false)
    }
}

/** Player name pill with the score pill underneath. [unit] is 1% of the screen width. */
@Composable
fun PlayerBadge(name: String, score: Int, unit: Dp, modifier: Modifier = Modifier, accent: Color? = null) {
    Column(modifier.width(unit * 32f), horizontalAlignment = Alignment.CenterHorizontally) {
        val nameScale = if (name.length > 8) 8f / name.length else 1f
        Pill(name, unit * 4.6f * nameScale, Modifier.fillMaxWidth().height(unit * 7.3f), accent ?: Palette.PillEdge)
        Spacer(Modifier.height(unit * 0.6f))
        Pill("Score: $score", unit * 3.9f, Modifier.width(unit * 27f).height(unit * 5.6f))
    }
}

@Composable
fun TurnPill(text: String, unit: Dp, modifier: Modifier = Modifier, textScale: Float = 1f) {
    Pill(text, unit * 5.4f * textScale, modifier.width(unit * 41f).height(unit * 9.3f))
}

internal fun DrawScope.drawGlossyDisc(c: Offset, r: Float, sw: Swatch) {
    drawCircle(Color.Black.copy(alpha = 0.4f), r, c + Offset(0f, r * 0.08f))
    drawCircle(Color(0xFF0E1A22), r, c)
    val ri = r * 0.9f
    drawCircle(
        Brush.verticalGradient(
            listOf(sw.light, sw.base, sw.dark),
            startY = c.y - ri,
            endY = c.y + ri
        ),
        ri,
        c
    )
    drawOval(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.5f), Color.Transparent),
            startY = c.y - ri,
            endY = c.y
        ),
        Offset(c.x - ri * 0.68f, c.y - ri * 0.9f),
        Size(ri * 1.36f, ri * 0.85f)
    )
}

private fun DrawScope.menuIcon(c: Offset, r: Float) {
    val w = r * 0.95f
    val h = r * 0.16f
    val gap = r * 0.30f
    for (i in -1..1) {
        drawRoundRect(
            Color.White,
            Offset(c.x - w / 2f, c.y + i * gap - h / 2f),
            Size(w, h),
            CornerRadius(h / 2f)
        )
    }
}

private fun DrawScope.exitIcon(c: Offset, r: Float) {
    val s = r
    drawRoundRect(
        Color(0xFFFFF3E0),
        Offset(c.x - s / 2f, c.y - s / 2f),
        Size(s, s),
        CornerRadius(s * 0.2f)
    )
    val d = s * 0.24f
    val red = Color(0xFFD62828)
    drawLine(red, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), strokeWidth = s * 0.16f, cap = StrokeCap.Round)
    drawLine(red, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), strokeWidth = s * 0.16f, cap = StrokeCap.Round)
}

@Composable
fun MenuButton(diameter: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(diameter)) {
        val r = this.size.minDimension / 2f * 0.94f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        drawGlossyDisc(c, r, Palette.Blue)
        menuIcon(c, r)
    }
}

@Composable
fun ExitButton(diameter: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(diameter)) {
        val r = this.size.minDimension / 2f * 0.94f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        drawGlossyDisc(c, r, Palette.Orange)
        exitIcon(c, r)
    }
}

@Composable
fun CounterOrb(value: Int, swatch: Swatch, diameter: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawGlossyDisc(center, this.size.minDimension / 2f * 0.94f, swatch)
        }
        BasicText("$value", style = hudTextStyle((diameter * 0.5f).asSp()))
    }
}

/** Stylized pointing hand (index finger pointing down). Height is 1.5x the width. */
@Composable
fun PointingHand(width: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width, width * 1.5f)) { drawPointingHand() }
}

private fun DrawScope.drawPointingHand() {
    val w = this.size.width
    val skin = Brush.verticalGradient(listOf(Color(0xFFFFE066), Color(0xFFF2B41A)))
    val outline = Color(0xFF8A5A00)
    val stroke = Stroke(w * 0.025f)

    fun part(x: Float, y: Float, pw: Float, ph: Float) {
        val tl = Offset(x * w, y * w)
        val sz = Size(pw * w, ph * w)
        val cr = CornerRadius(minOf(pw, ph) * w / 2f)
        drawRoundRect(brush = skin, topLeft = tl, size = sz, cornerRadius = cr)
        drawRoundRect(color = outline, topLeft = tl, size = sz, cornerRadius = cr, style = stroke)
    }

    part(0.36f, 0.55f, 0.30f, 0.85f)   // index finger
    part(0.10f, 0.10f, 0.80f, 0.65f)   // back of the hand
    part(0.06f, 0.42f, 0.34f, 0.15f)   // folded fingers
    part(0.06f, 0.56f, 0.34f, 0.15f)
    part(0.60f, 0.40f, 0.34f, 0.16f)   // thumb

    // Cuff
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF0F0F0F))),
        topLeft = Offset(0.22f * w, 0f),
        size = Size(0.56f * w, 0.14f * w),
        cornerRadius = CornerRadius(0.03f * w)
    )
}
