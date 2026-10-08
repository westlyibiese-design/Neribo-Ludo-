package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.ludo.ui.BoardThemes

/**
 * Small gold heading above a group of choices ("Board type", "Computer speed"...): centred,
 * 15sp bold gold. [topGap] is the space above it: 0 for the first heading under the title
 * (the title already leaves 18dp) and 16dp between groups.
 */
@Composable
internal fun DialogSectionLabel(text: String, modifier: Modifier = Modifier, topGap: Dp = 0.dp) {
    BasicText(
        text,
        modifier = modifier.fillMaxWidth().padding(top = topGap, bottom = 8.dp),
        style = bodyStyle(15.sp, FontWeight.Bold, GoldTheme.Gold, TextAlign.Center, 18.sp)
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
        badgeIcon = LudoIcon.Gear,
        badgeTone = ChunkyTones.Yellow,
        onClose = onDone,
        modifier = modifier
    ) {
        DialogSectionLabel("Board type")
        ChoiceFlow(BoardThemes.names, boardType, onBoardType)
        Spacer(Modifier.height(12.dp))
        BoardTilePreview(boardType)

        DialogSectionLabel("Computer speed", topGap = 16.dp)
        ChoiceFlow(listOf("Slow", "Normal", "Fast"), speed, onSpeed)

        DialogSectionLabel("Computer level", topGap = 16.dp)
        ChoiceFlow(listOf("Easy", "Normal"), level, onLevel)

        DialogButtons {
            ChunkyButton("Done", onDone, tone = ChunkyTones.Gold)
        }
    }
}

/** Pill choices that wrap onto new lines and stay centred (8dp gaps), like the HTML ".seg". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceFlow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { i, label ->
            OptionChip(label, selected = selected == i, onClick = { onSelect(i) })
        }
    }
}

/**
 * The four yard tiles of the chosen board (green, yellow, red, blue): square, 14dp corners, 2dp
 * gold border, 8dp gaps. Classic uses plain colours with the HTML's soft highlight; the other
 * boards use the same yard pictures the real board draws.
 */
@Composable
private fun BoardTilePreview(boardType: Int) {
    val ids = BoardThemes.images(boardType)
    val classic = listOf(Color(0xFF2E9B4E), Color(0xFFE0A424), Color(0xFFD6362F), Color(0xFF2D74D5))
    val names = listOf("green", "yellow", "red", "blue")
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Preview of the ${BoardThemes.names.getOrElse(boardType) { "Classic" }} board" },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (i in 0..3) {
            val tile = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .clip(shape)
            if (ids.isEmpty()) {
                Box(
                    tile
                        .background(classic[i])
                        .drawBehind {
                            // radial-gradient(circle at 30% 25%, #fff6, transparent 45%)
                            drawRect(
                                Brush.radialGradient(
                                    colors = listOf(Color(0x66FFFFFF), Color(0x00FFFFFF)),
                                    center = Offset(this.size.width * 0.30f, this.size.height * 0.25f),
                                    radius = this.size.width * 0.46f
                                )
                            )
                        }
                        .border(2.dp, GoldTheme.Gold, shape)
                )
            } else {
                Image(
                    painter = painterResource(ids[i]),
                    contentDescription = "${names[i]} house",
                    modifier = tile.border(2.dp, GoldTheme.Gold, shape),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}
