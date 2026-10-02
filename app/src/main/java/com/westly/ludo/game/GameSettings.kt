package com.westly.ludo.game

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
    var soundOn by mutableStateOf(prefs.getBoolean("sound_on", true))
        private set
    var vibrationOn by mutableStateOf(prefs.getBoolean("vibration_on", true))
        private set

    init {
        // Sounds reads these two flags directly, so they must be set as soon as the settings load.
        Sounds.soundOn = soundOn
        Sounds.vibrationOn = vibrationOn
    }

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

    fun chooseSound(on: Boolean) {
        soundOn = on
        Sounds.soundOn = on
        prefs.edit().putBoolean("sound_on", on).apply()
    }

    fun chooseVibration(on: Boolean) {
        vibrationOn = on
        Sounds.vibrationOn = on
        prefs.edit().putBoolean("vibration_on", on).apply()
    }

    fun chooseLevel(value: Int) {
        level = value.coerceIn(0, 1)
        prefs.edit().putInt("comp_level", level).apply()
    }
}
