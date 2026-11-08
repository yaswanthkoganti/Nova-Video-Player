package com.nova.player.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Seek-bar preview frames (cached in 3-second buckets, decoded on one background thread). */
class ThumbProvider(private val ctx: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val cache = LruCache<Long, Bitmap>(80)
    private var mmr: MediaMetadataRetriever? = null
    private var source: Uri? = null
    private var dw = 320
    private var dh = 180

    suspend fun setSource(uri: Uri) = withContext(dispatcher) {
        cache.evictAll(); runCatching { mmr?.release() }; mmr = null; source = uri
    }

    suspend fun frameAt(ms: Long): Bitmap? = withContext(dispatcher) {
        val key = ms / 3000
        cache.get(key)?.let { return@withContext it }
        val r = mmr ?: open() ?: return@withContext null
        val us = key * 3_000_000
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dw, dh)
            else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, dw, dh, true) }
        }.getOrNull()
        bmp?.also { cache.put(key, it) }
    }

    private fun open(): MediaMetadataRetriever? = runCatching {
        val u = source ?: return null
        val r = MediaMetadataRetriever()
        if (u.scheme == "http" || u.scheme == "https") r.setDataSource(u.toString(), HashMap()) else r.setDataSource(ctx, u)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
        dw = 320; dh = (320f * h / w).toInt().coerceAtLeast(40)
        mmr = r; r
    }.getOrNull()

    fun release() { runCatching { mmr?.release() }; mmr = null; executor.shutdown() }
}
