package at.sturq.ccdcam.studio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import at.sturq.ccdcam.haptics.HapticEvent
import at.sturq.ccdcam.haptics.HapticLevel
import at.sturq.ccdcam.haptics.SliderDetents

/**
 * The STUDIO panel: plain Android views above the emulator (deliberately not in the Sony OSD
 * style — it is the app's control room). Sliders give a detent pulse per step, a distinct one
 * at the neutral point and at the ends; dragging between steps stays silent.
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
        /** Lens supports manual focus (minimum focus distance > 0). */
        fun manualFocusSupported(): Boolean
        fun currentLinearZoom(): Float
        fun onClosed(state: StudioState)
    }

    var state: StudioState = StudioState()
        private set

    val isOpen: Boolean get() = host.visibility == View.VISIBLE

    private val dp = context.resources.displayMetrics.density
    private val accent = Color.rgb(0x5B, 0xE5, 0xDC)
    private val dim = Color.rgb(0x9A, 0x9A, 0x9A)
    private val sliders = HashMap<String, Pair<SeekBar, TextView>>()
    private var focusToggle: TextView? = null
    private val levelViews = HashMap<HapticLevel, TextView>()

    fun open(initial: StudioState) {
        state = initial
        host.removeAllViews()
        host.addView(build(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ((host.parent as View).height * 0.62f).toInt().coerceAtLeast((360 * dp).toInt()),
            Gravity.BOTTOM,
        ))
        host.visibility = View.VISIBLE
    }

    fun close() {
        if (!isOpen) return
        host.visibility = View.GONE
        host.removeAllViews()
        callbacks.onClosed(state)
    }

    // ---- views ----

    private fun text(s: String, size: Float = 12f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(context).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        letterSpacing = 0.06f
    }

    private fun button(s: String, onClick: () -> Unit) = text(s, 12f, accent, bold = true).apply {
        setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        setOnClickListener { callbacks.onHaptic(HapticEvent.BUTTON_PRESS); onClick() }
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(text(title, 11f, dim, bold = true).apply {
            setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt())
        })
    }

    private fun build(): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("STUDIO", 15f, accent, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("RESET") { resetLook() })
        header.addView(button("CLOSE") { close() })
        col.addView(header)

        section(col, "PICTURE")
        StudioSliders.LOOK.forEach { col.addView(slider(it, picture = true)) }

        section(col, "NIGHTSHOT")
        StudioSliders.NIGHTSHOT.forEach { col.addView(slider(it, picture = true)) }

        section(col, "CAMERA")
        if (callbacks.manualFocusSupported()) {
            val ft = text("", 12f, accent, bold = true).apply {
                setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
                setOnClickListener {
                    state = state.copy(manualFocus = !state.manualFocus)
                    callbacks.onHaptic(HapticEvent.SWITCH_TOGGLE)
                    refreshFocusToggle()
                    callbacks.onStudioChanged(state, picture = false, camera = true)
                }
            }
            focusToggle = ft
            col.addView(ft)
            col.addView(slider(StudioSliders.FOCUS, camera = true))
            refreshFocusToggle()
        } else {
            col.addView(text("FOCUS: fixed-focus lens", 11f, dim))
        }
        col.addView(slider(StudioSliders.EV, camera = true))
        state = state.with(StudioSliders.ZOOM, callbacks.currentLinearZoom())
        col.addView(slider(StudioSliders.ZOOM, zoom = true))

        section(col, "TACTILE FEEDBACK")
        val levels = LinearLayout(context)
        for (l in HapticLevel.entries) {
            val v = text(l.name, 12f, dim, bold = true).apply {
                setPadding(0, (6 * dp).toInt(), (18 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener {
                    if (state.haptics == l) return@setOnClickListener
                    state = state.copy(haptics = l)
                    refreshLevels()
                    callbacks.onStudioChanged(state, picture = false, camera = false)
                    callbacks.onHaptic(HapticEvent.SWITCH_TOGGLE)     // felt at the new level
                }
            }
            levelViews[l] = v
            levels.addView(v)
        }
        col.addView(levels)
        refreshLevels()

        return ScrollView(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 14, 14, 14))
                cornerRadii = floatArrayOf(18 * dp, 18 * dp, 18 * dp, 18 * dp, 0f, 0f, 0f, 0f)
            }
            isClickable = true                                   // touches stay in the panel
            addView(col)
        }
    }

    private fun slider(s: StudioSlider, picture: Boolean = false, camera: Boolean = false, zoom: Boolean = false): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(context)
        top.addView(text(s.label, 11f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = text(s.format(state.value(s)), 11f, accent)
        top.addView(value)
        row.addView(top)
        val detents = SliderDetents(s.min, s.max, s.steps, s.center)
        val bar = SeekBar(context).apply {
            max = RES
            progress = toProgress(s, state.value(s))
            detents.onValue(state.value(s))                     // start position: no pulse
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = fromProgress(s, p)
                    state = state.with(s, v)
                    value.text = s.format(v)
                    detents.onValue(v)?.let(callbacks::onHaptic)
                    if (zoom) callbacks.onZoom(v) else callbacks.onStudioChanged(state, picture, camera)
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        row.addView(bar)
        sliders[s.id] = bar to value
        return row
    }

    private fun refreshFocusToggle() {
        focusToggle?.text = if (state.manualFocus) "FOCUS: MANUAL" else "FOCUS: AUTO"
        sliders[StudioSliders.FOCUS.id]?.first?.isEnabled = state.manualFocus
    }

    private fun refreshLevels() {
        for ((l, v) in levelViews) v.setTextColor(if (l == state.haptics) accent else dim)
    }

    private fun resetLook() {
        state = state.resetLook()
        for (s in StudioSliders.LOOK + StudioSliders.NIGHTSHOT) {
            sliders[s.id]?.let { (bar, value) -> bar.progress = toProgress(s, s.default); value.text = s.format(s.default) }
        }
        callbacks.onStudioChanged(state, picture = true, camera = false)
    }

    private companion object {
        const val RES = 1000
        fun toProgress(s: StudioSlider, v: Float) = Math.round((v - s.min) / (s.max - s.min) * RES)
        fun fromProgress(s: StudioSlider, p: Int): Float {
            val v = s.min + (s.max - s.min) * p / RES
            // snap gently to the neutral point so "×1.00" is reachable by finger
            val c = s.center ?: return v
            return if (kotlin.math.abs(v - c) < (s.max - s.min) * 0.015f) c else v
        }
    }
}
