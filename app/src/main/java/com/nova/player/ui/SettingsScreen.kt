package com.nova.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import com.nova.player.data.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: SettingsRepo, s: Settings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun <T> set(k: Preferences.Key<T>, v: T) { scope.launch { repo.set(k, v) } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            SectionTitle("Playback engine")
            Chips(listOf("Auto (Media3 → libVLC)", "Media3", "libVLC"), s.engine.ordinal) {
                set(K.engine, EnginePref.values()[it].name)
            }
            Text(
                "Auto starts with the hardware-accelerated engine and silently switches to libVLC if a file can't be decoded.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )

            SectionTitle("Appearance")
            Chips(listOf("System", "Light", "Dark", "AMOLED"), s.theme) { set(K.theme, it) }

            SectionTitle("Gestures")
            SwitchRow("Brightness (swipe left side)", s.gestureBrightness) { set(K.gBright, it) }
            SwitchRow("Volume (swipe right side)", s.gestureVolume) { set(K.gVol, it) }
            SwitchRow("Seek (swipe horizontally)", s.gestureSeek) { set(K.gSeek, it) }
            SwitchRow("Pinch to zoom", s.gesturePinch) { set(K.gPinch, it) }
            Slide("Double-tap seek: ${s.doubleTapSec}s", s.doubleTapSec.toFloat(), 5f..60f, 10) { set(K.doubleTap, it.roundToInt()) }
            Slide("Hold-to-speed: ${s.longPressSpeed}x", s.longPressSpeed, 1.5f..4f, 4) { set(K.longPress, (it * 2).roundToInt() / 2f) }
            Slide("Swipe-seek sensitivity: ${"%.1f".format(s.seekSensitivity)}x", s.seekSensitivity, 0.5f..3f, 4) { set(K.seekSens, (it * 2).roundToInt() / 2f) }

            SectionTitle("Playback")
            SwitchRow("Resume where you left off", s.resumePlayback) { set(K.resume, it) }
            SwitchRow("Auto-play next episode", s.autoPlayNext) { set(K.autoNext, it) }
            SwitchRow("Skip silence (Media3)", s.skipSilence) { set(K.skipSilence, it) }
            SwitchRow("Auto picture-in-picture", s.autoPip) { set(K.autoPip, it) }
            SwitchRow("Background audio (keep playing when you leave)", s.backgroundAudio) { set(K.bgAudio, it) }
            Text("Start orientation", Modifier.padding(top = 8.dp))
            Chips(listOf("Auto-rotate", "Always landscape", "Match video"), s.orientation) { set(K.orientation, it) }

            SectionTitle("Player colours")
            Text("Controls (play button, active icons)")
            ColorSwatches(s.controlColor, withHue = true) { set(K.controlColor, it) }
            Text("Seek bar (blank \"A\" follows the controls colour)", Modifier.padding(top = 12.dp))
            ColorSwatches(s.seekColor, withHue = true) { set(K.seekColor, it) }

            SectionTitle("Subtitles")
            Text("Position")
            Chips(listOf("Fixed", "Auto (follow video aspect ratio)"), s.subPosMode) { set(K.subPosMode, it) }
            Text(
                "Auto moves subtitles into the black bar of letterboxed films and otherwise keeps them just above the picture's bottom edge. Applies to the Media3 engine.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
            Slide("Size: ${(s.subScale * 100).roundToInt()}%", s.subScale, 0.5f..2f, 0) { set(K.subScale, (it * 20).roundToInt() / 20f) }
            Text("Colour", Modifier.padding(top = 8.dp))
            Chips(listOf("White", "Yellow", "Cyan", "Green"), s.subColor) { set(K.subColor, it) }
            Text("Background", Modifier.padding(top = 8.dp))
            Chips(listOf("None", "Translucent", "Solid"), s.subBg) { set(K.subBg, it) }
            Slide("Height above ${if (s.subPosMode == 1) "video edge" else "screen bottom"}: ${(s.subBottom * 100).roundToInt()}%", s.subBottom, 0f..0.4f, 0) { set(K.subBottom, (it * 100).roundToInt() / 100f) }

            SectionTitle("OpenSubtitles (online subtitle search)")
            Text("Create a free account and API key at opensubtitles.com. Stored only on this device.", style = MaterialTheme.typography.bodySmall)
            var key by remember(s.osApiKey) { mutableStateOf(s.osApiKey) }
            var usr by remember(s.osUser) { mutableStateOf(s.osUser) }
            var pwd by remember(s.osPass) { mutableStateOf(s.osPass) }
            OutlinedTextField(key, { key = it; set(K.osKey, it) }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(usr, { usr = it; set(K.osUser, it) }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(pwd, { pwd = it; set(K.osPass, it) }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

            SectionTitle("Cloud transcription (Whisper API)")
            Text(
                "Needed for Tamil and Malayalam subtitles (no offline model exists) and optional for others. Works with OpenAI " +
                    "(https://api.openai.com/v1, whisper-1) or Groq (https://api.groq.com/openai/v1, whisper-large-v3-turbo). " +
                    "Audio is uploaded to that service (~10 MB per 5 minutes).",
                style = MaterialTheme.typography.bodySmall,
            )
            var wUrl by remember(s.whisperUrl) { mutableStateOf(s.whisperUrl) }
            var wKey by remember(s.whisperKey) { mutableStateOf(s.whisperKey) }
            var wModel by remember(s.whisperModel) { mutableStateOf(s.whisperModel) }
            OutlinedTextField(wUrl, { wUrl = it; set(K.whisperUrl, it) }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(wModel, { wModel = it; set(K.whisperModel, it) }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(wKey, { wKey = it; set(K.whisperKey, it) }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun Slide(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label)
        Slider(value.coerceIn(range), onChange, valueRange = range, steps = steps)
    }
}
