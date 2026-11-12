package at.sturq.ccdcam.firmware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.BatteryManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import at.sturq.ccdcam.osd.OsdIndicators
import at.sturq.ccdcam.osd.OsdItem
import at.sturq.ccdcam.osd.OsdLayout
import at.sturq.ccdcam.osd.OsdResources
import at.sturq.ccdcam.osd.OsdScene
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The DCR-HC90E touch-panel display, drawn over the preview inside the emulated 16:9 LCD
 * ([LcdLayout]). Phone UI only — never recorded (the recordable OSD is a separate GL pass).
 *
 * Visual references and confidence per element: docs/hc90e-ui-references.md.
 */
class FirmwareOsdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Transport { STOP, PLAY, PAUSE, REW, FF }

    enum class PlaybackCommand { STOP, REW, PLAY_PAUSE, FF }

    interface Listener {
        fun onNavEffect(effect: NavEffect)
        fun onPlaybackCommand(command: PlaybackCommand)
        fun onBeep(beep: Beep)
    }

    var listener: Listener? = null

    var navigator: FirmwareNavigator? = null
        set(value) { field = value; invalidate() }

    // ---- live state pushed by the activity ----
    /** NightShot body switch (indicator only; the look is in the shader). */
    var nightShot = false
        set(value) { field = value; invalidate() }

    var recording = false
        set(value) { field = value; invalidate() }

    /** Duration of the scene being recorded, ms (adds to the tape counter). */
    var sceneMs = 0L
        set(value) { field = value; invalidate() }

    var transport = Transport.STOP
        set(value) { field = value; invalidate() }

    /** Tape time code position during playback, ms. */
    var playbackPositionMs = 0L
        set(value) { field = value; invalidate() }

    /** Recording date/time of the clip being played (epoch ms), for DATA CODE. */
    var playbackClipTimeMs: Long? = null
        set(value) { field = value; invalidate() }

    /**
     * How far the phone is turned clockwise from portrait (0/90/180/270). The activity stays
     * portrait; the display is drawn turned so it stays upright for the user.
     */
    var deviceRotationCw = 0
        set(value) { if (field != value) { field = value; pressed = null; invalidate() } }

    /** Aspect of the shared camera region as displayed in this view (portrait UI: 9:16). */
    var regionAspectInView = 9f / 16f
        set(value) { field = value; invalidate() }

    private var dateTimeUntil = 0L
    private var remainingUntil = 0L
    private var message: String? = null
    private var messageUntil = 0L

    /** "Moving from OFF to CAMERA shows the current date and time for about 5 seconds." */
    fun showDateTime() {
        dateTimeUntil = SystemClock.uptimeMillis() + 5_000
        scheduleRefresh()
    }

    /** REMAINING AUTO: about 8 seconds (guide p. 54). */
    fun showRemaining() {
        remainingUntil = SystemClock.uptimeMillis() + 8_000
        scheduleRefresh()
    }

    fun showMessage(text: String, durationMs: Long = 3_000) {
        message = text
        messageUntil = SystemClock.uptimeMillis() + durationMs
        scheduleRefresh()
    }

    // ---- battery (InfoLITHIUM-style minutes, approximation) ----
    private var batteryPct = -1
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val raw = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (raw >= 0 && scale > 0) { batteryPct = raw * 100 / scale; invalidate() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { batteryReceiver.onReceive(context, it) }
    }

    override fun onDetachedFromWindow() {
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Throwable) {}
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    private val refresh = Runnable { invalidate() }
    private fun scheduleRefresh() {
        removeCallbacks(refresh)
        invalidate()
    }

    // ---- palette (sampled from ITmedia LCD photos) ----
    private val cyan = Color.rgb(0xAB, 0xE2, 0xDD)
    private val cyanHi = Color.rgb(0xE6, 0xF8, 0xF6)
    private val cyanLo = Color.rgb(0x5C, 0x8C, 0x88)
    private val cyanValue = Color.rgb(0x40, 0xE0, 0xD6)
    private val blue = Color.rgb(0x02, 0x38, 0xA3)
    private val blueHi = Color.rgb(0x46, 0x74, 0xDA)
    private val blueLo = Color.rgb(0x01, 0x1E, 0x5A)
    private val grayFill = Color.rgb(0x9C, 0xAD, 0xAB)
    private val grayText = Color.rgb(0x63, 0x6E, 0x6D)
    private val border = Color.rgb(0x22, 0x26, 0x26)
    private val stbyGreen = Color.rgb(0x3B, 0xE6, 0x38)
    private val recRed = Color.rgb(0xF0, 0x2A, 0x24)
    private val outline = Color.rgb(0x16, 0x16, 0x16)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rf = RectF()

    // ---- hit targets, rebuilt every frame ----
    private class Target(val box: Box, val enabled: Boolean, val action: () -> Unit)
    private val targets = ArrayList<Target>()
    private var pressed: Target? = null

    private lateinit var lcd: Box
    private var frame: LcdLayout.RotatedFrame? = null
    private fun b(f: FloatArray) = LcdLayout.at(lcd, f[0], f[1], f[2], f[3])

    override fun onDraw(canvas: Canvas) {
        val nav = navigator ?: return
        if (width == 0 || height == 0) return
        val region = LcdLayout.displayedRegion(width.toFloat(), height.toFloat(), regionAspectInView)
        val fr = LcdLayout.RotatedFrame(region, deviceRotationCw)
        frame = fr
        // Everything below is drawn in the upright virtual frame (fr.width × fr.height).
        lcd = LcdLayout.lcd(Box(0f, 0f, fr.width, fr.height))
        targets.clear()
        val now = SystemClock.uptimeMillis()
        canvas.save()
        canvas.translate(region.centerX, region.centerY)
        canvas.rotate(fr.canvasAngle)
        canvas.translate(-fr.width / 2f, -fr.height / 2f)

        indicators(now, forVideo = false)?.let { drawItems(canvas, OsdScene.build(it, forVideo = false)) }
        when (val s = nav.screen) {
            Screen.Main -> drawMain(canvas, nav, now)
            is Screen.PMenu -> drawPMenu(canvas, nav, s)
            is Screen.MenuCategories -> drawDrum(canvas, nav, categoryLevel = true, s.index, -1)
            is Screen.MenuItems -> drawDrum(canvas, nav, categoryLevel = false, s.index, s.category)
            is Screen.Setting -> drawSetting(canvas, nav, s)
        }
        if (message != null && now >= messageUntil) message = null
        canvas.restore()

        // Keep timed elements (date, REMAINING AUTO, messages, warning blink) fresh.
        val next = listOf(dateTimeUntil, remainingUntil, messageUntil).filter { it > now }.minOrNull()
        val blinking = nav.settings.cassette?.let { it.isAtEnd || it.isNearEnd(nav.settings.recMode) } ?: true
        removeCallbacks(refresh)
        if (next != null || blinking || recording || nightShot) postDelayed(refresh, 250)
    }

    // ================= indicator row (guide p. 26: 1–5) =================

    // ================= OSD indicator layer (pixel font, shared with the burn-in) =================

    private val osdRes = OsdResources.get(context)
    private val atlasBitmap = OsdResources.bitmap(osdRes.second)
    private val plainAtlas = OsdResources.plainAtlas(context)
    private val plainBitmap = OsdResources.bitmap(plainAtlas)
    private val atlasPaints = HashMap<Int, Paint>()
    private val srcRect = Rect()
    private val dstRect = RectF()

    private fun atlasPaint(color: Int) = atlasPaints.getOrPut(color) {
        // No filtering, no AA: the pixel structure of the font must survive scaling.
        Paint().apply {
            isFilterBitmap = false
            isAntiAlias = false
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
        }
    }

    private fun toUnitsX(x: Float) = (x - lcd.left) / lcd.width * OsdScene.LCD_W

    private fun toUnitsY(y: Float) = (y - lcd.top) / lcd.height * OsdScene.LCD_H

    /**
     * Draws [items] (LCD units) with the pixel font. [outlined] = OSD indicator style (dark
     * border, over the picture); false = key/menu label style (plain glyphs).
     */
    private fun drawItems(c: Canvas, items: List<OsdItem>, scale: Float = 1f, outlined: Boolean = true) {
        val sx = lcd.width / OsdScene.LCD_W
        val sy = lcd.height / OsdScene.LCD_H
        val atlas = if (outlined) osdRes.second else plainAtlas
        val bmp = if (outlined) atlasBitmap else plainBitmap
        for (q in OsdLayout.quads(items, osdRes.first, atlas, scale)) {
            srcRect.set(q.src.x, q.src.y, q.src.x + q.src.w, q.src.y + q.src.h)
            dstRect.set(lcd.left + q.left * sx, lcd.top + q.top * sy, lcd.left + q.right * sx, lcd.top + q.bottom * sy)
            c.drawBitmap(bmp, srcRect, dstRect, atlasPaint(q.color))
        }
    }

    /** Pixel-font text with its caps vertically centred on [centerY] (view px). */
    private fun pixelText(
        c: Canvas, text: String, x: Float, centerY: Float, scale: Float, color: Int,
        align: OsdItem.Align = OsdItem.Align.LEFT, outlined: Boolean = false,
    ) {
        // caps occupy glyph rows 2..10 -> centre at row 6.5 of the 14-row cell
        val top = toUnitsY(centerY) - 6.5f * OsdScene.PY * scale
        drawItems(c, listOf(OsdItem.Text(text, toUnitsX(x), top, align, color)), scale, outlined)
    }

    /** Width of [text] in view px at [scale]. */
    private fun pixelWidth(text: String, scale: Float) =
        osdRes.first.width(text) * OsdScene.PX * scale * lcd.width / OsdScene.LCD_W

    /**
     * Indicator state at [now]. The preview follows the LCD rules (date/time ~5 s after power-on,
     * DATA CODE in playback, REMAINING AUTO window, messages); [forVideo] gives the burned-in
     * variant: date/time always, no messages.
     */
    fun indicators(now: Long, forVideo: Boolean): OsdIndicators? {
        val nav = navigator ?: return null
        val s = nav.settings
        val cassette = s.cassette
        val warn = cassette == null || cassette.isAtEnd || cassette.isNearEnd(s.recMode)
        val blinkFast = cassette == null || cassette.isAtEnd
        val iconBlinkOn = if (warn) (now / (if (blinkFast) 160 else 480)) % 2 == 0L else true
        val showRemaining = s.remaining == FirmwareSettings.REMAINING_ON || recording || now < remainingUntil
        val dateTime: Long? = when {
            forVideo -> System.currentTimeMillis()
            nav.lamp == Lamp.PLAY_EDIT ->
                if (s.dataCode == FirmwareSettings.DATA_CODE_DATE_TIME) playbackClipTimeMs else null
            now < dateTimeUntil -> System.currentTimeMillis()
            else -> null
        }
        return OsdIndicators(
            lamp = nav.lamp,
            recording = recording,
            recMode = s.recMode,
            counterMs = (cassette?.counterMs ?: 0L) + if (recording) sceneMs else 0L,
            playbackMs = playbackPositionMs,
            transport = when (transport) {
                Transport.STOP -> OsdIndicators.Transport.STOP
                Transport.PLAY -> OsdIndicators.Transport.PLAY
                Transport.PAUSE -> OsdIndicators.Transport.PAUSE
                Transport.REW -> OsdIndicators.Transport.REW
                Transport.FF -> OsdIndicators.Transport.FF
            },
            batteryPct = batteryPct,
            remainingMin = cassette?.remainingMinutes(s.recMode),
            showRemaining = showRemaining,
            cassetteIconOn = (showRemaining || warn) && iconBlinkOn,
            dateTimeMs = dateTime,
            message = if (!forVideo && now < messageUntil) message else null,
            nightShot = nightShot,
            blinkOn = (now / 500) % 2 == 0L,
        )
    }

    // ================= main screen =================

    private fun drawMain(c: Canvas, nav: FirmwareNavigator, now: Long) {
        val t = LcdLayout.OSD_TEXT * lcd.height
        when (nav.lamp) {
            Lamp.TAPE -> {
                button(c, b(LcdLayout.END_SEARCH_BUTTON), Style.CYAN, enabled = !recording && nav.settings.cassette != null, icon = ::iconCassette) {
                    listener?.onNavEffect(NavEffect.EndSearch)
                }
                pMenuButton(c, nav)
            }
            Lamp.MEMORY -> pMenuButton(c, nav)
            Lamp.PLAY_EDIT -> {
                button(c, b(LcdLayout.PLAY_STOP), Style.CYAN, icon = { cv, bx -> centeredGlyph(cv, bx) { x, y, h -> glyphStop(cv, x, y, h) } }) {
                    listener?.onPlaybackCommand(PlaybackCommand.STOP)
                }
                button(c, b(LcdLayout.PLAY_REW), Style.CYAN, icon = { cv, bx -> twinGlyph(cv, bx, rew = true) }) {
                    listener?.onPlaybackCommand(PlaybackCommand.REW)
                }
                button(c, b(LcdLayout.PLAY_PLAY_PAUSE), Style.CYAN, icon = ::iconPlayPause) {
                    listener?.onPlaybackCommand(PlaybackCommand.PLAY_PAUSE)
                }
                button(c, b(LcdLayout.PLAY_FF), Style.CYAN, icon = { cv, bx -> twinGlyph(cv, bx, rew = false) }) {
                    listener?.onPlaybackCommand(PlaybackCommand.FF)
                }
                pMenuButton(c, nav)
            }
        }
    }

    private fun pMenuButton(c: Canvas, nav: FirmwareNavigator) {
        button(c, b(LcdLayout.P_MENU_BUTTON), Style.CYAN, label = "P-MENU", enabled = !recording) {
            nav.openPMenu()
        }
    }

    // ================= Personal Menu =================

    private fun drawPMenu(c: Canvas, nav: FirmwareNavigator, s: Screen.PMenu) {
        val pages = nav.pMenuPageCount
        button(c, b(LcdLayout.PMENU_UP), Style.CYAN, icon = { cv, bx -> doubleChevron(cv, bx, up = true) }) { nav.pMenuPage(-1) }
        button(c, b(LcdLayout.PMENU_DOWN), Style.CYAN, icon = { cv, bx -> doubleChevron(cv, bx, up = false) }) { nav.pMenuPage(1) }
        val upBox = b(LcdLayout.PMENU_UP)
        drawItems(c, listOf(OsdItem.Text("${s.page + 1}/$pages", toUnitsX(upBox.centerX), 0.500f * OsdScene.LCD_H, OsdItem.Align.CENTER, OsdScene.WHITE)))
        button(c, b(LcdLayout.PMENU_CLOSE), Style.BLUE, icon = ::iconClose) { nav.close() }

        val entries = nav.pMenuPageEntries(s.page)
        val cx = LcdLayout.PMENU_COL_X
        val ry = LcdLayout.PMENU_ROW_Y
        entries.forEachIndexed { i, entry ->
            val col = i % 2
            val row = i / 2
            val box = LcdLayout.at(lcd, cx[col * 2], ry[row * 2], cx[col * 2 + 1], ry[row * 2 + 1])
            val enabled = HC90eMenu.pMenuEntryAvailable(entry, nav.lamp)
            button(c, box, Style.CYAN, label = entry, enabled = enabled) {
                listener?.onNavEffect(nav.pMenuSelect(entry))
            }
        }
    }

    // ================= MENU drum =================

    private fun drawDrum(c: Canvas, nav: FirmwareNavigator, categoryLevel: Boolean, index: Int, category: Int) {
        val drum = b(LcdLayout.DRUM)
        // Cylinder body: dark, semi-transparent, darker towards the rims.
        rf.set(drum.left, drum.top, drum.right, drum.bottom)
        fill.shader = LinearGradient(
            0f, drum.top, 0f, drum.bottom,
            intArrayOf(0xE0141413.toInt(), 0xD8393A37.toInt(), 0xD8434441.toInt(), 0xD8393A37.toInt(), 0xE0141413.toInt()),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(rf, drum.width * 0.10f, drum.height * 0.08f, fill)
        fill.shader = null

        val names: List<String>
        val enabledAt: (Int) -> Boolean
        if (categoryLevel) {
            names = HC90eMenu.categories.map { it.label }
            enabledAt = { true }
        } else {
            val items = HC90eMenu.categories[category].items
            names = items.map { it.label }
            enabledAt = { items[it].isAvailable(nav.lamp) }
        }
        val n = names.size
        val centerY = lcd.top + LcdLayout.DRUM_SELECTED_CENTER_Y * lcd.height
        val radius = 0.36f * lcd.height
        val step = Math.toRadians(24.0)
        c.save()
        c.clipRect(drum.left, drum.top, drum.right, drum.bottom)
        for (k in -3..3) {
            if (k == 0) continue
            val angle = k * step
            if (abs(angle) >= Math.PI / 2) continue
            val idx = ((index + k) % n + n) % n
            val y = centerY + (radius * sin(angle)).toFloat()
            val scale = cos(angle).toFloat()
            val size = LcdLayout.OSD_TEXT * lcd.height * 1.05f * scale
            val color = if (enabledAt(idx)) Color.WHITE else grayText
            plainText(c, names[idx], drum.left + 0.06f * drum.width, y + size * 0.36f, size, color, alpha = (255 * (0.35f + 0.65f * scale)).toInt())
            val rowBox = Box(drum.left, y - size * 0.6f, drum.right, y + size * 0.6f)
            targets += Target(rowBox, true) { nav.drumTouch(idx) }
        }
        c.restore()

        // Selected row: cyan bar, black text; value tag under its right end.
        val barH = LcdLayout.DRUM_ROW_H * lcd.height
        val bar = Box(drum.left - 0.01f * lcd.width, centerY - barH / 2f, drum.right + 0.005f * lcd.width, centerY + barH / 2f)
        bevel(c, bar, Style.CYAN, enabled = true, pressed = false)
        val selSize = LcdLayout.OSD_TEXT * lcd.height * 1.25f
        plainText(c, names[index], bar.left + 0.06f * drum.width, bar.centerY + selSize * 0.36f, selSize,
            if (enabledAt(index)) Color.BLACK else grayText)
        if (!categoryLevel) {
            val item = HC90eMenu.categories[category].items[index]
            nav.settings.valueOf(item.id)?.let { v ->
                val label = item.options.firstOrNull { it.id == v }?.label ?: v
                val tag = Box(bar.right - 0.30f * lcd.width, bar.bottom, bar.right, bar.bottom + 0.085f * lcd.height)
                fill.color = cyanValue
                c.drawRect(tag.left, tag.top, tag.right, tag.bottom, fill)
                plainText(c, label, tag.right - 0.02f * lcd.width, tag.bottom - tag.height * 0.22f, tag.height * 0.78f, Color.BLACK, align = Paint.Align.RIGHT)
            }
        } else {
            // Category level: first items of the selected category under the bar (guide p. 34 ①).
            val preview = HC90eMenu.categories[index].items.take(3).map { it.label }
            val ps = LcdLayout.OSD_TEXT * lcd.height * 0.62f
            preview.forEachIndexed { i, label ->
                plainText(c, label, bar.left + 0.30f * drum.width, bar.bottom + ps * (1.25f + i * 1.05f), ps, Color.WHITE)
            }
        }

        // Category icon column on the left (rotating cylinder of pictograms).
        drawCategoryIcons(c, if (categoryLevel) index else category, centerY)

        button(c, b(LcdLayout.DRUM_UP), Style.CYAN, icon = { cv, bx -> triangle(cv, bx, up = true) }) { nav.drumStep(down = false) }
        button(c, b(LcdLayout.DRUM_DOWN), Style.CYAN, icon = { cv, bx -> triangle(cv, bx, up = false) }) { nav.drumStep(down = true) }
        button(c, b(LcdLayout.MENU_CLOSE), Style.BLUE, icon = ::iconClose) { nav.close() }
        if (!categoryLevel) button(c, b(LcdLayout.MENU_BACK), Style.CYAN, icon = ::iconReturn) { nav.back() }
        button(c, b(LcdLayout.MENU_OK), Style.CYAN, label = "OK") { listener?.onNavEffect(nav.ok()) }
    }

    private fun drawCategoryIcons(c: Canvas, selected: Int, centerY: Float) {
        val n = HC90eMenu.categories.size
        val x = lcd.left + 0.150f * lcd.width
        val step = 0.105f * lcd.height
        for (k in -3..3) {
            val idx = ((selected + k) % n + n) % n
            val scale = if (k == 0) 1.25f else 1f - abs(k) * 0.12f
            val s = 0.060f * lcd.height * scale
            val y = centerY + k * step * (1f - abs(k) * 0.08f)
            val color = if (k == 0) Color.WHITE else Color.argb(200, 225, 225, 225)
            drawCategoryIcon(c, idx, x, y, s, color)
        }
    }

    // ================= setting screen =================

    private fun drawSetting(c: Canvas, nav: FirmwareNavigator, s: Screen.Setting) {
        val item = HC90eMenu.item(s.itemId)
        val sel = item.options.firstOrNull { it.id == s.selected }?.label ?: s.selected
        val t = LcdLayout.OSD_TEXT * lcd.height
        // Title box under the indicator row: "ITEM: VALUE" (guide p. 34 ③).
        val title = LcdLayout.at(lcd, 0.035f, 0.165f, 0.965f, 0.255f)
        fill.color = Color.argb(150, 0, 0, 0)
        c.drawRect(title.left, title.top, title.right, title.bottom, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = lcd.height * 0.006f
        c.drawRect(title.left, title.top, title.right, title.bottom, stroke)
        plainText(c, "${item.label}: $sel", title.left + 0.02f * lcd.width, title.bottom - title.height * 0.24f, t * 0.95f, Color.WHITE)

        val opts = item.options
        val w = if (opts.size <= 2) 0.170f else 0.215f
        val gap = 0.045f
        val total = opts.size * w + (opts.size - 1) * gap
        var x = 0.5f - total / 2f
        for (o in opts) {
            val box = LcdLayout.at(lcd, x, 0.430f, x + w, 0.620f)
            val isSel = o.id == s.selected
            button(c, box, Style.CYAN, label = o.label, selectedMark = isSel) { nav.selectOption(o.id) }
            x += w + gap
        }
        if (nav.settingChanged) {
            button(c, b(LcdLayout.MENU_OK), Style.CYAN, label = "OK") { listener?.onNavEffect(nav.ok()) }
        } else {
            button(c, b(LcdLayout.MENU_OK), Style.CYAN, icon = ::iconReturn) { nav.back() }
        }
    }

    // ================= primitives =================

    private enum class Style { CYAN, BLUE }

    private fun button(
        c: Canvas,
        box: Box,
        style: Style,
        label: String? = null,
        enabled: Boolean = true,
        selectedMark: Boolean = false,
        icon: ((Canvas, Box) -> Unit)? = null,
        action: () -> Unit,
    ) {
        val isPressed = pressed?.box == box
        bevel(c, box, style, enabled, isPressed)
        if (selectedMark) {
            fill.color = Color.BLACK
            c.drawRect(box.left, box.top, box.left + box.width * 0.07f, box.bottom, fill)
        }
        val ink = when {
            !enabled -> grayText
            style == Style.BLUE -> Color.WHITE
            else -> Color.BLACK
        }
        if (icon != null) {
            fill.color = ink
            stroke.color = ink
            icon(c, box)
        }
        if (label != null) buttonLabel(c, box, label, ink)
        targets += Target(box, enabled, action)
    }

    /** Raised key: dark outer border, light top/left edge, dark bottom/right edge (ITmedia 28036). */
    private fun bevel(c: Canvas, box: Box, style: Style, enabled: Boolean, pressed: Boolean) {
        val (body, hi, lo) = when {
            !enabled -> Triple(grayFill, Color.rgb(0xC9, 0xD3, 0xD2), Color.rgb(0x5E, 0x69, 0x68))
            style == Style.BLUE -> Triple(blue, blueHi, blueLo)
            else -> Triple(cyan, cyanHi, cyanLo)
        }
        val e = (lcd.height * 0.010f).coerceAtLeast(1.5f)
        fill.color = border
        c.drawRect(box.left - e * 0.6f, box.top - e * 0.6f, box.right + e * 0.6f, box.bottom + e * 0.6f, fill)
        fill.color = if (pressed) lo else hi
        c.drawRect(box.left, box.top, box.right, box.bottom, fill)
        fill.color = if (pressed) hi else lo
        path.reset()
        path.moveTo(box.right, box.top); path.lineTo(box.right, box.bottom); path.lineTo(box.left, box.bottom)
        path.lineTo(box.left + e, box.bottom - e); path.lineTo(box.right - e, box.bottom - e); path.lineTo(box.right - e, box.top + e)
        path.close()
        c.drawPath(path, fill)
        fill.color = if (pressed) darken(body) else body
        c.drawRect(box.left + e, box.top + e, box.right - e, box.bottom - e, fill)
    }

    private fun darken(c: Int) = Color.rgb(Color.red(c) * 85 / 100, Color.green(c) * 85 / 100, Color.blue(c) * 85 / 100)

    /**
     * Key label in the pixel font: one line centred, or two lines left-aligned when it does not
     * fit (guide p. 34 figures), shrunk only if even that is too wide.
     */
    private fun buttonLabel(c: Canvas, box: Box, label: String, color: Int) {
        val pad = box.width * 0.08f
        val avail = box.width - 2 * pad
        val unitH = lcd.height / OsdScene.LCD_H
        val lineH = 12f * OsdScene.PY * unitH                    // caps + leading, scale 1
        val oneLine = pixelWidth(label, 1f) <= avail || !label.contains(' ')
        if (oneLine) {
            val scale = minOf(1f, avail / pixelWidth(label, 1f), box.height * 0.8f / lineH)
            pixelText(c, label, box.centerX, box.centerY, scale, color, OsdItem.Align.CENTER)
        } else {
            val cut = label.indexOf(' ')
            val lines = listOf(label.substring(0, cut), label.substring(cut + 1))
            val widest = lines.maxOf { pixelWidth(it, 1f) }
            val scale = minOf(1f, avail / widest, box.height * 0.9f / (2 * lineH))
            val step = lineH * scale
            lines.forEachIndexed { i, l ->
                pixelText(c, l, box.left + pad, box.centerY + (i - 0.5f) * step, scale, color)
            }
        }
    }

    /**
     * Menu text in the pixel font. [base] is the text baseline and [size] the former font size;
     * the cap height maps to the same visual size (caps ≈ 0.72 × size).
     */
    private fun plainText(c: Canvas, text: String, x: Float, base: Float, size: Float, color: Int,
                          alpha: Int = 255, align: Paint.Align = Paint.Align.LEFT) {
        val capH = 0.72f * size
        val scale = capH / (9f * OsdScene.PY * lcd.height / OsdScene.LCD_H)
        val a = when (align) {
            Paint.Align.LEFT -> OsdItem.Align.LEFT
            Paint.Align.CENTER -> OsdItem.Align.CENTER
            Paint.Align.RIGHT -> OsdItem.Align.RIGHT
        }
        val tinted = (alpha.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)
        pixelText(c, text, x, base - capH / 2f, scale, tinted, a)
    }

    private fun outlinedPath(c: Canvas, p: Path, color: Int, w: Float) {
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.color = outline
        stroke.strokeWidth = w * 2.6f
        c.drawPath(p, stroke)
        stroke.color = color
        stroke.strokeWidth = w
        c.drawPath(p, stroke)
    }

    private fun iconCassette(c: Canvas, box: Box) {
        val h = box.height * 0.42f
        val w = h * 1.45f
        val x = box.centerX - w / 2f
        val y = box.centerY - h / 2f
        path.reset()
        rf.set(x, y, x + w, y + h)
        path.addRoundRect(rf, h * 0.12f, h * 0.12f, Path.Direction.CW)
        val r = h * 0.18f
        path.addCircle(x + w * 0.33f, y + h * 0.5f, r, Path.Direction.CW)
        path.addCircle(x + w * 0.67f, y + h * 0.5f, r, Path.Direction.CW)
        path.moveTo(x + w * 0.33f, y + h * 0.5f + r); path.lineTo(x + w * 0.67f, y + h * 0.5f + r)
        stroke.strokeWidth = h * 0.10f
        c.drawPath(path, stroke)
    }

    private fun iconClose(c: Canvas, box: Box) {
        val s = box.height * 0.26f
        stroke.strokeWidth = box.height * 0.11f
        stroke.strokeCap = Paint.Cap.SQUARE
        c.drawLine(box.centerX - s, box.centerY - s, box.centerX + s, box.centerY + s, stroke)
        c.drawLine(box.centerX - s, box.centerY + s, box.centerX + s, box.centerY - s, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /** ↩ : arrow pointing left with a hook up to the right. */
    private fun iconReturn(c: Canvas, box: Box) {
        val w = box.height * 0.62f
        val h = box.height * 0.40f
        val l = box.centerX - w / 2f
        val r = box.centerX + w / 2f
        val top = box.centerY - h / 2f
        val bot = box.centerY + h / 2f
        stroke.strokeWidth = box.height * 0.075f
        path.reset()
        path.moveTo(r, top); path.lineTo(r, bot); path.lineTo(l + w * 0.12f, bot)
        c.drawPath(path, stroke)
        path.reset()
        path.moveTo(l, bot); path.lineTo(l + w * 0.30f, bot - h * 0.40f); path.lineTo(l + w * 0.30f, bot + h * 0.40f); path.close()
        c.drawPath(path, fill)
    }

    private fun doubleChevron(c: Canvas, box: Box, up: Boolean) {
        val w = box.height * 0.40f
        val h = box.height * 0.16f
        stroke.strokeWidth = box.height * 0.075f
        stroke.strokeJoin = Paint.Join.MITER
        for (k in 0..1) {
            val cy = box.centerY + (if (up) 1 else -1) * (k * h * 1.25f - h * 0.6f)
            path.reset()
            if (up) { path.moveTo(box.centerX - w / 2, cy + h / 2); path.lineTo(box.centerX, cy - h / 2); path.lineTo(box.centerX + w / 2, cy + h / 2) }
            else { path.moveTo(box.centerX - w / 2, cy - h / 2); path.lineTo(box.centerX, cy + h / 2); path.lineTo(box.centerX + w / 2, cy - h / 2) }
            c.drawPath(path, stroke)
        }
    }

    private fun triangle(c: Canvas, box: Box, up: Boolean) {
        val w = box.height * 0.42f
        val h = box.height * 0.26f
        path.reset()
        if (up) { path.moveTo(box.centerX - w / 2, box.centerY + h / 2); path.lineTo(box.centerX, box.centerY - h / 2); path.lineTo(box.centerX + w / 2, box.centerY + h / 2) }
        else { path.moveTo(box.centerX - w / 2, box.centerY - h / 2); path.lineTo(box.centerX, box.centerY + h / 2); path.lineTo(box.centerX + w / 2, box.centerY - h / 2) }
        path.close()
        c.drawPath(path, fill)
    }

    private fun glyphPlay(c: Canvas, x: Float, y: Float, h: Float) {
        path.reset(); path.moveTo(x, y); path.lineTo(x + h * 0.8f, y + h / 2); path.lineTo(x, y + h); path.close()
        c.drawPath(path, fill)
    }

    private fun glyphRew(c: Canvas, x: Float, y: Float, h: Float) {
        path.reset(); path.moveTo(x + h * 0.8f, y); path.lineTo(x, y + h / 2); path.lineTo(x + h * 0.8f, y + h); path.close()
        c.drawPath(path, fill)
    }

    private fun glyphPause(c: Canvas, x: Float, y: Float, h: Float) {
        c.drawRect(x, y, x + h * 0.22f, y + h, fill)
        c.drawRect(x + h * 0.42f, y, x + h * 0.64f, y + h, fill)
    }

    private fun glyphStop(c: Canvas, x: Float, y: Float, h: Float) {
        c.drawRect(x, y, x + h, y + h, fill)
    }

    private fun centeredGlyph(c: Canvas, box: Box, draw: (Float, Float, Float) -> Unit) {
        val h = box.height * 0.38f
        draw(box.centerX - h / 2f, box.centerY - h / 2f, h)
    }

    private fun twinGlyph(c: Canvas, box: Box, rew: Boolean) {
        val h = box.height * 0.40f
        val x = box.centerX - h * 0.8f
        val y = box.centerY - h / 2f
        if (rew) { glyphRew(c, x, y, h); glyphRew(c, x + h * 0.75f, y, h) }
        else { glyphPlay(c, x, y, h); glyphPlay(c, x + h * 0.75f, y, h) }
    }

    private fun iconPlayPause(c: Canvas, box: Box) {
        val h = box.height * 0.40f
        val x = box.centerX - h * 0.95f
        val y = box.centerY - h / 2f
        glyphPlay(c, x, y, h)
        glyphPause(c, x + h * 1.15f, y, h)
    }

    /**
     * Category pictograms (guide p. 34/37 menu figures): camcorder, memory card, picture,
     * tape edit, tool box, clock. Simplified redraws — an approximation of Sony's icons.
     */
    private fun drawCategoryIcon(c: Canvas, idx: Int, cx: Float, cy: Float, s: Float, color: Int) {
        path.reset()
        when (idx) {
            0 -> { // CAMERA SET: camcorder body + lens
                path.addRect(cx - s * 0.7f, cy - s * 0.35f, cx + s * 0.25f, cy + s * 0.35f, Path.Direction.CW)
                path.moveTo(cx + s * 0.25f, cy - s * 0.15f); path.lineTo(cx + s * 0.7f, cy - s * 0.35f)
                path.lineTo(cx + s * 0.7f, cy + s * 0.35f); path.lineTo(cx + s * 0.25f, cy + s * 0.15f)
            }
            1 -> { // MEMORY SET: card with cut corner
                path.moveTo(cx - s * 0.6f, cy - s * 0.4f); path.lineTo(cx + s * 0.45f, cy - s * 0.4f)
                path.lineTo(cx + s * 0.6f, cy - s * 0.25f); path.lineTo(cx + s * 0.6f, cy + s * 0.4f)
                path.lineTo(cx - s * 0.6f, cy + s * 0.4f); path.close()
            }
            2 -> { // PICT.APPLI.: picture frame with mountain
                path.addRect(cx - s * 0.65f, cy - s * 0.45f, cx + s * 0.65f, cy + s * 0.45f, Path.Direction.CW)
                path.moveTo(cx - s * 0.5f, cy + s * 0.3f); path.lineTo(cx - s * 0.1f, cy - s * 0.15f)
                path.lineTo(cx + s * 0.15f, cy + s * 0.1f); path.lineTo(cx + s * 0.5f, cy - s * 0.2f)
            }
            3 -> { // EDIT/PLAY: cassette
                rf.set(cx - s * 0.65f, cy - s * 0.4f, cx + s * 0.65f, cy + s * 0.4f)
                path.addRoundRect(rf, s * 0.1f, s * 0.1f, Path.Direction.CW)
                path.addCircle(cx - s * 0.25f, cy, s * 0.14f, Path.Direction.CW)
                path.addCircle(cx + s * 0.25f, cy, s * 0.14f, Path.Direction.CW)
            }
            4 -> { // STANDARD SET: tool box
                path.addRect(cx - s * 0.7f, cy - s * 0.2f, cx + s * 0.7f, cy + s * 0.45f, Path.Direction.CW)
                path.moveTo(cx - s * 0.25f, cy - s * 0.2f); path.lineTo(cx - s * 0.25f, cy - s * 0.45f)
                path.lineTo(cx + s * 0.25f, cy - s * 0.45f); path.lineTo(cx + s * 0.25f, cy - s * 0.2f)
                path.moveTo(cx - s * 0.7f, cy + s * 0.05f); path.lineTo(cx + s * 0.7f, cy + s * 0.05f)
            }
            else -> { // TIME/LANGU.: clock
                path.addCircle(cx, cy, s * 0.45f, Path.Direction.CW)
                path.moveTo(cx, cy); path.lineTo(cx, cy - s * 0.3f)
                path.moveTo(cx, cy); path.lineTo(cx + s * 0.22f, cy)
            }
        }
        outlinedPath(c, path, color, s * 0.10f)
    }

    // ================= touch =================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val nav = navigator ?: return false
        val (x, y) = frame?.toVirtual(event.x, event.y) ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = targets.lastOrNull { it.box.contains(x, y) }
                if (hit == null) {
                    // Outside any key: modal while a menu is open, otherwise let the preview
                    // (pinch zoom) have the gesture.
                    return nav.isMenuOpen
                }
                pressed = hit
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = null
                if (p != null && p.box.contains(x, y)) {
                    if (p.enabled) {
                        listener?.onBeep(Beep.TOUCH)
                        p.action()
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
                return true
            }
        }
        return pressed != null || nav.isMenuOpen
    }
}
