package at.sturq.ccdcam.osd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OsdFontAtlasTest {

    companion object {
        val font: OsdFont by lazy {
            val f = listOf(File("src/main/assets/osd/hc90e_osd_font.txt"), File("app/src/main/assets/osd/hc90e_osd_font.txt"))
                .first { it.exists() }
            OsdFont.parse(f.readText())
        }
        val atlas: OsdAtlas by lazy { OsdAtlas.build(font) }
    }

    @Test
    fun printableAsciiAndPictogramsArePresent() {
        for (c in 0x20..0x7E) assertTrue("glyph ${c.toChar()}", c.toChar() in font.glyphs)
        for (n in listOf("battery_0", "battery_4", "cassette", "sp", "lp", "play", "pause", "stop")) {
            assertTrue(n, n in font.sprites)
        }
        font.glyphs.values.forEach { assertEquals(OsdFont.CELL_W, it.width); assertEquals(OsdFont.CELL_H, it.height) }
    }

    @Test
    fun zeroIsNotSlashedLikeTheSonyOsd() {
        // PhoenixVGA has a slashed zero; the HC90 OSD does not (ITmedia photos).
        val z = font.glyph('0')
        for (y in 3..9) assertFalse("row $y has a stroke inside the zero", z.rows[y].substring(2, 5).contains('#'))
    }

    @Test
    fun indicatorLettersHaveNoSerifs() {
        // Sans-serif adaptations (tools/build_osd_font.py PATCHES).
        assertEquals("...##...", font.glyph('T').rows[10])   // no T foot
        assertEquals(".#####..", font.glyph('B').rows[2])    // no left serif on B
        assertEquals(".######.", font.glyph('E').rows[2])
        assertEquals(".##..##.", font.glyph('R').rows[10])   // no R foot
        assertEquals(".##.....", font.glyph('P').rows[10])
        assertEquals(".##.....", font.glyph('L').rows[2])
        assertEquals("...##...", font.glyph('1').rows[10])   // no base on 1
        assertEquals("....##..", font.glyph('4').rows[10])
    }

    @Test
    fun metricsDigitsTabularColonNarrowLettersProportional() {
        for (d in '0'..'9') assertEquals(8, font.advance(d))
        assertEquals(5, font.advance(':'))
        assertEquals(2, font.bearing(':'))
        assertTrue(font.advance('i') < font.advance('m'))
        assertEquals(5 * 8 + 2 * 5, font.width("0:06:05"))
    }

    @Test
    fun atlasHasOutlineAroundEveryLitTexel() {
        val r = atlas.glyphRect('0')
        assertEquals(OsdFont.CELL_W + 2, r.w)
        assertEquals(OsdFont.CELL_H + 2, r.h)
        val g = font.glyph('0')
        fun px(x: Int, y: Int) = atlas.pixels[(r.y + y + 1) * atlas.width + (r.x + x + 1)]
        for (y in -1..OsdFont.CELL_H) for (x in -1..OsdFont.CELL_W) {
            val lit = g.on(x, y)
            val near = (-1..1).any { dy -> (-1..1).any { dx -> g.on(x + dx, y + dy) } }
            val expected = when { lit -> OsdAtlas.FILL; near -> OsdAtlas.OUTLINE; else -> OsdAtlas.CLEAR }
            assertEquals("texel $x,$y", expected, px(x, y))
        }
    }

    @Test
    fun plainAtlasForKeyLabelsHasNoOutline() {
        val plain = OsdAtlas.build(font, outline = false)
        assertFalse(plain.pixels.any { it == OsdAtlas.OUTLINE })
        // same layout as the outlined atlas, so quads are interchangeable
        assertEquals(atlas.glyphRect('A'), plain.glyphRect('A'))
        assertEquals(atlas.pixels.count { it == OsdAtlas.FILL }, plain.pixels.count { it == OsdAtlas.FILL })
    }

    @Test
    fun scaledLayoutKeepsProportions() {
        val item = OsdItem.Text("MENU", 0f, 0f, OsdItem.Align.LEFT, OsdScene.WHITE)
        val a = OsdLayout.quads(listOf(item), font, atlas, 1f).first()
        val b = OsdLayout.quads(listOf(item), font, atlas, 0.5f).first()
        assertEquals((a.right - a.left) / 2f, b.right - b.left, 1e-4f)
        assertEquals((a.bottom - a.top) / 2f, b.bottom - b.top, 1e-4f)
    }

    @Test
    fun atlasEntriesDoNotOverlapAndHeightIsPowerOfTwo() {
        val rects = (0x20..0x7E).map { atlas.glyphRect(it.toChar()) } + font.sprites.keys.map { atlas.spriteRect(it) }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val a = rects[i]; val b = rects[j]
            val overlap = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
            assertFalse("$a overlaps $b", overlap)
        }
        assertEquals(0, atlas.height and (atlas.height - 1))
        rects.forEach { assertTrue(it.x + it.w <= atlas.width && it.y + it.h <= atlas.height) }
    }
}
