package com.westly.ludo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

// Native Compose version of the animated logo (professional-2.html): a blue tile, an orange "C"
// ring that draws itself, and a white dice that spins in and shows three dots. It is drawn in the
// original 1024 x 1024 SVG space and scaled to the screen. The name "LudoMate" and
// "(c) NERIBO GROUP" appear under the logo.

private val BgInner = Color(0xFF16203F)
private val BgMid = Color(0xFF0B1226)
private val BgOuter = Color(0xFF070B18)
private val TileA = Color(0xFF1A3399)
private val TileB = Color(0xFF2A7EE6)
private val OrangeA = Color(0xFFE08A22)
private val OrangeB = Color(0xFFB45A1E)
private val DotRed = Color(0xFFD95D30)
private val DotAmber = Color(0xFFF09F28)
private val TextMain = Color(0xFFF1F0EC)
private val TextTag = Color(0xFFC3C3B8)

private val EaseSpring = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
private val EaseDraw = CubicBezierEasing(0.65f, 0f, 0.25f, 1f)
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EaseRise = CubicBezierEasing(0.16f, 0.8f, 0.24f, 1f)
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

private val TilePath = Path().apply { addRoundRect(RoundRect(0f, 0f, 1024f, 1024f, 235f, 235f)) }

private val TwoPi = (2.0 * PI).toFloat()
private val TotalSeconds = 6.0f

private fun seg(t: Float, start: Float, dur: Float): Float = ((t - start) / dur).coerceIn(0f, 1f)

/** Plays the logo reveal full screen, then calls [onFinished] once. */
@Composable
fun NeriboIntro(onFinished: () -> Unit) {
    val t = remember { Animatable(0f) }
    val done by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        t.animateTo(TotalSeconds, tween((TotalSeconds * 1000).toInt(), easing = LinearEasing))
        done()
    }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // radial-gradient(circle at 50% 40%, #16203f, #0b1226 60%, #070b18)
                val c = Offset(size.width * 0.5f, size.height * 0.4f)
                val r = hypot(max(c.x, size.width - c.x), max(c.y, size.height - c.y))
                drawRect(
                    Brush.radialGradient(
                        0f to BgInner, 0.6f to BgMid, 1f to BgOuter,
                        center = c, radius = r
                    )
                )
            }
    ) {
        val tile = min(maxWidth * 0.62f, maxHeight * 0.38f)
        val s = tile.value / 260f
        fun cssSp(v: Float) = with(density) { (v * s).dp.toSp() }

        val wordStyle = TextStyle(
            fontSize = cssSp(56f),
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.01f).em
        )
        val wordW = remember(wordStyle) { measurer.measure("LudoMate", wordStyle).size.width.toFloat() }
        val shineP by remember { derivedStateOf { EaseInOut.transform(seg(t.value, 3.9f, 0.9f)) } }
        val risePx = with(density) { (16f * s).dp.toPx() }
        val floatPx = with(density) { (8f * tile.value / 520f).dp.toPx() }

        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Canvas(
                Modifier
                    .size(tile)
                    .graphicsLayer {
                        // gentle float once the logo has finished building
                        val u = ((t.value - 3.2f) / 6f).coerceAtLeast(0f)
                        translationY = -floatPx * (1f - cos(TwoPi * u)) / 2f
                    }
            ) {
                val time = t.value
                val k = size.width / 1024f
                withTransform({ scale(k, k, pivot = Offset.Zero) }) {
                    // ---- tile (springs in) with a soft blue glow under it ----
                    val te = EaseSpring.transform(seg(time, 0.1f, 0.9f))
                    val ta = te.coerceIn(0f, 1f)
                    val ts = 0.6f + 0.4f * te
                    if (ta > 0f) {
                        withTransform({ scale(ts, ts, pivot = Offset(512f, 512f)) }) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0f to Color(0x591446C8), 1f to Color(0x001446C8),
                                    center = Offset(512f, 560f), radius = 640f
                                ),
                                radius = 640f,
                                center = Offset(512f, 560f),
                                alpha = ta
                            )
                            drawRoundRect(
                                brush = Brush.linearGradient(
                                    listOf(TileA, TileB),
                                    start = Offset(0f, 0f), end = Offset(1024f, 1024f)
                                ),
                                size = Size(1024f, 1024f),
                                cornerRadius = CornerRadius(235f),
                                alpha = ta
                            )
                            // light sweep across the tile (skewed -18 degrees)
                            val sp = seg(time, 3.0f, 1.8f)
                            if (time >= 3.0f && sp < 1f) {
                                val tx = -500f + 1800f * EaseInOut.transform(sp)
                                val shear = 0.3249f
                                val band = Path().apply {
                                    moveTo(tx + shear * 100f, -100f)
                                    lineTo(tx + 220f + shear * 100f, -100f)
                                    lineTo(tx + 220f - shear * 1150f, 1150f)
                                    lineTo(tx - shear * 1150f, 1150f)
                                    close()
                                }
                                clipPath(TilePath) {
                                    drawPath(
                                        band,
                                        Brush.horizontalGradient(
                                            0f to Color(0x00FFFFFF),
                                            0.5f to Color(0x47FFFFFF),
                                            1f to Color(0x00FFFFFF),
                                            startX = tx - 166f, endX = tx + 54f
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // ---- orange C ring draws itself ----
                    val f = EaseDraw.transform(seg(time, 0.7f, 1.5f))
                    if (f > 0.004f) {
                        drawArc(
                            brush = Brush.linearGradient(
                                listOf(OrangeA, OrangeB),
                                start = Offset(226.4f, 294f), end = Offset(573f, 732f)
                            ),
                            startAngle = -54.4f,
                            sweepAngle = -251.2f * f,
                            useCenter = false,
                            topLeft = Offset(226.4f, 294f),
                            size = Size(438f, 438f),
                            style = Stroke(72f, cap = StrokeCap.Round)
                        )
                    }

                    // ---- dice spins in, dots pop, then it rocks gently ----
                    val de = EaseSpring.transform(seg(time, 1.6f, 1.0f))
                    if (de > 0.002f) {
                        val da = de.coerceIn(0f, 1f)
                        val rock = if (time > 3.2f) 3f * sin(TwoPi * (time - 3.2f) / 5f) else 0f
                        val pivot = Offset(737f, 512f)
                        withTransform({
                            scale(de, de, pivot = pivot)
                            rotate(-200f * (1f - de), pivot = pivot)
                            rotate(rock, pivot = pivot)
                        }) {
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(594f, 369f),
                                size = Size(286f, 286f),
                                cornerRadius = CornerRadius(62f),
                                alpha = da
                            )
                            fun dot(cx: Float, cy: Float, r: Float, color: Color, delay: Float, extra: Float = 1f) {
                                val p = EaseSpring.transform(seg(time, delay, 0.55f))
                                val radius = r * p * extra
                                if (radius > 0f) drawCircle(color, radius, Offset(cx, cy))
                            }
                            dot(666f, 440f, 34f, DotRed, 2.35f)
                            val pulse = if (time > 3.4f) {
                                1f + 0.14f * (1f - cos(TwoPi * (time - 3.4f) / 2.8f)) / 2f
                            } else {
                                1f
                            }
                            dot(737f, 512f, 39f, DotAmber, 2.5f, pulse)
                            dot(809f, 584f, 34f, DotRed, 2.65f)
                        }
                    }
                }
            }

            Spacer(Modifier.height((30f * s).dp))

            // Wordmark: rises and fades in, then a soft light passes through the letters.
            val pos = 2.3f - 2.9f * shineP
            val offsetX = -1.8f * wordW * pos
            val shineBrush = Brush.linearGradient(
                0.38f to TextMain, 0.5f to Color.White, 0.62f to TextMain,
                start = Offset(offsetX, 0f),
                end = Offset(offsetX + 2.8f * wordW, 0f)
            )
            BasicText(
                "LudoMate",
                style = wordStyle.copy(brush = shineBrush),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.graphicsLayer {
                    val e = EaseRise.transform(seg(t.value, 2.7f, 0.7f))
                    alpha = e.coerceIn(0f, 1f)
                    translationY = risePx * (1f - e)
                }
            )

            Spacer(Modifier.height((10f * s).dp))

            BasicText(
                "\u00A9 NERIBO GROUP",
                style = TextStyle(color = TextTag, fontSize = cssSp(17f), letterSpacing = 0.16f.em),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.graphicsLayer {
                    alpha = EaseOut.transform(seg(t.value, 3.3f, 0.7f))
                }
            )
        }
    }
}
