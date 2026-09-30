package com.westly.ludo.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.westly.ludo.ui.Palette
import com.westly.ludo.ui.PieceView
import com.westly.ludo.ui.Swatch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.delay

enum class Phase { AwaitRoll, Rolling, Choose, Moving, Pausing, GameOver }

class Piece(val color: LudoColor, val slot: Int) {
    /** -1 = in house, 0..55 = on the route, 56 = reached the center. */
    var progress by mutableIntStateOf(Route.IN_HOUSE)
    var finishRank by mutableIntStateOf(-1)
}

/**
 * All gameplay rules live here. The board only draws what this class says.
 * Player 1 owns yellow + red, Player 2 owns green + blue (as labelled on the yards).
 */
class LudoGame {
    val pieces: List<Piece> = LudoColor.values().flatMap { c -> (0..3).map { Piece(c, it) } }

    var phase by mutableStateOf(Phase.AwaitRoll)
    var activePlayer by mutableIntStateOf(0)
    var winner by mutableIntStateOf(-1)

    // The two dice values (0 = not rolled yet) and whether each has been used this turn.
    var die1 by mutableIntStateOf(0)
    var die2 by mutableIntStateOf(0)
    var used1 by mutableStateOf(false)
    var used2 by mutableStateOf(false)
    var selectedDie by mutableIntStateOf(-1)

    // Faces shown on the two real dice.
    var face1 by mutableIntStateOf(3)
    var face2 by mutableIntStateOf(4)

    private var movingPiece by mutableStateOf<Piece?>(null)
    private var moveProgress by mutableFloatStateOf(0f)
    private var movedThisRoll = false

    private fun colorsOf(player: Int): List<LudoColor> =
        if (player == 0) listOf(LudoColor.YELLOW, LudoColor.RED)
        else listOf(LudoColor.GREEN, LudoColor.BLUE)

    private fun dieValue(i: Int): Int = if (i == 0) die1 else die2
    private fun dieUsed(i: Int): Boolean = if (i == 0) used1 else used2

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

    private fun usableDice(): List<Int> =
        (0..1).filter { i -> !dieUsed(i) && ownPieces().any { canMove(it, dieValue(i)) } }

    /** True for pieces that may move with the currently selected die. */
    private fun isGlowing(piece: Piece): Boolean {
        if (phase != Phase.Choose) return false
        val i = selectedDie
        if (i < 0 || dieUsed(i)) return false
        if (piece.color !in colorsOf(activePlayer)) return false
        return canMove(piece, dieValue(i))
    }

    fun newGame() {
        for (p in pieces) {
            p.progress = Route.IN_HOUSE
            p.finishRank = -1
        }
        movingPiece = null
        activePlayer = 0
        winner = -1
        die1 = 0
        die2 = 0
        used1 = false
        used2 = false
        selectedDie = -1
        face1 = 3
        face2 = 4
        phase = Phase.AwaitRoll
    }

    suspend fun roll() {
        if (phase != Phase.AwaitRoll) return
        phase = Phase.Rolling
        repeat(9) {
            face1 = Random.nextInt(1, 7)
            face2 = Random.nextInt(1, 7)
            delay(60)
        }
        val a = Random.nextInt(1, 7)
        val b = Random.nextInt(1, 7)
        face1 = a
        face2 = b
        die1 = a
        die2 = b
        used1 = false
        used2 = false
        selectedDie = -1
        movedThisRoll = false
        afterDiceChange()
    }

    private suspend fun afterDiceChange() {
        val usable = usableDice()
        if (usable.isEmpty()) {
            endTurn()
            return
        }
        if (selectedDie !in usable) selectedDie = usable.first()
        phase = Phase.Choose
    }

    private suspend fun endTurn() {
        phase = Phase.Pausing
        delay(if (movedThisRoll) 500L else 1200L)
        val extraRoll = die1 == 6 && die2 == 6   // only a double six gives another roll
        die1 = 0
        die2 = 0
        used1 = false
        used2 = false
        selectedDie = -1
        if (!extraRoll) activePlayer = 1 - activePlayer
        phase = Phase.AwaitRoll
    }

    /** The player taps the blue (0) or green (1) circle to decide which die to use next. */
    fun selectDie(i: Int) {
        if (phase != Phase.Choose) return
        if (i !in usableDice()) return
        selectedDie = i
    }

    private suspend fun choosePiece(piece: Piece) {
        if (!isGlowing(piece)) return
        val dieIndex = selectedDie
        val d = dieValue(dieIndex)
        phase = Phase.Moving
        if (dieIndex == 0) used1 = true else used2 = true
        movedThisRoll = true

        val from = piece.progress
        val to = if (from == Route.IN_HOUSE) 0 else from + d
        if (to == Route.CENTER) {
            piece.finishRank = pieces.count { it.color == piece.color && it.finishRank >= 0 }
        }
        animateMove(piece, from, to)
        piece.progress = to
        movingPiece = null

        if (ownPieces().all { it.progress == Route.CENTER }) {
            winner = activePlayer
            phase = Phase.GameOver
            return
        }
        afterDiceChange()
    }

    private suspend fun animateMove(piece: Piece, from: Int, to: Int) {
        movingPiece = piece
        moveProgress = from.toFloat()
        val steps = to - from
        val duration = if (from == Route.IN_HOUSE) 400f else steps * 190f
        val startNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - startNanos) / 1_000_000f) / duration
            if (t >= 1f) break
            moveProgress = from + steps * t
        }
        moveProgress = to.toFloat()
    }

    /** Called with a tap position in board grid units. */
    suspend fun onBoardTap(row: Float, col: Float) {
        when (phase) {
            Phase.AwaitRoll -> {
                if (row in 6f..9f && col in 6f..9f) roll()
            }
            Phase.GameOver -> {
                if (row in 6f..9f && col in 6f..9f) newGame()
            }
            Phase.Choose -> {
                var best: PieceView? = null
                var bestDist = 1.15f
                for (v in pieceViews()) {
                    if (!v.glow) continue
                    val dr = v.row - row
                    val dc = v.col - col
                    val dist = sqrt(dr * dr + dc * dc)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = v
                    }
                }
                val hit = best
                if (hit != null) choosePiece(hit.tag as Piece)
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
            if (p === moving) continue
            val pr = p.progress
            when {
                pr < 0 -> {
                    val (r, c) = Route.houseSpot(p.color, p.slot)
                    house.add(PieceView(swatchOf(p.color), r, c, HOUSE_R, isGlowing(p), 0f, p))
                }
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
            val rad = BOARD_R / cols
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
                        p
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
            val (r0, c0) = Route.anchor(moving.color, moving.slot, moving.finishRank, a)
            val (r1, c1) = Route.anchor(moving.color, moving.slot, moving.finishRank, minOf(a + 1, Route.CENTER))
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
        return out
    }

    private companion object {
        const val HOUSE_R = 0.66f
        const val BOARD_R = 0.42f
        const val FINISH_R = 0.14f
    }
}
