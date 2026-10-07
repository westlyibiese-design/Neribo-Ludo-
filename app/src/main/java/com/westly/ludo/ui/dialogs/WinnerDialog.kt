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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen winner page: gold crown badge on slowly turning gold rays, "Winner!" in a gold
 * gradient, the winner's row with a "1st" tag, then "Play again" and "Go back to menu".
 *
 * [onMenu] is also called by the Back button. For a person who cannot start the next game (for
 * example a guest in an online game) pass [playAgainEnabled] = false and a [waitingText].
 */
@Composable
fun GoldWinnerDialog(
    winnerName: String,
    onPlayAgain: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    winnerSeat: Int = 0,
    subtitle: String? = null,
    scoreLine: String? = null,
    playAgainEnabled: Boolean = true,
    waitingText: String? = null
) {
    FullScreenGold(onBack = onMenu) {
        Box(modifier.size(280.dp), contentAlignment = Alignment.Center) {
            Rays(Modifier.size(280.dp))
            CrownBadge()
        }
        Spacer(Modifier.height(8.dp))
        BasicText(
            "Winner!",
            modifier = Modifier.semantics { heading() },
            style = displayStyle(46.sp, GoldTheme.Gold, TextAlign.Center).copy(
                brush = Brush.verticalGradient(listOf(Color(0xFFFFE9A6), GoldTheme.Gold, Color(0xFFD99A1E)))
            )
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                subtitle,
                style = bodyStyle(16.sp, FontWeight.Medium, GoldTheme.Muted, TextAlign.Center, 23.sp)
            )
        }
        Spacer(Modifier.height(18.dp))
        InfoPanel {
            InfoRow(
                title = winnerName,
                subtitle = scoreLine,
                leading = { SeatAvatar(winnerSeat, winnerName, avatarSize = 44.dp) },
                tag = { StatusTag("1st", TagKind.Good) }
            )
        }
        DialogButtons {
            if (waitingText != null) {
                BasicText(
                    waitingText,
                    modifier = Modifier.fillMaxWidth(),
                    style = bodyStyle(15.sp, FontWeight.SemiBold, GoldTheme.Muted, TextAlign.Center)
                )
            }
            ChunkyButton("Play again", onPlayAgain, tone = ChunkyTones.Green, enabled = playAgainEnabled)
            ChunkyButton("Go back to menu", onMenu, tone = ChunkyTones.Navy)
        }
    }
}

@Composable
private fun CrownBadge() {
    Box(
        Modifier
            .size(112.dp)
            .shadow(14.dp, CircleShape, clip = false, ambientColor = GoldTheme.Gold, spotColor = Color.Black)
            .background(GoldTheme.Navy2, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(ChunkyTones.Gold.top, ChunkyTones.Gold.bottom)))
            .border(3.dp, Color(0xFFFFF3C4), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        LudoIconView(LudoIcon.Crown, iconSize = 56.dp, tint = ChunkyTones.Gold.content)
    }
}

/** Twelve soft gold wedges that turn once every 40 seconds (still when animations are off). */
@Composable
private fun Rays(modifier: Modifier) {
    val motion = LocalMotionEnabled.current
    val turn = if (motion) {
        val transition = rememberInfiniteTransition(label = "rays")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(40000, easing = LinearEasing), RepeatMode.Restart),
            label = "raysTurn"
        )
        value
    } else {
        0f
    }
    Canvas(modifier.graphicsLayer { rotationZ = turn }) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val brush = Brush.radialGradient(
            colors = listOf(GoldTheme.Gold.copy(alpha = 0.5f), Color.Transparent),
            center = center,
            radius = radius
        )
        val wedges = 12
        for (k in 0 until wedges) {
            val start = Math.toRadians(k * 360.0 / wedges - 7.5)
            val end = Math.toRadians(k * 360.0 / wedges + 7.5)
            val path = Path().apply {
                moveTo(center.x, center.y)
                lineTo(center.x + radius * cos(start).toFloat(), center.y + radius * sin(start).toFloat())
                lineTo(center.x + radius * cos(end).toFloat(), center.y + radius * sin(end).toFloat())
                close()
            }
            drawPath(path, brush)
        }
    }
}
