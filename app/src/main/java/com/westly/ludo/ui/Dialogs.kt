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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                        PanelText("${names[3]}: ${scores[3]}", k, 30f, maxChars = 11)
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
    onClose: () -> Unit
) {
    BackHandler(true) { onClose() }
    Scrim {
        Panel(MenuPanel, 0.86f) { k ->
            val labels = listOf("Change Names", "Restart", "Resign", "Exit")
            val actions = listOf(onChangeNames, onRestart, onResign, onExit)
            val ys = listOf(132f, 276f, 420f, 564f)
            for (i in 0 until 4) {
                HotSpot(k, 88f, ys[i], 485f, 108f, actions[i]) {
                    PanelText(labels[i], k, 46f)
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
fun ResignDialog(message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    BackHandler(true) { onCancel() }
    Scrim {
        Panel(ResignPanel, 0.86f) { k ->
            Box(Modifier.offset(k * 79f, k * 58f).size(k * 490f, k * 92f), contentAlignment = Alignment.Center) {
                PanelText("Resign", k, 50f)
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
