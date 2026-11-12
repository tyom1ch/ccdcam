package at.sturq.ccdcam.firmware

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import at.sturq.ccdcam.R
import at.sturq.ccdcam.audio.WavPcm
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Plays the camcorder sounds: the project's rendered WAVs (res/raw, imported by
 * tools/import_ui_sounds.py) where they exist, synthesized tones otherwise. The same PCM is
 * used to burn the sounds into a recording (BURN SOUND).
 */
class BeepPlayer(context: Context) {

    private val res = context.applicationContext.resources
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "HC90eBeep") }
    private val cache = ConcurrentHashMap<Beep, FloatArray>()
    @Volatile private var loop: FloatArray? = null

    private val raw = mapOf(
        Beep.TOUCH to R.raw.ui_click,
        Beep.TOUCH_UP to R.raw.ui_declick,
        Beep.REC_START to R.raw.alert,
        Beep.REC_STOP to R.raw.alert,
        Beep.WARNING to R.raw.alert,
        Beep.POWER_ON to R.raw.power_on,
        Beep.CASSETTE_EJECT to R.raw.cassette_eject,
        Beep.CASSETTE_INSERT to R.raw.cassette_insert,
    )

    private fun load(id: Int): FloatArray = res.openRawResource(id).use { WavPcm.parseMono(it.readBytes()).samples }

    /** Mono PCM at [RATE] of [beep]. */
    fun pcm(beep: Beep): FloatArray = cache.getOrPut(beep) {
        raw[beep]?.let { id -> runCatching { load(id) }.getOrNull() }
            ?: BeepSynth.render(beep, RATE).let { s -> FloatArray(s.size) { s[it] / 32768f } }
    }

    /** Seamless tape-mechanism loop at [RATE] (res/raw/tape_loop). */
    fun tapeLoop(): FloatArray = loop ?: load(R.raw.tape_loop).also { loop = it }

    fun play(beep: Beep) {
        executor.execute {
            var track: AudioTrack? = null
            try {
                val f = pcm(beep)
                val pcm = ShortArray(f.size) { (f[it] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / RATE + 30)
            } catch (t: Throwable) {
                Log.w("BeepPlayer", "beep failed", t)
            } finally {
                try { track?.release() } catch (_: Throwable) {}
            }
        }
    }

    fun release() {
        executor.shutdownNow()
    }

    companion object {
        const val RATE = 48_000
    }
}
