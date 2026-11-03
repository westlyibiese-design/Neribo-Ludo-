package com.westly.ludo.game

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** The three hidden dice helpers. 0 means none. */
object CheatKind {
    const val NONE = 0

    /** The next roll is a double six (never twice in a row). */
    const val SIX = 1

    /** The next roll is a pair that captures an opponent seed. */
    const val KILL = 2

    /** The next roll is a pair that carries one of your seeds into the centre. */
    const val HOME = 3
}

/**
 * Listens for the secret tap codes on the seed circles inside the four houses.
 *
 * Each code is four taps made within 3 seconds. When a code is right (and the helper can really do
 * something this turn) the dice turn golden and the phone buzzes 1, 2 or 3 times. The golden dice
 * must be tapped within 5 seconds (the screen clears them, see [clear]); that tap rolls with the helper.
 *
 * This class only listens and remembers. It changes nothing in the game by itself, shows nothing
 * to other players (the gold lives on this phone only) and is switched off by passing `on = false`.
 */
class CheatPad {

    /** 0 = nothing armed, 1..3 = the helper (see [CheatKind]) that the golden dice will use. */
    var gold by mutableIntStateOf(CheatKind.NONE)
        private set

    /** True right after a helper double six: the very next roll cannot be a helper double six again. */
    private var sixLocked = false

    private val buf = IntArray(CODE_LEN)
    private var count = 0
    private var startedAt = 0L

    /** Forgets a half-typed code and any golden dice. The "once in a row" lock is kept. */
    fun clear() {
        gold = CheatKind.NONE
        count = 0
    }

    /**
     * Call for every tap on the board. [on] is true only on this person's own turn, waiting to roll, in
     * a game that allows the helper. [ready] tells whether a helper can really be used right now.
     */
    fun listen(row: Float, col: Float, on: Boolean, ready: (Int) -> Boolean) {
        if (!on) {
            clear()
            return
        }
        val id = slotOf(row, col)
        if (id < 0) {
            count = 0
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (count > 0 && now - startedAt > WINDOW_MS) count = 0
        buf[count] = id
        count++
        if (matchingCode(count) < 0) {
            // Not the next tap of a code: this tap may be the first tap of one, otherwise start over.
            buf[0] = id
            count = 1
            if (matchingCode(1) < 0) {
                count = 0
                return
            }
            startedAt = now
        } else if (count == 1) {
            startedAt = now
        }
        if (count == CODE_LEN) {
            val kind = matchingCode(count) + 1
            count = 0
            arm(kind, ready)
        }
    }

    /**
     * Call when the dice are tapped to roll. Returns the helper to roll with (0 = a normal roll) and
     * handles the "once in a row" lock for the double six.
     */
    fun takeForRoll(): Int {
        val kind = gold
        gold = CheatKind.NONE
        count = 0
        sixLocked = kind == CheatKind.SIX
        return kind
    }

    private fun arm(kind: Int, ready: (Int) -> Boolean) {
        if ((kind == CheatKind.SIX && sixLocked) || !ready(kind)) {
            vibrate(longArrayOf(0L, 140L))   // one long buzz: not possible right now
            return
        }
        gold = kind
        vibrate(LongArray(kind * 2) { i -> if (i == 0) 0L else if (i % 2 == 1) 45L else 85L })
    }

    /** Index (0..2) of the code whose first [n] taps equal the taps made so far, or -1. */
    private fun matchingCode(n: Int): Int {
        for (k in CODES.indices) {
            var same = true
            for (i in 0 until n) {
                if (CODES[k][i] != buf[i]) {
                    same = false
                    break
                }
            }
            if (same) return k
        }
        return -1
    }

    private fun vibrate(pattern: LongArray) {
        if (!Sounds.vibrationOn || Sounds.quiet) return
        val ctx = Sounds.appContext ?: return
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vib == null || !vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                vib.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            // ignore: vibration is optional
        }
    }

    companion object {
        private const val CODE_LEN = 4
        private const val WINDOW_MS = 3000L

        /**
         * Which seed circle a tap is on: house * 4 + corner. Houses: 0 green (top-left), 1 yellow (top-right),
         * 2 red (bottom-left), 3 blue (bottom-right). Corners inside a house: 0 top-left, 1 top-right,
         * 2 bottom-left, 3 bottom-right. Returns -1 for a tap outside the four houses.
         */
        internal fun slotOf(row: Float, col: Float): Int {
            val top = row >= 0f && row < 6f
            val bottom = row >= 9f && row < 15f
            val left = col >= 0f && col < 6f
            val right = col >= 9f && col < 15f
            val house = when {
                top && left -> 0
                top && right -> 1
                bottom && left -> 2
                bottom && right -> 3
                else -> return -1
            }
            val r = if (bottom) row - 9f else row
            val c = if (right) col - 9f else col
            val corner = (if (r >= 3f) 2 else 0) + (if (c >= 3f) 1 else 0)
            return house * 4 + corner
        }

        /**
         * The three codes, four taps each (house * 4 + corner).
         * Double Six: green TR, red TR, blue TL, yellow BL.
         * Kill dice:  yellow TL, blue BR, green BL, red BR.
         * Home dice:  blue TR, green TL, yellow BR, red TL.
         */
        private val CODES: List<IntArray> = listOf(
            intArrayOf(1, 9, 12, 6),
            intArrayOf(4, 15, 2, 11),
            intArrayOf(13, 0, 7, 8)
        )
    }
}
