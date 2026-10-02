package com.westly.ludo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.westly.ludo.R
import com.westly.ludo.game.GameSettings
import com.westly.ludo.game.LudoGame
import com.westly.ludo.game.PlayerNames
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Ludo Mate home, Settings (visual only) and Game Mode screens.

@Composable
private fun Dp.sp(): TextUnit = with(LocalDensity.current) { this@sp.toSp() }

private fun menuText(size: TextUnit, color: Color = Color.White) = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = FontWeight.ExtraBold,
    textAlign = TextAlign.Center,
    shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 3f), 5f)
)

@Composable
private fun MenuBackground(image: Int, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.WoodLight, Palette.WoodDark)))
    ) {
        // The picture fills the whole screen (also behind the system bars); the menu sits on top.
        Image(
            painter = painterResource(image),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(bottom = FooterSpace)) { content() }
    }
}

/** Big glossy rounded button. [onClick] null = shown but inactive. */
@Composable
private fun GlossButton(
    text: String,
    swatch: Swatch,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(50)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "btnScale")
    Box(
        modifier
            .width(width)
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (dimmed) 0.55f else 1f)
            .background(Brush.verticalGradient(listOf(swatch.light, swatch.base, swatch.dark)), shape)
            .border(height * 0.04f, swatch.dark.shade(0.7f), shape)
            .drawBehind {
                drawRoundRect(
                    Color.White.copy(alpha = 0.22f),
                    Offset(size.width * 0.05f, size.height * 0.07f),
                    Size(size.width * 0.90f, size.height * 0.40f),
                    CornerRadius(size.height * 0.3f)
                )
            }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = onClick != null
            ) { onClick?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text,
                style = menuText((height * (if (subtitle == null) 0.42f else 0.34f)).sp()),
                maxLines = 1,
                softWrap = false
            )
            if (subtitle != null) {
                BasicText(
                    subtitle,
                    style = menuText((height * 0.2f).sp(), Color.White.copy(alpha = 0.9f)),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/** Google "G" mark. Visual only: it has no click handler and signs nobody in. */
@Composable
private fun GoogleGIcon(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s / 2f, s / 2f)
        drawCircle(Color.Black.copy(alpha = 0.35f), s / 2f, c + Offset(0f, s * 0.03f))
        drawCircle(Color.White, s / 2f, c)
        val sw = s * 0.12f
        val r = s * 0.30f
        val tl = Offset(c.x - r, c.y - r)
        val box = Size(r * 2f, r * 2f)
        val red = Color(0xFFEA4335)
        val yellow = Color(0xFFFBBC05)
        val green = Color(0xFF34A853)
        val blue = Color(0xFF4285F4)
        drawArc(red, 215f, 100f, false, tl, box, style = Stroke(sw))
        drawArc(yellow, 135f, 80f, false, tl, box, style = Stroke(sw))
        drawArc(green, 45f, 90f, false, tl, box, style = Stroke(sw))
        drawArc(blue, 0f, 45f, false, tl, box, style = Stroke(sw))
        drawLine(blue, Offset(c.x, c.y), Offset(c.x + r + sw / 2f, c.y), strokeWidth = sw)
    }
}

/** "Ludo Mate" title with four pawns above it. */
@Composable
private fun LudoMateLogo(u: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(u * 56f).height(u * 17f)) {
            val colors = listOf(Palette.Green, Palette.Yellow, Palette.Blue, Palette.Red)
            val r = size.height * 0.2f
            val step = size.width / 4f
            for (i in 0 until 4) {
                drawPawn(Offset(step * (i + 0.5f), size.height * 0.5f), r, colors[i])
            }
        }
        Spacer(Modifier.height(u * 1.5f))
        val outlinePx = with(LocalDensity.current) { (u * 1.3f).toPx() }
        Box(contentAlignment = Alignment.Center) {
            // Dark brown outline behind the gold letters, so the title reads on any background.
            BasicText(
                "Ludo Mate",
                style = TextStyle(
                    color = Color(0xFF3F2916),
                    fontSize = (u * 14.5f).sp(),
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    drawStyle = Stroke(width = outlinePx, join = StrokeJoin.Round)
                ),
                maxLines = 1,
                softWrap = false
            )
            BasicText(
                "Ludo Mate",
                style = TextStyle(
                    brush = Brush.verticalGradient(listOf(Color(0xFFFFF3C4), Color(0xFFFFB92E))),
                    fontSize = (u * 14.5f).sp(),
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 6f), 10f)
                ),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
fun HomeScreen(onSettings: () -> Unit, onGame: () -> Unit) {
    MenuBackground(R.drawable.bg_home) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 185f)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(u * 3f)) {
                    GoogleGIcon(u * 11f)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LudoMateLogo(u)
                }
                GlossButton("Game", Palette.Green, u * 72f, u * 17f, onClick = onGame)
                Spacer(Modifier.height(u * 4f))
                GlossButton("Settings", Palette.Orange, u * 72f, u * 17f, onClick = onSettings)
                Spacer(Modifier.height(u * 10f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings: Settings (sound & vibration), Rules, Configuration, Change Names.
// ---------------------------------------------------------------------------

private fun DrawScope.gearIcon(c: Offset, r: Float) {
    drawCircle(Color.White, r * 0.5f, c, style = Stroke(r * 0.22f))
    for (i in 0 until 8) {
        val a = (i * 45f) * (PI.toFloat() / 180f)
        drawLine(
            Color.White,
            Offset(c.x + cos(a) * r * 0.55f, c.y + sin(a) * r * 0.55f),
            Offset(c.x + cos(a) * r * 0.8f, c.y + sin(a) * r * 0.8f),
            strokeWidth = r * 0.2f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.rulesIcon(c: Offset, r: Float) {
    drawRoundRect(Color.White, Offset(c.x - r * 0.5f, c.y - r * 0.65f), Size(r, r * 1.3f), CornerRadius(r * 0.15f))
    for (i in -1..1) {
        drawLine(
            Color(0xFF555555),
            Offset(c.x - r * 0.28f, c.y + i * r * 0.32f),
            Offset(c.x + r * 0.28f, c.y + i * r * 0.32f),
            strokeWidth = r * 0.1f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.configIcon(c: Offset, r: Float) {
    val knobs = listOf(-0.25f, 0.2f, -0.05f)
    for (i in -1..1) {
        val y = c.y + i * r * 0.42f
        drawLine(Color.White, Offset(c.x - r * 0.65f, y), Offset(c.x + r * 0.65f, y), strokeWidth = r * 0.12f, cap = StrokeCap.Round)
        drawCircle(Color.White, r * 0.15f, Offset(c.x + knobs[i + 1] * r * 1.3f, y))
    }
}

private fun DrawScope.nameIcon(c: Offset, r: Float) {
    drawCircle(Color.White, r * 0.26f, Offset(c.x, c.y - r * 0.25f))
    drawArc(
        Color.White, 180f, 180f, true,
        Offset(c.x - r * 0.5f, c.y + r * 0.05f), Size(r, r * 0.9f)
    )
}

@Composable
private fun SettingsRow(
    label: String,
    swatch: Swatch,
    u: Dp,
    onClick: (() -> Unit)? = null,
    icon: DrawScope.(Offset, Float) -> Unit
) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .width(u * 78f)
            .height(u * 16f)
            .background(Brush.verticalGradient(listOf(Palette.PillLight, Palette.PillDark)), shape)
            .border(1.dp, Palette.PillEdge, shape)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
    ) {
        Canvas(Modifier.align(Alignment.CenterStart).padding(start = u * 2f).size(u * 12f)) {
            val r = size.minDimension / 2f * 0.94f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawGlossyDisc(c, r, swatch)
            icon(c, r)
        }
        BasicText(
            label,
            style = menuText((u * 6.4f).sp()).copy(textAlign = TextAlign.Start),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = u * 17f)
        )
    }
}

@Composable
fun SettingsScreen(
    names: PlayerNames,
    onSound: () -> Unit,
    onRules: () -> Unit,
    onConfiguration: () -> Unit,
    onClose: () -> Unit
) {
    var namesOpen by remember { mutableStateOf(false) }
    // Soft entrance: fade and grow a little.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(320)) }

    Box(Modifier.fillMaxSize()) {
    MenuBackground(R.drawable.bg_settings) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 160f)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = enter.value
                        val sc = 0.94f + 0.06f * enter.value
                        scaleX = sc
                        scaleY = sc
                    }
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = u * 3f)) {
                        GlossButton(
                            "Settings", Palette.Orange, u * 56f, u * 14f,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        ExitButton(
                            u * 11.8f,
                            Modifier.align(Alignment.CenterEnd).clickable { onClose() }
                        )
                    }
                    Spacer(Modifier.height(u * 8f))
                    SettingsRow("Settings", Palette.Blue, u, onClick = onSound) { c, r -> gearIcon(c, r) }
                    Spacer(Modifier.height(u * 4f))
                    SettingsRow("Rules", Palette.Green, u, onClick = onRules) { c, r -> rulesIcon(c, r) }
                    Spacer(Modifier.height(u * 4f))
                    SettingsRow("Configuration", Palette.Yellow, u, onClick = onConfiguration) { c, r -> configIcon(c, r) }
                    Spacer(Modifier.height(u * 4f))
                    SettingsRow("Change Names", Palette.Red, u, onClick = { namesOpen = true }) { c, r -> nameIcon(c, r) }
                }
            }
        }
    }
    if (namesOpen) ChangeNamesDialog(names, onClose = { namesOpen = false })
    }
}

// ---------------------------------------------------------------------------
// Configuration: board type, computer speed, computer level
// ---------------------------------------------------------------------------

@Composable
private fun OptionChip(text: String, selected: Boolean, u: Dp, width: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val fill = if (selected) {
        Brush.verticalGradient(listOf(Palette.Green.light, Palette.Green.base, Palette.Green.dark))
    } else {
        Brush.verticalGradient(listOf(Palette.PillLight, Palette.PillDark))
    }
    Box(
        Modifier
            .width(width)
            .height(u * 8f)
            .background(fill, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Palette.PillEdge, shape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        BasicText(text, style = menuText((u * 3.8f).sp()), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SectionLabel(text: String, u: Dp) {
    BasicText(
        text,
        style = menuText((u * 4.2f).sp(), Color.White.copy(alpha = 0.9f)),
        maxLines = 1,
        softWrap = false
    )
    Spacer(Modifier.height(u * 1.5f))
}

/** Small 2x2 preview of the four houses for the chosen board type. */
@Composable
private fun ThemePreview(type: Int, u: Dp) {
    val ids = BoardThemes.images(type)
    val classic = listOf(Palette.Green, Palette.Yellow, Palette.Red, Palette.Blue)
    val shape = RoundedCornerShape(u * 1.5f)
    Column(verticalArrangement = Arrangement.spacedBy(u * 1f)) {
        for (r in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(u * 1f)) {
                for (c in 0..1) {
                    val i = r * 2 + c
                    val box = Modifier.size(u * 14f).clip(shape).border(1.dp, Palette.PillEdge, shape)
                    if (ids.isEmpty()) {
                        Box(box.background(Brush.verticalGradient(listOf(classic[i].light, classic[i].base))))
                    } else {
                        Image(
                            painter = painterResource(ids[i]),
                            contentDescription = null,
                            modifier = box,
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigurationScreen(settings: GameSettings, onClose: () -> Unit) {
    MenuBackground(R.drawable.bg_settings) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 150f)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = u * 3f)) {
                    GlossButton(
                        "Configuration", Palette.Orange, u * 56f, u * 14f,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    ExitButton(
                        u * 11.8f,
                        Modifier.align(Alignment.CenterEnd).clickable { onClose() }
                    )
                }
                Spacer(Modifier.height(u * 4f))

                SectionLabel("Board Type", u)
                Row(horizontalArrangement = Arrangement.spacedBy(u * 2f)) {
                    for (i in 0..2) {
                        OptionChip(BoardThemes.names[i], settings.boardType == i, u, u * 27f) { settings.chooseBoard(i) }
                    }
                }
                Spacer(Modifier.height(u * 2f))
                Row(horizontalArrangement = Arrangement.spacedBy(u * 2f)) {
                    for (i in 3..4) {
                        OptionChip(BoardThemes.names[i], settings.boardType == i, u, u * 27f) { settings.chooseBoard(i) }
                    }
                }
                Spacer(Modifier.height(u * 2.5f))
                ThemePreview(settings.boardType, u)
                Spacer(Modifier.height(u * 3f))

                SectionLabel("Computer Speed", u)
                Row(horizontalArrangement = Arrangement.spacedBy(u * 2f)) {
                    val labels = listOf("Slow", "Normal", "Fast")
                    for (i in 0..2) {
                        OptionChip(labels[i], settings.speed == i, u, u * 27f) { settings.chooseSpeed(i) }
                    }
                }
                Spacer(Modifier.height(u * 3f))

                SectionLabel("Computer Level", u)
                Row(horizontalArrangement = Arrangement.spacedBy(u * 2f)) {
                    val labels = listOf("Easy", "Normal")
                    for (i in 0..1) {
                        OptionChip(labels[i], settings.level == i, u, u * 27f) { settings.chooseLevel(i) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sound & Vibration
// ---------------------------------------------------------------------------

@Composable
fun SoundVibrationScreen(settings: GameSettings, onClose: () -> Unit) {
    MenuBackground(R.drawable.bg_settings) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 150f)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = u * 3f)) {
                    GlossButton(
                        "Sound & Vibration", Palette.Orange, u * 68f, u * 14f,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    ExitButton(
                        u * 11.8f,
                        Modifier.align(Alignment.CenterEnd).clickable { onClose() }
                    )
                }
                Spacer(Modifier.height(u * 8f))

                SectionLabel("Sound", u)
                Row(horizontalArrangement = Arrangement.spacedBy(u * 2f)) {
                    OptionChip("On", settings.soundOn, u, u * 27f) { settings.chooseSound(true) }
                    OptionChip("Off", !settings.soundOn, u, u * 27f) { settings.chooseSound(false) }
                }
                Spacer(Modifier.height(u * 5f))

                SectionLabel("Vibration", u)
                Row(horizontalArrangement = Arrangement.spacedBy(u * 2f)) {
                    OptionChip("On", settings.vibrationOn, u, u * 27f) { settings.chooseVibration(true) }
                    OptionChip("Off", !settings.vibrationOn, u, u * 27f) { settings.chooseVibration(false) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// How to Play (Rules). Every line below describes what the game code really does.
// ---------------------------------------------------------------------------

private val RulesText: List<Pair<String, String>> = listOf(
    "Rolling" to "Tap the dice in the middle of the board to roll. You roll two dice. Under the board " +
        "you get three circles: blue is the first die, green is the second die, and red is both dice added together.",
    "Moving" to "Tap a circle, then tap a glowing seed to move it that many steps. If two or more of your seeds " +
        "stand on the same spot, you choose which one moves. A seed can never go past the center.",
    "Leaving the house" to "You need a 6 on one die to bring a seed out of its house. Use the blue or the green " +
        "circle for it. The red total cannot bring a seed out.",
    "Another roll" to "Only a double six (6 and 6) gives you another roll. A single 6 does not.",
    "Capturing" to "If your seed stops on a square where an opponent seed stands, that opponent seed goes back to " +
        "its house and needs a new 6. There are no safe squares, so this can happen anywhere on the path. " +
        "If several opponent seeds stand there, you choose which one to capture. " +
        "If one die would put your seed on an opponent, but no other seed of yours can use the second die, " +
        "your seed must carry on and use the second die too.",
    "Winning seeds" to "A seed is a winning seed when it reaches the center, or when it captures an opponent seed. " +
        "A capturing seed leaves the board and counts as a winning seed.",
    "You & Computer" to "You play two colors, yellow and red (8 seeds). The computer plays green and blue. " +
        "Get all 8 of your seeds out as winning seeds to win the round and score 1 point.",
    "Tournament" to "You play red against 3 computers. Each player has one color and 4 seeds.\n\n" +
        "A round ends when one player gets all 4 seeds out. That player is safe and gets no point.\n\n" +
        "One player is knocked out each round. It is the player with the fewest seeds out. " +
        "If players are tied, the one whose seeds travelled the least goes out. " +
        "If they are still tied, they each roll a die: the lowest roll goes out, and a tie for lowest rolls again.\n\n" +
        "Round 1 has 4 players, Round 2 has 3 players, and Round 3 is the Final with 2 players. " +
        "Every round starts fresh with all seeds in their houses.\n\n" +
        "In the Final, the first player to get all 4 seeds out wins the tournament and gets 1 point.\n\n" +
        "If you are knocked out, you can Watch the computers finish (tap Skip to speed them up) or Leave. " +
        "If you leave, the player who won that round gets the point. " +
        "If you resign from the menu, you are knocked out at once, and if you leave, the player in front gets the point.",
    "Family" to "Family is for people playing together on one phone, with no computers. Choose 2, 3 or 4 players, " +
        "then type each player's name. These names are only used for that Family game. " +
        "The top of the screen tells you whose turn it is: Pass the phone to that player.\n\n" +
        "2 players: the You & Computer rules, but two people play. Player 1 plays yellow and red (8 seeds), " +
        "Player 2 plays green and blue. Get all 8 seeds out as winning seeds to win the round and score 1 point.\n\n" +
        "3 or 4 players: the Tournament rules, but everybody is a person. The colors are fixed: " +
        "Player 1 red, Player 2 green, Player 3 yellow, Player 4 blue (a 3 player game uses red, green and yellow). " +
        "Each player has 4 seeds. A round ends when one player gets all 4 seeds out. That player is safe and gets no point. " +
        "The player with the fewest seeds out is knocked out. If players are tied, the one whose seeds travelled the least " +
        "goes out. If they are still tied, each tied player taps to roll a die in turn: the lowest roll goes out, " +
        "and a tie for lowest rolls again.\n\n" +
        "With 4 players, Round 1 has 4 players, Round 2 has 3 players and Round 3 is the Final with 2 players. " +
        "With 3 players, Round 1 has 3 players and Round 2 is the Final with 2 players. " +
        "The first player to get all 4 seeds out in the Final wins and gets 1 point.\n\n" +
        "A double six gives the same player another roll. Restart keeps the scores. " +
        "End Game (End Tournament with 3 or 4 players) clears the scores and starts Family again, " +
        "so you choose the number of players and type the names again.",
    "Tips" to "The blue menu button (top left) lets you change names, restart the round, resign or exit. " +
        "The orange X (top right) goes back to Game Mode. Your game is saved by itself, " +
        "so you can close the app and come back."
)

@Composable
fun RulesScreen(onClose: () -> Unit) {
    MenuBackground(R.drawable.bg_settings) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 150f)
            val shape = RoundedCornerShape(u * 4f)
            val headStyle = menuText((u * 4.4f).sp(), Palette.Orange.light).copy(textAlign = TextAlign.Start)
            val bodyStyle = TextStyle(
                color = Color.White,
                fontSize = (u * 3.8f).sp(),
                lineHeight = (u * 5.3f).sp(),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start
            )
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(u * 2f))
                Box(Modifier.fillMaxWidth().padding(horizontal = u * 3f)) {
                    GlossButton(
                        "How to Play", Palette.Orange, u * 56f, u * 14f,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    ExitButton(
                        u * 11.8f,
                        Modifier.align(Alignment.CenterEnd).clickable { onClose() }
                    )
                }
                Spacer(Modifier.height(u * 3f))
                Box(
                    Modifier
                        .weight(1f)
                        .width(u * 92f)
                        .background(Color.Black.copy(alpha = 0.5f), shape)
                        .border(1.dp, Palette.PillEdge, shape)
                        .padding(horizontal = u * 4f, vertical = u * 3f)
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        for ((head, body) in RulesText) {
                            BasicText(head, style = headStyle)
                            Spacer(Modifier.height(u * 1f))
                            BasicText(body, style = bodyStyle)
                            Spacer(Modifier.height(u * 3.5f))
                        }
                    }
                }
                Spacer(Modifier.height(u * 1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Game mode selection
// ---------------------------------------------------------------------------

@Composable
fun GameModeScreen(
    onBack: () -> Unit,
    onYouAndComputer: () -> Unit,
    onTournament: () -> Unit,
    onFamily: () -> Unit = {},
    familyActive: Boolean = false
) {
    MenuBackground(R.drawable.bg_modes) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 170f)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = u * 3f)) {
                    GlossButton(
                        "Game Mode", Palette.Green, u * 56f, u * 14f,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    ExitButton(
                        u * 11.8f,
                        Modifier.align(Alignment.CenterEnd).clickable { onBack() }
                    )
                }
                Spacer(Modifier.height(u * 8f))
                GlossButton("You & Computer", Palette.Blue, u * 78f, u * 18f, onClick = onYouAndComputer)
                Spacer(Modifier.height(u * 4f))
                GlossButton(
                    "Tournament", Palette.Orange, u * 78f, u * 18f,
                    subtitle = "4 players", onClick = onTournament
                )
                Spacer(Modifier.height(u * 4f))
                GlossButton(
                    "Family", Palette.Green, u * 78f, u * 18f,
                    subtitle = if (familyActive) "Resume game" else "2, 3 or 4 players", onClick = onFamily
                )
                Spacer(Modifier.height(u * 4f))
                GlossButton("Connect and Play", Palette.Red, u * 78f, u * 18f, subtitle = "Coming soon", dimmed = true)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Family: choose 2 / 3 / 4 players, type their names, change names during the game.
// Humans only, one phone. These names are only for Family (not the Change Names in Settings).
// ---------------------------------------------------------------------------

/** The colour dots shown next to a Family player: 2 players own two colours each, 3 or 4 players one each. */
private fun familyDots(count: Int, index: Int): List<Color> = when {
    count == 2 && index == 0 -> listOf(Palette.Red.base, Palette.Yellow.base)
    count == 2 -> listOf(Palette.Green.base, Palette.Blue.base)
    else -> listOf(listOf(Palette.Red.base, Palette.Green.base, Palette.Yellow.base, Palette.Blue.base)[index])
}

@Composable
private fun FamilyNameRow(u: Dp, dots: List<Color>, value: String, hint: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .width(u * 84f)
            .height(u * 12.5f)
            .background(Brush.verticalGradient(listOf(Color(0xFF14566A), Color(0xFF0B3340))), shape)
            .border(1.dp, Palette.PillEdge, shape)
            .padding(horizontal = u * 3.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (c in dots) {
            Box(Modifier.size(u * 5.6f).background(c, CircleShape).border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape))
            Spacer(Modifier.width(u * 1.2f))
        }
        Spacer(Modifier.width(u * 1.5f))
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(10)) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = (u * 5.4f).sp(), fontWeight = FontWeight.ExtraBold),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        BasicText(
                            hint,
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.35f),
                                fontSize = (u * 5.4f).sp(),
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                    }
                    inner()
                }
            }
        )
    }
}

/** Family step 1: how many people are playing. */
@Composable
fun FamilyCountScreen(onBack: () -> Unit, onPick: (Int) -> Unit) {
    MenuBackground(R.drawable.bg_modes) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 170f)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = u * 3f)) {
                    GlossButton(
                        "Family", Palette.Green, u * 56f, u * 14f,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    ExitButton(
                        u * 11.8f,
                        Modifier.align(Alignment.CenterEnd).clickable { onBack() }
                    )
                }
                Spacer(Modifier.height(u * 8f))
                GlossButton("2 Players", Palette.Blue, u * 78f, u * 18f, subtitle = "2 colors each", onClick = { onPick(2) })
                Spacer(Modifier.height(u * 4f))
                GlossButton("3 Players", Palette.Orange, u * 78f, u * 18f, subtitle = "Knock-out rounds", onClick = { onPick(3) })
                Spacer(Modifier.height(u * 4f))
                GlossButton("4 Players", Palette.Red, u * 78f, u * 18f, subtitle = "Knock-out rounds", onClick = { onPick(4) })
            }
        }
    }
}

/** Family step 2: type the names of the [count] players, then start. */
@Composable
fun FamilyNamesScreen(count: Int, onBack: () -> Unit, onStart: (List<String>) -> Unit) {
    val edits = remember(count) { mutableStateListOf<String>().apply { repeat(count) { add("") } } }
    MenuBackground(R.drawable.bg_modes) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val u = minOf(maxWidth / 100f, maxHeight / 170f)
            Column(
                Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(u * 8f))
                Box(Modifier.fillMaxWidth().padding(horizontal = u * 3f)) {
                    GlossButton(
                        "Names", Palette.Green, u * 56f, u * 14f,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    ExitButton(
                        u * 11.8f,
                        Modifier.align(Alignment.CenterEnd).clickable { onBack() }
                    )
                }
                Spacer(Modifier.height(u * 8f))
                for (i in 0 until count) {
                    FamilyNameRow(u, familyDots(count, i), edits[i], "Player ${i + 1}") { edits[i] = it }
                    Spacer(Modifier.height(u * 3f))
                }
                Spacer(Modifier.height(u * 5f))
                GlossButton("Start", Palette.Green, u * 56f, u * 14f, onClick = { onStart(edits.toList()) })
                Spacer(Modifier.height(u * 6f))
            }
        }
    }
}

/** Menu > Change Names during a Family game. Only the Family names change. */
@Composable
fun FamilyNamesDialog(game: LudoGame, onClose: () -> Unit) {
    BackHandler(true) { onClose() }
    val count = game.familyPlayers
    val edits = remember { mutableStateListOf<String>().apply { for (i in 0 until count) add(game.familyName(i)) } }
    val block = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(interactionSource = block, indication = null) { }
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val u = minOf(maxWidth / 100f, maxHeight / 150f)
            val shape = RoundedCornerShape(u * 5f)
            Column(
                Modifier
                    .width(u * 92f)
                    .background(Color(0xFF4A2C12), shape)
                    .border(2.dp, Palette.PillEdge, shape)
                    .padding(vertical = u * 4f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = u * 4f)) {
                    BasicText(
                        "Change Names",
                        style = menuText((u * 6f).sp()),
                        modifier = Modifier.align(Alignment.Center)
                    )
                    ExitButton(
                        u * 9f,
                        Modifier.align(Alignment.CenterEnd).clickable { onClose() }
                    )
                }
                Spacer(Modifier.height(u * 4f))
                for (i in 0 until count) {
                    FamilyNameRow(u, familyDots(count, i), edits[i], "Player ${i + 1}") { edits[i] = it }
                    Spacer(Modifier.height(u * 2.5f))
                }
                Spacer(Modifier.height(u * 3f))
                GlossButton("Save", Palette.Green, u * 50f, u * 12f, onClick = {
                    game.setFamilyNames(edits.toList())
                    onClose()
                })
            }
        }
    }
}
