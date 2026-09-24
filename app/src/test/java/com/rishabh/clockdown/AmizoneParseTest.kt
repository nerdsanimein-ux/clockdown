package com.rishabh.clockdown

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AmizoneParseTest {
    // Two rows shaped like the real portal response (names are made up), deliberately unsorted.
    private val json = """
    [
      {"id":5372489,"title":"EESU1117 - ENVIRONMENTAL STUDIES (VAC -I)","start":"2026/09/24 03:51:00 PM","end":"2026/09/24 04:45:00 PM",
       "CourseCode":"EESU1117","FacultyName":"<b>Dr Jane Doe  (12345)</b> </br>Group/Sec -  BBA DM+BBA BA ) ","RoomNo":"Haryana-B - 412"},
      {"id":5163793,"title":"ETRU1106 - ORIENTATION PROGRAMME IN  ENTREPRENEURSHIP","start":"2026/09/24 11:21:00 AM","end":"2026/09/24 12:15:00 PM",
       "CourseCode":"ETRU1106","FacultyName":null,"RoomNo":""}
    ]"""

    @Test
    fun parsesSortsAndCleansSample() {
        val e = parseClasses(JSONArray(json))
        assertEquals(listOf(5163793L, 5372489L), e.map { it.amizoneId }) // sorted by start
        // 11:21 AM IST == 05:51 UTC
        assertEquals(Instant.parse("2026-09-24T05:51:00Z").toEpochMilli(), e[0].startMillis)
        assertEquals(Instant.parse("2026-09-24T10:21:00Z").toEpochMilli(), e[1].startMillis) // 03:51 PM
        assertEquals("ORIENTATION PROGRAMME IN ENTREPRENEURSHIP", e[0].name) // double space collapsed
        assertNull(e[0].faculty); assertNull(e[0].room)
        assertEquals("Dr Jane Doe", e[1].faculty)
        assertEquals("Haryana-B - 412", e[1].room)
        assertEquals(AMIZONE, e[1].source)
    }
}
