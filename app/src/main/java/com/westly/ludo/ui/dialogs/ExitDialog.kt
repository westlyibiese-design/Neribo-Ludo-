package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Exit" confirmation. [onKeepPlaying] is also called by the X button and the Back button.
 * Both buttons have text labels.
 */
@Composable
fun GoldExitDialog(
    onKeepPlaying: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    GoldDialog(
        title = "Exit",
        badgeIcon = LudoIcon.Exit,
        badgeTone = ChunkyTones.Red,
        onClose = onKeepPlaying,
        modifier = modifier
    ) {
        InfoPanel {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    "Are you sure you want to exit the game?",
                    style = bodyStyle(17.sp, FontWeight.SemiBold, GoldTheme.Cream, TextAlign.Center, 24.sp)
                )
            }
        }
        DialogButtons {
            ChunkyButton("Keep playing", onKeepPlaying, tone = ChunkyTones.Green)
            ChunkyButton("Exit game", onExit, tone = ChunkyTones.Red)
        }
    }
}
