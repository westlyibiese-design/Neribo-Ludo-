package com.westly.ludo.ui.dialogs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val SlamEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * Result of a tournament round (full-screen): the OUT stamp card for the player who is out, then
 * a ranking with "pieces home" dots.
 *
 * [names] is indexed by seat (0..3, empty = not playing). [seeds] = pieces home per seat out of 4,
 * -1 = was not in this round. [outSeat] = the seat that is out.
 * [nextLabel] is "Next round", or "Continue" when the person using the phone is the one who is out.
 * [autoAdvanceMillis] (for example when the person is only watching): calls [onNext] by itself.
 * Back is blocked: the only way on is the button.
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
    // In the round and still in first (most pieces home first), then the player who is out,
    // then players who were already out.
    val ranked = seats.sortedWith(
        compareBy<Int>(
            { if (seedsOf(it) < 0) 2 else if (it == outSeat) 1 else 0 },
            { -seedsOf(it) }
        )
    )
    val outName = names.getOrElse(outSeat) { "" }.ifEmpty { SeatColors.name(outSeat) }

    FullScreenGold(
        onBack = null,
        header = {
            BasicText(
                "Round $round result",
                style = bodyStyle(16.sp, FontWeight.Bold, GoldTheme.Muted, TextAlign.Center)
            )
        },
        footer = {
            ChunkyButton(nextLabel, onNext, tone = ChunkyTones.Green, icon = LudoIcon.Next, enabled = nextEnabled)
        }
    ) {
        OutStampCard(outSeat = outSeat, outName = outName, modifier = modifier)

        InfoPanel {
            ranked.forEachIndexed { index, seat ->
                val wasIn = seedsOf(seat) >= 0
                val isOut = seat == outSeat || !wasIn
                InfoRow(
                    title = names[seat],
                    subtitle = if (wasIn) "${seedsOf(seat)} of 4 pieces home" else null,
                    leading = { SeatAvatar(seat, names[seat], avatarSize = 34.dp) },
                    tag = {
                        if (isOut) StatusTag("Out", TagKind.Bad) else HomeDots(seedsOf(seat))
                    },
                    dimmed = isOut,
                    topDivider = index > 0,
                    verticalPadding = 10.dp
                )
            }
        }
    }
}

/** The gold card with the grey-ringed avatar, the slamming OUT badge, the name and the reason. */
@Composable
private fun OutStampCard(outSeat: Int, outName: String, modifier: Modifier) {
    val shortScreen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp <= 640
    Box(modifier.fillMaxWidth().goldFrame()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = if (shortScreen) 20.dp else 30.dp,
                    bottom = if (shortScreen) 20.dp else 24.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SeatAvatar(outSeat, outName, avatarSize = 96.dp, grayscale = 0.35f, stamp = true)
            BasicText(
                "$outName is out",
                modifier = Modifier.semantics { heading() },
                style = displayStyle(
                    27.sp, GoldTheme.Cream, TextAlign.Center,
                    lineHeight = 29.7.sp, letterSpacing = (-0.27).sp
                )
            )
            BasicText(
                "${SeatColors.name(outSeat)} lost the tie-break and leaves the tournament.",
                modifier = Modifier.widthIn(max = 326.dp),
                style = bodyStyle(16.sp, FontWeight.Medium, GoldTheme.Muted, TextAlign.Center)
            )
        }
        OutBadge(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 16.dp))
    }
}

/** Four 10dp dots: cream = a piece that is home, ring only = not home yet. */
@Composable
private fun HomeDots(home: Int) {
    Row(
        Modifier.semantics { contentDescription = "$home of 4 pieces home" },
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (i in 0 until 4) {
            Box(
                Modifier
                    .size(10.dp)
                    .then(
                        if (i < home) Modifier.background(GoldTheme.Cream, CircleShape)
                        else Modifier.border(1.5.dp, GoldTheme.Muted, CircleShape)
                    )
            )
        }
    }
}

/**
 * White "OUT" on red, tilted 9 degrees. After a 250ms wait it starts big and see-through, lands
 * at 60% (slightly small, fully visible) and settles at normal size by 550ms.
 */
@Composable
private fun OutBadge(modifier: Modifier) {
    val motion = LocalMotionEnabled.current
    var t by remember { mutableFloatStateOf(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (motion) {
            delay(250)
            val clock = Animatable(0f)
            clock.animateTo(1f, tween(550, easing = LinearEasing)) { t = value }
        }
    }
    // keyframes: 0% (opacity 0, rotate -14, scale 2.6) -> 60% (opacity 1, rotate 9, scale .94) -> 100% (scale 1)
    val first = t < 0.6f
    val local = if (first) SlamEasing.transform(t / 0.6f) else SlamEasing.transform((t - 0.6f) / 0.4f)
    val scaleNow = if (first) 2.6f + (0.94f - 2.6f) * local else 0.94f + (1f - 0.94f) * local
    val rotationNow = if (first) -14f + (9f - -14f) * local else 9f
    val alphaNow = if (first) local else 1f
    val shape = RoundedCornerShape(10.dp)
    BasicText(
        "OUT",
        modifier = modifier
            .graphicsLayer {
                rotationZ = rotationNow
                scaleX = scaleNow
                scaleY = scaleNow
                alpha = alphaNow
            }
            .shadow(8.dp, shape, clip = false)
            .background(GoldTheme.TagBad, shape)
            .border(3.dp, Color.White, shape)
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .semantics { contentDescription = "Out" },
        style = displayStyle(30.sp, Color.White),
        maxLines = 1
    )
}
