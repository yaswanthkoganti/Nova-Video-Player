package com.nova.player.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsStore by preferencesDataStore("nova_settings")

enum class EnginePref { AUTO, MEDIA3, VLC }

data class Settings(
    val engine: EnginePref = EnginePref.AUTO,
    val theme: Int = 0, // 0 system, 1 light, 2 dark, 3 AMOLED
    val doubleTapSec: Int = 10,
    val longPressSpeed: Float = 2f,
    val gestureBrightness: Boolean = true,
    val gestureVolume: Boolean = true,
    val gestureSeek: Boolean = true,
    val gesturePinch: Boolean = true,
    val seekSensitivity: Float = 1f,
    val skipSilence: Boolean = false,
    val autoPip: Boolean = true,
    val autoPlayNext: Boolean = true,
    val resumePlayback: Boolean = true,
    val subScale: Float = 1f,
    val backgroundAudio: Boolean = true,
    val subColor: Int = 0,        // 0 white, 1 yellow, 2 cyan, 3 green
    val subBg: Int = 1,           // 0 none, 1 translucent, 2 solid
    val subBottom: Float = 0.08f,
    val osApiKey: String = "",
    val osUser: String = "",
    val osPass: String = "",
    val orientation: Int = 0,      // 0 auto-rotate, 1 always landscape, 2 match video
    val subPosMode: Int = 1,       // 0 fixed, 1 auto (follow video aspect ratio)
    val controlColor: Int = 0,     // 0 = theme colour, else ARGB
    val seekColor: Int = 0,        // 0 = follow control colour, else ARGB
    val whisperUrl: String = "https://api.openai.com/v1",
    val whisperKey: String = "",
    val whisperModel: String = "whisper-1",
)

fun subColorInt(i: Int) = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF80DEEA.toInt(), 0xFFA5D6A7.toInt()).getOrElse(i) { 0xFFFFFFFF.toInt() }
fun subBgInt(i: Int) = intArrayOf(0x00000000, 0x99000000.toInt(), 0xFF000000.toInt()).getOrElse(i) { 0 }

object K {
    val engine = stringPreferencesKey("engine")
    val theme = intPreferencesKey("theme")
    val doubleTap = intPreferencesKey("double_tap")
    val longPress = floatPreferencesKey("long_press")
    val gBright = booleanPreferencesKey("g_bright")
    val gVol = booleanPreferencesKey("g_vol")
    val gSeek = booleanPreferencesKey("g_seek")
    val gPinch = booleanPreferencesKey("g_pinch")
    val seekSens = floatPreferencesKey("seek_sens")
    val skipSilence = booleanPreferencesKey("skip_silence")
    val autoPip = booleanPreferencesKey("auto_pip")
    val autoNext = booleanPreferencesKey("auto_next")
    val resume = booleanPreferencesKey("resume")
    val subScale = floatPreferencesKey("sub_scale")
    val bgAudio = booleanPreferencesKey("bg_audio")
    val subColor = intPreferencesKey("sub_color")
    val subBg = intPreferencesKey("sub_bg")
    val subBottom = floatPreferencesKey("sub_bottom")
    val osKey = stringPreferencesKey("os_key")
    val osUser = stringPreferencesKey("os_user")
    val osPass = stringPreferencesKey("os_pass")
    val orientation = intPreferencesKey("orientation")
    val subPosMode = intPreferencesKey("sub_pos_mode")
    val controlColor = intPreferencesKey("control_color")
    val seekColor = intPreferencesKey("seek_color")
    val whisperUrl = stringPreferencesKey("whisper_url")
    val whisperKey = stringPreferencesKey("whisper_key")
    val whisperModel = stringPreferencesKey("whisper_model")
}

class SettingsRepo(private val ctx: Context) {
    val flow: Flow<Settings> = ctx.settingsStore.data.map { p ->
        val d = Settings()
        Settings(
            engine = p[K.engine]?.let { runCatching { EnginePref.valueOf(it) }.getOrNull() } ?: d.engine,
            theme = p[K.theme] ?: d.theme,
            doubleTapSec = p[K.doubleTap] ?: d.doubleTapSec,
            longPressSpeed = p[K.longPress] ?: d.longPressSpeed,
            gestureBrightness = p[K.gBright] ?: d.gestureBrightness,
            gestureVolume = p[K.gVol] ?: d.gestureVolume,
            gestureSeek = p[K.gSeek] ?: d.gestureSeek,
            gesturePinch = p[K.gPinch] ?: d.gesturePinch,
            seekSensitivity = p[K.seekSens] ?: d.seekSensitivity,
            skipSilence = p[K.skipSilence] ?: d.skipSilence,
            autoPip = p[K.autoPip] ?: d.autoPip,
            autoPlayNext = p[K.autoNext] ?: d.autoPlayNext,
            resumePlayback = p[K.resume] ?: d.resumePlayback,
            subScale = p[K.subScale] ?: d.subScale,
            backgroundAudio = p[K.bgAudio] ?: d.backgroundAudio,
            subColor = p[K.subColor] ?: d.subColor,
            subBg = p[K.subBg] ?: d.subBg,
            subBottom = p[K.subBottom] ?: d.subBottom,
            osApiKey = p[K.osKey] ?: d.osApiKey,
            osUser = p[K.osUser] ?: d.osUser,
            osPass = p[K.osPass] ?: d.osPass,
            orientation = p[K.orientation] ?: d.orientation,
            subPosMode = p[K.subPosMode] ?: d.subPosMode,
            controlColor = p[K.controlColor] ?: d.controlColor,
            seekColor = p[K.seekColor] ?: d.seekColor,
            whisperUrl = p[K.whisperUrl] ?: d.whisperUrl,
            whisperKey = p[K.whisperKey] ?: d.whisperKey,
            whisperModel = p[K.whisperModel] ?: d.whisperModel,
        )
    }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        ctx.settingsStore.edit { it[key] = value }
    }
}
