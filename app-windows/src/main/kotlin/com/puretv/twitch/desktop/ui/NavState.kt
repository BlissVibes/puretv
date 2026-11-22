package com.puretv.twitch.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where a window is, plus the live stream that keeps playing in the corner while the
 * viewer browses ([dockedLogin]). Leaving a stream for a browsing screen docks it; opening
 * any player (another stream, a past video, multi-stream) takes over from the docked one,
 * because a window has one player. Setting [dockedLogin] to null closes it.
 */
internal class RouteHolder(initial: Route) {
    private var state by mutableStateOf(initial)

    var dockedLogin by mutableStateOf<String?>(null)

    var current: Route
        get() = state
        set(value) {
            dockedLogin = RouteDocking.next(state, value, dockedLogin)
            state = value
        }
}

internal object RouteDocking {
    /** The docked stream after moving from [from] to [to], given the one docked before. */
    fun next(from: Route, to: Route, docked: String?): String? = when {
        to is Route.Stream || to is Route.Multi || to is Route.Vod -> null
        from is Route.Stream -> from.login
        else -> docked
    }
}
