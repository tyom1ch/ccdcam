package at.sturq.ccdcam.firmware

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Camera setting data recorded with a scene, shown in playback as DATA CODE → CAMERA DATA
 * (HC90 guide p. 56: exposure, white balance, gain, shutter speed, aperture value; the
 * sample screen shows `AUTO AWB 100 F1.8 9dB`).
 *
 * The values are the phone camera's real per-frame values, expressed the way the camcorder
 * displays them (approximation: shutter rounded to the nearest camcorder step, gain in 3 dB
 * steps relative to the sensor's base ISO, aperture not below the HC90's F1.8).
 */
data class CameraData(
    /** Shutter speed denominator (1/[shutterDen] s), as the camcorder shows it ("100"). */
    val shutterDen: Int,
    /** Aperture value, one decimal ("F1.8"). */
    val fNumber: Float,
    /** Gain in dB (0..18, 3 dB steps). */
    val gainDb: Int,
    /** WHITE BAL. setting id (FirmwareSettings.WB_*). */
    val whiteBal: String,
    /** EXPOSURE setting id (AUTO or the manual step). */
    val exposure: String,
)

data class CameraDataSample(val offsetMs: Long, val data: CameraData)

object CameraDataFormat {
    /** Shutter speeds a PAL camcorder displays (denominators). */
    private val SHUTTER_STEPS = intArrayOf(
        3, 6, 12, 25, 50, 60, 100, 120, 150, 215, 300, 425, 600, 1000, 1250, 1750, 2500, 3500, 6000, 10000,
    )
    const val MIN_F = 1.8f
    const val MAX_GAIN_DB = 18

    fun shutterDen(exposureNs: Long): Int {
        if (exposureNs <= 0) return SHUTTER_STEPS.last()
        val den = 1e9 / exposureNs
        return SHUTTER_STEPS.minBy { abs(ln(it / den)) }
    }

    fun gainDb(iso: Int, isoMin: Int): Int {
        if (iso <= 0 || isoMin <= 0 || iso <= isoMin) return 0
        val db = 20 * log10(iso.toDouble() / isoMin)
        return ((db / 3).roundToInt() * 3).coerceIn(0, MAX_GAIN_DB)
    }

    fun fNumber(aperture: Float): Float = (maxOf(aperture, MIN_F) * 10).roundToInt() / 10f

    fun whiteBalLabel(wb: String): String = when (wb) {
        FirmwareSettings.WB_ONE_PUSH -> "OP"        // Sony uses pictograms; text stands in for them
        FirmwareSettings.WB_INDOOR -> "IN"
        FirmwareSettings.WB_OUTDOOR -> "OUT"
        else -> "AWB"
    }

    fun exposureLabel(exposure: String): String =
        if (exposure == FirmwareSettings.EXPOSURE_AUTO) "AUTO" else exposure

    fun format(d: CameraData): List<String> = listOf(
        exposureLabel(d.exposure), whiteBalLabel(d.whiteBal),
        d.shutterDen.toString(), "F" + "%.1f".format(Locale.US, d.fNumber), "${d.gainDb}dB",
    )

    /** Placeholders when a scene has no camera data (recorded before this feature). */
    val EMPTY: List<String> = listOf("---", "---", "---", "F--", "--dB")

    /** The data in effect at [offsetMs] into the scene. */
    fun at(samples: List<CameraDataSample>, offsetMs: Long): CameraData? {
        if (samples.isEmpty()) return null
        var r = samples.first().data
        for (s in samples) if (s.offsetMs <= offsetMs) r = s.data else break
        return r
    }

    fun encode(samples: List<CameraDataSample>): String = buildString {
        append("hc90e-datacode 1\n")
        for (s in samples) {
            val d = s.data
            append("${s.offsetMs};${d.shutterDen};${d.fNumber};${d.gainDb};${d.whiteBal};${d.exposure}\n")
        }
    }

    fun decode(text: String): List<CameraDataSample> = text.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val p = line.split(';')
            if (p.size != 6) return@mapNotNull null
            try {
                CameraDataSample(p[0].toLong(), CameraData(p[1].toInt(), p[2].toFloat(), p[3].toInt(), p[4], p[5]))
            } catch (_: NumberFormatException) { null }
        }
        .toList()

    /** Scene name without extension and the `_dv` suffix of a KEEP copy. */
    fun sceneKey(displayName: String): String = displayName.substringBeforeLast('.').removeSuffix("_dv")

    /** Recording start encoded in the file name (`ccdcam_yyyyMMdd_HHmmss`), or null. */
    fun startFromName(displayName: String): Long? {
        val m = Regex("""(\d{8}_\d{6})""").find(displayName) ?: return null
        return try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(m.value)?.time
        } catch (_: Exception) { null }
    }
}

/**
 * Collects camera data while recording: at most one sample per [intervalMs] from the capture
 * results (called on the camera thread).
 */
class CameraDataLog(private val intervalMs: Long = 500) {
    @Volatile var isoMin = 100
    @Volatile var whiteBal = FirmwareSettings.WB_AUTO
    @Volatile var exposure = FirmwareSettings.EXPOSURE_AUTO

    private var startMs = -1L
    private var lastMs = Long.MIN_VALUE / 2
    private val samples = ArrayList<CameraDataSample>()

    @Synchronized fun start(nowMs: Long) {
        samples.clear()
        startMs = nowMs
        lastMs = Long.MIN_VALUE / 2
    }

    @Synchronized fun stop(): List<CameraDataSample> {
        startMs = -1
        return samples.toList()
    }

    @Synchronized fun onFrame(nowMs: Long, exposureNs: Long, iso: Int, aperture: Float) {
        if (startMs < 0 || nowMs - lastMs < intervalMs) return
        lastMs = nowMs
        samples += CameraDataSample(
            nowMs - startMs,
            CameraData(
                CameraDataFormat.shutterDen(exposureNs), CameraDataFormat.fNumber(aperture),
                CameraDataFormat.gainDb(iso, isoMin), whiteBal, exposure,
            ),
        )
    }
}

/** Data code files next to the app's private data: `files/datacode/<scene>.txt`. */
object CameraDataStore {
    fun file(dir: File, displayName: String) = File(dir, CameraDataFormat.sceneKey(displayName) + ".txt")

    fun save(dir: File, displayName: String, samples: List<CameraDataSample>) {
        if (samples.isEmpty()) return
        dir.mkdirs()
        file(dir, displayName).writeText(CameraDataFormat.encode(samples))
    }

    fun load(dir: File, displayName: String): List<CameraDataSample> {
        val f = file(dir, displayName)
        return if (f.isFile) CameraDataFormat.decode(f.readText()) else emptyList()
    }
}
