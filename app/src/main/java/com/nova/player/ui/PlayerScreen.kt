package com.nova.player.ui

import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nova.player.PlayerActivity
import com.nova.player.engine.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.nova.player.data.Langs
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Sheet { TRACKS, SPEED, SLEEP, BOOKMARKS, MORE, ENHANCE, EQ, SUBS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel, act: PlayerActivity) {
    val engine by vm.engineFlow.collectAsState()
    val pb by vm.playback.collectAsState()
    val ui by vm.ui.collectAsState()
    val cfg by vm.settings.collectAsState()
    val thumb by vm.thumb.collectAsState()
    val scope = rememberCoroutineScope()

    val cfgS by rememberUpdatedState(cfg)
    val pbS by rememberUpdatedState(pb)
    val lockedS by rememberUpdatedState(ui.locked)

    var zoom by remember { mutableFloatStateOf(1f) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    LaunchedEffect(ui.resize) { zoom = 1f }

    val audio = remember { act.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var brightness by remember {
        mutableFloatStateOf(act.window.attributes.screenBrightness.let { if (it < 0) 0.5f else it })
    }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        engine?.let { e ->
            Box(Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom)) {
                e.Surface(Modifier.fillMaxSize())
            }
        }

        // ---------- gesture layer ----------
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { vm.toggleControls() },
                        onDoubleTap = { o ->
                            if (!lockedS) {
                                val w = size.width
                                val ms = cfgS.doubleTapSec * 1000L
                                when {
                                    o.x < w / 3f -> vm.seekBy(-ms)
                                    o.x > w * 2f / 3f -> vm.seekBy(ms)
                                    else -> vm.togglePlay()
                                }
                            }
                        },
                        onPress = {
                            if (!lockedS) {
                                val j = scope.launch { delay(450); vm.setTempSpeed(cfgS.longPressSpeed) }
                                tryAwaitRelease()
                                j.cancel()
                                if (vm.ui.value.tempSpeed != null) vm.setTempSpeed(null)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    var mode = 0 // -1 disabled, 1 seek, 2 brightness, 3 volume
                    var startX = 0f
                    var dx = 0f
                    var startPos = 0L
                    detectDragGestures(
                        onDragStart = { o -> mode = 0; startX = o.x; dx = 0f; startPos = pbS.positionMs },
                        onDragEnd = { if (mode == 1) vm.commitSeek(); mode = 0 },
                        onDragCancel = { if (mode == 1) vm.commitSeek(); mode = 0 },
                        onDrag = { change, d ->
                            if (lockedS) return@detectDragGestures
                            change.consume()
                            if (mode == 0) mode = when {
                                abs(d.x) > abs(d.y) -> if (cfgS.gestureSeek) 1 else -1
                                startX < size.width / 2f -> if (cfgS.gestureBrightness) 2 else -1
                                else -> if (cfgS.gestureVolume) 3 else -1
                            }
                            when (mode) {
                                1 -> {
                                    dx += d.x
                                    vm.previewSeek(startPos + (dx * 50 * cfgS.seekSensitivity).toLong())
                                }
                                2 -> {
                                    brightness = (brightness - d.y / size.height * 1.5f).coerceIn(0.02f, 1f)
                                    act.window.attributes = act.window.attributes.also { it.screenBrightness = brightness }
                                    vm.hud("Brightness ${(brightness * 100).roundToInt()}%")
                                }
                                3 -> {
                                    volume = (volume - d.y / size.height * 1.5f).coerceIn(0f, 1f)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVol).roundToInt(), 0)
                                    vm.hud("Volume ${(volume * 100).roundToInt()}%")
                                }
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val ev = awaitPointerEvent()
                            if (cfgS.gesturePinch && !lockedS && ev.changes.size >= 2) {
                                val z = ev.calculateZoom()
                                if (z != 1f) {
                                    zoom = (zoom * z).coerceIn(0.5f, 5f)
                                    vm.hud("Zoom ${(zoom * 100).roundToInt()}%")
                                    ev.changes.forEach { it.consume() }
                                }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
        )

        // ---------- HUD ----------
        ui.seekPreview?.let {
            val delta = it - pb.positionMs
            Hud("${fmtTime(it)}  (${if (delta >= 0) "+" else "−"}${fmtTime(abs(delta))})")
        } ?: ui.hud?.let { Hud(it) }
        ui.tempSpeed?.let { Hud("▶▶  ${it}x", Modifier.align(Alignment.TopCenter).padding(top = 48.dp)) }
        if (pb.buffering) LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
        pb.error?.let {
            Text("Can't play this file (${it.message})", color = Color(0xFFFF8A80), modifier = Modifier.align(Alignment.Center))
        }
        if (ui.sleepLeftMs != null || ui.sleepAtEnd) {
            Text(
                "😴 " + (ui.sleepLeftMs?.let { fmtTime(it) } ?: "end of video"),
                color = Color.White, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // ---------- controls ----------
        AnimatedVisibility(
            visible = ui.controlsVisible && !ui.pip && !ui.locked,
            enter = fadeIn(), exit = fadeOut(),
        ) { Controls(vm, pb, ui, act, thumb) { sheet = it } }

        if (ui.locked && ui.controlsVisible && !ui.pip) {
            IconButton(
                onClick = { vm.toggleLock() },
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp).background(Color(0x99000000), RoundedCornerShape(50)),
            ) { Icon(Icons.Default.Lock, "Unlock", tint = Color.White) }
        }

        ui.castName?.let { name ->
            Box(
                Modifier.fillMaxSize().background(Color(0xEE000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Default.Cast, null, tint = Color.White, modifier = Modifier.size(64.dp))
                    Text("Casting to $name", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(ui.title, color = Color(0xFFBBBBBB), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton({ vm.castToggle() }) { Text("Play / Pause") }
                        Button({ vm.castStop() }) { Text("Stop casting") }
                    }
                }
            }
        }

        val e = engine
        if (sheet != null && e != null) {
            SheetContent(sheet!!, vm, e, pb, ui, onOpen = { sheet = it }) { sheet = null }
        }
    }
}

@Composable
private fun Hud(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(color = Color(0xAA000000), shape = RoundedCornerShape(12.dp)) {
            Text(text, color = Color.White, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun CastButton() {
    AndroidView(
        modifier = Modifier.size(48.dp),
        factory = { c ->
            MediaRouteButton(c).also { b -> runCatching { CastButtonFactory.setUpMediaRouteButton(c, b) } }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Controls(vm: PlayerViewModel, pb: EngineState, ui: UiState, act: PlayerActivity, thumb: Bitmap?, onSheet: (Sheet) -> Unit) {
    val white = Color.White
    // Expressive shape morph: the play button squares off while playing.
    val corner by animateFloatAsState(if (pb.playing) 30f else 50f, MaterialTheme.motionScheme.defaultSpatialSpec<Float>(), label = "playMorph")
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent, Color(0xCC000000)))
        )
    ) {
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ act.finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = white) }
            Text(ui.title, color = white, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            CastButton()
            IconButton({ onSheet(Sheet.TRACKS) }) { Icon(Icons.Default.Subtitles, "Audio & subtitles", tint = white) }
            IconButton({ onSheet(Sheet.SPEED) }) { Icon(Icons.Default.Speed, "Speed", tint = white) }
            IconButton({ onSheet(Sheet.ENHANCE) }) {
                Icon(Icons.Default.AutoFixHigh, "Enhance video", tint = if (!ui.enhance.isIdentity) MaterialTheme.colorScheme.primary else white)
            }
            IconButton({ onSheet(Sheet.MORE) }) { Icon(Icons.Default.MoreVert, "More", tint = white) }
        }

        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.prev() }, enabled = ui.hasPrev) { Icon(Icons.Default.SkipPrevious, "Previous", tint = white, modifier = Modifier.size(36.dp)) }
            FilledIconButton(
                onClick = { vm.togglePlay() },
                modifier = Modifier.size(84.dp),
                shape = RoundedCornerShape(corner.coerceIn(0f, 50f).roundToInt()),
            ) {
                Icon(if (pb.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", modifier = Modifier.size(44.dp))
            }
            IconButton({ vm.next() }, enabled = ui.hasNext) { Icon(Icons.Default.SkipNext, "Next", tint = white, modifier = Modifier.size(36.dp)) }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val prev = ui.seekPreview
            if (prev != null) {
                val frac = if (pb.durationMs > 0) (prev / pb.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().padding(horizontal = 56.dp).padding(bottom = 6.dp)) {
                    Column(Modifier.align(BiasAlignment(frac * 2 - 1, 0f)), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (thumb != null) {
                            val shape = RoundedCornerShape(20.dp)
                            Image(
                                thumb.asImageBitmap(), null,
                                Modifier.height(96.dp).clip(shape).border(2.dp, MaterialTheme.colorScheme.primary, shape),
                            )
                        }
                        Text(fmtTime(prev), color = white, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            val shown = prev ?: pb.positionMs
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(shown), color = white, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = if (pb.durationMs > 0) shown / pb.durationMs.toFloat() else 0f,
                    onValueChange = { vm.previewSeek((it * pb.durationMs).toLong()) },
                    onValueChangeFinished = { vm.commitSeek() },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(fmtTime(pb.durationMs), color = white, style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.toggleLock() }) { Icon(Icons.Default.LockOpen, "Lock", tint = white) }
                IconButton({ vm.stepFrame(false) }) { Icon(Icons.Default.ChevronLeft, "Previous frame", tint = white) }
                IconButton({ vm.toggleAb() }) {
                    Icon(Icons.Default.Repeat, "A-B loop", tint = if (ui.abA != null) MaterialTheme.colorScheme.primary else white)
                }
                IconButton({ vm.stepFrame(true) }) { Icon(Icons.Default.ChevronRight, "Next frame", tint = white) }
                IconButton({ vm.cycleResize() }) { Icon(Icons.Default.AspectRatio, "Aspect", tint = white) }
                IconButton({ vm.addBookmark() }) { Icon(Icons.Default.BookmarkAdd, "Bookmark", tint = white) }
                IconButton({ act.enterPip() }) { Icon(Icons.Default.PictureInPictureAlt, "PiP", tint = white) }
                IconButton({ act.toggleRotation() }) { Icon(Icons.Default.ScreenRotation, "Rotate", tint = white) }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.labelLarge)
        Slider(value.coerceIn(range), onChange, valueRange = range)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SheetContent(
    sheet: Sheet, vm: PlayerViewModel, e: PlayerEngine, pb: EngineState, ui: UiState,
    onOpen: (Sheet) -> Unit, onDismiss: () -> Unit,
) {
    val bookmarks by vm.bookmarks.collectAsState()
    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.loadSubtitle(it) } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            when (sheet) {
                Sheet.TRACKS -> {
                    SectionTitle("Audio")
                    if (pb.audio.isEmpty()) Text("No audio tracks")
                    pb.audio.forEach { t -> TrackRow(t.label, t.selected) { e.selectAudio(t.id) } }
                    SectionTitle("Subtitles")
                    TrackRow("Off", pb.subs.none { it.selected }) { e.selectSubtitle(null) }
                    pb.subs.forEach { t -> TrackRow(t.label, t.selected) { e.selectSubtitle(t.id) } }
                    TextButton({ subPicker.launch(arrayOf("*/*")) }) { Text("Load subtitle file…") }
                    TextButton({ onOpen(Sheet.SUBS) }) { Text("Generate, translate or search online…") }
                    if (e.caps.subtitleDelay) DelayRow("Subtitle delay", ui.subDelay) { vm.adjustSubDelay(it) }
                    if (e.caps.audioDelay) DelayRow("Audio delay", ui.audioDelay) { vm.adjustAudioDelay(it) }
                    if (!e.caps.subtitleDelay) Text(
                        "Subtitle/audio delay needs libVLC — switch engine under More.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Sheet.SPEED -> {
                    SectionTitle("Playback speed: ${"%.2f".format(pb.speed)}x")
                    val opts = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
                    Chips(opts.map { "${it}x" }, opts.indexOfFirst { abs(it - pb.speed) < 0.01f }) { vm.setSpeed(opts[it]) }
                    Slider(pb.speed.coerceIn(0.25f, 4f), { vm.setSpeed((it * 20).roundToInt() / 20f) }, valueRange = 0.25f..4f)
                }
                Sheet.SLEEP -> {
                    SectionTitle("Sleep timer")
                    ui.sleepLeftMs?.let { Text("Stopping in ${fmtTime(it)}") }
                    if (ui.sleepAtEnd) Text("Stopping after this video")
                    val mins = listOf(15, 30, 45, 60, 90)
                    Chips(mins.map { "$it min" } + listOf("End of video", "Off"), -1) { i ->
                        when {
                            i < mins.size -> vm.sleepIn(mins[i])
                            i == mins.size -> vm.sleepAtEnd()
                            else -> vm.sleepIn(null)
                        }
                        onDismiss()
                    }
                }
                Sheet.BOOKMARKS -> {
                    SectionTitle("Bookmarks")
                    Button({ vm.addBookmark() }) { Text("Add at ${fmtTime(pb.positionMs)}") }
                    bookmarks.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable { e.seekTo(b.positionMs); onDismiss() }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Bookmark, null)
                            Spacer(Modifier.width(12.dp))
                            Text(b.label, Modifier.weight(1f))
                            IconButton({ vm.removeBookmark(b) }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
                Sheet.ENHANCE -> {
                    SectionTitle("Enhance video quality")
                    if (!e.caps.enhance) {
                        Text("Real-time enhancement runs on the Media3 engine. Switch engine under More.")
                    } else {
                        Text(
                            "GPU shader: sharpening, contrast, saturation and brightness, applied live. " +
                                "This is an image filter, not AI upscaling.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Chips(EnhancePresets.map { it.first }, EnhancePresets.indexOfFirst { it.second == ui.enhance }) {
                            vm.setEnhance(EnhancePresets[it].second)
                        }
                        LabeledSlider("Sharpness", ui.enhance.sharpen, 0f..1.5f) { vm.setEnhance(ui.enhance.copy(sharpen = it)) }
                        LabeledSlider("Contrast", ui.enhance.contrast, 0.7f..1.5f) { vm.setEnhance(ui.enhance.copy(contrast = it)) }
                        LabeledSlider("Saturation", ui.enhance.saturation, 0f..2f) { vm.setEnhance(ui.enhance.copy(saturation = it)) }
                        LabeledSlider("Brightness", ui.enhance.brightness, -0.2f..0.2f) { vm.setEnhance(ui.enhance.copy(brightness = it)) }
                    }
                }
                Sheet.EQ -> {
                    val eq = e.eq
                    SectionTitle("Equalizer")
                    if (eq == null) Text("Equalizer unavailable") else {
                        var on by remember { mutableStateOf(eq.enabled) }
                        var lv by remember { mutableStateOf(eq.levels()) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Enabled", Modifier.weight(1f))
                            Switch(on, { on = it; eq.enabled = it })
                        }
                        if (eq.presets.isNotEmpty()) {
                            Chips(eq.presets, -1) { eq.usePreset(it); on = true; eq.enabled = true; lv = eq.levels() }
                        }
                        eq.bands.forEachIndexed { i, hz ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                                Slider(
                                    lv.getOrElse(i) { 0f },
                                    { v -> eq.setLevel(i, v); lv = eq.levels() },
                                    valueRange = eq.minDb..eq.maxDb,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Sheet.SUBS -> {
                    val gen = Langs.gen.entries.toList()
                    var genI by remember { mutableIntStateOf(0) }
                    var from by remember { mutableIntStateOf(0) }
                    var to by remember { mutableIntStateOf(1) }
                    var osI by remember { mutableIntStateOf(0) }
                    val trNames = Langs.tr.map { it.second }

                    SectionTitle("Subtitle tools")
                    ui.busy?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(Modifier.size(32.dp)); Spacer(Modifier.width(12.dp)); Text(it)
                        }
                    }
                    OutlinedButton({ subPicker.launch(arrayOf("*/*")) }) { Text("Load subtitle file…") }

                    SectionTitle("Auto-generate from audio (on device)")
                    Text(
                        "Offline speech recognition (Vosk). Downloads a model once (~40 MB). Accuracy is below cloud " +
                            "services and there is no punctuation. Long videos take a while.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Chips(gen.map { it.value.first }, genI) { genI = it }
                    Button({ vm.generateSubs(gen[genI].key) }, enabled = ui.busy == null) { Text("Generate subtitles") }

                    SectionTitle("Translate current subtitle (on device)")
                    if (!ui.hasSub) Text("Load, generate or download a subtitle first.", style = MaterialTheme.typography.bodySmall)
                    Text("From", style = MaterialTheme.typography.labelLarge)
                    Chips(trNames, from) { from = it }
                    Text("To", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Chips(trNames, to) { to = it }
                    Button(
                        { vm.translateSubs(Langs.tr[from].first, Langs.tr[to].first) },
                        enabled = ui.hasSub && ui.busy == null && from != to,
                    ) { Text("Translate") }

                    SectionTitle("Search online (OpenSubtitles)")
                    Chips(trNames, osI) { osI = it }
                    Button({ vm.searchSubs(Langs.tr[osI].first) }, enabled = ui.busy == null) { Text("Search") }
                    ui.subResults.forEach { r ->
                        ListItem(
                            headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${r.lang} · ${r.downloads} downloads") },
                            modifier = Modifier.clickable { vm.downloadSub(r) },
                        )
                    }
                }
                Sheet.MORE -> {
                    SectionTitle("Tools")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ onOpen(Sheet.EQ) }) { Icon(Icons.Default.Equalizer, null); Spacer(Modifier.width(8.dp)); Text("Equalizer") }
                        FilledTonalButton({ onOpen(Sheet.SUBS) }) { Icon(Icons.Default.ClosedCaption, null); Spacer(Modifier.width(8.dp)); Text("Subtitle tools") }
                        FilledTonalButton({ onOpen(Sheet.SLEEP) }) { Icon(Icons.Default.Bedtime, null); Spacer(Modifier.width(8.dp)); Text("Sleep timer") }
                        FilledTonalButton({ onOpen(Sheet.BOOKMARKS) }) { Icon(Icons.Default.Bookmarks, null); Spacer(Modifier.width(8.dp)); Text("Bookmarks") }
                    }
                    SectionTitle("Aspect ratio")
                    Chips(ResizeMode.values().map { it.label }, ui.resize.ordinal) { vm.setResize(ResizeMode.values()[it]) }
                    SectionTitle("Volume boost: ${ui.boost}%")
                    Slider(ui.boost.toFloat(), { vm.setBoost(it.roundToInt()) }, valueRange = 100f..e.caps.maxBoost.toFloat())
                    if (e.caps.skipSilence) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Skip silence", Modifier.weight(1f))
                        Switch(ui.skipSilence, { vm.setSkipSilence(it) })
                    }
                    SectionTitle("Engine")
                    val other = if (ui.engineName == "libVLC") "Media3" else "libVLC"
                    Button({ vm.switchEngine(); onDismiss() }) { Text("Playing with ${ui.engineName} · switch to $other") }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

@Composable
private fun DelayRow(label: String, value: Long, onChange: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ${value}ms", Modifier.weight(1f))
        OutlinedButton({ onChange(-50) }) { Text("−50") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton({ onChange(50) }) { Text("+50") }
    }
}
