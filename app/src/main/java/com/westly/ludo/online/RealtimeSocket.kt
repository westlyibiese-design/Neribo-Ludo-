package com.westly.ludo.online

import android.os.SystemClock
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** How the one WebSocket to Supabase Realtime is doing. */
enum class RealtimeState { CONNECTED, CONNECTING, DISCONNECTED }

/**
 * What a channel tells its owner. Everything is called on the main thread.
 * [onBroadcast] gets the broadcast's event name and its JSON body, [onJoined] runs every time the
 * channel is (re)joined successfully, [onProblem] gets a short reason when a join is refused,
 * [onPresence] says who is on the channel (only when it was joined with a presence key).
 */
class RealtimeChannelListener(
    val onBroadcast: (event: String, payload: JSONObject) -> Unit,
    val onJoined: () -> Unit = {},
    val onProblem: (reason: String) -> Unit = {},
    /**
     * Presence (only for a channel joined with a presence key). [ready] is false while the list is
     * unknown (not joined yet, or the connection dropped); then [present] is empty and means nothing.
     * Otherwise [present] maps each presence key to the metas it tracks (one per connection).
     */
    val onPresence: (ready: Boolean, present: Map<String, List<JSONObject>>) -> Unit = { _, _ -> }
)

/**
 * A small Supabase Realtime client: one OkHttp WebSocket speaking the Phoenix channel protocol
 * (JSON frames {topic, event, payload, ref}), with private Broadcast channels only.
 *
 * What it does for its owner:
 *  - [open] / [close] start and stop it; while it is wanted it reconnects by itself (1 s, 2 s, 4 s ...
 *    up to 10 s) and joins every channel again each time.
 *  - [join] / [leave] / [send] work on channel NAMES such as "ludo:<room>:down" (the "realtime:"
 *    prefix of the wire topic is added here).
 *  - [track] / [untrack] and [RealtimeChannelListener.onPresence] give Phoenix Presence on a channel that
 *    was joined with a presence key: who is connected, learned from the server's presence_state and
 *    presence_diff frames. A dropped socket makes the list "not ready" until the next join.
 *  - A heartbeat goes out every 5 s (the answer's delay is the connection's round trip, see [roundTripMs]);
 *    while one is unanswered no new one is sent, and 6 unanswered ticks (about 30 s) mean the socket is dead.
 *  - The access token is checked every 30 s and sent to every joined channel when it changed, so a
 *    long game keeps working after the one-hour token was renewed.
 *
 * Nothing here blocks the main thread: OkHttp calls back on its own threads and every callback is
 * moved to the main thread before it touches any state. A damaged frame is ignored, and no token is
 * ever logged.
 */
class RealtimeSocket(private val tokenProvider: suspend () -> String?) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** CONNECTED / CONNECTING / DISCONNECTED, as Compose state. */
    var state by mutableStateOf(RealtimeState.DISCONNECTED)
        private set

    /** How long the server took to answer the last heartbeat, in milliseconds (0 = not measured yet). */
    var roundTripMs by mutableStateOf(0L)
        private set

    /** How long the heartbeat that is still unanswered has been waiting (0 = none waiting). A growing number means a slow link. */
    fun pendingHeartbeatMs(): Long =
        if (heartbeatRef != null && state == RealtimeState.CONNECTED) SystemClock.elapsedRealtime() - heartbeatSentAt else 0L

    private class Channel(val name: String, val listener: RealtimeChannelListener, val presenceKey: String?) {
        var joined = false
        var joinRef = ""
        var failures = 0

        /** What this phone tracks in Presence on this channel (sent again after every rejoin), or null. */
        var presenceMeta: JSONObject? = null

        /** Presence: key -> the metas of that key, as the server last told us. */
        val presence = LinkedHashMap<String, MutableList<JSONObject>>()
        var presenceReady = false
    }

    private val channels = LinkedHashMap<String, Channel>()
    private var ws: WebSocket? = null

    /** Bumped whenever a socket is replaced or dropped; callbacks of an older socket are ignored. */
    private var generation = 0
    private var wanted = false
    private var refCounter = 0
    private var backoffMs = FIRST_BACKOFF_MS
    private var heartbeatJob: Job? = null
    private var tokenJob: Job? = null
    private var reconnectJob: Job? = null
    private var heartbeatRef: String? = null
    private var heartbeatSentAt = 0L
    private var missedHeartbeats = 0
    private var lastToken: String? = null

    /** The OkHttp client for the socket: no read or call time limit (the heartbeat does that job). */
    private val client: OkHttpClient = OnlineHttp.client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------------------------------
    // Owner API
    // ---------------------------------------------------------------------------------------

    /** Starts the socket (and keeps it alive) until [close]. Safe to call again. */
    fun open() {
        wanted = true
        if (ws == null && reconnectJob == null) connect()
    }

    /** Stops the socket, forgets every channel and stops reconnecting. */
    fun close() {
        wanted = false
        generation++
        heartbeatJob?.cancel()
        heartbeatJob = null
        tokenJob?.cancel()
        tokenJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        val old = ws
        ws = null
        if (old != null) {
            try {
                old.close(NORMAL_CLOSE, "bye")
            } catch (e: Exception) {
                // Already closed: nothing to do.
            }
        }
        channels.clear()
        heartbeatRef = null
        missedHeartbeats = 0
        backoffMs = FIRST_BACKOFF_MS
        state = RealtimeState.DISCONNECTED
    }

    /** [close] and give up the scope for good (the app is closing). */
    fun release() {
        close()
        scope.cancel()
    }

    /** The app came back to the front: if the socket was lost while it slept, connect again now. */
    fun ensureConnected() {
        if (!wanted || ws != null) return
        reconnectJob?.cancel()
        reconnectJob = null
        backoffMs = FIRST_BACKOFF_MS
        connect()
    }

    /**
     * The app came back and thinks it is connected: ask the server for an answer right now. If none comes
     * within [PROBE_MS] the socket is treated as dead (and reconnects), instead of waiting for the slow heartbeat.
     */
    fun probe() {
        val socket = ws ?: return
        if (state != RealtimeState.CONNECTED) return
        val forGeneration = generation
        val ref = nextRef()
        heartbeatRef = ref
        heartbeatSentAt = SystemClock.elapsedRealtime()
        socket.send(frame("phoenix", "heartbeat", JSONObject(), ref, null))
        scope.launch {
            delay(PROBE_MS)
            if (forGeneration == generation && heartbeatRef == ref) handleDrop(forGeneration)
        }
    }

    /**
     * Joins the private channel [name]. Joining the same name again replaces the old listener.
     * If the socket is not connected yet the join happens as soon as it is. With a [presenceKey]
     * the channel also takes part in Presence (see [track] and [RealtimeChannelListener.onPresence]).
     */
    fun join(name: String, listener: RealtimeChannelListener, presenceKey: String? = null) {
        val old = channels.remove(name)
        if (old != null && old.joined) sendLeave(old)
        val channel = Channel(name, listener, presenceKey)
        channels[name] = channel
        if (state == RealtimeState.CONNECTED) sendJoin(channel)
    }

    /** Leaves the channel [name] (does nothing if it was never joined). */
    fun leave(name: String) {
        val channel = channels.remove(name) ?: return
        if (channel.joined) sendLeave(channel)
    }

    /** True when the channel [name] has been joined successfully on the current connection. */
    fun isJoined(name: String): Boolean = channels[name]?.joined == true

    /**
     * Tells everybody on [name] (a channel joined with a presence key) that this phone is here, with
     * [meta] attached. It is sent again by itself after every reconnect until [untrack] or [leave].
     */
    fun track(name: String, meta: JSONObject) {
        val channel = channels[name] ?: return
        if (channel.presenceKey == null) return
        channel.presenceMeta = meta
        if (channel.joined) sendTrack(channel)
    }

    /** Stops telling the others that this phone is here (they see it leave). */
    fun untrack(name: String) {
        val channel = channels[name] ?: return
        if (channel.presenceMeta == null) return
        channel.presenceMeta = null
        if (!channel.joined) return
        val socket = ws ?: return
        val payload = JSONObject().put("type", "presence").put("event", "untrack")
        socket.send(frame(WIRE_PREFIX + channel.name, "presence", payload, nextRef(), channel.joinRef))
    }

    /**
     * Broadcasts [payload] as [event] on [name]. Returns false when it could not be handed to the
     * socket (not joined, not connected); callers treat a lost message as harmless because the
     * game state is always repaired by the next snapshot.
     */
    fun send(name: String, event: String, payload: JSONObject): Boolean {
        val channel = channels[name] ?: return false
        val socket = ws ?: return false
        if (!channel.joined) return false
        return try {
            val inner = JSONObject()
                .put("type", "broadcast")
                .put("event", event)
                .put("payload", payload)
            socket.send(frame(WIRE_PREFIX + name, "broadcast", inner, nextRef(), channel.joinRef))
        } catch (e: JSONException) {
            false
        }
    }

    // ---------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------

    private fun connect() {
        if (!wanted || ws != null) return
        state = RealtimeState.CONNECTING
        val myGeneration = ++generation
        val base = OnlineConfig.SUPABASE_URL.replaceFirst("https://", "wss://")
        val url = base + "/realtime/v1/websocket?apikey=" + OnlineConfig.SUPABASE_PUBLISHABLE_KEY + "&vsn=1.0.0"
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            state = RealtimeState.DISCONNECTED
            return
        }
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch { handleOpen(myGeneration) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(myGeneration, text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { handleDrop(myGeneration) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { handleDrop(myGeneration) }
            }
        })
    }

    private fun handleOpen(forGeneration: Int) {
        if (forGeneration != generation) return
        state = RealtimeState.CONNECTED
        backoffMs = FIRST_BACKOFF_MS
        missedHeartbeats = 0
        heartbeatRef = null
        roundTripMs = 0L
        startHeartbeat(forGeneration)
        startTokenWatch(forGeneration)
        for (channel in channels.values.toList()) sendJoin(channel)
    }

    /** The socket is gone (closed, failed or declared dead): note it, and try again if still wanted. */
    private fun handleDrop(forGeneration: Int) {
        if (forGeneration != generation) return
        generation++
        heartbeatJob?.cancel()
        heartbeatJob = null
        tokenJob?.cancel()
        tokenJob = null
        val old = ws
        ws = null
        if (old != null) {
            try {
                old.cancel()
            } catch (e: Exception) {
                // Already gone.
            }
        }
        for (channel in channels.values.toList()) {
            channel.joined = false
            forgetPresence(channel)
        }
        state = RealtimeState.DISCONNECTED
        if (wanted) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob != null) return
        val wait = backoffMs
        backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
        reconnectJob = scope.launch {
            delay(wait)
            reconnectJob = null
            connect()
        }
    }

    private fun startHeartbeat(forGeneration: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (forGeneration != generation) return@launch
                if (heartbeatRef != null) {
                    // The last one is still unanswered: keep waiting for it (its delay is what slowLink measures).
                    missedHeartbeats++
                    if (missedHeartbeats >= MAX_MISSED_HEARTBEATS) {
                        handleDrop(forGeneration)
                        return@launch
                    }
                    continue
                }
                val ref = nextRef()
                heartbeatRef = ref
                heartbeatSentAt = SystemClock.elapsedRealtime()
                ws?.send(frame("phoenix", "heartbeat", JSONObject(), ref, null))
            }
        }
    }

    /** Sends a renewed access token to every joined channel (the Realtime server re-checks access with it). */
    private fun startTokenWatch(forGeneration: Int) {
        tokenJob?.cancel()
        tokenJob = scope.launch {
            while (isActive) {
                delay(TOKEN_CHECK_MS)
                if (forGeneration != generation) return@launch
                val token = fetchToken() ?: continue
                if (token == lastToken) continue
                lastToken = token
                val socket = ws ?: continue
                for (channel in channels.values) {
                    if (!channel.joined) continue
                    val payload = JSONObject().put("access_token", token)
                    socket.send(frame(WIRE_PREFIX + channel.name, "access_token", payload, nextRef(), channel.joinRef))
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Channels
    // ---------------------------------------------------------------------------------------

    private fun sendJoin(channel: Channel) {
        val forGeneration = generation
        channel.joined = false
        scope.launch {
            val token = fetchToken()
            if (token == null) {
                channel.listener.onProblem("not_signed_in")
                return@launch
            }
            if (forGeneration != generation || channels[channel.name] !== channel) return@launch
            val socket = ws ?: return@launch
            lastToken = token
            val ref = nextRef()
            channel.joinRef = ref
            forgetPresence(channel)
            val presence = JSONObject().put("key", channel.presenceKey ?: "")
            if (channel.presenceKey != null) presence.put("enabled", true)
            val config = JSONObject()
                .put("broadcast", JSONObject().put("self", false).put("ack", false))
                .put("presence", presence)
                .put("postgres_changes", JSONArray())
                .put("private", true)
            val payload = JSONObject().put("config", config).put("access_token", token)
            socket.send(frame(WIRE_PREFIX + channel.name, "phx_join", payload, ref, ref))
        }
    }

    private fun sendLeave(channel: Channel) {
        val socket = ws ?: return
        socket.send(frame(WIRE_PREFIX + channel.name, "phx_leave", JSONObject(), nextRef(), channel.joinRef))
    }

    /** Sends this phone's Presence entry on [channel] (the same frame the official clients send for track). */
    private fun sendTrack(channel: Channel) {
        val meta = channel.presenceMeta ?: return
        val socket = ws ?: return
        val payload = JSONObject().put("type", "presence").put("event", "track").put("payload", meta)
        socket.send(frame(WIRE_PREFIX + channel.name, "presence", payload, nextRef(), channel.joinRef))
    }

    /** The Presence list of [channel] is not known any more (dropped, or about to be joined again). */
    private fun forgetPresence(channel: Channel) {
        val wasReady = channel.presenceReady
        channel.presence.clear()
        channel.presenceReady = false
        if (wasReady && channel.presenceKey != null) {
            try {
                channel.listener.onPresence(false, emptyMap())
            } catch (e: Exception) {
                // A listener problem must never take the socket down.
            }
        }
    }

    /** Tells the owner of [channel] what Presence now looks like. */
    private fun notifyPresence(channel: Channel) {
        val copy = HashMap<String, List<JSONObject>>()
        for ((key, metas) in channel.presence) copy[key] = metas.toList()
        try {
            channel.listener.onPresence(channel.presenceReady, copy)
        } catch (e: Exception) {
            // A listener problem must never take the socket down.
        }
    }

    /** Reads the {key: {metas: [...]}} object of a presence_state / presence_diff frame into key -> metas. */
    private fun readMetas(o: JSONObject?): Map<String, List<JSONObject>> {
        val out = LinkedHashMap<String, List<JSONObject>>()
        if (o == null) return out
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val array = o.optJSONObject(key)?.optJSONArray("metas") ?: continue
            val list = ArrayList<JSONObject>()
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { list.add(it) }
            out[key] = list
        }
        return out
    }

    /** After a refused or crashed join, try again a few times (not forever: a refusal is usually final). */
    private fun scheduleRejoin(channel: Channel) {
        channel.failures++
        if (channel.failures > MAX_JOIN_FAILURES) return
        val forGeneration = generation
        scope.launch {
            delay(REJOIN_DELAY_MS)
            if (forGeneration == generation && channels[channel.name] === channel &&
                !channel.joined && state == RealtimeState.CONNECTED
            ) {
                sendJoin(channel)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Incoming frames
    // ---------------------------------------------------------------------------------------

    private fun handleMessage(forGeneration: Int, text: String) {
        if (forGeneration != generation) return
        val msg = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return
        }
        val topic = msg.optString("topic", "")
        val event = msg.optString("event", "")
        if (topic == "phoenix") {
            if (event == "phx_reply" && heartbeatRef != null && msg.optString("ref", "") == heartbeatRef) {
                roundTripMs = SystemClock.elapsedRealtime() - heartbeatSentAt
                heartbeatRef = null
                missedHeartbeats = 0
            }
            return
        }
        if (!topic.startsWith(WIRE_PREFIX)) return
        val channel = channels[topic.substring(WIRE_PREFIX.length)] ?: return
        val payload = msg.optJSONObject("payload") ?: JSONObject()
        when (event) {
            "phx_reply" -> {
                // Only the answer to the join matters; other replies (leave, token) are ignored.
                if (msg.optString("ref", "") != channel.joinRef) return
                if (payload.optString("status", "") == "ok") {
                    channel.joined = true
                    channel.failures = 0
                    sendTrack(channel)
                    channel.listener.onJoined()
                } else {
                    channel.joined = false
                    channel.listener.onProblem(reasonOf(payload))
                    scheduleRejoin(channel)
                }
            }
            "broadcast" -> {
                val name = payload.optString("event", "")
                val body = payload.optJSONObject("payload")
                if (name.isNotEmpty() && body != null) {
                    try {
                        channel.listener.onBroadcast(name, body)
                    } catch (e: Exception) {
                        // A message the game cannot use must never take the socket down.
                    }
                }
            }
            "phx_error", "phx_close" -> {
                channel.joined = false
                forgetPresence(channel)
                scheduleRejoin(channel)
            }
            "presence_state" -> {
                if (channel.presenceKey == null) return
                channel.presence.clear()
                for ((key, metas) in readMetas(payload)) channel.presence[key] = metas.toMutableList()
                channel.presenceReady = true
                notifyPresence(channel)
            }
            "presence_diff" -> {
                if (channel.presenceKey == null) return
                // The standard Phoenix Presence merge: leaves remove the entries they name, joins add new ones.
                for ((key, metas) in readMetas(payload.optJSONObject("leaves"))) {
                    val list = channel.presence[key] ?: continue
                    val gone = metas.map { it.optString("phx_ref", "") }.toSet()
                    list.removeAll { it.optString("phx_ref", "") in gone }
                    if (list.isEmpty()) channel.presence.remove(key)
                }
                for ((key, metas) in readMetas(payload.optJSONObject("joins"))) {
                    val list = channel.presence.getOrPut(key) { ArrayList() }
                    val have = list.map { it.optString("phx_ref", "") }.toSet()
                    for (meta in metas) if (meta.optString("phx_ref", "") !in have) list.add(meta)
                }
                notifyPresence(channel)
            }
            "system" -> {
                if (payload.optString("status", "") == "error") {
                    channel.listener.onProblem(payload.optString("message", "error"))
                }
            }
            else -> Unit // anything unknown: ignored
        }
    }

    private fun reasonOf(payload: JSONObject): String {
        val response = payload.optJSONObject("response")
        val reason = response?.optString("reason", "") ?: ""
        return if (reason.isNotEmpty()) reason else payload.optString("message", "error")
    }

    // ---------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------

    private suspend fun fetchToken(): String? =
        try {
            tokenProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun nextRef(): String {
        refCounter++
        return refCounter.toString()
    }

    private fun frame(topic: String, event: String, payload: JSONObject, ref: String, joinRef: String?): String {
        val o = JSONObject()
            .put("topic", topic)
            .put("event", event)
            .put("payload", payload)
            .put("ref", ref)
        if (joinRef != null && joinRef.isNotEmpty()) o.put("join_ref", joinRef)
        return o.toString()
    }

    private companion object {
        const val WIRE_PREFIX = "realtime:"
        const val NORMAL_CLOSE = 1000
        const val HEARTBEAT_MS = 5_000L
        const val PROBE_MS = 5_000L
        const val MAX_MISSED_HEARTBEATS = 6
        const val TOKEN_CHECK_MS = 30_000L
        const val FIRST_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 10_000L
        const val REJOIN_DELAY_MS = 3_000L
        const val MAX_JOIN_FAILURES = 5
    }
}
