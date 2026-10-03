package com.westly.ludo.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.westly.ludo.ui.Palette
import com.westly.ludo.ui.PiecePick
import com.westly.ludo.ui.PieceView
import com.westly.ludo.ui.Swatch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

enum class Phase { AwaitRoll, Rolling, Choose, Moving, Pausing, CaptureChoose, GameOver, RoundBreak }

/** Which Tournament overlay (Tie-Break, Round Result, You're Out) is open, if any. */
object TOverlay {
    const val NONE = "none"
    const val TIEBREAK = "tiebreak"
    const val RESULT = "result"
    const val OUT = "out"
}

class Piece(val color: LudoColor, val slot: Int) {
    /** -1 = in house, 0..55 = on the route, 56 = reached the center, 57 = banked after a capture. */
    var progress by mutableIntStateOf(Route.IN_HOUSE)
    var finishRank by mutableIntStateOf(-1)

    /** A winning seed for this round: it reached the center, or it captured an opponent seed. */
    var won by mutableStateOf(false)
}

/**
 * All gameplay rules live here. The board only draws what this class says.
 * You & Computer: Player 1 owns yellow + red, Player 2 owns green + blue (as labelled on the yards).
 * Tournament: four independent players with one color (4 seeds) each, no teams.
 */
class LudoGame(
    val tournament: Boolean = false,
    val familyPlayers: Int = 0,
    /**
     * Connect and Play: the engine players (0 = red ... 3 = blue) that start a Tournament-style game, or
     * 0 and 1 for a 2-player game. Null for every other mode. A 3-player Connect game may start with
     * any 3 of the 4 colours.
     */
    val connectActive: List<Int>? = null
) {
    /**
     * Family: humans only on one phone, no computers. 2 players follow the You & Computer rules,
     * 3 or 4 players follow the Tournament rules. Player 1..4 = red, green, yellow, blue.
     */
    val family: Boolean get() = familyPlayers > 0

    /** Connect and Play: humans only, each on their own phone. */
    val connect: Boolean get() = connectActive != null

    /** No computers at all: Family (one shared phone) and Connect and Play (one phone each). */
    val humansOnly: Boolean get() = family || connect

    /** The players that are in the game from the very start (everybody in Tournament, fewer in Family / Connect). */
    private val startSet: List<Int> =
        connectActive?.distinct()?.sorted()
            ?: (0 until (if (familyPlayers > 0) familyPlayers else 4)).toList()

    /** The names typed for a Family game (index = player). Only used when [family] is true. */
    val familyNames = mutableStateListOf<String>().apply { for (i in 0 until familyPlayers) add("Player ${i + 1}") }

    fun familyName(i: Int): String = familyNames.getOrElse(i) { "" }

    /** Sets the Family names: at most 10 letters each, an empty name becomes "Player N". */
    fun setFamilyNames(list: List<String>) {
        for (i in 0 until familyNames.size) {
            val t = list.getOrElse(i) { "" }.trim().take(10)
            familyNames[i] = if (t.isEmpty()) "Player ${i + 1}" else t
        }
    }

    /** How many players start a Tournament-style game: 4, or 3 in a 3-player Family game. */
    private val startPlayers: Int get() = startSet.size

    /** True when the active player rolled a double six and goes again (no "pass the phone"). */
    var bonusRoll by mutableStateOf(false)

    val pieces: List<Piece> = LudoColor.values().flatMap { c -> (0..3).map { Piece(c, it) } }

    private val sounds by lazy { Sounds() }

    var phase by mutableStateOf(Phase.AwaitRoll)
    var activePlayer by mutableIntStateOf(0)
    var winner by mutableIntStateOf(-1)

    /** Match score of Player 1 (index 0) and Player 2 (index 1). It survives round resets. */
    val playerCount: Int = if (tournament) 4 else 2
    val scores = mutableStateListOf<Int>().apply { repeat(playerCount) { add(0) } }

    // ------------------------------------------------------------------
    // Tournament elimination state (only used when tournament = true).
    // Players 0..3 = red (the human), green, yellow, blue.
    // ------------------------------------------------------------------

    /** Which players are still in the tournament (index = player). */
    val active = mutableStateListOf(true, true, true, true).also { for (i in 0..3) if (i !in startSet) it[i] = false }

    /** 1 = four players, 2 = three players, 3 = the final (two players). */
    var round by mutableIntStateOf(1)

    /** Players knocked out so far, in the order they went out. */
    val eliminatedOrder = mutableStateListOf<Int>()

    /** True when the human was knocked out and chose to watch the computers finish. */
    var spectator by mutableStateOf(false)

    /** One of [TOverlay]: which overlay is open over the board. */
    var overlay by mutableStateOf(TOverlay.NONE)

    /** The player who ended the last round (got all 4 seeds out), or -1 (resign). */
    var resultEnder by mutableIntStateOf(-1)

    /** The player knocked out by the last round (or by resigning), or -1. */
    var resultOut by mutableIntStateOf(-1)

    /** Players who took part in the round that just ended. */
    var resultPlayers by mutableStateOf<List<Int>>(emptyList())

    /** Seeds out per player when the round ended (-1 = not in that round). */
    var resultSeeds by mutableStateOf<List<Int>>(listOf(0, 0, 0, 0))

    /** True when the human is out because of Resign (no Round Result was shown). */
    var resultResigned by mutableStateOf(false)

    /** Who gets the point if the human chooses Leave on the You're Out screen. */
    var leaveTo by mutableIntStateOf(-1)

    /** Players in the current dice roll-off, and their rolls so far (0 = not rolled yet). */
    var tieIds by mutableStateOf<List<Int>>(emptyList())
    val tieRolls = mutableStateListOf(0, 0, 0, 0)

    /** Display only: who is rolling right now (-1 = nobody) and the face shown while rolling. */
    var tieRolling by mutableIntStateOf(-1)
    var tieFlicker by mutableIntStateOf(1)

    /** Spectator fast-forward: computers play about 4 times faster, without sound. */
    var fast by mutableStateOf(false)
        private set

    /** True while the round banner is showing (computers and taps wait). Not saved. */
    var bannerPending by mutableStateOf(tournament)

    /**
     * Goes up every time a round banner should be shown. Connect and Play guests watch it, so they
     * can show the same "Round N" banner for the same time as the host. Not saved.
     */
    var bannerSeq by mutableIntStateOf(if (tournament) 1 else 0)
        private set

    /** Shows the round banner (and tells the guests to show it too). */
    private fun showBanner() {
        bannerPending = true
        bannerSeq++
    }

    // The two dice values (0 = not rolled yet) and whether each has been used this turn.
    var die1 by mutableIntStateOf(0)
    var die2 by mutableIntStateOf(0)
    var used1 by mutableStateOf(false)
    var used2 by mutableStateOf(false)
    /** -1 = nothing chosen yet, 0 = first die (blue), 1 = second die (green), 2 = total (red). */
    var selectedDie by mutableIntStateOf(-1)

    /**
     * DISPLAY ONLY. The option (0/1/2) the computer has internally decided on and its hand has
     * reached, so the board can show the same highlight/glow. No rule or move ever reads this:
     * the computer plays through [executeMove], never through [selectDie] / [selectedDie].
     */
    var computerOption by mutableIntStateOf(-1)
        private set

    /** Set when the player taps a spot holding several movable pieces: they must pick one. */
    var pick by mutableStateOf<PiecePick?>(null)

    // Faces shown on the two real dice.
    var face1 by mutableIntStateOf(3)
    var face2 by mutableIntStateOf(4)

    private var movingPiece by mutableStateOf<Piece?>(null)
    private var moveProgress by mutableFloatStateOf(0f)

    /** DISPLAY ONLY: the rest spot rank used while the moving seed is drawn (it only matters on the last hop into the centre). */
    private var moveRank by mutableIntStateOf(-1)

    /** DISPLAY ONLY (Connect observers): a seed that is not drawn for a moment, such as the capturing seed that is banked. */
    private var hiddenPiece by mutableStateOf<Piece?>(null)

    /**
     * Connect and Play, host only: told at the moment an animation starts, so the host can tell the other
     * phones to play the same one. Null in every other mode, where nothing changes.
     */
    var visualSink: ((VisualEvent) -> Unit)? = null
    private var movedThisRoll = false
    private var moveTo = 0

    // Capture handling: the seed waiting for the player to choose which opponent to capture,
    // and the captured seed that is currently travelling back to its house.
    private var pendingMover by mutableStateOf<Piece?>(null)
    private var returning by mutableStateOf<Piece?>(null)
    private var retRow by mutableFloatStateOf(0f)
    private var retCol by mutableFloatStateOf(0f)
    private var retT by mutableFloatStateOf(0f)

    private fun colorsOf(player: Int): List<LudoColor> =
        if (tournament) listOf(TOURNAMENT_COLORS[player])
        else if (player == 0) listOf(LudoColor.YELLOW, LudoColor.RED)
        else listOf(LudoColor.GREEN, LudoColor.BLUE)

    private fun ownPieces(): List<Piece> {
        val mine = colorsOf(activePlayer)
        return pieces.filter { it.color in mine }
    }

    /** The legal-move rule: 6 to leave the house, never past the center. */
    private fun canMove(piece: Piece, d: Int): Boolean {
        if (d < 1) return false
        val p = piece.progress
        return when {
            p == Route.IN_HOUSE -> d == 6
            p >= Route.CENTER -> false
            else -> p + d <= Route.CENTER
        }
    }

    /** Number of steps behind option i: 0 = first die, 1 = second die, 2 = both dice together. */
    private fun optionValue(i: Int): Int = when (i) {
        0 -> die1
        1 -> die2
        else -> die1 + die2
    }

    /** True while the dice behind option i have not been spent. */
    private fun optionFresh(i: Int): Boolean = when (i) {
        0 -> die1 > 0 && !used1
        1 -> die2 > 0 && !used2
        else -> die1 > 0 && die2 > 0 && !used1 && !used2
    }

    /** Opponent seeds standing on a board cell. There are no safe cells. */
    private fun opponentsAtCell(r: Int, c: Int): List<Piece> {
        val mine = colorsOf(activePlayer)
        return pieces.filter { q ->
            q.color !in mine && q.progress in 0 until Route.CENTER && Route.cell(q.color, q.progress) == (r to c)
        }
    }

    /** Opponent seeds on the cell where [mover] is standing now. */
    private fun opponentsAt(mover: Piece): List<Piece> {
        if (mover.progress !in 0 until Route.CENTER) return emptyList()
        val (r, c) = Route.cell(mover.color, mover.progress)
        return opponentsAtCell(r, c)
    }

    private fun landingOf(piece: Piece, d: Int): Int =
        if (piece.progress == Route.IN_HOUSE) 0 else piece.progress + d

    /**
     * The 5 + 5 rule. A single die would stop on an opponent seed while the other die is still
     * unused: the stop only counts as a capture if a DIFFERENT own seed can still use the other die.
     * If none can, the seed may not stop there; it must carry on with the other die as well.
     */
    private fun forcedContinuation(piece: Piece, i: Int): Boolean {
        if (i !in 0..1) return false
        val other = 1 - i
        if (!optionFresh(other)) return false
        val land = landingOf(piece, optionValue(i))
        if (land >= Route.CENTER) return false
        val (r, c) = Route.cell(piece.color, land)
        if (opponentsAtCell(r, c).isEmpty()) return false
        val d2 = optionValue(other)
        return ownPieces().none { it !== piece && canMove(it, d2) }
    }

    /** Can this piece make the full move of option i? The total never brings a piece out of the house. */
    private fun canUse(piece: Piece, i: Int): Boolean {
        if (!optionFresh(i)) return false
        val d = optionValue(i)
        return when {
            i == 2 -> piece.progress >= 0 && piece.progress < Route.CENTER && piece.progress + d <= Route.CENTER
            !canMove(piece, d) -> false
            // Must carry on with the other die, so the whole combined move has to fit.
            forcedContinuation(piece, i) -> landingOf(piece, d) + optionValue(1 - i) <= Route.CENTER
            else -> true
        }
    }

    /** True when option i can be tapped: not spent, and at least one piece can really make it. */
    fun optionUsable(i: Int): Boolean =
        phase == Phase.Choose && optionFresh(i) && ownPieces().any { canUse(it, i) }

    private fun usableOptions(): List<Int> =
        (0..2).filter { i -> optionFresh(i) && ownPieces().any { canUse(it, i) } }

    /** True for pieces that may move with the currently selected option. Nothing glows before a choice. */
    private fun isGlowing(piece: Piece): Boolean {
        if (phase != Phase.Choose) return false
        val i = if (isComputerTurn) computerOption else selectedDie
        if (i !in 0..2) return false
        if (piece.color !in colorsOf(activePlayer)) return false
        return canUse(piece, i)
    }


    // ------------------------------------------------------------------
    // Tournament computer players and hand guidance.
    // Everything below only READS the shared rules above (canUse, forcedContinuation, ...)
    // and then plays through the very same selectDie / choosePiece / onPiecePicked
    // functions the human uses, so the computer can never do anything a human could not.
    // ------------------------------------------------------------------

    /**
     * Player 1 is always the human. You & Computer: Player 2 is the computer.
     * Tournament: Players 2, 3 and 4 are three independent computers.
     */
    private val computerSeats: Set<Int> = if (humansOnly) emptySet() else if (tournament) setOf(1, 2, 3) else setOf(1)

    /** Set while a resign / restart waits for the board to settle, so no computer starts a new action. */
    private var halting = false

    val isComputerTurn: Boolean
        get() = activePlayer in computerSeats && phase != Phase.GameOver && phase != Phase.RoundBreak && !halting

    /** The color a player controls in Tournament (index 0..3). */
    fun tournamentColor(player: Int): LudoColor = TOURNAMENT_COLORS[player]

    /** Set while the game screen is not visible, so computers stop at a clean moment. */
    var paused by mutableStateOf(false)

    /** What the computer has decided to do right now (drives the hand, never separate from it). */
    var plannedMove by mutableStateOf<Move?>(null)
        private set
    var plannedVictim by mutableStateOf<Piece?>(null)
        private set

    /** Computer pace (1 = normal, bigger = slower) and level (0 = easy, 1 = normal). Set from Configuration. */
    var computerSpeed: Float = 1f
    var computerLevel: Int = 0

    private var computerBusy = false
    private val stopComputer: Boolean get() = paused || halting

    /** Waiting-time multiplier for the computer: normal speed setting, 4 times shorter in fast-forward. */
    private val fastMul: Float get() = if (fast) 0.25f else 1f
    private val pace: Float get() = computerSpeed * fastMul
    private val computers: List<ComputerPlayer> =
        if (humansOnly) emptyList()
        else if (tournament) (1..3).map { ComputerPlayer(it, listOf(TOURNAMENT_COLORS[it])) }
        else listOf(ComputerPlayer(1, listOf(LudoColor.GREEN, LudoColor.BLUE)))

    /** Every (movement option, seed) pair that is legal right now for the active player. */
    fun legalMoves(): List<Move> {
        if (phase != Phase.Choose) return emptyList()
        val out = ArrayList<Move>()
        for (i in 0..2) {
            if (!optionFresh(i)) continue
            for (p in ownPieces()) if (canUse(p, i)) out.add(Move(i, p))
        }
        return out
    }

    /** Where the seed really ends up (progress) if this move is made, including the 5 + 5 rule. */
    fun targetOf(m: Move): Int {
        val i = m.option
        val forced = i != 2 && forcedContinuation(m.piece, i)
        val extra = if (forced) optionValue(1 - i) else 0
        val from = m.piece.progress
        return if (from == Route.IN_HOUSE) extra else from + optionValue(i) + extra
    }

    /** Opponent seeds that would be standing on the final cell of this move. */
    fun victimsAfter(m: Move): List<Piece> {
        val t = targetOf(m)
        if (t !in 0 until Route.CENTER) return emptyList()
        val (r, c) = Route.cell(m.piece.color, t)
        return opponentsAtCell(r, c)
    }

    private fun spotOf(piece: Piece): HandTarget.Spot {
        val (r, c) = Route.anchor(piece.color, piece.slot, piece.finishRank, piece.progress)
        return HandTarget.Spot(r, c)
    }

    // ------------------------------------------------------------------
    // Connect and Play, live view: what the host needs to tell the other phones where a hand goes.
    // These only READ the game; they never change it.
    // ------------------------------------------------------------------

    /** The colour whose side the hand of [player] enters from for the dice and the movement circles. */
    fun firstColorOf(player: Int): LudoColor = colorsOf(player.coerceIn(0, 3)).first()

    /** The board spot of [piece] as the hand points at it. */
    fun handSpotOf(piece: Piece): HandTarget.Spot = spotOf(piece)

    /** The seed that is waiting for a capture choice (its colour is the side the hand comes from), or null. */
    fun captureMoverColor(): LudoColor? = if (phase == Phase.CaptureChoose) pendingMover?.color else null

    /** The seed with this colour name and slot, or null. */
    fun pieceByName(color: String, slot: Int): Piece? =
        pieces.firstOrNull { it.color.name == color && it.slot == slot }

    /**
     * The seed a tap at ([row], [col]) would choose right now, judged exactly as [onBoardTap] judges it
     * (a movement circle must be selected and the seed must glow). Null when the tap would choose nothing.
     */
    fun boardTapPiece(row: Float, col: Float): Piece? {
        if (phase != Phase.Choose || selectedDie !in 0..2) return null
        return hitView(row, col)?.tag as? Piece
    }

    /** Where the hand goes when [piece] is picked in the pop-up (own seed to move, or opponent seed to capture); null if not allowed now. */
    fun pickSpotOf(piece: Piece): HandTarget.Spot? = when (phase) {
        Phase.CaptureChoose -> if (pick?.items?.any { it.tag === piece } == true) spotOf(piece) else null
        Phase.Choose -> if (isGlowing(piece)) spotOf(piece) else null
        else -> null
    }

    /** The glowing seed nearest to a tap, if one is close enough. */
    private fun hitView(row: Float, col: Float): PieceView? {
        val views = pieceViews().filter { it.glow }
        var best: PieceView? = null
        var bestDist = 1.15f
        for (v in views) {
            val dr = v.row - row
            val dc = v.col - col
            val dist = sqrt(dr * dr + dc * dc)
            if (dist < bestDist) {
                bestDist = dist
                best = v
            }
        }
        return best
    }

    // ------------------------------------------------------------------
    // Connect and Play, live view: the visual routines.
    // The host runs them inside the real rules (same timing, same sounds as always). An observer runs the
    // same routines from the host's events. They change DISPLAY fields only (dice faces, the moving or
    // returning seed, the tie-break flicker): never scores, turns or where seeds really stand.
    // The next snapshot from the host puts everything right, see [clearVisuals].
    // ------------------------------------------------------------------

    /** Observer: forget every display-only leftover. Called right after a snapshot has been applied. */
    fun clearVisuals() {
        movingPiece = null
        returning = null
        hiddenPiece = null
    }

    /** Observer: plays a dice roll that lands on [a] and [b]. */
    suspend fun observeRoll(a: Int, b: Int) {
        playRollVisual(a, b)
    }

    /** Observer: walks [piece] from [from] to [to] and leaves it standing there until the snapshot arrives. */
    suspend fun observeMove(piece: Piece, from: Int, to: Int, durationMs: Long, rank: Int) {
        returning = null
        hiddenPiece = null
        playMoveVisual(piece, from, to, durationMs.toFloat(), rank)
    }

    /** Observer: the capturing seed disappears and [victim] travels home; it rests there until the snapshot arrives. */
    suspend fun observeCapture(victim: Piece, mover: Piece?) {
        movingPiece = null
        hiddenPiece = mover
        val (vr, vc) = Route.anchor(victim.color, victim.slot, victim.finishRank, victim.progress)
        playCaptureVisual(victim, vr, vc, false)
    }

    /** Observer: plays the tie-break dice for [player] and leaves the result showing until the snapshot arrives. */
    suspend fun observeTieRoll(player: Int, value: Int) {
        tieRolling = player
        playTieVisual(value)
    }

    /** The round-win jingle, for observers (the host plays it itself while it runs the rules). */
    fun playJingle() {
        sounds.win()
    }

    /** The yard color whose side the computer's hand comes from right now. */
    fun handColor(): LudoColor {
        plannedMove?.let { return it.piece.color }
        if (phase == Phase.CaptureChoose) pendingMover?.let { return it.color }
        return colorsOf(activePlayer).first()
    }

    /**
     * Where the computer's hand should be right now, or null. The hand belongs to the computer
     * only: on the human's turn there is never a hand. It only follows the computer's real,
     * already-decided plan.
     */
    fun handTarget(): HandTarget? {
        if (!isComputerTurn) return null
        return when (phase) {
            Phase.AwaitRoll -> HandTarget.Dice
            Phase.Choose -> {
                val m = plannedMove ?: return null
                if (computerOption == -1) HandTarget.Orb(m.option) else spotOf(m.piece)
            }
            Phase.CaptureChoose -> plannedVictim?.let { spotOf(it) }
            else -> null
        }
    }

    /**
     * Plays the computer's turn(s) step by step with short pauses, until it is the human's turn
     * (or the screen is paused). Safe to call again at any time: only one run is active.
     */
    suspend fun computerStep() {
        if (computerBusy) return
        computerBusy = true
        try {
            while (isComputerTurn && !stopComputer) {
                val ai = computers.first { it.playerIndex == activePlayer }
                when (phase) {
                    Phase.AwaitRoll -> {
                        delay((1000 * pace).toLong())                       // hand reaches the dice
                        if (stopComputer) return
                        roll()
                    }
                    Phase.Choose -> {
                        val move = ai.chooseMove(this) ?: return
                        plannedMove = move
                        delay((800 * pace).toLong())                        // hand points at the movement circle
                        if (stopComputer) { plannedMove = null; return }
                        computerOption = move.option      // display only: hand moves on to the seed
                        delay((700 * pace).toLong())                        // hand moves to the seed
                        if (stopComputer) { plannedMove = null; return }
                        // The computer's own internal move: no human dice control is used.
                        executeMove(move.piece, move.option)
                        plannedMove = null
                        computerOption = -1
                    }
                    Phase.CaptureChoose -> {
                        val victim = ai.chooseVictim(this) ?: return
                        plannedVictim = victim
                        delay((900 * pace).toLong())
                        if (stopComputer) { plannedVictim = null; return }
                        resolveCapture(victim)            // internal: not the human pick handler
                        plannedVictim = null
                    }
                    else -> return
                }
            }
        } finally {
            computerOption = -1
            computerBusy = false
        }
    }

    /** Everything needed to continue the game later, as text. */
    fun toSaveString(): String {
        val o = JSONObject()
        val arr = JSONArray()
        val mp = movingPiece
        for (p in pieces) {
            // A piece caught mid-move is saved at the cell it was moving to.
            val prog = if (phase == Phase.Moving && p === mp) moveTo else p.progress
            arr.put(JSONArray().put(prog).put(p.finishRank).put(if (p.won) 1 else 0))
        }
        o.put("pieces", arr)
        o.put(
            "phase",
            when (phase) {
                Phase.AwaitRoll, Phase.Rolling -> "AwaitRoll"
                Phase.Choose -> "Choose"
                Phase.GameOver -> "GameOver"
                Phase.RoundBreak -> "RoundBreak"
                Phase.CaptureChoose -> "Capture"
                Phase.Moving, Phase.Pausing -> "Resolve"
            }
        )
        // The seed whose landing has not been resolved yet (capture still to apply or to choose).
        val pm = when (phase) {
            Phase.CaptureChoose -> pendingMover
            Phase.Moving -> mp
            else -> null
        }
        o.put("pendingMover", if (pm == null) -1 else pieces.indexOf(pm))
        o.put("scores", JSONArray().also { a -> scores.forEach { a.put(it) } })
        o.put("activePlayer", activePlayer)
        o.put("winner", winner)
        o.put("die1", die1)
        o.put("die2", die2)
        o.put("used1", used1)
        o.put("used2", used2)
        o.put("selectedDie", selectedDie)
        o.put("face1", face1)
        o.put("face2", face2)
        o.put("movedThisRoll", movedThisRoll)
        if (family) {
            o.put("fPlayers", familyPlayers)
            o.put("fNames", JSONArray().also { a -> familyNames.forEach { a.put(it) } })
        }
        if (tournament) {
            o.put("tActive", JSONArray().also { a -> active.forEach { a.put(it) } })
            o.put("tRound", round)
            o.put("tElim", JSONArray().also { a -> eliminatedOrder.forEach { a.put(it) } })
            o.put("tSpectator", spectator)
            o.put("tOverlay", overlay)
            o.put("tEnder", resultEnder)
            o.put("tOut", resultOut)
            o.put("tPlayers", JSONArray().also { a -> resultPlayers.forEach { a.put(it) } })
            o.put("tSeeds", JSONArray().also { a -> resultSeeds.forEach { a.put(it) } })
            o.put("tResigned", resultResigned)
            o.put("tLeaveTo", leaveTo)
            o.put("tTieIds", JSONArray().also { a -> tieIds.forEach { a.put(it) } })
            o.put("tTieRolls", JSONArray().also { a -> tieRolls.forEach { a.put(it) } })
        }
        return o.toString()
    }

    /** Loads a saved game. Returns false (and starts fresh) if the save is unreadable. */
    fun restore(json: String): Boolean {
        return try {
            val o = JSONObject(json)
            val arr = o.getJSONArray("pieces")
            if (arr.length() != pieces.size) {
                newGame()
                return false
            }
            for ((i, p) in pieces.withIndex()) {
                val a = arr.getJSONArray(i)
                p.progress = a.getInt(0)
                p.finishRank = a.getInt(1)
                p.won = (a.length() > 2 && a.getInt(2) == 1) || p.progress >= Route.CENTER
                // Older saves left a capturing seed standing on the route: bank it now.
                if (p.won && p.progress in 0 until Route.CENTER) p.progress = Route.BANKED
            }
            val sc = o.optJSONArray("scores")
            if (sc != null && sc.length() == playerCount) {
                for (i in 0 until playerCount) scores[i] = sc.getInt(i)
            }
            if (family) {
                o.optJSONArray("fNames")?.let { a ->
                    for (i in 0 until minOf(a.length(), familyNames.size)) familyNames[i] = a.getString(i)
                }
            }
            movingPiece = null
            pendingMover = null
            returning = null
            pick = null
            bonusRoll = false
            if (tournament) restoreTournament(o)
            activePlayer = o.getInt("activePlayer")
            if (tournament && activePlayer !in 0..3) activePlayer = firstActive()
            if (tournament && !active[activePlayer]) activePlayer = firstActive()
            winner = o.getInt("winner")
            die1 = o.getInt("die1")
            die2 = o.getInt("die2")
            used1 = o.getBoolean("used1")
            used2 = o.getBoolean("used2")
            selectedDie = o.getInt("selectedDie")
            face1 = o.getInt("face1")
            face2 = o.getInt("face2")
            movedThisRoll = o.getBoolean("movedThisRoll")
            val pm = o.optInt("pendingMover", -1)
            when (o.getString("phase")) {
                "GameOver" -> phase = Phase.GameOver
                "AwaitRoll" -> phase = Phase.AwaitRoll
                "RoundBreak" -> {
                    phase = Phase.RoundBreak
                    // A break with no overlay cannot happen normally: just start the round again.
                    if (!tournament || overlay == TOverlay.NONE) resetRound()
                }
                else -> restorePending(if (pm >= 0) pieces.getOrNull(pm) else null)
            }
            bannerPending = false
            true
        } catch (e: Exception) {
            newGame()
            false
        }
    }

    // ------------------------------------------------------------------
    // Connect and Play: the live mirror.
    // The host runs the real rules. Every guest keeps its own LudoGame only as a DISPLAY MODEL and
    // fills it from the host's snapshot. applyMirror never runs a rule: no scoring, no capture, no
    // turn change, no sound, no waiting. It only copies values (restore() must not be used for this,
    // because it applies pending captures, completes rounds and moves the turn on).
    // ------------------------------------------------------------------

    private fun pieceRef(p: Piece?): JSONObject? =
        if (p == null) null else JSONObject().put("color", p.color.name).put("slot", p.slot)

    private fun pieceFromRef(o: JSONObject?): Piece? {
        if (o == null) return null
        val color = o.optString("color", "")
        val slot = o.optInt("slot", -1)
        return pieces.firstOrNull { it.color.name == color && it.slot == slot }
    }

    private fun intList(a: JSONArray?): List<Int> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getInt(it) }

    /** Everything a screen needs to draw this game and to decide what to show. Changes nothing. */
    fun toMirrorJson(): JSONObject {
        val o = JSONObject()
        val arr = JSONArray()
        for (p in pieces) arr.put(JSONArray().put(p.progress).put(p.finishRank).put(if (p.won) 1 else 0))
        o.put("pieces", arr)
        o.put("phase", phase.name)
        o.put("activePlayer", activePlayer)
        o.put("winner", winner)
        o.put("scores", JSONArray().also { a -> scores.forEach { a.put(it) } })
        o.put("die1", die1)
        o.put("die2", die2)
        o.put("used1", used1)
        o.put("used2", used2)
        o.put("selectedDie", selectedDie)
        o.put("face1", face1)
        o.put("face2", face2)
        o.put("bonusRoll", bonusRoll)
        val pk = pick
        if (pk == null) {
            o.put("pick", JSONObject.NULL)
        } else {
            val items = JSONArray()
            for (v in pk.items) {
                val ref = pieceRef(v.tag as? Piece)
                if (ref != null) items.put(ref)
            }
            o.put(
                "pick",
                JSONObject()
                    .put("row", pk.row.toDouble())
                    .put("col", pk.col.toDouble())
                    .put("capture", pk.capture)
                    .put("items", items)
            )
        }
        val pm = pieceRef(pendingMover)
        o.put("pendingMover", pm ?: JSONObject.NULL)
        if (tournament) {
            o.put("active", JSONArray().also { a -> active.forEach { a.put(if (it) 1 else 0) } })
            o.put("round", round)
            o.put("eliminatedOrder", JSONArray().also { a -> eliminatedOrder.forEach { a.put(it) } })
            o.put("overlay", overlay)
            o.put("resultEnder", resultEnder)
            o.put("resultOut", resultOut)
            o.put("resultPlayers", JSONArray().also { a -> resultPlayers.forEach { a.put(it) } })
            o.put("resultSeeds", JSONArray().also { a -> resultSeeds.forEach { a.put(it) } })
            o.put("resultResigned", resultResigned)
            o.put("leaveTo", leaveTo)
            o.put("tieIds", JSONArray().also { a -> tieIds.forEach { a.put(it) } })
            o.put("tieRolls", JSONArray().also { a -> tieRolls.forEach { a.put(it) } })
            o.put("tieRolling", tieRolling)
            o.put("tieFlicker", tieFlicker)
            o.put("bannerSeq", bannerSeq)
        }
        return o
    }

    /**
     * Copies a host snapshot into this display model and does nothing else. Everything is read first
     * and only then written, so a damaged message throws before anything changes.
     */
    fun applyMirror(o: JSONObject) {
        val pa = o.getJSONArray("pieces")
        require(pa.length() == pieces.size)
        val prog = IntArray(pieces.size)
        val rank = IntArray(pieces.size)
        val wonFlags = BooleanArray(pieces.size)
        for (i in pieces.indices) {
            val a = pa.getJSONArray(i)
            prog[i] = a.getInt(0)
            rank[i] = a.getInt(1)
            wonFlags[i] = a.getInt(2) == 1
        }
        val newPhase = Phase.valueOf(o.getString("phase"))
        val newActive = o.getInt("activePlayer")
        require(newActive in 0..3)
        val newWinner = o.getInt("winner")
        val sc = intList(o.getJSONArray("scores"))
        require(sc.size == scores.size)
        val d1 = o.getInt("die1")
        val d2 = o.getInt("die2")
        val u1 = o.getBoolean("used1")
        val u2 = o.getBoolean("used2")
        val sel = o.getInt("selectedDie")
        val f1 = o.getInt("face1")
        val f2 = o.getInt("face2")
        val bonus = o.getBoolean("bonusRoll")

        var newPick: PiecePick? = null
        val po = o.optJSONObject("pick")
        if (po != null) {
            val items = ArrayList<PieceView>()
            val ia = po.optJSONArray("items")
            val row = po.getDouble("row").toFloat()
            val col = po.getDouble("col").toFloat()
            if (ia != null) {
                for (i in 0 until ia.length()) {
                    val piece = pieceFromRef(ia.optJSONObject(i)) ?: continue
                    items.add(PieceView(swatchOf(piece.color), row, col, BOARD_R, false, 0f, piece))
                }
            }
            if (items.isNotEmpty()) newPick = PiecePick(row, col, items, po.optBoolean("capture", false))
        }
        val newPending = pieceFromRef(o.optJSONObject("pendingMover"))

        var tActive: List<Int> = emptyList()
        var tRound = 1
        var tElim: List<Int> = emptyList()
        var tOverlay = TOverlay.NONE
        var tEnder = -1
        var tOut = -1
        var tPlayers: List<Int> = emptyList()
        var tSeeds: List<Int> = listOf(0, 0, 0, 0)
        var tResigned = false
        var tLeave = -1
        var tTieIds: List<Int> = emptyList()
        var tTieRolls: List<Int> = listOf(0, 0, 0, 0)
        var tRolling = -1
        var tFlicker = 1
        var tBanner = 0
        if (tournament) {
            tActive = intList(o.getJSONArray("active"))
            require(tActive.size == 4)
            tRound = o.getInt("round").coerceIn(1, 3)
            tElim = intList(o.getJSONArray("eliminatedOrder"))
            val ov = o.getString("overlay")
            tOverlay = if (ov == TOverlay.TIEBREAK || ov == TOverlay.RESULT || ov == TOverlay.OUT) ov else TOverlay.NONE
            tEnder = o.getInt("resultEnder")
            tOut = o.getInt("resultOut")
            tPlayers = intList(o.getJSONArray("resultPlayers"))
            tSeeds = intList(o.getJSONArray("resultSeeds"))
            require(tSeeds.size == 4)
            tResigned = o.getBoolean("resultResigned")
            tLeave = o.getInt("leaveTo")
            tTieIds = intList(o.getJSONArray("tieIds"))
            tTieRolls = intList(o.getJSONArray("tieRolls"))
            require(tTieRolls.size == 4)
            tRolling = o.getInt("tieRolling")
            tFlicker = o.getInt("tieFlicker")
            tBanner = o.getInt("bannerSeq")
        }

        // Everything parsed: now only plain value copies.
        for (i in pieces.indices) {
            pieces[i].progress = prog[i]
            pieces[i].finishRank = rank[i]
            pieces[i].won = wonFlags[i]
        }
        phase = newPhase
        activePlayer = newActive
        winner = newWinner
        for (i in sc.indices) scores[i] = sc[i]
        die1 = d1
        die2 = d2
        used1 = u1
        used2 = u2
        selectedDie = sel
        face1 = f1
        face2 = f2
        bonusRoll = bonus
        pick = newPick
        pendingMover = newPending
        if (tournament) {
            for (i in 0 until 4) active[i] = tActive[i] != 0
            round = tRound
            eliminatedOrder.clear()
            eliminatedOrder.addAll(tElim)
            overlay = tOverlay
            resultEnder = tEnder
            resultOut = tOut
            resultPlayers = tPlayers
            resultSeeds = tSeeds
            resultResigned = tResigned
            leaveTo = tLeave
            tieIds = tTieIds
            for (i in 0 until 4) tieRolls[i] = tTieRolls[i]
            tieRolling = tRolling
            tieFlicker = tFlicker
            bannerSeq = tBanner
        }
    }

    /** Loads the Tournament elimination fields. An older save without them is a normal 4-player Round 1. */
    private fun restoreTournament(o: JSONObject) {
        val ac = o.optJSONArray("tActive")
        for (i in 0 until 4) active[i] = if (ac != null && ac.length() == 4) ac.optBoolean(i, true) else true
        if (activeCount() < 2) for (i in 0 until 4) active[i] = i in startSet
        if (family || connect) for (i in 0 until 4) if (i !in startSet) active[i] = false
        round = o.optInt("tRound", 1).coerceIn(1, 3)
        eliminatedOrder.clear()
        o.optJSONArray("tElim")?.let { a -> for (i in 0 until a.length()) eliminatedOrder.add(a.getInt(i)) }
        spectator = o.optBoolean("tSpectator", false) && !active[0]
        resultEnder = o.optInt("tEnder", -1)
        resultOut = o.optInt("tOut", -1)
        resultPlayers = o.optJSONArray("tPlayers")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
        resultSeeds = o.optJSONArray("tSeeds")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
            ?.takeIf { it.size == 4 } ?: listOf(0, 0, 0, 0)
        resultResigned = o.optBoolean("tResigned", false)
        leaveTo = o.optInt("tLeaveTo", -1)
        tieIds = o.optJSONArray("tTieIds")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
        val tr = o.optJSONArray("tTieRolls")
        for (i in 0 until 4) tieRolls[i] = tr?.optInt(i, 0) ?: 0
        tieRolling = -1
        val ov = o.optString("tOverlay", TOverlay.NONE)
        overlay = if (ov == TOverlay.TIEBREAK || ov == TOverlay.RESULT || ov == TOverlay.OUT) ov else TOverlay.NONE
        if (overlay == TOverlay.TIEBREAK && tieIds.size < 2) overlay = TOverlay.NONE
        stopFast()
    }

    /** A restored game may still owe a capture on the cell where a seed had just landed. */
    private fun restorePending(mover: Piece?) {
        if (mover != null) {
            val victims = opponentsAt(mover)
            if (victims.size == 1) {
                applyCapture(mover, victims[0])
            } else if (victims.size > 1) {
                openCaptureChoice(mover, victims)
                return
            }
        }
        resumeTurn()
    }

    /** Works out where a restored mid-turn game should continue, without any waiting. */
    private fun resumeTurn() {
        if (ownPieces().all { it.won }) {
            completeRound()
            return
        }
        val usable = usableOptions()
        if (usable.isNotEmpty()) {
            if (selectedDie !in usable) selectedDie = -1
            phase = Phase.Choose
            return
        }
        val extraRoll = die1 == 6 && die2 == 6
        bonusRoll = extraRoll
        die1 = 0
        die2 = 0
        used1 = false
        used2 = false
        selectedDie = -1
        if (!extraRoll) activePlayer = nextPlayer(activePlayer)
        phase = Phase.AwaitRoll
    }

    /** Starts a whole new match: scores go back to 0. */
    fun newGame() {
        for (i in 0 until playerCount) scores[i] = 0
        if (tournament) {
            resetTournamentState()
            showBanner()
        }
        resetRound()
    }

    /** All winning seeds of the active player are home or captured: that player scores 1 and the winner page shows. */
    private fun completeRound() {
        if (tournament) {
            tournamentRoundEnded(activePlayer)
            return
        }
        scores[activePlayer] = scores[activePlayer] + 1
        winner = activePlayer
        pick = null
        pendingMover = null
        phase = Phase.GameOver
    }

    /** Starts the next round with the same players and mode. Scores are kept. */
    fun nextGame() {
        if (tournament) {
            // A new tournament: everybody is back in, scores are kept.
            resetTournamentState()
            showBanner()
        }
        resetRound()
    }

    private val stablePhases = setOf(Phase.AwaitRoll, Phase.Choose, Phase.CaptureChoose, Phase.GameOver, Phase.RoundBreak)

    /** Waits (up to about 20 seconds) until no move or computer action is half way through. */
    private suspend fun settle() {
        var waited = 0
        while ((phase !in stablePhases || computerBusy) && waited < 400) {
            delay(50)
            waited++
        }
    }

    /** Menu > Restart: a fresh round with the same players. Scores are kept. */
    suspend fun restartRound() {
        halting = true
        try {
            settle()
            resetRound()
        } finally {
            halting = false
        }
    }

    private fun seedsOut(player: Int): Int = pieces.count { it.color in colorsOf(player) && it.won }

    private fun distance(player: Int): Int =
        pieces.filter { it.color in colorsOf(player) }.sumOf { if (it.won) Route.CENTER else maxOf(it.progress, 0) }

    /** Most seeds out (captured or home) leads. If equal, the one whose seeds are closest to home leads. */
    private fun leaderAmong(candidates: List<Int>): Int {
        var best = candidates.first()
        for (c in candidates.drop(1)) {
            val a = seedsOut(c)
            val b = seedsOut(best)
            if (a > b || (a == b && distance(c) > distance(best))) best = c
        }
        return best
    }

    /** Who receives the point if [player] resigns right now: the opponent, or the tournament leader. */
    fun resignBeneficiary(player: Int = 0): Int =
        if (!tournament) (player + 1) % playerCount
        else leaderAmong(
            (0 until playerCount).filter { it != player && active[it] }
                .ifEmpty { listOf((player + 1) % playerCount) }
        )

    /** Menu > Resign: the point goes to [resignBeneficiary] and the winner page shows. */
    suspend fun resign(player: Int = 0) {
        halting = true
        try {
            settle()
            if (phase == Phase.GameOver || phase == Phase.RoundBreak) return
            if (tournament) {
                tournamentResign(player)
                return
            }
            val to = resignBeneficiary(player)
            scores[to] = scores[to] + 1
            winner = to
            pick = null
            pendingMover = null
            selectedDie = -1
            phase = Phase.GameOver
        } finally {
            halting = false
        }
    }

    /** Fresh round: every seed back in its house, no winning seeds, clean dice. Scores are kept. */
    private fun resetRound() {
        for (p in pieces) {
            p.progress = Route.IN_HOUSE
            p.finishRank = -1
            p.won = false
        }
        movingPiece = null
        pendingMover = null
        returning = null
        pick = null
        movedThisRoll = false
        activePlayer = if (tournament) firstActive() else 0
        winner = -1
        bonusRoll = false
        die1 = 0
        die2 = 0
        used1 = false
        used2 = false
        selectedDie = -1
        computerOption = -1
        face1 = 3
        face2 = 4
        phase = Phase.AwaitRoll
    }

    // ------------------------------------------------------------------
    // Tournament elimination.
    // A round ends when one active player has all 4 seeds out. That player is safe and gets no
    // point. One other player is knocked out. Rounds: 4 -> 3 -> 2 players (the final).
    // Only the winner of the final (or the player a leaving human hands the point to) scores.
    // ------------------------------------------------------------------

    fun activeCount(): Int = active.count { it }

    private fun firstActive(): Int = (0 until 4).firstOrNull { active[it] } ?: 0

    /** The next player still in the tournament after [from]: 0 -> 1 -> 2 -> 3 -> 0. */
    private fun nextActiveAfter(from: Int): Int {
        for (step in 1..4) {
            val p = (((from + step) % 4) + 4) % 4
            if (active[p]) return p
        }
        return firstActive()
    }

    private fun nextPlayer(from: Int): Int =
        if (!tournament) (from + 1) % playerCount else nextActiveAfter(from)

    private fun isOut(color: LudoColor): Boolean =
        tournament && !active[TOURNAMENT_COLORS.indexOf(color)]

    /** Banner text for a round: "Round 1 - 4 Players", "Round 2 - 3 Players", "Round 3 - Final". */
    fun roundTitle(r: Int = round): String {
        val players = startPlayers - r + 1
        return if (players <= 2) "Round $r - Final" else "Round $r - $players Players"
    }

    private fun resetTournamentState() {
        for (i in 0 until 4) active[i] = i in startSet
        round = 1
        eliminatedOrder.clear()
        spectator = false
        overlay = TOverlay.NONE
        resultEnder = -1
        resultOut = -1
        resultPlayers = emptyList()
        resultSeeds = listOf(0, 0, 0, 0)
        resultResigned = false
        leaveTo = -1
        tieIds = emptyList()
        for (i in 0 until 4) tieRolls[i] = 0
        tieRolling = -1
        stopFast()
    }

    /** Spectator only: make the computers play fast (no sound) until the tournament is decided. */
    fun startFast() {
        if (!tournament || !spectator || fast) return
        fast = true
        Sounds.quiet = true
    }

    fun stopFast() {
        fast = false
        Sounds.quiet = false
    }

    /** The tournament is decided: [to] gets the only point and the winner page shows. */
    private fun finishTournament(to: Int) {
        scores[to] = scores[to] + 1
        winner = to
        overlay = TOverlay.NONE
        pick = null
        pendingMover = null
        selectedDie = -1
        stopFast()
        phase = Phase.GameOver
    }

    /** [ender] has just got all 4 seeds out. Works out who is knocked out (or who wins the final). */
    private fun tournamentRoundEnded(ender: Int) {
        pick = null
        pendingMover = null
        selectedDie = -1
        val inRound = (0 until 4).filter { active[it] }
        if (inRound.size <= 2) {
            finishTournament(ender)   // the final: first to get all 4 seeds out wins
            return
        }
        resultEnder = ender
        resultPlayers = inRound
        resultSeeds = (0 until 4).map { if (active[it]) seedsOut(it) else -1 }
        resultResigned = false
        // 1. Fewest seeds out. 2. If tied: least total distance travelled. 3. If still tied: dice roll-off.
        val fewest = inRound.minOf { seedsOut(it) }
        var cand = inRound.filter { seedsOut(it) == fewest }
        if (cand.size > 1) {
            val least = cand.minOf { distance(it) }
            cand = cand.filter { distance(it) == least }
        }
        phase = Phase.RoundBreak
        if (cand.size == 1) {
            finishElimination(cand[0])
        } else {
            tieIds = cand
            for (i in 0 until 4) tieRolls[i] = 0
            tieRolling = -1
            overlay = TOverlay.TIEBREAK
        }
    }

    /** [out] is knocked out by the round result: show the Round Result screen. */
    private fun finishElimination(out: Int) {
        resultOut = out
        active[out] = false
        eliminatedOrder.add(out)
        if (out == 0) leaveTo = resultEnder   // if the human leaves, the round winner gets the point
        tieIds = emptyList()
        for (i in 0 until 4) tieRolls[i] = 0
        overlay = TOverlay.RESULT
        phase = Phase.RoundBreak
    }

    /** Round Result screen: Next Round (or Continue when the human is out and still has to choose). */
    fun resultNext() {
        if (overlay != TOverlay.RESULT) return
        if (!humansOnly && !active[0] && !spectator) {
            overlay = TOverlay.OUT
            return
        }
        startNextRound()
    }

    /** Every remaining player starts again with all seeds in the house. The turn goes to the next remaining player after the one who ended the round. */
    private fun startNextRound() {
        val after = resultEnder
        overlay = TOverlay.NONE
        resetRound()
        round = (startPlayers + 1 - activeCount()).coerceIn(1, 3)
        activePlayer = if (after in 0..3) nextActiveAfter(after) else firstActive()
        showBanner()
    }

    /** You're Out > Watch: the human stays as a spectator and the computers play on. */
    fun outWatch() {
        if (overlay != TOverlay.OUT) return
        spectator = true
        startNextRound()
    }

    /** You're Out > Leave: the tournament ends now and the point goes to [leaveTo]. */
    fun outLeave() {
        if (overlay != TOverlay.OUT) return
        val to = if (leaveTo in 0..3) leaveTo else resignBeneficiary(0)
        finishTournament(to)
    }

    /** Menu > Resign in Tournament: the human is knocked out at once and the unfinished round is dropped. */
    private fun tournamentResign(player: Int) {
        if (!active[player]) return
        val to = resignBeneficiary(player)
        pick = null
        pendingMover = null
        selectedDie = -1
        if (activeCount() <= 2) {
            finishTournament(to)   // the final: nobody is left to watch, the other player wins
            return
        }
        active[player] = false
        eliminatedOrder.add(player)
        resultEnder = -1
        resultOut = player
        resultResigned = true
        leaveTo = to
        overlay = TOverlay.OUT
        phase = Phase.RoundBreak
    }

    // ---- Dice roll-off (Tie-Break screen) ----

    /** The human taps their own dice on the Tie-Break screen. */
    suspend fun tieHumanRoll() {
        if (overlay != TOverlay.TIEBREAK || tieRolling >= 0) return
        if (humansOnly) {
            // Family and Connect: every tied player is a human and taps in turn.
            val next = tieTurn
            if (next >= 0) rollTieDie(next)
            return
        }
        if (0 !in tieIds || tieRolls[0] != 0) return
        rollTieDie(0)
    }

    /** Family tie-break: the tied player who has to tap next (-1 = nobody / a roll is in progress). */
    val tieTurn: Int
        get() = if (tieRolling >= 0) -1 else tieIds.firstOrNull { tieRolls[it] == 0 } ?: -1

    private suspend fun rollTieDie(p: Int) {
        tieRolling = p
        try {
            val v = Random.nextInt(1, 7)
            visualSink?.invoke(VisualEvent.TieRoll(p, v))
            playTieVisual(v)
            if (overlay == TOverlay.TIEBREAK && p in tieIds) tieRolls[p] = v
        } finally {
            tieRolling = -1
        }
    }

    /** The tie-break die flickers, then shows [v]. Visual only. */
    private suspend fun playTieVisual(v: Int) {
        sounds.roll()
        repeat(8) {
            tieFlicker = Random.nextInt(1, 7)
            delay((60 * fastMul).toLong())
        }
        tieFlicker = v
        sounds.dieLand()
    }

    /**
     * Runs the roll-off while the Tie-Break screen is open: the human rolls by tapping, the
     * computers roll by themselves. The lowest roll is knocked out; a tie for lowest rolls again.
     */
    suspend fun runTieBreak() {
        while (overlay == TOverlay.TIEBREAK) {
            val pending = tieIds.filter { tieRolls[it] == 0 }
            if (pending.isEmpty()) {
                delay((1500 * fastMul).toLong())   // everybody can see the numbers
                if (overlay != TOverlay.TIEBREAK) return
                resolveTie()
            } else if (humansOnly || 0 in pending || tieRolling >= 0) {
                delay(100)                         // waiting for the human, or a roll is in progress
            } else {
                delay((800 * fastMul).toLong())
                if (overlay != TOverlay.TIEBREAK) return
                val next = tieIds.firstOrNull { tieRolls[it] == 0 && it != 0 }
                if (next != null && tieRolling < 0) rollTieDie(next)
            }
        }
    }

    private fun resolveTie() {
        val ids = tieIds
        if (ids.isEmpty() || ids.any { tieRolls[it] == 0 }) return
        val low = ids.minOf { tieRolls[it] }
        val lows = ids.filter { tieRolls[it] == low }
        if (lows.size == 1) {
            finishElimination(lows[0])
        } else {
            // Only the players tied for the lowest roll again.
            tieIds = lows
            for (i in 0 until 4) tieRolls[i] = 0
        }
    }

    suspend fun roll() {
        if (phase != Phase.AwaitRoll) return
        phase = Phase.Rolling
        val a = Random.nextInt(1, 7)
        val b = Random.nextInt(1, 7)
        visualSink?.invoke(VisualEvent.Roll(activePlayer, a, b))
        playRollVisual(a, b)
        die1 = a
        die2 = b
        used1 = false
        used2 = false
        selectedDie = -1
        movedThisRoll = false
        afterDiceChange()
    }

    /** The dice flicker, then land on [a] and [b]. Visual only. */
    private suspend fun playRollVisual(a: Int, b: Int) {
        repeat(9) { i ->
            face1 = Random.nextInt(1, 7)
            face2 = Random.nextInt(1, 7)
            if (i == 0) sounds.roll()   // one recorded roll sound for the whole roll
            delay((60 * fastMul).toLong())
        }
        face1 = a
        face2 = b
        sounds.dieLand()
    }

    private suspend fun afterDiceChange() {
        pick = null
        selectedDie = -1
        if (usableOptions().isEmpty()) {
            endTurn()
            return
        }
        phase = Phase.Choose
    }

    private suspend fun endTurn() {
        phase = Phase.Pausing
        delay(((if (movedThisRoll) 500L else 1200L) * fastMul).toLong())
        val extraRoll = die1 == 6 && die2 == 6   // only a double six gives another roll
        bonusRoll = extraRoll
        die1 = 0
        die2 = 0
        used1 = false
        used2 = false
        selectedDie = -1
        if (!extraRoll) activePlayer = nextPlayer(activePlayer)
        phase = Phase.AwaitRoll
    }

    /** The player taps the blue (0), green (1) or red total (2) circle to decide what to move by. */
    fun selectDie(i: Int) {
        if (isComputerTurn) return   // human-only control: a computer never uses it
        if (phase != Phase.Choose) return
        if (!optionUsable(i)) return
        pick = null
        selectedDie = i
    }

    /**
     * Called when the player taps a pawn in the popup: either which own seed to move
     * (same-spot choice) or which opponent seed to capture.
     */
    suspend fun onPiecePicked(tag: Any?) {
        if (isComputerTurn) return   // human-only input: a computer never uses it
        val piece = tag as? Piece ?: return
        if (phase == Phase.CaptureChoose) {
            resolveCapture(piece)
            return
        }
        pick = null
        choosePiece(piece)
    }

    /** Applies the chosen capture once (human choice or computer decision). Leaves CaptureChoose first. */
    private suspend fun resolveCapture(victim: Piece) {
        if (phase != Phase.CaptureChoose) return
        val mover = pendingMover ?: return
        if (pick?.items?.any { it.tag === victim } != true) return
        pick = null
        pendingMover = null
        phase = Phase.Moving
        capture(mover, victim)
        finishMove()
    }

    fun dismissPick() {
        pick = null
    }

    /** HUMAN path: the seed the player tapped, moved with the circle the player selected. */
    private suspend fun choosePiece(piece: Piece) {
        if (!isGlowing(piece)) return
        executeMove(piece, selectedDie)
    }

    /**
     * The one place a move is carried out, for the human and for every computer. It re-checks the
     * move against the shared rules and spends the dice exactly once: the phase leaves Choose
     * before anything else happens, so a repeated call can never move or count twice.
     */
    private suspend fun executeMove(piece: Piece, dieIndex: Int) {
        if (phase != Phase.Choose) return
        if (dieIndex !in 0..2) return
        if (piece !in ownPieces() || !canUse(piece, dieIndex)) return
        val base = optionValue(dieIndex)
        // 5 + 5 rule: this seed may not stop on an opponent, so it also takes the other die.
        val forced = dieIndex != 2 && forcedContinuation(piece, dieIndex)
        val extra = if (forced) optionValue(1 - dieIndex) else 0
        phase = Phase.Moving
        pick = null
        // The red total and a forced continuation spend both dice; a single die spends only itself.
        when {
            dieIndex == 2 || forced -> {
                used1 = true
                used2 = true
            }
            dieIndex == 0 -> used1 = true
            else -> used2 = true
        }
        selectedDie = -1
        movedThisRoll = true

        val from = piece.progress
        val to = if (from == Route.IN_HOUSE) extra else from + base + extra
        moveTo = to
        if (to == Route.CENTER) {
            piece.finishRank = pieces.count { it.color == piece.color && it.finishRank >= 0 }
        }
        animateMove(piece, from, to)
        piece.progress = to
        movingPiece = null
        if (to == Route.CENTER) piece.won = true

        // Capture is decided only where the move really ends.
        val victims = opponentsAt(piece)
        if (victims.size > 1) {
            openCaptureChoice(piece, victims)   // the player picks which opponent seed
            return
        }
        if (victims.size == 1) capture(piece, victims[0])
        finishMove()
    }

    private suspend fun finishMove() {
        // The 8th winning seed (capture or home) ends the round: +1 score, then a fresh round.
        if (ownPieces().all { it.won }) {
            phase = Phase.Pausing
            sounds.win()
            delay((700 * fastMul).toLong())
            completeRound()
            return
        }
        afterDiceChange()
    }

    private fun openCaptureChoice(mover: Piece, victims: List<Piece>) {
        val (r, c) = Route.cell(mover.color, mover.progress)
        pendingMover = mover
        pick = PiecePick(
            r + 0.5f,
            c + 0.5f,
            victims.sortedBy { it.color.ordinal * 4 + it.slot }
                .map { PieceView(swatchOf(it.color), r + 0.5f, c + 0.5f, BOARD_R, false, 0f, it) },
            true
        )
        phase = Phase.CaptureChoose
    }

    /**
     * Sends the victim back to its own house (it needs a new 6 to come out), and banks the
     * capturing seed as a winning seed: it leaves the route and is never sent to its house.
     */
    private fun applyCapture(mover: Piece, victim: Piece) {
        victim.progress = Route.IN_HOUSE
        victim.finishRank = -1
        mover.won = true
        mover.progress = Route.BANKED
        mover.finishRank = -1
    }

    private suspend fun capture(mover: Piece, victim: Piece) {
        val (vr, vc) = Route.anchor(victim.color, victim.slot, victim.finishRank, victim.progress)
        visualSink?.invoke(VisualEvent.Capture(victim, mover))
        applyCapture(mover, victim)
        playCaptureVisual(victim, vr, vc, true)
    }

    /**
     * The captured seed travels from ([vr], [vc]) back to its house. Visual only. The host lets the seed
     * go ([clearEnd] = true); an observer keeps it in its house until the snapshot arrives.
     */
    private suspend fun playCaptureVisual(victim: Piece, vr: Float, vc: Float, clearEnd: Boolean) {
        returning = victim
        retRow = vr
        retCol = vc
        retT = 0f
        sounds.land()   // also a short buzz (see Sounds.land)
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - start) / 1_000_000f) / (450f * fastMul)
            if (t >= 1f) break
            retT = t
        }
        if (clearEnd) {
            returning = null
        } else {
            retT = 1f
        }
    }

    private suspend fun animateMove(piece: Piece, from: Int, to: Int) {
        val steps = to - from
        val duration = maxOf(
            8f,
            (if (from == Route.IN_HOUSE) 400f + to * 170f else steps * (if (steps > 6) 150f else 190f)) * fastMul
        )
        visualSink?.invoke(VisualEvent.Move(piece, from, to, duration.toLong(), piece.finishRank))
        playMoveVisual(piece, from, to, duration, piece.finishRank)
    }

    /** The seed walks from [from] to [to] in [duration] ms with its sounds. Visual only. */
    private suspend fun playMoveVisual(piece: Piece, from: Int, to: Int, duration: Float, rank: Int) {
        movingPiece = piece
        moveRank = rank
        moveProgress = from.toFloat()
        val steps = to - from
        if (from == Route.IN_HOUSE) sounds.out()
        var lastCell = from
        val startNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - startNanos) / 1_000_000f) / duration
            if (t >= 1f) break
            moveProgress = from + steps * t
            val cell = floor(moveProgress).toInt()
            if (cell != lastCell) {
                lastCell = cell
                if (from != Route.IN_HOUSE) sounds.tick()
            }
        }
        moveProgress = to.toFloat()
        if (to == Route.CENTER) sounds.finish()
        else if (from != Route.IN_HOUSE) sounds.tick()
    }

    /** Called with a tap position in board grid units. */
    suspend fun onBoardTap(row: Float, col: Float) {
        if (isComputerTurn) return   // human-only input
        when (phase) {
            Phase.AwaitRoll -> {
                if (row in 6f..9f && col in 6f..9f) roll()
            }
            Phase.GameOver -> {
                if (row in 6f..9f && col in 6f..9f) {
                    if (tournament) nextGame() else resetRound()
                }
            }
            Phase.Choose -> {
                // Any tap while a popup is open first closes it (its own buttons handle their own taps).
                pick = null
                if (selectedDie !in 0..2) return
                val views = pieceViews().filter { it.glow }
                val hit = hitView(row, col) ?: return
                // Every movable piece standing on the tapped spot (house seeds each have their own spot).
                val here = if (hit.group >= 0) views.filter { it.group == hit.group } else listOf(hit)
                if (here.size <= 1) {
                    choosePiece(hit.tag as Piece)
                } else {
                    // Several pieces here: the player decides, never the game.
                    pick = PiecePick(
                        (hit.group / 20) + 0.5f,
                        (hit.group % 20) + 0.5f,
                        here.sortedBy { (it.tag as Piece).color.ordinal * 4 + (it.tag as Piece).slot }
                    )
                }
            }
            else -> {}
        }
    }

    private fun swatchOf(color: LudoColor): Swatch = when (color) {
        LudoColor.GREEN -> Palette.Green
        LudoColor.YELLOW -> Palette.Yellow
        LudoColor.BLUE -> Palette.Blue
        LudoColor.RED -> Palette.Red
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Everything the board needs to draw the pieces right now. */
    fun pieceViews(): List<PieceView> {
        val moving = movingPiece
        val house = ArrayList<PieceView>()
        val board = ArrayList<PieceView>()
        val done = ArrayList<PieceView>()
        val groups = HashMap<Int, MutableList<Piece>>()

        for (p in pieces) {
            if (p === moving || p === returning || p === hiddenPiece) continue
            if (isOut(p.color)) continue   // a knocked-out player's seeds are not drawn
            val pr = p.progress
            when {
                pr < 0 -> {
                    val (r, c) = Route.houseSpot(p.color, p.slot)
                    house.add(PieceView(swatchOf(p.color), r, c, HOUSE_R, isGlowing(p), 0f, p))
                }
                pr == Route.BANKED -> continue   // a banked winning seed is no longer on the board
                pr >= Route.CENTER -> {
                    val (r, c) = Route.finishSpot(p.color, p.finishRank)
                    done.add(PieceView(swatchOf(p.color), r, c, FINISH_R, false, 0f, p))
                }
                else -> {
                    val (r, c) = Route.cell(p.color, pr)
                    groups.getOrPut(r * 20 + c) { ArrayList() }.add(p)
                }
            }
        }

        // Pieces sharing a cell sit side by side instead of hiding each other.
        for ((key, list) in groups) {
            val r = key / 20
            val c = key % 20
            val n = list.size
            val cols = ceil(sqrt(n.toFloat())).toInt()
            val rowsN = (n + cols - 1) / cols
            val spacing = 0.86f / cols
            val rad = if (cols == 1) BOARD_R else BOARD_R * 1.25f / cols
            list.forEachIndexed { i, p ->
                val ox = (i % cols) - (cols - 1) / 2f
                val oy = (i / cols) - (rowsN - 1) / 2f
                board.add(
                    PieceView(
                        swatchOf(p.color),
                        r + 0.5f + oy * spacing,
                        c + 0.5f + ox * spacing,
                        rad,
                        isGlowing(p),
                        0f,
                        p,
                        key,
                        p.won
                    )
                )
            }
        }
        board.sortBy { it.row }

        val out = ArrayList<PieceView>()
        out.addAll(house)
        out.addAll(board)
        out.addAll(done)

        if (moving != null) {
            val f = moveProgress
            val a = floor(f).toInt()
            val t = f - a
            val (r0, c0) = Route.anchor(moving.color, moving.slot, moveRank, a)
            val (r1, c1) = Route.anchor(moving.color, moving.slot, moveRank, minOf(a + 1, Route.CENTER))
            val rad = when {
                f < 0f -> lerp(HOUSE_R, BOARD_R, f + 1f)
                f > 55f -> lerp(BOARD_R, FINISH_R, f - 55f)
                else -> BOARD_R
            }
            val hop = sin(PI.toFloat() * t)
            out.add(
                PieceView(
                    swatchOf(moving.color),
                    lerp(r0, r1, t),
                    lerp(c0, c1, t),
                    rad,
                    false,
                    hop,
                    moving
                )
            )
        }
        val ret = returning
        if (ret != null) {
            val t = retT
            val (hr, hc) = Route.houseSpot(ret.color, ret.slot)
            out.add(
                PieceView(
                    swatchOf(ret.color),
                    lerp(retRow, hr, t),
                    lerp(retCol, hc, t),
                    lerp(BOARD_R, HOUSE_R, t),
                    false,
                    sin(PI.toFloat() * t),
                    ret
                )
            )
        }
        return out
    }

    private companion object {
        /** Tournament players 1..4 in turn order (clockwise around the board). */
        val TOURNAMENT_COLORS = listOf(LudoColor.RED, LudoColor.GREEN, LudoColor.YELLOW, LudoColor.BLUE)
        const val HOUSE_R = 0.66f
        const val BOARD_R = 0.42f
        const val FINISH_R = 0.14f
    }
}

/**
 * Connect and Play, host only: what the host's rules are about to animate. The host sends each one to the
 * other phones at the moment the animation starts, so they can play the same one.
 */
sealed class VisualEvent {
    /** The dice of [player] are rolling and will land on [a] and [b]. */
    class Roll(val player: Int, val a: Int, val b: Int) : VisualEvent()

    /** [piece] walks from progress [from] to [to] in [durationMs]; [rank] is its rest spot if it reaches the centre. */
    class Move(val piece: Piece, val from: Int, val to: Int, val durationMs: Long, val rank: Int) : VisualEvent()

    /** [victim] is sent home by [mover]. */
    class Capture(val victim: Piece, val mover: Piece) : VisualEvent()

    /** [player] rolls the tie-break die and gets [value]. */
    class TieRoll(val player: Int, val value: Int) : VisualEvent()
}

/** One legal action: use movement option [option] (0 = blue, 1 = green, 2 = red total) on [piece]. */
class Move(val option: Int, val piece: Piece)

/** Where the pointing hand should be. */
sealed class HandTarget {
    object Dice : HandTarget()
    class Orb(val index: Int) : HandTarget()
    class Spot(val row: Float, val col: Float) : HandTarget()
}
