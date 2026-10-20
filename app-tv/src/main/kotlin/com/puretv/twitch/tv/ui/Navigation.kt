package com.puretv.twitch.tv.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.puretv.twitch.tv.ui.screens.TvVodScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.puretv.twitch.tv.ui.screens.TvBrowseScreen
import com.puretv.twitch.tv.ui.screens.TvCategoryScreen
import com.puretv.twitch.tv.ui.screens.TvChannelScreen
import com.puretv.twitch.tv.ui.screens.TvFollowingScreen
import com.puretv.twitch.tv.ui.screens.TvHomeScreen
import com.puretv.twitch.tv.ui.screens.TvLoginScreen
import com.puretv.twitch.tv.ui.screens.TvSearchScreen
import com.puretv.twitch.tv.ui.screens.TvSettingsScreen
import com.puretv.twitch.tv.ui.screens.TvStreamScreen

/**
 * SECTION 07.2 — TV route table. Mirrors the phone app's [Routes] one-for-one
 * (so `core` repositories/ViewModID parametrization patterns line up) but
 * hosts purpose-built TV composables — see Section 12.2: app-tv shares no UI
 * with app-android.
 *
 *   TvHomeScreen     → Nav rail (Live/Following/Categories/Search/Settings) + content rows
 *   TvFollowingScreen → Following's own destination: live follows in a card
 *                        grid, everyone else in a focusable row list, its own
 *                        copy of the nav rail (same LEFT-to-rail behaviour as Home)
 *   TvBrowseScreen   → Category/game grid, D-pad navigable
 *   TvSearchScreen   → On-screen-keyboard-driven search
 *   TvStreamScreen   → Immersive fullscreen player + auto-hide controls + chat overlay
 *   TvChannelScreen  → Channel profile + watch CTA
 *   TvSettingsScreen → Quality / ad-block / proxy / account, focusable rows
 *   TvLoginScreen    → OAuth entry point (same PKCE flow + redirect scheme as phone)
 */
object Routes {
    const val HOME = "home"
    const val FOLLOWING = "following"
    const val BROWSE = "browse"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val STREAM = "stream/{channelLogin}"
    const val CHANNEL = "channel/{channelLogin}"
    const val CATEGORY = "category/{gameId}"
    const val VOD = "vod/{vodId}?login={login}&title={title}&thumb={thumb}"

    fun stream(channelLogin: String) = "stream/$channelLogin"
    fun channel(channelLogin: String) = "channel/$channelLogin"
    fun category(gameId: String) = "category/$gameId"

    /** A past broadcast. Titles carry spaces, slashes and ampersands, so every value is encoded. */
    fun vod(launch: VodLaunch) =
        "vod/${Uri.encode(launch.vodId)}?login=${Uri.encode(launch.channelLogin)}" +
            "&title=${Uri.encode(launch.title)}&thumb=${Uri.encode(launch.thumbnailUrl)}"
}

@Composable
fun PureTvTvNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            TvHomeScreen(
                onOpenStream = { navController.navigate(Routes.stream(it)) },
                onOpenChannel = { navController.navigate(Routes.channel(it)) },
                onOpenVod = { navController.navigate(Routes.vod(it)) },
                onOpenFollowing = { navController.navigate(Routes.FOLLOWING) },
                onOpenBrowse = { navController.navigate(Routes.BROWSE) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenLogin = { navController.navigate(Routes.LOGIN) },
            )
        }
        composable(Routes.FOLLOWING) {
            TvFollowingScreen(
                onOpenChannel = { navController.navigate(Routes.channel(it)) },
                onOpenHome = navController::popBackStack,
                onOpenBrowse = { navController.navigate(Routes.BROWSE) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenLogin = { navController.navigate(Routes.LOGIN) },
            )
        }
        composable(Routes.BROWSE) {
            TvBrowseScreen(onOpenCategory = { navController.navigate(Routes.category(it)) }, onBack = navController::popBackStack)
        }
        composable(Routes.CATEGORY) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString("gameId").orEmpty()
            TvCategoryScreen(
                gameId = gameId,
                onOpenStream = { navController.navigate(Routes.stream(it)) },
                onBack = navController::popBackStack,
            )
        }
        composable(Routes.SEARCH) {
            TvSearchScreen(
                onOpenChannel = { navController.navigate(Routes.channel(it)) },
                onBack = navController::popBackStack,
            )
        }
        composable(Routes.SETTINGS) {
            TvSettingsScreen(onBack = navController::popBackStack)
        }
        composable(Routes.LOGIN) {
            TvLoginScreen(onLoggedIn = { navController.popBackStack() }, onBack = navController::popBackStack)
        }
        composable(Routes.STREAM) { backStackEntry ->
            val channelLogin = backStackEntry.arguments?.getString("channelLogin").orEmpty()
            TvStreamScreen(
                channelLogin = channelLogin,
                onBack = navController::popBackStack,
                // The sleep timer ran out: back to Home, not to whichever screen opened the stream.
                onSleepExpired = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.CHANNEL) { backStackEntry ->
            val channelLogin = backStackEntry.arguments?.getString("channelLogin").orEmpty()
            TvChannelScreen(
                channelLogin = channelLogin,
                onWatch = { navController.navigate(Routes.stream(channelLogin)) },
                onOpenVod = { navController.navigate(Routes.vod(it)) },
                onBack = navController::popBackStack,
            )
        }
        composable(
            Routes.VOD,
            arguments = listOf(
                navArgument("vodId") { type = NavType.StringType },
                navArgument("login") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("thumb") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val launch = VodLaunch(
                vodId = args?.getString("vodId").orEmpty(),
                channelLogin = args?.getString("login").orEmpty(),
                title = args?.getString("title").orEmpty(),
                thumbnailUrl = args?.getString("thumb").orEmpty(),
            )
            TvVodScreen(launch = launch, onBack = navController::popBackStack)
        }
    }
}
