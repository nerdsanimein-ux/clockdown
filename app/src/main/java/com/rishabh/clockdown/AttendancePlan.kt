package com.rishabh.clockdown

import java.time.LocalDate
import java.time.ZoneId

/** How far ahead the timetable sync looks (today and the next 7 days). Beyond it the plans below can only estimate. */
const val TIMETABLE_DAYS = 7

/**
 * The two planning tools, built on the timetable and the saved attendance. Pure functions: everything they need is passed in,
 * so every case (not enough classes left, a double period, a day past the timetable) can be tested.
 */
object AttendancePlan {
    private fun Event.day(zone: ZoneId) = java.time.Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()

    /** Classes of [course] still to come that can be attended: not over, not already marked. Soonest first. */
    fun attendable(snap: AttSnapshot, course: CourseAtt, events: List<Event>, now: Long): List<Event> =
        events.filter { it.source == AMIZONE && it.courseCode.equals(course.code, true) && (it.endMillis ?: it.startMillis) > now && snap.markFor(it) == ClassMark.NOT_MARKED }
            .sortedBy { it.startMillis }

    /**
     * What attending needs to look like for a subject below [target]%. [classes] are the upcoming classes from [attendable].
     * [needed] is how many in a row get it to the target; if the timetable has fewer, [shortBy] says by how many.
     */
    class Recovery(val needed: Int, val classes: List<Event>, val reachedOn: LocalDate?, val shortBy: Int, /** Where attending every class the timetable still has would leave it. */ val percentAfterAll: String)

    fun recovery(course: CourseAtt, target: Int, classes: List<Event>, zone: ZoneId = AMIZONE_ZONE): Recovery {
        val needed = AttMath.toReach(course.attended, course.total, target)
        val use = classes.take(needed)
        val reached = if (use.size == needed && needed > 0) use.last().day(zone) else null
        return Recovery(needed, use, reached, (needed - use.size).coerceAtLeast(0), AttMath.percent(course.attended + classes.size, course.total + classes.size))
    }

    /**
     * The classes on one day, and whether they are an estimate. Days the timetable covers are exact. A later day is
     * estimated from the same weekday inside the timetable (so "next Friday" assumes this Friday's classes); it can be wrong
     * around holidays and changes, which is why the screen says so.
     */
    fun classesOn(day: LocalDate, events: List<Event>, today: LocalDate, zone: ZoneId = AMIZONE_ZONE): Pair<List<Event>, Boolean> {
        val last = today.plusDays(TIMETABLE_DAYS.toLong())
        val classes = events.filter { it.source == AMIZONE }
        if (!day.isAfter(last)) return classes.filter { it.day(zone) == day }.sortedBy { it.startMillis } to false
        val weeksBack = (java.time.temporal.ChronoUnit.DAYS.between(last, day) + 6) / 7
        val model = day.minusWeeks(weeksBack)
        return classes.filter { it.day(zone) == model }.map { it.copy(startMillis = it.startMillis + weeksBack * 7 * DAY_MS, endMillis = it.endMillis?.plus(weeksBack * 7 * DAY_MS)) }.sortedBy { it.startMillis } to true
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** One subject's result of a leave: what is missed, and where it leaves the percentage. */
    class Miss(
        val course: CourseAtt, val classes: List<Event>, val before: Zone, val after: Zone,
        /** Was at or above the target, ends below it. */
        val dropsBelowTarget: Boolean, val staysBelowTarget: Boolean, val percentAfter: String, val estimated: Boolean,
    )

    /**
     * Misses every class of every tracked subject on [days]. Today only counts classes not marked yet and not over (what's
     * already marked is already in the numbers). Worst result first.
     */
    fun leave(
        snap: AttSnapshot, events: List<Event>, days: Set<LocalDate>, now: Long, today: LocalDate,
        target: Int, green: Int, yellow: Int, zone: ZoneId = AMIZONE_ZONE,
    ): List<Miss> {
        val missed = days.sorted().flatMap { d ->
            val (list, est) = classesOn(d, events, today, zone)
            (if (d == today) list.filter { snap.markFor(it) == ClassMark.NOT_MARKED } else list).map { it to est }
        }
        return snap.counted.mapNotNull { c ->
            val mine = missed.filter { (e, _) -> e.courseCode.equals(c.code, true) }
            if (mine.isEmpty()) return@mapNotNull null
            val n = mine.size
            val wasOk = c.attended * 100 >= target * c.total
            val nowOk = c.attended * 100 >= target * (c.total + n)
            Miss(
                c, mine.map { it.first }, AttMath.zone(c.attended, c.total, green, yellow), AttMath.zone(c.attended, c.total + n, green, yellow),
                wasOk && !nowOk, !wasOk, AttMath.percent(c.attended, c.total + n), mine.any { it.second },
            )
        }.sortedWith(compareByDescending<Miss> { it.dropsBelowTarget }.thenByDescending { it.after.ordinal }.thenBy { it.course.name })
    }
}
