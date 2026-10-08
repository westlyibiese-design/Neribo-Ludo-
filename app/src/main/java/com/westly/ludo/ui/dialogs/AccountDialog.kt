package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Account" (UI only): letter badge, the player's [name] as the title, the Google "G" with
 * "Signed in with Google", an orange "Sign out" and a navy "Close". [onSignOut] is a stub the
 * caller fills in later. [onClose] is also called by the X button and the Back button.
 */
@Composable
fun GoldAccountDialog(
    name: String,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    GoldDialog(
        title = name.trim().ifEmpty { "Player" },
        badgeLetter = name.trim().ifEmpty { "Player" },
        badgeTone = ChunkyTones.Blue,
        onClose = onClose,
        modifier = modifier,
        leadContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GoogleMark()
                BasicText(
                    "Signed in with Google",
                    style = bodyStyle(16.sp, FontWeight.Medium, GoldTheme.Muted)
                )
            }
        }
    ) {
        DialogButtons(topGap = 2.dp) {
            ChunkyButton("Sign out", onSignOut, tone = ChunkyTones.Orange, icon = LudoIcon.Exit)
            ChunkyButton("Close", onClose, tone = ChunkyTones.Navy)
        }
    }
}
