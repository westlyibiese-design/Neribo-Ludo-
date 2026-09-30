package com.westly.ludo.game

enum class LudoColor { GREEN, YELLOW, BLUE, RED }

/**
 * The internal route map. Nothing here is ever drawn as a number.
 *
 * Progress of a piece:
 *   -1        = inside its house
 *    0        = its own start / entry cell
 *    1..50    = common route (step 50 is the cell just before its own lane)
 *    51..55   = the five cells of its own colored lane
 *    56       = the center / home
 * So after entering, a piece travels 50 steps to reach the lane entrance,
 * then 6 more steps (5 lane cells + the center).
 *
 * All cell positions are (row, col) on the 15x15 grid used by LudoBoard.
 */
object Route {
    const val IN_HOUSE = -1
    const val CENTER = 56

    // The 52 cells of the common route, clockwise, starting at the green start cell.
    private val ring: List<Pair<Int, Int>> = listOf(
        6 to 1, 6 to 2, 6 to 3, 6 to 4, 6 to 5,
        5 to 6, 4 to 6, 3 to 6, 2 to 6, 1 to 6, 0 to 6,
        0 to 7, 0 to 8,
        1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8,
        6 to 9, 6 to 10, 6 to 11, 6 to 12, 6 to 13, 6 to 14,
        7 to 14, 8 to 14,
        8 to 13, 8 to 12, 8 to 11, 8 to 10, 8 to 9,
        9 to 8, 10 to 8, 11 to 8, 12 to 8, 13 to 8, 14 to 8,
        14 to 7, 14 to 6,
        13 to 6, 12 to 6, 11 to 6, 10 to 6, 9 to 6,
        8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0,
        7 to 0, 6 to 0
    )

    // Where each color's own start cell sits inside the ring above.
    private fun startIndex(color: LudoColor): Int = when (color) {
        LudoColor.GREEN -> 0      // (6, 1)
        LudoColor.YELLOW -> 13    // (1, 8)
        LudoColor.BLUE -> 26      // (8, 13)
        LudoColor.RED -> 39       // (13, 6)
    }

    // The five colored lane cells of each color, from the outside toward the center.
    private fun lane(color: LudoColor): List<Pair<Int, Int>> = when (color) {
        LudoColor.GREEN -> listOf(7 to 1, 7 to 2, 7 to 3, 7 to 4, 7 to 5)
        LudoColor.YELLOW -> listOf(1 to 7, 2 to 7, 3 to 7, 4 to 7, 5 to 7)
        LudoColor.BLUE -> listOf(7 to 13, 7 to 12, 7 to 11, 7 to 10, 7 to 9)
        LudoColor.RED -> listOf(13 to 7, 12 to 7, 11 to 7, 10 to 7, 9 to 7)
    }

    /** Board cell (row, col) for progress 0..55. */
    fun cell(color: LudoColor, progress: Int): Pair<Int, Int> =
        if (progress <= 50) ring[(startIndex(color) + progress) % 52]
        else lane(color)[progress - 51]

    /** Resting spot of a seed inside its yard, as grid coordinates (row, col). */
    fun houseSpot(color: LudoColor, slot: Int): Pair<Float, Float> {
        val yardRow = if (color == LudoColor.RED || color == LudoColor.BLUE) 9f else 0f
        val yardCol = if (color == LudoColor.YELLOW || color == LudoColor.BLUE) 9f else 0f
        val dx = if (slot % 2 == 0) 2f else 4f
        val dy = if (slot / 2 == 0) 2f else 4f
        return (yardRow + dy) to (yardCol + dx)
    }

    /** Where a piece that reached the center rests, on the side of its own color. */
    fun finishSpot(color: LudoColor, rank: Int): Pair<Float, Float> {
        val k = rank.coerceIn(0, 3) - 1.5f
        return when (color) {
            LudoColor.YELLOW -> 6.2f to (7.5f + k * 0.4f)
            LudoColor.RED -> 8.8f to (7.5f + k * 0.4f)
            LudoColor.GREEN -> (7.5f + k * 0.36f) to 6.2f
            LudoColor.BLUE -> (7.5f + k * 0.36f) to 8.8f
        }
    }

    /** Center of the spot for a whole progress value (-1 house .. 56 center), as grid coordinates. */
    fun anchor(color: LudoColor, slot: Int, finishRank: Int, progress: Int): Pair<Float, Float> = when {
        progress < 0 -> houseSpot(color, slot)
        progress >= CENTER -> finishSpot(color, finishRank)
        else -> {
            val (r, c) = cell(color, progress)
            (r + 0.5f) to (c + 0.5f)
        }
    }
}
