package com.puretv.twitch.android.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * SECTION 09.1 — Room schema. Five tables backing offline browsing,
 * watch-history continuity, search suggestions, and emote caching so the
 * app stays responsive between cold starts and on flaky connections.
 */

@Entity(tableName = "cached_streams")
data class CachedStream(
    @PrimaryKey val channelLogin: String,
    val channelDisplayName: String,
    val title: String,
    val gameName: String,
    val viewerCount: Long,
    val thumbnailUrl: String,
    val fetchedAtEpochMs: Long,
)

@Entity(tableName = "cached_channels")
data class CachedChannel(
    @PrimaryKey val channelLogin: String,
    val id: String,
    val displayName: String,
    val description: String,
    val profileImageUrl: String,
    val fetchedAtEpochMs: Long,
)

@Entity(tableName = "cached_emotes")
data class CachedEmote(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val scopeKey: String, // "global" or a channel id
    val code: String,
    val imageUrl: String,
    val provider: String,
    val animated: Boolean,
    val fetchedAtEpochMs: Long,
)

@Entity(tableName = "watch_history")
data class WatchHistoryEntry(
    @PrimaryKey val channelLogin: String,
    val channelDisplayName: String,
    val lastWatchedEpochMs: Long,
    val totalWatchTimeMs: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntry(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val query: String,
    val searchedAtEpochMs: Long,
)

@Dao
interface CachedStreamDao {
    @Query("SELECT * FROM cached_streams ORDER BY viewerCount DESC")
    fun observeAll(): Flow<List<CachedStream>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(streams: List<CachedStream>)

    @Query("DELETE FROM cached_streams WHERE fetchedAtEpochMs < :olderThanEpochMs")
    suspend fun pruneStaleEntries(olderThanEpochMs: Long)
}

@Dao
interface CachedChannelDao {
    @Query("SELECT * FROM cached_channels WHERE channelLogin = :login LIMIT 1")
    suspend fun findByLogin(login: String): CachedChannel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: CachedChannel)
}

@Dao
interface CachedEmoteDao {
    @Query("SELECT * FROM cached_emotes WHERE scopeKey = :scopeKey")
    suspend fun forScope(scopeKey: String): List<CachedEmote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(emotes: List<CachedEmote>)

    @Query("DELETE FROM cached_emotes WHERE scopeKey = :scopeKey")
    suspend fun clearScope(scopeKey: String)
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedEpochMs DESC LIMIT 50")
    fun observeRecent(): Flow<List<WatchHistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntry)

    @Query("UPDATE watch_history SET totalWatchTimeMs = totalWatchTimeMs + :deltaMs, lastWatchedEpochMs = :nowEpochMs WHERE channelLogin = :login")
    suspend fun addWatchTime(login: String, deltaMs: Long, nowEpochMs: Long)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAtEpochMs DESC LIMIT 20")
    fun observeRecent(): Flow<List<SearchHistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: SearchHistoryEntry)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

/**
 * Where the viewer got to in one past broadcast, mirroring core's
 * `WatchProgress` (see VodProgressMappers) so the resume rules are the
 * Windows ones. The metadata lets Home draw a card without an API call.
 */
@Entity(tableName = "vod_progress")
data class VodProgressEntity(
    @PrimaryKey val vodId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val title: String,
    val channelLogin: String,
    val thumbnailUrl: String,
)

@Dao
interface VodProgressDao {
    @Query("SELECT * FROM vod_progress WHERE vodId = :vodId LIMIT 1")
    suspend fun get(vodId: String): VodProgressEntity?

    @Query("SELECT * FROM vod_progress ORDER BY updatedAt DESC LIMIT 50")
    fun observeRecent(): Flow<List<VodProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: VodProgressEntity)

    @Query("DELETE FROM vod_progress WHERE vodId = :vodId")
    suspend fun delete(vodId: String)

    /** Keeps the 300 most recent, the same bound as the Windows store. */
    @Query("DELETE FROM vod_progress WHERE vodId NOT IN (SELECT vodId FROM vod_progress ORDER BY updatedAt DESC LIMIT 300)")
    suspend fun prune()
}

/**
 * Must match Room's own `createSql` for [VodProgressEntity] in
 * `schemas/.../2.json` character for character (VodProgressMigrationTest checks
 * it), or Room rejects the migrated database at open.
 */
const val VOD_PROGRESS_CREATE_SQL =
    "CREATE TABLE IF NOT EXISTS `vod_progress` (`vodId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
        "`durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
        "`channelLogin` TEXT NOT NULL, `thumbnailUrl` TEXT NOT NULL, PRIMARY KEY(`vodId`))"

/** Version 2 adds VOD resume. Additive only, so every existing row survives. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(VOD_PROGRESS_CREATE_SQL)
    }
}

@Database(
    entities = [
        CachedStream::class,
        CachedChannel::class,
        CachedEmote::class,
        WatchHistoryEntry::class,
        SearchHistoryEntry::class,
        VodProgressEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class PureTvDatabase : RoomDatabase() {
    abstract fun cachedStreamDao(): CachedStreamDao
    abstract fun cachedChannelDao(): CachedChannelDao
    abstract fun cachedEmoteDao(): CachedEmoteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun vodProgressDao(): VodProgressDao

    companion object {
        const val DB_NAME = "puretv.db"
    }
}
