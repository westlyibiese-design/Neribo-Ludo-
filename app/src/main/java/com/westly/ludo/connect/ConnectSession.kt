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
import org.json.JSONArray
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

    /** Guest: has a seat (or a watcher's place) and sees the live player list. */
    GUEST_LOBBY
}

/** Guest, while a game is on screen: how the link to the host is doing. */
enum class LinkState {
    /** Connected (or nothing is wrong that this phone knows about). */
    OK,

    /** The link dropped; the phone is looking for the host again by itself. */
    RECONNECTING,

    /** The 30 seconds ran out: the screen offers Try again. */
    FAILED
}

/** The game this guest can return to (shown as the Rejoin button). */
class RejoinInfo(val room: String, val hostName: String, val watcher: Boolean)

/** The game this host can resume (shown as the Resume hosted game button). */
class HostSaveInfo(val room: String, val playerCount: Int)

/**
 * Everything the Connect and Play screens show, plus the host / guest logic behind it.
 *
 * It is owned by MainActivity (not by a screen), so moving between screens never drops the link.
 * Screens only read the observable fields and call the functions; all networking goes through
 * [ConnectManager]. Nearby callbacks arrive on the main thread, so the fields can be changed directly.
 */
class ConnectSession(context: Context, private val prefs: SharedPreferences) : ConnectManager.Listener, LiveSession {

    private val manager = ConnectManager(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The pointing hand of the other players (Phase 3). The game screen draws [RemoteHandPlayer.current]. */
    override val hand = RemoteHandPlayer(scope)

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
    override var playerCount by mutableStateOf(2)
        private set

    /** This phone's seat (0 = host); -1 when not seated. */
    var mySeat by mutableStateOf(-1)
        private set
    override var hostName by mutableStateOf("")
        private set

    /** The text inside the QR code (host only). */
    override var ticketText by mutableStateOf("")
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

    // ----- Phase 4: leave and return -----

    /** Guest: how the link to the host is doing while a game is on screen. */
    override var link by mutableStateOf(LinkState.OK)
        private set

    /** Guest: this phone has no seat and only watches. */
    override var watching by mutableStateOf(false)
        private set

    /** Host: whether people without a seat may scan the QR code and watch. Off by default. */
    override var allowWatchers by mutableStateOf(false)
        private set

    /** Host: how many watchers are connected right now. */
    override var watcherCount by mutableStateOf(0)
        private set

    /**
     * A message that covers the game screen with one OK button: the host ended the session, this person
     * was removed, or the game could not be found. The game screen stays up until OK so the person can read it.
     */
    override var finalText by mutableStateOf<String?>(null)
        private set

    /** Guest: the game this phone can return to, or null (drives the Rejoin button). */
    var rejoinInfo by mutableStateOf<RejoinInfo?>(null)
        private set

    /** Host: the saved game that can be resumed, or null (drives the Resume hosted game button). */
    var hostSaveInfo by mutableStateOf<HostSaveInfo?>(null)
        private set

    init {
        rejoinInfo = readSavedSession()?.let { RejoinInfo(it.room, it.hostName, it.watcher) }
        hostSaveInfo = readHostSaveInfo()
    }

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

    override val isHost: Boolean get() = role == ConnectRole.HOST

    /** The engine player this phone controls (0..3), or -1 when there is no game. */
    override val myPlayer: Int get() = playerOfSeat.getOrElse(mySeat) { -1 }

    /** The seat that plays [player], or -1 when nobody does (the absent colour of a 3-player game). */
    override fun seatOfPlayer(player: Int): Int = playerOfSeat.indexOf(player)

    override fun seatName(seat: Int): String = roster.firstOrNull { it.seat == seat }?.name ?: ""

    /** The name shown for an engine player; empty when nobody plays that player. */
    override fun playerName(player: Int): String {
        val seat = seatOfPlayer(player)
        return if (seat < 0) "" else seatName(seat)
    }

    /** A person's running score. It follows the person, not the colour, because colours are re-drawn each game. */
    override fun scoreOfSeat(seat: Int): Int {
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

    override val myColors: List<LudoColor> get() = colorsOfSeat(mySeat)

    /** "You are Red" or "You are Green + Blue" ("You are watching" for a watcher). */
    override val youAreText: String
        get() = if (watching) "You are watching"
        else "You are " + myColors.joinToString(" + ") { colorWord(it) }

    /** True when this person was knocked out of the tournament and is only watching the rest. */
    override val iAmOut: Boolean
        get() {
            val g = game ?: return false
            val p = myPlayer
            return p >= 0 && g.tournament && !g.active[p]
        }

    /** True when [seat] was knocked out of the tournament. */
    fun isSeatOut(seat: Int): Boolean {
        val g = game ?: return false
        val p = playerOfSeat.getOrElse(seat) { -1 }
        return p >= 0 && g.tournament && !g.active[p]
    }

    /** True when somebody was removed by the host (no new game can start then). */
    override val hasRemoved: Boolean get() = roster.any { it.removed }

    /** A seat whose phone dropped and that the game still needs (not knocked out, not removed). */
    private fun isGone(seat: Int): Boolean {
        val e = roster.firstOrNull { it.seat == seat } ?: return false
        return !e.connected && !e.removed && !isSeatOut(seat)
    }

    /**
     * The seat everybody is waiting for, or -1. A dropped person whose turn it is (or whose tie-break roll
     * is due) comes first; otherwise the first dropped person. Nothing is shown while this phone's own link is down.
     */
    override val waitingForSeat: Int
        get() {
            if (!inGame || link != LinkState.OK) return -1
            val g = game
            if (g != null) {
                val blocker = when {
                    g.overlay == TOverlay.TIEBREAK -> g.tieTurn
                    g.overlay == TOverlay.NONE && g.phase != Phase.GameOver -> g.activePlayer
                    else -> -1
                }
                if (blocker >= 0) {
                    val s = seatOfPlayer(blocker)
                    if (s >= 0 && isGone(s)) return s
                }
            }
            return roster.firstOrNull { isGone(it.seat) }?.seat ?: -1
        }

    /** The person whose phone dropped, or null when everybody is connected. */
    val waitingForName: String?
        get() {
            val s = waitingForSeat
            return if (s < 0) null else seatName(s)
        }

    /**
     * Host only: true when the seat's connection is lost and the game is at a moment where the person can
     * be taken out (never for a connected person, never for the host, never while a result is showing).
     */
    override fun canRemove(seat: Int): Boolean {
        if (role != ConnectRole.HOST || !started || !inGame || sessionEnded || seat <= 0) return false
        val g = game ?: return false
        val e = roster.firstOrNull { it.seat == seat } ?: return false
        if (e.connected || e.removed) return false
        val p = playerOfSeat.getOrElse(seat) { -1 }
        if (p < 0 || g.phase == Phase.GameOver) return false
        if (g.tournament && !g.active[p]) return false
        return when (g.overlay) {
            TOverlay.NONE -> true
            TOverlay.TIEBREAK -> p in g.tieIds
            else -> false
        }
    }

    /** The screen that runs the game gives its coroutine scope (it has the frame clock the move animations need). */
    fun bindScope(ui: CoroutineScope) {
        uiScope = ui
    }

    /**
     * True when this phone may act right now, judged from the state on screen: it is this person's turn
     * to roll, choose or capture. The host re-checks everything again before it does anything.
     */
    override fun canAct(): Boolean {
        val g = game ?: return false
        val me = myPlayer
        if (me < 0 || watching || finalText != null || link != LinkState.OK) return false
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

    override fun tapDie(i: Int) {
        if (!canAct() || game?.phase != Phase.Choose || i !in 0..2) return
        act(
            HostIntent(mySeat, ConnectProtocol.K_SELECT_DIE, i = i),
            ConnectProtocol.intentSelectDie(i)
        )
    }

    /** A tap on the board: the dice area rolls, a tap while choosing is a move. */
    override fun tapBoard(row: Float, col: Float) {
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
    override fun tapPiece(tag: Any?) {
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
    override fun tapTie() {
        val g = game ?: return
        if (g.overlay != TOverlay.TIEBREAK || g.tieTurn < 0 || g.tieTurn != myPlayer) return
        act(HostIntent(mySeat, ConnectProtocol.K_TIE_ROLL), ConnectProtocol.intent(ConnectProtocol.K_TIE_ROLL))
    }

    /** Host only: Next Round on the round result. */
    override fun tapNextRound() {
        if (!isHost) return
        act(HostIntent(0, ConnectProtocol.K_NEXT_ROUND), ConnectProtocol.intent(ConnectProtocol.K_NEXT_ROUND))
    }

    /** Host only: start the next game of the same room, with new random colours. */
    override fun tapNextGame() {
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

    // ----- Phase 4 working state -----

    /** Host: the watchers (people without a seat) that are connected: endpoint -> their device id. */
    private val watcherByEndpoint = HashMap<String, String>()

    /** Host: the session was ended with End Game / End Tournament; the host stays on a final panel until it leaves. */
    private var sessionEnded = false

    /** Host: the "ended" message, kept so that a phone that comes back while the final panel is up gets the same news. */
    private var lastEnded: JSONObject? = null

    /** Host: the winner of the last finished game of this room ("" if none finished yet). */
    private var lastWinner = ""

    /** Host: which seats were out or removed when the roster was last sent (one bit per seat). */
    private var lastOutMask = 0

    /** Host: the game changed since it was last saved, and when it was last saved. */
    private var saveDirty = false
    private var lastSaveAt = 0L

    /** Guest: this join is a return to a remembered game, and the role to ask for in the hello. */
    private var rejoining = false
    private var helloRole = ConnectProtocol.ROLE_PLAYER

    /** Guest: the loop that looks for the host again after the link dropped. */
    private var reconnectJob: Job? = null

    /** Guest: when anything last arrived from the host (SystemClock.elapsedRealtime). */
    private var lastHeardAt = 0L

    /** Guest: when the current reconnect try began. */
    private var attemptAt = 0L

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
                    error = withReason(MSG_HOST_FAILED, problem)
                }
            }
        }
    }

    /**
     * Guest: finds the host named in the scanned [ticket] and asks for a seat. [rejoin] = this is a return to
     * a remembered game (nothing found then means the game is gone), [watcher] = ask to watch, not to play.
     */
    fun startJoin(ticket: JoinTicket, name: String, rejoin: Boolean = false, watcher: Boolean = false) {
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
        rejoining = rejoin
        helloRole = if (watcher) ConnectProtocol.ROLE_WATCHER else ConnectProtocol.ROLE_PLAYER
        state = ConnectState.SEARCHING
        manager.startDiscovery { problem ->
            if (g == generation && problem != null) failJoin(withReason(MSG_SEARCH_FAILED, problem))
        }
        guestTimer = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (g == generation && state == ConnectState.SEARCHING) {
                if (rejoining) {
                    // The host is not around any more: forget the game.
                    clearLastSession()
                    failJoin(MSG_GAME_NOT_FOUND)
                } else {
                    failJoin(MSG_NOT_FOUND)
                }
            }
        }
    }

    /** Guest: returns to the remembered game (the Rejoin button). */
    fun startRejoin(name: String) {
        val saved = readSavedSession()
        if (saved == null) {
            leave()
            rejoinInfo = null
            error = MSG_GAME_NOT_FOUND
            return
        }
        startJoin(JoinTicket(saved.room, saved.token), name, rejoin = true, watcher = saved.watcher)
    }

    /** Guest: the Try again button after the 30 seconds of looking for the host ran out. */
    override fun retryReconnect() {
        if (role != ConnectRole.GUEST || !inGame || finalText != null) return
        if (link == LinkState.RECONNECTING) return
        link = LinkState.RECONNECTING
        runReconnectLoop()
    }

    /**
     * The app came back to the foreground. A guest checks that the host still answers (a locked phone often
     * loses its link without being told) and looks for the host again if not. A host makes sure it is advertising.
     */
    fun onAppForeground() {
        when (role) {
            ConnectRole.GUEST -> {
                if (!inGame || finalText != null) return
                when (link) {
                    LinkState.OK -> checkLink()
                    LinkState.FAILED -> retryReconnect()
                    LinkState.RECONNECTING -> Unit
                }
            }
            ConnectRole.HOST -> {
                if (started && !sessionEnded) {
                    // Harmless when it is already advertising: the answer is ignored.
                    manager.startAdvertising(ConnectProtocol.endpointName(roomCode, hostName)) { }
                }
            }
            null -> Unit
        }
    }

    /** Host: writes the running game to the saved settings now (the app is going to the background). */
    fun saveHostNow() {
        maybeSave(force = true)
    }

    /** Host: the Allow watchers switch. */
    override fun switchWatchers(on: Boolean) {
        if (role != ConnectRole.HOST) return
        allowWatchers = on
        saveDirty = true
        broadcastLobby()
    }

    /**
     * Host: takes [seat] out of the game (Remove player). The engine does the rest on the queue, so it never
     * runs at the same moment as a tap. Only allowed for a seat whose connection is lost.
     */
    override fun removePlayer(seat: Int) {
        if (!canRemove(seat)) return
        enqueue(HostIntent(0, K_REMOVE, i = seat))
    }

    /**
     * Host: End Game / End Tournament. Everybody is told, the saved game is deleted, and the host stays on a
     * final panel (still answering phones that come back, with the same news) until it presses OK.
     */
    override fun endForEveryone() {
        if (role != ConnectRole.HOST || !started || sessionEnded) return
        val g = game
        if (g != null && g.phase == Phase.GameOver && g.winner >= 0) lastWinner = playerName(g.winner)
        val seats = roster.sortedBy { it.seat }
        val names = seats.map { it.name }
        val scores = seats.map { scoreOfSeat(it.seat) }
        sessionEnded = true
        queue.clear()
        runnerJob?.cancel()
        runnerJob = null
        draining = false
        val message = ConnectProtocol.ended(lastWinner, names, scores)
        lastEnded = message
        manager.send(allEndpoints(), message)
        clearHostSave()
        finalText = endedText(lastWinner, names, scores)
    }

    /**
     * Host: the Resume hosted game button. Rebuilds the game from the saved one, advertises again with the
     * same room code and token, and lets returning guests take their old seats. Returns false (with [error]
     * set) when nothing could be resumed.
     */
    fun resumeHosting(name: String): Boolean {
        leave()
        val text = prefs.getString(KEY_HOST_SAVE, null)
        if (text == null || uiScope == null) {
            hostSaveInfo = null
            error = MSG_RESUME_FAILED
            return false
        }
        try {
            val o = JSONObject(text)
            val count = o.getInt("playerCount")
            if (count !in 2..4) throw IllegalStateException("players")
            val room = o.getString("room")
            val tok = o.getString("token")
            val ticket = JoinTicket(room, tok)
            if (JoinTicket.parse(ticket.toText()) == null) throw IllegalStateException("ticket")
            val orderArr = o.getJSONArray("order")
            val order = ArrayList<Int>()
            for (i in 0 until orderArr.length()) order.add(orderArr.getInt(i))
            if (order.size != count || order.toSet().size != count || order.any { it !in 0..3 }) {
                throw IllegalStateException("order")
            }
            if (count == 2 && order.any { it > 1 }) throw IllegalStateException("order")
            val seats = o.getJSONArray("roster")
            val entries = ArrayList<RosterEntry>()
            for (i in 0 until seats.length()) {
                val r = seats.getJSONObject(i)
                val seat = r.getInt("seat")
                if (seat !in 0 until count) continue
                entries.add(
                    RosterEntry(
                        seat = seat,
                        name = cleanName(r.optString("name", "Player")),
                        deviceId = if (seat == 0) deviceId else r.optString("deviceId", ""),
                        connected = seat == 0,
                        isHost = seat == 0,
                        out = false,
                        removed = r.optBoolean("removed", false)
                    )
                )
            }
            if (entries.size != count || entries.none { it.seat == 0 }) throw IllegalStateException("roster")
            val engine = LudoGame(tournament = count >= 3, connectActive = order)
            if (!engine.restore(o.getString("engine"))) throw IllegalStateException("engine")
            engine.visualSink = { e -> onEngineEvent(e) }

            val g = generation
            role = ConnectRole.HOST
            playerCount = count
            hostName = entries.first { it.seat == 0 }.name
            mySeat = 0
            roomCode = room
            token = tok
            ticketText = ticket.toText()
            roster.clear()
            roster.addAll(entries.sortedBy { it.seat })
            playerOfSeat.clear()
            playerOfSeat.addAll(order)
            val saved = o.optJSONArray("scores")
            for (i in seatScores.indices) seatScores[i] = saved?.optInt(i, 0) ?: 0
            allowWatchers = o.optBoolean("allowWatchers", false)
            lastWinner = o.optString("lastWinner", "")
            gameNo = o.optInt("gameNo", 1).coerceAtLeast(1)
            resetLive()
            game = engine
            started = true
            sessionEnded = false
            seq = 0
            lastSent = ""
            lastOutMask = 0
            state = ConnectState.STARTING
            manager.startAdvertising(ConnectProtocol.endpointName(room, hostName)) { problem ->
                if (g == generation && role == ConnectRole.HOST) {
                    if (problem == null) {
                        state = ConnectState.HOST_LOBBY
                        inGame = true
                        startPublisher()
                        publish(force = true)
                    } else {
                        teardown()
                        error = withReason(MSG_HOST_FAILED, problem)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            teardown()
            clearHostSave()
            error = MSG_RESUME_FAILED
            return false
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
        sessionEnded = false
        lastEnded = null
        lastWinner = ""
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
        lastOutMask = 0
        manager.send(allEndpoints(), ConnectProtocol.start(playerCount, assignment, gameNo))
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
                maybeSave(force = false)
            }
        }
    }

    /**
     * Sends the game to every guest when it has changed (at most about ten times a second, always the
     * latest one). Moves in progress are not part of it: guests see the board jump to each new state.
     */
    private fun publish(force: Boolean) {
        val g = game ?: return
        if (role != ConnectRole.HOST || !started || sessionEnded) return
        if (g.phase == Phase.GameOver) {
            for (seat in playerOfSeat.indices) seatScores[seat] = g.scores.getOrElse(playerOfSeat[seat]) { 0 }
            if (g.winner >= 0) lastWinner = playerName(g.winner)
        }
        // Somebody was knocked out or removed: everybody's seat list gets the new flags.
        val mask = outMask()
        if (mask != lastOutMask) {
            lastOutMask = mask
            broadcastLobby()
        }
        val snapshot = g.toMirrorJson()
        val text = snapshot.toString()
        if (!force && text == lastSent) return
        lastSent = text
        saveDirty = true
        seq++
        manager.send(allEndpoints(), ConnectProtocol.state(seq, gameNo, snapshot))
    }

    /** One bit per seat that is knocked out or removed. */
    private fun outMask(): Int {
        var m = 0
        for (e in roster) if (e.removed || isSeatOut(e.seat)) m = m or (1 shl e.seat)
        return m
    }

    private fun hostResync(endpointId: String) {
        if (!started) return
        if (!seatByEndpoint.containsKey(endpointId) && !watcherByEndpoint.containsKey(endpointId)) return
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
        if (role != ConnectRole.HOST || !started || sessionEnded) return
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
                // A game with a removed person cannot go on: the host ends it instead.
                if (req.seat == 0 && g.phase == Phase.GameOver && roster.none { it.removed }) newGameFromHost()
            K_REMOVE ->
                if (req.seat == 0 && canRemove(req.i)) {
                    val victim = playerOfSeat.getOrElse(req.i) { -1 }
                    if (victim >= 0) {
                        g.connectRemove(victim)
                        // Flag the seat only if the engine really took the person out (the game may have moved on meanwhile).
                        val done = g.phase == Phase.GameOver || (g.tournament && !g.active[victim])
                        if (done) {
                            markRemoved(req.i)
                            broadcastLobby()
                        }
                    }
                }
            else -> Unit
        }
    }

    /** Flags a seat as removed by the host. Its person is told so if they ever come back. */
    private fun markRemoved(seat: Int) {
        val at = roster.indexOfFirst { it.seat == seat }
        if (at < 0) return
        val e = roster[at]
        roster[at] = RosterEntry(e.seat, e.name, e.deviceId, e.connected, e.isHost, e.out, true)
    }

    // ----- Phase 3: live view, host side -----

    /** Sends one "ev" to every seated phone. Hand events (cosmetic, droppable) are skipped when too many went out. */
    private fun sendEv(message: JSONObject, droppable: Boolean) {
        if (role != ConnectRole.HOST || !started) return
        val now = SystemClock.elapsedRealtime()
        while (evTimes.isNotEmpty() && now - evTimes.first() > 1000L) evTimes.removeFirst()
        if (droppable && evTimes.size >= MAX_EV_PER_SECOND) return
        evTimes.addLast(now)
        val everyone = allEndpoints()
        if (everyone.isEmpty()) return
        manager.send(everyone, message)
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
    override fun leave() {
        when (role) {
            ConnectRole.HOST -> {
                if (started && game != null && !sessionEnded) {
                    // Leaving a running game only pauses it: the guests wait, and it can be resumed later.
                    // (persistHost drops the save instead when the game is already over.)
                    persistHost()
                } else if (!started && seatByEndpoint.isNotEmpty()) {
                    manager.send(seatByEndpoint.keys.toList(), ConnectProtocol.bye("closed"))
                }
            }
            ConnectRole.GUEST -> {
                if (state == ConnectState.GUEST_LOBBY && link == LinkState.OK && finalText == null) {
                    hostEndpoint?.let { manager.send(it, ConnectProtocol.bye("left")) }
                }
            }
            null -> Unit
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
        reconnectJob = null
        manager.stopAll()
        seatByEndpoint.clear()
        watcherByEndpoint.clear()
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
        sessionEnded = false
        lastEnded = null
        lastWinner = ""
        lastOutMask = 0
        saveDirty = false
        rejoining = false
        helloRole = ConnectProtocol.ROLE_PLAYER
        lastHeardAt = 0L
        link = LinkState.OK
        watching = false
        allowWatchers = false
        watcherCount = 0
        finalText = null
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
        if (role != ConnectRole.GUEST) return
        // Looking for the host again after the link dropped (the game stays on screen meanwhile).
        val reconnecting = link == LinkState.RECONNECTING && state == ConnectState.GUEST_LOBBY && hostEndpoint == null
        if (state != ConnectState.SEARCHING && !reconnecting) return
        val prefix = ConnectProtocol.endpointPrefix(roomCode)
        if (!endpointName.startsWith(prefix)) return
        val g = generation
        hostEndpoint = endpointId
        if (reconnecting) {
            attemptAt = SystemClock.elapsedRealtime()
            manager.stopDiscovery()
            manager.requestConnection(myName, endpointId) { problem ->
                if (g == generation && problem != null && hostEndpoint == endpointId) hostEndpoint = null
            }
            return
        }
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
                    if (g == generation && !seatByEndpoint.containsKey(endpointId) &&
                        !watcherByEndpoint.containsKey(endpointId)
                    ) {
                        manager.disconnect(endpointId)
                    }
                    helloTimers.remove(endpointId)
                }
            }
            ConnectRole.GUEST -> {
                if (endpointId == hostEndpoint) {
                    manager.send(
                        endpointId,
                        ConnectProtocol.hello(token, myName, deviceId, helloRole)
                    )
                }
            }
            null -> manager.disconnect(endpointId)
        }
    }

    override fun onConnectionFailed(endpointId: String) {
        if (role != ConnectRole.GUEST || endpointId != hostEndpoint) return
        if (state == ConnectState.CONNECTING) {
            failJoin(MSG_CONNECT_FAILED)
        } else if (link == LinkState.RECONNECTING) {
            hostEndpoint = null   // this try failed: the loop makes the next one
        }
    }

    override fun onDisconnected(endpointId: String) {
        when (role) {
            ConnectRole.HOST -> hostDropEndpoint(endpointId)
            ConnectRole.GUEST -> {
                if (endpointId != hostEndpoint) return
                if (state == ConnectState.GUEST_LOBBY) {
                    hostEndpoint = null
                    if (link == LinkState.RECONNECTING) return   // the loop makes the next try
                    onGuestLinkLost()
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
                ConnectRole.GUEST -> if (endpointId == hostEndpoint) {
                    lastHeardAt = SystemClock.elapsedRealtime()
                    guestHandle(msg)
                }
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
        // Already in: ignore a repeat.
        if (seatByEndpoint.containsKey(endpointId) || watcherByEndpoint.containsKey(endpointId)) return
        val sentToken = msg.optString("token", "")
        val sentDevice = msg.optString("deviceId", "")
        val wantsToWatch = msg.optString("role", ConnectProtocol.ROLE_PLAYER) == ConnectProtocol.ROLE_WATCHER
        if (msg.optInt("v", -1) != ConnectProtocol.PROTOCOL_VERSION) {
            rejectAndClose(endpointId, "version")
            return
        }
        if (!sameText(sentToken, token)) {
            rejectAndClose(endpointId, "bad_token")
            return
        }
        // The host is on its final "Game ended" panel: whoever comes back gets the same news.
        val news = lastEnded
        if (sessionEnded && news != null) {
            manager.send(endpointId, news)
            closeLater(endpointId)
            return
        }
        helloTimers.remove(endpointId)?.cancel()

        // 1. A person who already owns a seat gets it back (matched by device id), whatever the switches say.
        val owner = if (sentDevice.isEmpty()) null else roster.firstOrNull { !it.isHost && it.deviceId == sentDevice }
        if (owner != null) {
            if (owner.removed) {
                manager.send(endpointId, ConnectProtocol.removed())
                closeLater(endpointId)
                return
            }
            attachSeat(endpointId, owner.seat)
            return
        }

        // 2. Somebody without a seat, while a game runs, can only watch (and only if the host allows it).
        if (started) {
            if (!allowWatchers) {
                rejectAndClose(endpointId, "watchers_off")
                return
            }
            val sameWatcher = if (sentDevice.isEmpty()) null
            else watcherByEndpoint.entries.firstOrNull { it.value == sentDevice }?.key
            if (sameWatcher == null && watcherByEndpoint.size >= ConnectProtocol.MAX_WATCHERS) {
                rejectAndClose(endpointId, "full")
                return
            }
            if (sameWatcher != null) {
                // The same phone again: keep the newest link, drop the older one quietly.
                watcherByEndpoint.remove(sameWatcher)
                manager.disconnect(sameWatcher)
            }
            watcherByEndpoint[endpointId] = sentDevice
            watcherCount = watcherByEndpoint.size
            sendWelcomeAndGame(endpointId, -1)
            broadcastLobby()
            return
        }

        // 3. In the lobby: watching is not possible yet, everybody else takes the next free seat.
        if (wantsToWatch) {
            rejectAndClose(endpointId, "watchers_off")
            return
        }
        if (freeSeat() < 0) {
            rejectAndClose(endpointId, "full")
            return
        }
        val seat = freeSeat()
        val name = uniqueName(cleanName(msg.optString("name", "")))
        seatByEndpoint[endpointId] = seat
        val entry = RosterEntry(seat, name, sentDevice, true, false)
        val at = roster.indexOfFirst { it.seat > seat }
        if (at < 0) roster.add(entry) else roster.add(at, entry)
        sendWelcomeAndGame(endpointId, seat)
        broadcastLobby()
    }

    /** Sends a refusal and closes the link a moment later, so the refusal has time to arrive. */
    private fun rejectAndClose(endpointId: String, reason: String) {
        manager.send(endpointId, ConnectProtocol.reject(reason))
        closeLater(endpointId)
    }

    private fun closeLater(endpointId: String) {
        val g = generation
        scope.launch {
            delay(REJECT_CLOSE_DELAY_MS)
            if (g == generation) manager.disconnect(endpointId)
        }
    }

    /**
     * Puts a connection on a seat that already exists (a person coming back). A seat keeps only its newest
     * connection: the older one is taken off the map first, so its later disconnect is not mistaken for a drop.
     */
    private fun attachSeat(endpointId: String, seat: Int) {
        val older = seatByEndpoint.entries.filter { it.value == seat }.map { it.key }
        for (o in older) {
            seatByEndpoint.remove(o)
            manager.disconnect(o)
        }
        seatByEndpoint[endpointId] = seat
        val at = roster.indexOfFirst { it.seat == seat }
        if (at >= 0) {
            val e = roster[at]
            roster[at] = RosterEntry(e.seat, e.name, e.deviceId, true, e.isHost, e.out, e.removed)
        }
        sendWelcomeAndGame(endpointId, seat)
        broadcastLobby()
    }

    /** The answer to an accepted hello. In a running game the assignment and the whole game state follow at once. */
    private fun sendWelcomeAndGame(endpointId: String, seat: Int) {
        manager.send(
            endpointId,
            ConnectProtocol.welcome(
                seat, roomCode, hostName, playerCount, rosterForSend(),
                started, seat < 0, watcherByEndpoint.size, allowWatchers
            )
        )
        if (started) {
            val order = playerOfSeat.toList()
            val assignment = order.indices.map { s ->
                SeatAssignment(s, order[s], colorsOfSeat(s).map { it.name })
            }
            manager.send(endpointId, ConnectProtocol.start(playerCount, assignment, gameNo))
            hostResync(endpointId)
        }
    }

    private fun hostDropEndpoint(endpointId: String) {
        helloTimers.remove(endpointId)?.cancel()
        if (watcherByEndpoint.remove(endpointId) != null) {
            watcherCount = watcherByEndpoint.size
            broadcastLobby()
            return
        }
        val seat = seatByEndpoint.remove(endpointId) ?: return
        val at = roster.indexOfFirst { it.seat == seat }
        if (started) {
            // In a game the seat stays (its colours stay too); it is only marked as not connected, and
            // everybody sees "Waiting for ... to reconnect...". The person takes it back when they return.
            if (at >= 0) {
                val e = roster[at]
                roster[at] = RosterEntry(e.seat, e.name, e.deviceId, false, e.isHost, e.out, e.removed)
            }
        } else if (at >= 0) {
            roster.removeAt(at)
        }
        broadcastLobby()
    }

    private fun broadcastLobby() {
        manager.send(
            allEndpoints(),
            ConnectProtocol.lobby(playerCount, started, rosterForSend(), watcherByEndpoint.size, allowWatchers)
        )
    }

    /** Every phone that is connected: seated players and watchers. */
    private fun allEndpoints(): List<String> = seatByEndpoint.keys.toList() + watcherByEndpoint.keys.toList()

    /** The seat list as it goes over the air, with the out flag worked out from the game. */
    private fun rosterForSend(): List<RosterEntry> = roster.map { e ->
        RosterEntry(e.seat, e.name, e.deviceId, e.connected, e.isHost, isSeatOut(e.seat), e.removed)
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
                val isWatcher = seat < 0 || msg.optBoolean("watcher", false)
                if (count !in 2..4 || (!isWatcher && seat < 1)) return
                guestTimer?.cancel()
                reconnectJob?.cancel()
                reconnectJob = null
                manager.stopDiscovery()
                playerCount = count
                mySeat = if (isWatcher) -1 else seat
                watching = isWatcher
                hostName = cleanName(msg.optString("hostName", hostName))
                setRoster(ConnectProtocol.rosterFromJson(msg.optJSONArray("roster")))
                watcherCount = msg.optInt("watchers", 0)
                link = LinkState.OK
                lastHeardAt = SystemClock.elapsedRealtime()
                state = ConnectState.GUEST_LOBBY
                saveLastSession(mySeat, isWatcher)
            }
            ConnectProtocol.T_LOBBY -> {
                if (state != ConnectState.GUEST_LOBBY) return
                val count = msg.optInt("playerCount", playerCount)
                if (count in 2..4) playerCount = count
                setRoster(ConnectProtocol.rosterFromJson(msg.optJSONArray("roster")))
                watcherCount = msg.optInt("watchers", watcherCount)
            }
            ConnectProtocol.T_START -> guestStart(msg)
            ConnectProtocol.T_STATE -> guestState(msg)
            ConnectProtocol.T_EV -> guestEvent(msg)
            ConnectProtocol.T_REJECT -> {
                val reason = msg.optString("reason", "")
                val text = rejectMessage(reason)
                // These answers mean the way back is closed for good: forget the remembered game.
                val closed = reason == "bad_token" || reason == "started" || reason == "watchers_off" || reason == "version"
                if (closed && (rejoining || inGame)) clearLastSession()
                if (inGame) showFinal(text) else failJoin(text)
            }
            ConnectProtocol.T_ENDED -> {
                val (names, scores) = ConnectProtocol.endedScoresFromJson(msg.optJSONArray("scores"))
                val text = endedText(msg.optString("winnerName", ""), names, scores)
                clearLastSession()
                if (inGame) {
                    showFinal(text)
                } else {
                    teardown()
                    ended = text
                }
            }
            ConnectProtocol.T_REMOVED -> {
                clearLastSession()
                if (inGame) {
                    showFinal(MSG_REMOVED)
                } else {
                    teardown()
                    ended = MSG_REMOVED
                }
            }
            ConnectProtocol.T_BYE -> {
                clearLastSession()
                val message = if (inGame) MSG_GAME_ENDED else MSG_HOST_GONE
                teardown()
                ended = message
            }
            else -> Unit // unknown type: ignore
        }
    }

    /**
     * Guest: the session is over for this person. The game stays on screen under [text] and one OK button;
     * the link is closed and nothing tries to reconnect any more.
     */
    private fun showFinal(text: String) {
        reconnectJob?.cancel()
        reconnectJob = null
        guestTimer?.cancel()
        manager.stopDiscovery()
        hostEndpoint?.let { manager.disconnect(it) }
        hostEndpoint = null
        link = LinkState.OK
        finalText = text
    }

    /** The host's phone cannot be reached any more while a game is on screen: look for it again by itself. */
    private fun onGuestLinkLost() {
        if (role != ConnectRole.GUEST || state != ConnectState.GUEST_LOBBY || finalText != null) return
        if (!inGame) {
            teardown()
            ended = MSG_HOST_GONE
            return
        }
        if (link == LinkState.RECONNECTING) return
        link = LinkState.RECONNECTING
        runReconnectLoop()
    }

    /** Looks for the host again every few seconds for up to [RECONNECT_TIMEOUT_MS], then offers Try again. */
    private fun runReconnectLoop() {
        reconnectJob?.cancel()
        val g = generation
        hostEndpoint?.let { manager.disconnect(it) }
        hostEndpoint = null
        attemptAt = 0L
        reconnectJob = scope.launch {
            val deadline = SystemClock.elapsedRealtime() + RECONNECT_TIMEOUT_MS
            while (g == generation && link == LinkState.RECONNECTING && SystemClock.elapsedRealtime() < deadline) {
                val ep = hostEndpoint
                if (ep != null && SystemClock.elapsedRealtime() - attemptAt > ATTEMPT_TIMEOUT_MS) {
                    manager.disconnect(ep)
                    hostEndpoint = null
                }
                if (hostEndpoint == null) {
                    manager.stopDiscovery()
                    manager.startDiscovery { }
                }
                delay(RECONNECT_STEP_MS)
            }
            if (g == generation && link == LinkState.RECONNECTING) {
                manager.stopDiscovery()
                hostEndpoint?.let { manager.disconnect(it) }
                hostEndpoint = null
                link = LinkState.FAILED
            }
        }
    }

    /** The app came back and thinks it is connected: ask the host for the game, and look again if nothing answers. */
    private fun checkLink() {
        val ep = hostEndpoint
        if (ep == null) {
            onGuestLinkLost()
            return
        }
        val before = lastHeardAt
        val g = generation
        manager.send(ep, ConnectProtocol.resync())
        scope.launch {
            delay(CHECK_REPLY_MS)
            if (g == generation && link == LinkState.OK && inGame && lastHeardAt == before) {
                onGuestLinkLost()
            }
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
        // A host that resumed a saved game counts its snapshots from 1 again.
        lastSeq = 0
        appliedGameNo = 0
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

    // ----- Phase 4: remembered sessions -----

    private class SavedSession(val room: String, val token: String, val hostName: String, val watcher: Boolean)

    private fun readSavedSession(): SavedSession? {
        val text = prefs.getString(KEY_LAST_SESSION, null) ?: return null
        return try {
            val o = JSONObject(text)
            val room = o.getString("room")
            val tok = o.getString("token")
            if (JoinTicket.parse(JoinTicket(room, tok).toText()) == null) return null
            SavedSession(room, tok, o.optString("hostName", "Host"), o.optBoolean("watcher", false))
        } catch (e: Exception) {
            null
        }
    }

    /** Guest: remembers the game so the Rejoin button can bring this person back. */
    private fun saveLastSession(seat: Int, watcher: Boolean) {
        try {
            val o = JSONObject()
                .put("room", roomCode)
                .put("token", token)
                .put("hostName", hostName)
                .put("seat", seat)
                .put("watcher", watcher)
                .put("role", if (watcher) ConnectProtocol.ROLE_WATCHER else ConnectProtocol.ROLE_PLAYER)
                .put("time", System.currentTimeMillis())
            prefs.edit().putString(KEY_LAST_SESSION, o.toString()).apply()
            rejoinInfo = RejoinInfo(roomCode, hostName, watcher)
        } catch (e: Exception) {
            // Not being able to remember the game only costs the Rejoin button.
        }
    }

    private fun clearLastSession() {
        prefs.edit().remove(KEY_LAST_SESSION).apply()
        rejoinInfo = null
    }

    private fun readHostSaveInfo(): HostSaveInfo? {
        val text = prefs.getString(KEY_HOST_SAVE, null) ?: return null
        return try {
            val o = JSONObject(text)
            val count = o.getInt("playerCount")
            if (count !in 2..4) return null
            HostSaveInfo(o.getString("room"), count)
        } catch (e: Exception) {
            null
        }
    }

    private fun clearHostSave() {
        prefs.edit().remove(KEY_HOST_SAVE).apply()
        hostSaveInfo = null
        saveDirty = false
    }

    /** Saves the running game now if it changed (or [force]); at most about every two seconds otherwise. */
    private fun maybeSave(force: Boolean) {
        if (role != ConnectRole.HOST || !started || sessionEnded || game == null) return
        val now = SystemClock.elapsedRealtime()
        if (!force && (!saveDirty || now - lastSaveAt < SAVE_INTERVAL_MS)) return
        persistHost()
    }

    /** Writes everything needed to resume the hosted game. A finished game is not kept. */
    private fun persistHost() {
        val g = game ?: return
        if (role != ConnectRole.HOST || !started || sessionEnded) return
        if (g.phase == Phase.GameOver) {
            clearHostSave()
            return
        }
        try {
            val o = JSONObject()
                .put("playerCount", playerCount)
                .put("room", roomCode)
                .put("token", token)
                .put("gameNo", gameNo)
                .put("allowWatchers", allowWatchers)
                .put("lastWinner", lastWinner)
                .put("order", JSONArray().also { a -> playerOfSeat.forEach { a.put(it) } })
                .put("scores", JSONArray().also { a ->
                    for (seat in 0 until 4) {
                        val p = playerOfSeat.getOrElse(seat) { -1 }
                        a.put(if (p < 0) 0 else g.scores.getOrElse(p) { 0 })
                    }
                })
                .put("roster", JSONArray().also { a ->
                    for (e in roster) {
                        a.put(
                            JSONObject().put("seat", e.seat).put("name", e.name)
                                .put("deviceId", e.deviceId).put("removed", e.removed)
                        )
                    }
                })
                .put("engine", g.toSaveString())
                .put("time", System.currentTimeMillis())
            prefs.edit().putString(KEY_HOST_SAVE, o.toString()).apply()
            hostSaveInfo = HostSaveInfo(roomCode, playerCount)
            saveDirty = false
            lastSaveAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            // A failed save only costs the Resume button.
        }
    }

    /** The "Game ended" panel text: the winner of the last finished game (if any) and the scores. */
    private fun endedText(winner: String, names: List<String>, scores: List<Int>): String {
        val sb = StringBuilder("Game ended")
        if (winner.isNotEmpty()) sb.append("\nWinner: ").append(winner)
        if (names.isNotEmpty()) {
            sb.append("\n")
            sb.append(names.indices.joinToString("   ") { "${names[it]} ${scores.getOrElse(it) { 0 }}" })
        }
        return sb.toString()
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
        const val KEY_LAST_SESSION = "connect_last_session"
        const val KEY_HOST_SAVE = "connect_host_save"

        /** Host-only work item: take a person out of the game (never sent over the air). */
        private const val K_REMOVE = "remove"

        /** Phase 4: how long a guest keeps looking for the host before it offers Try again. */
        private const val RECONNECT_TIMEOUT_MS = 30_000L
        private const val RECONNECT_STEP_MS = 2_000L
        private const val ATTEMPT_TIMEOUT_MS = 8_000L
        private const val CHECK_REPLY_MS = 3_000L
        private const val SAVE_INTERVAL_MS = 2_000L

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
            "Couldn't open the lobby. Check that Bluetooth, Wi-Fi and Location are on, then try again."
        private const val MSG_SEARCH_FAILED =
            "Couldn't look for nearby phones. Check that Bluetooth, Wi-Fi and Location are on, then try again."

        /** Adds the technical reason (for example "8007 STATUS_RADIO_ERROR") in brackets under a friendly message. */
        private fun withReason(message: String, reason: String?): String =
            if (reason.isNullOrBlank()) message else "$message\n($reason)"
        private const val MSG_NOT_FOUND =
            "Couldn't find the host. Stand closer to the host phone and try again."
        private const val MSG_CONNECT_FAILED =
            "Couldn't connect to the host. Please try again."
        private const val MSG_VERSION = "Please update Ludo Mate on both phones."
        private const val MSG_HOST_GONE = "Disconnected from the host."
        private const val MSG_HOST_LOST = "Lost the connection to the host."
        private const val MSG_GAME_ENDED = "The host ended the game."
        private const val MSG_GAME_NOT_FOUND = "Game not found. The host may have left."
        private const val MSG_REMOVED = "You were removed by the host."
        private const val MSG_RESUME_FAILED = "Couldn't resume the game."
    }
}
