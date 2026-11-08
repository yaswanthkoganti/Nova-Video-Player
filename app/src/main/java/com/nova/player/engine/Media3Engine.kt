package com.nova.player.engine

import android.content.Context
import android.graphics.Color
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import kotlin.math.log10

/** Hardware-accelerated, battery-friendly engine. Primary engine in AUTO mode. */
@OptIn(UnstableApi::class)
class Media3Engine(private val ctx: Context) : PlayerEngine {
    override val name = "Media3"
    override val caps = Caps(subtitleDelay = false, audioDelay = false, maxBoost = 300, skipSilence = true, enhance = true)

    private val _state = MutableStateFlow(EngineState())
    override val state = _state.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val player: ExoPlayer = ExoPlayer.Builder(
        ctx,
        DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
    ).build()

    private var enhancer: LoudnessEnhancer? = null
    private val media3Eq = Media3Eq()
    override val eq: EqController get() = media3Eq
    private val enhanceParams = EnhanceParams()
    private var enhanceInstalled = false
    var title: String = ""
    val exoPlayer: ExoPlayer get() = player
    private val subStyle = mutableStateOf(Triple(Color.WHITE, Color.TRANSPARENT, 0.08f))
    private var boost = 100
    private var current: Uri? = null
    private val extraSubs = mutableListOf<Uri>()
    private val resize = mutableStateOf(ResizeMode.FIT)
    private val subScale = mutableStateOf(1f)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onTracksChanged(tracks: Tracks) = publish()
        override fun onPlaybackParametersChanged(p: PlaybackParameters) = publish()
        override fun onAudioSessionIdChanged(audioSessionId: Int) = rebuildEnhancer(audioSessionId)
        override fun onPlayerError(error: PlaybackException) {
            // IO/network errors (2xxx) can't be fixed by switching engines; decoder/container errors can.
            val recoverable = error.errorCode !in 2000..2999
            _state.update { it.copy(error = EngineError(error.errorCodeName, recoverable)) }
        }
    }

    init {
        player.addListener(listener)
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
        )
        player.setHandleAudioBecomingNoisy(true)
        rebuildEnhancer(player.audioSessionId)
        scope.launch {
            while (isActive) {
                if (player.isPlaying) tick()
                delay(200)
            }
        }
    }

    private fun tick() = _state.update { it.copy(positionMs = player.currentPosition.coerceAtLeast(0)) }

    private fun publish() {
        val tr = player.currentTracks
        _state.update {
            it.copy(
                playing = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.takeIf { d -> d > 0 } ?: 0L,
                speed = player.playbackParameters.speed,
                audio = list(tr, C.TRACK_TYPE_AUDIO),
                subs = list(tr, C.TRACK_TYPE_TEXT),
                ended = player.playbackState == Player.STATE_ENDED,
            )
        }
    }

    private fun list(tr: Tracks, type: Int): List<Track> =
        tr.groups.withIndex()
            .filter { it.value.type == type && it.value.isSupported }
            .map { (gi, g) ->
                val f = g.getTrackFormat(0)
                val label = listOfNotNull(
                    f.label,
                    f.language?.let { l -> Locale.forLanguageTag(l).displayLanguage.ifBlank { null } },
                    if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) "${f.channelCount}ch" else null,
                ).distinct().joinToString(" · ").ifBlank { "Track ${gi + 1}" }
                Track(gi.toString(), label, g.isSelected)
            }

    private fun rebuildEnhancer(sessionId: Int) {
        runCatching { enhancer?.release() }
        enhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        media3Eq.bind(sessionId)
        applyBoost()
    }

    private fun applyBoost() {
        if (boost <= 100) {
            enhancer?.setEnabled(false)
            player.volume = boost / 100f
        } else {
            player.volume = 1f
            enhancer?.setTargetGain((2000 * log10(boost / 100.0)).toInt()) // millibels
            enhancer?.setEnabled(true)
        }
    }

    private fun mimeFor(u: Uri): String {
        val p = (u.lastPathSegment ?: "").lowercase()
        return when {
            p.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            p.endsWith(".ass") || p.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            p.endsWith(".ttml") || p.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun buildItem(): MediaItem = MediaItem.Builder()
        .setUri(current!!)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .setSubtitleConfigurations(extraSubs.map {
            MediaItem.SubtitleConfiguration.Builder(it)
                .setMimeType(mimeFor(it))
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        })
        .build()

    override fun load(uri: Uri, startMs: Long, speed: Float) {
        current = uri
        extraSubs.clear()
        _state.update { EngineState() }
        player.setMediaItem(buildItem(), startMs)
        player.setPlaybackSpeed(speed)
        player.prepare()
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }

    override fun seekTo(ms: Long, exact: Boolean) {
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(ms)
        tick()
    }

    override fun setSpeed(speed: Float) = player.setPlaybackParameters(PlaybackParameters(speed, 1f)) // pitch kept

    override fun selectAudio(id: String) {
        val g = player.currentTracks.groups.getOrNull(id.toIntOrNull() ?: return) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
    }

    override fun selectSubtitle(id: String?) {
        val b = player.trackSelectionParameters.buildUpon()
        val g = id?.toIntOrNull()?.let { player.currentTracks.groups.getOrNull(it) }
        if (g == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = b.build()
    }

    override fun addSubtitle(uri: Uri) {
        extraSubs += uri
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.setMediaItem(buildItem(), pos)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    // Media3 has no subtitle/audio delay: the UI hides these (caps) and AUTO mode can hand over to libVLC.
    override fun setSubtitleDelay(ms: Long) {}
    override fun setAudioDelay(ms: Long) {}

    override fun setSubtitleScale(scale: Float) { subScale.value = scale }
    override fun setSubtitleStyle(color: Int, bgColor: Int, bottomFraction: Float) {
        subStyle.value = Triple(color, bgColor, bottomFraction)
    }

    override fun setEnhance(e: Enhance) {
        enhanceParams.set(e)
        if (!e.isIdentity && !enhanceInstalled) {
            player.setVideoEffects(listOf(EnhanceEffect(enhanceParams)))
            enhanceInstalled = true
        }
    }
    override fun setResize(mode: ResizeMode) { resize.value = mode }
    override fun setSkipSilence(on: Boolean) { player.skipSilenceEnabled = on }
    override fun setVolumeBoost(percent: Int) { boost = percent.coerceIn(0, caps.maxBoost); applyBoost() }

    override fun stepFrame(forward: Boolean) {
        player.pause()
        player.setSeekParameters(SeekParameters.EXACT)
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0 } ?: 30f
        val step = (1000f / fps).toLong().coerceAtLeast(1)
        player.seekTo((player.currentPosition + if (forward) step else -step).coerceAtLeast(0))
        tick()
    }

    override fun release() {
        scope.cancel()
        runCatching { enhancer?.release() }
        media3Eq.release()
        player.removeListener(listener)
        player.release()
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        val mode by resize
        val scale by subScale
        val style by subStyle
        val boxMod = when (mode) {
            ResizeMode.R16_9 -> Modifier.aspectRatio(16 / 9f)
            ResizeMode.R4_3 -> Modifier.aspectRatio(4 / 3f)
            else -> Modifier.fillMaxSize()
        }
        Box(modifier, contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = boxMod,
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        setShutterBackgroundColor(Color.BLACK)
                        setPlayer(this@Media3Engine.player)
                        subtitleView?.setStyle(
                            CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null)
                        )
                    }
                },
                update = { v ->
                    v.resizeMode = when (mode) {
                        ResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        ResizeMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                    v.subtitleView?.apply {
                        setStyle(CaptionStyleCompat(style.first, style.second, Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
                        setBottomPaddingFraction(style.third)
                        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
                    }
                },
            )
        }
    }
}
