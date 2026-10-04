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
import androidx.compose.runtime.key
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
import com.westly.ludo.connect.ConnectSession
import com.westly.ludo.connect.LinkState
import com.westly.ludo.game.LudoColor
import com.westly.ludo.game.LudoGame
import com.westly.ludo.game.GameSettings
import com.westly.ludo.game.Phase
import com.westly.ludo.game.PlayerNames
import com.westly.ludo.game.Sounds
import com.westly.ludo.game.TOverlay
import com.westly.ludo.online.OnlineAuth
import com.westly.ludo.ui.AccountDialog
import com.westly.ludo.ui.BoardThemes
import com.westly.ludo.ui.ChangeNamesDialog
import com.westly.ludo.ui.ConfigurationScreen
import com.westly.ludo.ui.ConnectCountScreen
import com.westly.ludo.ui.ConnectDialog
import com.westly.ludo.ui.ConnectMenuDialog
import com.westly.ludo.ui.ConnectQrDialog
import com.westly.ludo.ui.ConnectResumeScreen
import com.westly.ludo.ui.ConnectWaitBanner
import com.westly.ludo.ui.ConnectNotice
import com.westly.ludo.ui.ConnectHostScreen
import com.westly.ludo.ui.ConnectJoinScreen
import com.westly.ludo.ui.ConnectMenuScreen
import com.westly.ludo.ui.ConnectOfflineScreen
import com.westly.ludo.ui.CounterOrb
import com.westly.ludo.ui.ExitButton
import com.westly.ludo.ui.FamilyCountScreen
import com.westly.ludo.ui.FamilyNamesDialog
import com.westly.ludo.ui.FamilyNamesScreen
import com.westly.ludo.ui.GameModeScreen
import com.westly.ludo.ui.HandGuide
import com.westly.ludo.ui.FooterSpace
import com.westly.ludo.ui.HomeScreen
import com.westly.ludo.ui.KeepScreenOn
import com.westly.ludo.ui.YouAreLabel
import com.westly.ludo.ui.MenuDialog
import com.westly.ludo.ui.NeriboFooter
import com.westly.ludo.ui.OnlineScreen
import com.westly.ludo.ui.ResignDialog
import com.westly.ludo.ui.RoundBanner
import com.westly.ludo.ui.RoundResultDialog
import com.westly.ludo.ui.RulesScreen
import com.westly.ludo.ui.SignInPanel
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
import org.json.JSONObject
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val game = LudoGame()
    // Tournament is a separate game with its own state, score and save slot.
    private val tournament = LudoGame(tournament = true)

    // Family (humans only, one phone) is a third game with its own save slot. It is created when the
    // players pick 2 / 3 / 4 and type their names, and thrown away by End Game / End Tournament.
    private var family by mutableStateOf<LudoGame?>(null)

    private fun prefs() = getSharedPreferences("ludomate", Context.MODE_PRIVATE)

    // Player names, saved on this phone until the user changes them again.
    private val names by lazy { PlayerNames(prefs()) }

    // Board type, computer speed and computer level, saved on this phone.
    private val settings by lazy { GameSettings(prefs()) }

    // Connect and Play (nearby phones). Owned here, not by a screen, so moving between screens keeps the link.
    private val connect by lazy { ConnectSession(applicationContext, prefs()) }

    // Online play: the Google / Supabase sign-in. Owned here so the session survives moving between screens.
    // Its session is kept in its own file, which is left out of cloud backups.
    private val online by lazy {
        OnlineAuth(applicationContext, getSharedPreferences("ludomate_online", Context.MODE_PRIVATE))
    }

    private fun saveGame() {
        prefs().edit()
            .putString("save", game.toSaveString())
            .putString("tournament_save", tournament.toSaveString())
            .also { e ->
                val f = family
                if (f != null) e.putString("family_save", f.toSaveString()) else e.remove("family_save")
            }
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Sounds.appContext = applicationContext
        prefs().getString("save", null)?.let { game.restore(it) }
        prefs().getString("tournament_save", null)?.let { tournament.restore(it) }
        prefs().getString("family_save", null)?.let { saved ->
            try {
                val n = JSONObject(saved).optInt("fPlayers", 0)
                if (n in 2..4) family = LudoGame(tournament = n >= 3, familyPlayers = n).also { it.restore(saved) }
            } catch (e: Exception) {
                family = null
            }
        }
        setContent {
            LudoApp(
                game, tournament, names, settings,
                connect = connect,
                online = online,
                familyGame = family,
                onFamilyCreate = { count, list ->
                    family = LudoGame(tournament = count >= 3, familyPlayers = count).also { it.setFamilyNames(list) }
                    saveGame()
                },
                onFamilyEnd = {
                    family = null
                    saveGame()
                }
            )
        }
    }

    override fun onPause() {
        super.onPause()
        saveGame()
    }

    override fun onResume() {
        super.onResume()
        // A guest whose phone was locked checks its link to the host; a host makes sure it is still advertising.
        connect.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        saveGame()
        // A hosted game is saved so it can be resumed if the app is closed.
        connect.saveHostNow()
    }

    override fun onDestroy() {
        // Stop advertising / searching and close every nearby connection.
        connect.release()
        super.onDestroy()
    }
}

/** Intro -> home -> settings / game modes -> the chosen game. */
@Composable
fun LudoApp(
    game: LudoGame,
    tournament: LudoGame,
    names: PlayerNames,
    settings: GameSettings,
    connect: ConnectSession,
    online: OnlineAuth,
    familyGame: LudoGame? = null,
    onFamilyCreate: (Int, List<String>) -> Unit = { _, _ -> },
    onFamilyEnd: () -> Unit = {}
) {
    // One scope for the whole app screen, so a move in progress is not cut off when leaving a game.
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf("intro") }
    // How many people the Family players picked (used while typing their names).
    var familyCount by rememberSaveable { mutableStateOf(2) }
    // How many players the Connect and Play host picked.
    var connectCount by rememberSaveable { mutableStateOf(2) }
    // The join screen was opened by the Rejoin button (no scanning).
    var connectRejoin by rememberSaveable { mutableStateOf(false) }

    // Online play: the sign-in panel and the account dialog float over whatever screen is showing.
    var showSignIn by rememberSaveable { mutableStateOf(false) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    // The player tapped Online while signed out: after a successful sign-in, go on to the Online screen.
    var openOnlineAfterSignIn by rememberSaveable { mutableStateOf(false) }
    // Signed out (or the server dropped the session) while on the Online screen: back to the Connect menu.
    LaunchedEffect(online.user) {
        if (online.user == null) {
            showAccount = false
            if (screen == "online") screen = "connect"
        }
    }

    // The screen's coroutine scope has the frame clock the host needs to run move animations.
    SideEffect { connect.bindScope(scope) }
    // A line for the Offline screen, for example "The host ended the game." after a game was closed.
    var connectNotice by remember { mutableStateOf<String?>(null) }

    // Any screen other than the lobby screens and the Connect game closes the nearby connection.
    LaunchedEffect(screen) {
        if (screen != "connect_host" && screen != "connect_join" && screen != "connect_game" &&
            screen != "connect_resume"
        ) {
            connect.leave()
        }
    }
    // The host pressed Start (a guest is told the game began, or a returning phone found its game): everybody opens the game screen.
    LaunchedEffect(connect.inGame) {
        if (connect.inGame && (screen == "connect_host" || screen == "connect_join" || screen == "connect_resume")) {
            screen = "connect_game"
        }
    }
    // The game is over for this phone (the host ended it or the link dropped): back to the Offline screen.
    LaunchedEffect(connect.inGame, connect.ended) {
        if (screen == "connect_game" && !connect.inGame) {
            val message = connect.ended
            if (message != null) {
                connectNotice = message
                connect.clearNotices()
            }
            screen = "connect_offline"
        }
    }

    BackHandler(enabled = screen != "intro" && screen != "home") {
        screen = when (screen) {
            "game", "tournament", "family", "family_count" -> "modes"
            "family_names" -> "family_count"
            "connect" -> "modes"
            "online" -> "connect"
            "connect_game" -> "connect_offline"
            "connect_offline" -> "connect"
            "connect_count", "connect_host", "connect_join", "connect_resume" -> "connect_offline"
            "config", "sound", "rules" -> "settings"
            else -> "home"
        }
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = screen, animationSpec = tween(400), label = "screen") { s ->
            when (s) {
                "intro" -> NeriboIntro { screen = "home" }
                "home" -> HomeScreen(
                    onSettings = { screen = "settings" },
                    onGame = { screen = "modes" },
                    accountName = online.user?.name,
                    onAccount = {
                        if (online.user == null) {
                            openOnlineAfterSignIn = false
                            showSignIn = true
                        } else {
                            showAccount = true
                        }
                    }
                )
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
                    onTournament = { screen = "tournament" },
                    onFamily = { screen = if (familyGame != null) "family" else "family_count" },
                    familyActive = familyGame != null,
                    onConnect = { screen = "connect" }
                )
                "connect" -> ConnectMenuScreen(
                    onBack = { screen = "modes" },
                    onOffline = { screen = "connect_offline" },
                    onOnline = {
                        if (online.user != null) {
                            screen = "online"
                        } else {
                            openOnlineAfterSignIn = true
                            showSignIn = true
                        }
                    }
                )
                "online" -> OnlineScreen(
                    account = online.user,
                    playerName = names[0],
                    onBack = { screen = "connect" }
                )
                "connect_offline" -> ConnectOfflineScreen(
                    onBack = {
                        connectNotice = null
                        screen = "connect"
                    },
                    onHost = {
                        connectNotice = null
                        screen = "connect_count"
                    },
                    onJoin = {
                        connectNotice = null
                        connectRejoin = false
                        screen = "connect_join"
                    },
                    notice = connectNotice,
                    rejoin = connect.rejoinInfo,
                    onRejoin = {
                        connectNotice = null
                        connectRejoin = true
                        screen = "connect_join"
                    },
                    resume = connect.hostSaveInfo,
                    onResume = {
                        connectNotice = null
                        screen = "connect_resume"
                    }
                )
                "connect_resume" -> ConnectResumeScreen(
                    connect, names,
                    onClose = { screen = "connect_offline" }
                )
                "connect_count" -> ConnectCountScreen(
                    onBack = { screen = "connect_offline" },
                    onPick = { count ->
                        connectCount = count
                        screen = "connect_host"
                    }
                )
                "connect_host" -> ConnectHostScreen(
                    connect, names, connectCount,
                    onClose = { screen = "connect_offline" }
                )
                "connect_join" -> ConnectJoinScreen(
                    connect, names,
                    onClose = { screen = "connect_offline" },
                    rejoin = connectRejoin
                )
                "connect_game" -> connect.game?.let { cg ->
                    KeepScreenOn()
                    LudoScreen(
                        cg, scope, names, settings,
                        onModes = { screen = "connect_offline" },
                        onHome = { screen = "connect_offline" },
                        connect = connect
                    )
                }
                "family_count" -> FamilyCountScreen(
                    onBack = { screen = "modes" },
                    onPick = { count ->
                        familyCount = count
                        screen = "family_names"
                    }
                )
                "family_names" -> FamilyNamesScreen(
                    count = familyCount,
                    onBack = { screen = "family_count" },
                    onStart = { list ->
                        onFamilyCreate(familyCount, list)
                        screen = "family"
                    }
                )
                "family" -> familyGame?.let { fg ->
                    LudoScreen(
                        fg, scope, names, settings,
                        onModes = { screen = "modes" },
                        onHome = { screen = "home" },
                        onEndFamily = {
                            onFamilyEnd()
                            screen = "family_count"
                        }
                    )
                }
                "game" -> LudoScreen(game, scope, names, settings, onModes = { screen = "modes" }, onHome = { screen = "home" })
                "tournament" -> LudoScreen(tournament, scope, names, settings, onModes = { screen = "modes" }, onHome = { screen = "home" })
            }
        }
        // © line at the same spot on every page (the intro already shows its own © line).
        if (screen != "intro") {
            NeriboFooter(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 4.dp))
        }
        if (showSignIn) {
            SignInPanel(
                online,
                onSuccess = {
                    showSignIn = false
                    if (openOnlineAfterSignIn) screen = "online"
                    openOnlineAfterSignIn = false
                },
                onClose = {
                    showSignIn = false
                    openOnlineAfterSignIn = false
                }
            )
        }
        val account = online.user
        if (showAccount && account != null) {
            AccountDialog(
                account,
                onSignOut = {
                    showAccount = false
                    scope.launch { online.signOut() }
                },
                onClose = { showAccount = false }
            )
        }
    }
}

private fun turnText(game: LudoGame, nm: (Int) -> String): String = when {
    game.phase == Phase.GameOver -> "${nm(game.winner.coerceAtLeast(0))} Wins!"
    game.family -> when {
        // Family: the phone is handed over before the roll; after a double six the same player rolls again.
        game.phase == Phase.AwaitRoll && game.bonusRoll -> "${nm(game.activePlayer)} Rolls Again"
        game.phase == Phase.AwaitRoll -> "Pass the phone to ${nm(game.activePlayer)}"
        else -> "${nm(game.activePlayer)}'s Turn"
    }
    game.activePlayer == 0 -> "Your Turn"
    else -> "${nm(game.activePlayer)} Turn"
}

/** Connect and Play wording: every person has their own phone, so it is "Your Turn" or "<Name>'s Turn". */
private fun connectTurnText(game: LudoGame, nm: (Int) -> String, me: Int): String = when {
    game.phase == Phase.GameOver -> "${nm(game.winner.coerceAtLeast(0))} Wins!"
    game.activePlayer == me -> "Your Turn"
    else -> "${nm(game.activePlayer)}'s Turn"
}

private fun connectDotColor(c: LudoColor): Color = when (c) {
    LudoColor.RED -> Palette.Red.base
    LudoColor.GREEN -> Palette.Green.base
    LudoColor.YELLOW -> Palette.Yellow.base
    LudoColor.BLUE -> Palette.Blue.base
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
    onHome: () -> Unit,
    onEndFamily: () -> Unit = {},
    connect: ConnectSession? = null
) {
    // Connect and Play: names come from the room, taps go to the session (host or guest), and a guest's
    // game is only a display model. Every other mode passes null and behaves exactly as before.
    val cs = connect
    val tournament = game.tournament
    // Names shown on screen: Connect uses the room's names, Family its own, the other modes the saved player names.
    val nm: (Int) -> String = { i ->
        if (cs != null) cs.playerName(i) else if (game.family) game.familyName(i) else names[i]
    }
    val nameList: List<String> =
        if (cs != null) List(4) { cs.playerName(it) }
        else if (game.family) game.familyNames.toList()
        else names.names.toList()
    val endLabel = if (tournament) "End Tournament" else "End Game"
    val turnLine = if (cs != null) connectTurnText(game, nm, cs.myPlayer) else turnText(game, nm)
    val wideTurn = game.family || cs != null
    // Connect: a colour nobody plays (3-player game) has no badge.
    val present: (Int) -> Boolean = { p -> cs == null || cs.seatOfPlayer(p) >= 0 }

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
    // Leaving a Connect and Play game: the host ends it for everybody (after asking), a guest just leaves.
    val exitAction: () -> Unit = {
        if (cs == null) {
            onModes()
        } else if (cs.isHost) {
            dialog = "connectHostExit"
        } else {
            dialog = "connectLeave"
        }
        Unit
    }
    // Connect: the seat the host is about to remove (asked to confirm first).
    var removeSeat by remember { mutableStateOf(-1) }
    if (cs != null) {
        val covered = cs.finalText != null || cs.link != LinkState.OK
        BackHandler(enabled = dialog == "none" && !covered) { dialog = "connectMenu" }
        // A final message or a lost link has its own buttons: the back key does nothing then.
        BackHandler(enabled = covered) { }
    }
    // Computers also wait while a Tournament overlay (Tie-Break / Round Result / You're Out) or the round banner is showing.
    val overlayOpen = tournament && game.overlay != TOverlay.NONE
    // A Connect guest runs no rules: it shows the banner by itself whenever the host's banner counter goes up.
    var guestBanner by remember { mutableStateOf(false) }
    if (cs != null && !cs.isHost && tournament) {
        LaunchedEffect(game, game.bannerSeq) {
            if (game.bannerSeq > 0) {
                guestBanner = true
                delay(1500)
                guestBanner = false
            }
        }
    }
    val bannerOn = tournament && (if (cs != null && !cs.isHost) guestBanner else game.bannerPending)
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
            // Connect adds one line for "You are ..." between the buttons and the board.
            val topH = if (cs != null) 32.5f else 27.5f
            val fixedU = (if (tournament) 58.7f else 54.5f) + (topH - 27.5f)
            val maxBoard = minOf(maxWidth, maxHeight * 0.70f)
            val u = minOf(maxWidth / 100f, (maxHeight - maxBoard) / fixedU)
            val boardSide = minOf(maxBoard, maxHeight - u * fixedU)

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top: menu button, exit button, then player names + scores
                Box(Modifier.width(u * 100f).height(u * topH)) {
                    MenuButton(
                        u * 11.8f,
                        Modifier.align(Alignment.TopStart).padding(start = u * 1f, top = u * 1f)
                            .clickable { dialog = if (cs != null) "connectMenu" else "menu" }
                    )
                    ExitButton(
                        u * 11.8f,
                        Modifier.align(Alignment.TopEnd).padding(end = u * 1f, top = u * 1f)
                            .clickable { exitAction() }
                    )
                    if (cs != null && (cs.myColors.isNotEmpty() || cs.watching)) {
                        // Which colours are mine. Under the turn pill in Tournament-style games, at the top otherwise.
                        Box(
                            Modifier.align(Alignment.TopCenter)
                                .padding(top = if (tournament) u * 12.4f else u * 3f)
                        ) {
                            YouAreLabel(cs.myColors.map { connectDotColor(it) }, cs.youAreText, u)
                        }
                    }
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
                            turnLine,
                            u,
                            Modifier.align(Alignment.TopCenter).padding(top = u * 2f),
                            textScale = 0.75f,
                            widthUnits = if (wideTurn) 66f else 41f,
                            fitChars = if (wideTurn) 24 else 0
                        )
                        if (present(1)) PlayerBadge(
                            nm(1), game.scores[1], u,
                            Modifier.align(Alignment.BottomStart).padding(start = u * 12f)
                                .alpha(if (game.activePlayer == 1) 1f else 0.5f),
                            Palette.Green.base
                        )
                        if (present(2)) PlayerBadge(
                            nm(2), game.scores[2], u,
                            Modifier.align(Alignment.BottomEnd).padding(end = u * 11f)
                                .alpha(if (game.activePlayer == 2) 1f else 0.5f),
                            Palette.Yellow.base
                        )
                    } else {
                        PlayerBadge(
                            nm(1), game.scores[1], u,
                            Modifier.align(Alignment.BottomStart).padding(start = u * 12f)
                                .alpha(if (game.activePlayer == 1) 1f else 0.5f)
                        )
                        PlayerBadge(
                            nm(0), game.scores[0], u,
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
                    onPick = { tag ->
                        if (cs != null) cs.tapPiece(tag)
                        else if (!game.isComputerTurn) scope.launch { game.onPiecePicked(tag) }
                    },
                    onBoardTap = { row, col ->
                        if (cs != null) cs.tapBoard(row, col)
                        else if (!game.isComputerTurn && !game.bannerPending) scope.launch { game.onBoardTap(row, col) }
                    },
                    yardLabels = if (tournament) listOf(nm(1), nm(2), nm(0), nm(3))
                    else listOf(nm(1), nm(0), nm(0), nm(1)),
                    yardImages = yardImages,
                    // green, yellow, red, blue = players 1, 2, 0, 3
                    outYards = if (tournament) listOf(!game.active[1], !game.active[2], !game.active[0], !game.active[3]) else emptyList()
                )

                // Bottom: dice indicators (blue = first die, red = total, green = second die)
                Spacer(Modifier.height(u * 2.5f))
                Row(horizontalArrangement = Arrangement.spacedBy(u * 4f)) {
                    val orbTap: (Int) -> Unit = { i -> if (cs != null) cs.tapDie(i) else game.selectDie(i) }
                    val orbOn = cs?.canAct() ?: true
                    MoveOrb(game, 0, game.die1, Palette.Blue, u, orbOn, orbTap)
                    MoveOrb(game, 2, game.die1 + game.die2, Palette.Red, u, orbOn, orbTap)
                    MoveOrb(game, 1, game.die2, Palette.Green, u, orbOn, orbTap)
                }
                Spacer(Modifier.height(u * 2f))
                if (tournament) {
                    Row(Modifier.width(u * 100f), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (present(0)) PlayerBadge(
                            nm(0), game.scores[0], u,
                            Modifier.padding(start = u * 12f).alpha(if (game.activePlayer == 0) 1f else 0.5f),
                            Palette.Red.base
                        ) else Spacer(Modifier.width(u * 32f))
                        // A 3-player Family game has no blue player; a 3-player Connect game lacks one colour.
                        if ((!game.family || game.familyPlayers > 3) && present(3)) {
                            PlayerBadge(
                                nm(3), game.scores[3], u,
                                Modifier.padding(end = u * 11f).alpha(if (game.activePlayer == 3) 1f else 0.5f),
                                Palette.Blue.base
                            )
                        }
                    }
                } else {
                    TurnPill(
                        turnLine,
                        u,
                        textScale = 0.75f,
                        widthUnits = if (wideTurn) 66f else 41f,
                        fitChars = if (wideTurn) 24 else 0
                    )
                }
            }

            // Computer hand: driven by the computer's real, already-decided action (never on the human's turn).
            val colTop = (maxHeight - (boardSide + u * fixedU)) / 2f
            val boardTop = colTop + u * topH
            // Connect and Play: the hand shown is the one of the OTHER players (the person who acts sees their own finger).
            key(cs?.hand?.epoch ?: 0) {
                HandGuide(
                    target = if (cs != null) cs.hand.current?.target else game.handTarget(),
                    owner = if (cs != null) cs.hand.owner else game.handColor(),
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

            // Connect: small notices over the board (knocked out, watching, a phone dropped, watcher count).
            if (cs != null) {
                val waitSeat = cs.waitingForSeat
                val statusLine = when {
                    cs.watching -> "You are watching"
                    cs.iAmOut -> "You are out - watching"
                    else -> null
                }
                val watchers = if (cs.isHost) cs.watcherCount else 0
                if (statusLine != null || waitSeat >= 0 || watchers > 0) {
                    Column(
                        Modifier.align(Alignment.TopCenter).padding(top = boardTop + u * 1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(u * 1f)
                    ) {
                        if (statusLine != null) ConnectNotice(statusLine, u)
                        if (waitSeat >= 0) {
                            ConnectWaitBanner(
                                "Waiting for ${cs.seatName(waitSeat)} to reconnect...",
                                canRemove = cs.canRemove(waitSeat),
                                onRemove = { removeSeat = waitSeat },
                                u = u
                            )
                        }
                        if (watchers > 0) ConnectNotice("Watching: $watchers", u)
                    }
                }
            }

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
            resignEnabled = game.family || !(tournament && !game.active[0]),
            // Family has no Resign: End Game / End Tournament clears everything and starts over.
            resignLabel = if (game.family) endLabel else "Resign"
        )
        "resign" -> if (game.family) {
            ResignDialog(
                title = endLabel,
                message = "Are you sure? Scores will be cleared.",
                onConfirm = {
                    dialog = "none"
                    onEndFamily()
                },
                onCancel = { dialog = "menu" }
            )
        } else {
            ResignDialog(
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
        }
        "names" -> if (game.family) {
            FamilyNamesDialog(game, onClose = { dialog = "none" })
        } else {
            ChangeNamesDialog(names, onClose = { dialog = "none" })
        }
    }

    if (tournament) {
        val colorNames = listOf("Red", "Green", "Yellow", "Blue")
        when (game.overlay) {
            TOverlay.TIEBREAK -> {
                TieBreakDialog(
                    names = nameList,
                    tied = game.tieIds,
                    rolls = game.tieRolls.toList(),
                    rolling = game.tieRolling,
                    flicker = game.tieFlicker,
                    // Family: every tied person is a human and taps their own dice in turn.
                    // Connect: the same, but each person taps on their own phone, so only the one whose turn it is can.
                    humanCanRoll = if (cs != null) game.tieTurn >= 0 && game.tieTurn == cs.myPlayer
                    else if (game.family) game.tieTurn >= 0
                    else 0 in game.tieIds && game.tieRolls[0] == 0 && game.tieRolling < 0,
                    onRoll = { if (cs != null) cs.tapTie() else scope.launch { game.tieHumanRoll() } },
                    tapPlayer = if (cs != null || game.family) game.tieTurn else 0,
                    tapMessage = if (cs != null && game.tieTurn >= 0) "Tap your dice to roll"
                    else if (game.family && game.tieTurn >= 0) "${nm(game.tieTurn)}, tap your dice to roll" else null,
                    waitMessage = if (cs != null && game.tieTurn >= 0) "Waiting for ${nm(game.tieTurn)}..." else null
                )
                // The roll-off runs while this screen is open; leaving the screen stops it safely.
                // A Connect guest never runs it: only the host decides the roll-off.
                if (cs == null || cs.isHost) {
                    LaunchedEffect(Unit) { game.runTieBreak() }
                }
            }
            TOverlay.RESULT -> RoundResultDialog(
                title = "Round ${game.round} Result",
                outLine = "${nm(game.resultOut)} (${colorNames.getOrElse(game.resultOut) { "" }}) is OUT",
                names = nameList,
                seeds = game.resultSeeds,
                outPlayer = game.resultOut,
                auto = game.spectator,
                fast = game.fast,
                onNext = { if (cs != null) cs.tapNextRound() else game.resultNext() },
                // Connect: only the host moves on; a guest sees who it is waiting for.
                nextEnabled = cs == null || cs.isHost,
                nextLabel = if (cs == null || cs.isHost) "Next Round" else "Waiting for ${cs.hostName}..."
            )
            // Connect has no "You're out" screen: a knocked-out person keeps watching the game.
            TOverlay.OUT -> if (cs == null) {
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
        if (cs != null) {
            // Connect: names and scores follow the people (by seat), because colours change every game.
            val seats = (0 until cs.playerCount).toList()
            WinnerPage(
                winnerName = nm(game.winner.coerceAtLeast(0)),
                names = seats.map { cs.seatName(it) },
                scores = seats.map { cs.scoreOfSeat(it) },
                tournament = tournament,
                onNext = { cs.tapNextGame() },
                onModes = { exitAction() },
                // A game with a removed person cannot go on: the host ends it from the menu.
                nextEnabled = cs.isHost && !cs.hasRemoved,
                waitingText = if (!cs.isHost) "Waiting for ${cs.hostName}..."
                else if (cs.hasRemoved) "Someone left: end the game" else null
            )
        } else {
            WinnerPage(
                winnerName = nm(game.winner.coerceAtLeast(0)),
                names = nameList,
                scores = game.scores.toList().let { sc ->
                    List(if (game.family) game.familyPlayers else PlayerNames.COUNT) { sc.getOrElse(it) { 0 } }
                },
                tournament = tournament,
                onNext = { game.nextGame() },
                onModes = {
                    game.nextGame()
                    onModes()
                }
            )
        }
    }

    // Connect menus sit above the winner page so they can be opened from it too.
    if (cs != null) {
        when (dialog) {
            "connectMenu" -> ConnectMenuDialog(
                isHost = cs.isHost,
                endLabel = endLabel,
                onEnd = { dialog = "connectEnd" },
                onExit = {
                    if (cs.isHost) {
                        dialog = "connectHostExit"
                    } else {
                        dialog = "connectLeave"
                    }
                },
                onClose = { dialog = "none" },
                watchersOn = cs.allowWatchers,
                onToggleWatchers = { cs.switchWatchers(!cs.allowWatchers) },
                onShowQr = { dialog = "connectQr" }
            )
            "connectQr" -> ConnectQrDialog(
                ticketText = cs.ticketText,
                watching = cs.watcherCount,
                allowWatchers = cs.allowWatchers,
                onClose = { dialog = "connectMenu" }
            )
            "connectLeave" -> ConnectDialog(
                "Leave the game? You can come back later.",
                "Leave", onPrimary = {
                    dialog = "none"
                    // The remembered game stays, so the Rejoin button can bring this person back.
                    cs.leave()
                    onModes()
                },
                secondaryLabel = "Stay", onSecondary = { dialog = "none" },
                primarySwatch = Palette.Red
            )
            "connectEnd", "connectHostExit" -> ResignDialog(
                title = if (dialog == "connectEnd") endLabel else "Exit",
                message = if (dialog == "connectEnd") "Are you sure? Scores will be cleared."
                else "The game ends for everyone. Exit?",
                onConfirm = {
                    dialog = "none"
                    // Tells every phone the game ended and clears the saved game. The host then sees the
                    // same "Game ended" panel and leaves with OK.
                    cs.endForEveryone()
                },
                onCancel = { dialog = if (dialog == "connectEnd") "connectMenu" else "none" }
            )
        }

        // Host: Remove player, after asking.
        if (removeSeat >= 0) {
            val seat = removeSeat
            // The phone may have come back while the question was open.
            if (!cs.canRemove(seat)) {
                removeSeat = -1
            } else {
                ConnectDialog(
                    "Remove ${cs.seatName(seat)} from the game?" +
                        (if (!tournament) "\nThe other player wins the point." else ""),
                    "Remove", onPrimary = {
                        removeSeat = -1
                        cs.removePlayer(seat)
                    },
                    secondaryLabel = "Cancel", onSecondary = { removeSeat = -1 },
                    primarySwatch = Palette.Red
                )
            }
        }

        // This phone's own link to the host is down (or the host is gone): the game waits.
        if (cs.finalText == null && cs.link == LinkState.RECONNECTING) {
            ConnectDialog(
                "Reconnecting...\nWaiting for the host...",
                "Leave", onPrimary = { dialog = "connectLeave" }
            )
        } else if (cs.finalText == null && cs.link == LinkState.FAILED) {
            ConnectDialog(
                "Couldn't reach the host yet.",
                "Try again", onPrimary = { cs.retryReconnect() },
                secondaryLabel = "Leave", onSecondary = { dialog = "connectLeave" }
            )
        }

        // The session is over for this person: the host ended it, they were removed, or the game is gone.
        val finalMessage = cs.finalText
        if (finalMessage != null) {
            ConnectDialog(
                finalMessage,
                "OK", onPrimary = {
                    dialog = "none"
                    cs.leave()
                    onModes()
                }
            )
        }
    }
    }
}

/**
 * One of the three movement choices: 0 = first die (blue), 1 = second die (green), 2 = total (red).
 * Usable options are tappable and outlined, the chosen one is larger with a bold ring,
 * and unusable or spent options are dimmed and do nothing.
 */
@Composable
private fun MoveOrb(
    game: LudoGame,
    index: Int,
    value: Int,
    swatch: Swatch,
    u: Dp,
    allowed: Boolean,
    onTap: (Int) -> Unit
) {
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
            .clickable(enabled = usable && allowed && !game.isComputerTurn) { onTap(index) }
    )
}
