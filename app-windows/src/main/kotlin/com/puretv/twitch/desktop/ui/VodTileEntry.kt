package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.model.VideoInfo

/** A past video of [channelLogin] as a multi-stream tile source, starting at [startMs] when nothing better is saved. */
fun VideoInfo.toTileSource(channelLogin: String, startMs: Long = 0): TileSource.Vod = TileSource.Vod(
    videoId = id,
    title = title,
    channelLogin = userLogin.ifBlank { channelLogin },
    startPositionMs = startMs,
    kind = VodKind.from(type),
    thumbnailUrl = thumbnailUrl,
)

/** The video the player screen is playing as a tile source, picking up at [positionMs]. */
fun VodLaunch.toTileSource(positionMs: Long): TileSource.Vod = TileSource.Vod(
    videoId = vodId,
    title = title,
    channelLogin = channelLogin,
    startPositionMs = positionMs,
    thumbnailUrl = thumbnailUrl,
)

/** What to tell the viewer after a past video was sent to multi-stream ([popped]: into its own window). */
fun AddOutcome.note(popped: Boolean): String = when (this) {
    AddOutcome.Added -> if (popped) "Opened in its own window" else "Added to multi-stream"
    is AddOutcome.AlreadyShown -> if (popped) "Opened in its own window" else "Already in multi-stream"
    AddOutcome.Full -> "Four streams is the limit"
    AddOutcome.Blank -> "That video can't be added"
}
