package com.nova.player.net

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.text.format.Formatter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.*
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Serves a content:// video over HTTP (with Range support) so a Chromecast can pull it. */
class LocalHttpServer(private val ctx: Context, private val uri: Uri) : NanoHTTPD(0) {
    override fun serve(session: IHTTPSession): Response {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        val len = pfd.statSize
        val mime = ctx.contentResolver.getType(uri) ?: "video/mp4"
        val range = session.headers["range"]
        var start = 0L
        var end = len - 1
        if (range != null && range.startsWith("bytes=")) {
            val p = range.removePrefix("bytes=").split("-")
            start = p[0].toLongOrNull() ?: 0L
            end = p.getOrNull(1)?.toLongOrNull() ?: (len - 1)
        }
        val fis = object : FileInputStream(pfd.fileDescriptor) {
            override fun close() { super.close(); pfd.close() }
        }
        fis.channel.position(start)
        val res = newFixedLengthResponse(
            if (range != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK, mime, fis, end - start + 1
        )
        res.addHeader("Accept-Ranges", "bytes")
        res.addHeader("Access-Control-Allow-Origin", "*")
        if (range != null) res.addHeader("Content-Range", "bytes $start-$end/$len")
        return res
    }
}

/** Sends the current video to a Cast device and mirrors transport controls. */
class CastController(
    private val ctx: Context,
    private val onStarted: (String) -> Unit,
    private val onEnded: (Long) -> Unit,
) {
    private val cc: CastContext? = runCatching { CastContext.getSharedInstance(ctx) }.getOrNull()
    private var server: LocalHttpServer? = null
    private var active = false
    private var lastPos = 0L

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) { onStarted(s.castDevice?.friendlyName ?: "Cast device") }
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {}
        override fun onSessionEnding(s: CastSession) { lastPos = s.remoteMediaClient?.approximateStreamPosition ?: lastPos }
        override fun onSessionEnded(s: CastSession, error: Int) { if (active) { active = false; stopServer(); onEnded(lastPos) } }
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStartFailed(s: CastSession, error: Int) {}
        override fun onSessionResuming(s: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    init { cc?.sessionManager?.addSessionManagerListener(listener, CastSession::class.java) }

    private val client: RemoteMediaClient? get() = cc?.sessionManager?.currentCastSession?.remoteMediaClient

    private fun stopServer() { server?.stop(); server = null }

    @Suppress("DEPRECATION")
    private fun ip(): String {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
    }

    /** Returns false if the source can't be cast (e.g. smb://). */
    fun load(uri: Uri, title: String, positionMs: Long): Boolean {
        val rc = client ?: return false
        val url: String
        val mime: String
        when (uri.scheme) {
            "http", "https" -> { url = uri.toString(); mime = "video/mp4" }
            "content" -> {
                stopServer()
                val srv = LocalHttpServer(ctx, uri).also { it.start() }
                server = srv
                url = "http://${ip()}:${srv.listeningPort}/video"
                mime = ctx.contentResolver.getType(uri) ?: "video/mp4"
            }
            else -> return false
        }
        val md = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, title) }
        val info = MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setContentType(mime).setMetadata(md).build()
        rc.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(positionMs).build())
        active = true
        return true
    }

    fun toggle() { client?.togglePlayPause() }
    fun stop() { cc?.sessionManager?.endCurrentSession(true) }
    fun release() { cc?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java); stopServer() }
}
