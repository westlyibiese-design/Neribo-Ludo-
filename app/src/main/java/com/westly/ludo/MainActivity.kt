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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.westly.ludo.game.LudoGame
import com.westly.ludo.game.GameSettings
import com.westly.ludo.game.Phase
import com.westly.ludo.game.PlayerNames
import com.westly.ludo.game.Sounds
import com.westly.ludo.game.TOverlay
import com.westly.ludo.ui.BoardThemes
import com.westly.ludo.ui.ChangeNamesDialog
import com.westly.ludo.ui.ConfigurationScreen
import com.westly.ludo.ui.CounterOrb
import com.westly.ludo.ui.ExitButton
import com.westly.ludo.ui.GameModeScreen
import com.westly.ludo.ui.HandGuide
import com.westly.ludo.ui.FooterSpace
import com.westly.ludo.ui.HomeScreen
import com.westly.ludo.ui.MenuDialog
import com.westly.ludo.ui.NeriboFooter
import com.westly.ludo.ui.ResignDialog
import com.westly.ludo.ui.RoundBanner
import com.westly.ludo.ui.RoundResultDialog
import com.westly.ludo.ui.RulesScreen
import com.westly.ludo.ui.SkipButton
import com.westly.ludo.ui.SoundVibrationScreen
import com.westly.ludo.ui.TieBreakDialog
import com.westly.ludo.ui.YoureOutDialog
import com.westly.ludo.ui.WinnerPage
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val game = LudoGame()
    // Tournament is a separate game with its own state, score and save slot.
    private val tournament = LudoGame(tournament = true)

    private fun prefs() = getSharedPreferences("ludomate", Context.MODE_PRIVATE)

    // Player names, saved on this phone until the user changes them again.
    private val names by lazy { PlayerNames(prefs()) }

    // Board type, computer speed and computer level, saved on this phone.
    private val settings by lazy { GameSettings(prefs()) }

    private fun saveGame() {
        prefs().edit()
            .putString("save", game.toSaveString())
            .putString("tournament_save", tournament.toSaveString())
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Sounds.appContext = applicationContext
        prefs().getString("save", null)?.let { game.restore(it) }
        prefs().getString("tournament_save", null)?.let { tournament.restore(it) }
        setContent { LudoApp(game, tournament, names, settings) }
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
fun LudoApp(game: LudoGame, tournament: LudoGame, names: PlayerNames, settings: GameSettings) {
    // One scope for the whole app screen, so a move in progress is not cut off when leaving a game.
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf("intro") }

    BackHandler(enabled = screen != "intro" && screen != "home") {
        screen = when (screen) {
            "game", "tournament" -> "modes"
            "config", "sound", "rules" -> "settings"
            else -> "home"
        }
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = screen, animationSpec = tween(400), label = "screen") { s ->
            when (s) {
                "intro" -> NeriboIntro { screen = "home" }
                "home" -> HomeScreen(onSettings = { screen = "settings" }, onGame = { screen = "modes" })
                "settings" -> SettingsScreen(
                    names,
                    onSound = { screen = "sound" },
                    onRules = { screen = "rules" },
                    onConfiguration = { screen = "config" },
                    onClose = { screen = "home" }
                )
                "sound" -> SoundVibrationScreen(settings, onClose = { screen = "settings" })
                "rules" -> RulesScreen(onClose = { screen = "settings" })
                "config" -> ConfigurationScreen(settings, onClose = { screen = "settings" })
                "modes" -> GameModeScreen(
                    onBack = { screen = "home" },
                    onYouAndComputer = { screen = "game" },
                    onTournament = { screen = "tournament" }
                )
                "game" -> LudoScreen(game, scope, names, settings, onModes = { screen = "modes" }, onHome = { screen = "home" })
                "tournament" -> LudoScreen(tournament, scope, names, settings, onModes = { screen = "modes" }, onHome = { screen = "home" })
            }
        }
        // © line at the same spot on every page (the intro already shows its own © line).
        if (screen != "intro") {
            NeriboFooter(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 4.dp))
        }
    }
}

private fun turnText(game: LudoGame, names: PlayerNames): String = when {
    game.phase == Phase.GameOver -> "${names[game.winner.coerceAtLeast(0)]} Wins!"
    game.activePlayer == 0 -> "Your Turn"
    else -> "${names[game.activePlayer]} Turn"
}

/**
 * The game screen around the board (Phase 2 layout, unchanged) wired to the Phase 3 game.
 * Every size is a multiple of `u`, which is 1% of the screen width, so the layout
 * scales with the device.
 */
@Composable
fun LudoScreen(
    game: LudoGame,
    scope: CoroutineScope,
    names: PlayerNames,
    settings: GameSettings,
    onModes: () -> Unit,
    onHome: () -> Unit
) {
    val tournament = game.tournament

    // Configuration: computer speed and level, and the board type pictures for the four houses.
    SideEffect {
        game.computerSpeed = settings.speedFactor
        game.computerLevel = settings.level
    }
    val yardImages: List<ImageBitmap?> = BoardThemes.images(settings.boardType).map { ImageBitmap.imageResource(it) }

    // Computer players only play while this screen is showing; they stop at a clean moment.
    DisposableEffect(game) {
        game.paused = false
        onDispose {
            game.paused = true
            game.stopFast()   // spectator fast-forward never leaks sound-off into another screen
        }
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

    // Which pop-up is open: "none", "menu", "resign" or "names". Computers wait while one is open.
    var dialog by remember { mutableStateOf("none") }
    // Computers also wait while a Tournament overlay (Tie-Break / Round Result / You're Out) or the round banner is showing.
    val overlayOpen = tournament && game.overlay != TOverlay.NONE
    val bannerOn = tournament && game.bannerPending
    LaunchedEffect(dialog, overlayOpen, bannerOn) { game.paused = dialog != "none" || overlayOpen || bannerOn }
    LaunchedEffect(game.bannerPending) {
        if (tournament && game.bannerPending) {
            delay(1500)
            game.bannerPending = false
        }
    }
    val bannerAlpha by animateFloatAsState(if (bannerOn) 1f else 0f, tween(300), label = "banner")

    Box(Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.WoodLight, Palette.WoodDark)))
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = FooterSpace), contentAlignment = Alignment.Center) {
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
                    MenuButton(
                        u * 11.8f,
                        Modifier.align(Alignment.TopStart).padding(start = u * 1f, top = u * 1f).clickable { dialog = "menu" }
                    )
                    ExitButton(
                        u * 11.8f,
                        Modifier.align(Alignment.TopEnd).padding(end = u * 1f, top = u * 1f).clickable { onModes() }
                    )
                    if (tournament && game.spectator && game.phase != Phase.GameOver) {
                        // The human is only watching: Skip makes the computers play fast.
                        SkipButton(
                            u * 9.6f, game.fast,
                            Modifier.align(Alignment.TopStart).padding(start = u * 1f, top = u * 14.2f)
                                .clickable(enabled = !game.fast) { game.startFast() }
                        )
                    }
                    if (tournament) {
                        TurnPill(
                            turnText(game, names),
                            u,
                            Modifier.align(Alignment.TopCenter).padding(top = u * 2f),
                            textScale = 0.75f
                        )
                        PlayerBadge(
                            names[1], game.scores[1], u,
                            Modifier.align(Alignment.BottomStart).padding(start = u * 12f)
                                .alpha(if (game.activePlayer == 1) 1f else 0.5f),
                            Palette.Green.base
                        )
                        PlayerBadge(
                            names[2], game.scores[2], u,
                            Modifier.align(Alignment.BottomEnd).padding(end = u * 11f)
                                .alpha(if (game.activePlayer == 2) 1f else 0.5f),
                            Palette.Yellow.base
                        )
                    } else {
                        PlayerBadge(
                            names[1], game.scores[1], u,
                            Modifier.align(Alignment.BottomStart).padding(start = u * 12f)
                                .alpha(if (game.activePlayer == 1) 1f else 0.5f)
                        )
                        PlayerBadge(
                            names[0], game.scores[0], u,
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
                    onBoardTap = { row, col -> if (!game.isComputerTurn && !game.bannerPending) scope.launch { game.onBoardTap(row, col) } },
                    yardLabels = if (tournament) listOf(names[1], names[2], names[0], names[3])
                    else listOf(names[1], names[0], names[0], names[1]),
                    yardImages = yardImages,
                    // green, yellow, red, blue = players 1, 2, 0, 3
                    outYards = if (tournament) listOf(!game.active[1], !game.active[2], !game.active[0], !game.active[3]) else emptyList()
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
                            names[0], game.scores[0], u,
                            Modifier.padding(start = u * 12f).alpha(if (game.activePlayer == 0) 1f else 0.5f),
                            Palette.Red.base
                        )
                        PlayerBadge(
                            names[3], game.scores[3], u,
                            Modifier.padding(end = u * 11f).alpha(if (game.activePlayer == 3) 1f else 0.5f),
                            Palette.Blue.base
                        )
                    }
                } else {
                    TurnPill(
                        turnText(game, names),
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

            // Round banner: fades in and out over the board, takes no taps.
            if (bannerAlpha > 0.01f) {
                RoundBanner(
                    game.roundTitle(), u,
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = boardTop + boardSide / 2f - maxHeight / 2f)
                        .alpha(bannerAlpha)
                )
            }
        }
    }

    when (dialog) {
        "menu" -> MenuDialog(
            onChangeNames = { dialog = "names" },
            onRestart = {
                dialog = "none"
                scope.launch { game.restartRound() }
            },
            onResign = { dialog = "resign" },
            onExit = {
                dialog = "none"
                onHome()
            },
            onClose = { dialog = "none" },
            resignEnabled = !(tournament && !game.active[0])
        )
        "resign" -> ResignDialog(
            message = if (tournament && game.activeCount() > 2) {
                "You will be knocked out of the tournament. Do you want to resign?"
            } else {
                "A point will be awarded to ${names[game.resignBeneficiary(0)]}. Do you want to resign?"
            },
            onConfirm = {
                dialog = "none"
                scope.launch { game.resign(0) }
            },
            onCancel = { dialog = "menu" }
        )
        "names" -> ChangeNamesDialog(names, onClose = { dialog = "none" })
    }

    if (tournament) {
        val colorNames = listOf("Red", "Green", "Yellow", "Blue")
        when (game.overlay) {
            TOverlay.TIEBREAK -> {
                TieBreakDialog(
                    names = names.names.toList(),
                    tied = game.tieIds,
                    rolls = game.tieRolls.toList(),
                    rolling = game.tieRolling,
                    flicker = game.tieFlicker,
                    humanCanRoll = 0 in game.tieIds && game.tieRolls[0] == 0 && game.tieRolling < 0,
                    onRoll = { scope.launch { game.tieHumanRoll() } }
                )
                // The roll-off runs while this screen is open; leaving the screen stops it safely.
                LaunchedEffect(Unit) { game.runTieBreak() }
            }
            TOverlay.RESULT -> RoundResultDialog(
                title = "Round ${game.round} Result",
                outLine = "${names[game.resultOut]} (${colorNames.getOrElse(game.resultOut) { "" }}) is OUT",
                names = names.names.toList(),
                seeds = game.resultSeeds,
                outPlayer = game.resultOut,
                auto = game.spectator,
                fast = game.fast,
                onNext = { game.resultNext() }
            )
            TOverlay.OUT -> {
                val to = if (game.leaveTo in 0..3) game.leaveTo else 1
                YoureOutDialog(
                    message = (if (game.resultResigned) "You resigned.\n" else "You are out of the tournament.\n") +
                        "Watch the rest, or leave now.\nIf you leave, ${names[to]} gets the point.",
                    onWatch = { game.outWatch() },
                    onLeave = { game.outLeave() }
                )
            }
        }
    }

    if (over) {
        WinnerPage(
            winnerName = names[game.winner.coerceAtLeast(0)],
            names = names.names.toList(),
            scores = game.scores.toList().let { sc -> List(PlayerNames.COUNT) { sc.getOrElse(it) { 0 } } },
            tournament = tournament,
            onNext = { game.nextGame() },
            onModes = {
                game.nextGame()
                onModes()
            }
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
