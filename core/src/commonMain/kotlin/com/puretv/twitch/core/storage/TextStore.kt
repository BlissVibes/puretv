package com.puretv.twitch.core.storage

/**
 * A small named-text store for caches that should survive a restart (for
 * example the emote lists, so chat shows emotes on its very first lines).
 * Each platform backs it with plain files in its own cache folder; see
 * `FileTextStore` in desktopMain and androidMain. Implementations are
 * best-effort: a read that fails is a miss, and a write that fails is dropped.
 */
interface TextStore {
    suspend fun read(name: String): String?
    suspend fun write(name: String, text: String)
}
