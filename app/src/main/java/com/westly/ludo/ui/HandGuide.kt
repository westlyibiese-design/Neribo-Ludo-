package com.westly.ludo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.westly.ludo.game.HandTarget

/** Where the fingertip is, and whether the hand comes from below (pointing up). */
private class HandPose(val tipX: Dp, val tipY: Dp, val up: Boolean)

/**
 * The pointing hand. It is drawn only while [target] (taken straight from the game state) is
 * not null, glides to the new spot when the target changes, and fades away when the action is
 * done. It never receives touches, so it cannot block the player.
 *
 * Geometry: the board's top-left corner and side, the vertical center of the movement circles
 * and a function giving the horizontal center of each circle (option 0 = blue, 1 = green, 2 = red).
 */
@Composable
fun BoxScope.HandGuide(
    target: HandTarget?,
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
    val cell = with(density) { m.cell.toDp() }

    val pose: HandPose? = when (target) {
        null -> null
        HandTarget.Dice -> HandPose(colDp(7.5f), rowDp(7.5f) - cell * 0.35f, false)
        is HandTarget.Orb -> HandPose(orbX(target.index), orbCenterY + u * 4.6f, true)
        is HandTarget.Spot -> HandPose(colDp(target.col), rowDp(target.row) - cell * 0.35f, false)
    }

    var last by remember { mutableStateOf<HandPose?>(null) }
    SideEffect { if (pose != null) last = pose }
    val shown = pose ?: last ?: return

    val x by animateDpAsState(shown.tipX, tween(450), label = "handX")
    val y by animateDpAsState(shown.tipY, tween(450), label = "handY")
    val alpha by animateFloatAsState(if (pose != null) 1f else 0f, tween(250), label = "handAlpha")
    if (alpha < 0.01f) return

    // Gentle tapping motion toward the target.
    val bob by rememberInfiniteTransition(label = "handBob").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "handBobValue"
    )

    val w = u * 8f
    val sign = if (shown.up) -1f else 1f
    val approach = u * 1.6f * (bob - 1f) * sign
    // Fingertip inside the hand drawing: (0.51w, 1.40w) pointing down, (0.49w, 0.10w) when turned up.
    val tipDx = if (shown.up) w * 0.49f else w * 0.51f
    val tipDy = if (shown.up) w * 0.10f else w * 1.40f

    PointingHand(
        w,
        Modifier
            .align(Alignment.TopStart)
            .offset(x - tipDx, y - tipDy + approach)
            .graphicsLayer {
                this.alpha = alpha
                rotationZ = if (shown.up) 180f else 0f
            }
    )
}
