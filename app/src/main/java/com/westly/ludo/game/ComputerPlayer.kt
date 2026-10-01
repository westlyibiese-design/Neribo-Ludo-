package com.westly.ludo.game

import kotlin.random.Random

/**
 * One independent Tournament computer opponent. It owns exactly one color and only ever
 * looks at moves the shared rule engine says are legal for that color right now.
 * It never changes the game itself: the game plays the chosen move through the same
 * movement and capture code the human uses.
 */
class ComputerPlayer(val playerIndex: Int, val color: LudoColor) {

    /** Picks one legal move for this player, or null if it is not its turn / nothing is legal. */
    fun chooseMove(game: LudoGame): Move? {
        if (game.activePlayer != playerIndex) return null
        val moves = game.legalMoves().filter { it.piece.color == color }
        if (moves.isEmpty()) return null
        return moves.maxByOrNull { score(game, it) }
    }

    /** Picks which opponent seed to capture when several stand on the same cell. */
    fun chooseVictim(game: LudoGame): Piece? {
        if (game.activePlayer != playerIndex) return null
        val victims = game.pick?.items?.mapNotNull { it.tag as? Piece } ?: return null
        return victims.maxByOrNull { it.progress }
    }

    private fun score(game: LudoGame, m: Move): Double {
        val from = m.piece.progress
        val to = game.targetOf(m)
        var s = 0.0
        if (to == Route.CENTER) s += 100.0
        val victims = game.victimsAfter(m)
        if (victims.isNotEmpty()) s += 90.0 + victims.maxOf { it.progress } * 0.3
        if (from == Route.IN_HOUSE) s += 45.0
        if (to in 51 until Route.CENTER) s += 20.0
        s += to * 0.4
        if (from in 0..50 && threatened(game, from)) s += 25.0
        if (to in 1..50 && threatened(game, to)) s -= 30.0
        if (m.option == 2 && victims.isEmpty() && to != Route.CENTER) s -= 8.0
        return s + Random.nextDouble()
    }

    /** True if an opponent seed stands 1..12 cells behind this progress on the common route. */
    private fun threatened(game: LudoGame, progress: Int): Boolean {
        val me = Route.ringIndex(color, progress)
        return game.pieces.any { q ->
            q.color != color && q.progress in 0..50 &&
                ((me - Route.ringIndex(q.color, q.progress) + 52) % 52) in 1..12
        }
    }
}
