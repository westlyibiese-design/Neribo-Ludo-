package com.westly.ludo.ui.dialogs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Result of a tournament round: ranking rows with "pieces home" dots, and a rotated OUT stamp
 * that slams onto the player who is out.
 *
 * [names] is indexed by seat (0..3, empty = not playing). [seeds] = pieces home per seat out of 4,
 * -1 = was not in this round. [outSeat] = the seat that is out.
 * [autoAdvanceMillis] (for example when the person is only watching): calls [onNext] by itself.
 * Back and X are blocked: the only way on is the button ("Next round" or "Continue").
 */
@Composable
fun GoldRoundResultDialog(
    round: Int,
    outSeat: Int,
    names: List<String>,
    seeds: List<Int>,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    nextLabel: String = "Next round",
    nextEnabled: Boolean = true,
    autoAdvanceMillis: Long? = null
) {
    if (autoAdvanceMillis != null) {
        LaunchedEffect(Unit) {
            delay(autoAdvanceMillis)
            onNext()
        }
    }
    fun seedsOf(seat: Int) = seeds.getOrElse(seat) { -1 }
    val seats = names.indices.filter { names[it].isNotEmpty() }
    // In the round and still in first, then the player who is out, then players who were already out.
    val ranked = seats.sortedWith(
        compareBy<Int>(
            { if (seedsOf(it) < 0) 2 else if (it == outSeat) 1 else 0 },
            { -seedsOf(it) }
        )
    )
    val outName = names.getOrElse(outSeat) { "" }

    GoldDialog(
        title = "Round $round result",
        lead = if (outName.isNotEmpty()) "$outName (${SeatColors.name(outSeat)}) is out." else null,
        badgeIcon = LudoIcon.Bars,
        badgeTone = ChunkyTones.Gold,
        onClose = {},
        showClose = false,
        modifier = modifier
    ) {
        InfoPanel {
            ranked.forEachIndexed { index, seat ->
                ResultRow(
                    rank = index + 1,
                    seat = seat,
                    name = names[seat],
                    seeds = seedsOf(seat),
                    isOut = seat == outSeat,
                    topDivider = index > 0
                )
            }
        }
        DialogButtons {
            ChunkyButton(nextLabel, onNext, tone = ChunkyTones.Green, enabled = nextEnabled)
        }
    }
}

@Composable
private fun ResultRow(rank: Int, seat: Int, name: String, seeds: Int, isOut: Boolean, topDivider: Boolean) {
    val wasIn = seeds >= 0
    Column(Modifier.fillMaxWidth()) {
        if (topDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(GoldTheme.Line))
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.alpha(if (wasIn) 1f else 0.4f)) { SeatAvatar(seat, name) }
            Column(Modifier.weight(1f).alpha(if (wasIn) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                BasicText(
                    "$rank. $name",
                    style = bodyStyle(
                        16.sp, FontWeight.Bold,
                        if (isOut) GoldTheme.ErrorText else GoldTheme.Cream
                    ),
                    maxLines = 2
                )
                if (wasIn) {
                    HomeDots(seeds)
                }
            }
            when {
                isOut -> OutStamp()
                wasIn -> StatusTag("$seeds/4", TagKind.Neutral)
                else -> StatusTag("Out", TagKind.Bad)
            }
        }
    }
}

/** Four little dots: gold = a piece that is home. */
@Composable
private fun HomeDots(home: Int) {
    Row(
        Modifier.semantics { contentDescription = "$home of 4 pieces home" },
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (i in 0 until 4) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(if (i < home) GoldTheme.Gold else GoldTheme.Line, CircleShape)
            )
        }
    }
}

/** Rotated red "OUT" stamp. It starts big and see-through and slams down with a small bounce. */
@Composable
private fun OutStamp() {
    val motion = LocalMotionEnabled.current
    val scale = remember { Animatable(if (motion) 2.6f else 1f) }
    val fade = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (motion) {
            delay(350)
            fade.animateTo(1f, tween(90))
        }
    }
    LaunchedEffect(Unit) {
        if (motion) {
            delay(350)
            scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
        }
    }
    val shape = RoundedCornerShape(8.dp)
    BasicText(
        "OUT",
        modifier = Modifier
            .graphicsLayer {
                rotationZ = -10f
                scaleX = scale.value
                scaleY = scale.value
                alpha = fade.value
            }
            .border(2.5.dp, GoldTheme.TagBad, shape)
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .semantics { contentDescription = "Out" },
        style = displayStyle(22.sp, GoldTheme.TagBad),
        maxLines = 1
    )
}
