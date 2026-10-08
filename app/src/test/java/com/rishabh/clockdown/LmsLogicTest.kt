package com.rishabh.clockdown

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

class LmsSsoTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val signedIn = """<html><script>M.cfg = {"wwwroot":"x","userId":4242,"sesskey":"k"}</script></html>"""

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.close() }

    private fun form() = SsoForm("2026-2027/Odd", server.url("/moodle_26odd/submit.php").toString(), listOf("usercode" to "u@example.test", "token" to "t0k&en", "userId" to "42"))
    private fun go(f: SsoForm = form()) = LmsSso.postForm(client, f, "UA-test", 1_800_000_000_000L, { a -> a.substringBeforeLast("/submit.php") }, secure = false)
    private fun redirect(code: Int, to: String, cookie: String? = null) =
        MockResponse.Builder().code(code).addHeader("Location", to).apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.build()

    @Test
    fun followsTheWholeChainTheRealSiteUsesAndKeepsTheSessionCookie() {
        server.enqueue(redirect(302, "/moodle_26odd", "MoodleSession=abc123; path=/moodle_26odd/; secure; HttpOnly"))
        server.enqueue(redirect(301, "/moodle_26odd/"))
        server.enqueue(redirect(303, "/moodle_26odd/my/courses.php"))
        server.enqueue(MockResponse.Builder().code(200).body(signedIn).build())
        val r = go() as SsoResult.Ok
        assertEquals(server.url("/moodle_26odd").toString().trimEnd('/'), r.base)
        assertTrue("MoodleSession=abc123" in r.cookie)
        assertEquals("2026-2027/Odd", r.label)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("UA-test", post.headers["User-Agent"])
        assertEquals("https://s.amizone.net/Home", post.headers["Referer"])
        assertEquals("https://s.amizone.net", post.headers["Origin"])
        assertEquals("usercode=u%40example.test&token=t0k%26en&userId=42", post.body?.utf8()) // exactly the form's own fields
        val next = server.takeRequest()
        assertEquals("GET", next.method) // a redirect after a POST is followed with a GET
        assertNull(next.headers["Referer"])
        server.takeRequest(); val last = server.takeRequest()
        assertTrue("MoodleSession=abc123" in last.headers["Cookie"].orEmpty()) // the cookie travels along the chain
    }

    @Test
    fun neverFollowsARedirectToAnotherSite() {
        server.enqueue(redirect(302, "https://evil.example/steal"))
        assertEquals("redirect-away", (go() as SsoResult.Failed).kind)
        assertEquals(1, server.requestCount) // it stopped right there
    }

    @Test
    fun aPageThatIsNotSignedInIsNotSuccess() {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "MoodleSession=x; path=/").body("""M.cfg = {"userId":0}""").build())
        assertEquals("not-signed-in", (go() as SsoResult.Failed).kind)
        server.enqueue(MockResponse.Builder().code(200).body(signedIn).build()) // signed in but no session cookie ever arrived
        assertEquals("not-signed-in", (go() as SsoResult.Failed).kind)
    }

    @Test
    fun givesUpAfterSixRedirectsAndNeverLoops() {
        repeat(10) { server.enqueue(redirect(302, "/loop")) }
        assertEquals("too-many-redirects", (go() as SsoResult.Failed).kind)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun anHttpErrorIsAFailureWithItsCode() {
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals("http-503", (go() as SsoResult.Failed).kind)
    }

    @Test
    fun aFormPointingAnywhereButAmizoneIsRefusedBeforeAnythingIsSent() {
        val r = LmsSso.postForm(client, SsoForm("x", "https://evil.example/m/submit.php", listOf("token" to "secret")), "ua", 1L, LmsParse::ssoBase)
        assertEquals("bad-action", (r as SsoResult.Failed).kind)
        assertEquals(0, server.requestCount)
    }
}

class LmsLogicTest {
    private fun reply(code: Int, body: String = "", location: String? = null) = LmsReply(code, null, location, body, emptyList())
    private val ist = ZoneId.of("Asia/Kolkata")

    // ---------------------------------------------------------------- what a reply says about the session
    @Test
    fun aRedirectToTheLoginPageMeansTheSessionIsGone() {
        assertEquals(Verdict.EXPIRED, lmsVerdict(reply(303, location = "https://h/moodle_26odd/login/index.php")))
        assertEquals(Verdict.PROBLEM, lmsVerdict(reply(302, location = "https://h/somewhere/else"))) // not a login: don't say "sign in"
        assertEquals(Verdict.EXPIRED, lmsVerdict(reply(401)))
        assertEquals(Verdict.PROBLEM, lmsVerdict(reply(500)))
        assertEquals(Verdict.PROBLEM, lmsVerdict(reply(404)))
        assertEquals(Verdict.PROBLEM, lmsVerdict(reply(403)))
    }

    @Test
    fun aPageForAGuestOrTheLoginFormMeansSignedOut() {
        assertEquals(Verdict.OK, lmsVerdict(reply(200, """M.cfg = {"userId":4242}""")))
        assertEquals(Verdict.EXPIRED, lmsVerdict(reply(200, """M.cfg = {"userId":0}""")))
        assertEquals(Verdict.EXPIRED, lmsVerdict(reply(200, "<input name=\"logintoken\" value=\"a\">")))
        assertEquals(Verdict.OK, lmsVerdict(reply(200, "[{\"error\":false}]"), html = false)) // an AJAX answer has no page config
    }

    // ---------------------------------------------------------------- "assignment pages only when needed"
    private fun assign(status: SubStatus?, fetchedAt: Long) =
        LmsAssign(1, 1, "A", null, null, null, null, null, status, null, null, null, null, "", emptyList(), fetchedAt)

    private val hour = 3_600_000L

    @Test
    fun anAssignmentPageIsReadOnlyWhenItMayHaveChanged() {
        val now = 1_800_000_000_000L
        assertTrue(assignNeedsRead(null, now))                                                // never seen
        assertTrue(assignNeedsRead(assign(null, 0), now))                                     // seen on a course page, never opened
        assertFalse(assignNeedsRead(assign(SubStatus.NONE, now - hour), now))                 // pending, read an hour ago
        assertTrue(assignNeedsRead(assign(SubStatus.NONE, now - 3 * hour), now))              // pending, a while ago
        assertFalse(assignNeedsRead(assign(SubStatus.SUBMITTED, now - 10 * hour), now))       // handed in: leave it alone...
        assertTrue(assignNeedsRead(assign(SubStatus.SUBMITTED, now - 25 * hour), now))        // ...until a grade could have appeared
    }

    // ---------------------------------------------------------------- Due soon grouping
    private fun due(name: String, dueMs: Long?, status: SubStatus? = SubStatus.NONE) =
        LmsAssign(name.hashCode(), 1, name, null, null, null, dueMs, null, status, null, null, null, null, "", emptyList(), 1)

    @Test
    fun groupsByHowSoonAndKeepsSubmittedApart() {
        val now = java.time.ZonedDateTime.of(2026, 10, 8, 20, 0, 0, 0, ist).toInstant().toEpochMilli()
        fun at(d: Int, h: Int) = java.time.ZonedDateTime.of(2026, 10, d, h, 0, 0, 0, ist).toInstant().toEpochMilli()
        val all = listOf(
            due("late", at(7, 14)), due("tonight", at(8, 23)), due("tomorrow", at(9, 9)), due("nextweek", at(15, 9)),
            due("later", at(30, 9)), due("nodate", null), due("done", at(9, 9), SubStatus.SUBMITTED), due("draft", at(9, 10), SubStatus.DRAFT),
        )
        val g = LmsDue.grouped(all, now, ist)
        assertEquals(listOf("late"), g[DueGroup.OVERDUE]!!.map { it.name })
        assertEquals(listOf("tonight"), g[DueGroup.TODAY]!!.map { it.name })
        assertEquals(listOf("tomorrow", "draft", "nextweek"), g[DueGroup.WEEK]!!.map { it.name })
        assertEquals(listOf("later", "nodate"), g[DueGroup.LATER]!!.map { it.name })
        assertEquals(listOf("done"), LmsDue.submitted(all).map { it.name })
    }

    @Test
    fun theWidgetListSkipsSubmittedAndLongMissedOnesAndStopsAtTheLimit() {
        val now = 1_800_000_000_000L
        val all = listOf(
            due("done", now + hour, SubStatus.SUBMITTED), due("gone", now - 3 * 24 * hour), due("justmissed", now - hour),
            due("a", now + hour), due("b", now + 2 * hour), due("c", now + 3 * hour), due("d", now + 4 * hour),
        )
        assertEquals(listOf("justmissed", "a", "b"), LmsDue.next(all, now, 3).map { it.name })
    }

    // ---------------------------------------------------------------- reminders
    @Test
    fun remindersAre24And3HoursBeforeAndOnlyForWhatIsNotSubmitted() {
        val now = 1_800_000_000_000L
        val all = listOf(
            due("open", now + 30 * hour), due("done", now + 30 * hour, SubStatus.SUBMITTED), due("soon", now + 5 * hour),
            due("passed", now - hour), due("nodate", null), due("draft", now + 100 * hour, SubStatus.DRAFT),
        )
        val p = LmsReminders.plan(all, now)
        fun of(n: String) = p.filter { it.cmid == n.hashCode() }.map { it.hours to (it.at - now) / hour }
        assertEquals(listOf(24 to 6L, 3 to 27L), of("open"))
        assertEquals(listOf(3 to 2L), of("soon")) // the 24-hour one is already in the past
        assertTrue(of("done").isEmpty()); assertTrue(of("passed").isEmpty()); assertTrue(of("nodate").isEmpty())
        assertEquals(listOf(24 to 76L, 3 to 97L), of("draft")) // a draft is not handed in: still reminded
    }

    @Test
    fun theReminderTextSaysTodayTomorrowOrTheDay() {
        val now = java.time.ZonedDateTime.of(2026, 10, 13, 14, 0, 0, 0, ist).toInstant().toEpochMilli()
        fun due(d: Int, h: Int) = java.time.ZonedDateTime.of(2026, 10, d, h, 0, 0, 0, ist).toInstant().toEpochMilli()
        assertTrue(LmsReminders.whenText(due(14, 14), now, ist).startsWith("tomorrow at"))
        assertTrue(LmsReminders.whenText(due(13, 17), now, ist).startsWith("today at"))
        assertTrue(LmsReminders.whenText(due(16, 9), now, ist).startsWith("on "))
    }

    // ---------------------------------------------------------------- file names from the LMS
    @Test
    fun fileNamesCanOnlyEverBePlainFileNames() {
        assertEquals("passwd", LmsFiles.safeName("../../etc/passwd", "x"))
        assertEquals("a_b.pdf", LmsFiles.safeName("a:b.pdf", "x"))
        assertEquals("x", LmsFiles.safeName("...", "x"))
        assertEquals("x", LmsFiles.safeName(null, "x"))
        assertEquals("Lecture 1.pdf", LmsFiles.nameFromDisposition("attachment; filename=\"Lecture 1.pdf\""))
        assertEquals("Lecture 1.pdf", LmsFiles.nameFromDisposition("attachment; filename*=UTF-8''Lecture%201.pdf"))
        assertNull(LmsFiles.nameFromDisposition(null))
    }

    // ---------------------------------------------------------------- wording and the injected stylesheet
    @Test
    fun countdownWords() {
        val h = 3_600_000L; val d = 24 * h
        assertEquals("5d 14h", LmsText.countdown(5 * d + 14 * h + 59 * 60_000))
        assertEquals("1d 6h", LmsText.countdown(30 * h))
        assertEquals("5h 12m", LmsText.countdown(5 * h + 12 * 60_000))
        assertEquals("42m", LmsText.countdown(42 * 60_000))
        assertEquals("now", LmsText.countdown(10_000))
        assertEquals("2h late", LmsText.countdown(-2 * h - 5))
        assertEquals("2d late", LmsText.countdown(-2 * d - 3 * h))
        assertEquals("1m late", LmsText.countdown(-1000))
    }

    @Test
    fun theInjectedStyleSheetIsStylingOnlyAndCannotBreakOut() {
        for (dark in listOf(false, true)) {
            val css = LmsStyle.css(dark)
            assertFalse(css.contains("</style", ignoreCase = true))
            assertFalse(css.contains("<script", ignoreCase = true))
            assertFalse(css.contains("javascript:", ignoreCase = true))
            assertFalse(css.contains("@import"))
            assertFalse(css.contains("url(")) // nothing is ever fetched by it
            val js = LmsStyle.inject(dark)
            assertTrue(js.startsWith("(function(){try{")) // any problem is swallowed: the page is left as it was
            assertTrue(js.endsWith("}catch(e){}})();"))
            assertFalse(js.contains(".click(")); assertFalse(js.contains(".submit(")); assertFalse(js.contains(".value")) // never presses or fills anything
        }
    }
}
