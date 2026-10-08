package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen "You're out" (knocked out of a tournament) or "You resigned". The person can keep
 * watching or leave. [opponentName] gets the point if the person leaves.
 * [onWatch] is also called by the Back button (watching is the safe choice).
 * [modifier] is accepted for symmetry with the other dialogs and is not used.
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
    FullScreenGold(
        onBack = onWatch,
        mainGap = 14.dp,
        footer = {
            ChunkyButton("Watch the rest", onWatch, tone = ChunkyTones.Green, icon = LudoIcon.Eye)
            GhostButton("Leave game", onLeave, icon = LudoIcon.Exit)
        }
    ) {
        SeatAvatar(-1, "", avatarSize = 96.dp, icon = LudoIcon.Close)
        BasicText(
            if (resigned) "You resigned" else "You\u2019re out",
            modifier = Modifier.semantics { heading() },
            style = displayStyle(
                34.sp, GoldTheme.Cream, TextAlign.Center,
                lineHeight = 37.4.sp, letterSpacing = (-0.34).sp
            )
        )
        BasicText(
            (if (resigned) "You left the tournament." else "You\u2019re out of the tournament.") +
                " Watch the rest, or leave now.",
            modifier = Modifier.widthIn(max = 326.dp),
            style = bodyStyle(16.sp, FontWeight.Medium, GoldTheme.Muted, TextAlign.Center)
        )
        InfoPanel {
            InfoRow(
                title = "If you leave, $opponentName gets the point",
                subtitle = "Watching keeps the scores as they are.",
                leading = { SeatAvatar(opponentSeat, opponentName) }
            )
        }
    }
}
