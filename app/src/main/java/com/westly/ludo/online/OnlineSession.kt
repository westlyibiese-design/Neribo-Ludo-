package com.westly.ludo.online

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

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
    val hostId: String
)

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
) {
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

    var link by mutableStateOf(OnlineLink.OK)
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

    // ---------------------------------------------------------------------------------------
    // App lifecycle
    // ---------------------------------------------------------------------------------------

    fun onAppForeground() {
        foreground = true
    }

    fun onAppBackground() {
        foreground = false
    }

    /** Stops polling and any call in progress. Called when the activity is destroyed. */
    fun release() {
        scope.cancel()
    }

    /** Forgets everything on this phone (used when the player signs out). Nothing is sent to the server. */
    fun reset() {
        stopPolling()
        clearLocal()
        myRoomRef = null
        conflict = null
        offerWatchCode = null
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
        }
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
        val reply = call("ludo_room_snapshot", JSONObject().put("p_room", current.id))
        val j = reply.json
        if (j == null) {
            // Only a real network problem shows the banner; other errors are simply tried again.
            if (reply.failure?.kind == OnlineException.Kind.NO_NETWORK) link = OnlineLink.OFFLINE
            return
        }
        link = OnlineLink.OK
        // The person may have left while this call was on its way.
        if (room?.id != current.id) return
        if (j.optBoolean("ok", false)) {
            if (!applySnapshot(j)) return
            if (room?.status == "ended") closeRoom("The host closed the room.")
        } else {
            when (j.optString("reason", "")) {
                "not_member" -> closeRoom("You are no longer in that room.")
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
        room = null
        members = emptyList()
        mySeat = -1
        myRole = null
        link = OnlineLink.OK
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
                link = OnlineLink.OK
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
        link = OnlineLink.OK
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
            if (room?.status == "ended") {
                clearLocal()
                notice = reasonText("ended")
                return false
            }
            link = OnlineLink.OK
            return true
        }
        val reason = j.optString("reason", "")
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
                hostId = r.optString("host_id", "")
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
        "removed" -> "The host removed you from that room."
        "not_signed_in" -> "Please sign in with Google again."
        else -> MSG_GENERIC
    }

    private companion object {
        /** How often the room screen asks the server for the room, in milliseconds. */
        const val POLL_INTERVAL_MS = 1500L
        const val MSG_GENERIC = "Something went wrong. Please try again."
        const val MSG_NO_NET = "Online needs an internet connection."
    }
}
