package com.westly.ludo.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * "You're out" (knocked out of a tournament) or "You resigned". The person can keep watching or
 * leave. [opponentName] gets the point if the person leaves.
 * [onWatch] is also called by the X button and the Back button (watching is the safe choice).
 */
@Composable
fun GoldYoureOutDialog(
    resigned: Boolean,
    opponentName: String,
    onWatch: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    opponentSeat: Int = 1
) {
    GoldDialog(
        title = if (resigned) "You resigned" else "You\u2019re out",
        lead = if (resigned) "You left the tournament." else "You are out of the tournament.",
        badgeIcon = if (resigned) LudoIcon.Flag else LudoIcon.Exit,
        badgeTone = ChunkyTones.Red,
        onClose = onWatch,
        modifier = modifier
    ) {
        InfoPanel {
            InfoRow(
                title = "You can still watch",
                subtitle = "Follow the rest of the tournament live.",
                icon = LudoIcon.Eye
            )
            InfoRow(
                title = "If you leave, $opponentName gets the point",
                leading = { SeatAvatar(opponentSeat, opponentName) },
                tag = { StatusTag("+1", TagKind.Good) },
                topDivider = true
            )
        }
        DialogButtons {
            ChunkyButton("Watch the rest", onWatch, tone = ChunkyTones.Green, icon = LudoIcon.Eye)
            GhostButton("Leave game", onLeave)
        }
    }
}
