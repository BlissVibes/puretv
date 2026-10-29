package com.puretv.twitch.desktop.data

import com.puretv.twitch.desktop.platform.AppPaths

import com.puretv.twitch.core.notify.LiveNotifyState
import com.puretv.twitch.core.notify.LiveNotifyStateCodec
import com.puretv.twitch.core.notify.LiveNotifyStateStore
import java.io.File

/**
 * Go-live alert memory (which broadcasts were already seen) in `live-notify.json`
 * next to the other desktop stores, so a restart never announces a stream twice.
 * Written off-thread through [AtomicJsonWriter] like [WatchProgressStore]; a file
 * that will not parse is set aside and the alerts simply re-seed silently.
 */
class LiveNotifyFileStore(appDataDir: File = defaultDir()) : LiveNotifyStateStore {
    private val file = File(appDataDir, "live-notify.json")
    private val writer = AtomicJsonWriter(file)

    init { runCatching { file.parentFile?.mkdirs() } }

    override suspend fun load(): LiveNotifyState {
        if (!file.exists()) return LiveNotifyState()
        val text = runCatching { file.readText() }.getOrNull() ?: return LiveNotifyState()
        return LiveNotifyStateCodec.decodeOrNull(text) ?: run {
            AtomicFile.quarantineCorrupt(file)
            LiveNotifyState()
        }
    }

    override suspend fun save(state: LiveNotifyState) {
        writer.enqueue { LiveNotifyStateCodec.encode(state) }
    }

    /** Block until pending writes are durable. Tests and shutdown only. */
    fun flush() = writer.flush()

    private companion object {
        fun defaultDir(): File = AppPaths.dataDir
    }
}
