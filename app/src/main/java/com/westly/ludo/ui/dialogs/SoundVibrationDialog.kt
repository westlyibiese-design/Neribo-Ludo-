package com.westly.ludo.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "Sound & vibration": two On / Off toggles with icons (the HTML uses the same phone icon for
 * both Vibration choices).
 *
 * Holds no state: pass [soundOn] / [vibrationOn] in and save the change in the callbacks
 * (settings.chooseSound(it), settings.chooseVibration(it)). The existing GameSettings already
 * passes vibration on to the real phone vibrator (through Sounds.vibrationOn), so nothing extra
 * is needed here. Changes apply straight away; [onDone], X and Back just close.
 */
@Composable
fun GoldSoundVibrationDialog(
    soundOn: Boolean,
    vibrationOn: Boolean,
    onSound: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    GoldDialog(
        title = "Sound & vibration",
        badgeIcon = LudoIcon.Sound,
        badgeTone = ChunkyTones.Yellow,
        onClose = onDone,
        modifier = modifier
    ) {
        DialogSectionLabel("Sound")
        SegmentedToggle(
            options = listOf(ToggleOption("On", LudoIcon.Sound), ToggleOption("Off", LudoIcon.Mute)),
            selectedIndex = if (soundOn) 0 else 1,
            onSelect = { onSound(it == 0) }
        )

        DialogSectionLabel("Vibration", topGap = 16.dp)
        SegmentedToggle(
            options = listOf(ToggleOption("On", LudoIcon.Vibrate), ToggleOption("Off", LudoIcon.Vibrate)),
            selectedIndex = if (vibrationOn) 0 else 1,
            onSelect = { onVibration(it == 0) }
        )

        DialogButtons {
            ChunkyButton("Done", onDone, tone = ChunkyTones.Gold)
        }
    }
}
