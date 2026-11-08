package com.nova.player.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/** Format-coverage engine (exotic codecs, DTS/TrueHD, ISO/DVD, odd containers, delays). */
class VlcEngine(private val ctx: Context) : PlayerEngine {
    override val name = "libVLC"
    override val caps = Caps(subtitleDelay = true, audioDelay = true, maxBoost = 200, skipSilence = false, enhance = false)

    private val _state = MutableStateFlow(EngineState())
    override val state = _state.asStateFlow()

    private val lib = LibVLC(
        ctx,
        arrayListOf("--network-caching=2000", "--file-caching=1000", "--audio-time-stretch", "--avcodec-hw=any")
    )
    private val mp = MediaPlayer(lib)
    override val eq: EqController = VlcEq(mp)
    private var pfd: ParcelFileDescriptor? = null

    init {
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Buffering -> _state.update { it.copy(buffering = e.buffering < 100f) }
                MediaPlayer.Event.EndReached -> _state.update { it.copy(ended = true, playing = false) }
                MediaPlayer.Event.EncounteredError ->
                    _state.update { it.copy(error = EngineError("libVLC cannot play this file", false)) }
                MediaPlayer.Event.TimeChanged -> _state.update { it.copy(positionMs = e.timeChanged) }
                MediaPlayer.Event.LengthChanged -> _state.update { it.copy(durationMs = e.lengthChanged) }
                MediaPlayer.Event.Playing, MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped,
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> publish()
            }
        }
    }

    private fun publish() {
        _state.update {
            it.copy(
                playing = mp.isPlaying,
                speed = mp.rate,
                audio = tracks(mp.audioTracks, mp.audioTrack),
                subs = tracks(mp.spuTracks, mp.spuTrack),
            )
        }
    }

    private fun tracks(arr: Array<MediaPlayer.TrackDescription>?, cur: Int) =
        arr.orEmpty().filter { it.id >= 0 }.map { Track(it.id.toString(), it.name ?: "Track ${it.id}", it.id == cur) }

    override fun load(uri: Uri, startMs: Long, speed: Float) {
        _state.update { EngineState() }
        runCatching { pfd?.close() }; pfd = null
        val media = if (uri.scheme == "content") {
            pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            Media(lib, pfd!!.fileDescriptor)
        } else Media(lib, uri)
        media.setHWDecoderEnabled(true, false)
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        mp.media = media
        media.release()
        mp.rate = speed
    }

    override fun play() { mp.play() }
    override fun pause() { mp.pause() }
    override fun seekTo(ms: Long, exact: Boolean) { mp.time = ms }
    override fun setSpeed(speed: Float) { mp.rate = speed }
    override fun selectAudio(id: String) { id.toIntOrNull()?.let { mp.audioTrack = it } }
    override fun selectSubtitle(id: String?) { mp.spuTrack = id?.toIntOrNull() ?: -1 }

    override fun addSubtitle(uri: Uri) {
        // libVLC can't read content:// subtitle slaves reliably, so copy to cache first.
        val ext = uri.lastPathSegment?.substringAfterLast('.', "srt")?.take(5) ?: "srt"
        val f = File(ctx.cacheDir, "sub_${System.nanoTime()}.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true)
    }

    override fun setSubtitleDelay(ms: Long) { mp.setSpuDelay(ms * 1000) }
    override fun setAudioDelay(ms: Long) { mp.setAudioDelay(ms * 1000) }
    override fun setSubtitleScale(scale: Float) {}
    override fun setSubtitleStyle(color: Int, bgColor: Int, bottomFraction: Float) {} // libass renders ASS natively
    override fun setEnhance(e: Enhance) {}

    override fun setResize(mode: ResizeMode) {
        mp.videoScale = when (mode) {
            ResizeMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            ResizeMode.CROP -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            ResizeMode.STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
            ResizeMode.R16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
            ResizeMode.R4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
        }
    }

    override fun setSkipSilence(on: Boolean) {}
    override fun setVolumeBoost(percent: Int) { mp.volume = percent.coerceIn(0, caps.maxBoost) }

    override fun stepFrame(forward: Boolean) {
    mp.pause()
    val delta = if (forward) 42L else -42L
    mp.time = (mp.time + delta).coerceAtLeast(0L)
}

    override fun release() {
        runCatching { mp.stop() }
        mp.setEventListener(null)
        runCatching { mp.detachViews() }
        mp.release()
        lib.release()
        runCatching { pfd?.close() }
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { c -> VLCVideoLayout(c).also { mp.attachViews(it, null, true, false) } },
            onRelease = { runCatching { mp.detachViews() } },
        )
    }
}
