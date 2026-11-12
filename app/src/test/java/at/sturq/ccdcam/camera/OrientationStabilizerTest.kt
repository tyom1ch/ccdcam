package at.sturq.ccdcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationStabilizerTest {

    @Test
    fun steadyTurnIsAcceptedAfterHold() {
        val s = OrientationStabilizer()
        assertFalse(s.update(90, 0))
        assertFalse(s.update(92, 200))
        assertTrue(s.update(88, 450))
        assertEquals(90, s.rotationCw)
    }

    @Test
    fun shakingDoesNotFlip() {
        val s = OrientationStabilizer()
        var t = 0L
        repeat(40) {
            s.update(if (it % 2 == 0) 80 else 10, t)
            t += 100
        }
        assertEquals(0, s.rotationCw)
    }

    @Test
    fun nearDiagonalIsIgnored() {
        val s = OrientationStabilizer()
        for (t in 0L..3000L step 100) s.update(55, t)   // just past the 45° boundary
        assertEquals(0, s.rotationCw)
    }

    @Test
    fun wrapsAroundZero() {
        val s = OrientationStabilizer(initialCw = 270)
        s.update(355, 0)
        s.update(5, 500)
        assertEquals(0, s.rotationCw)
        assertFalse(s.update(-1, 1000))
        assertEquals(0, s.rotationCw)
    }
}
