package com.westly.ludo

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.westly.ludo.game.LudoGame
import com.westly.ludo.game.Phase
import com.westly.ludo.ui.CounterOrb
import com.westly.ludo.ui.ExitButton
import com.westly.ludo.ui.GameModeScreen
import com.westly.ludo.ui.HandGuide
import com.westly.ludo.ui.HomeScreen
import com.westly.ludo.ui.LudoBoard
import com.westly.ludo.ui.NeriboIntro
import com.westly.ludo.ui.SettingsScreen
import com.westly.ludo.ui.MenuButton
import com.westly.ludo.ui.Palette
import com.westly.ludo.ui.PlayerBadge
import com.westly.ludo.ui.PointingHand
import com.westly.ludo.ui.Swatch
import com.westly.ludo.ui.TurnPill
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val game = LudoGame()
    // Tournament is a separate game with its own state, score and save slot.
    private val tournament = LudoGame(tournament = true)

    private fun prefs() = getSharedPreferences("ludomate", Context.MODE_PRIVATE)

    private fun saveGame() {
        prefs().edit()
            .putString("save", game.toSaveString())
            .putString("tournament_save", tournament.toSaveString())
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs().getString("save", null)?.let { game.restore(it) }
        prefs().getString("tournament_save", null)?.let { tournament.restore(it) }
        setContent { LudoApp(game, tournament) }
    }

    override fun onPause() {
        super.onPause()
        saveGame()
    }

    override fun onStop() {
        super.onStop()
        saveGame()
    }
}

/** Intro -> home -> settings / game modes -> the chosen game. */
@Composable
fun LudoApp(game: LudoGame, tournament: LudoGame) {
    // One scope for the whole app screen, so a move in progress is not cut off when leaving a game.
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf("intro") }

    BackHandler(enabled = screen != "intro" && screen != "home") {
        screen = when (screen) {
            "game", "tournament" -> "modes"
            else -> "home"
        }
    }

    Crossfade(targetState = screen, animationSpec = tween(400), label = "screen") { s ->
        when (s) {
            "intro" -> NeriboIntro { screen = "home" }
            "home" -> HomeScreen(onSettings = { screen = "settings" }, onGame = { screen = "modes" })
            "settings" -> SettingsScreen(onClose = { screen = "home" })
            "modes" -> GameModeScreen(
                onBack = { screen = "home" },
                onYouAndComputer = { screen = "game" },
                onTournament = { screen = "tournament" }
            )
            "game" -> LudoScreen(game, scope, onExit = { screen = "modes" })
            "tournament" -> LudoScreen(tournament, scope, onExit = { screen = "modes" })
        }
    }
}

/**
 * The game screen around the board (Phase 2 layout, unchanged) wired to the Phase 3 game.
 * Every size is a multiple of `u`, which is 1% of the screen width, so the layout
 * scales with the device.
 */
@Composable
fun LudoScreen(game: LudoGame, scope: CoroutineScope, onExit: () -> Unit) {
    val tournament = game.tournament

    // Computer players only play while this screen is showing; they stop at a clean moment.
    DisposableEffect(game) {
        game.paused = false
        onDispose { game.paused = true }
    }
    LaunchedEffect(game.phase, game.activePlayer, game.paused) {
        if (game.isComputerTurn && !game.paused) scope.launch { game.computerStep() }
    }

    // Soft 0..1 pulse used by the glowing pieces and the dice hint.
    val pulse = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(game.phase) {
        if (game.phase == Phase.Choose || game.phase == Phase.AwaitRoll) {
            while (true) {
                val t = withFrameNanos { it } / 1_000_000_000f
                pulse.floatValue = (sin(t * 5f) + 1f) / 2f
            }
        }
    }
    val over = game.phase == Phase.GameOver

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.WoodLight, Palette.WoodDark)))
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // The board takes the full screen width (or up to 70% of the height on short screens).
            // Everything around it is sized in `u` (1% of the screen width) and shrinks only if needed.
            // Tournament shows four score badges, so its bottom area is a little taller.
            val fixedU = if (tournament) 58.7f else 54.5f
            val maxBoard = minOf(maxWidth, maxHeight * 0.70f)
            val u = minOf(maxWidth / 100f, (maxHeight - maxBoard) / fixedU)
            val boardSide = minOf(maxBoard, maxHeight - u * fixedU)

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top: menu button, exit button, then player names + scores
                Box(Modifier.width(u * 100f).height(u * 27.5f)) {
                    MenuButton(u * 11.8f, Modifier.align(Alignment.TopStart).padding(start = u * 1f, top = u * 1f))
                    ExitButton(
                        u * 11.8f,
                        Modifier.align(Alignment.TopEnd).padding(end = u * 1f, top = u * 1f).clickable { onExit() }
                    )
                    if (tournament) {
                        TurnPill(
                            if (over) "Player ${game.winner + 1} Wins!" else "Player ${game.activePlayer + 1} Turn",
                            u,
                            Modifier.align(Alignment.TopCenter).padding(top = u * 2f),
                            textScale = 0.75f
                        )
                        PlayerBadge(
                            "Player 2", game.scores[1], u,
                            Modifier.align(Alignment.BottomStart).padding(start = u * 12f)
                                .alpha(if (game.activePlayer == 1) 1f else 0.5f),
                            Palette.Green.base
                        )
                        PlayerBadge(
                            "Player 3", game.scores[2], u,
                            Modifier.align(Alignment.BottomEnd).padding(end = u * 11f)
                                .alpha(if (game.activePlayer == 2) 1f else 0.5f),
                            Palette.Yellow.base
                        )
                    } else {
                        PlayerBadge(
                            "Player 2", game.scores[1], u,
                            Modifier.align(Alignment.BottomStart).padding(start = u * 12f)
                                .alpha(if (game.activePlayer == 1) 1f else 0.5f)
                        )
                        PlayerBadge(
                            "Player 1", game.scores[0], u,
                            Modifier.align(Alignment.BottomEnd).padding(end = u * 11f)
                                .alpha(if (game.activePlayer == 0) 1f else 0.5f)
                        )
                    }
                }

                // Board with the two dice
                LudoBoard(
                    Modifier.size(boardSide),
                    leftDie = game.face1,
                    rightDie = game.face2,
                    pieces = { game.pieceViews() },
                    pulse = { pulse.floatValue },
                    rollHint = game.phase == Phase.AwaitRoll,
                    pick = game.pick,
                    onPick = { tag -> if (!game.isComputerTurn) scope.launch { game.onPiecePicked(tag) } },
                    onBoardTap = { row, col -> if (!game.isComputerTurn) scope.launch { game.onBoardTap(row, col) } },
                    yardLabels = if (tournament) listOf("Player 2", "Player 3", "Player 1", "Player 4")
                    else listOf("Player 2", "Player 1", "Player 1", "Player 2")
                )

                // Bottom: dice indicators (blue = first die, red = total, green = second die)
                Spacer(Modifier.height(u * 2.5f))
                Row(horizontalArrangement = Arrangement.spacedBy(u * 4f)) {
                    MoveOrb(game, 0, game.die1, Palette.Blue, u)
                    MoveOrb(game, 2, game.die1 + game.die2, Palette.Red, u)
                    MoveOrb(game, 1, game.die2, Palette.Green, u)
                }
                Spacer(Modifier.height(u * 2f))
                if (tournament) {
                    Row(Modifier.width(u * 100f), horizontalArrangement = Arrangement.SpaceBetween) {
                        PlayerBadge(
                            "Player 1", game.scores[0], u,
                            Modifier.padding(start = u * 12f).alpha(if (game.activePlayer == 0) 1f else 0.5f),
                            Palette.Red.base
                        )
                        PlayerBadge(
                            "Player 4", game.scores[3], u,
                            Modifier.padding(end = u * 11f).alpha(if (game.activePlayer == 3) 1f else 0.5f),
                            Palette.Blue.base
                        )
                    }
                } else {
                    TurnPill(
                        if (over) "Player ${game.winner + 1} Wins!" else "Player ${game.activePlayer + 1} Turn",
                        u,
                        textScale = 0.75f
                    )
                }
            }

            // Computer hand: driven by the computer's real, already-decided action (never on the human's turn).
            val colTop = (maxHeight - (boardSide + u * fixedU)) / 2f
            val boardTop = colTop + u * 27.5f
            HandGuide(
                target = game.handTarget(),
                owner = game.handColor(),
                screenW = maxWidth,
                boardLeft = (maxWidth - boardSide) / 2f,
                boardTop = boardTop,
                boardSide = boardSide,
                orbCenterY = boardTop + boardSide + u * 2.5f + u * 6.6f,
                orbX = { option ->
                    val slot = when (option) { 0 -> 0; 2 -> 1; else -> 2 }
                    (maxWidth - u * 47.6f) / 2f + u * 6.6f + u * 17.2f * slot
                },
                u = u
            )
        }
    }
}

/**
 * One of the three movement choices: 0 = first die (blue), 1 = second die (green), 2 = total (red).
 * Usable options are tappable and outlined, the chosen one is larger with a bold ring,
 * and unusable or spent options are dimmed and do nothing.
 */
@Composable
private fun MoveOrb(game: LudoGame, index: Int, value: Int, swatch: Swatch, u: Dp) {
    val usable = game.optionUsable(index)
    // Display only: on a computer turn the highlight follows the computer's own internal choice.
    val shownOption = if (game.isComputerTurn) game.computerOption else game.selectedDie
    val selected = usable && shownOption == index
    val scale by animateFloatAsState(if (selected) 1.14f else 1f, label = "orbScale")
    val ring = when {
        selected -> Modifier.border(u * 0.8f, Color.White, CircleShape)
        usable -> Modifier.border(u * 0.35f, Color.White.copy(alpha = 0.6f), CircleShape)
        else -> Modifier
    }
    CounterOrb(
        value, swatch, u * 13.2f,
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (usable) 1f else 0.4f)
            .then(ring)
            .clickable(enabled = usable && !game.isComputerTurn) { game.selectDie(index) }
    )
}
