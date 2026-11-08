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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object Langs {
    /** Languages with a small offline Vosk model. code -> (name, model folder) */
    val gen = linkedMapOf(
        "en" to ("English" to "vosk-model-small-en-us-0.15"),
        "es" to ("Spanish" to "vosk-model-small-es-0.42"),
        "fr" to ("French" to "vosk-model-small-fr-0.22"),
        "de" to ("German" to "vosk-model-small-de-0.15"),
        "it" to ("Italian" to "vosk-model-small-it-0.22"),
        "pt" to ("Portuguese" to "vosk-model-small-pt-0.3"),
        "ru" to ("Russian" to "vosk-model-small-ru-0.22"),
        "hi" to ("Hindi" to "vosk-model-small-hi-0.22"),
        "ja" to ("Japanese" to "vosk-model-small-ja-0.22"),
        "zh" to ("Chinese" to "vosk-model-small-cn-0.22"),
    )
    /** Translation targets (ML Kit codes). */
    val tr = listOf(
        "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
        "pt" to "Portuguese", "ru" to "Russian", "hi" to "Hindi", "ar" to "Arabic", "ja" to "Japanese",
        "zh" to "Chinese", "ko" to "Korean", "tr" to "Turkish",
    )
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
            val outBuf = java.io.ByteArrayOutputStream()
            fun feed(bytes: ByteArray) {
                val sb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val step = srcRate / 16000.0
                outBuf.reset()
                while (sb.remaining() >= ch) {
                    var m = 0
                    for (c in 0 until ch) m += sb.get()
                    acc += m / ch.toDouble(); cnt++; phase += 1.0
                    if (phase >= step) {
                        val v = (acc / cnt).toInt().toShort()
                        outBuf.write(v.toInt() and 0xff); outBuf.write((v.toInt() shr 8) and 0xff)
                        acc = 0.0; cnt = 0; phase -= step
                    }
                }
                val arr = outBuf.toByteArray()
                if (arr.isNotEmpty() && rec.acceptWaveForm(arr, arr.size)) collect(rec.result)
            }

            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            try {
                while (!outDone) {
                    ensureActive()
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
                    when {
                        o >= 0 -> {
                            val ob = codec.getOutputBuffer(o)!!
                            val bytes = ByteArray(info.size)
                            ob.position(info.offset); ob.get(bytes, 0, info.size)
                            feed(bytes)
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                            if (durUs > 0) onProgress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                        }
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val nf = codec.outputFormat
                            srcRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
                collect(rec.finalResult)
            } finally {
                runCatching { codec.stop() }; codec.release(); ex.release(); rec.close(); model.close()
            }
            cues
        }
}

object Translator {
    suspend fun translate(cues: List<Cue>, src: String, dst: String, onProgress: (Float) -> Unit): List<Cue> {
        val tr = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.fromLanguageTag(src) ?: error("Unsupported: $src"))
                .setTargetLanguage(TranslateLanguage.fromLanguageTag(dst) ?: error("Unsupported: $dst"))
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
