package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Game = only the game in progress. Tournament = the whole tournament. */
enum class EndScope { Game, Tournament }

/**
 * "End this game?" / "End the whole tournament?".
 * [round] is the round being played (only used for the tournament round pills and texts).
 * [onKeepPlaying] is also called by the X button and the Back button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoldEndDialog(
    scope: EndScope,
    onKeepPlaying: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    round: Int = 3
) {
    val tournament = scope == EndScope.Tournament
    val rounds = if (round < 1) 1 else round
    GoldDialog(
        title = if (tournament) "End the whole tournament?" else "End this game?",
        lead = if (tournament) "This closes the tournament for everyone." else "Only the game in progress ends.",
        badgeIcon = LudoIcon.Power,
        badgeTone = ChunkyTones.Red,
        onClose = onKeepPlaying,
        modifier = modifier
    ) {
        // Which rounds are affected
        FlowRow(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (tournament) {
                for (r in 1..rounds) {
                    RoundPill("Round $r", current = r == rounds)
                }
            } else {
                RoundPill("This game only", current = true, wide = true)
            }
        }

        InfoPanel {
            if (tournament) {
                InfoRow(
                    title = "All $rounds rounds are discarded",
                    subtitle = "Round $rounds is stopped where it stands.",
                    icon = LudoIcon.Redo
                )
                InfoRow(
                    title = "Every score is cleared",
                    subtitle = "Nobody keeps their points.",
                    icon = LudoIcon.Bars,
                    topDivider = true
                )
                InfoRow(
                    title = "Back to the mode menu",
                    subtitle = "A new tournament starts from round 1.",
                    icon = LudoIcon.Exit,
                    topDivider = true
                )
            } else {
                InfoRow(
                    title = "This game stops now",
                    subtitle = "Pieces stay where they are, then reset.",
                    icon = LudoIcon.Redo
                )
                InfoRow(
                    title = "Scores are cleared",
                    subtitle = "Everyone starts again from zero.",
                    icon = LudoIcon.Bars,
                    topDivider = true
                )
                InfoRow(
                    title = "Back to the mode menu",
                    subtitle = "Pick a mode to play again.",
                    icon = LudoIcon.Exit,
                    topDivider = true
                )
            }
        }

        DialogButtons {
            ChunkyButton("Keep playing", onKeepPlaying, tone = ChunkyTones.Green)
            ChunkyButton(
                text = if (tournament) "End tournament" else "End game",
                onClick = onEnd,
                tone = ChunkyTones.Red
            )
        }
    }
}

/** Small red pill. Past rounds are struck through; the current one is solid red. */
@Composable
private fun RoundPill(text: String, current: Boolean, wide: Boolean = false) {
    BasicText(
        text,
        modifier = Modifier
            .background(
                if (current) GoldTheme.TagBad else Color(0x38D6362F),
                RoundedCornerShape(50)
            )
            .padding(horizontal = if (wide) 14.dp else 11.dp, vertical = 5.dp),
        style = bodyStyle(
            13.sp,
            FontWeight.Bold,
            if (current) Color.White else GoldTheme.ErrorText
        ).merge(
            TextStyle(textDecoration = if (current) TextDecoration.None else TextDecoration.LineThrough)
        ),
        maxLines = 1
    )
}
