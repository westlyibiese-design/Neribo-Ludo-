package com.westly.ludo.ui.dialogs

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Full-screen winner page: gold crown badge, "You are the", "Winner!" in a gold gradient on
 * slowly turning gold rays, a line of praise, the winner's row with a "1st" tag, then
 * "Play again" and "Go back to menu".
 *
 * The texts default to the HTML copy, which is written for "you won". Change [eyebrow],
 * [subtitle] and [scoreLine] when somebody else won (pass null to hide a line).
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
    eyebrow: String? = "You are the",
    subtitle: String? = "You conquered the board.",
    scoreLine: String? = "You played like a legend!",
    playAgainEnabled: Boolean = true,
    waitingText: String? = null
) {
    FullScreenGold(
        onBack = onMenu,
        footer = {
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
    ) {
        WinColumn(modifier) {
            FullBadge(LudoIcon.Crown, ChunkyTones.Yellow, Modifier.padding(bottom = 6.dp), big = true)
            if (eyebrow != null) {
                BasicText(
                    eyebrow,
                    style = bodyStyle(16.sp, FontWeight.Bold, GoldTheme.Muted, TextAlign.Center)
                )
            }
            WinnerTitle()
            if (subtitle != null) {
                BasicText(
                    subtitle,
                    modifier = Modifier.widthIn(max = 326.dp),
                    style = bodyStyle(16.sp, FontWeight.Medium, GoldTheme.Muted, TextAlign.Center)
                )
            }
            InfoPanel(Modifier.padding(top = 8.dp)) {
                InfoRow(
                    title = winnerName,
                    subtitle = scoreLine,
                    leading = { SeatAvatar(winnerSeat, winnerName) },
                    tag = { StatusTag("1st", TagKind.Good) }
                )
            }
        }
    }
}

/**
 * The centred column of the winner page. Behind it, 18 gold rays (8 degrees wide, every 20) fade
 * out from a point 200dp below the top and turn once every 50 seconds (still when animations are off).
 */
@Composable
private fun WinColumn(modifier: Modifier, content: @Composable () -> Unit) {
    val motion = LocalMotionEnabled.current
    val turn = if (motion) {
        rememberInfiniteTransition(label = "rays").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(50000, easing = LinearEasing)),
            label = "raysTurn"
        )
    } else {
        null
    }
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val angle = turn?.value ?: 0f
                val cx = this.size.width / 2f
                val cy = 200.dp.toPx()
                val reach = 239.dp.toPx()
                val brush = Brush.radialGradient(
                    colors = listOf(GoldTheme.Ray, GoldTheme.Ray.copy(alpha = 0f)),
                    center = Offset(cx, cy),
                    radius = reach
                )
                for (k in 0 until 18) {
                    drawArc(
                        brush = brush,
                        startAngle = angle - 90f + k * 20f,
                        sweepAngle = 8f,
                        useCenter = true,
                        topLeft = Offset(cx - reach, cy - reach),
                        size = Size(reach * 2f, reach * 2f)
                    )
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        content()
    }
}

/** "Winner!": gold gradient letters over a solid dark-gold copy 4dp lower (the HTML drop-shadow). */
@Composable
private fun WinnerTitle() {
    val width = LocalConfiguration.current.screenWidthDp
    val size = (width * 0.15f).coerceIn(46f, 64f).sp
    val gradient = Brush.verticalGradient(
        0.0f to Color(0xFFFFF3B0),
        0.6f to Color(0xFFF0B429),
        1.0f to Color(0xFFB87A12)
    )
    Box(Modifier.semantics { heading() }, contentAlignment = Alignment.Center) {
        BasicText(
            "Winner!",
            modifier = Modifier.offset(y = 4.dp).clearAndSetSemantics { },
            style = displayStyle(size, Color(0xFF7A4E08), TextAlign.Center, lineHeight = size * 1.1f, letterSpacing = (-0.01).em)
        )
        BasicText(
            "Winner!",
            style = displayStyle(size, GoldTheme.Gold, TextAlign.Center, lineHeight = size * 1.1f, letterSpacing = (-0.01).em)
                .copy(brush = gradient)
        )
    }
}
