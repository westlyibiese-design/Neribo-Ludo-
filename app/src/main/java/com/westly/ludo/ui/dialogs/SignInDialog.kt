package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Sign in". No Google or Play services code lives here: [onSignIn] is filled in by the caller.
 * [onCancel] is also called by the X button and the Back button.
 *
 * Optional extras for the real sign-in: [busy] greys the buttons and shows "Signing in...",
 * [signInLabel] is "Try again" after an error, [status] is the error line, [detail] a small
 * technical line, and [onAddAccount] shows an "Add account" button.
 */
@Composable
fun GoldSignInDialog(
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    signInLabel: String = "Sign in with Google",
    busy: Boolean = false,
    status: String? = null,
    detail: String? = null,
    onAddAccount: (() -> Unit)? = null
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
                text = if (busy) "Signing in..." else signInLabel,
                onClick = onSignIn,
                tone = ChunkyTones.White,
                enabled = !busy,
                leading = { GoogleMark() }
            )
            if (status != null) {
                BasicText(
                    status,
                    modifier = Modifier.fillMaxWidth(),
                    style = bodyStyle(14.sp, FontWeight.Medium, GoldTheme.ErrorText, TextAlign.Center)
                )
            }
            if (detail != null) {
                BasicText(
                    detail,
                    modifier = Modifier.fillMaxWidth(),
                    style = bodyStyle(12.sp, FontWeight.Medium, GoldTheme.Muted, TextAlign.Center)
                )
            }
            if (onAddAccount != null) {
                ChunkyButton("Add account", onAddAccount, tone = ChunkyTones.Blue)
            }
            ChunkyButton("Cancel", onCancel, tone = ChunkyTones.Navy, enabled = !busy)
        }
    }
}
