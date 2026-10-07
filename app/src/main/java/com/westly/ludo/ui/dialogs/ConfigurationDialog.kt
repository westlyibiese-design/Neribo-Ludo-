package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.ludo.ui.BoardThemes
import com.westly.ludo.ui.Palette

/** Small muted heading above a group of chips ("Board type", "Computer speed"...). */
@Composable
internal fun DialogSectionLabel(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        style = bodyStyle(14.sp, FontWeight.Bold, GoldTheme.Muted)
    )
}

/**
 * "Configuration": board type, computer speed, computer level.
 *
 * This dialog holds no state of its own. Pass the current choices in and save the new choice in
 * the callbacks (for example settings.chooseBoard(it)). The choices apply straight away, so
 * [onDone], the X button and the Back button all just close the dialog.
 *
 * boardType: 0 Classic, 1 Afrobeats, 2 Super Eagles, 3 Adventure, 4 Kingdoms.
 * speed: 0 Slow, 1 Normal, 2 Fast. level: 0 Easy, 1 Normal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoldConfigurationDialog(
    boardType: Int,
    speed: Int,
    level: Int,
    onBoardType: (Int) -> Unit,
    onSpeed: (Int) -> Unit,
    onLevel: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    GoldDialog(
        title = "Configuration",
        badgeIcon = LudoIcon.Sliders,
        badgeTone = ChunkyTones.Yellow,
        onClose = onDone,
        modifier = modifier
    ) {
        DialogSectionLabel("Board type")
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BoardThemes.names.forEachIndexed { i, name ->
                OptionChip(name, selected = boardType == i, onClick = { onBoardType(i) })
            }
        }
        Spacer(Modifier.height(12.dp))
        BoardTilePreview(boardType)

        Spacer(Modifier.height(18.dp))
        DialogSectionLabel("Computer speed")
        ChoiceRow(listOf("Slow", "Normal", "Fast"), speed, onSpeed)

        Spacer(Modifier.height(18.dp))
        DialogSectionLabel("Computer level")
        ChoiceRow(listOf("Easy", "Normal"), level, onLevel)

        DialogButtons {
            ChunkyButton("Done", onDone, tone = ChunkyTones.Gold)
        }
    }
}

/** Equal-width chips in one row. */
@Composable
private fun ChoiceRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            OptionChip(label, selected = selected == i, onClick = { onSelect(i) }, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The four yard tiles of the chosen board (green, yellow, red, blue). Classic uses plain colours;
 * the other boards use the same yard pictures the real board draws.
 */
@Composable
private fun BoardTilePreview(boardType: Int) {
    val ids = BoardThemes.images(boardType)
    val classic = listOf(Palette.Green, Palette.Yellow, Palette.Red, Palette.Blue)
    val names = listOf("green", "yellow", "red", "blue")
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Preview of the ${BoardThemes.names.getOrElse(boardType) { "Classic" }} board" },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (i in 0..3) {
            val tile = Modifier
                .weight(1f)
                .height(52.dp)
                .clip(shape)
                .border(1.5.dp, Color(0x40FFFFFF), shape)
            if (ids.isEmpty()) {
                Box(tile.background(Brush.verticalGradient(listOf(classic[i].light, classic[i].base))))
            } else {
                Image(
                    painter = painterResource(ids[i]),
                    contentDescription = "${names[i]} house",
                    modifier = tile,
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}
