package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Game settings" menu: four full-width rows. Each row only calls its callback; the caller opens
 * the matching dialog. [onClose] is called by the X button and the Back button.
 */
@Composable
fun GoldGameSettingsDialog(
    onSettings: () -> Unit,
    onRules: () -> Unit,
    onConfiguration: () -> Unit,
    onChangeNames: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    GoldDialog(
        title = "Game settings",
        badgeIcon = LudoIcon.Gear,
        badgeTone = ChunkyTones.Gold,
        onClose = onClose,
        modifier = modifier
    ) {
        InfoPanel {
            SettingsRow("Settings", LudoIcon.Gear, ChunkyTones.Blue, onSettings, topDivider = false)
            SettingsRow("Rules", LudoIcon.Book, ChunkyTones.Green, onRules, topDivider = true)
            SettingsRow("Configuration", LudoIcon.Sliders, ChunkyTones.Yellow, onConfiguration, topDivider = true)
            SettingsRow("Change names", LudoIcon.Pen, ChunkyTones.Red, onChangeNames, topDivider = true)
        }
    }
}

/** Round coloured icon, label, chevron. The whole row is the tap area (56dp tall at least). */
@Composable
private fun SettingsRow(
    label: String,
    icon: LudoIcon,
    tone: ChunkyTone,
    onClick: () -> Unit,
    topDivider: Boolean
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    if (topDivider) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(GoldTheme.Line))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) Color(0x0FFFFFFF) else Color.Transparent)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RoundIconBadge(icon, tone)
        BasicText(
            label,
            modifier = Modifier.weight(1f),
            style = bodyStyle(17.sp, FontWeight.Bold, GoldTheme.Cream)
        )
        LudoIconView(LudoIcon.Chevron, iconSize = 22.dp, tint = GoldTheme.Muted)
    }
}
