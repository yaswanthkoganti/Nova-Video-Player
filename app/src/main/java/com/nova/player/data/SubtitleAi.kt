package com.nova.player.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

object Langs {
    /** Languages with a small OFFLINE Vosk model. code -> (name, model folder). Model names: verify at alphacephei.com/vosk/models */
    val gen = linkedMapOf(
        "en" to ("English" to "vosk-model-small-en-us-0.15"),
        "te" to ("Telugu" to "vosk-model-small-te-0.42"),
        "hi" to ("Hindi" to "vosk-model-small-hi-0.22"),
        "es" to ("Spanish" to "vosk-model-small-es-0.42"),
        "fr" to ("French" to "vosk-model-small-fr-0.22"),
        "de" to ("German" to "vosk-model-small-de-0.15"),
        "it" to ("Italian" to "vosk-model-small-it-0.22"),
        "pt" to ("Portuguese" to "vosk-model-small-pt-0.3"),
        "ru" to ("Russian" to "vosk-model-small-ru-0.22"),
        "ja" to ("Japanese" to "vosk-model-small-ja-0.22"),
        "zh" to ("Chinese" to "vosk-model-small-cn-0.22"),
    )

    /** Languages offered for generation: every offline one plus Whisper-only languages (Tamil, Malayalam, ...). */
    val cloud: Map<String, String> = LinkedHashMap<String, String>().apply {
        put("te", "Telugu"); put("ta", "Tamil"); put("ml", "Malayalam")
        gen.forEach { (k, v) -> if (k !in this) put(k, v.first) }
        put("kn", "Kannada"); put("bn", "Bengali"); put("mr", "Marathi"); put("ar", "Arabic"); put("ko", "Korean"); put("tr", "Turkish")
    }

    /** On-device translation targets (ML Kit). Malayalam is NOT supported by ML Kit Translate. */
    val tr = listOf(
        "en" to "English", "te" to "Telugu", "ta" to "Tamil", "hi" to "Hindi", "kn" to "Kannada", "bn" to "Bengali",
        "mr" to "Marathi", "gu" to "Gujarati", "ur" to "Urdu", "es" to "Spanish", "fr" to "French", "de" to "German",
        "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "ar" to "Arabic", "ja" to "Japanese",
        "zh" to "Chinese", "ko" to "Korean", "tr" to "Turkish",
    )

    /** OpenSubtitles supports Malayalam too. */
    val search = tr + ("ml" to "Malayalam")

    fun osCode(c: String) = if (c == "zh") "zh-cn" else c
}

class ModelManager(private val ctx: Context) {
    suspend fun ensure(code: String, status: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val name = Langs.gen[code]?.second ?: error("No offline model for $code")
        val dir = File(ctx.filesDir, "vosk/$name")
        if (File(dir, "am").exists()) return@withContext dir
        File(ctx.filesDir, "vosk").mkdirs()
        val zip = File(ctx.cacheDir, "$name.zip")
        val conn = URL("https://alphacephei.com/vosk/models/$name.zip").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        if (conn.responseCode >= 400) error("Model download failed (HTTP ${conn.responseCode}) for $name")
        val total = conn.contentLengthLong
        var done = 0L
        conn.inputStream.use { i ->
            zip.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) status("Downloading speech model ${done * 100 / total}%")
                    ensureActive()
                }
            }
        }
        status("Unpacking model…")
        ZipInputStream(zip.inputStream()).use { z ->
            val root = File(ctx.filesDir, "vosk")
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(root, e.name)
                if (!out.canonicalPath.startsWith(root.canonicalPath)) continue // zip-slip guard
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
        zip.delete()
        dir
    }
}

/** Decodes any audio track to 16 kHz mono signed-16-bit little-endian PCM and streams it in ~1 s blocks. */
object PcmDecoder {
    suspend fun decode(ctx: Context, uri: Uri, onPcm: suspend (ByteArray) -> Unit, onProgress: (Float) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val ti = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track")
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var acc = 0.0; var cnt = 0; var phase = 0.0
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        try {
            while (!outDone) {
                currentCoroutineContext().ensureActive()
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    val bytes = ByteArray(info.size)
                    ob.position(info.offset); ob.get(bytes, 0, info.size)
                    codec.releaseOutputBuffer(o, false)
                    // downmix to mono + box-filter decimate to 16 kHz
                    val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val step = srcRate / 16000.0
                    while (sb.remaining() >= ch) {
                        var m = 0
                        for (c in 0 until ch) m += sb.get()
                        acc += m / ch.toDouble(); cnt++; phase += 1.0
                        if (phase >= step) {
                            val v = (acc / cnt).toInt().toShort().toInt()
                            out.write(v and 0xff); out.write((v shr 8) and 0xff)
                            acc = 0.0; cnt = 0; phase -= step
                        }
                    }
                    if (out.size() >= 32_000) { onPcm(out.toByteArray()); out.reset() }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    if (durUs > 0) onProgress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    srcRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            if (out.size() > 0) onPcm(out.toByteArray())
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
    }
}

/** Offline speech recognition (Vosk). */
object Transcriber {
    suspend fun transcribe(ctx: Context, uri: Uri, modelDir: File, onProgress: (Float) -> Unit): List<Cue> =
        withContext(Dispatchers.Default) {
            val model = Model(modelDir.absolutePath)
            val rec = Recognizer(model, 16000f).apply { setWords(true) }
            val cues = mutableListOf<Cue>()

            fun collect(json: String) {
                val arr = JSONObject(json).optJSONArray("result") ?: return
                var words = mutableListOf<Triple<String, Double, Double>>()
                fun flush() {
                    if (words.isEmpty()) return
                    cues += Cue((words.first().second * 1000).toLong(), (words.last().third * 1000).toLong(), words.joinToString(" ") { it.first })
                    words = mutableListOf()
                }
                for (i in 0 until arr.length()) {
                    val w = arr.getJSONObject(i)
                    val t = Triple(w.getString("word"), w.getDouble("start"), w.getDouble("end"))
                    if (words.isNotEmpty() && (t.second - words.last().third > 0.8 || words.size >= 8 || t.third - words.first().second > 5.0)) flush()
                    words += t
                }
                flush()
            }

            try {
                PcmDecoder.decode(ctx, uri, { pcm -> if (rec.acceptWaveForm(pcm, pcm.size)) collect(rec.result) }, onProgress)
                collect(rec.finalResult)
            } finally {
                runCatching { rec.close() }; runCatching { model.close() }
            }
            cues
        }
}

/**
 * Whisper via any OpenAI-compatible endpoint (OpenAI, Groq, self-hosted). Supports Telugu, Tamil, Malayalam and ~90 more.
 * Audio is cut into 5-minute 16 kHz mono WAV parts (~9.6 MB each) and uploaded one by one.
 */
object CloudWhisper {
    private const val CHUNK_SECONDS = 300

    suspend fun transcribe(
        ctx: Context, uri: Uri, baseUrl: String, key: String, model: String, lang: String, status: (String) -> Unit,
    ): List<Cue> = withContext(Dispatchers.IO) {
        val cues = mutableListOf<Cue>()
        val buf = ByteArrayOutputStream()
        val bytesPerChunk = CHUNK_SECONDS * 16000 * 2
        var part = 0

        suspend fun flush() {
            if (buf.size() == 0) return
            val offsetMs = part * CHUNK_SECONDS * 1000L
            part++
            status("Uploading part $part…")
            val wav = wav(buf.toByteArray())
            buf.reset()
            cues += upload(wav, baseUrl, key, model, lang, offsetMs)
        }

        PcmDecoder.decode(
            ctx, uri,
            { pcm -> buf.write(pcm, 0, pcm.size); if (buf.size() >= bytesPerChunk) flush() },
            { p -> status("Decoding audio ${(p * 100).toInt()}%") },
        )
        flush()
        cues
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(16000); h.putInt(32000); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }

    private fun upload(wav: ByteArray, baseUrl: String, key: String, model: String, lang: String, offsetMs: Long): List<Cue> {
        val boundary = "----nova${System.nanoTime()}"
        val c = URL(baseUrl.trimEnd('/') + "/audio/transcriptions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 20_000; c.readTimeout = 180_000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setChunkedStreamingMode(0)
        c.outputStream.use { o ->
            fun field(n: String, v: String) {
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
            }
            field("model", model)
            field("response_format", "verbose_json")
            field("timestamp_granularities[]", "segment")
            field("language", lang)
            o.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            o.write(wav)
            o.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code >= 400) error("HTTP $code ${text.take(160)}")
        val json = JSONObject(text)
        val segs = json.optJSONArray("segments")
        if (segs != null && segs.length() > 0) {
            return (0 until segs.length()).mapNotNull { i ->
                val s = segs.getJSONObject(i)
                val t = s.optString("text").trim()
                if (t.isEmpty()) null
                else Cue(offsetMs + (s.getDouble("start") * 1000).toLong(), offsetMs + (s.getDouble("end") * 1000).toLong(), t)
            }
        }
        val t = json.optString("text").trim()
        return if (t.isEmpty()) emptyList() else listOf(Cue(offsetMs, offsetMs + CHUNK_SECONDS * 1000L, t))
    }
}

object Translator {
    suspend fun translate(cues: List<Cue>, src: String, dst: String, onProgress: (Float) -> Unit): List<Cue> {
        val tr = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.fromLanguageTag(src) ?: error("On-device translation doesn't support: $src"))
                .setTargetLanguage(TranslateLanguage.fromLanguageTag(dst) ?: error("On-device translation doesn't support: $dst"))
                .build()
        )
        try {
            tr.downloadModelIfNeeded().await()
            return cues.mapIndexed { i, c ->
                onProgress(i / cues.size.toFloat())
                c.copy(text = tr.translate(c.text).await())
            }
        } finally { tr.close() }
    }
}
