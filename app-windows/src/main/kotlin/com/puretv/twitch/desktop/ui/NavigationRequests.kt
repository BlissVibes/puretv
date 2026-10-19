package com.puretv.twitch.desktop.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Requests that reach the window from outside it: a second launch asking the
 * running copy to show itself (see SingleInstance), and a click on a go-live
 * notification asking for a stream. Routing itself stays in App; this only
 * carries the asks.
 *
 * Conflated channels rather than shared flows, so a request made before the
 * window has started collecting (a launch during a `--background` start) waits
 * for it instead of being dropped. Only the latest of each matters.
 */
class NavigationRequests {
    private val show = Channel<Unit>(Channel.CONFLATED)
    private val stream = Channel<String>(Channel.CONFLATED)

    /** Bring the window back (from the tray, or from behind other windows). */
    val showWindow: Flow<Unit> = show.receiveAsFlow()

    /** Open this channel's stream. Always preceded by a [showWindow] request. */
    val openStream: Flow<String> = stream.receiveAsFlow()

    fun requestShow() {
        show.trySend(Unit)
    }

    fun requestStream(login: String) {
        show.trySend(Unit)
        stream.trySend(login)
    }
}
