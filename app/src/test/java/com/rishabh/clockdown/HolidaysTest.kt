package com.rishabh.clockdown

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class HolidaysTest {
    private fun row(type: String, title: String, start: String, end: String) =
        """{"id":0,"title":"$title","start":"$start","end":"$end","sType":"$type"}"""

    @Test
    fun keepsOnlyHolidaysSortedAndTidied() {
        val arr = JSONArray("[" + listOf(
            row("C", "ABC1 - A class", "2026/10/01 09:30:00 AM", "2026/10/01 10:25:00 AM"),
            row("H", "Dussehra  (Vijaya Dashami) ", "2026/10/20 12:00:00 AM", "2026/10/20 11:00:00 PM"),
            row("H", "Mahatma Gandhi’s Birthday ", "2026/10/02 12:00:00 AM", "2026/10/02 11:00:00 PM"),
            row("H", "Dussehra  (Vijaya Dashami) ", "2026/10/20 12:00:00 AM", "2026/10/20 11:00:00 PM"),
        ).joinToString(",") + "]")
        assertEquals(
            listOf(Holiday(LocalDate.of(2026, 10, 2), "Mahatma Gandhi’s Birthday"), Holiday(LocalDate.of(2026, 10, 20), "Dussehra (Vijaya Dashami)")),
            parseHolidays(arr),
        )
    }

    @Test
    fun aMultiDayEntryFillsEachDay() {
        val arr = JSONArray("[" + row("H", "Break", "2026/12/24 12:00:00 AM", "2026/12/26 11:00:00 PM") + "]")
        assertEquals(listOf(24, 25, 26), parseHolidays(arr).map { it.date.dayOfMonth })
    }

    @Test
    fun daysAway() {
        val d = LocalDate.of(2026, 10, 12)
        assertEquals(listOf("Today", "Tomorrow", "in 8 days"), listOf(0L, 1L, 8L).map { daysAwayText(d, d.plusDays(it)) })
    }

    @Test
    fun holidaysAreNeverParsedAsClasses() {
        val cls = """{"id":5132929,"title":"BEHU3101 - SELF","start":"2026/10/01 02:01:00 PM","end":"2026/10/01 02:55:00 PM","sType":"C","CourseCode":"BEHU3101"}"""
        val arr = JSONArray("[" + row("H", "Maha Navmi", "2026/10/19 12:00:00 AM", "2026/10/19 11:00:00 PM") + "," + cls + "]")
        assertEquals(listOf(5132929L), parseClasses(arr).map { it.amizoneId })
    }
}
