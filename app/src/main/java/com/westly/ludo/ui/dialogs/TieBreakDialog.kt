package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * Full-screen dice roll-off. This screen only draws what the game tells it; the game decides who
 * rolls, how long the roll lasts (about 900 ms) and what the dice show.
 *
 * [names] is indexed by seat (0..3). [players] = seats in this round. [tied] = seats that still
 * have to roll (everyone else is dimmed and marked Safe). [rolls] = last roll per seat, 0 = not
 * rolled yet. [rollingSeat] = the seat whose dice are spinning now (-1 = none). [canRollSeat] =
 * the seat that may tap to roll right now (-1 = nobody; the game rolls for the computer).
 *
 * When everybody has rolled, the lowest roll is marked Out. If several share the lowest roll they
 * are marked Again and roll again. [onSeeResult] shows the gold "See result" button when everyone
 * has rolled (pass null to hide it, for example when the game moves on by itself).
 * Back is blocked on this screen.
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
    message: String? = null
) {
    fun nameOf(seat: Int) = names.getOrElse(seat) { "" }.ifEmpty { SeatColors.name(seat) }
    fun rollOf(seat: Int) = rolls.getOrElse(seat) { 0 }

    val pending = tied.filter { rollOf(it) == 0 }
    val allRolled = tied.isNotEmpty() && pending.isEmpty() && rollingSeat < 0
    val low = if (allRolled) tied.minOf { rollOf(it) } else 0
    val lows = if (allRolled) tied.filter { rollOf(it) == low } else emptyList()
    val outSeat = if (lows.size == 1) lows[0] else -1
    val canRoll = canRollSeat >= 0 && rollingSeat < 0 && !allRolled && canRollSeat in tied

    val shownMessage = message ?: when {
        tied.isEmpty() -> ""
        allRolled && outSeat >= 0 -> "${nameOf(outSeat)} rolled the lowest and is out."
        allRolled -> "Tie for the lowest roll! Those players roll again."
        rollingSeat >= 0 -> "Rolling\u2026"
        canRoll -> "${nameOf(canRollSeat)}, tap to roll."
        else -> "Highest roll stays. Lowest goes out."
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
                delay(70)
            }
        }
    }

    FullScreenGold(onBack = null) {
        BasicText(
            "Tie-break",
            modifier = Modifier.semantics { heading() },
            style = displayStyle(34.sp, GoldTheme.Gold, TextAlign.Center)
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Highest roll stays. Lowest goes out.",
            style = bodyStyle(16.sp, FontWeight.Medium, GoldTheme.Muted, TextAlign.Center, 23.sp)
        )
        Spacer(Modifier.height(20.dp))

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (seat in players) {
                val inTie = seat in tied
                val value = if (rollingSeat == seat) flicker else rollOf(seat)
                val tag: Pair<String, TagKind>? = when {
                    !inTie -> "Safe" to TagKind.Good
                    allRolled && seat == outSeat -> "Out" to TagKind.Bad
                    allRolled && lows.size > 1 && rollOf(seat) == low -> "Again" to TagKind.Neutral
                    allRolled -> "Safe" to TagKind.Good
                    else -> null
                }
                RollCard(
                    seat = seat,
                    name = nameOf(seat),
                    value = value,
                    spinning = rollingSeat == seat,
                    dimmed = !inTie,
                    tag = tag,
                    tappable = canRoll && seat == canRollSeat,
                    onTap = onRoll
                )
            }
        }

        StatusLine(shownMessage)

        if (canRoll) {
            DialogButtons { ChunkyButton("Roll dice", onRoll, tone = ChunkyTones.Green) }
        } else if (allRolled && onSeeResult != null) {
            DialogButtons { ChunkyButton("See result", onSeeResult, tone = ChunkyTones.Gold) }
        }
    }
}

/** One status line under the cards; it reserves two lines so the layout does not jump. */
@Composable
private fun StatusLine(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text,
            style = bodyStyle(16.sp, FontWeight.SemiBold, GoldTheme.Cream, TextAlign.Center, 22.sp)
        )
    }
}

@Composable
private fun RollCard(
    seat: Int,
    name: String,
    value: Int,
    spinning: Boolean,
    dimmed: Boolean,
    tag: Pair<String, TagKind>?,
    tappable: Boolean,
    onTap: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val source = remember { MutableInteractionSource() }
    val motion = LocalMotionEnabled.current
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.5f else 1f)
            .background(GoldTheme.PanelFill, shape)
            .border(if (tappable) 2.dp else 1.dp, if (tappable) GoldTheme.Focus else GoldTheme.GoldLine, shape)
            .then(
                if (tappable) Modifier.clickable(
                    interactionSource = source,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Roll dice",
                    onClick = onTap
                ) else Modifier
            )
            .heightIn(min = 72.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SeatAvatar(seat, name, avatarSize = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(
                name,
                style = bodyStyle(18.sp, FontWeight.Bold, GoldTheme.Cream),
                maxLines = 2
            )
            if (tag != null) {
                StatusTag(tag.first, tag.second)
            }
        }
        DiceFace(
            value = value,
            modifier = Modifier.graphicsLayer {
                // a small wobble while the dice spin
                rotationZ = if (spinning && motion) ((value % 3) - 1) * 9f else 0f
            }
        )
    }
}
