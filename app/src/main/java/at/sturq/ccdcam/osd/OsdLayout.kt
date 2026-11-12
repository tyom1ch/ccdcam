package at.sturq.ccdcam.osd

import at.sturq.ccdcam.firmware.OsdFormat
import java.util.Calendar
import java.util.TimeZone

/** One textured quad: destination in LCD units, source in atlas texels, multiply colour. */
data class OsdQuad(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val src: IRect,
    val color: Int,
)

/** Turns [OsdItem]s into atlas quads. Pure; shared by the Canvas preview and the GL burn-in. */
object OsdLayout {

    /** @param scale size multiplier of the OSD pixel (1 = indicator size). */
    fun quads(items: List<OsdItem>, font: OsdFont, atlas: OsdAtlas, scale: Float = 1f): List<OsdQuad> {
        val out = ArrayList<OsdQuad>()
        val px = OsdScene.PX * scale
        val py = OsdScene.PY * scale
        for (item in items) {
            when (item) {
                is OsdItem.Text -> {
                    val px = px * item.scale
                    val py = py * item.scale
                    val w = font.width(item.text) * px
                    var pen = start(item.x, w, item.align)
                    for (ch in item.text) {
                        if (ch != ' ') {
                            val r = atlas.glyphRect(ch)
                            // atlas rect includes a 1 px outline border on every side
                            val l = pen - (font.bearing(ch) + 1) * px
                            val t = item.y - py
                            out += OsdQuad(l, t, l + r.w * px, t + r.h * py, r, item.color)
                        }
                        pen += font.advance(ch) * px
                    }
                }
                is OsdItem.Sprite -> {
                    val g = font.sprite(item.name)
                    val r = atlas.spriteRect(item.name)
                    val l = start(item.x, g.width * px, item.align) - px
                    val t = item.y - py
                    out += OsdQuad(l, t, l + r.w * px, t + r.h * py, r, item.color)
                }
            }
        }
        return out
    }

    private fun start(x: Float, width: Float, align: OsdItem.Align) = when (align) {
        OsdItem.Align.LEFT -> x
        OsdItem.Align.CENTER -> x - width / 2f
        OsdItem.Align.RIGHT -> x - width
    }
}

/** Date / time strings of the OSD (HC90E: 24 h clock; D M YYYY order is an approximation). */
object OsdClock {
    fun format(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): Pair<String, String> {
        val c = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        return OsdFormat.date(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)) to
            OsdFormat.clock(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }
}
