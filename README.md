# Nova Player v0.2

Android video player: VLC-class format coverage + MX-style gestures, Material 3 Expressive UI.
Kotlin · Jetpack Compose (Material 3 Expressive) · Media3 + libVLC · Room · DataStore.

## Get the APK
This source was written without an Android SDK/compiler available, so no APK is bundled.
Two ways to get one:

1. **GitHub (no local setup):** push this folder to a new GitHub repo. The workflow in
   `.github/workflows/build-apk.yml` builds it; download `NovaPlayer-apk` from the Actions run
   (use `app-universal-debug.apk`). Debug builds are auto-signed and install directly.
2. **Android Studio / CLI:** open the folder and Run, or `./build-apk.sh`
   (JDK 17 + Android SDK 35). Then `adb install -r app/build/outputs/apk/debug/app-universal-debug.apk`.

Expect to fix a handful of compile errors on first build (unverified API names, see below).

## Features
| Area | Status |
|---|---|
| Material 3 Expressive theme (expressive motion, large shapes, flexible app bars, shape-morphing play button, LoadingIndicator) | done |
| Engines: Media3 + libVLC with automatic fallback | done |
| MX-style gestures, hold-to-2x, pinch zoom, lock, aspect modes, A-B loop, frame step, speed, bookmarks | done |
| **Enhance video quality**: live GPU shader (sharpen/contrast/saturation/brightness, presets) – Media3 engine only; image filter, not AI upscaling | done |
| **Equalizer** (Android audiofx on Media3, 10-band on libVLC) + volume boost to 300% | done |
| **Background audio** with notification/lock-screen controls (Media3 engine; libVLC keeps playing without notification) | done |
| **Seek-bar thumbnail previews** | done |
| **On-device auto-subtitles** (Vosk offline ASR, 10 languages, model downloaded on first use) | done |
| **On-device subtitle translation** (ML Kit, SRT only) | done |
| **Online subtitle search** (OpenSubtitles.com; needs your own API key + account) | done |
| **Subtitle rendering** (SRT/VTT/SSA via Media3, ASS via libVLC; colour, background, position, size) | done |
| **SMB** browse + play (jcifs-ng listing, libVLC playback) | done |
| **DLNA/UPnP** discovery + browse + play | done |
| **Chromecast** (local files served via built-in HTTP server) | done |

## Known limits / risks
- Not compiled or tested on a device. Material 3 `1.4.0-alpha15`, Media3 `GlProgram`/`BaseGlShaderProgram`, libVLC
  `nextFrame()`, jcifs-ng and Vosk model URLs are from memory and may need adjusting.
- Auto-subtitles use Vosk, not Whisper: lower accuracy, no punctuation. Whisper.cpp needs an NDK build (not included).
- Video enhancement is a GPU filter, not AI super-resolution. It is not available on the libVLC engine.
- Chromecast plays only formats the receiver supports (MP4/H.264 safest); smb:// sources can't be cast.
- Background audio stops if Android kills the app; the player state lives in the activity's ViewModel.
- Cast needs Google Play Services. OpenSubtitles credentials are stored unencrypted in DataStore.
