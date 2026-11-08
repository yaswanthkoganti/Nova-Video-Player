package com.nova.player.engine

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

enum class ResizeMode(val label: String) {
    FIT("Fit"), CROP("Crop"), STRETCH("Stretch"), R16_9("16:9"), R4_3("4:3")
}

data class Track(val id: String, val label: String, val selected: Boolean)

/** What an engine can do, so the UI only offers supported controls. */
data class Caps(
    val subtitleDelay: Boolean,
    val audioDelay: Boolean,
    val maxBoost: Int,          // max volume % (100 = no boost)
    val skipSilence: Boolean,
    val enhance: Boolean,       // real-time GPU video enhancement
)

/** Real-time GPU enhancement parameters (unsharp mask + colour). */
data class Enhance(
    val sharpen: Float = 0f,      // 0..1.5
    val contrast: Float = 1f,     // 0.7..1.5
    val saturation: Float = 1f,   // 0..2
    val brightness: Float = 0f,   // -0.2..0.2
) {
    val isIdentity get() = sharpen == 0f && contrast == 1f && saturation == 1f && brightness == 0f
}

val EnhancePresets = listOf(
    "Off" to Enhance(),
    "Light" to Enhance(0.3f, 1.04f, 1.06f, 0f),
    "Vivid" to Enhance(0.4f, 1.10f, 1.30f, 0.01f),
    "Cinema" to Enhance(0.3f, 1.12f, 0.95f, -0.02f),
    "Sharp" to Enhance(0.9f, 1.05f, 1.05f, 0f),
)

interface EqController {
    val bands: List<Int>        // centre frequencies in Hz
    val minDb: Float
    val maxDb: Float
    val presets: List<String>
    var enabled: Boolean
    fun levels(): List<Float>
    fun setLevel(band: Int, db: Float)
    fun usePreset(index: Int)
}

data class EngineError(val message: String, val recoverableByOtherEngine: Boolean)

data class EngineState(
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val audio: List<Track> = emptyList(),
    val subs: List<Track> = emptyList(),
    val ended: Boolean = false,
    val error: EngineError? = null,
)

/** Common contract implemented by the Media3 and libVLC back-ends. */
interface PlayerEngine {
    val name: String
    val caps: Caps
    val state: StateFlow<EngineState>
    val eq: EqController?

    fun load(uri: Uri, startMs: Long, speed: Float)
    fun play()
    fun pause()
    fun seekTo(ms: Long, exact: Boolean = false)
    fun setSpeed(speed: Float)
    fun selectAudio(id: String)
    fun selectSubtitle(id: String?)
    fun addSubtitle(uri: Uri)
    fun setSubtitleDelay(ms: Long)
    fun setAudioDelay(ms: Long)
    fun setSubtitleScale(scale: Float)
    fun setSubtitleStyle(color: Int, bgColor: Int, bottomFraction: Float)
    fun setEnhance(e: Enhance)
    fun setResize(mode: ResizeMode)
    fun setSkipSilence(on: Boolean)
    fun setVolumeBoost(percent: Int)
    fun stepFrame(forward: Boolean)
    fun release()

    @Composable
    fun Surface(modifier: Modifier)
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
