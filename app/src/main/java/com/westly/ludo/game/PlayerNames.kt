package com.westly.ludo.game

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf

/**
 * The four player names (index 0 = the human, shown as "Me").
 * Changed names are saved on this phone and stay until the user changes them again.
 */
class PlayerNames(private val prefs: SharedPreferences) {
    val names = mutableStateListOf<String>()

    init {
        for (i in 0 until COUNT) {
            val saved = prefs.getString("name$i", null)?.trim().orEmpty()
            names.add(if (saved.isEmpty()) DEFAULTS[i] else saved.take(MAX_LENGTH))
        }
    }

    operator fun get(index: Int): String = names.getOrElse(index) { DEFAULTS.getOrElse(index) { "Player" } }

    /** Saves a new name. An empty name goes back to the default one. */
    fun set(index: Int, value: String) {
        if (index !in 0 until COUNT) return
        val clean = value.trim().take(MAX_LENGTH).ifEmpty { DEFAULTS[index] }
        names[index] = clean
        prefs.edit().putString("name$index", clean).apply()
    }

    companion object {
        const val COUNT = 4
        const val MAX_LENGTH = 10
        val DEFAULTS = listOf("Me", "Player 2", "Player 3", "Player 4")
    }
}
