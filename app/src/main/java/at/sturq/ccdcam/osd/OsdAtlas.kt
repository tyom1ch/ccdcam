package at.sturq.ccdcam.osd

/** Integer rectangle in atlas pixels. */
data class IRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Glyph atlas with the 1 px black outline baked in (8-neighbour dilation), as on the HC90 LCD
 * where every OSD character has a dark border. Fill texels are opaque white so the colour is a
 * multiply (GL vertex colour / Canvas MULTIPLY filter); outline stays black for any colour.
 * Built once; sampled with GL_NEAREST / unfiltered bitmap so pixels stay crisp.
 */
class OsdAtlas private constructor(
    val width: Int,
    val height: Int,
    /** ARGB_8888 texels, row-major, top row first. */
    val pixels: IntArray,
    private val glyphRects: Map<Char, IRect>,
    private val spriteRects: Map<String, IRect>,
) {
    /** Rect of a glyph incl. its 1 px outline border: (CELL_W + 2) × (CELL_H + 2). */
    fun glyphRect(ch: Char): IRect = glyphRects[ch] ?: glyphRects.getValue('?')

    fun spriteRect(name: String): IRect = spriteRects[name] ?: throw IllegalArgumentException(name)

    companion object {
        const val FILL = -0x1               // 0xFFFFFFFF
        const val OUTLINE = -0xefeff0       // 0xFF101010
        const val CLEAR = 0
        private const val PAD = 1           // outline border around each glyph
        private const val GAP = 1           // empty texel between entries (no bleeding)

        /**
         * @param outline bake the 1 px dark border (OSD indicators on the picture). Without it
         *   (touch-panel key labels: black text on cyan keys, as on the LCD) only fill texels exist.
         */
        fun build(font: OsdFont, atlasWidth: Int = 256, outline: Boolean = true): OsdAtlas {
            val entries = font.glyphs.entries.sortedBy { it.key }.map { it.key as Any to it.value } +
                font.sprites.entries.sortedBy { it.key }.map { it.key as Any to it.value }
            // Shelf packing: all entries share the cell height.
            val placed = LinkedHashMap<Any, IRect>()
            var x = 0
            var y = 0
            val rowH = OsdFont.CELL_H + 2 * PAD + GAP
            for ((key, g) in entries) {
                val w = g.width + 2 * PAD
                if (x + w > atlasWidth) { x = 0; y += rowH }
                placed[key] = IRect(x, y, w, OsdFont.CELL_H + 2 * PAD)
                x += w + GAP
            }
            var height = 1
            while (height < y + rowH) height *= 2          // power of two for older GLES drivers
            val px = IntArray(atlasWidth * height)
            for ((key, g) in entries) {
                val r = placed.getValue(key)
                for (gy in -PAD until g.height + PAD) for (gx in -PAD until g.width + PAD) {
                    val v = when {
                        g.on(gx, gy) -> FILL
                        outline && (-1..1).any { dy -> (-1..1).any { dx -> g.on(gx + dx, gy + dy) } } -> OUTLINE
                        else -> CLEAR
                    }
                    px[(r.y + gy + PAD) * atlasWidth + (r.x + gx + PAD)] = v
                }
            }
            @Suppress("UNCHECKED_CAST")
            return OsdAtlas(
                atlasWidth, height, px,
                placed.filterKeys { it is Char } as Map<Char, IRect>,
                placed.filterKeys { it is String } as Map<String, IRect>,
            )
        }
    }
}
