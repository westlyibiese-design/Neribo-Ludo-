package com.westly.ludo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.westly.ludo.game.HandTarget
import com.westly.ludo.game.LudoColor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Origin (where the hand enters from) and fingertip, in dp from the top-left of the screen area. */
private class HandPose(val ox: Dp, val oy: Dp, val tx: Dp, val ty: Dp)

/**
 * The computer player's hand. It exists only while the computer has already decided what it
 * will do ([target] comes straight from that decision). It enters from the computer player's
 * own side of the board (the corner of its yard, or the screen edge beside its movement
 * circles), glides to the real target with the wrist always toward its own side (never upside
 * down), taps, and glides back out when the action is done. It never receives touches.
 */
@Composable
fun BoxScope.HandGuide(
    target: HandTarget?,
    owner: LudoColor,
    screenW: Dp,
    boardLeft: Dp,
    boardTop: Dp,
    boardSide: Dp,
    orbCenterY: Dp,
    orbX: (Int) -> Dp,
    u: Dp
) {
    val density = LocalDensity.current
    val m = boardMetrics(with(density) { boardSide.toPx() })
    fun colDp(c: Float) = boardLeft + with(density) { m.x(c).toDp() }
    fun rowDp(r: Float) = boardTop + with(density) { m.y(r).toDp() }

    val leftSide = owner == LudoColor.GREEN || owner == LudoColor.RED
    // The corner of the owner's yard, in board grid units.
    val oRow = if (owner == LudoColor.GREEN || owner == LudoColor.YELLOW) 1.2f else 13.8f
    val oCol = if (owner == LudoColor.GREEN || owner == LudoColor.RED) 1.2f else 13.8f

    /** Point [back] cells before (tr, tc) on the line coming from the owner's corner. */
    fun boardPose(tr: Float, tc: Float, back: Float): HandPose {
        val dr = tr - oRow
        val dc = tc - oCol
        val len = hypot(dr, dc)
        val k = if (len > back + 0.3f) back / len else 0f
        return HandPose(colDp(oCol), rowDp(oRow), colDp(tc - dc * k), rowDp(tr - dr * k))
    }

    val pose: HandPose? = when (target) {
        null -> null
        HandTarget.Dice -> boardPose(7.5f, 7.5f, 1.9f)          // stops beside the dice, not on them
        is HandTarget.Spot -> boardPose(target.row, target.col, 0.55f)   // stops at the edge of the seed
        is HandTarget.Orb -> {
            val cx = orbX(target.index)
            if (leftSide) HandPose(-u * 2f, orbCenterY, cx - u * 7.2f, orbCenterY)
            else HandPose(screenW + u * 2f, orbCenterY, cx + u * 7.2f, orbCenterY)
        }
    }

    var last by remember { mutableStateOf<HandPose?>(null) }
    SideEffect { if (pose != null) last = pose }
    val shown = pose ?: last ?: return

    // Starts at the owner's side, glides to the target, and glides back when the action ends.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(pose != null) {
        arrived = false
        if (pose != null) {
            withFrameNanos { }
            arrived = true
        }
    }
    val x by animateDpAsState(if (arrived) shown.tx else shown.ox, tween(520), label = "handX")
    val y by animateDpAsState(if (arrived) shown.ty else shown.oy, tween(520), label = "handY")
    val alpha by animateFloatAsState(if (pose != null) 1f else 0f, tween(260), label = "handAlpha")
    if (pose == null && alpha < 0.01f) return

    // Finger points away from the owner's side, wrist stays toward it.
    var angle = (atan2((shown.ty - shown.oy).value, (shown.tx - shown.ox).value) * 180f / PI.toFloat()) - 90f
    if (angle < -180f) angle += 360f
    val rot by animateFloatAsState(angle, tween(300), label = "handRot")
    val rad = (angle + 90f) * PI.toFloat() / 180f
    val dirX = cos(rad)
    val dirY = sin(rad)

    val bob by rememberInfiniteTransition(label = "handBob").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(480), RepeatMode.Reverse),
        label = "handBobValue"
    )

    val w = u * 5.2f
    val tipDx = w * 0.51f
    val tipDy = w * 1.40f

    BlackHand(
        w,
        Modifier
            .align(Alignment.TopStart)
            .offset(x - tipDx, y - tipDy)
            .graphicsLayer {
                this.alpha = alpha
                // Turn around the fingertip so the tip stays exactly on the target.
                transformOrigin = TransformOrigin(0.51f, 1.40f / 1.5f)
                rotationZ = rot
                // Small tap toward the target.
                val tap = u.toPx() * 1.2f * (1f - bob)
                translationX = -dirX * tap
                translationY = -dirY * tap
            }
    )
}

/** Small bold black human hand, index finger pointing down (tip at 0.51w, 1.40w). */
@Composable
private fun BlackHand(width: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width, width * 1.5f)) { drawBlackHand() }
}

private fun DrawScope.drawBlackHand() {
    val w = size.width
    val fill = Brush.verticalGradient(listOf(Color(0xFF2E2E2E), Color(0xFF040404)))
    val edge = Color.White.copy(alpha = 0.55f)
    val stroke = Stroke(w * 0.035f)

    fun part(x: Float, y: Float, pw: Float, ph: Float) {
        val tl = Offset(x * w, y * w)
        val sz = Size(pw * w, ph * w)
        val cr = CornerRadius(minOf(pw, ph) * w / 2f)
        drawRoundRect(brush = fill, topLeft = tl, size = sz, cornerRadius = cr)
        drawRoundRect(color = edge, topLeft = tl, size = sz, cornerRadius = cr, style = stroke)
    }

    part(0.36f, 0.55f, 0.30f, 0.85f)   // index finger
    part(0.10f, 0.10f, 0.80f, 0.65f)   // back of the hand
    part(0.06f, 0.42f, 0.34f, 0.15f)   // folded fingers
    part(0.06f, 0.56f, 0.34f, 0.15f)
    part(0.60f, 0.40f, 0.34f, 0.16f)   // thumb

    // Wrist
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF6B6B6B), Color(0xFF2A2A2A))),
        topLeft = Offset(0.22f * w, 0f),
        size = Size(0.56f * w, 0.14f * w),
        cornerRadius = CornerRadius(0.03f * w)
    )
}
