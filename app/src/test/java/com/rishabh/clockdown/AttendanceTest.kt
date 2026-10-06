package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AttendanceTest {
    // ---- the two pages, shaped like Amizone's (every name, code and number here is invented)

    private fun courseRow(code: String, name: String, attendance: String) = """
        <tr>
          <td data-title="Course Code">
            $code
          </td>
          <td data-title="Course Name">
            $name
          </td>
          <td data-title="Type">Core<br> [Group Name: X]</td>
          <td data-title="Session Plan"><button onclick="FnShow('1','2')">View</button></td>
          <td data-title="Attendance">
            $attendance
          </td>
          <td data-title="Internal Asses."></td>
        </tr>"""

    private fun btn(cid: String, sgid: String, shown: String) =
        """<button onclick="Fnattendance2('$cid','$sgid')" class="btn"><i class="ace-icon">$shown</i></button>"""

    private val listPage = """<html><table>
        <thead><tr><th>Course Code</th></tr></thead>
        ${courseRow("AB101", "INTRO TO SAMPLES", btn("11", "21", "18/24 (75.00)"))}
        ${courseRow("CD202", "ADVANCED   EXAMPLES", btn("12", "22", "13/20 (65.00)"))}
        ${courseRow("EF303", "UNTRACKED COURSE", "NA")}
        </table></html>"""

    private fun histRow(n: Int, date: String, time: String, p: Int, a: Int, remarks: String = "") = """
        <tr>
          <td data-title="Sno"> $n </td>
          <td data-title="Date Of Class"> $date </td>
          <td data-title="Timings Of Class"> $time </td>
          <td data-title="present"> $p </td>
          <td data-title="Absent"> $a </td>
          <td data-title="Remarks"> $remarks </td>
          <td data-title="Class Video"></td>
        </tr>"""

    private val historyPage = """<div><h1> Attendance Details : </h1><table>
        <thead class="cf"><tr><th>SNo</th><th>Date Of Class</th></tr></thead>
        ${histRow(1, "20/08/2026", "[14:01-14:55]", 0, 1)}
        ${histRow(2, "27/08/2026", "[09:30-10:25][10:26-11:20]", 2, 0)}
        ${histRow(3, "03/09/2026", "[14:01-14:55]", 1, 0, "OD marked as present")}
        <tr><td></td><td></td><td data-title="Total"> Total Attendance </td><td data-title="present"> 3 </td><td data-title="Absent"> 1 </td><td data-title="Remarks"> 75.00 </td></tr>
        </table></div>"""

    // ---- parsing

    @Test fun listPageGivesCountsIdsAndNotTracked() {
        val c = AttendanceParse.courses(listPage)
        assertEquals(listOf("AB101", "CD202", "EF303"), c.map { it.code })
        assertEquals("ADVANCED EXAMPLES", c[1].name) // runs of spaces collapsed
        assertEquals(18, c[0].attended); assertEquals(24, c[0].total)
        assertEquals("11", c[0].cid); assertEquals("21", c[0].sgid)
        assertTrue(c[0].tracked)
        assertFalse(c[2].tracked) // "NA"
        assertNull(c[2].cid)
    }

    @Test fun historyCountsDoublePeriodsAndMarksOd() {
        val r = AttendanceParse.history(historyPage)
        assertEquals(3, r.size) // the Total row is not a session
        assertEquals(LocalDate.of(2026, 9, 3), r[0].date) // newest first
        assertEquals(Mark.OD, r[0].mark) // counts as present, labelled OD
        val double = r.first { it.date == LocalDate.of(2026, 8, 27) }
        assertEquals(2, double.present) // a double period is worth 2, one row
        assertEquals(listOf("09:30-10:25", "10:26-11:20"), double.slots)
        assertEquals(Mark.ABSENT, r.last().mark)
        // Counting by columns (not rows) reproduces the page's own total.
        assertEquals(3, r.sumOf { it.present }); assertEquals(1, r.sumOf { it.absent })
    }

    @Test fun anUnrecognisedPageIsAFormatFailureNotEmptyNumbers() {
        for (bad in listOf("<html>nothing here</html>", "", listPage.replace("18/24 (75.00)", "lots"), listPage.replace("18/24", "30/24"))) {
            try { AttendanceParse.courses(bad); fail("should not parse: ${bad.take(30)}") } catch (e: AttendanceParse.AttendanceFormatException) { assertEquals("list-format", e.kind) }
        }
        try { AttendanceParse.history("<html>moved</html>"); fail() } catch (e: AttendanceParse.AttendanceFormatException) { assertEquals("history-format", e.kind) }
        try { AttendanceParse.history(historyPage.replace("[14:01-14:55]", "[x]").replace("> 0 <", "> two <")); fail() } catch (e: AttendanceParse.AttendanceFormatException) { assertEquals("history-format", e.kind) }
    }

    @Test fun aCourseWithNoRecordAtAllIsEmptyNotAFailure() {
        assertEquals(0, AttendanceParse.history("<h1> Record Not Exit </h1>").size)
    }

    // ---- the arithmetic

    @Test fun zonesMatchAmizonesChart() {
        // Green is ABOVE 85, yellow is 75 to 85 (both ends in), red is below 75.
        assertEquals(Zone.GREEN, AttMath.zone(86, 100, 85, 75))
        assertEquals(Zone.YELLOW, AttMath.zone(85, 100, 85, 75)) // exactly 85 is not above it
        assertEquals(Zone.YELLOW, AttMath.zone(75, 100, 85, 75)) // exactly 75 is the minimum, so not red
        assertEquals(Zone.RED, AttMath.zone(74, 100, 85, 75))
        assertEquals(Zone.YELLOW, AttMath.zone(18, 24, 85, 75)) // 75.0%
        assertEquals(Zone.RED, AttMath.zone(13, 20, 85, 75)) // 65%
    }

    @Test fun classesNeededAndSpare() {
        // 13/20 = 65%: need x with (13+x)/(20+x) >= 0.75  ->  x = 8  (21/28 = 0.75)
        assertEquals(8, AttMath.toReach(13, 20, 75))
        assertEquals(0, AttMath.toReach(18, 24, 75))
        // 4/8 = 50%: to 80%: (4+x)/(8+x) >= 0.8 -> x = 12 (16/20)
        assertEquals(12, AttMath.toReach(4, 8, 80))
        // 22/23 can miss floor((2200 - 75*23)/75) = 6; 18/24 can miss 0; 20/24 -> floor((2000-1800)/75) = 2
        assertEquals(6, AttMath.canMiss(22, 23, 75)); assertEquals(0, AttMath.canMiss(18, 24, 75)); assertEquals(2, AttMath.canMiss(20, 24, 75))
        // The answers really do land on the right side of the line.
        for (a in 0..30) for (t in a..40) if (t > 0) {
            val x = AttMath.toReach(a, t, 75)
            assertTrue((a + x) * 100 >= 75 * (t + x))
            if (x > 0) assertFalse((a + x - 1) * 100 >= 75 * (t + x - 1))
            if (a * 100 >= 75 * t) {
                val m = AttMath.canMiss(a, t, 75)
                assertTrue(a * 100 >= 75 * (t + m))
                assertFalse(a * 100 >= 75 * (t + m + 1))
            }
        }
    }

    @Test fun percentIsNeverRoundedUpOverALine() {
        assertEquals("74.9", AttMath.percent(749, 1000))
        assertEquals("74.9", AttMath.percent(7499, 10000)) // 74.99 shows as 74.9, not 75.0
        assertEquals("75.0", AttMath.percent(18, 24))
        assertEquals("–", AttMath.percent(0, 0))
    }

    // ---- today's classes

    private val ist = ZoneId.of("Asia/Kolkata")
    private fun event(code: String, date: LocalDate, hh: Int, mm: Int) =
        Event(name = "X", startMillis = date.atTime(hh, mm).atZone(ist).toInstant().toEpochMilli(), source = AMIZONE, courseCode = code)

    private val today = LocalDate.of(2026, 10, 6)
    private val snap = AttSnapshot(
        listOf(
            CourseAtt("AB101", "A", "1", "2", true, 3, 4, listOf(
                ClassRecord(today, listOf("09:30-10:25", "10:26-11:20"), 2, 0, false),
                ClassRecord(today.minusDays(7), listOf("14:01-14:55"), 0, 1, false),
            )),
            CourseAtt("EF303", "U", null, null, false, 0, 0, emptyList()),
        ),
        0,
    )

    @Test fun todaysClassesAreMarkedPresentAbsentOrNotMarkedYet() {
        // Both periods of the double row are present; the same course later today has no row yet.
        assertEquals(ClassMark.PRESENT, snap.markFor(event("AB101", today, 9, 30)))
        assertEquals(ClassMark.PRESENT, snap.markFor(event("AB101", today, 10, 26)))
        assertEquals(ClassMark.NOT_MARKED, snap.markFor(event("AB101", today, 14, 1)))
        assertEquals(ClassMark.ABSENT, snap.markFor(event("ab101", today.minusDays(7), 14, 1))) // course code case doesn't matter
        // Never "Absent" without a row, and nothing at all for a course Amizone doesn't track or doesn't list.
        assertNull(snap.markFor(event("EF303", today, 9, 30)))
        assertNull(snap.markFor(event("ZZ999", today, 9, 30)))
    }

    // ---- notifications

    @Test fun markChangesAreOnlyNewOrChangedRecentRows() {
        val old = snap.copy(courses = snap.courses.map { if (it.code == "AB101") it.copy(records = it.records.drop(1)) else it })
        val n = AttendanceChanges.marks(old, snap, today)
        assertEquals(1, n.size) // the new row dated today; the week-old row is not news
        assertEquals(Mark.PRESENT, n[0].mark)
        assertTrue(AttendanceChanges.marks(snap, snap, today).isEmpty())
    }

    @Test fun onlyDropsToAWorseZoneAreReported() {
        fun s(a: Int, t: Int) = AttSnapshot(listOf(CourseAtt("AB101", "A", null, null, true, a, t, emptyList())), 0)
        assertEquals(Zone.YELLOW, AttendanceChanges.drops(s(22, 24), s(22, 26), 85, 75).single().to) // 91.7 -> 84.6
        assertEquals(Zone.RED, AttendanceChanges.drops(s(18, 24), s(18, 25), 85, 75).single().to) // 75.0 -> 72.0
        assertTrue(AttendanceChanges.drops(s(13, 20), s(14, 22), 85, 75).isEmpty()) // red -> red
        assertTrue(AttendanceChanges.drops(s(13, 20), s(18, 22), 85, 75).isEmpty()) // improved
    }

    @Test fun snapshotSurvivesSaveAndLoad() {
        val back = AttendanceStore.fromJson(AttendanceStore.toJson(snap.copy(updatedAt = 42)))
        assertEquals(snap.copy(updatedAt = 42), back)
    }

    // ---- is the session still alive? (HTML replies, not just the timetable's JSON)

    @Test fun htmlRepliesAreClassifiedForTheSession() {
        val marker = arrayOf(AttendanceParse.LIST_MARKER)
        assertEquals(Verdict.OK, SessionCheck.classifyPage(200, listPage, marker))
        assertEquals(Verdict.EXPIRED, SessionCheck.classifyPage(302, "", marker)) // the portal sends the signed-out to its login page
        assertEquals(Verdict.EXPIRED, SessionCheck.classifyPage(401, "", marker))
        assertEquals(Verdict.EXPIRED, SessionCheck.classifyPage(200, """<form id="loginform"><input name="_UserName"></form>""", marker))
        assertEquals(Verdict.PROBLEM, SessionCheck.classifyPage(500, "<html>Error.</html>", marker)) // a server problem is not a dead sign-in
        assertEquals(Verdict.PROBLEM, SessionCheck.classifyPage(503, "", marker))
        assertNull(SessionCheck.classifyPage(200, "<html>a different page</html>", marker)) // page changed shape
    }
}
