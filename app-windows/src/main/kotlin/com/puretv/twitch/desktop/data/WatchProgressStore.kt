package com.puretv.twitch.desktop.data

import com.puretv.twitch.desktop.platform.AppPaths

import com.puretv.twitch.core.vod.ResumePolicy
import com.puretv.twitch.core.vod.WatchProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local per-VOD playback positions (the "continue watching" backing store).
 * Plaintext JSON next to the other desktop stores; nothing sensitive. The data
 * dir is injectable so tests point at a temp dir — mirrors [FollowStore].
 */
class WatchProgressStore(appDataDir: File = defaultDir()) {
    private val file = File(appDataDir, "watch-progress.json")
    // Compact JSON: machine-only file; prettyPrint doubled bytes + CPU on every write.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writer = AtomicJsonWriter(file)

    // Serializes the read-derive-write sections (the 10s autosave loop, the
    // onCleared save, and explicit removes can otherwise interleave — audit F2).
    private val lock = Any()

    private val _progress = MutableStateFlow(loadFromDisk())
    val progress: StateFlow<Map<String, WatchProgress>> = _progress.asStateFlow()

    init { runCatching { file.parentFile?.mkdirs() } }

    fun get(vodId: String): WatchProgress? = _progress.value[vodId]

    fun save(p: WatchProgress) = synchronized(lock) { persistLocked(capped(_progress.value + (p.vodId to p))) }

    fun remove(vodId: String) = synchronized(lock) { persistLocked(_progress.value - vodId) }

    /** Resumable entries (per [ResumePolicy]), newest first. */
    fun continueWatching(): List<WatchProgress> =
        _progress.value.values
            .filter { ResumePolicy.resumePositionMs(it) != null }
            .sortedByDescending { it.updatedAt }

    /**
     * Bound the store (audit F3): an unbounded map is loaded into memory on
     * startup and rewritten in full on every 10s autosave, so cost grows with
     * watch history forever. Keep only the [MAX_ENTRIES] most-recently-updated
     * VODs — far more than any "continue watching" surface shows.
     */
    private fun capped(map: Map<String, WatchProgress>): Map<String, WatchProgress> =
        if (map.size <= MAX_ENTRIES) {
            map
        } else {
            map.entries.sortedByDescending { it.value.updatedAt }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        }

    private fun persistLocked(map: Map<String, WatchProgress>) {
        // In-memory update is synchronous; serialize + fsync run off-thread so the
        // continue-watching "remove" click and the autosave loop never block.
        _progress.value = map
        writer.enqueue { json.encodeToString(map) }
    }

    /** Block until pending writes are durable. Tests + shutdown only — never the UI thread. */
    fun flush() = writer.flush()

    private fun loadFromDisk(): Map<String, WatchProgress> {
        if (!file.exists()) return emptyMap()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, WatchProgress>>(text) }
            .getOrElse {
                // Preserve the unparseable bytes before falling back (audit F3).
                AtomicFile.quarantineCorrupt(file)
                emptyMap()
            }
    }

    companion object {
        private const val MAX_ENTRIES = 300

        private fun defaultDir(): File =
            AppPaths.dataDir
    }
}
