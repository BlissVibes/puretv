package com.puretv.twitch.core.repository

import com.puretv.twitch.core.api.TwitchApiClient
import com.puretv.twitch.core.api.VideoPage
import com.puretv.twitch.core.model.VideoType
import com.puretv.twitch.core.stream.MasterPlaylistResult
import com.puretv.twitch.core.stream.TwitchGqlDirectoryClient
import com.puretv.twitch.core.stream.VodResolver

/**
 * Discovery + playback resolution for past videos. Parallel to
 * [StreamRepository]; kept separate so live and archive concerns don't tangle.
 */
class VodRepository(
    private val apiClient: TwitchApiClient,
    private val vodResolver: VodResolver,
    // Signed-out browsing (D5.1): with no user token a channel's past videos come
    // from the anonymous GQL directory. Null keeps every read on Helix.
    private val directory: TwitchGqlDirectoryClient? = null,
) {
    /**
     * Signed out this is a single page with a null cursor: anonymous GQL refuses
     * every `after` cursor ("failed integrity check"), so there is no next page.
     */
    suspend fun videosFor(
        userId: String,
        type: VideoType? = null,
        first: Int = 20,
        after: String? = null,
    ): VideoPage {
        if (userId.isBlank()) return VideoPage(emptyList(), null)
        val anonymous = directory?.takeIf { !apiClient.hasUserToken() }
        if (anonymous != null) {
            // A cursor can only come from a Helix page, so there is nothing to continue.
            if (after != null) return VideoPage(emptyList(), null)
            return VideoPage(anonymous.videosForUser(userId, type, first), null)
        }
        return apiClient.getVideos(userId, type, first, after)
    }

    /**
     * One video's title, channel, kind and art by id, for a video named only by a pasted
     * link. Signed in it asks Helix; signed out, or when Helix has nothing or fails, it asks
     * the anonymous directory. Null when neither knows the video.
     */
    suspend fun videoById(videoId: String): com.puretv.twitch.core.model.VideoInfo? {
        if (videoId.isBlank()) return null
        if (apiClient.hasUserToken()) {
            runCatching { apiClient.getVideoById(videoId) }.getOrNull()?.let { return it }
        }
        return directory?.let { runCatching { it.videoById(videoId) }.getOrNull() }
    }

    suspend fun resolvePlayableVod(vodId: String, oauthToken: String? = null): MasterPlaylistResult =
        vodResolver.resolveVodMasterPlaylist(vodId, oauthToken)

    suspend fun loadStoryboard(vodId: String): com.puretv.twitch.core.stream.Storyboard? =
        vodResolver.loadStoryboard(vodId)

    suspend fun videoComments(vodId: String, offsetSeconds: Int): com.puretv.twitch.core.stream.CommentBatch =
        vodResolver.videoComments(vodId, offsetSeconds)
}
