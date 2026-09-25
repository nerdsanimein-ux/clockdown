package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCaseTest {
    @Test
    fun convertsRealCourseNames() {
        assertEquals("Sample Studies (LAB -I)", titleCase("SAMPLE STUDIES (LAB -I)"))
        assertEquals("Skills and Practice-I (LAB-III)", titleCase("SKILLS AND PRACTICE-I (LAB-III)"))
        assertEquals("Introduction to Example Methods", titleCase("INTRODUCTION TO EXAMPLE METHODS"))
        assertEquals("Reading & Writing Basics", titleCase("READING & WRITING BASICS"))
        assertEquals("Thinking for Effectiveness", titleCase("THINKING FOR EFFECTIVENESS"))
        assertEquals("General Example Studies- Foundation", titleCase("GENERAL EXAMPLE STUDIES- FOUNDATION"))
    }

    @Test
    fun leavesMixedCaseAloneAndOnlyAffectsClasses() {
        assertEquals("Movie night", titleCase("Movie night"))
        assertEquals("Pay Luis", Event(name = "Pay Luis", startMillis = 0).title())
        assertEquals("MOVIE NIGHT", Event(name = "MOVIE NIGHT", startMillis = 0).title()) // a manual name is the user's own
        assertEquals("Movie Night", Event(name = "MOVIE NIGHT", startMillis = 0, source = AMIZONE).title())
    }
}
