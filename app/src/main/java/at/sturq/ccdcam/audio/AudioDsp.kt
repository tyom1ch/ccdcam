package at.sturq.ccdcam.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Sound of the emulated camcorder. The DV formats are documented for the HC90E (PCM 16 bit /
 * 48 kHz, 12 bit / 32 kHz); the microphone EQ, AGC, preamp hiss and limiter values are
 * approximations of a 2005 consumer camcorder. Nothing here imitates MP3 or VHS artefacts.
 */
enum class AudioProfile(
    val outRate: Int,
    val hpHz: Float,
    val lpHz: Float,
    val presenceHz: Float,
    val presenceDb: Float,
    val agc: Boolean,
    val agcTargetDb: Float,
    val agcMaxGainDb: Float,
    val agcAttackMs: Float,
    val agcReleaseMs: Float,
    val hissDb: Float,
    val drive: Float,
    val bits12: Boolean,
    /** Bit crusher: sample-and-hold rate (0 = off), bits, wet share. Retro colour, not DV. */
    val crushHz: Int = 0,
    val crushBits: Int = 16,
    val crushMix: Float = 0f,
    /** Share of the sample-and-hold stepping (the plug-in's "Quantize"), 0..1. */
    val crushHold: Float = 1f,
    /** Reconstruction low-pass at crushHz / 2 after the crusher (the plug-in's DAC filter). */
    val crushDac: Boolean = false,
    /** 4th-order low cut (thin camcorder mic, no bass). */
    val steepLowCut: Boolean = false,
) {
    /** Sony DV, 16 bit 48 kHz: full band, gentle AGC. */
    DV16_48K(48_000, 80f, 18_000f, 3_000f, 1.5f, true, -20f, 18f, 50f, 1_500f, -72f, 1.2f, false),

    /** Sony DV, 12 bit 32 kHz: ~14.5 kHz band, nonlinear 12-bit quantisation. */
    DV12_32K(32_000, 80f, 14_500f, 3_000f, 1.5f, true, -20f, 18f, 50f, 1_500f, -66f, 1.3f, true),

    /** Small built-in mic, strong pumping AGC, audible preamp hiss. */
    LOFI(32_000, 180f, 7_000f, 2_500f, 4f, true, -16f, 26f, 20f, 800f, -58f, 2.0f, true),

    /**
     * Retro lo-fi: thin mic without lows, pumping AGC, slightly bit-crushed like a 19 kHz
     * bit crusher with ADC/DAC filters (band ends near 9.5 kHz), 8 bits, little sample-hold
     * stepping, 40 % wet. Artistic, not a documented DV mode.
     */
    RETRO(48_000, 300f, 9_500f, 2_500f, 3f, true, -18f, 22f, 25f, 1_000f, -62f, 1.6f, false,
        crushHz = 19_000, crushBits = 8, crushMix = 0.4f, crushHold = 0.3f, crushDac = true,
        steepLowCut = true),

    /** No processing (the file is left as recorded). */
    CLEAN(0, 0f, 0f, 0f, 0f, false, 0f, 0f, 0f, 0f, 0f, 1f, false);

    val bypass: Boolean get() = this == CLEAN
}

/** RBJ-cookbook biquad, direct form I, one channel. */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y
        return y.toFloat()
    }

    companion object {
        private const val Q = 0.7071

        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun lowPass(f: Float, fs: Int): Biquad {
            val w = 2 * PI * f / fs; val c = cos(w); val a = sin(w) / (2 * Q)
            return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + a, -2 * c, 1 - a)
        }

        fun highPass(f: Float, fs: Int): Biquad {
            val w = 2 * PI * f / fs; val c = cos(w); val a = sin(w) / (2 * Q)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + a, -2 * c, 1 - a)
        }

        fun peaking(f: Float, db: Float, fs: Int, q: Double = 1.0): Biquad {
            val amp = 10.0.pow(db / 40.0); val w = 2 * PI * f / fs; val c = cos(w); val a = sin(w) / (2 * q)
            return make(1 + a * amp, -2 * c, 1 - a * amp, 1 + a / amp, -2 * c, 1 - a / amp)
        }
    }
}

/** Camcorder AGC: RMS follower with fast attack / slow release, gain capped at [maxGainDb]. */
class Agc(fs: Int, targetDb: Float, maxGainDb: Float, attackMs: Float, releaseMs: Float) {
    private val target = dbToLin(targetDb)
    private val maxGain = dbToLin(maxGainDb)
    private val att = 1 - kotlin.math.exp(-1.0 / (fs * attackMs / 1000.0)).toFloat()
    private val rel = 1 - kotlin.math.exp(-1.0 / (fs * releaseMs / 1000.0)).toFloat()
    private var env = target * target
    var gain = 1f
        private set

    fun process(x: Float): Float {
        val p = x * x
        env += (if (p > env) att else rel) * (p - env)
        val want = (target / sqrt(env + 1e-12f)).coerceIn(0.25f, maxGain)
        gain += (if (want < gain) att else rel) * (want - gain)
        return x * gain
    }
}

object AudioMath {
    fun dbToLin(db: Float) = 10f.pow(db / 20f)

    /** Soft limiter with unity gain at full scale: tanh(d·x) / tanh(d). Never exceeds ±1. */
    fun softClip(x: Float, drive: Float): Float = (tanh(drive * x) / tanh(drive)).coerceIn(-1f, 1f)

    private const val MU = 255.0

    /**
     * DV 12-bit nonlinear quantisation approximated by μ-law companding to 12 bits: fine steps
     * for quiet sounds, coarse for loud ones.
     */
    fun quantize12(x: Float): Float {
        val v = x.coerceIn(-1f, 1f).toDouble()
        val c = sign(v) * ln(1 + MU * abs(v)) / ln(1 + MU)
        val q = (c * 2047).roundToInt() / 2047.0
        return (sign(q) * ((1 + MU).pow(abs(q)) - 1) / MU).toFloat()
    }

    fun quantize16(x: Float): Float = (x.coerceIn(-1f, 1f) * 32767f).roundToInt() / 32767f
}

private fun dbToLin(db: Float) = AudioMath.dbToLin(db)

/** Streaming linear-interpolation resampler for interleaved audio. */
class Resampler(private val inRate: Int, private val outRate: Int, private val channels: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0                         // in input frames, relative to [last]
    private var last = FloatArray(channels)       // last frame of the previous chunk
    private var started = false

    fun process(input: FloatArray): FloatArray {
        if (inRate == outRate) return input
        val frames = input.size / channels
        if (frames == 0) return FloatArray(0)
        val out = ArrayList<Float>((frames / step).toInt() * channels + channels)
        // frame index -1 = last of previous chunk
        fun sample(i: Int, ch: Int) = if (i < 0) last[ch] else input[i * channels + ch]
        if (!started) { pos = 1.0; started = true }   // first chunk: start at frame 0
        while (pos < frames) {
            val i0 = kotlin.math.floor(pos).toInt() - 1
            val t = (pos - kotlin.math.floor(pos)).toFloat()
            for (ch in 0 until channels) {
                val a = sample(i0, ch); val b = sample(i0 + 1, ch)
                out += a + (b - a) * t
            }
            pos += step
        }
        pos -= frames
        for (ch in 0 until channels) last[ch] = input[(frames - 1) * channels + ch]
        return out.toFloatArray()
    }
}

/**
 * The whole camcorder audio path for one clip, applied chunk by chunk (constant memory):
 * mic EQ → preamp hiss → AGC → soft limiter → (resample) → DV quantisation.
 */
class AudioChain(
    private val profile: AudioProfile, private val inRate: Int, private val channels: Int, seed: Long = 1L,
    /** Tests only: false skips the bit crusher of the profile. */
    private val crush: Boolean = true,
) {
    private val outRate = if (profile.outRate > 0) profile.outRate else inRate
    private val hp = Array(channels) { Biquad.highPass(profile.hpHz, inRate) }
    private val hp2 = Array(channels) { Biquad.highPass(profile.hpHz, inRate) }
    private val crushStep = profile.crushHz.toDouble() / inRate
    private var crushPhase = 1.0
    private val held = FloatArray(channels)
    private val crushLevels = (1 shl (profile.crushBits - 1)).toFloat()
    private val dac = Array(channels) { Biquad.lowPass(minOf(profile.crushHz / 2f, inRate * 0.45f), inRate) }
    // 4th order (two sections): mic band limit and anti-alias before 32 kHz.
    private val lp = Array(channels) { Biquad.lowPass(minOf(profile.lpHz, inRate * 0.45f), inRate) }
    private val lp2 = Array(channels) { Biquad.lowPass(minOf(profile.lpHz, inRate * 0.45f), inRate) }
    private val pk = Array(channels) { Biquad.peaking(profile.presenceHz, profile.presenceDb, inRate) }
    private val agc = Array(channels) {
        Agc(inRate, profile.agcTargetDb, profile.agcMaxGainDb, profile.agcAttackMs, profile.agcReleaseMs)
    }
    private val hiss = dbToLin(profile.hissDb)
    private var rng = seed
    private val resampler = Resampler(inRate, outRate, channels)

    val outputRate: Int get() = outRate

    private fun noise(): Float {
        rng = rng * 6364136223846793005L + 1442695040888963407L
        return ((rng ushr 40).toInt() / 16777216f) * 2f - 1f
    }

    /** Interleaved float samples in [-1, 1] at the input rate -> processed, at [outputRate]. */
    fun process(chunk: FloatArray): FloatArray {
        if (profile.bypass) return chunk
        val y = FloatArray(chunk.size)
        for (i in chunk.indices) {
            val ch = i % channels
            var s = hp[ch].process(chunk[i])
            if (profile.steepLowCut) s = hp2[ch].process(s)
            s = pk[ch].process(s)
            s = lp2[ch].process(lp[ch].process(s))
            s += noise() * hiss
            if (profile.agc) s = agc[ch].process(s)
            if (crush && profile.crushHz > 0) {
                // sample-and-hold at crushHz (aliasing on purpose), then coarse quantisation
                if (ch == 0) {
                    crushPhase += crushStep
                    if (crushPhase >= 1.0) { crushPhase -= 1.0; for (c in 0 until channels) held[c] = Float.NaN }
                }
                if (held[ch].isNaN()) held[ch] = s
                val stepped = s + (held[ch] - s) * profile.crushHold
                var q = kotlin.math.round(stepped.coerceIn(-1f, 1f) * crushLevels) / crushLevels
                if (profile.crushDac) q = dac[ch].process(q)
                s = s * (1 - profile.crushMix) + q * profile.crushMix
            }
            y[i] = AudioMath.softClip(s, profile.drive)
        }
        val r = resampler.process(y)
        for (i in r.indices) r[i] = if (profile.bits12) AudioMath.quantize12(r[i]) else AudioMath.quantize16(r[i])
        return r
    }
}
