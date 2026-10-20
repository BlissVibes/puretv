package com.puretv.twitch.android.player

/**
 * Who the shared player is playing for. The player is one app-wide instance, but
 * stream screens can stack (a go-live alert tapped while watching another channel
 * opens a second one on top). When the top one is popped the one below plays again
 * and takes the player back, and only then does the popped screen's view model
 * clean up. That late clean-up must not stop what the other screen just started,
 * so a view model may stop the player only while it is still the owner.
 */
class PlayOwnership<T : Any> {
    private var owner: T? = null

    /** Makes [candidate] the owner. True if that is a change of owner. */
    @Synchronized
    fun claim(candidate: T): Boolean {
        val changed = owner !== candidate
        owner = candidate
        return changed
    }

    /** True if [candidate] was the owner; it no longer is. A stale caller gets false and changes nothing. */
    @Synchronized
    fun release(candidate: T): Boolean {
        if (owner !== candidate) return false
        owner = null
        return true
    }
}
