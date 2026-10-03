package com.westly.ludo.connect

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    // ----- Private working state -----

    private var token = ""
    private var started = false
    private var myName = ""
    private var hostEndpoint: String? = null

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
     * Host pressed Start. Phase 1 only checks that every seat is filled (the screen then shows a
     * short "everyone is connected" box); Phase 2 replaces this body with the real game start.
     * Returns true when the start was accepted.
     */
    fun requestStart(): Boolean = role == ConnectRole.HOST && state == ConnectState.HOST_LOBBY && isFull

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
        helloTimers.clear()
        guestTimer = null
        manager.stopAll()
        seatByEndpoint.clear()
        hostEndpoint = null
        started = false
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
                    teardown()
                    ended = MSG_HOST_GONE
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
        if (at >= 0) roster.removeAt(at)
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
            ConnectProtocol.T_REJECT -> failJoin(rejectMessage(msg.optString("reason", "")))
            ConnectProtocol.T_BYE -> {
                teardown()
                ended = MSG_HOST_GONE
            }
            else -> Unit // unknown type: ignore
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
    }
}
