package at.sturq.ccdcam.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class CameraDataTest {

    @Test
    fun shutterSnapsToCamcorderSteps() {
        assertEquals(100, CameraDataFormat.shutterDen(10_000_000))     // 1/100 s
        assertEquals(50, CameraDataFormat.shutterDen(20_000_000))
        assertEquals(25, CameraDataFormat.shutterDen(33_333_333))      // 1/30 -> nearest PAL step
        assertEquals(1000, CameraDataFormat.shutterDen(1_000_000))
    }

    @Test
    fun gainIn3dbStepsFromBaseIso() {
        assertEquals(0, CameraDataFormat.gainDb(50, 50))
        assertEquals(6, CameraDataFormat.gainDb(100, 50))
        assertEquals(9, CameraDataFormat.gainDb(140, 50))
        assertEquals(18, CameraDataFormat.gainDb(6400, 50))           // capped at the camcorder range
    }

    @Test
    fun formatMatchesManualSample() {
        val d = CameraData(100, CameraDataFormat.fNumber(1.68f), 9, FirmwareSettings.WB_AUTO, FirmwareSettings.EXPOSURE_AUTO)
        assertEquals(listOf("AUTO", "AWB", "100", "F1.8", "9dB"), CameraDataFormat.format(d))
    }

    @Test
    fun logSamplesAtIntervalAndRoundTrips() {
        val log = CameraDataLog(intervalMs = 500)
        log.isoMin = 50
        log.onFrame(0, 10_000_000, 50, 1.8f)                          // not recording: ignored
        log.start(1_000)
        for (t in 1_000L..3_000L step 33) log.onFrame(t, 10_000_000, 100, 1.8f)
        val samples = log.stop()
        assertEquals(listOf(0L, 528L, 1056L, 1584L), samples.map { it.offsetMs })
        assertEquals(samples, CameraDataFormat.decode(CameraDataFormat.encode(samples)))

        val dir = Files.createTempDirectory("dc").toFile()
        CameraDataStore.save(dir, "ccdcam_20261113_101500.mp4", samples)
        assertEquals(samples, CameraDataStore.load(dir, "ccdcam_20261113_101500_dv.mp4"))
        assertEquals(emptyList<CameraDataSample>(), CameraDataStore.load(dir, "other.mp4"))
    }

    @Test
    fun dataAtPosition() {
        val a = CameraData(100, 1.8f, 0, "AUTO", "AUTO")
        val b = a.copy(gainDb = 12)
        val s = listOf(CameraDataSample(0, a), CameraDataSample(1000, b))
        assertEquals(a, CameraDataFormat.at(s, 999))
        assertEquals(b, CameraDataFormat.at(s, 5000))
        assertNull(CameraDataFormat.at(emptyList(), 0))
    }

    @Test
    fun startTimeFromSceneName() {
        val t = CameraDataFormat.startFromName("ccdcam_20261113_101500_dv.mp4")!!
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = t }
        assertEquals(2026, cal.get(java.util.Calendar.YEAR))
        assertEquals(10, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(15, cal.get(java.util.Calendar.MINUTE))
        assertNull(CameraDataFormat.startFromName("VID_x.mp4"))
    }
}
