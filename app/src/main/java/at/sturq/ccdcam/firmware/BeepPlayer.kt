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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Plays the camcorder sounds: the project's rendered WAVs (res/raw, imported by
 * tools/import_ui_sounds.py) where they exist, synthesized tones otherwise. Sounds are not mixed
 * into recordings: the phone microphone hears them, as the camcorder mic did.
 */
class BeepPlayer(context: Context) {

    private val res = context.applicationContext.resources
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "HC90eBeep") }
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

    /** Per-sound level: the REC start/stop sound sits 14 dB under the other keys. */
    private val gain = mapOf(Beep.REC_START to 0.2f, Beep.REC_STOP to 0.2f)

    /** BEEP VOL (speaker only; sounds mixed into a clip keep their level). */
    @Volatile var volume = 1f

    /** Mono PCM at [RATE] of [beep]. */
    fun pcm(beep: Beep): FloatArray = cache.getOrPut(beep) {
        val x = raw[beep]?.let { id -> runCatching { load(id) }.getOrNull() }
            ?: BeepSynth.render(beep, RATE).let { s -> FloatArray(s.size) { s[it] / 32768f } }
        val g = gain[beep] ?: 1f
        if (g != 1f) for (i in x.indices) x[i] *= g
        x
    }

    /** Seamless tape-mechanism loop at [RATE] (res/raw/tape_loop). */
    fun tapeLoop(): FloatArray = loop ?: load(R.raw.tape_loop).also { loop = it }

    /** One prepared static track per sound (built on first use, reused afterwards). */
    private val tracks = HashMap<Beep, AudioTrack>()
    /** Sounding track per voice: UI keys/REC/warnings, and the mechanism (cassette, power). */
    private val playing = HashMap<Boolean, AudioTrack>()

    private fun track(beep: Beep): AudioTrack = tracks.getOrPut(beep) {
        val f = pcm(beep)
        val pcm = ShortArray(f.size) { (f[it] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }
        AudioTrack.Builder()
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
            .also { it.write(pcm, 0, pcm.size) }
    }

    /** When the sound of each voice started, and a sound waiting for its turn. */
    private val startedAt = HashMap<Boolean, Long>()
    private val waiting = HashMap<Boolean, ScheduledFuture<*>>()

    /**
     * Plays [beep]. A new sound cuts the one still sounding in the same voice (like the
     * camcorder's single beeper), but only after that one has sounded for [MIN_SOUND_MS]: a
     * press click is never swallowed by an instant release click, and fast taps never queue
     * up into late clicks (only the newest waiting sound is kept).
     */
    fun play(beep: Beep) {
        executor.execute {
            val voice = beep.melody
            waiting.remove(voice)?.cancel(false)
            val since = android.os.SystemClock.uptimeMillis() - (startedAt[voice] ?: 0L)
            val delay = MIN_SOUND_MS - since
            if (playing[voice] != null && delay > 0) {
                waiting[voice] = executor.schedule({ waiting.remove(voice); start(beep) }, delay, TimeUnit.MILLISECONDS)
            } else {
                start(beep)
            }
        }
    }

    private fun start(beep: Beep) {
        try {
            val voice = beep.melody
            playing.remove(voice)?.let { cut(it) }
            val t = track(beep)
            cut(t)                                      // the same sound may still be sounding
            t.reloadStaticData()
            t.setVolume(volume)
            t.play()
            playing[voice] = t
            startedAt[voice] = android.os.SystemClock.uptimeMillis()
        } catch (t: Throwable) {
            Log.w("BeepPlayer", "beep failed", t)
        }
    }

    private fun cut(t: AudioTrack) {
        if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
            t.setVolume(0f)                             // no pop on the cut
            t.pause()
        }
        t.stop()
    }

    fun release() {
        executor.execute {
            waiting.values.forEach { it.cancel(false) }
            waiting.clear()
            tracks.values.forEach { runCatching { it.release() } }
            tracks.clear()
            playing.clear()
        }
        executor.shutdown()
    }

    companion object {
        const val RATE = 48_000
        /** Shortest time a sound plays before the next one may cut it. */
        const val MIN_SOUND_MS = 40L
    }
}
