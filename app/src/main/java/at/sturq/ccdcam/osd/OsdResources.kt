package at.sturq.ccdcam.osd

import android.content.Context
import android.graphics.Bitmap

/** Font + atlas, loaded once per process and shared by the preview view and the GL burn-in. */
object OsdResources {

    @Volatile private var cached: Pair<OsdFont, OsdAtlas>? = null

    fun get(context: Context): Pair<OsdFont, OsdAtlas> = cached ?: synchronized(this) {
        cached ?: run {
            val text = context.assets.open("osd/hc90e_osd_font.txt").bufferedReader().use { it.readText() }
            val font = OsdFont.parse(text)
            (font to OsdAtlas.build(font)).also { cached = it }
        }
    }

    /** The atlas as an ARGB_8888 bitmap (premultiplied: texels are opaque or fully clear). */
    fun bitmap(atlas: OsdAtlas): Bitmap =
        Bitmap.createBitmap(atlas.pixels, atlas.width, atlas.height, Bitmap.Config.ARGB_8888)
}
