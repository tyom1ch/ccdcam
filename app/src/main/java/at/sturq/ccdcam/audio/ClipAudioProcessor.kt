package at.sturq.ccdcam.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Post-processes the sound of a finished clip (variant A of the plan: the CameraX Recorder is
 * left untouched). AAC is decoded, run through [AudioChain] chunk by chunk, re-encoded to AAC
 * at the profile's rate, and muxed with the original video samples (copied, not re-encoded).
 * Audio timestamps keep the original timeline, so A/V sync is preserved.
 */
object ClipAudioProcessor {

    private const val TAG = "ClipAudio"
    private const val TIMEOUT_US = 10_000L
    private const val AAC_BITRATE = 128_000

    private class Encoded(val data: ByteArray, val ptsUs: Long)

    /** Writes [src] with processed audio to [dst]. Returns false (and deletes [dst]) on failure. */
    fun process(context: Context, src: Uri, dst: File, profile: AudioProfile, rotationCw: Int): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, src, null)
            var vIdx = -1; var aIdx = -1
            var vFmt: MediaFormat? = null; var aFmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vIdx < 0) { vIdx = i; vFmt = f }
                else if (mime.startsWith("audio/") && aIdx < 0) { aIdx = i; aFmt = f }
            }
            if (vFmt == null || aFmt == null) return false
            ex.selectTrack(aIdx)
            val (encFmt, audio) = transcode(ex, aFmt, profile) ?: return false
            mux(context, src, dst, vIdx, vFmt, encFmt, audio, rotationCw)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "audio processing failed", t)
            dst.delete()
            false
        } finally {
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    private fun encoder(rate: Int, channels: Int): MediaCodec {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun transcode(ex: MediaExtractor, fmt: MediaFormat, profile: AudioProfile): Pair<MediaFormat, List<Encoded>>? {
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0)
        dec.start()
        var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var chain: AudioChain? = null
        var enc: MediaCodec? = null
        var encFmt: MediaFormat? = null
        val out = ArrayList<Encoded>()
        val pending = ArrayDeque<ShortArray>()
        var firstPts = -1L
        var framesFed = 0L
        var decInDone = false; var decOutDone = false; var encInDone = false; var encOutDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (!encOutDone) {
                // 1. extractor -> decoder
                if (!decInDone) {
                    val i = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                // 2. decoder -> DSP -> pending PCM
                if (!decOutDone) {
                    val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        inRate = dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            if (firstPts < 0) firstPts = info.presentationTimeUs
                            val buf = dec.getOutputBuffer(o)!!
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val pcm = ShortArray(info.size / 2)
                            buf.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm)
                            val c = chain ?: AudioChain(profile, inRate, channels).also {
                                chain = it
                                enc = encoder(it.outputRate, channels)
                            }
                            val y = c.process(FloatArray(pcm.size) { pcm[it] / 32768f })
                            pending.addLast(ShortArray(y.size) { (y[it] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() })
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decOutDone = true
                    }
                }
                val e = enc
                val c = chain
                if (e == null || c == null) {
                    if (decOutDone) return null              // no audio at all
                    continue
                }
                // 3. pending PCM -> encoder
                if (!encInDone && (pending.isNotEmpty() || decOutDone)) {
                    val i = e.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = e.getInputBuffer(i)!!
                        buf.clear()
                        val pts = firstPts + framesFed * 1_000_000L / c.outputRate
                        if (pending.isNotEmpty()) {
                            var chunk = pending.removeFirst()
                            val cap = (buf.capacity() / 2 / channels) * channels
                            if (chunk.size > cap) {
                                pending.addFirst(chunk.copyOfRange(cap, chunk.size))
                                chunk = chunk.copyOf(cap)
                            }
                            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(chunk)
                            framesFed += chunk.size / channels
                            e.queueInputBuffer(i, 0, chunk.size * 2, pts, 0)
                        } else {
                            e.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encInDone = true
                        }
                    }
                }
                // 4. encoder -> encoded list
                val o = e.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encFmt = e.outputFormat
                } else if (o >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buf = e.getOutputBuffer(o)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        out += Encoded(ByteArray(info.size).also { buf.get(it) }, info.presentationTimeUs)
                    }
                    e.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encOutDone = true
                }
            }
            return (encFmt ?: return null) to out
        } finally {
            try { dec.stop() } catch (_: Throwable) {}
            dec.release()
            enc?.let { try { it.stop() } catch (_: Throwable) {}; it.release() }
        }
    }

    private fun mux(
        context: Context, src: Uri, dst: File, vIdx: Int, vFmt: MediaFormat, aFmt: MediaFormat,
        audio: List<Encoded>, rotationCw: Int,
    ) {
        val ex = MediaExtractor()
        ex.setDataSource(context, src, null)
        ex.selectTrack(vIdx)
        val m = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            m.setOrientationHint(rotationCw)
            val vt = m.addTrack(vFmt)
            val at = m.addTrack(aFmt)
            m.start()
            val size = if (vFmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = ByteBuffer.allocateDirect(maxOf(size, 4 shl 20))
            val info = MediaCodec.BufferInfo()
            var ai = 0
            fun writeAudio(a: Encoded) {
                info.set(0, a.data.size, a.ptsUs, 0)
                m.writeSampleData(at, ByteBuffer.wrap(a.data), info)
            }
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val pts = ex.sampleTime
                while (ai < audio.size && audio[ai].ptsUs <= pts) writeAudio(audio[ai++])
                val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, n, pts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(vt, buf, info)
                ex.advance()
            }
            while (ai < audio.size) writeAudio(audio[ai++])
            m.stop()
        } finally {
            m.release()
            ex.release()
        }
    }
}
