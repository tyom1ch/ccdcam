package at.sturq.ccdcam.studio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import at.sturq.ccdcam.haptics.HapticEvent
import at.sturq.ccdcam.haptics.HapticLevel
import at.sturq.ccdcam.haptics.SliderDetents
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * STUDIO in the manner of a phone camera's pro mode: a row of round controls at the bottom,
 * one ruler slider just above them over the viewfinder, and a strip of live capture values at
 * the top. The viewfinder stays visible; only the selected control shows its ruler.
 */
class StudioPanel(
    private val context: Context,
    private val host: FrameLayout,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onStudioChanged(state: StudioState, picture: Boolean, camera: Boolean)
        fun onZoom(linear: Float)
        fun onHaptic(event: HapticEvent)
        fun manualFocusSupported(): Boolean
        fun currentLinearZoom(): Float
        /** One line of live values, e.g. "f/1.7 · AF · 1/100s · ISO 640". */
        fun liveInfo(): String
        fun onClosed(state: StudioState)
    }

    var state: StudioState = StudioState()
        private set

    val isOpen: Boolean get() = host.visibility == View.VISIBLE

    private val dp = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private var selected: String? = null
    private val chips = LinkedHashMap<String, Chip>()
    private var ruler: RulerView? = null
    private var info: TextView? = null

    /** A round control: slider ([slider] != null) or action. */
    private class Chip(val id: String, val label: String, val icon: Icon, val slider: StudioSlider?, val view: ChipView)

    enum class Icon { RESET, EV, FOCUS, ZOOM, VIGNETTE, SCANLINES, NOISE, SMEAR, FLARE, CHROMA, SHARP, SATURATION, CONTRAST, WARMTH, IR, TACTILE }

    private val infoTicker = object : Runnable {
        override fun run() {
            info?.text = callbacks.liveInfo()
            main.postDelayed(this, 400)
        }
    }

    fun open(initial: StudioState) {
        state = initial.with(StudioSliders.ZOOM, callbacks.currentLinearZoom())
        host.removeAllViews()
        build()
        host.visibility = View.VISIBLE
        main.post(infoTicker)
    }

    fun close() {
        if (!isOpen) return
        main.removeCallbacks(infoTicker)
        host.visibility = View.GONE
        host.removeAllViews()
        chips.clear(); ruler = null; info = null; selected = null
        callbacks.onClosed(state)
    }

    // ---- layout ----

    private fun build() {
        // live values strip (top of the camera area)
        info = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            text = callbacks.liveInfo()
        }
        host.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val r = RulerView(context).apply { visibility = View.INVISIBLE }
        ruler = r
        bottom.addView(r, LinearLayout.LayoutParams((context.resources.displayMetrics.widthPixels * 0.86f).toInt(), (54 * dp).toInt()).apply {
            bottomMargin = (10 * dp).toInt()
        })

        val row = LinearLayout(context).apply { setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0) }
        fun add(id: String, label: String, icon: Icon, slider: StudioSlider?) {
            val v = ChipView(context, icon, label)
            val chip = Chip(id, label, icon, slider, v)
            v.setOnClickListener { onChip(chip) }
            chips[id] = chip
            row.addView(v, LinearLayout.LayoutParams((72 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        add("reset", "Reset All", Icon.RESET, null)
        add(StudioSliders.EV.id, "Exposure", Icon.EV, StudioSliders.EV)
        if (callbacks.manualFocusSupported()) add(StudioSliders.FOCUS.id, "Focus", Icon.FOCUS, StudioSliders.FOCUS)
        add(StudioSliders.ZOOM.id, "Zoom", Icon.ZOOM, StudioSliders.ZOOM)
        add(StudioSliders.VIGNETTE.id, "Vignette", Icon.VIGNETTE, StudioSliders.VIGNETTE)
        add(StudioSliders.IR_SPOT.id, "IR Spot", Icon.IR, StudioSliders.IR_SPOT)
        add(StudioSliders.SCANLINES.id, "Scanlines", Icon.SCANLINES, StudioSliders.SCANLINES)
        add(StudioSliders.NOISE.id, "Noise", Icon.NOISE, StudioSliders.NOISE)
        add(StudioSliders.SMEAR.id, "Smear", Icon.SMEAR, StudioSliders.SMEAR)
        add(StudioSliders.FLARE.id, "Flare", Icon.FLARE, StudioSliders.FLARE)
        add(StudioSliders.CHROMA.id, "Chroma", Icon.CHROMA, StudioSliders.CHROMA)
        add(StudioSliders.SHARPNESS.id, "Sharpness", Icon.SHARP, StudioSliders.SHARPNESS)
        add(StudioSliders.SATURATION.id, "Saturation", Icon.SATURATION, StudioSliders.SATURATION)
        add(StudioSliders.CONTRAST.id, "Contrast", Icon.CONTRAST, StudioSliders.CONTRAST)
        add(StudioSliders.WARMTH.id, "Warmth", Icon.WARMTH, StudioSliders.WARMTH)
        add("tactile", tactileLabel(), Icon.TACTILE, null)

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        bottom.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (6 * dp).toInt()
        })
        host.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        refreshChips()
    }

    private fun tactileLabel() = "Haptics " + when (state.haptics) {
        HapticLevel.OFF -> "Off"
        HapticLevel.SUBTLE -> "Subtle"
        HapticLevel.MECHANICAL -> "Mech."
    }

    private fun onChip(c: Chip) {
        when (c.id) {
            "reset" -> {
                callbacks.onHaptic(HapticEvent.BUTTON_PRESS)
                state = state.resetAll().copy(haptics = state.haptics)
                callbacks.onStudioChanged(state, picture = true, camera = true)
                selected?.let { id -> chips[id]?.slider?.let { ruler?.bind(it) } }
                refreshChips()
            }
            "tactile" -> {
                val next = HapticLevel.entries[(state.haptics.ordinal + 1) % HapticLevel.entries.size]
                state = state.copy(haptics = next)
                callbacks.onStudioChanged(state, picture = false, camera = false)
                callbacks.onHaptic(HapticEvent.SWITCH_TOGGLE)        // felt at the new level
                c.view.label = tactileLabel()
            }
            else -> {
                callbacks.onHaptic(HapticEvent.BUTTON_PRESS)
                if (selected == c.id) {
                    selected = null
                    ruler?.visibility = View.INVISIBLE
                } else {
                    selected = c.id
                    ruler?.bind(c.slider!!)
                    ruler?.visibility = View.VISIBLE
                }
                refreshChips()
            }
        }
    }

    private fun refreshChips() {
        for (c in chips.values) {
            c.view.selectedChip = c.id == selected
            // a dot under controls that are not at their neutral value
            c.view.modified = c.slider?.let { s -> s.id != StudioSliders.ZOOM.id && abs(state.value(s) - s.default) > 1e-3f } ?: false
            c.view.invalidate()
        }
    }

    private fun onValue(s: StudioSlider, v: Float) {
        state = state.with(s, v)
        when (s) {
            StudioSliders.ZOOM -> callbacks.onZoom(v)
            StudioSliders.EV, StudioSliders.FOCUS -> callbacks.onStudioChanged(state, picture = false, camera = true)
            else -> callbacks.onStudioChanged(state, picture = true, camera = false)
        }
        refreshChips()
    }

    // ---- views ----

    /** Round icon button with a label under it; white when selected (as in pro camera modes). */
    @SuppressLint("ViewConstructor")
    private inner class ChipView(ctx: Context, private val icon: Icon, label: String) : View(ctx) {
        var label: String = label
            set(value) { field = value; invalidate() }
        var selectedChip = false
        var modified = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
        private val path = Path()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (86 * dp).toInt())
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = 30 * dp
            val r = 26 * dp
            fill.color = if (selectedChip) Color.WHITE else Color.argb(235, 44, 44, 44)
            c.drawCircle(cx, cy, r, fill)
            line.color = if (selectedChip) Color.rgb(30, 30, 30) else Color.WHITE
            line.strokeWidth = 2.2f * dp
            drawIcon(c, cx, cy, 11 * dp)
            if (modified) { fill.color = Color.rgb(0x5B, 0xE5, 0xDC); c.drawCircle(cx + r * 0.72f, cy - r * 0.72f, 3.5f * dp, fill) }
            text.textSize = 12 * dp
            c.drawText(label, cx, cy + r + 18 * dp, text)
        }

        private fun drawIcon(c: Canvas, x: Float, y: Float, s: Float) {
            path.reset()
            when (icon) {
                Icon.RESET -> {
                    c.drawArc(RectF(x - s, y - s, x + s, y + s), 40f, 290f, false, line)
                    path.moveTo(x + s * 0.95f, y - s * 0.05f); path.lineTo(x + s * 0.6f, y - s * 0.75f); path.lineTo(x + s * 1.15f, y - s * 0.6f)
                    c.drawPath(path, line)
                }
                Icon.EV -> {
                    c.drawCircle(x, y, s * 0.5f, line)
                    for (k in 0 until 8) {
                        val a = Math.PI / 4 * k
                        c.drawLine(x + (s * 0.8f * cos(a)).toFloat(), y + (s * 0.8f * sin(a)).toFloat(),
                            x + (s * 1.1f * cos(a)).toFloat(), y + (s * 1.1f * sin(a)).toFloat(), line)
                    }
                }
                Icon.FOCUS -> {
                    val k = s * 0.45f
                    for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                        c.drawLine(x + sx * s, y + sy * s, x + sx * (s - k), y + sy * s, line)
                        c.drawLine(x + sx * s, y + sy * s, x + sx * s, y + sy * (s - k), line)
                    }
                }
                Icon.ZOOM -> {
                    c.drawCircle(x - s * 0.2f, y - s * 0.2f, s * 0.65f, line)
                    c.drawLine(x + s * 0.3f, y + s * 0.3f, x + s, y + s, line)
                }
                Icon.VIGNETTE -> {
                    c.drawRoundRect(RectF(x - s, y - s * 0.75f, x + s, y + s * 0.75f), s * 0.2f, s * 0.2f, line)
                    c.drawOval(RectF(x - s * 0.6f, y - s * 0.42f, x + s * 0.6f, y + s * 0.42f), line)
                }
                Icon.SCANLINES -> for (k in -2..2) c.drawLine(x - s, y + k * s * 0.42f, x + s, y + k * s * 0.42f, line)
                Icon.NOISE -> {
                    fill.color = line.color
                    for ((dx, dy) in listOf(-0.7f to -0.6f, 0.2f to -0.8f, 0.8f to -0.2f, -0.3f to 0f, 0.5f to 0.6f, -0.8f to 0.7f, 0.1f to 0.4f))
                        c.drawCircle(x + dx * s, y + dy * s, s * 0.13f, fill)
                }
                Icon.SMEAR -> {
                    c.drawLine(x, y - s * 1.1f, x, y + s * 1.1f, line)
                    fill.color = line.color; c.drawCircle(x, y, s * 0.35f, fill)
                }
                Icon.FLARE -> {
                    c.drawLine(x - s * 1.1f, y, x + s * 1.1f, y, line)
                    c.drawLine(x, y - s * 0.5f, x, y + s * 0.5f, line)
                    fill.color = line.color; c.drawCircle(x, y, s * 0.22f, fill)
                }
                Icon.CHROMA -> {
                    c.drawCircle(x - s * 0.3f, y, s * 0.6f, line)
                    c.drawCircle(x + s * 0.3f, y, s * 0.6f, line)
                }
                Icon.SHARP -> {
                    path.moveTo(x, y - s); path.lineTo(x + s, y + s * 0.8f); path.lineTo(x - s, y + s * 0.8f); path.close()
                    c.drawPath(path, line)
                }
                Icon.SATURATION -> {
                    path.moveTo(x, y - s); path.quadTo(x + s, y + s * 0.1f, x, y + s); path.quadTo(x - s, y + s * 0.1f, x, y - s)
                    c.drawPath(path, line)
                }
                Icon.CONTRAST -> {
                    c.drawCircle(x, y, s * 0.9f, line)
                    fill.color = line.color
                    c.drawArc(RectF(x - s * 0.9f, y - s * 0.9f, x + s * 0.9f, y + s * 0.9f), 90f, 180f, true, fill)
                }
                Icon.WARMTH -> {
                    c.drawRoundRect(RectF(x - s * 0.25f, y - s, x + s * 0.25f, y + s * 0.35f), s * 0.25f, s * 0.25f, line)
                    c.drawCircle(x, y + s * 0.65f, s * 0.38f, line)
                    c.drawLine(x + s * 0.6f, y - s * 0.6f, x + s, y - s * 0.6f, line)
                    c.drawLine(x + s * 0.6f, y - s * 0.2f, x + s, y - s * 0.2f, line)
                }
                Icon.IR -> {
                    // crescent: NightShot light
                    c.drawArc(RectF(x - s, y - s, x + s, y + s), 60f, 240f, false, line)
                    c.drawArc(RectF(x - s * 0.2f, y - s * 0.85f, x + s * 1.1f, y + s * 0.85f), 120f, 120f, false, line)
                }
                Icon.TACTILE -> {
                    c.drawRoundRect(RectF(x - s * 0.4f, y - s * 0.8f, x + s * 0.4f, y + s * 0.8f), s * 0.15f, s * 0.15f, line)
                    for (sx in listOf(-1f, 1f)) {
                        c.drawLine(x + sx * s * 0.7f, y - s * 0.4f, x + sx * s * 0.7f, y + s * 0.4f, line)
                        c.drawLine(x + sx * s * 1.05f, y - s * 0.25f, x + sx * s * 1.05f, y + s * 0.25f, line)
                    }
                }
            }
        }
    }

    /**
     * Tick ruler on a dark pill: major ticks at the detents, minor ones between, the neutral
     * point marked; drag left/right to move the white marker (relative, for precision).
     */
    @SuppressLint("ViewConstructor")
    private inner class RulerView(ctx: Context) : View(ctx) {
        private var slider: StudioSlider? = null
        private var detents: SliderDetents? = null
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 24, 24, 24) }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 230, 230, 230); strokeCap = Paint.Cap.ROUND }
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.LEFT }
        private var lastX = 0f

        fun bind(s: StudioSlider) {
            slider = s
            detents = SliderDetents(s.min, s.max, s.steps, s.center).also { it.onValue(state.value(s)) }
            invalidate()
        }

        private val left get() = 92 * dp
        private val right get() = width - 18 * dp

        override fun onDraw(c: Canvas) {
            val s = slider ?: return
            val h = height.toFloat()
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, bg)
            label.textSize = 13 * dp
            c.drawText(s.format(state.value(s)), 18 * dp, h / 2 + 5 * dp, label)
            val minor = 4
            val n = s.steps * minor
            tick.strokeWidth = 1.4f * dp
            for (i in 0..n) {
                val x = left + (right - left) * i / n
                val major = i % minor == 0
                val len = if (major) h * 0.34f else h * 0.18f
                c.drawLine(x, h / 2 - len / 2, x, h / 2 + len / 2, tick)
            }
            s.center?.let { cv ->
                val x = xOf(s, cv)
                tick.strokeWidth = 2f * dp
                c.drawLine(x, h * 0.2f, x, h * 0.8f, tick)
            }
            marker.strokeWidth = 4.5f * dp
            val mx = xOf(s, state.value(s))
            c.drawLine(mx, h * 0.18f, mx, h * 0.82f, marker)
        }

        private fun xOf(s: StudioSlider, v: Float) = left + (right - left) * (v - s.min) / (s.max - s.min)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val s = slider ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    val dv = (e.x - lastX) / (right - left) * (s.max - s.min)
                    lastX = e.x
                    var v = (state.value(s) + dv).coerceIn(s.min, s.max)
                    // gentle snap to the neutral point so it can be found by finger
                    s.center?.let { cv -> if (abs(v - cv) < (s.max - s.min) * 0.012f) v = cv }
                    if (v != state.value(s)) {
                        onValue(s, v)
                        detents?.onValue(v)?.let(callbacks::onHaptic)
                        invalidate()
                    }
                }
            }
            return true
        }
    }
}
