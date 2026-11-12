package at.sturq.ccdcam.osd

import at.sturq.ccdcam.firmware.Lamp
import at.sturq.ccdcam.firmware.RecMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsdSceneLayoutTest {

    private val font = OsdFontAtlasTest.font
    private val atlas = OsdFontAtlasTest.atlas

    private fun state(
        recording: Boolean = false,
        message: String? = null,
        dateTimeMs: Long? = null,
        lamp: Lamp = Lamp.TAPE,
    ) = OsdIndicators(
        lamp = lamp, recording = recording, recMode = RecMode.SP, counterMs = 365_000,
        playbackMs = 0, transport = OsdIndicators.Transport.STOP, batteryPct = 73,
        remainingMin = 57, showRemaining = true, cassetteIconOn = true, dateTimeMs = dateTimeMs,
        message = message,
    )

    private fun texts(items: List<OsdItem>) = items.filterIsInstance<OsdItem.Text>().map { it.text }

    @Test
    fun recordingShowsRedRecAndCounter() {
        val items = OsdScene.build(state(recording = true), forVideo = true)
        val rec = items.filterIsInstance<OsdItem.Text>().first { it.text == "REC" }
        assertEquals(OsdScene.REC_RED, rec.color)
        assertTrue("0:06:05" in texts(items))
        assertTrue("57min" in texts(items))
        assertTrue(items.any { it is OsdItem.Sprite && it.name == "sp" })
        assertTrue(items.any { it is OsdItem.Sprite && it.name == "cassette" })
    }

    @Test
    fun standbyIsGreen() {
        val stby = OsdScene.build(state(), forVideo = false).filterIsInstance<OsdItem.Text>().first { it.text == "STBY" }
        assertEquals(OsdScene.STBY_GREEN, stby.color)
    }

    @Test
    fun videoNeverContainsMessages() {
        assertFalse("Insert a cassette" in texts(OsdScene.build(state(message = "Insert a cassette"), forVideo = true)))
        assertTrue("Insert a cassette" in texts(OsdScene.build(state(message = "Insert a cassette"), forVideo = false)))
    }

    @Test
    fun dateRowOnlyWhenGiven() {
        val noDate = OsdScene.build(state(), forVideo = true)
        assertTrue(texts(noDate).none { it.contains(" 20") })
        val t = java.util.GregorianCalendar(2026, 10, 12, 21, 4, 37).timeInMillis
        val withDate = texts(OsdScene.build(state(dateTimeMs = t), forVideo = true))
        assertTrue("12 11 2026" in withDate)
        assertTrue("21:04:37" in withDate)
    }

    @Test
    fun batteryLevelSprite() {
        fun level(pct: Int) = OsdScene.build(state().copy(batteryPct = pct), true)
            .filterIsInstance<OsdItem.Sprite>().first { it.name.startsWith("battery_") }.name
        assertEquals("battery_4", level(100))
        assertEquals("battery_3", level(73))
        assertEquals("battery_0", level(5))
        assertEquals("battery_0", level(-1))
    }

    @Test
    fun playEditShowsTimeCodeInsteadOfCounter() {
        val items = texts(OsdScene.build(state(lamp = Lamp.PLAY_EDIT).copy(playbackMs = 386_840), false))
        assertTrue("0:06:26:21" in items)
        assertFalse("STBY" in items)
    }

    @Test
    fun rightAlignedTextEndsAtItsAnchor() {
        val item = OsdItem.Text("0:06:05", 1009f, 18f, OsdItem.Align.RIGHT, OsdScene.WHITE)
        val quads = OsdLayout.quads(listOf(item), font, atlas)
        assertEquals(7, quads.size)
        // pen width = 50 OSD px; the first glyph starts there (minus its 1 px outline border)
        val start = 1009f - font.width("0:06:05") * OsdScene.PX
        assertEquals(start - OsdScene.PX, quads.first().left, 0.01f)
        // quads keep the atlas cell proportions (no stretch): w/h = (10 PX)/(16 PY)
        val q = quads.first()
        assertEquals(10 * OsdScene.PX / (16 * OsdScene.PY), (q.right - q.left) / (q.bottom - q.top), 1e-4f)
    }

    @Test
    fun wholeSceneStaysInsideTheLcd() {
        val t = java.util.GregorianCalendar(2026, 11, 31, 23, 59, 59).timeInMillis
        val quads = OsdLayout.quads(OsdScene.build(state(recording = true, dateTimeMs = t), true), font, atlas)
        quads.forEach {
            assertTrue("$it", it.left >= 0f && it.top >= 0f && it.right <= OsdScene.LCD_W && it.bottom <= OsdScene.LCD_H)
        }
    }

    @Test
    fun spacesProduceNoQuads() {
        val quads = OsdLayout.quads(listOf(OsdItem.Text("1 2", 0f, 0f, OsdItem.Align.LEFT, OsdScene.WHITE)), font, atlas)
        assertEquals(2, quads.size)
    }

    @Test
    fun glVerticesFlipYAndMapAtlasTexels() {
        val q = OsdQuad(0f, 0f, OsdScene.LCD_W, OsdScene.LCD_H, IRect(0, 0, 32, 16), OsdScene.REC_RED)
        val v = OsdVertices.build(listOf(q), 256, 128)
        assertEquals(6 * OsdVertices.FLOATS_PER_VERTEX, v.size)
        // first vertex: LCD top-left -> uv (0, 1) in GL (y up), atlas (0, 0)
        assertEquals(0f, v[0], 1e-6f); assertEquals(1f, v[1], 1e-6f)
        assertEquals(0f, v[2], 1e-6f); assertEquals(0f, v[3], 1e-6f)
        // colour REC red, opaque
        assertEquals(0xF0 / 255f, v[4], 1e-6f); assertEquals(1f, v[7], 1e-6f)
        // second vertex: LCD top-right -> atlas u = 32/256
        assertEquals(1f, v[8], 1e-6f); assertEquals(32f / 256f, v[10], 1e-6f)
    }

    @Test
    fun nightShotIndicatorOnlyWhenOn() {
        fun has(on: Boolean, video: Boolean) = OsdScene.build(state().copy(nightShot = on), video)
            .any { it is OsdItem.Sprite && it.name == "nightshot" }
        assertTrue(has(true, false))
        assertTrue(has(true, true))     // also burned into the recording
        assertFalse(has(false, false))
        assertTrue("nightshot" in font.sprites)
    }

    @Test
    fun nightShotCaptionIsCentredAndPushesMessagesDown() {
        val items = OsdScene.build(state(message = "Insert a cassette").copy(nightShot = true), false)
        val cap = items.filterIsInstance<OsdItem.Text>().first { it.text == "NIGHTSHOT" }
        assertEquals(OsdItem.Align.CENTER, cap.align)
        assertEquals(OsdScene.LCD_W / 2f, cap.x, 0.01f)
        val msg = items.filterIsInstance<OsdItem.Text>().first { it.text == "Insert a cassette" }
        assertTrue(msg.y > cap.y + OsdScene.CELL_H)
        // burned into the recording too
        assertTrue(OsdScene.build(state().copy(nightShot = true), true)
            .any { it is OsdItem.Text && it.text == "NIGHTSHOT" })
        assertFalse(OsdScene.build(state(), true).any { it is OsdItem.Text && it.text == "NIGHTSHOT" })
    }
}
