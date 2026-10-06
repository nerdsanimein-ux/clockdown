package com.rishabh.clockdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRuleTest {
    /** Feeds [readings] at 20 ms steps starting [startMs] after the alarm began; returns the time it said stop, or null. */
    private fun run(rule: MotionRule, startMs: Long, readings: List<Pair<Float, Float>>): Long? {
        var t = startMs
        for ((z, total) in readings) { if (rule.decide(t, t, z, total)) return t; t += 20 }
        return null
    }

    private fun rest(n: Int, faceUp: Boolean = true) = List(n) { (if (faceUp) 9.8f else -9.8f) to 9.81f }

    @Test fun aPhoneLyingStillKeepsRinging() {
        assertTrue(run(MotionRule(), 0, rest(500)) == null)
        assertTrue(run(MotionRule(), 0, rest(500, faceUp = false)) == null) // even face down from the start
    }

    @Test fun turningFaceDownStops() {
        val rule = MotionRule()
        val up = rest(100) // 2 s face up: settled
        val down = rest(40, faceUp = false)
        assertTrue(run(rule, 0, up + down) != null)
    }

    @Test fun aPhoneThatBeganFaceDownNeedsToBeTurnedUpAndDownAgain() {
        val rule = MotionRule()
        val begin = rest(100, faceUp = false)
        assertTrue(run(rule, 0, begin) == null)
        val rule2 = MotionRule()
        assertTrue(run(rule2, 0, begin + rest(20) + rest(40, faceUp = false)) != null)
    }

    @Test fun pickingItUpStops() {
        val rule = MotionRule()
        val lift = List(12) { 9.8f to 12.0f } // the pull jumps well past gravity and stays disturbed
        assertTrue(run(rule, 0, rest(100) + lift) != null)
    }

    @Test fun aSingleBumpOrTheAlarmsOwnBuzzDoesNotStopIt() {
        val bump = List(3) { 9.8f to 12.0f }
        assertTrue(run(MotionRule(), 0, rest(100) + bump + rest(100)) == null)
        val hum = List(200) { 9.8f to (9.81f + (if (it % 2 == 0) 0.4f else -0.4f)) } // table-top vibration is small
        assertTrue(run(MotionRule(), 0, hum) == null)
    }

    @Test fun nothingCountsDuringTheFirstMomentsWhileThePhoneSettles() {
        val rule = MotionRule()
        assertFalse(rule.decide(500, 500, -9.8f, 20f)) // jostled while being put down
        assertFalse(rule.decide(1000, 1000, -9.8f, 20f))
    }
}
