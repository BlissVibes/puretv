package com.puretv.twitch.android.notify

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.puretv.twitch.core.notify.LiveNotifyState
import com.puretv.twitch.core.notify.LiveNotifyStateCodec
import com.puretv.twitch.core.notify.LiveNotifyStateStore
import kotlinx.coroutines.flow.first

// Its own file, apart from puretv_settings: this is rewritten after every poll and
// is not a preference. A corrupt file resets to empty, which only means the next
// poll re-seeds silently (nobody is announced), never a flood.
private val Context.liveNotifyStore by preferencesDataStore(
    name = "puretv_live_notify",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Keeps what the go-live detector has already seen, so a restart never re-announces a stream. */
class DataStoreLiveNotifyStore(private val context: Context) : LiveNotifyStateStore {
    private val stateKey = stringPreferencesKey("state")

    override suspend fun load(): LiveNotifyState =
        LiveNotifyStateCodec.decode(runCatching { context.liveNotifyStore.data.first()[stateKey] }.getOrNull())

    override suspend fun save(state: LiveNotifyState) {
        context.liveNotifyStore.edit { it[stateKey] = LiveNotifyStateCodec.encode(state) }
    }
}
