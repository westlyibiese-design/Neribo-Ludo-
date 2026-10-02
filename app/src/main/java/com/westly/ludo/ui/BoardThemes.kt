package com.westly.ludo.ui

import com.westly.ludo.R

/** The board types shown in Configuration. Index 0 is the normal board with no pictures. */
object BoardThemes {
    val names = listOf("Classic", "Afrobeats", "Super Eagles", "Adventure", "Kingdoms")

    /** Yard pictures in the order green, yellow, red, blue (empty for Classic). */
    fun images(type: Int): List<Int> = when (type) {
        1 -> listOf(
            R.drawable.board_afro_green, R.drawable.board_afro_yellow,
            R.drawable.board_afro_red, R.drawable.board_afro_blue
        )
        2 -> listOf(
            R.drawable.board_eagles_green, R.drawable.board_eagles_yellow,
            R.drawable.board_eagles_red, R.drawable.board_eagles_blue
        )
        3 -> listOf(
            R.drawable.board_adv_green, R.drawable.board_adv_yellow,
            R.drawable.board_adv_red, R.drawable.board_adv_blue
        )
        4 -> listOf(
            R.drawable.board_king_green, R.drawable.board_king_yellow,
            R.drawable.board_king_red, R.drawable.board_king_blue
        )
        else -> emptyList()
    }
}
