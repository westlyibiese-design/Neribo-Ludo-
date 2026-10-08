package com.westly.ludo.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "Sign in" (UI only). No Google or Play services code lives here: [onSignIn] is a stub the
 * caller fills in later. [onCancel] is also called by the X button and the Back button.
 */
@Composable
fun GoldSignInDialog(
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    GoldDialog(
        title = "Sign in",
        lead = "Sign in with Google to play online with friends.",
        badgeIcon = LudoIcon.Star,
        badgeTone = ChunkyTones.Yellow,
        onClose = onCancel,
        modifier = modifier
    ) {
        DialogButtons(topGap = 2.dp) {
            ChunkyButton(
                text = "Sign in with Google",
                onClick = onSignIn,
                tone = ChunkyTones.White,
                leading = { GoogleMark() }
            )
            ChunkyButton("Cancel", onCancel, tone = ChunkyTones.Navy)
        }
    }
}
