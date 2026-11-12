package at.sturq.ccdcam.osd

/**
 * The HC90E OSD pixel font (assets/osd/hc90e_osd_font.txt, built by tools/build_osd_font.py):
 * PhoenixVGA 8x14 glyphs (CC BY-SA 4.0, adapted) plus project pictograms ("sprites").
 * Pure Kotlin so layout and atlas packing are unit-testable.
 */
class OsdFont private constructor(
    val glyphs: Map<Char, Bitmap1>,
    val sprites: Map<String, Bitmap1>,
) {
    /** 1-bit image: rows of '#'/'.' of equal width. */
    class Bitmap1(val rows: List<String>) {
        val width: Int = rows.first().length
        val height: Int = rows.size
        fun on(x: Int, y: Int): Boolean = y in 0 until height && x in 0 until width && rows[y][x] == '#'
        /** Leftmost / rightmost lit column, or null if empty. */
        val litColumns: IntRange? = run {
            val cols = (0 until width).filter { x -> rows.any { it[x] == '#' } }
            if (cols.isEmpty()) null else cols.first()..cols.last()
        }
    }

    fun glyph(ch: Char): Bitmap1 = glyphs[ch] ?: glyphs.getValue('?')

    fun sprite(name: String): Bitmap1 = sprites[name] ?: throw IllegalArgumentException("no sprite $name")

    /**
     * Horizontal metrics in OSD pixels: where the glyph's column 0 sits relative to the pen
     * (-bearing) and how far the pen moves. Digits are tabular (time code must not jitter),
     * ':' is a narrow centred slot, everything else is trimmed to its ink plus a 1 px gap —
     * the HC90 OSD is proportional (docs/hc90e-ui-references.md, "OSD font").
     */
    fun bearing(ch: Char): Int = when {
        ch.isDigit() -> 0
        ch == ':' -> COLON_BEARING
        ch == ' ' -> 0
        else -> glyph(ch).litColumns?.first ?: 0
    }

    fun advance(ch: Char): Int = when {
        ch.isDigit() -> CELL_W
        ch == ':' -> COLON_ADVANCE
        ch == ' ' -> SPACE_ADVANCE
        else -> glyph(ch).litColumns?.let { it.last - it.first + 2 } ?: SPACE_ADVANCE
    }

    /** Width of [text] in OSD pixels (pen advance). */
    fun width(text: String): Int = text.sumOf { advance(it) }

    companion object {
        const val CELL_W = 8
        const val CELL_H = 14
        const val COLON_BEARING = 2
        const val COLON_ADVANCE = 5
        const val SPACE_ADVANCE = 4

        fun parse(text: String): OsdFont {
            val glyphs = HashMap<Char, Bitmap1>()
            val sprites = HashMap<String, Bitmap1>()
            var key: Any? = null
            val rows = ArrayList<String>()
            fun flush() {
                when (val k = key) {
                    is Char -> glyphs[k] = Bitmap1(rows.toList())
                    is String -> sprites[k] = Bitmap1(rows.toList())
                }
                rows.clear()
            }
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r')
                when {
                    line.isEmpty() || line.startsWith("//") -> Unit
                    line.startsWith("glyph ") -> {
                        flush(); key = line.substringAfter(' ').trim().removePrefix("0x").toInt(16).toChar()
                    }
                    line.startsWith("sprite ") -> {
                        flush(); key = line.substringAfter(' ').trim()
                    }
                    else -> {
                        require(line.all { it == '#' || it == '.' }) { "bad row '$line'" }
                        rows += line
                    }
                }
            }
            flush()
            (glyphs.values + sprites.values).forEach { g ->
                require(g.height == CELL_H && g.rows.all { it.length == g.width }) { "bad glyph size" }
            }
            require('?' in glyphs) { "font needs '?'" }
            return OsdFont(glyphs, sprites)
        }
    }
}
