package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Test

class StyleTest {
    private val h = 3_600_000L
    private val d = 24 * h

    @Test
    fun leftTextPicksTheRightUnit() {
        assertEquals("3 days left", leftText(3 * d + 5 * h))
        assertEquals("1 day left", leftText(d + h))
        assertEquals("23h 30m left", leftText(23 * h + 30 * 60_000))
        assertEquals("45m left", leftText(45 * 60_000))
        assertEquals("Now", leftText(0))
    }

    @Test
    fun progressFillsOverSevenDaysAndClamps() {
        assertEquals(0f, progress(30 * d), 0f)
        assertEquals(0.5f, progress((3.5 * d).toLong()), 0.001f)
        assertEquals(1f, progress(-5), 0f)
    }

    @Test
    fun colorIndexIsStableAndInRange() {
        val a = Event(id = 1, name = "x", startMillis = 0, source = AMIZONE, courseCode = "CSSU3105")
        val b = a.copy(id = 99, startMillis = 5) // same course, different session
        assertEquals(a.colorIndex(), b.colorIndex())
        assertEquals(true, Event(id = -7, name = "m", startMillis = 0).colorIndex() in 0..7)
    }
}
