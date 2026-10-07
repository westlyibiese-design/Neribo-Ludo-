package com.westly.ludo.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Quick = a single game. Tournament = you are knocked out. Final = last two, so it also ends the tournament. */
enum class ResignMode { Quick, Tournament, Final }

/**
 * "Resign this game?" / "Resign and leave the tournament?".
 * [mySeat] and [opponentSeat] are seat numbers 0..3 (Red, Green, Yellow, Blue) used for the avatar colours.
 * [onKeepPlaying] is also called by the X button and the Back button.
 */
@Composable
fun GoldResignDialog(
    mode: ResignMode,
    myName: String,
    opponentName: String,
    onKeepPlaying: () -> Unit,
    onResign: () -> Unit,
    modifier: Modifier = Modifier,
    mySeat: Int = 0,
    opponentSeat: Int = 1
) {
    val tournament = mode == ResignMode.Tournament
    GoldDialog(
        title = if (tournament) "Resign and leave the tournament?" else "Resign this game?",
        lead = if (tournament) {
            "You will be knocked out. This can\u2019t be undone."
        } else {
            "$opponentName wins the round if you resign."
        },
        badgeIcon = LudoIcon.Flag,
        badgeTone = ChunkyTones.Red,
        onClose = onKeepPlaying,
        modifier = modifier
    ) {
        InfoPanel {
            if (tournament) {
                InfoRow(
                    title = myName,
                    subtitle = "Knocked out of the tournament",
                    leading = { SeatAvatar(mySeat, myName) },
                    tag = { StatusTag("Out", TagKind.Bad) }
                )
                InfoRow(
                    title = "You can still watch",
                    subtitle = "Follow the rest of the tournament live.",
                    icon = LudoIcon.Eye,
                    topDivider = true
                )
            } else {
                InfoRow(
                    title = "$opponentName gets the point",
                    subtitle = if (mode == ResignMode.Final) {
                        "This is the final, so the tournament ends."
                    } else {
                        "The game ends and scores update."
                    },
                    leading = { SeatAvatar(opponentSeat, opponentName) },
                    tag = { StatusTag("+1", TagKind.Good) }
                )
            }
        }
        DialogButtons {
            ChunkyButton("Keep playing", onKeepPlaying, tone = ChunkyTones.Green)
            ChunkyButton(
                text = if (tournament) "Resign and leave" else "Resign",
                onClick = onResign,
                tone = ChunkyTones.Red
            )
        }
    }
}
