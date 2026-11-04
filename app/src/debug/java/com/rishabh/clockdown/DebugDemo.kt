package com.rishabh.clockdown

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Debug builds only. Generic, made-up data for screenshots: no real student, course or classmate appears. */
object DebugDemo {
    private data class C(val code: String, val name: String, val attended: Int, val total: Int)

    private val courses = listOf(
        C("MKT101", "MARKETING MANAGEMENT", 22, 25), C("FIN102", "FINANCIAL ACCOUNTING", 18, 23), C("ECO103", "BUSINESS ECONOMICS", 23, 25),
        C("COM104", "BUSINESS COMMUNICATION", 15, 21), C("STA105", "BUSINESS STATISTICS", 21, 25),
    )

    fun load(ctx: Context): String {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        Session.save(ctx, "ASP.NET_SessionId=demo; .ASPXAUTH=demo", Session.userAgent(ctx) ?: "Clockdown-demo")
        ctx.prefs.edit().putLong("lastSync", now).putBoolean("unconfirmed", false).apply()

        // Timetable: tomorrow's classes, the first at 9:30.
        val dao = AppDb.get(ctx)
        dao.upcoming(0).forEach { dao.delete(it) }
        val day = LocalDate.now(zone).plusDays(1)
        courses.forEachIndexed { i, c ->
            val start = day.atTime(LocalTime.of(9, 30).plusMinutes(55L * i)).atZone(zone).toInstant().toEpochMilli()
            dao.upsert(Event(name = c.name, startMillis = start, endMillis = start + 55 * 60_000, source = AMIZONE, amizoneId = 900_000L + i,
                courseCode = c.code, faculty = "Prof. A. Sharma", room = "Block A - 20${i + 1}"))
        }

        AttendanceStore.save(ctx, AttSnapshot(courses.map { CourseAtt(it.code, it.name, null, null, true, it.attended, it.total, emptyList()) }, now))

        // LMS: two courses share a name, so the group line shows what tells them apart.
        val base = "https://alms06.amizone.net/moodle_26odd"
        fun item(id: Int, type: String, name: String, badge: String? = null, note: String? = null, dates: String? = null) = LmsItem(id, type, name, badge, note, dates)
        val mkt = LmsCourse(9001, "MARKETING MANAGEMENT", "Gr- BBA (Sec A)/ MKT101/2026-2027/Odd", listOf(
            LmsSection(0, "MARKETING MANAGEMENT", listOf(
                item(1, "forum", "Announcements"),
                item(2, "resource", "Syllabus", "PDF", "syllabus"),
                item(3, "assign", "Market Research Report", note = "Pick a product you use every day and explain who it is for, how it is priced and how it reaches you.",
                    dates = "Opened: Monday, 2 November 2026, 9:00 AM · Due: Saturday, 7 November 2026, 11:59 PM"),
                item(4, "quiz", "Unit 1 Quiz", note = "Quiz", dates = "Opened: Thursday, 5 November 2026, 10:00 AM · Closes: Thursday, 12 November 2026, 5:00 PM"),
            )),
            LmsSection(1, "Module I", listOf(item(5, "url", "Introduction to Marketing", note = "study material"), item(6, "resource", "The 4 Ps of Marketing", "PDF", "study material"))),
        ), now)
        fun simple(id: Int, name: String, group: String, n: Int) = LmsCourse(id, name, group, listOf(LmsSection(0, name, (1..n).map { item(id * 100 + it, "resource", "Lecture $it", "PDF") })), now)
        val lms = LmsSnapshot(base, "2026-2027/Odd", now, false,
            listOf(
                mkt, simple(9002, "FINANCIAL ACCOUNTING", "Gr- BBA (Sec A)/ FIN102/2026-2027/Odd", 12),
                simple(9003, "BUSINESS COMMUNICATION", "Gr- BBA (Sec A)/ COM104/2026-2027/Odd", 9), simple(9004, "BUSINESS COMMUNICATION", "Gr- BBA (Sec B)/ COM104/2026-2027/Odd", 7),
                simple(9005, "BUSINESS ECONOMICS", "Gr- BBA (Sec A)/ ECO103/2026-2027/Odd", 10),
            ),
            listOf(LmsAssign(3, 9001, "Market Research Report", "Monday, 2 November 2026, 9:00 AM", "Saturday, 7 November 2026, 11:59 PM", now, now + 2 * 86_400_000L + 3 * 3_600_000L,
                "No submissions have been made yet", SubStatus.NONE, null, null, null, null, "", emptyList(), now)),
        )
        LmsSession.save(ctx, base, "MoodleSession=demo", "2026-2027/Odd")
        LmsStore.save(ctx, lms)

        // The leaderboard the course screen reads (see LmsSync.leaderboard): made-up classmates.
        val names = listOf("Aarav S", "Diya K", "Demo Student", "Kabir M", "Isha R", "Rohan P", "Meera T", "Arjun V")
        val pct = listOf("70%", "62.5%", "55%", "50%", "45%", "40%", "35%", "30%")
        val rows = names.indices.joinToString("") { i ->
            """<div class="rkt-all-row${if (i == 2) " rkt-lb-me" else ""}"><span class="rkt-rank-sm">${i + 1}</span><div class="rkt-all-info"><div class="rkt-all-name">${names[i].uppercase()} -.</div></div><span class="rkt-all-score">${pct[i]}</span></div>"""
        }
        val html = """<div class="rkt-rank-num">#3</div><div class="rkt-rank-sub">out of 60 students</div><div class="rkt-prog-labels"><span>Course completion</span><span>11 / 20 modules</span></div><div class="rkt-score-line">55% Completed</div><div class="rkt-all-list">$rows</div>"""
        ctx.prefs.edit().putString("debugBoardJson", org.json.JSONObject().put("success", true).put("html", html).toString()).apply()
        return "demo loaded: ${courses.size} classes tomorrow, ${lms.courses.size} LMS courses"
    }
}
