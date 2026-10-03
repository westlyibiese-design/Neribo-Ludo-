package com.westly.ludo.connect

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.westly.ludo.game.HandTarget
import com.westly.ludo.game.LudoColor
import com.westly.ludo.game.LudoGame
import com.westly.ludo.game.Phase
import com.westly.ludo.game.Piece
import com.westly.ludo.game.TOverlay
import com.westly.ludo.game.VisualEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Where this phone is in the connect flow. */
enum class ConnectState {
    /** Nothing running. */
    IDLE,

    /** Host: the lobby is being opened. */
    STARTING,

    /** Host: advertising and showing the QR code. */
    HOST_LOBBY,

    /** Guest: looking for the host's phone. */
    SEARCHING,

    /** Guest: found the host, connecting and waiting for a seat. */
    CONNECTING,

    /** Guest: has a seat and sees the live player list. */
    GUEST_LOBBY
}

/**
 * Everything the Connect and Play screens show, plus the host / guest logic behind it.
 *
 * It is owned by MainActivity (not by a screen), so moving between screens never drops the link.
 * Screens only read the observable fields and call the functions; all networking goes through
 * [ConnectManager]. Nearby callbacks arrive on the main thread, so the fields can be changed directly.
 */
class ConnectSession(context: Context, private val prefs: SharedPreferences) : ConnectManager.Listener {

    private val manager = ConnectManager(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The pointing hand of the other players (Phase 3). The game screen draws [RemoteHandPlayer.current]. */
    val hand = RemoteHandPlayer(scope)

    init {
        manager.listener = this
    }

    // ----- What the screens observe -----

    var role by mutableStateOf<ConnectRole?>(null)
        private set
    var state by mutableStateOf(ConnectState.IDLE)
        private set

    /** The 4 public room letters. */
    var roomCode by mutableStateOf("")
        private set

    /** 2, 3 or 4, including the host. */
    var playerCount by mutableStateOf(2)
        private set

    /** This phone's seat (0 = host); -1 when not seated. */
    var mySeat by mutableStateOf(-1)
        private set
    var hostName by mutableStateOf("")
        private set

    /** The text inside the QR code (host only). */
    var ticketText by mutableStateOf("")
        private set

    /** A problem the person can retry: shown with a Try again button. */
    var error by mutableStateOf<String?>(null)
        private set

    /** The link ended after the lobby was open: shown with an OK button that leads back to the menu. */
    var ended by mutableStateOf<String?>(null)
        private set

    /** The seats that are taken, in seat order. */
    val roster = mutableStateListOf<RosterEntry>()

    /** True once every seat is filled. */
    val isFull: Boolean get() = roster.size >= playerCount

    /** A stable random id for this phone, kept in the saved settings. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    // ----- The shared game (Phase 2) -----

    /**
     * The game on screen. On the host it is the real engine that runs the rules; on a guest it is a
     * display model that is only ever filled from the host's snapshots. Null outside a game.
     */
    var game by mutableStateOf<LudoGame?>(null)
        private set

    /** True from the moment a game starts until this session ends. */
    var inGame by mutableStateOf(false)
        private set

    /** 1 for the first game of the room, then one more for every new game. */
    var gameNo by mutableStateOf(0)
        private set

    /** The engine player each seat plays in the current game (index = seat). Re-drawn for every new game. */
    val playerOfSeat = mutableStateListOf<Int>()

    val isHost: Boolean get() = role == ConnectRole.HOST

    /** The engine player this phone controls (0..3), or -1 when there is no game. */
    val myPlayer: Int get() = playerOfSeat.getOrElse(mySeat) { -1 }

    /** The seat that plays [player], or -1 when nobody does (the absent colour of a 3-player game). */
    fun seatOfPlayer(player: Int): Int = playerOfSeat.indexOf(player)

    fun seatName(seat: Int): String = roster.firstOrNull { it.seat == seat }?.name ?: ""

    /** The name shown for an engine player; empty when nobody plays that player. */
    fun playerName(player: Int): String {
        val seat = seatOfPlayer(player)
        return if (seat < 0) "" else seatName(seat)
    }

    /** A person's running score. It follows the person, not the colour, because colours are re-drawn each game. */
    fun scoreOfSeat(seat: Int): Int {
        val g = game ?: return 0
        val p = playerOfSeat.getOrElse(seat) { -1 }
        return if (p < 0) 0 else g.scores.getOrElse(p) { 0 }
    }

    /** The colours a seat plays, in the order they are named on screen. */
    fun colorsOfSeat(seat: Int): List<LudoColor> {
        val p = playerOfSeat.getOrElse(seat) { -1 }
        return when {
            p < 0 -> emptyList()
            playerCount >= 3 -> listOf(TOURNAMENT_COLORS[p])
            p == 0 -> listOf(LudoColor.RED, LudoColor.YELLOW)
            else -> listOf(LudoColor.GREEN, LudoColor.BLUE)
        }
    }

    val myColors: List<LudoColor> get() = colorsOfSeat(mySeat)

    /** "You are Red" or "You are Green + Blue". */
    val youAreText: String
        get() = "You are " + myColors.joinToString(" + ") { colorWord(it) }

    /** The person whose phone dropped, or null when everybody is connected. */
    val waitingForName: String?
        get() = if (!inGame) null else roster.firstOrNull { !it.connected }?.name

    /** The screen that runs the game gives its coroutine scope (it has the frame clock the move animations need). */
    fun bindScope(ui: CoroutineScope) {
        uiScope = ui
    }

    /**
     * True when this phone may act right now, judged from the state on screen: it is this person's turn
     * to roll, choose or capture. The host re-checks everything again before it does anything.
     */
    fun canAct(): Boolean {
        val g = game ?: return false
        val me = myPlayer
        if (me < 0) return false
        return when (g.phase) {
            Phase.AwaitRoll, Phase.Choose, Phase.CaptureChoose -> g.activePlayer == me
            else -> false
        }
    }

    // ----- Taps from the game screen. The host queues them for itself, a guest sends them as an "intent". -----

    fun tapRoll() {
        if (!canAct() || game?.phase != Phase.AwaitRoll) return
        act(HostIntent(mySeat, ConnectProtocol.K_ROLL), ConnectProtocol.intent(ConnectProtocol.K_ROLL))
    }

    fun tapDie(i: Int) {
        if (!canAct() || game?.phase != Phase.Choose || i !in 0..2) return
        act(
            HostIntent(mySeat, ConnectProtocol.K_SELECT_DIE, i = i),
            ConnectProtocol.intentSelectDie(i)
        )
    }

    /** A tap on the board: the dice area rolls, a tap while choosing is a move. */
    fun tapBoard(row: Float, col: Float) {
        val g = game ?: return
        if (!canAct()) return
        when (g.phase) {
            Phase.AwaitRoll -> if (row in 6f..9f && col in 6f..9f) tapRoll()
            Phase.Choose -> act(
                HostIntent(mySeat, ConnectProtocol.K_BOARD_TAP, row = row, col = col),
                ConnectProtocol.intentBoardTap(row, col)
            )
            else -> Unit
        }
    }

    /** A tap on a pawn in the pick pop-up (which seed to move, or which opponent seed to capture). */
    fun tapPiece(tag: Any?) {
        val piece = tag as? Piece ?: return
        if (!canAct()) return
        val ph = game?.phase
        if (ph != Phase.Choose && ph != Phase.CaptureChoose) return
        act(
            HostIntent(mySeat, ConnectProtocol.K_PICK_PIECE, color = piece.color.name, slot = piece.slot),
            ConnectProtocol.intentPickPiece(piece.color.name, piece.slot)
        )
    }

    /** A tap on this person's own dice in the tie-break. */
    fun tapTie() {
        val g = game ?: return
        if (g.overlay != TOverlay.TIEBREAK || g.tieTurn < 0 || g.tieTurn != myPlayer) return
        act(HostIntent(mySeat, ConnectProtocol.K_TIE_ROLL), ConnectProtocol.intent(ConnectProtocol.K_TIE_ROLL))
    }

    /** Host only: Next Round on the round result. */
    fun tapNextRound() {
        if (!isHost) return
        act(HostIntent(0, ConnectProtocol.K_NEXT_ROUND), ConnectProtocol.intent(ConnectProtocol.K_NEXT_ROUND))
    }

    /** Host only: start the next game of the same room, with new random colours. */
    fun tapNextGame() {
        if (!isHost) return
        act(HostIntent(0, ConnectProtocol.K_NEXT_GAME), ConnectProtocol.intent(ConnectProtocol.K_NEXT_GAME))
    }

    // ----- Private working state -----

    private var token = ""
    private var started = false
    private var myName = ""
    private var hostEndpoint: String? = null

    /** The screen's coroutine scope, used to run the host's rule calls (see [bindScope]). */
    private var uiScope: CoroutineScope? = null

    /** Host: number of the last "state" sent, and the last snapshot text (only changes are sent). */
    private var seq = 0
    private var lastSent = ""

    /** Host: the engine player of each seat's score, carried from game to game. Index = seat. */
    private val seatScores = IntArray(4)

    /** Host: taps waiting to be checked and carried out, strictly one at a time. */
    private val queue = ArrayDeque<HostIntent>()
    private var draining = false
    private var runnerJob: Job? = null

    /** Guest: the newest "state" applied, and which game it belonged to. */
    private var lastSeq = 0
    private var appliedGameNo = 0

    // ----- Phase 3 working state -----

    /** Host: when the last few "ev" messages went out (to keep them under about ten a second). */
    private val evTimes = ArrayDeque<Long>()

    /** One animation a guest has been told to play. [seq] is the snapshot that follows it. */
    private class Anim(
        val kind: String,
        val seq: Int,
        val v1: Int = 0,
        val v2: Int = 0,
        val color: String = "",
        val slot: Int = 0,
        val durationMs: Long = 0L,
        val rank: Int = -1,
        val moverColor: String = "",
        val moverSlot: Int = -1
    )

    /** Guest: animations waiting to play, the first one being the one playing now. */
    private val anims = ArrayDeque<Anim>()
    private var animJob: Job? = null
    private var safetyJob: Job? = null

    /** Guest: roughly when the queued animations should all be over (SystemClock.elapsedRealtime). */
    private var busyUntil = 0L

    /** Guest: the newest snapshot that arrived while an animation was still to play. */
    private var pendingSnapshot: JSONObject? = null
    private var pendingSeq = 0

    /** Guest: an animation has run and no snapshot has been applied since (the display still shows its leftovers). */
    private var visualsDirty = false

    /** Bumped on every reset; late answers from an earlier attempt are ignored. */
    private var generation = 0

    private val seatByEndpoint = HashMap<String, Int>()
    private val helloTimers = HashMap<String, Job>()
    private var guestTimer: Job? = null
    private val random = SecureRandom()

    // ----- Starting and leaving -----

    /** Host: opens a lobby for [count] players and starts advertising. */
    fun startHosting(count: Int, name: String) {
        leave()
        val g = generation
        val clean = cleanName(name)
        role = ConnectRole.HOST
        playerCount = count.coerceIn(2, 4)
        hostName = clean
        mySeat = 0
        roomCode = randomRoom()
        token = randomToken()
        ticketText = JoinTicket(roomCode, token).toText()
        roster.add(RosterEntry(0, clean, deviceId, true, true))
        state = ConnectState.STARTING
        manager.startAdvertising(ConnectProtocol.endpointName(roomCode, clean)) { problem ->
            if (g == generation && role == ConnectRole.HOST) {
                if (problem == null) {
                    state = ConnectState.HOST_LOBBY
                } else {
                    teardown()
                    error = MSG_HOST_FAILED
                }
            }
        }
    }

    /** Guest: finds the host named in the scanned [ticket] and asks for a seat. */
    fun startJoin(ticket: JoinTicket, name: String) {
        leave()
        if (ticket.version != ConnectProtocol.PROTOCOL_VERSION) {
            error = MSG_VERSION
            return
        }
        val g = generation
        role = ConnectRole.GUEST
        roomCode = ticket.room
        token = ticket.token
        myName = cleanName(name)
        state = ConnectState.SEARCHING
        manager.startDiscovery { problem ->
            if (g == generation && problem != null) failJoin(MSG_SEARCH_FAILED)
        }
        guestTimer = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (g == generation && state == ConnectState.SEARCHING) failJoin(MSG_NOT_FOUND)
        }
    }

    /**
     * Host pressed Start with every seat filled: colours are drawn at random, the real game is created
     * on this phone, and everybody is told to open the game screen. Advertising keeps running (a later
     * phase needs it) but newcomers are turned away with "started".
     * Returns true when the start was accepted.
     */
    fun requestStart(): Boolean {
        if (role != ConnectRole.HOST || state != ConnectState.HOST_LOBBY || !isFull || started) return false
        if (roster.any { !it.connected }) return false
        if (uiScope == null) return false
        started = true
        for (i in seatScores.indices) seatScores[i] = 0
        gameNo = 0
        beginGame()
        inGame = true
        broadcastLobby()
        startPublisher()
        return true
    }

    // ----- Host: starting games, keeping the guests in sync, carrying out taps -----

    /** Who plays which engine player: 2 players = player 0 / 1 shuffled, 3 = three of the four colours, 4 = all. */
    private fun drawAssignment(count: Int): List<Int> {
        val pool = ArrayList<Int>(if (count == 2) listOf(0, 1) else listOf(0, 1, 2, 3))
        java.util.Collections.shuffle(pool, random)
        return pool.take(count)
    }

    /** Creates a fresh game with a new random assignment and the carried-over scores, and tells everybody. */
    private fun beginGame() {
        val order = drawAssignment(playerCount)
        val engine = LudoGame(tournament = order.size >= 3, connectActive = order)
        engine.visualSink = { e -> onEngineEvent(e) }
        resetLive()
        for (seat in order.indices) engine.scores[order[seat]] = seatScores[seat]
        playerOfSeat.clear()
        playerOfSeat.addAll(order)
        game = engine
        gameNo++
        val assignment = order.indices.map { seat ->
            SeatAssignment(seat, order[seat], colorsOfSeat(seat).map { it.name })
        }
        manager.send(seatByEndpoint.keys.toList(), ConnectProtocol.start(playerCount, assignment, gameNo))
        publish(force = true)
    }

    /** Host: starts the next game of the same room. The scores follow the people to their new colours. */
    private fun newGameFromHost() {
        val old = game ?: return
        for (seat in playerOfSeat.indices) seatScores[seat] = old.scores.getOrElse(playerOfSeat[seat]) { 0 }
        beginGame()
    }

    private fun startPublisher() {
        val g = generation
        scope.launch {
            while (g == generation && role == ConnectRole.HOST) {
                delay(PUBLISH_INTERVAL_MS)
                publish(force = false)
            }
        }
    }

    /**
     * Sends the game to every guest when it has changed (at most about ten times a second, always the
     * latest one). Moves in progress are not part of it: guests see the board jump to each new state.
     */
    private fun publish(force: Boolean) {
        val g = game ?: return
        if (role != ConnectRole.HOST || !started) return
        if (g.phase == Phase.GameOver) {
            for (seat in playerOfSeat.indices) seatScores[seat] = g.scores.getOrElse(playerOfSeat[seat]) { 0 }
        }
        val snapshot = g.toMirrorJson()
        val text = snapshot.toString()
        if (!force && text == lastSent) return
        lastSent = text
        seq++
        manager.send(seatByEndpoint.keys.toList(), ConnectProtocol.state(seq, gameNo, snapshot))
    }

    private fun hostResync(endpointId: String) {
        if (!started || !seatByEndpoint.containsKey(endpointId)) return
        val g = game ?: return
        seq++
        manager.send(endpointId, ConnectProtocol.state(seq, gameNo, g.toMirrorJson()))
    }

    /** A tap from a guest: read it carefully (it is untrusted), then queue it. */
    private fun hostIntent(endpointId: String, msg: JSONObject) {
        if (msg.optInt("v", -1) != ConnectProtocol.PROTOCOL_VERSION) return
        if (!started) return
        val seat = seatByEndpoint[endpointId] ?: return
        val intent = when (msg.optString("kind", "")) {
            ConnectProtocol.K_ROLL -> HostIntent(seat, ConnectProtocol.K_ROLL)
            ConnectProtocol.K_TIE_ROLL -> HostIntent(seat, ConnectProtocol.K_TIE_ROLL)
            ConnectProtocol.K_NEXT_ROUND -> HostIntent(seat, ConnectProtocol.K_NEXT_ROUND)
            ConnectProtocol.K_NEXT_GAME -> HostIntent(seat, ConnectProtocol.K_NEXT_GAME)
            ConnectProtocol.K_SELECT_DIE ->
                HostIntent(seat, ConnectProtocol.K_SELECT_DIE, i = msg.optInt("i", -1))
            ConnectProtocol.K_BOARD_TAP -> {
                val row = msg.optDouble("row", Double.NaN)
                val col = msg.optDouble("col", Double.NaN)
                if (row.isNaN() || col.isNaN() || row < -1.0 || row > 16.0 || col < -1.0 || col > 16.0) return
                HostIntent(seat, ConnectProtocol.K_BOARD_TAP, row = row.toFloat(), col = col.toFloat())
            }
            ConnectProtocol.K_PICK_PIECE -> HostIntent(
                seat, ConnectProtocol.K_PICK_PIECE,
                color = msg.optString("color", ""), slot = msg.optInt("slot", -1)
            )
            else -> return
        }
        enqueue(intent)
    }

    /** Host and guest taps go through this one route: the host's own ones are queued, a guest's are sent. */
    private fun act(intent: HostIntent, message: JSONObject) {
        when (role) {
            ConnectRole.HOST -> enqueue(intent)
            ConnectRole.GUEST -> hostEndpoint?.let { manager.send(it, message) }
            null -> Unit
        }
    }

    /**
     * Puts a tap in line. The rule functions of the engine wait for animations, so the queue is
     * emptied by one coroutine, one tap at a time: two taps never run the engine at the same moment.
     */
    private fun enqueue(intent: HostIntent) {
        if (role != ConnectRole.HOST || !started) return
        if (queue.size >= MAX_QUEUE) return
        queue.addLast(intent)
        if (draining) return
        val ui = uiScope ?: return
        draining = true
        val g = generation
        runnerJob = ui.launch {
            try {
                while (queue.isNotEmpty() && g == generation) {
                    val next = queue.removeFirst()
                    try {
                        process(next)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A tap that cannot be carried out is dropped; the game goes on.
                    }
                }
            } finally {
                draining = false
            }
        }
    }

    /**
     * Checks a tap against the live game and, only if it is allowed right now, calls the engine's own
     * function for it. The sender's seat must be the player whose turn it is (for a tie-break roll: the
     * player whose roll is next; for Next Round / next game: the host). Anything else is dropped silently,
     * and the engine's own phase checks stay the final authority.
     */
    private suspend fun process(req: HostIntent) {
        val g = game ?: return
        if (!started || role != ConnectRole.HOST) return
        val player = playerOfSeat.getOrElse(req.seat) { -1 }
        if (player < 0) return
        val myTurn = !g.bannerPending && g.overlay == TOverlay.NONE && g.activePlayer == player
        when (req.kind) {
            ConnectProtocol.K_ROLL ->
                if (myTurn && g.phase == Phase.AwaitRoll) {
                    announceHand(req.seat, player, HandTarget.Dice, g.firstColorOf(player), true)
                    g.roll()
                }
            ConnectProtocol.K_SELECT_DIE ->
                if (myTurn && g.phase == Phase.Choose && req.i in 0..2) {
                    if (g.optionUsable(req.i)) {
                        announceHand(req.seat, player, HandTarget.Orb(req.i), g.firstColorOf(player), false)
                    }
                    g.selectDie(req.i)
                }
            ConnectProtocol.K_BOARD_TAP ->
                if (myTurn && (g.phase == Phase.AwaitRoll || g.phase == Phase.Choose)) {
                    if (g.phase == Phase.AwaitRoll) {
                        if (req.row in 6f..9f && req.col in 6f..9f) {
                            announceHand(req.seat, player, HandTarget.Dice, g.firstColorOf(player), true)
                        }
                    } else {
                        val hit = g.boardTapPiece(req.row, req.col)
                        if (hit != null) announceHand(req.seat, player, g.handSpotOf(hit), hit.color, true)
                    }
                    g.onBoardTap(req.row, req.col)
                }
            ConnectProtocol.K_PICK_PIECE ->
                if (myTurn && (g.phase == Phase.Choose || g.phase == Phase.CaptureChoose)) {
                    val piece = g.pieces.firstOrNull { it.color.name == req.color && it.slot == req.slot }
                    if (piece != null) {
                        val spot = g.pickSpotOf(piece)
                        if (spot != null) {
                            announceHand(req.seat, player, spot, g.captureMoverColor() ?: piece.color, true)
                        }
                        g.onPiecePicked(piece)
                    }
                }
            ConnectProtocol.K_TIE_ROLL ->
                if (g.overlay == TOverlay.TIEBREAK && g.tieTurn == player) g.tieHumanRoll()
            ConnectProtocol.K_NEXT_ROUND ->
                if (req.seat == 0 && g.overlay == TOverlay.RESULT) g.resultNext()
            ConnectProtocol.K_NEXT_GAME ->
                if (req.seat == 0 && g.phase == Phase.GameOver) newGameFromHost()
            else -> Unit
        }
    }

    // ----- Phase 3: live view, host side -----

    /** Sends one "ev" to every seated phone. Hand events (cosmetic, droppable) are skipped when too many went out. */
    private fun sendEv(message: JSONObject, droppable: Boolean) {
        if (role != ConnectRole.HOST || !started) return
        val now = SystemClock.elapsedRealtime()
        while (evTimes.isNotEmpty() && now - evTimes.first() > 1000L) evTimes.removeFirst()
        if (droppable && evTimes.size >= MAX_EV_PER_SECOND) return
        evTimes.addLast(now)
        if (seatByEndpoint.isEmpty()) return
        manager.send(seatByEndpoint.keys.toList(), message)
    }

    /**
     * The host's rules are about to animate something: tell everybody, at the moment it starts. The "seq" is
     * the number the next snapshot will have, so a phone knows which snapshot to hold back until it has played it.
     */
    private fun onEngineEvent(e: VisualEvent) {
        if (role != ConnectRole.HOST || !started) return
        val next = seq + 1
        val message = when (e) {
            is VisualEvent.Roll -> ConnectProtocol.evRoll(next, e.player, e.a, e.b)
            is VisualEvent.Move -> ConnectProtocol.evMove(
                next, e.piece.color.name, e.piece.slot, e.from, e.to, e.durationMs, e.rank
            )
            is VisualEvent.Capture -> ConnectProtocol.evCapture(
                next, e.victim.color.name, e.victim.slot, e.mover.color.name, e.mover.slot
            )
            is VisualEvent.TieRoll -> ConnectProtocol.evTieRoll(next, e.player, e.value)
        }
        sendEv(message, false)
    }

    /**
     * Tells everybody where [player]'s hand goes, just before the host carries out that (already checked) tap.
     * The host shows the hand of a guest's tap itself; its own taps get no hand on its own screen. The
     * host never waits for it unless [HOST_LEAD_FOR_GUESTS_MS] is raised above 0.
     */
    private suspend fun announceHand(seat: Int, player: Int, target: HandTarget, color: LudoColor, animated: Boolean) {
        sendEv(ConnectProtocol.evHand(seq + 1, player, color.name, target), true)
        if (seat != 0) {
            hand.add(target, color)
            if (animated && HOST_LEAD_FOR_GUESTS_MS > 0L) delay(HOST_LEAD_FOR_GUESTS_MS)
        }
    }

    /** Forgets everything about animations and hands in progress (a new game, or the end of the session). */
    private fun resetLive() {
        animJob?.cancel()
        animJob = null
        safetyJob?.cancel()
        safetyJob = null
        anims.clear()
        pendingSnapshot = null
        pendingSeq = 0
        busyUntil = 0L
        visualsDirty = false
        evTimes.clear()
        hand.clear()
    }

    /** Hides the "Try again" / "OK" notices. */
    fun clearNotices() {
        error = null
        ended = null
    }

    /**
     * Leaves whatever is running: tells the other phones, stops advertising and discovery, closes
     * every connection and clears the screen state. Safe to call at any time, also when idle.
     */
    fun leave() {
        if (role == ConnectRole.HOST && seatByEndpoint.isNotEmpty()) {
            manager.send(seatByEndpoint.keys.toList(), ConnectProtocol.bye("closed"))
        } else if (role == ConnectRole.GUEST && state == ConnectState.GUEST_LOBBY) {
            hostEndpoint?.let { manager.send(it, ConnectProtocol.bye("left")) }
        }
        teardown()
        error = null
        ended = null
    }

    /** Final clean-up when the app is closed. */
    fun release() {
        leave()
        scope.cancel()
    }

    /** Stops everything and resets the working state, but leaves any notice for the screen to show. */
    private fun teardown() {
        generation++
        scope.coroutineContext.cancelChildren()
        resetLive()
        helloTimers.clear()
        guestTimer = null
        manager.stopAll()
        seatByEndpoint.clear()
        hostEndpoint = null
        started = false
        runnerJob?.cancel()
        runnerJob = null
        queue.clear()
        draining = false
        seq = 0
        lastSent = ""
        lastSeq = 0
        appliedGameNo = 0
        for (i in seatScores.indices) seatScores[i] = 0
        game = null
        inGame = false
        gameNo = 0
        playerOfSeat.clear()
        token = ""
        role = null
        state = ConnectState.IDLE
        roomCode = ""
        mySeat = -1
        hostName = ""
        ticketText = ""
        roster.clear()
    }

    private fun failJoin(message: String) {
        teardown()
        error = message
    }

    // ----- Nearby events -----

    override fun onEndpointFound(endpointId: String, endpointName: String) {
        if (role != ConnectRole.GUEST || state != ConnectState.SEARCHING) return
        val prefix = ConnectProtocol.endpointPrefix(roomCode)
        if (!endpointName.startsWith(prefix)) return
        val g = generation
        hostEndpoint = endpointId
        hostName = endpointName.substring(prefix.length).take(MAX_NAME)
        state = ConnectState.CONNECTING
        guestTimer?.cancel()
        manager.stopDiscovery()
        manager.requestConnection(myName, endpointId) { problem ->
            if (g == generation && problem != null) failJoin(MSG_CONNECT_FAILED)
        }
        guestTimer = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (g == generation && state == ConnectState.CONNECTING) failJoin(MSG_CONNECT_FAILED)
        }
    }

    override fun onConnected(endpointId: String) {
        when (role) {
            ConnectRole.HOST -> {
                // Not trusted until a valid "hello" arrives; drop the phone if it stays silent.
                val g = generation
                helloTimers[endpointId] = scope.launch {
                    delay(HELLO_TIMEOUT_MS)
                    if (g == generation && !seatByEndpoint.containsKey(endpointId)) {
                        manager.disconnect(endpointId)
                    }
                    helloTimers.remove(endpointId)
                }
            }
            ConnectRole.GUEST -> {
                if (endpointId == hostEndpoint) {
                    manager.send(
                        endpointId,
                        ConnectProtocol.hello(token, myName, deviceId, "player")
                    )
                }
            }
            null -> manager.disconnect(endpointId)
        }
    }

    override fun onConnectionFailed(endpointId: String) {
        if (role == ConnectRole.GUEST && endpointId == hostEndpoint && state == ConnectState.CONNECTING) {
            failJoin(MSG_CONNECT_FAILED)
        }
    }

    override fun onDisconnected(endpointId: String) {
        when (role) {
            ConnectRole.HOST -> hostDropEndpoint(endpointId)
            ConnectRole.GUEST -> {
                if (endpointId != hostEndpoint) return
                if (state == ConnectState.GUEST_LOBBY) {
                    val message = if (inGame) MSG_HOST_LOST else MSG_HOST_GONE
                    teardown()
                    ended = message
                } else if (state == ConnectState.CONNECTING) {
                    failJoin(MSG_CONNECT_FAILED)
                }
            }
            null -> Unit
        }
    }

    override fun onPayload(endpointId: String, bytes: ByteArray) {
        val msg = ConnectProtocol.decode(bytes) ?: return
        try {
            when (role) {
                ConnectRole.HOST -> hostHandle(endpointId, msg)
                ConnectRole.GUEST -> if (endpointId == hostEndpoint) guestHandle(msg)
                null -> Unit
            }
        } catch (e: Exception) {
            // A bad message must never crash the app: drop it.
        }
    }

    // ----- Host side -----

    private fun hostHandle(endpointId: String, msg: JSONObject) {
        when (msg.optString("t")) {
            ConnectProtocol.T_HELLO -> hostHello(endpointId, msg)
            ConnectProtocol.T_INTENT -> hostIntent(endpointId, msg)
            ConnectProtocol.T_RESYNC -> {
                if (msg.optInt("v", -1) == ConnectProtocol.PROTOCOL_VERSION) hostResync(endpointId)
            }
            ConnectProtocol.T_BYE -> {
                if (msg.optInt("v", -1) != ConnectProtocol.PROTOCOL_VERSION) return
                hostDropEndpoint(endpointId)
                manager.disconnect(endpointId)
            }
            else -> Unit // unknown type: ignore
        }
    }

    private fun hostHello(endpointId: String, msg: JSONObject) {
        if (seatByEndpoint.containsKey(endpointId)) return // already seated: ignore a repeat
        val sentToken = msg.optString("token", "")
        val reason = when {
            msg.optInt("v", -1) != ConnectProtocol.PROTOCOL_VERSION -> "version"
            !sameText(sentToken, token) -> "bad_token"
            msg.optString("role", "player") == "watcher" -> "watchers_off"
            started -> "started"
            freeSeat() < 0 -> "full"
            else -> null
        }
        if (reason != null) {
            manager.send(endpointId, ConnectProtocol.reject(reason))
            // Give the reject a moment to arrive before the link is closed.
            val g = generation
            scope.launch {
                delay(REJECT_CLOSE_DELAY_MS)
                if (g == generation) manager.disconnect(endpointId)
            }
            return
        }
        helloTimers.remove(endpointId)?.cancel()
        val seat = freeSeat()
        val name = uniqueName(cleanName(msg.optString("name", "")))
        seatByEndpoint[endpointId] = seat
        val entry = RosterEntry(seat, name, msg.optString("deviceId", ""), true, false)
        val at = roster.indexOfFirst { it.seat > seat }
        if (at < 0) roster.add(entry) else roster.add(at, entry)
        manager.send(
            endpointId,
            ConnectProtocol.welcome(seat, roomCode, hostName, playerCount, roster.toList())
        )
        broadcastLobby()
    }

    private fun hostDropEndpoint(endpointId: String) {
        helloTimers.remove(endpointId)?.cancel()
        val seat = seatByEndpoint.remove(endpointId) ?: return
        val at = roster.indexOfFirst { it.seat == seat }
        if (started) {
            // In a game the seat stays (its colours stay too); it is only marked as not connected, and
            // everybody sees "Waiting for ... to reconnect...". Coming back is a later phase.
            if (at >= 0) {
                val e = roster[at]
                roster[at] = RosterEntry(e.seat, e.name, e.deviceId, false, e.isHost)
            }
        } else if (at >= 0) {
            roster.removeAt(at)
        }
        broadcastLobby()
    }

    private fun broadcastLobby() {
        manager.send(
            seatByEndpoint.keys.toList(),
            ConnectProtocol.lobby(playerCount, started, roster.toList())
        )
    }

    private fun freeSeat(): Int {
        for (seat in 1 until playerCount) {
            if (roster.none { it.seat == seat }) return seat
        }
        return -1
    }

    /** The second person called "Me" becomes "Me (2)", the third "Me (3)". */
    private fun uniqueName(base: String): String {
        val taken = roster.map { it.name.lowercase() }
        var candidate = base
        var n = 2
        while (candidate.lowercase() in taken) {
            candidate = "${base.take(10)} ($n)"
            n++
        }
        return candidate
    }

    // ----- Guest side -----

    private fun guestHandle(msg: JSONObject) {
        if (msg.optInt("v", -1) != ConnectProtocol.PROTOCOL_VERSION) return
        when (msg.optString("t")) {
            ConnectProtocol.T_WELCOME -> {
                val seat = msg.optInt("seat", -1)
                val count = msg.optInt("playerCount", 0)
                if (seat < 1 || count !in 2..4) return
                guestTimer?.cancel()
                playerCount = count
                mySeat = seat
                hostName = cleanName(msg.optString("hostName", hostName))
                setRoster(ConnectProtocol.rosterFromJson(msg.optJSONArray("roster")))
                state = ConnectState.GUEST_LOBBY
            }
            ConnectProtocol.T_LOBBY -> {
                if (state != ConnectState.GUEST_LOBBY) return
                val count = msg.optInt("playerCount", playerCount)
                if (count in 2..4) playerCount = count
                setRoster(ConnectProtocol.rosterFromJson(msg.optJSONArray("roster")))
            }
            ConnectProtocol.T_START -> guestStart(msg)
            ConnectProtocol.T_STATE -> guestState(msg)
            ConnectProtocol.T_EV -> guestEvent(msg)
            ConnectProtocol.T_REJECT -> failJoin(rejectMessage(msg.optString("reason", "")))
            ConnectProtocol.T_BYE -> {
                val message = if (inGame) MSG_GAME_ENDED else MSG_HOST_GONE
                teardown()
                ended = message
            }
            else -> Unit // unknown type: ignore
        }
    }

    /** The host started a game (or the next one): build a fresh display model and wait for the first state. */
    private fun guestStart(msg: JSONObject) {
        if (state != ConnectState.GUEST_LOBBY) return
        val n = msg.optInt("playerCount", 0)
        if (n !in 2..4) return
        val arr = msg.optJSONArray("assignment") ?: return
        val order = IntArray(n) { -1 }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val seat = o.optInt("seat", -1)
            val player = o.optInt("player", -1)
            if (seat !in 0 until n || player !in 0..3) continue
            order[seat] = player
        }
        if (order.any { it < 0 } || order.toSet().size != n) return
        if (n == 2 && order.any { it > 1 }) return
        val no = msg.optInt("gameNo", 0)
        if (no <= 0) return
        playerCount = n
        playerOfSeat.clear()
        for (p in order) playerOfSeat.add(p)
        gameNo = no
        resetLive()
        game = LudoGame(tournament = n >= 3, connectActive = order.toList())
        inGame = true
        // The first state normally follows at once; if it never comes, ask for it.
        val g = generation
        scope.launch {
            var tries = 0
            while (g == generation && appliedGameNo != no && tries < MAX_RESYNC_TRIES) {
                delay(RESYNC_WAIT_MS)
                if (g == generation && appliedGameNo != no) {
                    hostEndpoint?.let { manager.send(it, ConnectProtocol.resync()) }
                    tries++
                }
            }
        }
    }

    /** A snapshot from the host. Older ones and ones of another game are dropped; a damaged one is ignored. */
    private fun guestState(msg: JSONObject) {
        if (game == null) return
        val s = msg.optInt("seq", -1)
        if (s <= lastSeq || msg.optInt("gameNo", -1) != gameNo) return
        val snapshot = msg.optJSONObject("game") ?: return
        val head = anims.firstOrNull()
        if (head != null && s >= head.seq) {
            // An animation that comes before this snapshot has still to play: keep only the newest one for later.
            if (pendingSnapshot == null || s > pendingSeq) {
                pendingSnapshot = snapshot
                pendingSeq = s
            }
            return
        }
        applyGuestSnapshot(snapshot, s)
    }

    /**
     * Puts a snapshot on screen: copies the values, drops the leftovers of any animation, and plays the
     * round-win jingle once when the snapshot is the one that ends a round or the game.
     */
    private fun applyGuestSnapshot(snapshot: JSONObject, s: Int) {
        val g = game ?: return
        val first = lastSeq == 0
        val before = g.phase
        try {
            g.applyMirror(snapshot)
        } catch (e: Exception) {
            return
        }
        if (anims.isEmpty()) {
            // Nothing is playing: the animations' leftovers (a seed standing at its goal, dice faces) give way to the real board.
            g.clearVisuals()
            visualsDirty = false
        }
        lastSeq = s
        appliedGameNo = gameNo
        val ends = g.phase == Phase.GameOver || g.phase == Phase.RoundBreak
        val wasEnd = before == Phase.GameOver || before == Phase.RoundBreak
        if (ends && !wasEnd && !first) g.playJingle()
    }

    // ----- Phase 3: live view, guest side -----

    /**
     * A live event from the host: either a hand to show, or an animation to play. Events are cosmetic: a
     * damaged, late or lost one changes nothing, because the next snapshot always puts the board right.
     */
    private fun guestEvent(msg: JSONObject) {
        val g = game ?: return
        if (!inGame) return
        val kind = msg.optString("k", "")
        if (kind == ConnectProtocol.EV_HAND) {
            val player = msg.optInt("player", -1)
            if (player !in 0..3 || player == myPlayer) return   // the person's own hand is their finger
            val target = ConnectProtocol.handTargetFromJson(msg.optJSONObject("target")) ?: return
            val color = ConnectProtocol.colorFromName(msg.optString("color", "")) ?: return
            hand.add(target, color)
            return
        }
        if (uiScope == null) return   // no screen to animate on: the snapshots alone will do
        val evSeq = msg.optInt("seq", -1)
        if (evSeq <= lastSeq) return  // the snapshot that follows it is already on screen: too late to show it
        val anim: Anim = when (kind) {
            ConnectProtocol.EV_ROLL -> {
                val a = msg.optInt("a", 0)
                val b = msg.optInt("b", 0)
                if (a !in 1..6 || b !in 1..6) return
                Anim(kind, evSeq, v1 = a, v2 = b)
            }
            ConnectProtocol.EV_MOVE -> {
                val color = msg.optString("color", "")
                val slot = msg.optInt("slot", -1)
                val from = msg.optInt("from", -99)
                val to = msg.optInt("to", -99)
                val ms = msg.optLong("durationMs", -1L)
                if (g.pieceByName(color, slot) == null) return
                if (from !in -1..55 || to !in 0..56 || to < from || ms !in 8L..8000L) return
                Anim(kind, evSeq, v1 = from, v2 = to, color = color, slot = slot, durationMs = ms,
                    rank = msg.optInt("rank", -1))
            }
            ConnectProtocol.EV_CAPTURE -> {
                val color = msg.optString("color", "")
                val slot = msg.optInt("slot", -1)
                if (g.pieceByName(color, slot) == null) return
                Anim(kind, evSeq, color = color, slot = slot,
                    moverColor = msg.optString("mColor", ""), moverSlot = msg.optInt("mSlot", -1))
            }
            ConnectProtocol.EV_TIE_ROLL -> {
                val player = msg.optInt("player", -1)
                val value = msg.optInt("value", 0)
                if (player !in 0..3 || value !in 1..6) return
                Anim(kind, evSeq, v1 = player, v2 = value)
            }
            else -> return   // unknown kind: ignore
        }
        anims.addLast(anim)
        busyUntil = maxOf(busyUntil, SystemClock.elapsedRealtime()) + HAND_LEAD_MS + expectedMs(anim)
        armSafety()
        runAnims()
    }

    /** About how long an animation takes, used only to size the safety timer. */
    private fun expectedMs(a: Anim): Long = when (a.kind) {
        ConnectProtocol.EV_MOVE -> a.durationMs + 300L
        ConnectProtocol.EV_CAPTURE -> 600L
        else -> 700L
    }

    /**
     * How long to wait before an animation starts, so the hand of the person who acted gets there first.
     * The person who acted never sees a hand, so for them the last hand is old and there is no wait.
     */
    private fun leadMs(a: Anim): Long {
        if (a.kind == ConnectProtocol.EV_TIE_ROLL) return 0L
        val at = hand.lastQueuedAt
        if (at == 0L) return 0L
        val waited = SystemClock.elapsedRealtime() - at
        return (HAND_LEAD_MS - waited).coerceIn(0L, HAND_LEAD_MS)
    }

    /** Plays the queued animations one after the other on the screen's scope (it has the frame clock). */
    private fun runAnims() {
        if (animJob?.isActive == true) return
        val ui = uiScope ?: return
        val gen = generation
        animJob = ui.launch {
            while (gen == generation && anims.isNotEmpty()) {
                val a = anims.first()
                val g = game ?: break
                val wait = leadMs(a)
                if (wait > 0L) delay(wait)
                if (gen != generation) return@launch
                playAnim(g, a)
                visualsDirty = true
                anims.removeFirstOrNull()
            }
            if (gen == generation) animationsDone()
        }
    }

    private suspend fun playAnim(g: LudoGame, a: Anim) {
        when (a.kind) {
            ConnectProtocol.EV_ROLL -> g.observeRoll(a.v1, a.v2)
            ConnectProtocol.EV_MOVE -> {
                val piece = g.pieceByName(a.color, a.slot)
                if (piece != null) g.observeMove(piece, a.v1, a.v2, a.durationMs, a.rank)
            }
            ConnectProtocol.EV_CAPTURE -> {
                val victim = g.pieceByName(a.color, a.slot)
                if (victim != null) g.observeCapture(victim, g.pieceByName(a.moverColor, a.moverSlot))
            }
            ConnectProtocol.EV_TIE_ROLL -> g.observeTieRoll(a.v1, a.v2)
            else -> Unit
        }
    }

    /** Every queued animation has played: show the snapshot that was held back for them. */
    private fun animationsDone() {
        val snap = pendingSnapshot ?: return   // not here yet: it will be applied the moment it arrives
        pendingSnapshot = null
        applyGuestSnapshot(snap, pendingSeq)
    }

    /**
     * Safety net: if the animations are not over well after they should have been (for example the screen
     * stopped drawing), or no snapshot comes after them, stop waiting. Held snapshots are applied at once;
     * otherwise the host is asked for a fresh one. A lost event can therefore never freeze this phone.
     */
    private fun armSafety() {
        safetyJob?.cancel()
        val g = generation
        val wait = (busyUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + SAFETY_MS
        safetyJob = scope.launch {
            delay(wait)
            if (g != generation || role != ConnectRole.GUEST) return@launch
            if (anims.isEmpty() && pendingSnapshot == null && !visualsDirty) return@launch
            animJob?.cancel()
            animJob = null
            anims.clear()
            val snap = pendingSnapshot
            if (snap != null) {
                pendingSnapshot = null
                applyGuestSnapshot(snap, pendingSeq)
            } else {
                hostEndpoint?.let { manager.send(it, ConnectProtocol.resync()) }
            }
        }
    }

    private fun setRoster(list: List<RosterEntry>) {
        roster.clear()
        roster.addAll(list.sortedBy { it.seat })
    }

    private fun rejectMessage(reason: String): String = when (reason) {
        "bad_token" -> "This code is no longer valid. Ask the host to show the QR code again."
        "full" -> "This game is full."
        "started" -> "This game has already started."
        "version" -> MSG_VERSION
        "watchers_off" -> "The host is not allowing watchers."
        else -> MSG_CONNECT_FAILED
    }

    // ----- Small helpers -----

    private fun cleanName(raw: String): String =
        raw.replace('|', ' ').trim().take(MAX_NAME).ifEmpty { "Player" }

    private fun randomRoom(): String {
        val sb = StringBuilder()
        repeat(ConnectProtocol.ROOM_LENGTH) {
            sb.append(ConnectProtocol.ROOM_ALPHABET[random.nextInt(ConnectProtocol.ROOM_ALPHABET.length)])
        }
        return sb.toString()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(ConnectProtocol.TOKEN_LENGTH / 2)
        random.nextBytes(bytes)
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    private fun colorWord(c: LudoColor): String = when (c) {
        LudoColor.RED -> "Red"
        LudoColor.GREEN -> "Green"
        LudoColor.YELLOW -> "Yellow"
        LudoColor.BLUE -> "Blue"
    }

    /** One tap waiting for the host to check it. [seat] is whoever made it (0 = the host itself). */
    private class HostIntent(
        val seat: Int,
        val kind: String,
        val i: Int = 0,
        val row: Float = 0f,
        val col: Float = 0f,
        val color: String = "",
        val slot: Int = 0
    )

    /** Compares two texts without stopping at the first difference. */
    private fun sameText(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        const val KEY_DEVICE_ID = "connect_device_id"

        /** Display names are cut to this many characters. */
        const val MAX_NAME = 14

        private const val HELLO_TIMEOUT_MS = 10_000L
        private const val DISCOVERY_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REJECT_CLOSE_DELAY_MS = 400L
        private const val PUBLISH_INTERVAL_MS = 100L
        private const val RESYNC_WAIT_MS = 2_000L
        private const val MAX_RESYNC_TRIES = 3
        private const val MAX_QUEUE = 40

        /** Phase 3: other phones wait this long after a hand arrives before they start the animation, so the hand gets there first. */
        const val HAND_LEAD_MS = 600L

        /** Phase 3: how long a phone waits beyond the expected end of the animations before it stops waiting. */
        private const val SAFETY_MS = 3_000L

        /** Phase 3: at most this many "ev" messages a second (hand events are skipped beyond it). */
        private const val MAX_EV_PER_SECOND = 10

        /**
         * Phase 3: the host's own screen shows a guest's hand, but the host's rules run at once, so there the hand
         * and the animation start together. Raise this (in milliseconds) to make the host wait for the hand;
         * the guest who tapped then waits just as long. 0 keeps every tap instant.
         */
        private const val HOST_LEAD_FOR_GUESTS_MS = 0L

        /** Tournament-style engine players 0..3 = red, green, yellow, blue. */
        private val TOURNAMENT_COLORS = listOf(LudoColor.RED, LudoColor.GREEN, LudoColor.YELLOW, LudoColor.BLUE)

        private const val MSG_HOST_FAILED =
            "Couldn't open the lobby. Check that Bluetooth and Wi-Fi are on, then try again."
        private const val MSG_SEARCH_FAILED =
            "Couldn't look for nearby phones. Check that Bluetooth and Wi-Fi are on, then try again."
        private const val MSG_NOT_FOUND =
            "Couldn't find the host. Stand closer to the host phone and try again."
        private const val MSG_CONNECT_FAILED =
            "Couldn't connect to the host. Please try again."
        private const val MSG_VERSION = "Please update Ludo Mate on both phones."
        private const val MSG_HOST_GONE = "Disconnected from the host."
        private const val MSG_HOST_LOST = "Lost the connection to the host."
        private const val MSG_GAME_ENDED = "The host ended the game."
    }
}
