package com.nova.player.engine

import android.media.audiofx.Equalizer
import org.videolan.libvlc.MediaPlayer

/** android.media.audiofx.Equalizer bound to ExoPlayer's audio session. */
class Media3Eq : EqController {
    private var eq: Equalizer? = null
    private var lv = FloatArray(5)
    private var on = false
    private var freqs = listOf(60, 230, 910, 3600, 14000)
    private var lo = -12f
    private var hi = 12f
    private var names: List<String> = emptyList()

    fun bind(session: Int) {
        runCatching { eq?.release() }
        eq = runCatching { Equalizer(0, session) }.getOrNull()
        eq?.let { e ->
            val n = e.numberOfBands.toInt()
            freqs = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 }
            lo = e.bandLevelRange[0] / 100f
            hi = e.bandLevelRange[1] / 100f
            names = (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) }
            if (lv.size != n) lv = FloatArray(n)
            apply()
        }
    }

    private fun apply() {
        val e = eq ?: return
        lv.forEachIndexed { i, db -> runCatching { e.setBandLevel(i.toShort(), (db * 100).toInt().toShort()) } }
        runCatching { e.setEnabled(on) }
    }

    fun release() { runCatching { eq?.release() }; eq = null }

    override val bands get() = freqs
    override val minDb get() = lo
    override val maxDb get() = hi
    override val presets get() = names
    override var enabled: Boolean
        get() = on
        set(v) { on = v; apply() }

    override fun levels(): List<Float> = lv.toList()
    override fun setLevel(band: Int, db: Float) { if (band in lv.indices) { lv[band] = db; apply() } }
    override fun usePreset(index: Int) {
        val e = eq ?: return
        runCatching {
            e.usePreset(index.toShort())
            lv = FloatArray(lv.size) { b -> e.getBandLevel(b.toShort()) / 100f }
        }
    }
}

/** libVLC's built-in 10-band equalizer. */
class VlcEq(private val mp: MediaPlayer) : EqController {
    private val n = MediaPlayer.Equalizer.getBandCount()
    private var eq = MediaPlayer.Equalizer.create()
    private var on = false

    override val bands = (0 until n).map { MediaPlayer.Equalizer.getBandFrequency(it).toInt() }
    override val minDb = -20f
    override val maxDb = 20f
    override val presets = (0 until MediaPlayer.Equalizer.getPresetCount()).map { MediaPlayer.Equalizer.getPresetName(it) }
    override var enabled: Boolean
        get() = on
        set(v) { on = v; mp.setEqualizer(if (v) eq else null) }

    override fun levels(): List<Float> = (0 until n).map { eq.getAmp(it) }
    override fun setLevel(band: Int, db: Float) { eq.setAmp(band, db); if (on) mp.setEqualizer(eq) }
    override fun usePreset(index: Int) { eq = MediaPlayer.Equalizer.createFromPreset(index); if (on) mp.setEqualizer(eq) }
}
