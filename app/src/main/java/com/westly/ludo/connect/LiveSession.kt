package com.westly.ludo.connect

import com.westly.ludo.game.LudoColor

/**
 * What the in-game screen (LudoScreen) and its dialogs need from a live multiplayer session.
 *
 * Two classes implement it: [ConnectSession] (phones next to each other, Nearby Connections) and
 * OnlineSession (friends anywhere, Supabase Realtime). The screen only talks to this interface, so
 * both kinds of game look and play the same; only the way messages travel is different.
 *
 * The three members at the bottom have defaults so the Offline session did not need to change
 * for them: it is not online, has no room code, and can switch watchers on and off.
 */
interface LiveSession {

    // ----- Who is who -----

    /** True on the phone that runs the rules. */
    val isHost: Boolean

    /** The engine player this phone controls (0..3), or -1 for a watcher. */
    val myPlayer: Int

    /** The colours this phone plays, in the order they are named on screen. */
    val myColors: List<LudoColor>

    /** "You are Red", "You are Green + Blue" or "You are watching". */
    val youAreText: String

    /** 2, 3 or 4, including the host. */
    val playerCount: Int

    /** The host's name, for "Waiting for <HostName>...". */
    val hostName: String

    /** The name shown for an engine player; empty when nobody plays that colour. */
    fun playerName(player: Int): String

    /** The name of the person in [seat]. */
    fun seatName(seat: Int): String

    /** The seat that plays [player], or -1 when nobody does. */
    fun seatOfPlayer(player: Int): Int

    /** A person's running score (it follows the person, not the colour). */
    fun scoreOfSeat(seat: Int): Int

    // ----- State of the game on this phone -----

    /** The pointing hand of the OTHER players; the game screen draws [RemoteHandPlayer.current]. */
    val hand: RemoteHandPlayer

    /** This phone has no seat and only watches. */
    val watching: Boolean

    /** This person was knocked out of the tournament and only watches the rest. */
    val iAmOut: Boolean

    /** Host: how many watchers are connected. */
    val watcherCount: Int

    /** Host: whether watchers are allowed. */
    val allowWatchers: Boolean

    /** True when somebody was removed by the host (no new game can start then). */
    val hasRemoved: Boolean

    /** The seat everybody is waiting for to reconnect, or -1. */
    val waitingForSeat: Int

    /** A message that covers the game screen with one OK button (the host ended it, ...), or null. */
    val finalText: String?

    /** How the link to the host is doing on this phone. */
    val link: LinkState

    /** The text inside the join QR code (Offline host only). */
    val ticketText: String

    // ----- Taps from the game screen -----

    /** True when this person may act right now, judged from what is on screen. */
    fun canAct(): Boolean

    /** Host only: true when [seat] may be removed from the game right now. */
    fun canRemove(seat: Int): Boolean

    fun tapDie(i: Int)

    /** A tap on the board: the dice area rolls, a tap while choosing is a move. */
    fun tapBoard(row: Float, col: Float)

    /** A tap on a pawn in the pick pop-up. */
    fun tapPiece(tag: Any?)

    /** A tap on this person's own dice in the tie-break. */
    fun tapTie()

    /** Host only: Next Round on the round result. */
    fun tapNextRound()

    /** Host only: the next game of the same room. */
    fun tapNextGame()

    // ----- Leaving and host actions -----

    /** Leaves the game on this phone. */
    fun leave()

    /** Host: End Game / End Tournament for everybody. */
    fun endForEveryone()

    /** Host: takes [seat] out of the game. */
    fun removePlayer(seat: Int)

    /** Host: the Allow watchers switch. */
    fun switchWatchers(on: Boolean)

    /** The Try again button after the link could not be restored. */
    fun retryReconnect()

    // ----- Online additions (defaults keep Offline unchanged) -----

    /** True for a game played over the internet. */
    val isOnline: Boolean get() = false

    /** The room code to show and share (online only). */
    val onlineRoomCode: String? get() = null

    /** False online: watchers are chosen when the room is created and cannot be switched later. */
    val canToggleWatchers: Boolean get() = true

    /**
     * Online: what the covering panel says while [link] is not OK, for example "Reconnecting..." (this phone
     * lost its connection) or "Waiting for the host..." (the host's phone is gone). Null = the screen's own text.
     */
    val linkMessage: String? get() = null
}
