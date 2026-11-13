package at.sturq.ccdcam.osd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF

/** Draws OSD items onto a bitmap with the pixel-font atlas (photo imprint), no filtering. */
object OsdCanvas {
    @Volatile private var atlasBitmap: Bitmap? = null

    /** [items] laid out for an OSD [lcdW] wide (LCD units), scaled onto [bmp] (mutable). */
    fun draw(context: Context, bmp: Bitmap, items: List<OsdItem>, lcdW: Float) {
        val (font, atlas) = OsdResources.get(context)
        val atlasBmp = atlasBitmap ?: OsdResources.bitmap(atlas).also { atlasBitmap = it }
        val sx = bmp.width / lcdW
        val sy = bmp.height / OsdScene.LCD_H
        val c = Canvas(bmp)
        val src = Rect()
        val dst = RectF()
        val paints = HashMap<Int, Paint>()
        for (q in OsdLayout.quads(items, font, atlas)) {
            src.set(q.src.x, q.src.y, q.src.x + q.src.w, q.src.y + q.src.h)
            dst.set(q.left * sx, q.top * sy, q.right * sx, q.bottom * sy)
            val p = paints.getOrPut(q.color) {
                Paint().apply {
                    isFilterBitmap = false
                    isAntiAlias = false
                    colorFilter = PorterDuffColorFilter(q.color, PorterDuff.Mode.MULTIPLY)
                }
            }
            c.drawBitmap(atlasBmp, src, dst, p)
        }
    }
}
