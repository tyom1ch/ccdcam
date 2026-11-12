package at.sturq.ccdcam.firmware

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.Executors

/** Plays [Beep]s on a background thread. PCM is rendered once per sound and cached. */
class BeepPlayer {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "HC90eBeep") }
    private val cache = HashMap<Beep, ShortArray>()

    fun play(beep: Beep) {
        executor.execute {
            val pcm = cache.getOrPut(beep) { BeepSynth.render(beep) }
            var track: AudioTrack? = null
            try {
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(BeepSynth.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / BeepSynth.SAMPLE_RATE + 30)
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
}
