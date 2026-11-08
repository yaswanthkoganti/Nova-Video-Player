package com.nova.player.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nova.player.data.*
import com.nova.player.engine.*
import com.nova.player.net.CastController
import com.nova.player.service.BackgroundAudio
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class UiState(
    val uri: String = "",
    val title: String = "",
    val engineName: String = "",
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val pip: Boolean = false,
    val resize: ResizeMode = ResizeMode.FIT,
    val abA: Long? = null,
    val abB: Long? = null,
    val boost: Int = 100,
    val skipSilence: Boolean = false,
    val subDelay: Long = 0,
    val audioDelay: Long = 0,
    val sleepLeftMs: Long? = null,
    val sleepAtEnd: Boolean = false,
    val tempSpeed: Float? = null,
    val seekPreview: Long? = null,
    val hud: String? = null,
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,
    val enhance: Enhance = Enhance(),
    val busy: String? = null,
    val subResults: List<OpenSubtitles.Result> = emptyList(),
    val hasSub: Boolean = false,
    val castName: String? = null,
)

private enum class Kind { MEDIA3, VLC }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val dao = NovaDb.get(ctx).dao()

    private val repo = SettingsRepo(ctx)
    val settings = repo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val engineFlow = MutableStateFlow<PlayerEngine?>(null)
    val playback: StateFlow<EngineState> = engineFlow
        .flatMapLatest { it?.state ?: flowOf(EngineState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineState())

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> = _ui.map { it.uri }.distinctUntilChanged()
        .flatMapLatest { dao.bookmarks(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var playlist: List<Pair<Uri, String>> = emptyList()
    private var index = 0
    private var userSpeed = 1f
    private var kind = Kind.MEDIA3
    private var opened = false
    private var endedHandled = false
    private val engine get() = engineFlow.value

    // ---------- lifecycle ----------

    fun open(uri: Uri) {
        if (opened) return
        opened = true
        viewModelScope.launch {
            val s = settings.first()
            val items = runCatching { MediaRepo.load(ctx) }.getOrDefault(emptyList())
            val me = items.find { it.uri == uri }
            playlist = if (me != null)
                items.filter { it.folder == me.folder }.sortedWith(compareBy(Natural) { it.title })
                    .map { it.uri to it.title }
            else listOf(uri to (uri.lastPathSegment?.substringAfterLast('/') ?: "Video"))
            index = playlist.indexOfFirst { it.first == uri }.coerceAtLeast(0)
            kind = if (s.engine == EnginePref.VLC || uri.scheme == "smb") Kind.VLC else Kind.MEDIA3
            startEngine()
            startTickers()
        }
    }

    private suspend fun startEngine(forceStartMs: Long? = null) {
        val (uri, title) = playlist[index]
        val s = settings.value
        val wanted = if (kind == Kind.VLC) "libVLC" else "Media3"
        val old = engine
        val e = old?.takeIf { it.name == wanted }
            ?: (if (kind == Kind.VLC) VlcEngine(ctx) else Media3Engine(ctx))
        val saved = dao.state(uri.toString())
        val start = forceStartMs ?: if (s.resumePlayback && saved != null &&
            saved.positionMs > 5_000 && saved.positionMs < saved.durationMs - 10_000) saved.positionMs else 0L
        userSpeed = saved?.speed ?: 1f
        val resize = ResizeMode.values().getOrElse(saved?.resize ?: 0) { ResizeMode.FIT }
        endedHandled = false

        if (e !== old) { engineFlow.value = e; old?.release() }
        (e as? Media3Engine)?.title = title
        thumbs.setSource(uri)
        e.load(uri, start, userSpeed)
        e.setEnhance(_ui.value.enhance)
        e.setSubtitleStyle(subColorInt(s.subColor), subBgInt(s.subBg), s.subBottom, s.subPosMode == 1)
        e.setResize(resize)
        e.setVolumeBoost(100)
        e.setSkipSilence(s.skipSilence)
        e.setSubtitleScale(s.subScale)
        e.play()

        _ui.update {
            it.copy(
                uri = uri.toString(), title = title, engineName = e.name, resize = resize,
                abA = null, abB = null, boost = 100, skipSilence = s.skipSilence && e.caps.skipSilence,
                subDelay = saved?.subDelayMs ?: 0, audioDelay = saved?.audioDelayMs ?: 0,
                seekPreview = null, hasNext = index < playlist.lastIndex, hasPrev = index > 0, hasSub = false, subResults = emptyList(),
            )
        }
        showControlsBriefly()

        // Restore per-video track choices + delays once tracks exist.
        viewModelScope.launch {
            withTimeoutOrNull(8_000) { e.state.first { it.audio.isNotEmpty() || it.durationMs > 0 } }
            saved?.let { st ->
                if (st.engine == e.name) { st.audioId?.let { e.selectAudio(it) }; e.selectSubtitle(st.subId) }
                if (e.caps.subtitleDelay && st.subDelayMs != 0L) e.setSubtitleDelay(st.subDelayMs)
                if (e.caps.audioDelay && st.audioDelayMs != 0L) e.setAudioDelay(st.audioDelayMs)
            }
        }
    }

    private fun startTickers() {
        // live-apply subtitle appearance/position changes made from the player sheets
        viewModelScope.launch {
            settings.collect { s ->
                engine?.setSubtitleStyle(subColorInt(s.subColor), subBgInt(s.subBg), s.subBottom, s.subPosMode == 1)
                engine?.setSubtitleScale(s.subScale)
            }
        }
        // A-B loop
        viewModelScope.launch {
            while (true) {
                delay(100)
                val e = engine ?: continue
                val u = _ui.value
                val a = u.abA; val b = u.abB
                if (a != null && b != null && e.state.value.positionMs >= b) e.seekTo(a, exact = true)
            }
        }
        // periodic persistence
        viewModelScope.launch { while (true) { delay(5_000); persist() } }
        // ended + engine fallback
        viewModelScope.launch {
            playback.collect { s ->
                if (s.ended && !endedHandled) { endedHandled = true; onEnded() }
                val err = s.error
                if (err != null && err.recoverableByOtherEngine && kind == Kind.MEDIA3 &&
                    settings.value.engine == EnginePref.AUTO) {
                    kind = Kind.VLC
                    startEngine(s.positionMs)
                    hud("Switched to libVLC for this file")
                }
            }
        }
    }

    private fun onEnded() {
        val u = _ui.value
        when {
            u.sleepAtEnd -> { _ui.update { it.copy(sleepAtEnd = false) } }
            settings.value.autoPlayNext && u.hasNext -> next()
        }
    }

    fun persist() {
        val e = engine ?: return
        if (playlist.isEmpty()) return
        val s = e.state.value
        val u = _ui.value
        val st = VideoState(
            uri = playlist[index].first.toString(),
            positionMs = if (s.ended) 0 else s.positionMs,
            durationMs = s.durationMs, speed = userSpeed,
            audioId = s.audio.firstOrNull { it.selected }?.id,
            subId = s.subs.firstOrNull { it.selected }?.id,
            subDelayMs = u.subDelay, audioDelayMs = u.audioDelay,
            resize = u.resize.ordinal, engine = e.name, updated = System.currentTimeMillis(),
        )
        viewModelScope.launch { dao.save(st) }
    }

    fun pauseIfPlaying() { if (playback.value.playing) engine?.pause() }
    override fun onCleared() {
        BackgroundAudio.stop(ctx)
        cast.release()
        thumbs.release()
        engine?.release()
    }

    // ---------- transport ----------

    fun togglePlay() { engine?.let { if (it.state.value.playing) it.pause() else it.play() } }

    fun seekBy(ms: Long) {
        val e = engine ?: return
        val s = e.state.value
        val t = (s.positionMs + ms).coerceIn(0, maxOf(s.durationMs, 0))
        e.seekTo(t)
        hud((if (ms > 0) "+" else "−") + (kotlin.math.abs(ms) / 1000) + "s  ·  " + fmtTime(t))
    }

    fun previewSeek(ms: Long) {
        val dur = playback.value.durationMs
        _ui.update { it.copy(seekPreview = ms.coerceIn(0, maxOf(dur, 0))) }
        showControlsBriefly()
    }

    fun commitSeek() {
        _ui.value.seekPreview?.let { engine?.seekTo(it) }
        _ui.update { it.copy(seekPreview = null) }
    }

    fun next() { if (index < playlist.lastIndex) { persist(); index++; viewModelScope.launch { startEngine() } } }
    fun prev() { if (index > 0) { persist(); index--; viewModelScope.launch { startEngine() } } }
    fun stepFrame(forward: Boolean) { engine?.stepFrame(forward) }

    fun setSpeed(s: Float) { userSpeed = s; engine?.setSpeed(s) }
    fun setTempSpeed(s: Float?) {
        engine?.setSpeed(s ?: userSpeed)
        _ui.update { it.copy(tempSpeed = s) }
    }

    fun toggleAb() {
        val p = playback.value.positionMs
        _ui.update {
            when {
                it.abA == null -> it.copy(abA = p)
                it.abB == null && p > it.abA -> it.copy(abB = p)
                else -> it.copy(abA = null, abB = null)
            }
        }
        val u = _ui.value
        hud(when { u.abA == null -> "Loop off"; u.abB == null -> "Loop A set"; else -> "Looping ${fmtTime(u.abA)} – ${fmtTime(u.abB)}" })
    }

    // ---------- picture / audio ----------

    fun setResize(m: ResizeMode) { engine?.setResize(m); _ui.update { it.copy(resize = m) }; hud(m.label) }
    fun cycleResize() { val v = ResizeMode.values(); setResize(v[(_ui.value.resize.ordinal + 1) % v.size]) }
    fun setBoost(p: Int) { engine?.setVolumeBoost(p); _ui.update { it.copy(boost = p) } }
    fun setSkipSilence(on: Boolean) { engine?.setSkipSilence(on); _ui.update { it.copy(skipSilence = on) } }

    fun adjustSubDelay(d: Long) {
        val v = _ui.value.subDelay + d
        engine?.setSubtitleDelay(v); _ui.update { it.copy(subDelay = v) }
    }
    fun adjustAudioDelay(d: Long) {
        val v = _ui.value.audioDelay + d
        engine?.setAudioDelay(v); _ui.update { it.copy(audioDelay = v) }
    }

    fun switchEngine() {
        val pos = playback.value.positionMs
        kind = if (kind == Kind.MEDIA3) Kind.VLC else Kind.MEDIA3
        viewModelScope.launch { persist(); startEngine(pos) }
    }

    // ---------- bookmarks ----------

    fun addBookmark() {
        val p = playback.value.positionMs
        val u = _ui.value.uri
        viewModelScope.launch { dao.add(Bookmark(uri = u, positionMs = p, label = fmtTime(p))) }
        hud("Bookmarked ${fmtTime(p)}")
    }
    fun removeBookmark(b: Bookmark) { viewModelScope.launch { dao.remove(b) } }

    // ---------- sleep timer ----------

    private var sleepJob: Job? = null
    fun sleepIn(minutes: Int?) {
        sleepJob?.cancel()
        _ui.update { it.copy(sleepAtEnd = false, sleepLeftMs = null) }
        if (minutes == null) return
        sleepJob = viewModelScope.launch {
            var left = minutes * 60_000L
            while (left > 0) { _ui.update { it.copy(sleepLeftMs = left) }; delay(1_000); left -= 1_000 }
            engine?.pause(); _ui.update { it.copy(sleepLeftMs = null) }
        }
    }
    fun sleepAtEnd() { sleepJob?.cancel(); _ui.update { it.copy(sleepAtEnd = true, sleepLeftMs = null) } }

    // ---------- UI chrome ----------

    private var hudJob: Job? = null
    fun hud(text: String) {
        hudJob?.cancel()
        _ui.update { it.copy(hud = text) }
        hudJob = viewModelScope.launch { delay(900); _ui.update { it.copy(hud = null) } }
    }

    private var ctrlJob: Job? = null
    fun showControlsBriefly() {
        _ui.update { it.copy(controlsVisible = true) }
        ctrlJob?.cancel()
        ctrlJob = viewModelScope.launch {
            delay(3_500)
            if (playback.value.playing) _ui.update { it.copy(controlsVisible = false) }
        }
    }
    fun toggleControls() {
        if (_ui.value.controlsVisible) { ctrlJob?.cancel(); _ui.update { it.copy(controlsVisible = false) } }
        else showControlsBriefly()
    }
    fun toggleLock() { _ui.update { it.copy(locked = !it.locked, controlsVisible = true) }; hud(if (_ui.value.locked) "Locked" else "Unlocked") }
    fun setPip(on: Boolean) { _ui.update { it.copy(pip = on) } }

    // ---------- seek thumbnails ----------

    private val thumbs = ThumbProvider(ctx)
    val thumb: StateFlow<Bitmap?> = _ui.map { it.seekPreview }.distinctUntilChanged().debounce(40)
        .mapLatest { ms -> if (ms == null) null else thumbs.frameAt(ms) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---------- video enhancement ----------

    fun setEnhance(e: Enhance) { engine?.setEnhance(e); _ui.update { it.copy(enhance = e) } }

    // ---------- background audio ----------

    fun startBackground(c: Context) { (engine as? Media3Engine)?.let { BackgroundAudio.start(c, it.exoPlayer) } }
    fun stopBackground(c: Context) = BackgroundAudio.stop(c)

    // ---------- subtitles: load / AI generate / translate / online search ----------

    private var lastSub: File? = null
    private fun busy(t: String?) = _ui.update { it.copy(busy = t) }

    private fun useSubtitle(f: File) {
        lastSub = f
        engine?.addSubtitle(Uri.fromFile(f))
        _ui.update { it.copy(hasSub = true) }
    }

    fun loadSubtitle(uri: Uri) {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                val ext = uri.lastPathSegment?.substringAfterLast('.', "srt")?.take(4) ?: "srt"
                File(ctx.cacheDir, "sub_${System.nanoTime()}.$ext").also { out ->
                    ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } }
                }
            }
            useSubtitle(f)
        }
    }

    /** [cloud] = Whisper API (needed for Tamil/Malayalam); otherwise offline Vosk. */
    fun generateSubs(lang: String, cloud: Boolean) {
        val uri = playlist.getOrNull(index)?.first ?: return
        val s = settings.value
        viewModelScope.launch {
            try {
                val cues = if (cloud) {
                    if (s.whisperKey.isBlank()) { hud("Add your Whisper API key in Settings"); return@launch }
                    CloudWhisper.transcribe(ctx, uri, s.whisperUrl, s.whisperKey, s.whisperModel, lang) { busy(it) }
                } else {
                    busy("Preparing speech model…")
                    val dir = ModelManager(ctx).ensure(lang) { busy(it) }
                    busy("Transcribing 0%")
                    Transcriber.transcribe(ctx, uri, dir) { busy("Transcribing ${(it * 100).toInt()}%") }
                }
                if (cues.isEmpty()) { hud("No speech found"); return@launch }
                val f = File(ctx.cacheDir, "auto_${System.nanoTime()}_$lang.srt").apply { writeText(toSrt(cues)) }
                useSubtitle(f)
                hud("Subtitles generated (${cues.size} lines)")
            } catch (e: Exception) { hud("Failed: ${e.message}") } finally { busy(null) }
        }
    }

    fun translateSubs(src: String, dst: String) {
        val f = lastSub ?: run { hud("Load or generate a subtitle first"); return }
        viewModelScope.launch {
            try {
                val cues = parseSrt(f.readText())
                if (cues.isEmpty()) { hud("Only SRT subtitles can be translated"); return@launch }
                busy("Downloading translation model…")
                val out = Translator.translate(cues, src, dst) { busy("Translating ${(it * 100).toInt()}%") }
                val nf = File(ctx.cacheDir, "tr_${System.nanoTime()}_$dst.srt").apply { writeText(toSrt(out)) }
                useSubtitle(nf)
                hud("Translated to $dst")
            } catch (e: Exception) { hud("Translate failed: ${e.message}") } finally { busy(null) }
        }
    }

    private fun cleanTitle(t: String) = t.substringBeforeLast('.')
        .replace(Regex("[._]"), " ")
        .replace(Regex("\\[.*?]|\\(.*?\\)"), " ")
        .replace(Regex("(?i)\\b(2160p|1080p|720p|480p|4k|x264|x265|h264|h265|hevc|bluray|brrip|webrip|web-dl|hdrip|dvdrip|aac|ac3)\\b.*"), "")
        .trim()

    fun searchSubs(lang: String) {
        val s = settings.value
        if (s.osApiKey.isBlank()) { hud("Add your OpenSubtitles API key in Settings"); return }
        viewModelScope.launch {
            busy("Searching…")
            try {
                val r = OpenSubtitles(s.osApiKey, s.osUser, s.osPass).search(cleanTitle(_ui.value.title), Langs.osCode(lang))
                _ui.update { it.copy(subResults = r) }
                if (r.isEmpty()) hud("No results")
            } catch (e: Exception) { hud("Search failed: ${e.message}") } finally { busy(null) }
        }
    }

    fun downloadSub(r: OpenSubtitles.Result) {
        val s = settings.value
        viewModelScope.launch {
            busy("Downloading…")
            try {
                val dest = File(ctx.cacheDir, "os_${r.fileId}.srt")
                OpenSubtitles(s.osApiKey, s.osUser, s.osPass).download(r.fileId, dest)
                useSubtitle(dest)
                hud("Subtitle added")
            } catch (e: Exception) { hud("Download failed: ${e.message}") } finally { busy(null) }
        }
    }

    // ---------- Chromecast ----------

    private val cast = CastController(ctx,
        onStarted = { name -> castCurrent(name) },
        onEnded = { pos -> _ui.update { it.copy(castName = null) }; engine?.seekTo(pos); engine?.play() })

    private fun castCurrent(name: String) {
        val e = engine ?: return
        val uri = playlist.getOrNull(index)?.first ?: return
        val pos = playback.value.positionMs
        e.pause()
        if (cast.load(uri, _ui.value.title, pos)) _ui.update { it.copy(castName = name) }
        else { hud("This source can't be cast"); e.play() }
    }
    fun castToggle() = cast.toggle()
    fun castStop() = cast.stop()

    // ---------- persisted preferences (used by in-player quick settings) ----------

    fun <T> setPref(key: Preferences.Key<T>, value: T) { viewModelScope.launch { repo.set(key, value) } }
}
