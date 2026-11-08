package com.nova.player.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.nova.player.PlayerActivity

object PlaybackHolder {
    @Volatile var player: Player? = null
}

/** Exposes the running ExoPlayer to a MediaSession so playback survives leaving the app (notification + lock screen). */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val p = PlaybackHolder.player
        if (p == null) { stopSelf(); return }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, p).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (PlaybackHolder.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        session?.release(); session = null
        super.onDestroy()
    }
}

object BackgroundAudio {
    private var future: ListenableFuture<MediaController>? = null

    fun start(ctx: Context, p: ExoPlayer) {
        PlaybackHolder.player = p
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        if (future == null) {
            future = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        }
    }

    fun stop(ctx: Context) {
        (PlaybackHolder.player as? ExoPlayer)?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        }
        future?.let { MediaController.releaseFuture(it) }
        future = null
        PlaybackHolder.player = null
        ctx.stopService(Intent(ctx, PlaybackService::class.java))
    }
}
