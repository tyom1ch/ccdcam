package at.sturq.ccdcam.studio

import at.sturq.ccdcam.profile.Profiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioTest {

    @Test
    fun neutralStudioKeepsTheMenuLook() {
        val base = Profiles.HC90E_PAL
        assertEquals(base, StudioLook.apply(base, StudioState()))
    }

    @Test
    fun slidersChangeTheirParameter() {
        val base = Profiles.HC90E_PAL
        val st = StudioState()
            .with(StudioSliders.VIGNETTE, 2f)
            .with(StudioSliders.SCANLINES, 0f)
            .with(StudioSliders.SMEAR, 0.5f)
            .with(StudioSliders.IR_SPOT, 1f)
            .with(StudioSliders.WARMTH, 1f)
        val p = StudioLook.apply(base, st).shader
        assertEquals(base.shader.vignetteStrength * 2f, p.vignetteStrength, 1e-6f)
        assertEquals(0f, p.scanlineAmp)
        assertEquals(0.3f, p.smearStrength, 1e-6f)
        assertEquals(1f, p.irSpot)
        assertTrue(p.warmGrade.first > base.shader.warmGrade.first && p.warmGrade.third < base.shader.warmGrade.third)
    }

    @Test
    fun nightShotIrSpotIsOnByDefault() {
        assertEquals(0.7f, StudioState().value(StudioSliders.IR_SPOT))
        assertEquals(0.7f, Profiles.HC90E_PAL.shader.irSpot)
    }

    @Test
    fun valuesAreClampedAndResetKeepsCameraAndTactile() {
        val st = StudioState(manualFocus = true, haptics = at.sturq.ccdcam.haptics.HapticLevel.MECHANICAL)
            .with(StudioSliders.EV, 9f)
            .with(StudioSliders.NOISE, 1.5f)
        assertEquals(2f, st.value(StudioSliders.EV))
        val r = st.resetLook()
        assertEquals(1f, r.value(StudioSliders.NOISE))
        assertEquals(2f, r.value(StudioSliders.EV))
        assertTrue(r.manualFocus)
        assertEquals(at.sturq.ccdcam.haptics.HapticLevel.MECHANICAL, r.haptics)
    }

    @Test
    fun everySliderHasSaneRange() {
        for (s in StudioSliders.ALL) {
            assertTrue(s.id, s.min < s.max && s.default in s.min..s.max && s.steps > 0)
            s.center?.let { assertTrue(s.id, it in s.min..s.max) }
        }
        assertEquals(StudioSliders.ALL.size, StudioSliders.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun focusSliderStartsWithAutofocus() {
        val st = StudioState()
        assertTrue(!st.manualFocusActive)
        assertEquals("AF", StudioSliders.FOCUS.format(0f))
        val inf = st.with(StudioSliders.FOCUS, StudioSliders.FOCUS_AF_MAX + 0.01f)
        assertTrue(inf.manualFocusActive)
        assertEquals("∞", StudioSliders.FOCUS.format(StudioSliders.FOCUS_AF_MAX + 0.01f))
        assertEquals(1f, st.with(StudioSliders.FOCUS, 1f).focusFraction, 1e-6f)
    }

    @Test
    fun resetAllClearsEverythingButTactile() {
        val st = StudioState(haptics = at.sturq.ccdcam.haptics.HapticLevel.OFF)
            .with(StudioSliders.EV, 1f).with(StudioSliders.FOCUS, 0.5f).with(StudioSliders.VIGNETTE, 2f)
        val r = st.resetAll()
        assertEquals(0f, r.value(StudioSliders.EV))
        assertTrue(!r.manualFocusActive)
        assertEquals(1f, r.value(StudioSliders.VIGNETTE))
        assertEquals(at.sturq.ccdcam.haptics.HapticLevel.OFF, r.haptics)
    }
}
