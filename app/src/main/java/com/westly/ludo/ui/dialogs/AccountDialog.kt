package com.westly.ludo.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "Account" (UI only): letter badge, the player's [name] as the title, "Signed in with Google",
 * an orange "Sign out" and a navy "Close". [onSignOut] is a stub the caller fills in later.
 * [onClose] is also called by the X button and the Back button.
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
        lead = "Signed in with Google",
        badgeLetter = name.trim().ifEmpty { "Player" },
        badgeTone = ChunkyTones.Blue,
        onClose = onClose,
        modifier = modifier
    ) {
        DialogButtons(topGap = 2.dp) {
            ChunkyButton("Sign out", onSignOut, tone = ChunkyTones.Orange)
            ChunkyButton("Close", onClose, tone = ChunkyTones.Navy)
        }
    }
}
