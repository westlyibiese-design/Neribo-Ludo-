package com.westly.ludo.online

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import com.westly.ludo.connect.ConnectProtocol
import com.westly.ludo.connect.ConnectSession
import com.westly.ludo.connect.LinkState
import com.westly.ludo.connect.LiveSession
import com.westly.ludo.connect.RemoteHandPlayer
import com.westly.ludo.connect.RosterEntry
import com.westly.ludo.connect.SeatAssignment
import com.westly.ludo.game.HandTarget
import com.westly.ludo.game.LudoColor
import com.westly.ludo.game.LudoGame
import com.westly.ludo.game.Phase
import com.westly.ludo.game.Piece
import com.westly.ludo.game.TOverlay
import com.westly.ludo.game.VisualEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException

/** The host sends the game state at most this often, in milliseconds (always the latest one). */
const val ONLINE_STATE_INTERVAL_MS = 250L

/** What this person is in the room. */
enum class OnlineRole { HOST, PLAYER, WATCHER }

/** OFFLINE means the last poll could not reach the server; the room screen then shows a banner. */
enum class OnlineLink { OK, OFFLINE }

/** The room as the server reports it. [status] is "waiting", "playing" or "ended". */
data class OnlineRoom(
    val id: String,
    val code: String,
    val playerCount: Int,
    val maxWatchers: Int,
    val status: String,
    val hostId: String,
    /** 0 until the host has started the first game, then 1, 2, ... */
    val gameNo: Int = 0,
    /** The engine player of each seat in the current game (index = seat), empty until a game is recorded. */
    val assignment: List<Int> = emptyList(),
    /** The result of the last finished game (or of the whole session once it was ended), if the host stored one. */
    val result: OnlineResult? = null
)

/** What the server stores about the last finished game: the winner and every person's score, in seat order. */
class OnlineResult(val winnerName: String, val names: List<String>, val scores: List<Int>, val gameNo: Int)

/** The hosted game this phone can resume (drives the "Resume hosted game" button). */
class OnlineHostSave(val userId: String, val roomId: String, val code: String, val playerCount: Int)

/** One person in the room. [seat] is null for a watcher; seat 0 is the host. */
data class OnlineMember(val userId: String, val seat: Int?, val role: OnlineRole, val name: String)

/** The live room the server says this person is in (for the "Return to room" button). */
data class OnlineRoomRef(val roomId: String, val code: String, val status: String, val role: OnlineRole)

/**
 * "You are already in room XXXX." [retry] repeats the action that was refused, and is used after
 * the person chose "Leave it". A player in a game that has started cannot leave in this phase
 * (the seat is kept for rejoining), so [canLeave] is false for that one case.
 */
class OnlineConflict(
    val code: String,
    val roomId: String,
    val role: OnlineRole,
    val status: String,
    val retry: () -> Unit
) {
    val canLeave: Boolean get() = !(role == OnlineRole.PLAYER && status == "playing")
}

/** One answer from a database function: either [json] or [failure] is set. */
internal class RpcReply(val json: JSONObject?, val failure: OnlineException?)

/**
 * Online rooms: creating, joining, leaving and the live room roster.
 *
 * Owned by MainActivity (like the Offline session), not by a screen, so it survives moving between
 * screens. Everything the screens show is Compose state. Every server call goes through
 * [SupabaseApi.rpc]; a failure never throws to the screen, it becomes [problem] or [notice].
 * The name that is sent is always the one the player saved as "Player 1" (never the Google name).
 */
@Suppress("unused")
class OnlineSession(
    context: Context,
    private val prefs: SharedPreferences,
    private val auth: OnlineAuth
) : LiveSession {
    private val appContext = context.applicationContext

    /** Calls run on the main thread's scope; the network work itself happens on IO inside [SupabaseApi]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var foreground = true
    private var pollJob: Job? = null
    private var myRoomJob: Job? = null

    /** The room this phone is in right now, or null. */
    var room by mutableStateOf<OnlineRoom?>(null)
        private set

    /** Everyone active in the room, players first by seat, then watchers. */
    var members by mutableStateOf<List<OnlineMember>>(emptyList())
        private set

    /** This person's seat (0 = host), or -1 for a watcher or when not in a room. */
    var mySeat by mutableStateOf(-1)
        private set

    var myRole by mutableStateOf<OnlineRole?>(null)
        private set

    /** True while a create / join / leave call is running, so buttons can be switched off. */
    var busy by mutableStateOf(false)
        private set

    /** A line for the Online menu, for example "The host closed the room." */
    var notice by mutableStateOf<String?>(null)
        private set

    /** An error line for the Start a Room / Join a Room screens. */
    var problem by mutableStateOf<String?>(null)
        private set

    var roomLink by mutableStateOf(OnlineLink.OK)

    /** True while the room screen's last answer from the server was slow (so the person knows the app is not frozen). */
    var roomSlow by mutableStateOf(false)
        private set
        private set

    /** The live room the server reports for this person; drives the "Return to room" button. */
    var myRoomRef by mutableStateOf<OnlineRoomRef?>(null)
        private set

    /** Set when an action was refused because the person is already in another live room. */
    var conflict by mutableStateOf<OnlineConflict?>(null)
        private set

    /** Set to the code that was tried when a room was full but has a free watcher place. */
    var offerWatchCode by mutableStateOf<String?>(null)
        private set

    /** A "Game ended" panel for the Online screens: somebody tried to return to a room that was already closed. */
    var endedPanel by mutableStateOf<String?>(null)
        private set

    /** The hosted game that can be resumed on this phone, or null (drives the "Resume hosted game" button). */
    var hostSave by mutableStateOf<OnlineHostSave?>(null)
        private set

    init {
        hostSave = readHostSaveInfo()
    }

    // ---------------------------------------------------------------------------------------
    // App lifecycle
    // ---------------------------------------------------------------------------------------

    fun onAppForeground() {
        foreground = true
        if (inGame && finalText == null) {
            // A phone that slept may have lost its socket without being told: connect again, check that
            // the connection really answers, and ask for the game.
            socket.ensureConnected()
            socket.probe()
            socketLostSince = 0L
            if (!isHost) requestState()
        }
    }

    fun onAppBackground() {
        foreground = false
    }

    /** Host: writes the running game to the saved settings now (the app is going to the background). */
    fun saveHostNow() {
        persistHost()
    }

    /** Stops polling and any call in progress. Called when the activity is destroyed. */
    fun release() {
        stopGame()
        socket.release()
        scope.cancel()
    }

    /** Forgets everything on this phone (used when the player signs out). Nothing is sent to the server. */
    fun reset() {
        stopPolling()
        clearLocal()
        myRoomRef = null
        conflict = null
        offerWatchCode = null
        endedPanel = null
        problem = null
        notice = null
    }

    fun clearNotice() {
        notice = null
    }

    fun clearProblem() {
        problem = null
    }

    fun dismissConflict() {
        conflict = null
    }

    fun dismissOfferWatch() {
        offerWatchCode = null
    }

    fun dismissEnded() {
        endedPanel = null
    }

    // ---------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------

    /** Asks the server whether this person is already in a live room. Quiet: it only fills [myRoomRef]. */
    fun checkMyRoom() {
        myRoomJob?.cancel()
        myRoomJob = scope.launch {
            val reply = call("ludo_my_room")
            val j = reply.json
            if (j == null) {
                if (reply.failure?.kind == OnlineException.Kind.NO_NETWORK) notice = MSG_NO_NET
                return@launch
            }
            val id = j.optString("room_id", "")
            myRoomRef = if (j.optBoolean("ok", false) && id.isNotEmpty()) {
                OnlineRoomRef(id, j.optString("code", ""), j.optString("status", ""), roleOf(j.optString("role", "")))
            } else {
                null
            }
            // A saved hosted game whose room is over (ended, expired, or this person is no longer its host).
            val save = hostSave
            val ref = myRoomRef
            if (save != null && save.userId.equals(auth.user?.id.orEmpty(), ignoreCase = true) &&
                !(ref != null && ref.roomId == save.roomId && ref.role == OnlineRole.HOST)
            ) {
                clearHostSave()
                notice = MSG_HOST_GAME_OVER
            }
        }
    }

    /** The hosted game this phone can resume right now, or null: the save is this person's and the server still says they host that room. */
    val resumeOffer: OnlineHostSave?
        get() {
            val save = hostSave ?: return null
            val ref = myRoomRef ?: return null
            if (!save.userId.equals(auth.user?.id.orEmpty(), ignoreCase = true)) return null
            return if (ref.roomId == save.roomId && ref.role == OnlineRole.HOST && ref.status == "playing") save else null
        }

    /**
     * The Resume hosted game button. The room is opened like for Return to room; because this phone is the
     * host of a game that is already running, [afterSnapshot] rebuilds the game from the saved one.
     */
    fun resumeHosted(onDone: (Boolean) -> Unit) {
        val save = resumeOffer
        if (save == null) {
            notice = MSG_HOST_GAME_OVER
            onDone(false)
            return
        }
        openRoom(save.roomId, onDone)
    }

    /** Start a Room. [onDone] gets true when the room exists and this phone is in it. */
    fun createRoom(players: Int, watchers: Int, name: String, onDone: (Boolean) -> Unit) {
        if (busy) return
        beginAction()
        scope.launch {
            var opened = false
            try {
                val args = JSONObject()
                    .put("p_player_count", players)
                    .put("p_max_watchers", watchers)
                    .put("p_name", name)
                val reply = call("ludo_create_room", args)
                val j = reply.json
                if (j == null) {
                    problem = failureText(reply.failure)
                } else if (j.optBoolean("ok", false)) {
                    enterRoom(j, fetch = false)
                    opened = true
                } else {
                    refuse(j, { createRoom(players, watchers, name, onDone) }, null)
                }
            } finally {
                busy = false
            }
            if (opened) onDone(true)
        }
    }

    /** Join a Room (or Watch when [watch] is true). [onDone] gets true when this phone is in the room. */
    fun joinRoom(code: String, name: String, watch: Boolean, onDone: (Boolean) -> Unit) {
        if (busy) return
        beginAction()
        scope.launch {
            var opened = false
            try {
                val args = JSONObject()
                    .put("p_code", code)
                    .put("p_name", name)
                    .put("p_watch", watch)
                val reply = call("ludo_join_room", args)
                val j = reply.json
                if (j == null) {
                    problem = failureText(reply.failure)
                } else if (j.optBoolean("ok", false)) {
                    enterRoom(j, fetch = true)
                    opened = true
                } else {
                    refuse(j, { joinRoom(code, name, watch, onDone) }, if (watch) null else code)
                }
            } finally {
                busy = false
            }
            if (opened) onDone(true)
        }
    }

    /** Opens a room this person is already in (Return to room, or "Go back to it"). */
    fun openRoom(roomId: String, onDone: (Boolean) -> Unit) {
        if (busy) return
        beginAction()
        scope.launch {
            var ok = false
            try {
                ok = loadRoom(roomId)
            } finally {
                busy = false
            }
            onDone(ok)
        }
    }

    /**
     * After the screen was rebuilt (the phone changed a setting, for example) the room screen has
     * nothing in memory. This finds the live room again. [onDone] gets true when a room was found.
     */
    fun restore(onDone: (Boolean) -> Unit) {
        scope.launch {
            var ok = false
            val reply = call("ludo_my_room")
            val j = reply.json
            if (j != null && j.optBoolean("ok", false)) {
                val id = j.optString("room_id", "")
                if (id.isNotEmpty()) ok = loadRoom(id)
            } else if (j == null) {
                problem = failureText(reply.failure)
            }
            onDone(ok)
        }
    }

    /** "Go back to it" in the already-in-a-room dialog. */
    fun openConflictRoom(onDone: (Boolean) -> Unit) {
        val c = conflict ?: return
        conflict = null
        openRoom(c.roomId, onDone)
    }

    /** "Leave it": the host closes that room, anybody else leaves it, then the refused action is repeated. */
    fun leaveConflictRoom() {
        val c = conflict ?: return
        conflict = null
        if (busy || !c.canLeave) return
        beginAction()
        scope.launch {
            var proceed = false
            try {
                val function = if (c.role == OnlineRole.HOST) "ludo_end_room" else "ludo_leave_room"
                val reply = call(function, JSONObject().put("p_room", c.roomId))
                if (reply.failure != null) {
                    problem = failureText(reply.failure)
                } else {
                    proceed = true
                    myRoomRef = null
                    if (c.role == OnlineRole.HOST) clearHostSave()
                }
            } finally {
                busy = false
            }
            if (proceed) c.retry()
        }
    }

    /**
     * Exit for a guest or watcher. Before the game starts the seat is freed. The local room is
     * cleared even when the call fails, so nobody is stuck on the room screen without internet.
     */
    fun leave(onDone: () -> Unit) {
        val current = room
        if (current == null) {
            onDone()
            return
        }
        if (busy) return
        stopPolling()
        busy = true
        scope.launch {
            try {
                call("ludo_leave_room", JSONObject().put("p_room", current.id))
            } finally {
                busy = false
            }
            myRoomRef = null
            clearLocal()
            onDone()
        }
    }

    /** Exit for the host: closes the room for everyone. */
    fun endRoom(onDone: () -> Unit) {
        val current = room
        if (current == null) {
            onDone()
            return
        }
        if (busy) return
        stopPolling()
        busy = true
        scope.launch {
            try {
                call("ludo_end_room", JSONObject().put("p_room", current.id))
            } finally {
                busy = false
            }
            myRoomRef = null
            clearHostSave()
            clearLocal()
            onDone()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Polling (while the room screen is open)
    // ---------------------------------------------------------------------------------------

    /** Starts asking the server for the room every [POLL_INTERVAL_MS]. Does nothing if already running. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                if (foreground) pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        val current = room ?: return
        val startedAt = SystemClock.elapsedRealtime()
        // If the answer takes long, say so while we wait (not only afterwards).
        val slowWatch = scope.launch {
            delay(POLL_SLOW_MS)
            roomSlow = true
        }
        val reply = call("ludo_room_snapshot", JSONObject().put("p_room", current.id))
        slowWatch.cancel()
        roomSlow = SystemClock.elapsedRealtime() - startedAt >= POLL_SLOW_MS
        val j = reply.json
        if (j == null) {
            // Only a real network problem shows the banner; other errors are simply tried again.
            if (reply.failure?.kind == OnlineException.Kind.NO_NETWORK) roomLink = OnlineLink.OFFLINE
            return
        }
        roomLink = OnlineLink.OK
        // The person may have left while this call was on its way.
        if (room?.id != current.id) return
        if (j.optBoolean("ok", false)) {
            if (!applySnapshot(j)) return
            if (room?.status == "ended") closeRoom("The host closed the room.")
        } else {
            when (j.optString("reason", "")) {
                "not_member" -> closeRoom("You are no longer in that room.")
                "removed" -> closeRoom(reasonText("removed"))
                "expired" -> closeRoom(reasonText("expired"))
                "not_found" -> closeRoom(reasonText("ended"))
                else -> {
                    // Anything else: keep the screen and try again on the next tick.
                }
            }
        }
    }

    /** The room is over for this phone: stop, remember why for the Online menu, and let the screen go back. */
    private fun closeRoom(message: String) {
        stopPolling()
        notice = message
        myRoomRef = null
        clearLocal()
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private fun beginAction() {
        problem = null
        notice = null
        busy = true
    }

    private fun clearLocal() {
        stopGame()
        room = null
        members = emptyList()
        mySeat = -1
        myRole = null
        roomLink = OnlineLink.OK
    }

    /** Calls a database function. Never throws, except to stop when the scope is cancelled. */
    private suspend fun call(name: String, args: JSONObject = JSONObject()): RpcReply =
        try {
            RpcReply(auth.api.rpc(name, args), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnlineException) {
            RpcReply(null, e)
        } catch (e: Exception) {
            RpcReply(null, OnlineException(OnlineException.Kind.BAD_ANSWER, MSG_GENERIC))
        }

    private fun failureText(e: OnlineException?): String = e?.message ?: MSG_GENERIC

    /** Turns an ok:false answer into the right message, dialog or offer. */
    private fun refuse(j: JSONObject, retry: () -> Unit, joinCode: String?) {
        val reason = j.optString("reason", "")
        if (reason == "busy") {
            conflict = OnlineConflict(
                code = j.optString("code", ""),
                roomId = j.optString("room_id", ""),
                role = roleOf(j.optString("role", "")),
                status = j.optString("status", ""),
                retry = retry
            )
        } else if (reason == "full" && joinCode != null && j.optBoolean("watchers_available", false)) {
            offerWatchCode = joinCode
        } else if (reason == "ended" || reason == "expired") {
            // The room is over: show the stored result, even if the host's phone is long gone.
            endedPanel = resultText(parseResult(j.optJSONObject("result")))
        } else {
            problem = reasonText(reason)
        }
    }

    /**
     * This phone has just created or joined a room. With [fetch] the full member list is asked for;
     * if that call fails, the answer of the create / join call itself is enough to show the screen,
     * and the next poll fills in the rest.
     */
    private suspend fun enterRoom(j: JSONObject, fetch: Boolean) {
        val id = j.optString("room_id", "")
        if (fetch) {
            val snap = call("ludo_room_snapshot", JSONObject().put("p_room", id)).json
            if (snap != null && snap.optBoolean("ok", false) && applySnapshot(snap)) {
                roomLink = OnlineLink.OK
                return
            }
        }
        val myId = auth.user?.id.orEmpty()
        val seat = if (j.isNull("seat")) -1 else j.optInt("seat", -1)
        val role = roleOf(j.optString("role", ""))
        room = OnlineRoom(
            id = id,
            code = j.optString("code", ""),
            playerCount = j.optInt("player_count", 2),
            maxWatchers = j.optInt("max_watchers", 0),
            status = j.optString("status", "waiting"),
            hostId = j.optString("host_id", "")
        )
        members = listOf(OnlineMember(myId, if (seat >= 0) seat else null, role, j.optString("name", "")))
        mySeat = seat
        myRole = role
        roomLink = OnlineLink.OK
    }

    /** Loads a room this person is a member of. Returns false (with a message) when that is not possible. */
    private suspend fun loadRoom(roomId: String): Boolean {
        val reply = call("ludo_room_snapshot", JSONObject().put("p_room", roomId))
        val j = reply.json
        if (j == null) {
            problem = failureText(reply.failure)
            return false
        }
        if (j.optBoolean("ok", false)) {
            if (!applySnapshot(j)) {
                problem = MSG_GENERIC
                return false
            }
            // Resuming a hosted game that could not be rebuilt closes the room (see afterSnapshot).
            if (room == null) return false
            if (room?.status == "ended") {
                val shown = room?.result
                clearLocal()
                myRoomRef = null
                if (hostSave?.roomId == roomId) clearHostSave()
                endedPanel = resultText(shown)
                return false
            }
            roomLink = OnlineLink.OK
            return true
        }
        val reason = j.optString("reason", "")
        if (hostSave?.roomId == roomId && reason != "not_signed_in") {
            clearHostSave()
            notice = MSG_HOST_GAME_OVER
            myRoomRef = null
            return false
        }
        if (reason == "ended" || reason == "expired") {
            endedPanel = resultText(parseResult(j.optJSONObject("result")))
            myRoomRef = null
            return false
        }
        notice = if (reason == "not_member") "You are no longer in that room." else reasonText(reason)
        return false
    }

    /** Copies a ludo_room_snapshot answer into the state. Returns false if the answer is not what was expected. */
    private fun applySnapshot(j: JSONObject): Boolean {
        return try {
            val r = j.getJSONObject("room")
            val parsed = OnlineRoom(
                id = r.getString("id"),
                code = r.getString("code"),
                playerCount = r.getInt("player_count"),
                maxWatchers = r.optInt("max_watchers", 0),
                status = r.getString("status"),
                hostId = r.optString("host_id", ""),
                gameNo = r.optInt("game_no", 0),
                assignment = parseOrder(r.optJSONArray("assignment"), r.getInt("player_count")) ?: emptyList(),
                result = parseResult(r.optJSONObject("result"))
            )
            val array = j.getJSONArray("members")
            val list = ArrayList<OnlineMember>()
            for (i in 0 until array.length()) {
                val m = array.getJSONObject(i)
                list.add(
                    OnlineMember(
                        userId = m.getString("user_id"),
                        seat = if (m.isNull("seat")) null else m.getInt("seat"),
                        role = roleOf(m.optString("role", "")),
                        name = m.optString("name", "")
                    )
                )
            }
            room = parsed
            members = list
            val myId = auth.user?.id
            val mine = list.firstOrNull { it.userId == myId }
            if (mine != null) {
                mySeat = mine.seat ?: -1
                myRole = mine.role
            }
            afterSnapshot()
            true
        } catch (e: JSONException) {
            false
        }
    }

    private fun roleOf(text: String): OnlineRole = when (text) {
        "host" -> OnlineRole.HOST
        "watcher" -> OnlineRole.WATCHER
        else -> OnlineRole.PLAYER
    }

    /** The friendly sentence for each reason code the database functions can answer with. */
    private fun reasonText(reason: String): String = when (reason) {
        "not_found" -> "No room with that code."
        "ended" -> "That room has closed."
        "expired" -> "That room has expired."
        "full" -> "This room is full."
        "started" -> "That game has already started."
        "watchers_full" -> "No watcher places are left in this room."
        "watchers_off" -> "This room does not allow watchers."
        "removed" -> "You were removed by the host."
        "not_signed_in" -> "Please sign in with Google again."
        else -> MSG_GENERIC
    }

    // ---------------------------------------------------------------------------------------
    // The online game (Phase 3)
    //
    // Design in one place. The HOST's phone runs the rules (LudoGame) exactly like the Offline host;
    // every other phone, players and watchers alike, shows a display model that is only ever filled
    // from the host's snapshots. Only the road the messages take is different from Offline:
    //
    //   ludo:<room>:down          host -> everybody   start, state, ev (live view), ended
    //   ludo:<room>:to:<user>     host -> one person  start + state (an answer to resync)
    //   ludo:<room>:up:<user>     one person -> host  intent (a tap) and resync
    //
    // Starting. When the room snapshot says "playing" and game_no is still 0, the host draws the
    // random colours, creates the engine, records game_no and the seat -> player assignment on the
    // server (ludo_publish_start), opens the socket, joins "down" and an "up" + "to" channel for
    // every person it knows, and publishes "start" and the first "state". Every 3 seconds it reads
    // the roster again, joins the channels of people who arrived meanwhile (watchers can come late)
    // and sends them the game without waiting for them to ask.
    //
    // Guests and watchers. They read game_no and the assignment from the same room snapshot, so they
    // can build the display model even if the live "start" never reaches them. They join "down",
    // "to:<me>" and "up:<me>", and as soon as all three are joined they send "resync"; the host
    // answers on "to:<me>". The resync is repeated every 2 seconds until the first state is on screen
    // and is sent again every time the channels are joined again (after a lost connection).
    //
    // Phase 4 (leave and return). Everybody also tracks Presence on "down" (key = user id). The host reads
    // it to see which phones are connected and publishes the per-seat flags (connected / out / removed) in
    // the "roster" part of every state; guests read the host's presence to know whether the host is gone.
    // A phone that lost its connection shows "Reconnecting..." (then Try again after 30 s); a vanished host
    // shows "Waiting for the host..." until it is back. The host saves the running game on this phone so
    // "Resume hosted game" can rebuild it; the result of every finished game is stored on the server so
    // people who come back later still see the winner, even if the host's phone is off.
    //
    // The sender of a tap is the user id in the "up" topic, which the server checks against the
    // caller. The host maps it to a seat through the roster and never reads a seat from a message.
    // A watcher has no seat, so everything from a watcher except "resync" is dropped.
    // ---------------------------------------------------------------------------------------

    /** The pointing hand of the other players (the game screen draws [RemoteHandPlayer.current]). */
    override val hand = RemoteHandPlayer(scope)

    /** One WebSocket for all the game channels. Its access token always comes from [OnlineAuth]. */
    private val socket = RealtimeSocket { auth.accessToken() }

    /**
     * The game on screen. On the host it is the real engine; on every other phone a display model that
     * is only filled from the host's snapshots. Null outside a game.
     */
    var game by mutableStateOf<LudoGame?>(null)
        private set

    /** True from the moment a game is on screen until this phone leaves it. */
    var inGame by mutableStateOf(false)
        private set

    /** 1 for the first game of the room, then one more for every new game. */
    var gameNo by mutableStateOf(0)
        private set

    /** The engine player each seat plays in the current game (index = seat). Re-drawn for every new game. */
    val playerOfSeat = mutableStateListOf<Int>()

    /** A message that covers the game screen with one OK button (the host ended it, ...). */
    override var finalText by mutableStateOf<String?>(null)
        private set

    /** The screen's coroutine scope: it has the frame clock the host's rules and the animations need. */
    private var uiScope: CoroutineScope? = null

    /** Bumped when a game is torn down; late answers and old loops notice and stop. */
    private var generation = 0
    private val random = SecureRandom()

    // Host working state
    private var seq = 0
    private var lastSent = ""
    private val seatScores = IntArray(4)
    private val queue = ArrayDeque<HostIntent>()
    private var draining = false
    private var runnerJob: Job? = null
    private var sessionEnded = false
    private var lastWinner = ""
    private var publisherJob: Job? = null
    private val hostKnown = HashSet<String>()
    private val evTimes = ArrayDeque<Long>()

    // Host, Phase 4
    /** People the host removed: seat -> name. They are no longer in the room list, so this keeps their name and flag. */
    private val removedInfo = HashMap<Int, String>()

    /** The user ids Presence says are connected (lower case), or null while Presence is not known. */
    private var presentUids: Set<String>? = null

    /** When each person was last seen in Presence (or first expected); a short absence is not yet "disconnected". */
    private val lastSeenAt = HashMap<String, Long>()

    /** The game number whose result is stored on the server, and when the last attempt was made. */
    private var resultStoredNo = 0
    private var resultAttemptAt = 0L

    /** The game changed since it was last saved on this phone, and when it was last saved. */
    private var saveDirty = false
    private var lastSaveAt = 0L

    // Everybody
    private var gameLoopJob: Job? = null
    private var channelsOpen = false

    // Guest and watcher working state
    private var resyncJob: Job? = null
    private var lastSeq = 0
    private var appliedGameNo = 0
    private var linkJob: Job? = null
    private var socketLostSince = 0L
    private var hostMissingSince = 0L
    private var hostPresenceReady = false
    private var hostPresent = false

    /** One animation a phone has been told to play. [seq] is the snapshot that follows it. */
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

    private val anims = ArrayDeque<Anim>()
    private var animJob: Job? = null
    private var safetyJob: Job? = null
    private var busyUntil = 0L
    private var pendingSnapshot: JSONObject? = null
    private var pendingSeq = 0
    private var visualsDirty = false

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

    /** The screen that runs the game gives its coroutine scope (see [uiScope]). */
    fun bindScope(ui: CoroutineScope) {
        uiScope = ui
    }

    // ----- What the game screen reads (LiveSession) -----

    override val isHost: Boolean get() = myRole == OnlineRole.HOST

    override val watching: Boolean get() = myRole == OnlineRole.WATCHER

    /** The engine player this phone controls (0..3), or -1 for a watcher or when there is no game. */
    override val myPlayer: Int get() = playerOfSeat.getOrElse(mySeat) { -1 }

    override val playerCount: Int get() = room?.playerCount ?: playerOfSeat.size

    override val hostName: String get() = members.firstOrNull { it.role == OnlineRole.HOST }?.name ?: ""

    override val watcherCount: Int get() = members.count { it.role == OnlineRole.WATCHER }

    /** Online watchers are chosen when the room is made, so this only says whether there are watcher places. */
    override val allowWatchers: Boolean get() = (room?.maxWatchers ?: 0) > 0

    /** True when somebody was removed by the host (no new game can start then). */
    override val hasRemoved: Boolean get() = flags.any { it.removed }

    /**
     * Per seat: connected / out / removed, and the name. On the host it is worked out from Presence and the
     * engine; on every other phone it is copied from the roster part of the host's latest state.
     */
    var flags by mutableStateOf<List<RosterEntry>>(emptyList())
        private set

    private fun flagOf(seat: Int): RosterEntry? = flags.firstOrNull { it.seat == seat }

    /** True when [seat] was knocked out of the tournament. */
    private fun isSeatOut(seat: Int): Boolean {
        val g = game ?: return false
        val p = playerOfSeat.getOrElse(seat) { -1 }
        return p >= 0 && g.tournament && !g.active[p]
    }

    /** A seat whose phone dropped and that the game still needs (not knocked out, not removed). */
    private fun isGone(seat: Int): Boolean {
        val e = flagOf(seat) ?: return false
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
            return flags.firstOrNull { isGone(it.seat) }?.seat ?: -1
        }

    /** How this phone's own connection is doing while a game is on screen (the host's is always shown as OK). */
    override var link by mutableStateOf(LinkState.OK)
        private set

    /** What the covering panel says while [link] is not OK: "Reconnecting..." or "Waiting for the host...". */
    override var linkMessage by mutableStateOf<String?>(null)
        private set

    /**
     * A small note over the board that does not cover the game: "Slow connection..." while this phone's
     * link is slow, or a short notice that a tap did not go through. Null = nothing to say.
     */
    override var liveNote by mutableStateOf<String?>(null)
        private set

    private var flashText: String? = null
    private var flashUntil = 0L
    private var slowLink = false
    private var noteJob: Job? = null

    /** When the last tap left this phone and which state number was on screen then (0 = no tap is waiting for its answer). */
    private var tapSentAt = 0L
    private var tapSeq = 0

    override val ticketText: String get() = ""

    override val isOnline: Boolean get() = true

    override val onlineRoomCode: String? get() = room?.code

    override val canToggleWatchers: Boolean get() = false

    override fun seatOfPlayer(player: Int): Int = playerOfSeat.indexOf(player)

    /** The name of the person in [seat]; a removed person is no longer in the room list, so the roster of the game is the fallback. */
    override fun seatName(seat: Int): String =
        members.firstOrNull { it.seat == seat && it.role != OnlineRole.WATCHER }?.name
            ?: flagOf(seat)?.name ?: ""

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
    private fun colorsOfSeat(seat: Int): List<LudoColor> {
        val p = playerOfSeat.getOrElse(seat) { -1 }
        return when {
            p < 0 -> emptyList()
            playerOfSeat.size >= 3 -> listOf(TOURNAMENT_COLORS[p])
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

    /**
     * Host only: true when the seat's connection is lost and the game is at a moment where the person can
     * be taken out (never for a connected person, never for the host, never while a result is showing).
     */
    override fun canRemove(seat: Int): Boolean {
        if (!isHost || !inGame || sessionEnded || finalText != null || seat <= 0) return false
        val g = game ?: return false
        val e = flagOf(seat) ?: return false
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

    /**
     * Host: takes [seat] out of the game (Remove player). The engine does the rest on the queue, so it never
     * runs at the same moment as a tap. Only allowed for a seat whose connection is lost.
     */
    override fun removePlayer(seat: Int) {
        if (!canRemove(seat)) return
        enqueue(HostIntent(0, K_REMOVE, i = seat))
    }

    override fun switchWatchers(on: Boolean) {
        // Online watchers are fixed when the room is created.
    }

    /** The Try again button: opens the 30 seconds of reconnecting again and joins the channels once more. */
    override fun retryReconnect() {
        val r = room ?: return
        if (isHost || !inGame || finalText != null) return
        if (link == LinkState.RECONNECTING) return
        socketLostSince = SystemClock.elapsedRealtime()
        hostMissingSince = 0L
        link = LinkState.RECONNECTING
        linkMessage = MSG_RECONNECTING
        socket.ensureConnected()
        guestOpenChannels(r)
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

    // ----- Taps from the game screen. The host queues them for itself, everybody else sends them as an "intent". -----

    private fun tapRoll() {
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

    /** Host and guest taps go through this one route: the host's own are queued, everybody else's are sent. */
    private fun act(intent: HostIntent, message: JSONObject) {
        if (isHost) {
            enqueue(intent)
            return
        }
        if (sendUp(message)) {
            // The host's answer is the next state; the note watch complains if it is very late.
            tapSentAt = SystemClock.elapsedRealtime()
            tapSeq = lastSeq
        } else {
            // The tap could not even leave this phone (the connection is down or too slow): say so, never stay silent.
            flash(MSG_TAP_LOST)
            requestState()
        }
    }

    /** Shows [text] over the board for a few seconds. */
    private fun flash(text: String) {
        flashText = text
        flashUntil = SystemClock.elapsedRealtime() + NOTE_FLASH_MS
        updateNote()
    }

    /** Works out what the small note over the board says right now. */
    private fun updateNote() {
        val now = SystemClock.elapsedRealtime()
        val text = when {
            flashText != null && now < flashUntil -> flashText
            slowLink -> if (isHost) MSG_SLOW_HOST else MSG_SLOW
            else -> null
        }
        if (liveNote != text) liveNote = text
    }

    /** Once a second while a game is on screen: is this phone's connection slow, and did the last tap get its answer? */
    private fun startNoteWatch() {
        noteJob?.cancel()
        val g = generation
        noteJob = scope.launch {
            while (g == generation) {
                delay(NOTE_STEP_MS)
                if (g != generation) return@launch
                if (foreground) checkNote()
            }
        }
    }

    private fun checkNote() {
        val now = SystemClock.elapsedRealtime()
        if (tapSentAt != 0L) {
            if (lastSeq > tapSeq) {
                tapSentAt = 0L
            } else if (now - tapSentAt >= TAP_WAIT_MS) {
                tapSentAt = 0L
                flash(MSG_TAP_SLOW)
                requestState()
            }
        }
        slowLink = socket.state == RealtimeState.CONNECTED &&
            (socket.roundTripMs >= SLOW_RTT_MS || socket.pendingHeartbeatMs() >= SLOW_PENDING_MS)
        updateNote()
    }

    // ----- Topics -----

    private fun downTopic(roomId: String) = "ludo:" + roomId.lowercase() + ":down"

    private fun toTopic(roomId: String, userId: String) = "ludo:" + roomId.lowercase() + ":to:" + userId.lowercase()

    private fun upTopic(roomId: String, userId: String) = "ludo:" + roomId.lowercase() + ":up:" + userId.lowercase()

    private fun myId(): String? = auth.user?.id?.lowercase()

    /** Sends [message] to the host on this person's own "up" channel; the message type is the event name. */
    private fun sendUp(message: JSONObject): Boolean {
        val r = room ?: return false
        val me = myId() ?: return false
        return socket.send(upTopic(r.id, me), message.optString("t", ""), message)
    }

    // ----- Starting a game, on whichever phone notices that the room is playing -----

    /**
     * Called after every room snapshot. It starts the game on the host when the room just became
     * "playing", lets guests and watchers enter the game once the host has recorded it, and notices
     * that the room was ended. Nothing here runs the rules.
     */
    private fun afterSnapshot() {
        val r = room ?: return
        if (finalText != null || sessionEnded) return
        if (r.status == "ended") {
            if (inGame) showGameOver(if (isHost) MSG_ROOM_CLOSED else resultText(r.result))
            return
        }
        if (r.status != "playing") return
        if (isHost) {
            if (!inGame && r.gameNo == 0) {
                hostBeginFirstGame()
            } else if (!inGame) {
                // This phone hosts a game that is already running (the app was closed): rebuild it from the save.
                if (hostResumeFromSave(r) == false) hostRoomLost()
            } else if (inGame) {
                syncHostMembers()
                // The record on the server is behind this game (the first call failed): say it again.
                if (r.gameNo != gameNo) publishStartToServer()
            }
        } else if (r.gameNo >= 1 && validOrder(r.assignment, r.playerCount)) {
            if (!inGame || r.gameNo > gameNo) guestEnterGame(r)
        }
    }

    private fun validOrder(order: List<Int>, n: Int): Boolean {
        if (n !in 2..4 || order.size != n || order.toSet().size != n) return false
        if (order.any { it !in 0..3 }) return false
        return !(n == 2 && order.any { it > 1 })
    }

    /** Reads a list of {seat, player} (the room record or a "start" message) into player-per-seat, or null if damaged. */
    private fun parseOrder(arr: JSONArray?, n: Int): List<Int>? {
        if (arr == null || n !in 2..4) return null
        val order = IntArray(n) { -1 }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val seat = o.optInt("seat", -1)
            val player = o.optInt("player", -1)
            if (seat !in 0 until n || player !in 0..3) continue
            order[seat] = player
        }
        val list = order.toList()
        return if (validOrder(list, n)) list else null
    }

    /** Who plays which engine player: 2 players = player 0 / 1 shuffled, 3 = three of the four colours, 4 = all. */
    private fun drawAssignment(count: Int): List<Int> {
        val pool = ArrayList<Int>(if (count == 2) listOf(0, 1) else listOf(0, 1, 2, 3))
        java.util.Collections.shuffle(pool, random)
        return pool.take(count)
    }

    private fun hostBeginFirstGame() {
        val r = room ?: return
        if (uiScope == null) return   // the screen is not up yet: the next snapshot tries again
        if (members.count { it.role != OnlineRole.WATCHER } < r.playerCount) return
        for (i in seatScores.indices) seatScores[i] = 0
        gameNo = 0
        sessionEnded = false
        lastWinner = ""
        hostKnown.clear()
        removedInfo.clear()
        resultStoredNo = 0
        presentUids = null
        lastSeenAt.clear()
        expectEveryone()
        hostBeginGame()
        inGame = true
        channelsOpen = true
        openHostChannels(r)
        syncHostMembers()
        startPublisher()
        startGameLoop()
        persistHost()
    }

    /** Host: opens the socket and joins "down" (everybody is told the game each time it is joined, and Presence is tracked). */
    private fun openHostChannels(r: OnlineRoom) {
        val me = myId() ?: return
        val down = downTopic(r.id)
        socket.open()
        socket.join(
            down,
            RealtimeChannelListener(
                onBroadcast = { _, _ -> },   // only the host speaks on "down"
                // Every time "down" is (re)joined, everybody is told the game again.
                onJoined = { hostAnnounceGame() },
                onPresence = { ready, present -> hostOnPresence(ready, present) }
            ),
            presenceKey = me
        )
        socket.track(down, presenceMeta())
    }

    /** What this phone tells everybody in Presence. It only drives indicators; it never grants anything. */
    private fun presenceMeta(): JSONObject = JSONObject()
        .put("uid", myId().orEmpty())
        .put("role", when (myRole) {
            OnlineRole.HOST -> "host"
            OnlineRole.WATCHER -> "watcher"
            else -> "player"
        })
        .put("seat", mySeat)

    /** Host: every person in the room counts as "just seen", so nobody is shown as gone before they had time to connect. */
    private fun expectEveryone() {
        val now = SystemClock.elapsedRealtime()
        for (m in members) lastSeenAt[m.userId.lowercase()] = now
    }

    /** The host's own screen and Presence both end up here: who is connected right now. */
    private fun hostOnPresence(ready: Boolean, present: Map<String, List<JSONObject>>) {
        if (!isHost) return
        if (!ready) {
            // The host's own connection dropped: nothing is known, so nobody is shown as gone.
            presentUids = null
            recomputeFlags()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val set = present.keys.map { it.lowercase() }.toSet()
        for (u in set) lastSeenAt[u] = now
        presentUids = set
        recomputeFlags()
    }

    private fun connectedNow(userId: String?): Boolean {
        if (userId == null) return false
        val present = presentUids ?: return true
        val id = userId.lowercase()
        if (id in present) return true
        val seen = lastSeenAt.getOrPut(id) { SystemClock.elapsedRealtime() }
        return SystemClock.elapsedRealtime() - seen < GONE_GRACE_MS
    }

    /** Host: works out the per-seat flags (name, connected, out, removed) that everybody is shown. */
    private fun recomputeFlags() {
        if (!isHost) return
        val list = ArrayList<RosterEntry>()
        for (seat in playerOfSeat.indices) {
            val m = members.firstOrNull { it.seat == seat && it.role != OnlineRole.WATCHER }
            val removed = removedInfo.containsKey(seat)
            val name = m?.name ?: removedInfo[seat] ?: ""
            val connected = seat == 0 || connectedNow(m?.userId)
            list.add(RosterEntry(seat, name, "", connected, seat == 0, isSeatOut(seat), removed))
        }
        val old = flags
        var same = old.size == list.size
        if (same) {
            for (i in list.indices) {
                val a = list[i]
                val b = old[i]
                if (a.seat != b.seat || a.name != b.name || a.connected != b.connected ||
                    a.out != b.out || a.removed != b.removed
                ) {
                    same = false
                    break
                }
            }
        }
        if (!same) flags = list
    }

    /** The roster part of every state: the per-seat flags as the Offline "lobby" message writes them. */
    private fun rosterJson(): JSONArray =
        ConnectProtocol.lobby(playerOfSeat.size, true, flags).optJSONArray("roster") ?: JSONArray()

    /**
     * Host: rebuilds a running game from the save on this phone. Returns true when the game is up again,
     * false when it cannot be rebuilt (no save, a different room or person, an older game, a damaged save),
     * and null when the screen is not up yet (the next snapshot tries again).
     */
    private fun hostResumeFromSave(r: OnlineRoom): Boolean? {
        if (uiScope == null) return null
        val text = prefs.getString(KEY_HOST_SAVE, null) ?: return false
        val me = myId() ?: return false
        try {
            val o = JSONObject(text)
            if (!o.optString("userId", "").equals(me, ignoreCase = true)) return false
            if (!o.optString("roomId", "").equals(r.id, ignoreCase = true)) return false
            val count = o.getInt("playerCount")
            if (count != r.playerCount) return false
            val no = o.getInt("gameNo")
            // A save that is older than the game the server knows would not match what the guests have.
            if (no < 1 || no < r.gameNo) return false
            val orderArr = o.getJSONArray("order")
            val order = ArrayList<Int>()
            for (i in 0 until orderArr.length()) order.add(orderArr.getInt(i))
            if (!validOrder(order, count)) return false
            val engine = LudoGame(tournament = count >= 3, connectActive = order)
            if (!engine.restore(o.getString("engine"))) return false
            engine.visualSink = { e -> onEngineEvent(e) }

            removedInfo.clear()
            val removedArr = o.optJSONArray("removed")
            if (removedArr != null) {
                for (i in 0 until removedArr.length()) {
                    val x = removedArr.optJSONObject(i) ?: continue
                    val seat = x.optInt("seat", -1)
                    if (seat in 1 until count) removedInfo[seat] = x.optString("name", "")
                }
            }
            resetLive()
            playerOfSeat.clear()
            playerOfSeat.addAll(order)
            for (i in seatScores.indices) seatScores[i] = 0
            for (seat in order.indices) seatScores[seat] = engine.scores.getOrElse(order[seat]) { 0 }
            game = engine
            gameNo = no
            // The guests' counters are far ahead of the last saved one; jumping forward keeps every new state newer.
            seq = o.optInt("seq", 0) + SEQ_JUMP
            lastSent = ""
            lastWinner = o.optString("lastWinner", "")
            sessionEnded = false
            resultStoredNo = 0
            hostKnown.clear()
            presentUids = null
            lastSeenAt.clear()
            expectEveryone()
            inGame = true
            channelsOpen = true
            openHostChannels(r)
            syncHostMembers()
            startPublisher()
            startGameLoop()
            return true
        } catch (e: Exception) {
            stopGame()
            return false
        }
    }

    /** The game this host was running cannot be rebuilt: the room is closed so nobody waits for it for ever. */
    private fun hostRoomLost() {
        val r = room ?: return
        clearHostSave()
        scope.launch { call("ludo_end_room", JSONObject().put("p_room", r.id)) }
        closeRoom(MSG_RESUME_FAILED)
    }

    /** Creates a fresh game with a new random assignment and the carried-over scores, and tells everybody. */
    private fun hostBeginGame() {
        val count = room?.playerCount ?: return
        val order = drawAssignment(count)
        val engine = LudoGame(tournament = order.size >= 3, connectActive = order)
        engine.visualSink = { e -> onEngineEvent(e) }
        resetLive()
        for (seat in order.indices) engine.scores[order[seat]] = seatScores[seat]
        playerOfSeat.clear()
        playerOfSeat.addAll(order)
        game = engine
        gameNo++
        lastSent = ""
        publishStartToServer()
        hostAnnounceGame()
    }

    /** Host: starts the next game of the same room. The scores follow the people to their new colours. */
    private fun newGameFromHost() {
        val old = game ?: return
        for (seat in playerOfSeat.indices) seatScores[seat] = old.scores.getOrElse(playerOfSeat[seat]) { 0 }
        hostBeginGame()
        persistHost()
    }

    /** The "start" message of the current game: player count, game number, and who plays which colours. */
    private fun startMessage(): JSONObject {
        val order = playerOfSeat.toList()
        val assignment = order.indices.map { seat ->
            SeatAssignment(seat, order[seat], colorsOfSeat(seat).map { it.name })
        }
        return ConnectProtocol.start(order.size, assignment, gameNo)
    }

    /** Records game_no and the assignment on the server so guests and watchers can build the board from it. */
    private fun publishStartToServer() {
        val r = room ?: return
        if (playerOfSeat.isEmpty()) return
        val no = gameNo
        val assignment = startMessage().optJSONArray("assignment") ?: return
        val g = generation
        scope.launch {
            val reply = call(
                "ludo_publish_start",
                JSONObject().put("p_room", r.id).put("p_game_no", no).put("p_assignment", assignment)
            )
            // A failure is simply tried again by the 3-second roster loop (the server's game_no is behind).
            if (g != generation) return@launch
            val j = reply.json
            if (j != null && !j.optBoolean("ok", false) && j.optString("reason", "") == "ended") {
                showGameOver(MSG_ROOM_CLOSED)
            }
        }
    }

    /** Host: tells everybody on "down" which game this is and what it looks like now. */
    private fun hostAnnounceGame() {
        val r = room ?: return
        if (!isHost || game == null || sessionEnded) return
        socket.send(downTopic(r.id), ConnectProtocol.T_START, startMessage())
        publish(force = true)
    }

    /**
     * Host: makes sure there is an "up" channel (to hear the person) and a "to" channel (to answer them)
     * for everybody in the roster, and drops the channels of people who are gone. A person that is new
     * is sent the game as soon as the "to" channel is joined.
     */
    private fun syncHostMembers() {
        val r = room ?: return
        val me = myId() ?: return
        val present = members.map { it.userId.lowercase() }.filter { it != me }.toSet()
        for (uid in present) {
            if (!hostKnown.add(uid)) continue
            lastSeenAt.getOrPut(uid) { SystemClock.elapsedRealtime() }
            socket.join(
                upTopic(r.id, uid),
                RealtimeChannelListener(onBroadcast = { event, body -> hostOnUp(uid, event, body) })
            )
            socket.join(
                toTopic(r.id, uid),
                RealtimeChannelListener(
                    onBroadcast = { _, _ -> },
                    onJoined = { hostSendGameTo(uid) }
                )
            )
        }
        for (uid in hostKnown.toList()) {
            if (uid in present) continue
            hostKnown.remove(uid)
            socket.leave(upTopic(r.id, uid))
            socket.leave(toTopic(r.id, uid))
        }
    }

    // ----- Host: keeping everybody in sync, carrying out taps -----

    private fun startPublisher() {
        publisherJob?.cancel()
        val g = generation
        publisherJob = scope.launch {
            while (g == generation && isHost) {
                delay(ONLINE_STATE_INTERVAL_MS)
                publish(force = false)
                maybeSave()
            }
        }
    }

    /**
     * Sends the game on "down" when it has changed (at most every [ONLINE_STATE_INTERVAL_MS], always the
     * latest one). Moves in progress are not part of it: other phones see the board jump to each new state.
     */
    private fun publish(force: Boolean) {
        val g = game ?: return
        val r = room ?: return
        if (!isHost || sessionEnded) return
        if (g.phase == Phase.GameOver) {
            for (seat in playerOfSeat.indices) seatScores[seat] = g.scores.getOrElse(playerOfSeat[seat]) { 0 }
            if (g.winner >= 0) lastWinner = playerName(g.winner)
            storeResultOnce()
        }
        // Who is connected, out or removed travels with every state, so every phone can show it.
        recomputeFlags()
        val roster = rosterJson()
        val snapshot = g.toMirrorJson()
        val text = snapshot.toString() + roster.toString()
        if (!force && text == lastSent) return
        lastSent = text
        saveDirty = true
        seq++
        socket.send(
            downTopic(r.id), ConnectProtocol.T_STATE,
            ConnectProtocol.state(seq, gameNo, snapshot).put("roster", roster)
        )
    }

    /** The JSON the server keeps about a finished game (or the whole session): winner and scores in seat order. */
    private fun resultJson(winner: String, no: Int): JSONObject {
        val scores = JSONArray()
        for (seat in playerOfSeat.indices) {
            scores.put(JSONObject().put("name", seatName(seat)).put("score", scoreOfSeat(seat)))
        }
        return JSONObject().put("winnerName", winner).put("scores", scores).put("gameNo", no)
    }

    /**
     * Host: once per finished game, stores its result on the server (so a person who comes back later still
     * sees the winner even if this phone is off). A failed call is tried again after a few seconds.
     */
    private fun storeResultOnce() {
        val g = game ?: return
        val r = room ?: return
        if (g.phase != Phase.GameOver || g.winner < 0 || resultStoredNo == gameNo) return
        val now = SystemClock.elapsedRealtime()
        if (resultAttemptAt != 0L && now - resultAttemptAt < RESULT_RETRY_MS) return
        resultAttemptAt = now
        val no = gameNo
        val gen = generation
        val payload = resultJson(lastWinner, no)
        scope.launch {
            val reply = call("ludo_set_result", JSONObject().put("p_room", r.id).put("p_result", payload))
            if (gen != generation) return@launch
            val j = reply.json ?: return@launch
            if (j.optBoolean("ok", false) || j.optString("reason", "") == "ended") resultStoredNo = no
        }
    }

    /** Host: sends one person the game (start, then the whole state) on their own "to" channel. */
    private fun hostSendGameTo(userId: String) {
        val r = room ?: return
        val g = game ?: return
        if (!isHost || sessionEnded) return
        val to = toTopic(r.id, userId)
        socket.send(to, ConnectProtocol.T_START, startMessage())
        seq++
        recomputeFlags()
        socket.send(
            to, ConnectProtocol.T_STATE,
            ConnectProtocol.state(seq, gameNo, g.toMirrorJson()).put("roster", rosterJson())
        )
    }

    private fun memberOf(userId: String): OnlineMember? =
        members.firstOrNull { it.userId.equals(userId, ignoreCase = true) }

    /** Something arrived on a person's "up" channel. [userId] comes from the topic, so it cannot be forged. */
    private fun hostOnUp(userId: String, event: String, body: JSONObject) {
        if (!isHost || game == null || sessionEnded) return
        if (body.optInt("v", -1) != ConnectProtocol.PROTOCOL_VERSION) return
        val member = memberOf(userId) ?: return
        try {
            when (event) {
                ConnectProtocol.T_RESYNC -> hostSendGameTo(userId)
                ConnectProtocol.T_INTENT -> hostIntent(member, body)
                else -> Unit
            }
        } catch (e: Exception) {
            // A bad message must never crash the app: drop it.
        }
    }

    /** A tap from a person: read it carefully (it is untrusted), then queue it. Watchers have no seat, so their taps are dropped. */
    private fun hostIntent(member: OnlineMember, msg: JSONObject) {
        val seat = member.seat ?: return
        if (member.role == OnlineRole.WATCHER) return
        if (removedInfo.containsKey(seat)) return
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

    /**
     * Puts a tap in line. The rule functions of the engine wait for animations, so the queue is
     * emptied by one coroutine, one tap at a time: two taps never run the engine at the same moment.
     */
    private fun enqueue(intent: HostIntent) {
        if (!isHost || game == null || sessionEnded) return
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
        if (!isHost || sessionEnded) return
        val player = playerOfSeat.getOrElse(req.seat) { -1 }
        if (player < 0) return
        val myTurn = !g.bannerPending && g.overlay == TOverlay.NONE && g.activePlayer == player
        when (req.kind) {
            ConnectProtocol.K_ROLL ->
                if (myTurn && g.phase == Phase.AwaitRoll) {
                    announceHand(req.seat, player, HandTarget.Dice, g.firstColorOf(player))
                    g.roll()
                }
            ConnectProtocol.K_SELECT_DIE ->
                if (myTurn && g.phase == Phase.Choose && req.i in 0..2) {
                    if (g.optionUsable(req.i)) {
                        announceHand(req.seat, player, HandTarget.Orb(req.i), g.firstColorOf(player))
                    }
                    g.selectDie(req.i)
                }
            ConnectProtocol.K_BOARD_TAP ->
                if (myTurn && (g.phase == Phase.AwaitRoll || g.phase == Phase.Choose)) {
                    if (g.phase == Phase.AwaitRoll) {
                        if (req.row in 6f..9f && req.col in 6f..9f) {
                            announceHand(req.seat, player, HandTarget.Dice, g.firstColorOf(player))
                        }
                    } else {
                        val hit = g.boardTapPiece(req.row, req.col)
                        if (hit != null) announceHand(req.seat, player, g.handSpotOf(hit), hit.color)
                    }
                    g.onBoardTap(req.row, req.col)
                }
            ConnectProtocol.K_PICK_PIECE ->
                if (myTurn && (g.phase == Phase.Choose || g.phase == Phase.CaptureChoose)) {
                    val piece = g.pieces.firstOrNull { it.color.name == req.color && it.slot == req.slot }
                    if (piece != null) {
                        val spot = g.pickSpotOf(piece)
                        if (spot != null) {
                            announceHand(req.seat, player, spot, g.captureMoverColor() ?: piece.color)
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
                if (req.seat == 0 && g.phase == Phase.GameOver && !hasRemoved) newGameFromHost()
            K_REMOVE ->
                if (req.seat == 0 && canRemove(req.i)) {
                    val victim = playerOfSeat.getOrElse(req.i) { -1 }
                    if (victim >= 0) {
                        g.connectRemove(victim)
                        // Flag the seat only if the engine really took the person out (the game may have moved on meanwhile).
                        val done = g.phase == Phase.GameOver || (g.tournament && !g.active[victim])
                        if (done) markRemoved(req.i)
                    }
                }
            else -> Unit
        }
    }

    /**
     * Host: the person in [seat] is out of the game for good. They are told on their own channel (if they ever
     * come back they see why), the server is told so they cannot return, and the roster flag goes out with the next state.
     */
    private fun markRemoved(seat: Int) {
        val r = room ?: return
        val m = members.firstOrNull { it.seat == seat && it.role != OnlineRole.WATCHER } ?: return
        removedInfo[seat] = m.name
        socket.send(toTopic(r.id, m.userId), ConnectProtocol.T_REMOVED, ConnectProtocol.removed())
        removeOnServer(r.id, m.userId)
        recomputeFlags()
        saveDirty = true
    }

    /** Tells the server to remove [userId]; a failed call is tried again by the 3-second loop. */
    private fun removeOnServer(roomId: String, userId: String) {
        scope.launch {
            call("ludo_remove_member", JSONObject().put("p_room", roomId).put("p_user", userId))
        }
    }

    /** Host: the server still lists somebody who was removed here (the first call failed): ask again. */
    private fun retryRemovals() {
        val r = room ?: return
        for (seat in removedInfo.keys.toList()) {
            val m = members.firstOrNull { it.seat == seat && it.role != OnlineRole.WATCHER } ?: continue
            removeOnServer(r.id, m.userId)
        }
    }

    // ----- Live view, host side -----

    /** Sends one "ev" on "down". Hand events (cosmetic, droppable) are skipped when too many went out. */
    private fun sendEv(message: JSONObject, droppable: Boolean) {
        val r = room ?: return
        if (!isHost || game == null) return
        val now = SystemClock.elapsedRealtime()
        while (evTimes.isNotEmpty() && now - evTimes.first() > 1000L) evTimes.removeFirst()
        if (droppable && evTimes.size >= MAX_EV_PER_SECOND) return
        evTimes.addLast(now)
        socket.send(downTopic(r.id), ConnectProtocol.T_EV, message)
    }

    /**
     * The host's rules are about to animate something: tell everybody, at the moment it starts. The "seq" is
     * the number the next snapshot will have, so a phone knows which snapshot to hold back until it has played it.
     */
    private fun onEngineEvent(e: VisualEvent) {
        if (!isHost) return
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
     * Tells everybody where [player]'s hand goes, just before the host carries out that (already checked)
     * tap. The host shows the hand of another person's tap itself; its own taps get no hand on its own screen.
     */
    private fun announceHand(seat: Int, player: Int, target: HandTarget, color: LudoColor) {
        sendEv(ConnectProtocol.evHand(seq + 1, player, color.name, target), true)
        if (seat != 0) hand.add(target, color)
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

    // ----- Host: End Game / End Tournament -----

    /**
     * Host: End Game / End Tournament. Everybody is told on "down", the room is closed on the server, and
     * the host stays on a final panel until it presses OK.
     */
    override fun endForEveryone() {
        val r = room ?: return
        if (!isHost || !inGame || sessionEnded) return
        val g = game
        if (g != null && g.phase == Phase.GameOver && g.winner >= 0) lastWinner = playerName(g.winner)
        val seats = (0 until playerOfSeat.size).toList()
        val names = seats.map { seatName(it) }
        val scores = seats.map { scoreOfSeat(it) }
        val result = resultJson(lastWinner, gameNo)
        sessionEnded = true
        queue.clear()
        runnerJob?.cancel()
        runnerJob = null
        draining = false
        socket.send(downTopic(r.id), ConnectProtocol.T_ENDED, ConnectProtocol.ended(lastWinner, names, scores))
        publisherJob?.cancel()
        publisherJob = null
        gameLoopJob?.cancel()
        gameLoopJob = null
        finalText = endedText(lastWinner, names, scores)
        clearHostSave()
        // The room is closed on the server with the result, so people who come back later see the winner.
        // A failed call is tried a few more times.
        scope.launch {
            var tries = 0
            while (tries < END_ROOM_TRIES) {
                val reply = call("ludo_end_room", JSONObject().put("p_room", r.id).put("p_result", result))
                if (reply.json != null) break
                tries++
                delay(END_ROOM_RETRY_MS)
            }
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

    // ----- Guests and watchers: entering the game, joining the channels -----

    /** Builds the display model for game [no] and starts asking the host for its state. */
    private fun buildDisplay(order: List<Int>, no: Int) {
        resetLive()
        playerOfSeat.clear()
        playerOfSeat.addAll(order)
        gameNo = no
        lastSeq = 0
        appliedGameNo = 0
        game = LudoGame(tournament = order.size >= 3, connectActive = order)
        inGame = true
        startResyncLoop(no)
    }

    /** Enters the game recorded on the server (the room snapshot), whether or not the live "start" arrived. */
    private fun guestEnterGame(r: OnlineRoom) {
        buildDisplay(r.assignment, r.gameNo)
        if (!channelsOpen) {
            channelsOpen = true
            guestOpenChannels(r)
        }
        if (gameLoopJob?.isActive != true) startGameLoop()
    }

    private fun guestOpenChannels(r: OnlineRoom) {
        val me = myId() ?: return
        val down = downTopic(r.id)
        val to = toTopic(r.id, me)
        val up = upTopic(r.id, me)
        // Once all three channels are joined the host can hear us and answer us: ask for the game.
        val ready: () -> Unit = {
            if (socket.isJoined(down) && socket.isJoined(to) && socket.isJoined(up)) requestState()
        }
        socket.open()
        socket.join(
            down,
            RealtimeChannelListener(
                onBroadcast = { event, body -> guestOnMessage(event, body) },
                onJoined = ready,
                onPresence = { isReady, present -> guestOnPresence(isReady, present, r.hostId) }
            ),
            presenceKey = me
        )
        socket.track(down, presenceMeta())
        socket.join(
            to,
            RealtimeChannelListener(onBroadcast = { event, body -> guestOnMessage(event, body) }, onJoined = ready)
        )
        socket.join(up, RealtimeChannelListener(onBroadcast = { _, _ -> }, onJoined = ready))
    }

    /** Asks the host to send the game again (start + the whole state) on this person's "to" channel. */
    private fun requestState() {
        if (isHost) return
        sendUp(ConnectProtocol.resync())
    }

    /** Keeps asking every 2 seconds until the first state of this game is on screen. */
    private fun startResyncLoop(no: Int) {
        resyncJob?.cancel()
        val g = generation
        resyncJob = scope.launch {
            var tries = 0
            while (g == generation && !isHost && appliedGameNo != no && tries < MAX_RESYNC_TRIES) {
                delay(RESYNC_WAIT_MS)
                if (g == generation && appliedGameNo != no) {
                    requestState()
                    tries++
                }
            }
        }
    }

    private fun guestOnMessage(event: String, body: JSONObject) {
        if (isHost || body.optInt("v", -1) != ConnectProtocol.PROTOCOL_VERSION) return
        try {
            when (event) {
                ConnectProtocol.T_START -> guestStart(body)
                ConnectProtocol.T_STATE -> guestState(body)
                ConnectProtocol.T_EV -> guestEvent(body)
                ConnectProtocol.T_ENDED -> {
                    if (finalText != null) return
                    val (names, scores) = ConnectProtocol.endedScoresFromJson(body.optJSONArray("scores"))
                    freezeGame()
                    myRoomRef = null
                    finalText = endedText(body.optString("winnerName", ""), names, scores)
                }
                ConnectProtocol.T_REMOVED -> {
                    if (finalText != null) return
                    freezeGame()
                    myRoomRef = null
                    finalText = MSG_REMOVED
                }
                else -> Unit
            }
        } catch (e: Exception) {
            // A bad message must never crash the app: drop it.
        }
    }

    /** The host started a game (or the next one): build a fresh display model and wait for the first state. */
    private fun guestStart(msg: JSONObject) {
        if (!inGame && room?.status != "playing") return
        val n = msg.optInt("playerCount", 0)
        val order = parseOrder(msg.optJSONArray("assignment"), n) ?: return
        val no = msg.optInt("gameNo", 0)
        if (no <= 0 || no < gameNo) return
        // The same game again (the host says it more than once): nothing to rebuild.
        if (no == gameNo && game != null) return
        buildDisplay(order, no)
    }

    /** A snapshot from the host. Older ones and ones of another game are dropped; a damaged one is ignored. */
    private fun guestState(msg: JSONObject) {
        if (game == null) return
        val s = msg.optInt("seq", -1)
        if (s <= lastSeq || msg.optInt("gameNo", -1) != gameNo) return
        val snapshot = msg.optJSONObject("game") ?: return
        // Who is connected, out or removed: shown at once, even if the board waits for an animation.
        msg.optJSONArray("roster")?.let { arr ->
            val list = ConnectProtocol.rosterFromJson(arr).sortedBy { it.seat }
            if (list.isNotEmpty()) flags = list
        }
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

    // ----- Live view, guest and watcher side -----

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
     * otherwise the host is asked for a fresh one. A lost event can therefore never freeze this phone
     * for more than about three seconds.
     */
    private fun armSafety() {
        safetyJob?.cancel()
        val g = generation
        val wait = (busyUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + SAFETY_MS
        safetyJob = scope.launch {
            delay(wait)
            if (g != generation || isHost) return@launch
            if (anims.isEmpty() && pendingSnapshot == null && !visualsDirty) return@launch
            animJob?.cancel()
            animJob = null
            anims.clear()
            val snap = pendingSnapshot
            if (snap != null) {
                pendingSnapshot = null
                applyGuestSnapshot(snap, pendingSeq)
            } else {
                requestState()
            }
        }
    }

    // ----- Every 3 seconds while a game is on screen -----

    /**
     * The slow loop of a game. Everybody re-reads the room: a new game number or the end of the room is
     * noticed even if the live message was lost. The host also joins the channels of people who arrived
     * meanwhile, repeats the server record if it is behind, and keeps the room from expiring.
     */
    private fun startGameLoop() {
        gameLoopJob?.cancel()
        if (!isHost) startLinkWatch()
        startNoteWatch()
        val g = generation
        gameLoopJob = scope.launch {
            var sinceTouch = 0L
            while (g == generation) {
                delay(GAME_TICK_MS)
                if (g != generation) return@launch
                if (!foreground) continue
                gameTick()
                sinceTouch += GAME_TICK_MS
                val r = room
                if (isHost && r != null && sinceTouch >= TOUCH_EVERY_MS) {
                    sinceTouch = 0L
                    call("ludo_touch_room", JSONObject().put("p_room", r.id))
                }
            }
        }
    }

    private suspend fun gameTick() {
        val current = room ?: return
        val reply = call("ludo_room_snapshot", JSONObject().put("p_room", current.id))
        val j = reply.json
        if (j == null) {
            // Only a real network problem shows the banner; other errors are simply tried again.
            if (reply.failure?.kind == OnlineException.Kind.NO_NETWORK) roomLink = OnlineLink.OFFLINE
            return
        }
        roomLink = OnlineLink.OK
        if (!inGame || room?.id != current.id || finalText != null) return
        if (j.optBoolean("ok", false)) {
            applySnapshot(j)
            if (isHost && inGame) retryRemovals()
        } else {
            when (j.optString("reason", "")) {
                "not_member" -> showGameOver("You are no longer in that room.")
                "removed" -> showGameOver(MSG_REMOVED)
                "expired" -> showGameOver(resultText(parseResult(j.optJSONObject("result"))))
                "not_found" -> showGameOver(reasonText("ended"))
                else -> Unit
            }
        }
    }

    // ----- Guests and watchers: Presence and the state of this phone's own connection -----

    /** What Presence says about the host: [ready] false means it is not known (this phone's connection is down). */
    private fun guestOnPresence(ready: Boolean, present: Map<String, List<JSONObject>>, hostId: String) {
        if (isHost) return
        hostPresenceReady = ready
        hostPresent = ready && present.keys.any { it.equals(hostId, ignoreCase = true) }
        if (link != LinkState.OK) evaluateLink()
    }

    /** Looks at the connection once a second while a game is on screen (guests and watchers only). */
    private fun startLinkWatch() {
        linkJob?.cancel()
        val g = generation
        linkJob = scope.launch {
            while (g == generation) {
                delay(LINK_STEP_MS)
                if (g != generation) return@launch
                if (foreground) evaluateLink()
            }
        }
    }

    private fun setLink(state: LinkState, message: String?) {
        val before = link
        if (before != state) link = state
        if (linkMessage != message) linkMessage = message
        // Back to normal: ask the host for the game again, in case something was missed meanwhile.
        if (before != LinkState.OK && state == LinkState.OK) requestState()
    }

    /**
     * The decision behind the covering panels:
     *  - this phone's connection (socket or its channels) is down: "Reconnecting..." after 2 seconds, then
     *    after 30 seconds Try again / Exit;
     *  - the connection is fine but the host is not on the channel: "Waiting for the host..." (no time limit;
     *    the person can Exit at any time and use Return to room later);
     *  - otherwise nothing.
     */
    private fun evaluateLink() {
        val r = room
        val me = myId()
        if (isHost || !inGame || finalText != null || r == null || me == null) {
            setLink(LinkState.OK, null)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val connected = socket.state == RealtimeState.CONNECTED &&
            socket.isJoined(downTopic(r.id)) && socket.isJoined(toTopic(r.id, me)) && socket.isJoined(upTopic(r.id, me))
        if (!connected) {
            if (socketLostSince == 0L) socketLostSince = now
            val lostFor = now - socketLostSince
            if (lostFor >= LINK_FAIL_MS) {
                setLink(LinkState.FAILED, MSG_RECONNECT_FAILED)
            } else if (lostFor >= LINK_GRACE_MS && link != LinkState.FAILED) {
                setLink(LinkState.RECONNECTING, MSG_RECONNECTING)
            }
            return
        }
        socketLostSince = 0L
        if (hostPresenceReady && !hostPresent) {
            if (hostMissingSince == 0L) hostMissingSince = now
            if (now - hostMissingSince >= HOST_GRACE_MS) {
                setLink(LinkState.RECONNECTING, MSG_WAITING_HOST)
                return
            }
        } else {
            hostMissingSince = 0L
        }
        setLink(LinkState.OK, null)
    }

    // ----- Ending and leaving -----

    /** Stops the loops and the socket but leaves the game on screen (a final message is about to cover it). */
    private fun freezeGame() {
        publisherJob?.cancel()
        publisherJob = null
        gameLoopJob?.cancel()
        gameLoopJob = null
        resyncJob?.cancel()
        resyncJob = null
        linkJob?.cancel()
        linkJob = null
        noteJob?.cancel()
        noteJob = null
        socket.close()
    }

    /** The game is over for this phone: it stays on screen under [text] and one OK button. */
    private fun showGameOver(text: String) {
        if (finalText != null) return
        freezeGame()
        myRoomRef = null
        finalText = text
    }

    /** Forgets the whole game on this phone: loops, socket, engine, scores, messages. */
    private fun stopGame() {
        generation++
        freezeGame()
        runnerJob?.cancel()
        runnerJob = null
        queue.clear()
        draining = false
        resetLive()
        channelsOpen = false
        hostKnown.clear()
        seq = 0
        lastSent = ""
        lastSeq = 0
        appliedGameNo = 0
        sessionEnded = false
        lastWinner = ""
        for (i in seatScores.indices) seatScores[i] = 0
        removedInfo.clear()
        presentUids = null
        lastSeenAt.clear()
        resultStoredNo = 0
        resultAttemptAt = 0L
        saveDirty = false
        socketLostSince = 0L
        hostMissingSince = 0L
        hostPresenceReady = false
        hostPresent = false
        flags = emptyList()
        link = LinkState.OK
        linkMessage = null
        liveNote = null
        flashText = null
        slowLink = false
        tapSentAt = 0L
        game = null
        inGame = false
        gameNo = 0
        playerOfSeat.clear()
        finalText = null
    }

    /**
     * Leaves the game on this phone (Exit for a guest or watcher, or OK on a final message). A watcher
     * is told to the server so the place is free again; a player keeps the seat and can come back.
     */
    override fun leave() {
        val r = room
        val wasWatcher = myRole == OnlineRole.WATCHER
        val closed = finalText != null
        if (isHost && inGame) {
            // A game that is still running is only paused (it can be resumed); a finished or ended one is let go.
            val over = game?.phase == Phase.GameOver
            if (closed || sessionEnded || over) clearHostSave() else persistHost()
        }
        stopPolling()
        clearLocal()
        if (closed) myRoomRef = null
        if (r != null && wasWatcher && !closed) {
            scope.launch { call("ludo_leave_room", JSONObject().put("p_room", r.id)) }
        }
    }

    // ----- The saved hosted game (Resume hosted game) -----

    private fun readHostSaveInfo(): OnlineHostSave? {
        val text = prefs.getString(KEY_HOST_SAVE, null) ?: return null
        return try {
            val o = JSONObject(text)
            val count = o.getInt("playerCount")
            if (count !in 2..4) return null
            OnlineHostSave(o.getString("userId"), o.getString("roomId"), o.optString("code", ""), count)
        } catch (e: Exception) {
            null
        }
    }

    private fun clearHostSave() {
        prefs.edit().remove(KEY_HOST_SAVE).apply()
        hostSave = null
        saveDirty = false
    }

    /** Saves the running game now if it changed; at most about every two seconds. */
    private fun maybeSave() {
        if (!isHost || !inGame || sessionEnded || game == null) return
        if (!saveDirty || SystemClock.elapsedRealtime() - lastSaveAt < SAVE_INTERVAL_MS) return
        persistHost()
    }

    /**
     * Writes everything needed to resume the hosted game: the room, the colours of each seat, the game number,
     * the people who were removed and the engine itself. People who are in the room are not saved: the server
     * knows them, and they re-attach by user id.
     */
    private fun persistHost() {
        val g = game ?: return
        val r = room ?: return
        val me = myId() ?: return
        if (!isHost || !inGame || sessionEnded || finalText != null || playerOfSeat.isEmpty()) return
        try {
            val o = JSONObject()
                .put("userId", me)
                .put("roomId", r.id)
                .put("code", r.code)
                .put("playerCount", r.playerCount)
                .put("maxWatchers", r.maxWatchers)
                .put("gameNo", gameNo)
                .put("seq", seq)
                .put("lastWinner", lastWinner)
                .put("order", JSONArray().also { a -> playerOfSeat.forEach { a.put(it) } })
                .put("removed", JSONArray().also { a ->
                    for ((seat, name) in removedInfo) a.put(JSONObject().put("seat", seat).put("name", name))
                })
                .put("engine", g.toSaveString())
                .put("time", System.currentTimeMillis())
            prefs.edit().putString(KEY_HOST_SAVE, o.toString()).apply()
            hostSave = OnlineHostSave(me, r.id, r.code, r.playerCount)
            saveDirty = false
            lastSaveAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            // A failed save only costs the Resume button.
        }
    }

    // ----- Results -----

    /** Reads the result the server stores ({winnerName, scores: [{name, score}], gameNo}); null when there is none. */
    private fun parseResult(o: JSONObject?): OnlineResult? {
        if (o == null) return null
        val names = ArrayList<String>()
        val scores = ArrayList<Int>()
        val arr = o.optJSONArray("scores")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                names.add(x.optString("name", "Player"))
                scores.add(x.optInt("score", 0))
            }
        }
        return OnlineResult(o.optString("winnerName", ""), names, scores, o.optInt("gameNo", 0))
    }

    /** The "Game ended" text for a stored result; plain "Game ended." when the host never stored one. */
    private fun resultText(result: OnlineResult?): String =
        if (result == null) "Game ended." else endedText(result.winnerName, result.names, result.scores)

    private fun colorWord(c: LudoColor): String = when (c) {
        LudoColor.RED -> "Red"
        LudoColor.GREEN -> "Green"
        LudoColor.YELLOW -> "Yellow"
        LudoColor.BLUE -> "Blue"
    }

    private companion object {
        /** How often the room screen asks the server for the room, in milliseconds. */
        const val POLL_INTERVAL_MS = 1500L
        const val MSG_GENERIC = "Something went wrong. Please try again."
        const val MSG_NO_NET = "Couldn't reach the server. Your internet may be off or slow - please try again."
        const val MSG_ROOM_CLOSED = "The host closed the room."
        const val MSG_GAME_ENDED = "The host ended the game."
        const val MSG_REMOVED = "You were removed by the host."
        const val MSG_RECONNECTING = "Reconnecting..."
        const val MSG_RECONNECT_FAILED = "Couldn't reconnect yet."
        const val MSG_WAITING_HOST = "Waiting for the host..."
        const val MSG_HOST_GAME_OVER = "That game has already ended."
        const val MSG_RESUME_FAILED = "That game could not be resumed, so the room was closed."

        /** Host-only work item: take a person out of the game (never sent over the air). */
        const val K_REMOVE = "remove"

        const val KEY_HOST_SAVE = "online_host_save"
        const val SAVE_INTERVAL_MS = 2_000L

        /** A person absent from Presence for this long counts as disconnected (it spares the first seconds of a join). */
        const val GONE_GRACE_MS = 8_000L

        /** After a resume the sequence number of the states jumps this far ahead of the last saved one. */
        const val SEQ_JUMP = 1_000
        const val RESULT_RETRY_MS = 5_000L
        const val END_ROOM_TRIES = 4
        const val END_ROOM_RETRY_MS = 2_000L

        // Slow-connection notes (kept short: the note must fit on one line over the board).
        const val MSG_SLOW = "Slow connection - moves may be late"
        const val MSG_SLOW_HOST = "Your connection is slow"
        const val MSG_TAP_LOST = "Tap not sent - slow connection"
        const val MSG_TAP_SLOW = "Still waiting for the host..."

        /** A heartbeat answer slower than this, or one still unanswered after [SLOW_PENDING_MS], means a slow link. */
        const val SLOW_RTT_MS = 1_500L
        const val SLOW_PENDING_MS = 3_000L

        /** A tap whose answer (the next state) has not come after this long is reported. */
        const val TAP_WAIT_MS = 4_000L
        const val NOTE_STEP_MS = 1_000L
        const val NOTE_FLASH_MS = 4_000L

        /** A room-screen answer slower than this shows "Slow connection...". */
        const val POLL_SLOW_MS = 2_500L

        const val LINK_STEP_MS = 1_000L
        const val LINK_GRACE_MS = 2_000L
        const val LINK_FAIL_MS = 30_000L
        const val HOST_GRACE_MS = 6_000L

        /** Tournament-style engine players 0..3 = red, green, yellow, blue. */
        val TOURNAMENT_COLORS = listOf(LudoColor.RED, LudoColor.GREEN, LudoColor.YELLOW, LudoColor.BLUE)

        const val GAME_TICK_MS = 3_000L
        const val TOUCH_EVERY_MS = 60_000L
        const val RESYNC_WAIT_MS = 2_000L
        const val MAX_RESYNC_TRIES = 5
        const val MAX_QUEUE = 40
        const val MAX_EV_PER_SECOND = 10
        const val SAFETY_MS = 3_000L
        val HAND_LEAD_MS = ConnectSession.HAND_LEAD_MS
    }
}
