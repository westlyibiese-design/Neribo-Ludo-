package com.westly.ludo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min

// Native Compose version of the Neribo Artificial Intelligence logo reveal
// (neribo-logo-reveal.html). Same shapes, colors, timings and easings, drawn in the
// original SVG coordinate space (viewBox 270 170 660 560) and scaled to the screen.

private val BgColor = Color(0xFF141414)
private val RingA = Color(0xFFE5943A)
private val RingB = Color(0xFFCD7A2E)
private val RingC = Color(0xFF9C431E)
private val TriMid = Color(0xFFEFA029)
private val TriSide = Color(0xFFD95C33)
private val TextMain = Color(0xFFF1F0EC)
private val TextTag = Color(0xFFC3C3B8)

private val EaseRing = CubicBezierEasing(0.65f, 0.05f, 0.36f, 1f)
private val EaseMid = CubicBezierEasing(0.22f, 0.7f, 0.32f, 1f)
private val EaseLock = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.05f)
private val EaseRise = CubicBezierEasing(0.16f, 0.8f, 0.24f, 1f)
private val EaseShine = CubicBezierEasing(0.33f, 0f, 0.2f, 1f)
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

private fun poly(vararg p: Pair<Float, Float>): Path = Path().apply {
    moveTo(p[0].first, p[0].second)
    for (i in 1 until p.size) lineTo(p[i].first, p[i].second)
    close()
}

private val TopTri = poly(655f to 296f, 655f to 396f, 822f to 346f)
private val BottomTri = poly(655f to 497f, 655f to 598f, 822f to 548f)
private val MidTri = poly(655f to 373f, 655f to 520f, 898f to 447f)

private fun seg(t: Float, start: Float, dur: Float): Float = ((t - start) / dur).coerceIn(0f, 1f)

/** Plays the logo reveal full screen, then calls [onFinished] once. */
@Composable
fun NeriboIntro(onFinished: () -> Unit) {
    val t = remember { Animatable(0f) }
    val done by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        // The original animation ends at 4.95 s; hold the finished logo briefly.
        t.animateTo(5.6f, tween(5600, easing = LinearEasing))
        done()
    }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(Modifier.fillMaxSize().background(BgColor)) {
        val iconW = min(maxWidth * 0.54f, maxHeight * 0.4f)
        val iconH = iconW * (560f / 660f)
        // One "css pixel" of the original design, in dp (the original icon is 260 px wide).
        val s = iconW.value / 260f
        fun cssSp(v: Float) = with(density) { (v * s).dp.toSp() }

        val wordStyle = TextStyle(
            fontSize = cssSp(56f),
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.01f).em
        )
        val wordW = remember(wordStyle) { measurer.measure("LudoMate", wordStyle).size.width.toFloat() }
        val shineP by remember { derivedStateOf { EaseInOut.transform(seg(t.value, 4.05f, 0.9f)) } }
        val risePx = with(density) { (16f * s).dp.toPx() }

        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Canvas(
                Modifier
                    .size(iconW, iconH)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            ) {
                val time = t.value
                val k = size.width / 660f
                withTransform({
                    scale(k, k, pivot = Offset.Zero)
                    translate(-270f, -170f)
                }) {
                    // Ring: draws itself clockwise-opposite from the top right, around the left.
                    val f = EaseRing.transform(seg(time, 0.25f, 1.5f))
                    if (f > 0f) {
                        val ringBrush = Brush.linearGradient(
                            0f to RingA, 0.55f to RingB, 1f to RingC,
                            start = Offset(397.6f, 276.9f),
                            end = Offset(572f, 617.1f)
                        )
                        drawArc(
                            brush = ringBrush,
                            startAngle = -61f,
                            sweepAngle = -238f * f,
                            useCenter = false,
                            topLeft = Offset(337.5f, 244.5f),
                            size = Size(405f, 405f),
                            style = Stroke(67f, cap = StrokeCap.Round)
                        )
                    }

                    // Two side triangles lock in (slight overshoot).
                    val eTop = EaseLock.transform(seg(time, 2.05f, 0.55f))
                    val eBottom = EaseLock.transform(seg(time, 2.18f, 0.55f))
                    if (eTop > 0f) {
                        withTransform({ translate(26f * (1f - eTop), 0f) }) {
                            drawPath(TopTri, TriSide, alpha = eTop.coerceIn(0f, 1f))
                        }
                    }
                    if (eBottom > 0f) {
                        withTransform({ translate(26f * (1f - eBottom), 0f) }) {
                            drawPath(BottomTri, TriSide, alpha = eBottom.coerceIn(0f, 1f))
                        }
                    }

                    // Middle triangle expands from its left edge.
                    val pm = seg(time, 1.5f, 0.7f)
                    if (pm > 0f) {
                        val sx = EaseMid.transform(pm)
                        val a = EaseMid.transform((pm / 0.4f).coerceIn(0f, 1f))
                        withTransform({ scale(sx, 1f, pivot = Offset(655f, 446.5f)) }) {
                            drawPath(MidTri, TriMid, alpha = a.coerceIn(0f, 1f))
                        }
                    }

                    // White shine sweeps across the finished icon only (clipped to its shapes).
                    val sp = seg(time, 4.05f, 0.9f)
                    if (time >= 4.05f && sp < 1f) {
                        val x = 970f * EaseShine.transform(sp)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.White.copy(alpha = 0f),
                                0.5f to Color.White.copy(alpha = 0.95f),
                                1f to Color.White.copy(alpha = 0f),
                                startX = x,
                                endX = x + 220f
                            ),
                            topLeft = Offset(x, 170f),
                            size = Size(220f, 560f),
                            blendMode = BlendMode.SrcAtop
                        )
                    }
                }
            }

            Spacer(Modifier.height((22f * s).dp))

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
                    val e = EaseRise.transform(seg(t.value, 2.75f, 0.6f))
                    alpha = e.coerceIn(0f, 1f)
                    translationY = risePx * (1f - e)
                }
            )

            Spacer(Modifier.height((8f * s).dp))

            BasicText(
                "© NERIBO GROUP",
                style = TextStyle(color = TextTag, fontSize = cssSp(19f), letterSpacing = 0.01f.em),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.graphicsLayer {
                    alpha = EaseOut.transform(seg(t.value, 3.35f, 0.6f))
                }
            )
        }
    }
}
