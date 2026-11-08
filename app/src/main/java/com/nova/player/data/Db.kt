package com.nova.player.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Everything we remember per video: position, speed, tracks, delays, aspect. */
@Entity
data class VideoState(
    @PrimaryKey val uri: String,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val audioId: String?,
    val subId: String?,
    val subDelayMs: Long,
    val audioDelayMs: Long,
    val resize: Int,
    val engine: String,
    val updated: Long,
)

@Entity
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val positionMs: Long,
    val label: String,
)

@Dao
interface NovaDao {
    @Query("SELECT * FROM VideoState WHERE uri = :u")
    suspend fun state(u: String): VideoState?

    @Upsert
    suspend fun save(s: VideoState)

    @Query("SELECT * FROM VideoState")
    fun allStates(): Flow<List<VideoState>>

    @Query("SELECT * FROM Bookmark WHERE uri = :u ORDER BY positionMs")
    fun bookmarks(u: String): Flow<List<Bookmark>>

    @Insert
    suspend fun add(b: Bookmark)

    @Delete
    suspend fun remove(b: Bookmark)
}

@Database(entities = [VideoState::class, Bookmark::class], version = 1, exportSchema = false)
abstract class NovaDb : RoomDatabase() {
    abstract fun dao(): NovaDao

    companion object {
        @Volatile private var inst: NovaDb? = null
        fun get(ctx: Context): NovaDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, NovaDb::class.java, "nova.db")
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
