package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AttendancePlanTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 6) // a Tuesday
    private val now = today.atTime(12, 0).atZone(ist).toInstant().toEpochMilli()

    private fun cls(code: String, day: LocalDate, hh: Int, mm: Int = 0, mins: Int = 54): Event {
        val start = day.atTime(hh, mm).atZone(ist).toInstant().toEpochMilli()
        return Event(id = (day.toEpochDay() * 100 + hh).toInt(), name = code, startMillis = start, endMillis = start + mins * 60_000L, source = AMIZONE, courseCode = code)
    }

    private fun course(code: String, a: Int, t: Int, records: List<ClassRecord> = emptyList()) = CourseAtt(code, code, "1", "2", true, a, t, records)
    private fun snap(vararg c: CourseAtt) = AttSnapshot(c.toList(), 0)

    @Test fun recoveryListsTheRealClassesAndTheDateTargetIsReached() {
        val c = course("AB", 13, 20) // 65%: needs 8 in a row for 75%
        val classes = (1..10).map { cls("AB", today.plusDays(it.toLong()), 9) }
        val r = AttendancePlan.recovery(c, 75, classes)
        assertEquals(8, r.needed); assertEquals(8, r.classes.size)
        assertEquals(today.plusDays(8), r.reachedOn) // the 8th class
        assertEquals(0, r.shortBy)
    }

    @Test fun recoveryIsHonestWhenTheTimetableHasTooFewClasses() {
        val c = course("AB", 13, 20)
        val r = AttendancePlan.recovery(c, 75, (1..5).map { cls("AB", today.plusDays(it.toLong()), 9) })
        assertEquals(8, r.needed); assertEquals(5, r.classes.size)
        assertNull(r.reachedOn) // not reachable inside the timetable
        assertEquals(3, r.shortBy)
        assertEquals("72.0", r.percentAfterAll) // 18/25
    }

    @Test fun attendableSkipsOverAndAlreadyMarkedClasses() {
        val marked = ClassRecord(today, listOf("09:00-09:54"), 1, 0, false)
        val c = course("AB", 5, 7, listOf(marked))
        val s = snap(c)
        val events = listOf(cls("AB", today, 9), cls("AB", today, 10), cls("AB", today, 14), cls("AB", today.plusDays(1), 9), cls("ZZ", today, 15))
        val got = AttendancePlan.attendable(s, c, events, now)
        // 9:00 is marked, 10:00 is over (it's noon) and still unmarked: it may be marked later, so it is NOT counted as upcoming either way.
        assertEquals(listOf(today.atTime(14, 0), today.plusDays(1).atTime(9, 0)), got.map { java.time.Instant.ofEpochMilli(it.startMillis).atZone(ist).toLocalDateTime() })
    }

    @Test fun daysInsideTheTimetableAreExactAndLaterDaysAreEstimated() {
        val thisWed = today.plusDays(1)
        val events = listOf(cls("AB", thisWed, 9), cls("CD", thisWed, 14))
        val (exact, est1) = AttendancePlan.classesOn(thisWed, events, today)
        assertEquals(2, exact.size); assertFalse(est1)
        val nextWed = thisWed.plusDays(7) // day 8 from today: beyond the 7-day timetable
        val (guess, est2) = AttendancePlan.classesOn(nextWed, events, today)
        assertTrue(est2)
        assertEquals(2, guess.size)
        assertEquals(nextWed.atTime(9, 0), java.time.Instant.ofEpochMilli(guess[0].startMillis).atZone(ist).toLocalDateTime()) // same weekday and time
    }

    @Test fun leaveFlagsSubjectsThatDropBelowTheTarget() {
        // AB: 18/24 = 75.0%. Missing two classes -> 18/26 = 69.2%: drops below. CD: 22/23, missing one stays well above.
        // EF: already below (13/20), missing one: stays below.
        val s = snap(course("AB", 18, 24), course("CD", 22, 23), course("EF", 13, 20))
        val fri = today.plusDays(3)
        val events = listOf(cls("AB", fri, 9), cls("AB", fri, 10), cls("CD", fri, 14), cls("EF", fri, 15))
        val out = AttendancePlan.leave(s, events, setOf(fri), now, today, 75, 85, 75).associateBy { it.course.code }
        assertTrue(out.getValue("AB").dropsBelowTarget); assertEquals("69.2", out.getValue("AB").percentAfter); assertEquals(Zone.RED, out.getValue("AB").after)
        assertFalse(out.getValue("CD").dropsBelowTarget); assertFalse(out.getValue("CD").staysBelowTarget)
        assertTrue(out.getValue("EF").staysBelowTarget)
        assertEquals("AB", AttendancePlan.leave(s, events, setOf(fri), now, today, 75, 85, 75).first().course.code) // worst first
    }

    @Test fun leaveTodayOnlyCountsClassesNotMarkedYet() {
        val done = ClassRecord(today, listOf("09:00-09:54"), 1, 0, false)
        val s = snap(course("AB", 5, 7, listOf(done)))
        val events = listOf(cls("AB", today, 9), cls("AB", today, 14))
        val out = AttendancePlan.leave(s, events, setOf(today), now, today, 75, 85, 75).single()
        assertEquals(1, out.classes.size) // the 9:00 class is already in the numbers
    }

    @Test fun aSubjectWithNoMissedClassesIsLeftOut() {
        val s = snap(course("AB", 18, 24), course("CD", 22, 23))
        val out = AttendancePlan.leave(s, listOf(cls("AB", today.plusDays(2), 9)), setOf(today.plusDays(2)), now, today, 75, 85, 75)
        assertEquals(listOf("AB"), out.map { it.course.code })
    }
}
