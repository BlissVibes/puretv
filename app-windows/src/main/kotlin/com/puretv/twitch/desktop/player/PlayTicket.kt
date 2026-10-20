package com.puretv.twitch.desktop.player

import java.util.concurrent.atomic.AtomicLong

/**
 * Tells a stale stop from a current one when one screen's player hand-off overlaps
 * another's.
 *
 * The player is one shared singleton. Going from one stream to the next (a flip, a click
 * in the followed rail) builds the new screen's view model before the old one is cleaned
 * up, and the old one's cleanup stops the player. If that stop lands after the new view
 * model asked for its stream, the new stream is stopped instead of the old one. Each
 * view model takes a ticket when it is built; on cleanup it stops the player only if
 * its ticket is still the newest, so a newer screen's stream is never stopped by an
 * older screen going away.
 */
class PlayTicket {
    private val newest = AtomicLong()

    /** A ticket for a screen that is about to own the player. */
    fun issue(): Long = newest.incrementAndGet()

    /** True while no newer screen has taken the player since [ticket] was issued. */
    fun isCurrent(ticket: Long): Boolean = newest.get() == ticket

    /** Runs [stop] only if [ticket] is still the newest. Returns whether it ran. */
    fun stopIfCurrent(ticket: Long, stop: () -> Unit): Boolean {
        if (!isCurrent(ticket)) return false
        stop()
        return true
    }
}
