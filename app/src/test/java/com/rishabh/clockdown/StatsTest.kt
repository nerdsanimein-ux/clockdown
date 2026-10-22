package com.rishabh.clockdown

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {
    @Test
    fun theBodyHoldsTheIdTheVersionAndNothingElse() {
        val o = JSONObject(Stats.body("ab".repeat(16), 12, "1.7", fresh = true, debug = false))
        assertEquals(setOf("id", "v", "vn", "fresh", "debug"), o.keys().asSequence().toSet())
        assertEquals(12, o.getInt("v"))
        assertEquals("1.7", o.getString("vn"))
        assertEquals(true, o.getBoolean("fresh"))
    }
}
