package com.puretv.twitch.desktop.ui

/**
 * Where a PureTV window can be told to go: the vocabulary of "open in a new window" and
 * of the go-live toast. Kept apart from the private routes of [App] so other parts of the
 * app (video cards, the tray) can name a screen without knowing how the window shows it.
 */
sealed interface WindowDestination {
    /** The Home tab. */
    data object Home : WindowDestination

    /** A channel's page. */
    data class Channel(val login: String) : WindowDestination

    /** A channel's live stream, playing. */
    data class Stream(val login: String) : WindowDestination

    /** A video (past broadcast, highlight or clip) playing, with what the resume entry needs. */
    data class Vod(
        val videoId: String,
        val channelLogin: String = "",
        val title: String = "",
        val thumbnailUrl: String = "",
    ) : WindowDestination

    /** A category's page; [name] is shown as its title while it loads. */
    data class Category(val id: String, val name: String = "") : WindowDestination

    /** The Search tab, with [query] already typed in. */
    data class Search(val query: String = "") : WindowDestination
}

/** What a window starts on, or moves to, for a [WindowDestination]: a tab plus a screen on top of it. */
internal data class StartScreen(
    val tab: Destination,
    val route: Route,
    /** Set only for Search: the text to put in the search box. */
    val searchQuery: String? = null,
)

/** True for the destinations that start a video player, which count toward the player cap. */
fun WindowDestination.startsPlayer(): Boolean = this is WindowDestination.Stream || this is WindowDestination.Vod

/**
 * The tab and screen a window shows for this destination. Anything that names nothing
 * (a blank login or video id) falls back to Home instead of opening an empty screen.
 */
internal fun WindowDestination.toStartScreen(): StartScreen = when (this) {
    WindowDestination.Home -> StartScreen(Destination.HOME, Route.Top)
    is WindowDestination.Channel -> loginOrHome(login) { StartScreen(Destination.HOME, Route.Channel(it)) }
    is WindowDestination.Stream -> loginOrHome(login) { StartScreen(Destination.HOME, Route.Stream(it)) }
    is WindowDestination.Vod ->
        if (videoId.isBlank()) StartScreen(Destination.HOME, Route.Top)
        else StartScreen(
            Destination.HOME,
            Route.Vod(VodLaunch(videoId.trim(), channelLogin.trim().lowercase(), title, thumbnailUrl)),
        )
    is WindowDestination.Category ->
        if (id.isBlank()) StartScreen(Destination.BROWSE, Route.Top)
        else StartScreen(Destination.BROWSE, Route.Category(id.trim(), name.ifBlank { "Category" }))
    is WindowDestination.Search -> StartScreen(Destination.SEARCH, Route.Top, query.trim().ifEmpty { null })
}

private inline fun loginOrHome(login: String, build: (String) -> StartScreen): StartScreen {
    val clean = login.trim().removePrefix("@").lowercase()
    return if (clean.isEmpty()) StartScreen(Destination.HOME, Route.Top) else build(clean)
}

/** The main window's title; a second launch finds the running copy by it. */
const val PRIMARY_WINDOW_TITLE = "PureTV for Twitch"

/**
 * The words in a window's title for the screen it shows, so Alt+Tab tells windows apart.
 * Null on the Home tab, which needs no extra words.
 */
internal fun screenName(route: Route, tab: Destination): String? = when (route) {
    Route.Top -> if (tab == Destination.HOME) null else tab.label
    is Route.Category -> route.gameName
    is Route.Channel -> route.login
    is Route.Stream -> "${route.login} (live)"
    is Route.Multi -> "Multi-stream"
    is Route.Vod -> route.launch.title.ifBlank { "Video" }
}

/**
 * The OS title of a window. The main window keeps the plain app name on Home (a second
 * launch looks for it by that name); other windows are told apart by their number.
 */
fun windowTitle(slot: Int, screen: String?): String {
    val base = if (slot == 0) PRIMARY_WINDOW_TITLE else "PureTV $slot"
    return if (screen.isNullOrBlank()) base else "$screen - $base"
}
