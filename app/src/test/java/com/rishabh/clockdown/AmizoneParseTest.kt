package com.rishabh.clockdown

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AmizoneParseTest {
    // Two rows shaped like the portal's response, deliberately unsorted. Every name, code and room here is invented.
    private val json = """
    [
      {"id":9000002,"title":"XY101 - INTRODUCTION TO SAMPLE STUDIES (LAB-II)","start":"2026/09/24 03:51:00 PM","end":"2026/09/24 04:45:00 PM",
       "CourseCode":"XY101","FacultyName":"<b>Dr Jane Doe  (12345)</b> </br>Group/Sec -  Example Group A ) ","RoomNo":"Block A - 101"},
      {"id":9000001,"title":"ZQ202 - ADVANCED EXAMPLE    PRACTICE","start":"2026/09/24 11:21:00 AM","end":"2026/09/24 12:15:00 PM",
       "CourseCode":"ZQ202","FacultyName":null,"RoomNo":""}
    ]"""

    @Test
    fun parsesSortsAndCleansSample() {
        val e = parseClasses(JSONArray(json))
        assertEquals(listOf(9000001L, 9000002L), e.map { it.amizoneId }) // sorted by start
        // 11:21 AM IST == 05:51 UTC
        assertEquals(Instant.parse("2026-09-24T05:51:00Z").toEpochMilli(), e[0].startMillis)
        assertEquals(Instant.parse("2026-09-24T10:21:00Z").toEpochMilli(), e[1].startMillis) // 03:51 PM
        assertEquals("ADVANCED EXAMPLE PRACTICE", e[0].name) // repeated spaces collapsed
        assertNull(e[0].faculty); assertNull(e[0].room)
        assertEquals("Dr Jane Doe", e[1].faculty)
        assertEquals("Block A - 101", e[1].room)
        assertEquals(AMIZONE, e[1].source)
    }

    @Test
    fun anyNumberOfClassesInADayIsFine() {
        // Nothing assumes a particular timetable shape: none, one, or a packed day all parse.
        assertEquals(0, parseClasses(JSONArray("[]")).size)
        val many = (1..12).joinToString(",", "[", "]") {
            """{"id":$it,"title":"C$it - Course $it","start":"2026/09/24 0${it % 9 + 1}:00:00 AM","end":"2026/09/24 0${it % 9 + 1}:50:00 AM","CourseCode":"C$it","FacultyName":null,"RoomNo":null}"""
        }
        assertEquals(12, parseClasses(JSONArray(many)).size)
    }
}
