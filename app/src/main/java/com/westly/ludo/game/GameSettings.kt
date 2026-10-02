package com.westly.ludo.game

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Configuration choices, saved on this phone.
 * boardType: 0 Classic, 1 Afrobeats, 2 Super Eagles, 3 Adventure, 4 Kingdoms.
 * speed: 0 Slow, 1 Normal, 2 Fast (computer pace). level: 0 Easy, 1 Normal (computer level).
 */
class GameSettings(private val prefs: SharedPreferences) {
    var boardType by mutableIntStateOf(prefs.getInt("board_type", 0).coerceIn(0, 4))
        private set
    var speed by mutableIntStateOf(prefs.getInt("comp_speed", 1).coerceIn(0, 2))
        private set
    var level by mutableIntStateOf(prefs.getInt("comp_level", 0).coerceIn(0, 1))
        private set

    /** Multiplier for the computer's waiting times: bigger = slower. */
    val speedFactor: Float
        get() = when (speed) {
            0 -> 1.4f
            2 -> 0.75f
            else -> 1f
        }

    fun chooseBoard(value: Int) {
        boardType = value.coerceIn(0, 4)
        prefs.edit().putInt("board_type", boardType).apply()
    }

    fun chooseSpeed(value: Int) {
        speed = value.coerceIn(0, 2)
        prefs.edit().putInt("comp_speed", speed).apply()
    }

    fun chooseLevel(value: Int) {
        level = value.coerceIn(0, 1)
        prefs.edit().putInt("comp_level", level).apply()
    }
}
