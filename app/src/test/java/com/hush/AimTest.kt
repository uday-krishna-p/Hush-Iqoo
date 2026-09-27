package com.hush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AimTest {
    // The 1.2 m triangle on the map: this phone A at the origin, B along +x, C below.
    private val a = 0.0 to 0.0; private val b = 1.2 to 0.0; private val c = 0.6 to -1.04

    /** In the room the map is turned by [turn] degrees (and mirrored if asked); the phone lies at heading [h]. */
    private fun roomHeadingOf(mapBearing: Double, turn: Double, mirror: Boolean) = Aim.norm((if (mirror) -mapBearing else mapBearing) + turn)

    @Test fun oneTapGivesTheArrow() {
        val aim = Aim()
        aim.tap("B", Aim.bearing(a, b), roomHeadingOf(Aim.bearing(a, b), 40.0, false))   // pointed at B: heading = B's room bearing
        val knock = 0.3 to 0.9
        // Phone now lying at heading 200: the knock's room bearing minus 200.
        val expected = Aim.norm(roomHeadingOf(Aim.bearing(a, knock), 40.0, false) - 200.0)
        assertEquals(expected, aim.screenDeg(Aim.bearing(a, knock), 200.0)!!, 1e-6)
        assertNull(aim.frame()!!.mirrored)
    }

    @Test fun twoTapsFindAMirroredMap() {
        val aim = Aim()
        for (t in listOf("B" to b, "C" to c)) aim.tap(t.first, Aim.bearing(a, t.second), roomHeadingOf(Aim.bearing(a, t.second), 75.0, true))
        val f = aim.frame()!!
        assertEquals(true, f.mirrored)
        assertTrue(f.spreadDeg!! < 1.0)
        val knock = -0.5 to 0.4
        val expected = Aim.norm(roomHeadingOf(Aim.bearing(a, knock), 75.0, true) - 10.0)
        assertEquals(expected, aim.screenDeg(Aim.bearing(a, knock), 10.0)!!, 1e-6)
    }

    @Test fun twoTapsOnAnUnmirroredMap() {
        val aim = Aim()
        for (t in listOf("B" to b, "C" to c)) aim.tap(t.first, Aim.bearing(a, t.second), roomHeadingOf(Aim.bearing(a, t.second), 300.0, false))
        assertEquals(false, aim.frame()!!.mirrored)
        assertEquals(300.0, aim.frame()!!.rotationDeg, 1e-6)
    }

    @Test fun nothingBeforeATapAndRetapReplaces() {
        val aim = Aim()
        assertNull(aim.screenDeg(10.0, 0.0))
        aim.tap("B", 90.0, 100.0); aim.tap("B", 90.0, 120.0)
        assertEquals(1, aim.frame()!!.refs)
        assertEquals(30.0, aim.frame()!!.rotationDeg, 1e-6)
    }
}
