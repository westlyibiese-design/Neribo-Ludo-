package com.westly.ludo.connect

import android.net.Uri
import com.westly.ludo.game.HandTarget
import com.westly.ludo.game.LudoColor
import org.json.JSONArray
import org.json.JSONObject

/** Who this phone is in a Connect and Play room. A watcher is a guest without a seat (see ConnectSession.watching). */
enum class ConnectRole { HOST, GUEST }

/**
 * One person in the room. [deviceId] is only known to the host (it is never sent over the air).
 * [out] = knocked out of the tournament (still watching), [removed] = taken out of the game by the host.
 */
class RosterEntry(
    val seat: Int,
    val name: String,
    val deviceId: String,
    val connected: Boolean,
    val isHost: Boolean = false,
    val out: Boolean = false,
    val removed: Boolean = false
)

/** One seat's share of a new game: which engine player it plays and with which colours. */
class SeatAssignment(val seat: Int, val player: Int, val colors: List<String>)

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

    // Phase 4: leave and return.
    const val T_ENDED = "ended"
    const val T_REMOVED = "removed"

    /** The two roles a "hello" can ask for. */
    const val ROLE_PLAYER = "player"
    const val ROLE_WATCHER = "watcher"

    // Phase 2: the shared game.
    const val T_START = "start"
    const val T_STATE = "state"
    const val T_INTENT = "intent"
    const val T_RESYNC = "resync"

    // Phase 3: live view. "ev" messages are cosmetic: a lost one never changes the game.
    const val T_EV = "ev"
    const val EV_ROLL = "roll"
    const val EV_MOVE = "move"
    const val EV_CAPTURE = "capture"
    const val EV_TIE_ROLL = "tieRoll"
    const val EV_HAND = "hand"

    // The kinds of "intent" a guest may send to the host.
    const val K_ROLL = "roll"
    const val K_SELECT_DIE = "selectDie"
    const val K_BOARD_TAP = "boardTap"
    const val K_PICK_PIECE = "pickPiece"
    const val K_TIE_ROLL = "tieRoll"
    const val K_NEXT_ROUND = "nextRound"
    const val K_NEXT_GAME = "nextGame"

    fun endpointName(room: String, hostName: String): String =
        "$ENDPOINT_TAG|$room|${hostName.replace('|', ' ')}"

    fun endpointPrefix(room: String): String = "$ENDPOINT_TAG|$room|"

    private fun base(type: String): JSONObject =
        JSONObject().put("v", PROTOCOL_VERSION).put("t", type)

    fun hello(token: String, name: String, deviceId: String, role: String): JSONObject =
        base(T_HELLO).put("token", token).put("name", name).put("deviceId", deviceId).put("role", role)

    /** [seat] is -1 for a watcher. [started] says a game is running (the "start" and a "state" follow). */
    fun welcome(
        seat: Int,
        roomCode: String,
        hostName: String,
        playerCount: Int,
        roster: List<RosterEntry>,
        started: Boolean = false,
        watcher: Boolean = false,
        watchers: Int = 0,
        allowWatchers: Boolean = false
    ): JSONObject =
        base(T_WELCOME)
            .put("seat", seat)
            .put("roomCode", roomCode)
            .put("hostName", hostName)
            .put("playerCount", playerCount)
            .put("roster", rosterToJson(roster))
            .put("started", started)
            .put("watcher", watcher)
            .put("watchers", watchers)
            .put("allowWatchers", allowWatchers)

    fun lobby(
        playerCount: Int,
        started: Boolean,
        roster: List<RosterEntry>,
        watchers: Int = 0,
        allowWatchers: Boolean = false
    ): JSONObject =
        base(T_LOBBY)
            .put("playerCount", playerCount)
            .put("started", started)
            .put("roster", rosterToJson(roster))
            .put("watchers", watchers)
            .put("allowWatchers", allowWatchers)

    /** Host -> everybody: the host ended the session. [names] and [scores] are in seat order. */
    fun ended(winnerName: String, names: List<String>, scores: List<Int>): JSONObject {
        val arr = JSONArray()
        for (i in names.indices) {
            arr.put(JSONObject().put("name", names[i]).put("score", scores.getOrElse(i) { 0 }))
        }
        return base(T_ENDED).put("reason", "host_ended").put("winnerName", winnerName).put("scores", arr)
    }

    /** Host -> one guest: the host took this person out of the game. */
    fun removed(): JSONObject = base(T_REMOVED)

    /** Reads the "scores" list of an "ended" message: names and scores in seat order. Bad entries are skipped. */
    fun endedScoresFromJson(arr: JSONArray?): Pair<List<String>, List<Int>> {
        val names = ArrayList<String>()
        val scores = ArrayList<Int>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                names.add(o.optString("name", "Player"))
                scores.add(o.optInt("score", 0))
            }
        }
        return Pair(names, scores)
    }

    fun reject(reason: String): JSONObject = base(T_REJECT).put("reason", reason)

    fun bye(reason: String): JSONObject = base(T_BYE).put("reason", reason)

    /** Host -> everybody: a game begins (or the next game of the same room). The first "state" follows. */
    fun start(playerCount: Int, assignment: List<SeatAssignment>, gameNo: Int): JSONObject {
        val arr = JSONArray()
        for (a in assignment) {
            val colors = JSONArray()
            for (c in a.colors) colors.put(c)
            arr.put(JSONObject().put("seat", a.seat).put("player", a.player).put("colors", colors))
        }
        return base(T_START).put("playerCount", playerCount).put("assignment", arr).put("gameNo", gameNo)
    }

    /** Host -> everybody: the whole game as it is now. [seq] only ever goes up; [gameNo] says which game it belongs to. */
    fun state(seq: Int, gameNo: Int, game: JSONObject): JSONObject =
        base(T_STATE).put("seq", seq).put("gameNo", gameNo).put("game", game)

    /** Guest -> host: a tap, to be checked and carried out by the host. */
    fun intent(kind: String): JSONObject = base(T_INTENT).put("kind", kind)

    fun intentSelectDie(i: Int): JSONObject = intent(K_SELECT_DIE).put("i", i)

    fun intentBoardTap(row: Float, col: Float): JSONObject =
        intent(K_BOARD_TAP).put("row", row.toDouble()).put("col", col.toDouble())

    fun intentPickPiece(color: String, slot: Int): JSONObject =
        intent(K_PICK_PIECE).put("color", color).put("slot", slot)

    /** Guest -> host: please send the game state again. */
    fun resync(): JSONObject = base(T_RESYNC)

    // ----- Phase 3: live view events (host -> everybody) -----
    // Every event carries "seq": the number of the "state" that will follow once the action is done.
    // A phone that plays the event holds back that state (and later ones) until the animation ends.

    private fun ev(kind: String, seq: Int): JSONObject = base(T_EV).put("k", kind).put("seq", seq)

    /** The dice are about to roll for [player]; [a] and [b] are the faces they will land on. */
    fun evRoll(seq: Int, player: Int, a: Int, b: Int): JSONObject =
        ev(EV_ROLL, seq).put("player", player).put("a", a).put("b", b)

    /** A seed walks from progress [from] to [to] in [durationMs]. [rank] is where it rests if it reaches the centre. */
    fun evMove(seq: Int, color: String, slot: Int, from: Int, to: Int, durationMs: Long, rank: Int): JSONObject =
        ev(EV_MOVE, seq)
            .put("color", color).put("slot", slot)
            .put("from", from).put("to", to)
            .put("durationMs", durationMs).put("rank", rank)

    /** The seed [color]/[slot] is sent home by the seed [moverColor]/[moverSlot]. */
    fun evCapture(seq: Int, color: String, slot: Int, moverColor: String, moverSlot: Int): JSONObject =
        ev(EV_CAPTURE, seq)
            .put("color", color).put("slot", slot)
            .put("mColor", moverColor).put("mSlot", moverSlot)

    /** [player] rolls the tie-break die and gets [value]. */
    fun evTieRoll(seq: Int, player: Int, value: Int): JSONObject =
        ev(EV_TIE_ROLL, seq).put("player", player).put("value", value)

    /** [player] is about to tap [target]; the hand enters from the side of [color]. */
    fun evHand(seq: Int, player: Int, color: String, target: HandTarget): JSONObject {
        val t = JSONObject()
        when (target) {
            HandTarget.Dice -> t.put("kind", "dice")
            is HandTarget.Orb -> t.put("kind", "orb").put("option", target.index)
            is HandTarget.Spot -> t.put("kind", "spot").put("row", target.row.toDouble()).put("col", target.col.toDouble())
        }
        return ev(EV_HAND, seq).put("player", player).put("color", color).put("target", t)
    }

    /** Reads the "target" object of a hand event; null when it is damaged. */
    fun handTargetFromJson(o: JSONObject?): HandTarget? {
        if (o == null) return null
        return when (o.optString("kind", "")) {
            "dice" -> HandTarget.Dice
            "orb" -> {
                val i = o.optInt("option", -1)
                if (i in 0..2) HandTarget.Orb(i) else null
            }
            "spot" -> {
                val r = o.optDouble("row", Double.NaN)
                val c = o.optDouble("col", Double.NaN)
                if (r.isNaN() || c.isNaN() || r < -1.0 || r > 16.0 || c < -1.0 || c > 16.0) null
                else HandTarget.Spot(r.toFloat(), c.toFloat())
            }
            else -> null
        }
    }

    /** A colour name from the wire, or null when it is not one of the four. */
    fun colorFromName(name: String?): LudoColor? = LudoColor.values().firstOrNull { it.name == name }

    private fun rosterToJson(roster: List<RosterEntry>): JSONArray {
        val arr = JSONArray()
        for (e in roster) {
            arr.put(
                JSONObject()
                    .put("seat", e.seat)
                    .put("name", e.name)
                    .put("connected", e.connected)
                    .put("isHost", e.isHost)
                    .put("out", e.out)
                    .put("removed", e.removed)
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
                    isHost = o.optBoolean("isHost", false),
                    out = o.optBoolean("out", false),
                    removed = o.optBoolean("removed", false)
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
