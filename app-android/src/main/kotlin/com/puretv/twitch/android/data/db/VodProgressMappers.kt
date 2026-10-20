package com.puretv.twitch.android.data.db

import com.puretv.twitch.core.vod.WatchProgress

/** Room row to the shared core model, so ResumePolicy applies the same rules on every app. */
fun VodProgressEntity.toCore(): WatchProgress = WatchProgress(
    vodId = vodId,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    title = title,
    channelLogin = channelLogin,
    thumbnailUrl = thumbnailUrl,
)

fun WatchProgress.toEntity(): VodProgressEntity = VodProgressEntity(
    vodId = vodId,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    title = title,
    channelLogin = channelLogin,
    thumbnailUrl = thumbnailUrl,
)