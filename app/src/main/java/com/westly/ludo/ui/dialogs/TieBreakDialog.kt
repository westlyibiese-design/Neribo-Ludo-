package com.westly.ludo.ui.dialogs

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Soft ring colour of the row whose turn it is: yellow at 18%. */
private const val GlowAlpha = 0.18f

/**
 * Full-screen dice roll-off. This screen only draws what the game tells it; the game decides who
 * rolls, how long the roll lasts (about 900 ms) and what the dice show.
 *
 * [names] is indexed by seat (0..3). [players] = seats in this round, in rolling order. [tied] =
 * seats that still have to roll (everyone else is dimmed and marked Safe). [rolls] = last roll
 * per seat, 0 = not rolled yet. [rollingSeat] = the seat whose dice are spinning now (-1 = none).
 * [canRollSeat] = the seat that may tap its row to roll right now (-1 = nobody; the game rolls
 * for the computer). [round] = 1 for the first roll-off, 2 and up when only tied players roll again.
 *
 * When everybody has rolled and one player has the lowest roll, that player is marked Out and the
 * gold-free green "See result" button appears ([onSeeResult], pass null to hide it, for example
 * when the game moves on by itself). If several share the lowest roll the status line says they
 * roll again; the game then starts the next round with a smaller [tied] list.
 * [message] replaces the status line text. Back is blocked on this screen.
 */
@Composable
fun GoldTieBreakDialog(
    names: List<String>,
    players: List<Int>,
    tied: List<Int>,
    rolls: List<Int>,
    rollingSeat: Int,
    canRollSeat: Int,
    onRoll: () -> Unit,
    onSeeResult: (() -> Unit)? = null,
    message: String? = null,
    round: Int = 1
) {
    fun nameOf(seat: Int) = names.getOrElse(seat) { "" }.ifEmpty { SeatColors.name(seat) }
    fun rollOf(seat: Int) = rolls.getOrElse(seat) { 0 }

    val pending = tied.filter { rollOf(it) == 0 }
    val allRolled = tied.isNotEmpty() && pending.isEmpty() && rollingSeat < 0
    val low = if (allRolled) tied.minOf { rollOf(it) } else 0
    val lows = if (allRolled) tied.filter { rollOf(it) == low } else emptyList()
    val outSeat = if (lows.size == 1) lows[0] else -1
    val done = allRolled && outSeat >= 0
    val canRoll = canRollSeat >= 0 && rollingSeat < 0 && !allRolled && canRollSeat in tied
    val turnSeat = when {
        done -> -1
        rollingSeat >= 0 -> rollingSeat
        else -> pending.firstOrNull() ?: -1
    }

    val shownMessage = message ?: when {
        done -> "${nameOf(outSeat)} rolled lowest and is out."
        allRolled -> "${lows.joinToString(" and ") { nameOf(it) }} tied for lowest. Rolling again..."
        round > 1 -> "Round $round of the tie-break. Only tied players roll again."
        canRoll -> "Tap your dice to roll."
        else -> "Waiting for the others..."
    }

    val level = when (players.size) {
        2 -> "Two players are level."
        3 -> "Three players are level."
        4 -> "Four players are level."
        else -> "Players are level."
    }

    // The dice face that flickers while a seat is rolling (skipped when animations are off).
    // (read straight from the phone: this runs outside the dialog window that provides LocalMotionEnabled)
    val context = LocalContext.current
    val motion = remember(context) { animationsEnabled(context) }
    var flicker by remember { mutableIntStateOf(1) }
    LaunchedEffect(rollingSeat) {
        if (rollingSeat >= 0 && motion) {
            while (true) {
                flicker = Random.nextInt(1, 7)
                delay(110)
            }
        }
    }

    FullScreenGold(
        onBack = null,
        header = {
            FullBadge(LudoIcon.Dice, ChunkyTones.Yellow, Modifier.padding(bottom = 6.dp))
            BasicText(
                "Tie-break",
                modifier = Modifier.semantics { heading() },
                style = displayStyle(
                    size = 27.sp,
                    color = GoldTheme.Cream,
                    align = TextAlign.Center,
                    lineHeight = 29.7.sp,
                    letterSpacing = (-0.27).sp
                )
            )
            BasicText(
                "$level Highest rolls stay in. The lowest roll is out.",
                modifier = Modifier.widthIn(max = 326.dp),
                style = bodyStyle(16.sp, FontWeight.Medium, GoldTheme.Muted, TextAlign.Center)
            )
        },
        belowMain = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.TopCenter
            ) {
                BasicText(
                    shownMessage,
                    style = bodyStyle(16.sp, FontWeight.SemiBold, GoldTheme.Muted, TextAlign.Center)
                )
            }
        },
        footer = {
            if (done && onSeeResult != null) {
                ChunkyButton("See result", onSeeResult, tone = ChunkyTones.Green, icon = LudoIcon.Next)
            }
        }
    ) {
        for (seat in players) {
            val inTie = seat in tied
            val value = if (rollingSeat == seat) flicker else rollOf(seat)
            val mine = canRoll && seat == canRollSeat
            val state: RowState
            val sub: String
            var tag: Pair<String, TagKind>? = null
            when {
                done -> {
                    state = if (seat == outSeat) RowState.Out else RowState.Through
                    sub = if (seat == outSeat) "Lowest roll" else "Safe"
                    tag = if (seat == outSeat) "Out" to TagKind.Bad else "Safe" to TagKind.Good
                }
                !inTie -> {
                    state = RowState.Through
                    sub = "Rolled higher, safe this round"
                    tag = "Safe" to TagKind.Good
                }
                rollingSeat == seat -> {
                    state = RowState.Turn
                    sub = "Rolling..."
                }
                mine -> {
                    state = RowState.Turn
                    sub = "Your turn: tap to roll"
                }
                value > 0 -> {
                    state = RowState.Plain
                    sub = "Rolled $value"
                }
                turnSeat == seat -> {
                    state = RowState.Turn
                    sub = "${nameOf(seat)} is rolling"
                }
                else -> {
                    state = RowState.Plain
                    sub = "Waiting for turn"
                }
            }
            RollRow(
                seat = seat,
                name = nameOf(seat),
                sub = sub,
                tag = tag,
                state = state,
                value = value,
                spinning = rollingSeat == seat,
                tappable = mine,
                onTap = onRoll
            )
        }

    }
}

/** How a row looks: Plain, Turn (yellow ring that pulses), Through (faded, safe) or Out (red). */
private enum class RowState { Plain, Turn, Through, Out }

/** One player's row: avatar, name and small status line, optional tag, and the die. */
@Composable
private fun RollRow(
    seat: Int,
    name: String,
    sub: String,
    tag: Pair<String, TagKind>?,
    state: RowState,
    value: Int,
    spinning: Boolean,
    tappable: Boolean,
    onTap: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    val source = remember { MutableInteractionSource() }
    val motion = LocalMotionEnabled.current

    // glow: 0 -> 5dp yellow ring at 18% -> 0, 1.4s, ease-in-out
    val glow = if (state == RowState.Turn && motion) {
        rememberInfiniteTransition(label = "rowGlow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(700, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
                RepeatMode.Reverse
            ),
            label = "rowGlowValue"
        )
    } else {
        null
    }

    val fill = when (state) {
        RowState.Turn -> GoldTheme.TurnFill
        RowState.Out -> GoldTheme.OutFill
        else -> GoldTheme.RowFill
    }
    val border = when (state) {
        RowState.Turn -> GoldTheme.Focus
        RowState.Out -> GoldTheme.ErrorBorder
        else -> GoldTheme.Line
    }

    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (state == RowState.Through) 0.6f else 1f)
            .drawBehind {
                val amount = glow?.value ?: 0f
                if (amount > 0f) {
                    val half = 2.5.dp.toPx()
                    drawRoundRect(
                        color = Color(0xFFFFD85E).copy(alpha = GlowAlpha * amount),
                        topLeft = Offset(-half, -half),
                        size = Size(this.size.width + half * 2f, this.size.height + half * 2f),
                        cornerRadius = CornerRadius(22.dp.toPx() + half),
                        style = Stroke(width = 5.dp.toPx())
                    )
                }
            }
            .background(fill, shape)
            .border(1.5.dp, border, shape)
            .then(
                if (tappable) Modifier.clickable(
                    interactionSource = source,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Roll the dice",
                    onClick = onTap
                ) else Modifier
            )
            .padding(start = 12.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SeatAvatar(seat, name, grayscale = if (state == RowState.Out) 0.7f else 0f)
        Column(Modifier.weight(1f)) {
            BasicText(name, style = displayStyle(18.sp, GoldTheme.Cream), maxLines = 1)
            BasicText(
                sub,
                style = bodyStyle(14.sp, FontWeight.Medium, GoldTheme.Muted)
            )
        }
        if (tag != null) {
            StatusTag(tag.first, tag.second)
        }
        DiceFace(value = value, shaking = spinning)
    }
}
