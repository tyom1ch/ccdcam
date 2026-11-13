package at.sturq.ccdcam.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticModelTest {

    private val full = HapticCapabilities(35, true, Primitive.entries.toSet(), Predefined.entries.toSet())
    private val android11Basic = HapticCapabilities(
        30, true, setOf(Primitive.CLICK, Primitive.TICK, Primitive.QUICK_RISE, Primitive.QUICK_FALL),
        Predefined.entries.toSet(),
    )
    private val android10 = HapticCapabilities(29, true, emptySet(), Predefined.entries.toSet())
    private val android7 = HapticCapabilities(24, true)

    // ---- routing ----

    @Test
    fun everyEventHasAPatternOnEveryTier() {
        for (caps in listOf(full, android11Basic, android10, android7)) {
            for (level in listOf(HapticLevel.SUBTLE, HapticLevel.MECHANICAL)) {
                for (e in HapticEvent.entries) {
                    assertNotEquals("$e $level sdk ${caps.sdk}", HapticPattern.None, HapticRouter.route(e, level, caps))
                }
            }
        }
    }

    @Test
    fun tierFollowsTheDevice() {
        assertTrue(HapticRouter.route(HapticEvent.BUTTON_PRESS, HapticLevel.SUBTLE, full) is HapticPattern.Composition)
        assertTrue(HapticRouter.route(HapticEvent.BUTTON_PRESS, HapticLevel.SUBTLE, android10) is HapticPattern.PredefinedSeq)
        assertTrue(HapticRouter.route(HapticEvent.BUTTON_PRESS, HapticLevel.SUBTLE, android7) is HapticPattern.ViewSeq)
        // 11 without primitive support at all: predefined effects
        val noPrims = android11Basic.copy(primitives = emptySet())
        assertTrue(HapticRouter.route(HapticEvent.SLIDER_DETENT, HapticLevel.SUBTLE, noPrims) is HapticPattern.PredefinedSeq)
    }

    @Test
    fun missingNewPrimitivesAreSubstituted() {
        // THUD / LOW_TICK are Android 12 primitives: an Android 11 vibrator gets CLICK / TICK.
        val p = HapticRouter.route(HapticEvent.TAPE_EJECT, HapticLevel.MECHANICAL, android11Basic)
        p as HapticPattern.Composition
        assertTrue(p.steps.all { it.effect in android11Basic.primitives })
        assertEquals(3, p.steps.size)
    }

    @Test
    fun offNoVibratorOrSystemSettingSilenceEverything() {
        for (e in HapticEvent.entries) {
            assertEquals(HapticPattern.None, HapticRouter.route(e, HapticLevel.OFF, full))
            assertEquals(HapticPattern.None, HapticRouter.route(e, HapticLevel.SUBTLE, full.copy(hasVibrator = false)))
            assertEquals(HapticPattern.None, HapticRouter.route(e, HapticLevel.MECHANICAL, full.copy(systemHapticsEnabled = false)))
        }
    }

    @Test
    fun sliderFeelsAreDistinct() {
        fun pat(e: HapticEvent, caps: HapticCapabilities) = HapticRouter.route(e, HapticLevel.SUBTLE, caps)
        for (caps in listOf(full, android10, android7)) {
            val d = pat(HapticEvent.SLIDER_DETENT, caps)
            val c = pat(HapticEvent.SLIDER_CENTER, caps)
            val s = pat(HapticEvent.SLIDER_ENDSTOP, caps)
            assertNotEquals(d, c); assertNotEquals(d, s); assertNotEquals(c, s)
        }
    }

    @Test
    fun rejectionNeverFeelsLikeConfirmation() {
        for (caps in listOf(full, android10, android7)) for (level in listOf(HapticLevel.SUBTLE, HapticLevel.MECHANICAL)) {
            val r = HapticRouter.route(HapticEvent.ACTION_REJECTED, level, caps)
            for (ok in listOf(HapticEvent.FOCUS_LOCKED, HapticEvent.REC_STARTED, HapticEvent.BUTTON_PRESS)) {
                assertNotEquals("$ok vs reject sdk ${caps.sdk}", HapticRouter.route(ok, level, caps), r)
            }
        }
        // Android 11 without primitives / predefined support: the system REJECT constant
        assertEquals(HapticPattern.ViewSeq(listOf(Step(ViewFeedback.REJECT))),
            HapticRouter.route(HapticEvent.ACTION_REJECTED, HapticLevel.SUBTLE, HapticCapabilities(30, true)))
    }

    @Test
    fun recPressAndRecStartAreDifferent() {
        for (caps in listOf(full, android10, android7)) {
            assertNotEquals(
                HapticRouter.route(HapticEvent.BUTTON_PRESS, HapticLevel.MECHANICAL, caps),
                HapticRouter.route(HapticEvent.REC_STARTED, HapticLevel.MECHANICAL, caps),
            )
        }
    }

    @Test
    fun cassettePatternsAreShortMultiStep() {
        for (e in listOf(HapticEvent.TAPE_EJECT, HapticEvent.TAPE_INSERT)) {
            val p = HapticRouter.route(e, HapticLevel.MECHANICAL, full) as HapticPattern.Composition
            assertTrue(p.steps.size >= 2)
            assertTrue("short", p.steps.sumOf { it.delayMs } <= 250)
        }
    }

    // ---- throttling ----

    @Test
    fun detentsAreRateLimited() {
        val t = HapticThrottle()
        var passed = 0
        for (ms in 0L until 1000L step 5) if (t.allow(HapticEvent.SLIDER_DETENT, ms)) passed++
        assertTrue("$passed pulses in 1 s of fast dragging", passed in 15..23)
    }

    @Test
    fun repeatedPressIsSuppressedButPriorityPasses() {
        val t = HapticThrottle()
        assertTrue(t.allow(HapticEvent.BUTTON_PRESS, 0))
        assertFalse(t.allow(HapticEvent.BUTTON_PRESS, 30))
        assertFalse(t.allow(HapticEvent.SWITCH_TOGGLE, 10))       // global gap
        assertTrue(t.allow(HapticEvent.ACTION_REJECTED, 12))     // priority
        assertTrue(t.allow(HapticEvent.REC_STARTED, 13))
        assertTrue(t.allow(HapticEvent.BUTTON_PRESS, 100))
    }

    // ---- slider detents ----

    @Test
    fun detentsOnlyOnStepCrossings() {
        val d = SliderDetents(0f, 2f, 8, center = 1f)
        assertNull(d.onValue(1.2f))                               // initial position
        val events = (120..200).map { d.onValue(it / 100f) }.filterNotNull()
        // 1.2 → 2.0 crosses steps at 1.25,1.5,1.75 (rounded boundaries) and ends at the stop
        assertEquals(HapticEvent.SLIDER_ENDSTOP, events.last())
        assertEquals(3, events.count { it == HapticEvent.SLIDER_DETENT })
        assertNull(d.onValue(2f))                                 // stays at the end: once
    }

    @Test
    fun centerHasItsOwnFeel() {
        val d = SliderDetents(-2f, 2f, 12, center = 0f)
        d.onValue(-0.5f)
        val events = listOf(-0.3f, -0.1f, 0.05f, 0.2f).mapNotNull { d.onValue(it) }
        assertTrue(HapticEvent.SLIDER_CENTER in events)
        assertEquals(1, events.count { it == HapticEvent.SLIDER_CENTER })
    }

    @Test
    fun slowDragWithinAStepIsSilent() {
        val d = SliderDetents(0f, 1f, 10)
        d.onValue(0.31f)
        for (i in 0..20) assertNull(d.onValue(0.31f + i * 0.001f))
    }
}
