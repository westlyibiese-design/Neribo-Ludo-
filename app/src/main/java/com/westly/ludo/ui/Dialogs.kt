package com.westly.ludo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
import com.westly.ludo.game.PlayerNames
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Winner page, game menu, resign confirmation and change-names box.
// Each one is a picture (the generated panel) with the real text and tap areas on top.
// Positions below are in pixels of the panel picture, scaled to the screen.
// ---------------------------------------------------------------------------

private class PanelSpec(val res: Int, val w: Int, val h: Int)

private val WinnerPanel = PanelSpec(R.drawable.dlg_winner, 729, 932)
private val MenuPanel = PanelSpec(R.drawable.dlg_menu, 668, 764)
private val ResignPanel = PanelSpec(R.drawable.dlg_resign, 650, 604)
private val NamesPanel = PanelSpec(R.drawable.dlg_names, 668, 762)
private val OutPanel = PanelSpec(R.drawable.dlg_out, 751, 844)
private val TiePanel = PanelSpec(R.drawable.dlg_tiebreak, 730, 1198)
private val ResultPanel = PanelSpec(R.drawable.dlg_roundresult, 723, 1015)

@Composable
private fun Dp.toSp2(): TextUnit = with(LocalDensity.current) { this@toSp2.toSp() }

@Composable
private fun PanelText(
    text: String,
    k: Dp,
    px: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    maxChars: Int = 0,
    maxLines: Int = 1
) {
    val scale = if (maxChars > 0 && text.length > maxChars) maxChars.toFloat() / text.length else 1f
    BasicText(
        text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = (k * (px * scale)).toSp2(),
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 3f), 5f)
        ),
        maxLines = maxLines,
        softWrap = maxLines > 1
    )
}

/** A tap area on the panel picture, with a small press effect. */
@Composable
private fun HotSpot(
    k: Dp,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "spotScale")
    Box(
        Modifier
            .offset(k * x, k * y)
            .size(k * w, k * h)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Dimmed full-screen layer that blocks taps behind it. */
@Composable
private fun Scrim(onTap: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(interactionSource = source, indication = null) { onTap?.invoke() }
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Centers the panel picture and gives [content] the size of one picture pixel. */
@Composable
private fun Panel(spec: PanelSpec, widthFraction: Float, content: @Composable BoxScope.(Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val panelW = minOf(maxWidth * widthFraction, 440.dp, maxHeight * 0.92f * spec.w / spec.h)
        val k = panelW / spec.w
        Box(Modifier.size(k * spec.w, k * spec.h)) {
            Image(
                painter = painterResource(spec.res),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            this.content(k)
        }
    }
}

// ---------------------------------------------------------------------------
// Winner page
// ---------------------------------------------------------------------------

private val Confetti = listOf(
    Triple(0.17f, 0.17f, Palette.Yellow.base), Triple(0.09f, 0.23f, Palette.Green.base),
    Triple(0.85f, 0.20f, Palette.Red.base), Triple(0.93f, 0.25f, Palette.Blue.base),
    Triple(0.08f, 0.78f, Palette.Blue.base), Triple(0.18f, 0.81f, Palette.Yellow.base),
    Triple(0.83f, 0.79f, Palette.Red.base), Triple(0.91f, 0.83f, Palette.Green.base),
    Triple(0.50f, 0.10f, Palette.Green.base), Triple(0.55f, 0.88f, Palette.Yellow.base)
)

/**
 * Shown when a round is won (or someone resigns). The winner's name and the scores are on it.
 * [onNext] = new game, same players and mode. [onModes] = back to the Game Mode screen.
 */
@Composable
fun WinnerPage(
    winnerName: String,
    names: List<String>,
    scores: List<Int>,
    tournament: Boolean,
    onNext: () -> Unit,
    onModes: () -> Unit
) {
    BackHandler(true) { onModes() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.WoodLight, Palette.WoodDark)))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for ((fx, fy, color) in Confetti) {
                drawCircle(color, size.width * 0.012f, Offset(size.width * fx, size.height * fy))
            }
        }
        Panel(WinnerPanel, 0.88f) { k ->
            // Winner name
            Box(Modifier.offset(k * 59f, k * 316f).size(k * 612f, k * 137f), contentAlignment = Alignment.Center) {
                PanelText(winnerName, k, 64f, maxChars = 11)
            }
            // Scores (Tournament has four players, so each pill shows two of them)
            Box(Modifier.offset(k * 74f, k * 509f).size(k * 262f, k * 107f), contentAlignment = Alignment.Center) {
                if (tournament) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PanelText("${names[0]}: ${scores[0]}", k, 30f, maxChars = 11)
                        PanelText("${names[1]}: ${scores[1]}", k, 30f, maxChars = 11)
                    }
                } else {
                    PanelText("${names[0]}: ${scores[0]}", k, 40f, maxChars = 9)
                }
            }
            Box(Modifier.offset(k * 394f, k * 509f).size(k * 262f, k * 107f), contentAlignment = Alignment.Center) {
                if (tournament) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PanelText("${names[2]}: ${scores[2]}", k, 30f, maxChars = 11)
                        // A 3-player Family game has no fourth player.
                        if (names.size > 3) PanelText("${names[3]}: ${scores[3]}", k, 30f, maxChars = 11)
                    }
                } else {
                    PanelText("${names[1]}: ${scores[1]}", k, 40f, maxChars = 9)
                }
            }
            // Green tick = new game, red X = Game Mode screen
            HotSpot(k, 179f, 698f, 156f, 156f, onNext)
            HotSpot(k, 394f, 698f, 156f, 156f, onModes)
        }
    }
}

// ---------------------------------------------------------------------------
// Game menu
// ---------------------------------------------------------------------------

@Composable
fun MenuDialog(
    onChangeNames: () -> Unit,
    onRestart: () -> Unit,
    onResign: () -> Unit,
    onExit: () -> Unit,
    onClose: () -> Unit,
    resignEnabled: Boolean = true,
    resignLabel: String = "Resign"
) {
    BackHandler(true) { onClose() }
    Scrim {
        Panel(MenuPanel, 0.86f) { k ->
            val labels = listOf("Change Names", "Restart", resignLabel, "Exit")
            val noop: () -> Unit = {}
            val actions = listOf(onChangeNames, onRestart, onResign, onExit)
            val ys = listOf(132f, 276f, 420f, 564f)
            for (i in 0 until 4) {
                // Resign is dimmed and does nothing when it is not available (knocked out already).
                val off = i == 2 && !resignEnabled
                HotSpot(k, 88f, ys[i], 485f, 108f, if (off) noop else actions[i]) {
                    PanelText(labels[i], k, 46f, modifier = if (off) Modifier.alpha(0.35f) else Modifier)
                }
            }
            HotSpot(k, 582f, 6f, 80f, 80f, onClose)
        }
    }
}

// ---------------------------------------------------------------------------
// Resign confirmation
// ---------------------------------------------------------------------------

@Composable
fun ResignDialog(message: String, onConfirm: () -> Unit, onCancel: () -> Unit, title: String = "Resign") {
    BackHandler(true) { onCancel() }
    Scrim {
        Panel(ResignPanel, 0.86f) { k ->
            Box(Modifier.offset(k * 79f, k * 58f).size(k * 490f, k * 92f), contentAlignment = Alignment.Center) {
                PanelText(title, k, 50f, maxChars = 12)
            }
            Box(
                Modifier.offset(k * 79f, k * 190f).size(k * 490f, k * 180f),
                contentAlignment = Alignment.Center
            ) {
                PanelText(message, k, 36f, maxLines = 4, modifier = Modifier.width(k * 480f))
            }
            // Red X = cancel, green tick = resign
            HotSpot(k, 153f, 411f, 144f, 144f, onCancel)
            HotSpot(k, 352f, 411f, 144f, 144f, onConfirm)
        }
    }
}

// ---------------------------------------------------------------------------
// Change names
// ---------------------------------------------------------------------------

@Composable
fun ChangeNamesDialog(names: PlayerNames, onClose: () -> Unit) {
    BackHandler(true) { onClose() }
    val focus = LocalFocusManager.current
    val edits = remember {
        mutableStateListOf<String>().apply { for (i in 0 until PlayerNames.COUNT) add(names[i]) }
    }
    Scrim {
        Panel(NamesPanel, 0.86f) { k ->
            val ys = listOf(110f, 224f, 339f, 454f)
            for (i in 0 until PlayerNames.COUNT) {
                BasicTextField(
                    value = edits[i],
                    onValueChange = { edits[i] = it.take(PlayerNames.MAX_LENGTH) },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = (k * 40f).toSp2(),
                        fontWeight = FontWeight.ExtraBold
                    ),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.offset(k * 165f, k * ys[i]).size(k * 420f, k * 90f),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (edits[i].isEmpty()) {
                                BasicText(
                                    PlayerNames.DEFAULTS[i],
                                    style = TextStyle(
                                        color = Color.White.copy(alpha = 0.35f),
                                        fontSize = (k * 40f).toSp2(),
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            // Green tick = save, small red X = close without saving
            HotSpot(k, 269f, 589f, 130f, 130f, {
                for (i in 0 until PlayerNames.COUNT) names.set(i, edits[i])
                onClose()
            })
            HotSpot(k, 585f, 5f, 80f, 80f, onClose)
        }
    }
}

// ---------------------------------------------------------------------------
// Tournament: Round Result, Tie-Break and You're Out
// ---------------------------------------------------------------------------

/** Wraps a panel so it also stays clear of the © footer line. */
@Composable
private fun TournamentOverlay(content: @Composable BoxScope.() -> Unit) {
    Scrim {
        Box(Modifier.fillMaxSize().padding(bottom = FooterSpace), content = content)
    }
}

/**
 * Shown at the end of every Tournament round except the final.
 * [seeds] = seeds out per player index (0 = red/human ... 3 = blue), -1 = was not in this round.
 * [auto] = the human is only watching: Next Round happens by itself after a moment.
 */
@Composable
fun RoundResultDialog(
    title: String,
    outLine: String,
    names: List<String>,
    seeds: List<Int>,
    outPlayer: Int,
    auto: Boolean,
    fast: Boolean,
    onNext: () -> Unit
) {
    if (auto) {
        LaunchedEffect(Unit) {
            delay(if (fast) 300L else 2500L)
            onNext()
        }
    }
    TournamentOverlay {
        Panel(ResultPanel, 0.9f) { k ->
            // Title bar: round name and who is out
            Box(Modifier.offset(k * 62f, k * 47f).size(k * 601f, k * 136f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PanelText(title, k, 46f)
                    PanelText(outLine, k, 32f, color = Color(0xFFFFB4AB), maxChars = 26)
                }
            }
            // One row per player: red, green, yellow, blue
            val tops = listOf(233f, 376f, 520f, 664f)
            for (i in 0 until 4) {
                if (i >= names.size) continue   // a 3-player Family game has no fourth row
                val inRound = seeds.getOrElse(i) { -1 } >= 0
                val isOut = i == outPlayer
                val a = if (inRound) 1f else 0.4f
                val nm = names.getOrElse(i) { "" }
                Box(
                    Modifier.offset(k * 160f, k * (tops[i] + 10f)).size(k * 340f, k * 93f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    PanelText(
                        if (isOut) "$nm - OUT" else nm,
                        k, 40f,
                        color = if (isOut) Color(0xFFFF8A80) else Color.White,
                        maxChars = 13,
                        modifier = Modifier.alpha(a)
                    )
                }
                Box(
                    Modifier.offset(k * 512f, k * (tops[i] + 28f)).size(k * 133f, k * 60f),
                    contentAlignment = Alignment.Center
                ) {
                    PanelText(
                        if (inRound) "${seeds[i]}/4" else "OUT",
                        k, 36f,
                        modifier = Modifier.alpha(a)
                    )
                }
            }
            HotSpot(k, 63f, 840f, 598f, 120f, onNext) {
                PanelText("Next Round", k, 54f)
            }
        }
    }
}

/**
 * The dice roll-off. Cards are red, green, yellow, blue (player index 0..3).
 * Players not in the tie are dimmed. [rolls] 0 = not rolled yet.
 */
@Composable
fun TieBreakDialog(
    names: List<String>,
    tied: List<Int>,
    rolls: List<Int>,
    rolling: Int,
    flicker: Int,
    humanCanRoll: Boolean,
    onRoll: () -> Unit,
    tapPlayer: Int = 0,
    tapMessage: String? = null
) {
    val pending = tied.filter { rolls.getOrElse(it) { 0 } == 0 }
    val message = when {
        tied.isEmpty() -> ""
        pending.isEmpty() -> {
            val low = tied.minOf { rolls.getOrElse(it) { 0 } }
            val lows = tied.filter { rolls.getOrElse(it) { 0 } == low }
            if (lows.size == 1) "${names.getOrElse(lows[0]) { "" }} is OUT" else "Tie! Lowest roll again"
        }
        humanCanRoll -> tapMessage ?: "Tap your dice to roll"
        else -> "Highest roll stays. Lowest goes OUT."
    }
    TournamentOverlay {
        Panel(TiePanel, 0.9f) { k ->
            Box(Modifier.offset(k * 46f, k * 39f).size(k * 638f, k * 121f), contentAlignment = Alignment.Center) {
                PanelText("Tie-Break", k, 60f)
            }
            for (i in 0 until 4) {
                val left = if (i % 2 == 0) 38f else 378f
                val top = if (i / 2 == 0) 197f else 603f
                val cardH = if (i / 2 == 0) 378f else 383f
                val pillY = if (i / 2 == 0) 313f else 719f
                val diceY = if (i / 2 == 0) 376f else 787f
                if (i in tied) {
                    Box(
                        Modifier.offset(k * (left + 41f), k * pillY).size(k * 235f, k * 50f),
                        contentAlignment = Alignment.Center
                    ) {
                        PanelText(names.getOrElse(i) { "" }, k, 32f, maxChars = 9)
                    }
                    val v = rolls.getOrElse(i) { 0 }
                    val canTap = i == tapPlayer && humanCanRoll
                    Box(
                        Modifier.offset(k * (left + 64f), k * diceY).size(k * 189f, k * 168f),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            rolling == i -> PanelText(flicker.toString(), k, 120f)
                            v > 0 -> PanelText(v.toString(), k, 120f)
                            canTap -> PanelText("Tap", k, 60f)
                            else -> PanelText("?", k, 100f, color = Color.White.copy(alpha = 0.5f))
                        }
                    }
                    if (canTap) HotSpot(k, left, top, 316f, cardH, onRoll)
                } else {
                    // Not in the tie: the card is dimmed and empty.
                    Box(
                        Modifier
                            .offset(k * left, k * top)
                            .size(k * 316f, k * cardH)
                            .background(Color(0xDD2A180A), RoundedCornerShape(k * 44f))
                    )
                }
            }
            Box(Modifier.offset(k * 38f, k * 1026f).size(k * 653f, k * 122f), contentAlignment = Alignment.Center) {
                PanelText(message, k, 38f, maxLines = 2, modifier = Modifier.width(k * 610f))
            }
        }
    }
}

/** The human is knocked out (round result or Resign): Watch (green) or Leave (red). */
@Composable
fun YoureOutDialog(message: String, onWatch: () -> Unit, onLeave: () -> Unit) {
    TournamentOverlay {
        Panel(OutPanel, 0.9f) { k ->
            Box(Modifier.offset(k * 51f, k * 166f).size(k * 649f, k * 120f), contentAlignment = Alignment.Center) {
                PanelText("You're Out", k, 62f)
            }
            Box(Modifier.offset(k * 39f, k * 322f).size(k * 673f, k * 333f), contentAlignment = Alignment.Center) {
                PanelText(message, k, 38f, maxLines = 6, modifier = Modifier.width(k * 620f))
            }
            HotSpot(k, 51f, 688f, 311f, 111f, onWatch) { PanelText("Watch", k, 48f) }
            HotSpot(k, 390f, 688f, 310f, 111f, onLeave) { PanelText("Leave", k, 48f) }
        }
    }
}
