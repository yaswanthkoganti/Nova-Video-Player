package com.nova.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoItem(
    val id: Long, val uri: Uri, val title: String, val durationMs: Long, val size: Long,
    val folder: String, val added: Long, val width: Int, val height: Int,
)

/** "Episode 2" sorts before "Episode 10". */
val Natural = Comparator<String> { a, b ->
    val split = Regex("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)")
    val x = a.split(split); val y = b.split(split)
    var r = 0
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]; val q = y[i]
        r = if (p.firstOrNull()?.isDigit() == true && q.firstOrNull()?.isDigit() == true)
            p.toBigInteger().compareTo(q.toBigInteger())
        else p.compareTo(q, ignoreCase = true)
        if (r != 0) break
    }
    if (r != 0) r else x.size - y.size
}

object MediaRepo {
    suspend fun load(ctx: Context): List<VideoItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<VideoItem>()
        val base = Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            Video.Media._ID, Video.Media.DISPLAY_NAME, Video.Media.DURATION, Video.Media.SIZE,
            Video.Media.BUCKET_DISPLAY_NAME, Video.Media.DATE_ADDED, Video.Media.WIDTH, Video.Media.HEIGHT,
        )
        ctx.contentResolver.query(base, proj, null, null, "${Video.Media.DATE_ADDED} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(Video.Media._ID)
            val name = c.getColumnIndexOrThrow(Video.Media.DISPLAY_NAME)
            val dur = c.getColumnIndexOrThrow(Video.Media.DURATION)
            val size = c.getColumnIndexOrThrow(Video.Media.SIZE)
            val bucket = c.getColumnIndexOrThrow(Video.Media.BUCKET_DISPLAY_NAME)
            val added = c.getColumnIndexOrThrow(Video.Media.DATE_ADDED)
            val w = c.getColumnIndexOrThrow(Video.Media.WIDTH)
            val h = c.getColumnIndexOrThrow(Video.Media.HEIGHT)
            while (c.moveToNext()) {
                val i = c.getLong(id)
                out += VideoItem(
                    i, ContentUris.withAppendedId(base, i), c.getString(name) ?: "Video",
                    c.getLong(dur), c.getLong(size), c.getString(bucket) ?: "Unknown",
                    c.getLong(added), c.getInt(w), c.getInt(h),
                )
            }
        }
        out
    }
}
