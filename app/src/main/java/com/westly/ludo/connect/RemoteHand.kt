package com.westly.ludo.connect

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.westly.ludo.game.HandTarget
import com.westly.ludo.game.LudoColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One pointing hand to show: where it goes and the colour whose side it enters from. */
class RemoteHand(val target: HandTarget, val owner: LudoColor)

/**
 * Plays the pointing hand of the OTHER players on this phone (Connect and Play).
 *
 * Hands come in one after the other from a small queue, so two quick hand events never trample each
 * other. A hand that waited too long is dropped (it would only confuse), and the queue is short so it
 * can never grow without bound. The game screen reads [current] and draws it with the same HandGuide
 * the computer player uses; this class only decides what to show and for how long.
 * It needs no frame clock (it only waits), so any main-thread scope will do.
 */
class RemoteHandPlayer(private val scope: CoroutineScope) {

    /** The hand to show now, or null when the hand should leave. */
    var current by mutableStateOf<RemoteHand?>(null)
        private set

    /** The side the hand last came from. Kept while [current] is null so the hand can leave the same way. */
    var owner by mutableStateOf(LudoColor.GREEN)
        private set

    /** Goes up when the same target is shown twice in a row, so the hand goes out and comes in again. */
    var epoch by mutableIntStateOf(0)
        private set

    /** When the newest hand was queued (SystemClock.elapsedRealtime), or 0 if none yet. */
    var lastQueuedAt: Long = 0L
        private set

    private class Item(val hand: RemoteHand, val at: Long)

    private val queue = ArrayDeque<Item>()
    private var job: Job? = null

    /** Queues a hand. Called on the main thread. */
    fun add(target: HandTarget, owner: LudoColor) {
        val now = SystemClock.elapsedRealtime()
        lastQueuedAt = now
        while (queue.size >= MAX_QUEUE) queue.removeFirst()
        queue.addLast(Item(RemoteHand(target, owner), now))
        if (job?.isActive != true) job = scope.launch { run() }
    }

    private suspend fun run() {
        while (true) {
            val item = queue.removeFirstOrNull() ?: break
            if (SystemClock.elapsedRealtime() - item.at > STALE_MS) continue
            val prev = current
            if (prev != null && sameTarget(prev.target, item.hand.target) && prev.owner == item.hand.owner) {
                // The same spot again: let the hand leave first, then come in again.
                current = null
                delay(RETRIGGER_GAP_MS)
                epoch++
            }
            owner = item.hand.owner
            current = item.hand
            delay(HOLD_MS)
        }
        current = null
    }

    /** Drops everything: used when the game ends or a new game starts. */
    fun clear() {
        job?.cancel()
        job = null
        queue.clear()
        current = null
        lastQueuedAt = 0L
    }

    private fun sameTarget(a: HandTarget, b: HandTarget): Boolean = when {
        a === HandTarget.Dice && b === HandTarget.Dice -> true
        a is HandTarget.Orb && b is HandTarget.Orb -> a.index == b.index
        a is HandTarget.Spot && b is HandTarget.Spot -> a.row == b.row && a.col == b.col
        else -> false
    }

    companion object {
        /** How long a hand stays: the glide (about half a second) plus one tap. */
        const val HOLD_MS = 1100L

        /** A hand that waited longer than this in the queue is skipped. */
        const val STALE_MS = 3000L

        /** Pause between two identical hands. */
        private const val RETRIGGER_GAP_MS = 300L

        private const val MAX_QUEUE = 6
    }
}
