package com.westly.ludo.connect

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Who this phone is in a Connect and Play room. Watchers arrive in a later phase. */
enum class ConnectRole { HOST, GUEST }

/** One person in the room. [deviceId] is only known to the host (it is never sent over the air). */
class RosterEntry(
    val seat: Int,
    val name: String,
    val deviceId: String,
    val connected: Boolean,
    val isHost: Boolean = false
)

/**
 * What the QR code carries: the public room letters (also advertised, so a guest can pick the right
 * host when several games are nearby) and the secret token (only ever in the QR code).
 */
class JoinTicket(val room: String, val token: String, val version: Int = ConnectProtocol.PROTOCOL_VERSION) {

    fun toText(): String = "ludomate://join?v=$version&room=$room&token=$token"

    companion object {
        /** Returns null when [text] is not a Ludo Mate join code. */
        fun parse(text: String?): JoinTicket? {
            if (text == null) return null
            return try {
                val uri = Uri.parse(text.trim())
                if (uri.scheme != "ludomate" || uri.host != "join") return null
                val version = uri.getQueryParameter("v")?.toIntOrNull() ?: return null
                val room = uri.getQueryParameter("room")?.uppercase() ?: return null
                val token = uri.getQueryParameter("token")?.lowercase() ?: return null
                if (room.length != ConnectProtocol.ROOM_LENGTH) return null
                if (room.any { it !in ConnectProtocol.ROOM_ALPHABET }) return null
                if (token.length != ConnectProtocol.TOKEN_LENGTH) return null
                if (token.any { it !in "0123456789abcdef" }) return null
                JoinTicket(room, token, version)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * The wire format: every message is a small JSON object with "v" (protocol version) and "t" (type).
 * Later phases may add fields but must never rename or remove the ones used here.
 */
object ConnectProtocol {
    const val SERVICE_ID = "com.westly.ludo.connect"
    const val PROTOCOL_VERSION = 1

    /** Most people who may watch a game (used from Phase 4; kept here so it is easy to change). */
    const val MAX_WATCHERS = 4

    const val ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val ROOM_LENGTH = 4
    const val TOKEN_LENGTH = 16

    /** The host's advertised name starts with this, then the room letters, then another bar and the host name. */
    const val ENDPOINT_TAG = "LM1"

    const val T_HELLO = "hello"
    const val T_WELCOME = "welcome"
    const val T_LOBBY = "lobby"
    const val T_REJECT = "reject"
    const val T_BYE = "bye"

    fun endpointName(room: String, hostName: String): String =
        "$ENDPOINT_TAG|$room|${hostName.replace('|', ' ')}"

    fun endpointPrefix(room: String): String = "$ENDPOINT_TAG|$room|"

    private fun base(type: String): JSONObject =
        JSONObject().put("v", PROTOCOL_VERSION).put("t", type)

    fun hello(token: String, name: String, deviceId: String, role: String): JSONObject =
        base(T_HELLO).put("token", token).put("name", name).put("deviceId", deviceId).put("role", role)

    fun welcome(seat: Int, roomCode: String, hostName: String, playerCount: Int, roster: List<RosterEntry>): JSONObject =
        base(T_WELCOME)
            .put("seat", seat)
            .put("roomCode", roomCode)
            .put("hostName", hostName)
            .put("playerCount", playerCount)
            .put("roster", rosterToJson(roster))

    fun lobby(playerCount: Int, started: Boolean, roster: List<RosterEntry>): JSONObject =
        base(T_LOBBY)
            .put("playerCount", playerCount)
            .put("started", started)
            .put("roster", rosterToJson(roster))

    fun reject(reason: String): JSONObject = base(T_REJECT).put("reason", reason)

    fun bye(reason: String): JSONObject = base(T_BYE).put("reason", reason)

    private fun rosterToJson(roster: List<RosterEntry>): JSONArray {
        val arr = JSONArray()
        for (e in roster) {
            arr.put(
                JSONObject()
                    .put("seat", e.seat)
                    .put("name", e.name)
                    .put("connected", e.connected)
                    .put("isHost", e.isHost)
            )
        }
        return arr
    }

    /** Reads the roster list of a welcome / lobby message. Bad entries are skipped. */
    fun rosterFromJson(arr: JSONArray?): List<RosterEntry> {
        val out = ArrayList<RosterEntry>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val seat = o.optInt("seat", -1)
            if (seat < 0) continue
            out.add(
                RosterEntry(
                    seat = seat,
                    name = o.optString("name", "Player"),
                    deviceId = "",
                    connected = o.optBoolean("connected", true),
                    isHost = o.optBoolean("isHost", false)
                )
            )
        }
        return out
    }

    /** Turns received bytes into a message, or null if they are not a valid Ludo Mate message. */
    fun decode(bytes: ByteArray): JSONObject? = try {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        if (o.optString("t", "").isEmpty()) null else o
    } catch (e: Exception) {
        null
    }

    fun encode(message: JSONObject): ByteArray = message.toString().toByteArray(Charsets.UTF_8)
}
