package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCaseTest {
    @Test
    fun convertsRealCourseNames() {
        assertEquals("Environmental Studies (VAC -I)", titleCase("ENVIRONMENTAL STUDIES (VAC -I)"))
        assertEquals("Goal Setting and Time Management-I (VAC-III)", titleCase("GOAL SETTING AND TIME MANAGEMENT-I (VAC-III)"))
        assertEquals("Orientation Programme in Entrepreneurship", titleCase("ORIENTATION PROGRAMME IN ENTREPRENEURSHIP"))
        assertEquals("Internet & Web Fundamentals", titleCase("INTERNET & WEB FUNDAMENTALS"))
        assertEquals("Understanding Self for Effectiveness", titleCase("UNDERSTANDING SELF FOR EFFECTIVENESS"))
        assertEquals("General Business Communication- Foundation", titleCase("GENERAL BUSINESS COMMUNICATION- FOUNDATION"))
    }

    @Test
    fun leavesMixedCaseAloneAndOnlyAffectsClasses() {
        assertEquals("Movie night", titleCase("Movie night"))
        assertEquals("Pay Luis", Event(name = "Pay Luis", startMillis = 0).title())
        assertEquals("MOVIE NIGHT", Event(name = "MOVIE NIGHT", startMillis = 0).title()) // a manual name is the user's own
        assertEquals("Movie Night", Event(name = "MOVIE NIGHT", startMillis = 0, source = AMIZONE).title())
    }
}
