package com.puretv.twitch.android.player

import android.net.Uri
import androidx.media3.common.MediaMetadata
import com.puretv.twitch.core.model.ChannelInfo
import com.puretv.twitch.core.model.StreamInfo

/**
 * What the media notification and lock screen say about a stream: the stream
 * title over the channel name, with the channel avatar as artwork, the way a
 * video app shows a video over its channel. Both lookups can fail on a flaky
 * network, so every field falls back to something still worth reading rather
 * than a blank notification.
 */
internal data class NowPlaying(val title: String, val artist: String?, val artworkUrl: String?) {

    fun toMediaMetadata(): MediaMetadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setArtworkUri(artworkUrl?.let(Uri::parse))
        .build()

    companion object {
        fun of(channelLogin: String, channel: ChannelInfo?, stream: StreamInfo?): NowPlaying {
            val name = channel?.displayName?.takeIf { it.isNotBlank() }
                ?: stream?.userName?.takeIf { it.isNotBlank() }
                ?: channelLogin
            val streamTitle = stream?.title?.trim()?.takeIf { it.isNotEmpty() }
            return NowPlaying(
                title = streamTitle ?: name,
                // With no stream title the name is already the headline, so the
                // second line carries the category instead of repeating it.
                artist = if (streamTitle != null) name else stream?.gameName?.takeIf { it.isNotBlank() },
                artworkUrl = channel?.profileImageUrl?.takeIf { it.isNotBlank() },
            )
        }
    }
}
