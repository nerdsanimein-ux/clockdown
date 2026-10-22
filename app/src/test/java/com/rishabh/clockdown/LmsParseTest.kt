package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** All HTML here is made up, in the SHAPE of the real pages (Amizone writes 'single' quotes, Moodle "double"). No real names or keys. */
class LmsParseTest {
    private fun form(id: String, semFolder: String, label: String) = """
        <form id='$id' action='https://alms06.amizone.net/$semFolder/submit.php' method='post' target="_blank">
          <input type='hidden' name='usercode' value='student@example.test'>
          <input type='hidden' name='token' value='tok&amp;en123'>
          <input type='hidden' name='userId' value='4242'>
          <div class="text-center"><p><a href="javascript:void(0);" onclick="document.getElementById('$id').submit();" class="btn"><i class="ace-icon fa fa-clipboard"></i>$label</a></p></div>
        </form>"""

    private val home = "<div>" + form("a1", "moodle_25even", "2025-2026/Even") + form("a2", "moodle_26odd", "2026-2027/Odd") + "</div>"

    @Test
    fun picksTheMostRecentSemesterWhateverTheOrderOnThePage() {
        val forms = LmsParse.ssoForms(home)
        assertEquals(2, forms.size)
        assertEquals("2026-2027/Odd", LmsParse.currentSemester(forms)!!.label)
        assertEquals("2026-2027/Odd", LmsParse.currentSemester(forms.reversed())!!.label)
    }

    @Test
    fun withinAYearOddComesBeforeEvenSoEvenIsNewer() {
        val forms = LmsParse.ssoForms(form("a", "m1", "2026-2027/Odd") + form("b", "m2", "2026-2027/Even"))
        assertEquals("2026-2027/Even", LmsParse.currentSemester(forms)!!.label)
    }

    @Test
    fun readsTheHiddenFieldsAndDecodesThem() {
        val f = LmsParse.ssoForms(home).first { it.label.endsWith("Odd") }
        assertEquals(listOf("usercode", "token", "userId"), f.fields.map { it.first })
        assertEquals("tok&en123", f.fields.first { it.first == "token" }.second)
    }

    @Test
    fun ignoresFormsThatAreNotLmsButtons() {
        assertTrue(LmsParse.ssoForms("<form action='/Search' method='get'><input type='hidden' name='q' value='x'></form>").isEmpty())
        assertTrue(LmsParse.ssoForms("<p>no forms</p>").isEmpty())
    }

    @Test
    fun theFolderComesFromTheFormButOnlyOnAmizoneNet() {
        assertEquals("https://alms06.amizone.net/moodle_26odd", LmsParse.ssoBase("https://alms06.amizone.net/moodle_26odd/submit.php"))
        assertEquals("https://alms07.amizone.net/moodle_27even", LmsParse.ssoBase("https://alms07.amizone.net/moodle_27even/submit.php"))
        assertNull(LmsParse.ssoBase("https://evil.example/moodle_26odd/submit.php"))
        assertNull(LmsParse.ssoBase("https://alms06.amizone.net.evil.example/moodle_26odd/submit.php"))
        assertNull(LmsParse.ssoBase("http://alms06.amizone.net/moodle_26odd/submit.php")) // plain http
        assertNull(LmsParse.ssoBase("https://alms06.amizone.net:8443/moodle_26odd/submit.php"))
        assertNull(LmsParse.ssoBase("https://alms06.amizone.net/a/b/submit.php")) // one folder only
        assertNull(LmsParse.ssoBase("https://alms06.amizone.net/moodle_26odd/other.php"))
        assertNull(LmsParse.ssoBase("https://alms06.amizone.net/moodle_26odd/submit.php?next=//evil.example"))
    }

    // ---------------------------------------------------------------- statuses

    @Test
    fun aDraftIsNeverReadAsSubmitted() {
        assertEquals(SubStatus.DRAFT, LmsParse.statusOf("Draft (not submitted)"))
        assertEquals(SubStatus.SUBMITTED, LmsParse.statusOf("Submitted for grading"))
        assertEquals(SubStatus.SUBMITTED, LmsParse.statusOf("Submitted for grading - late"))
        assertEquals(SubStatus.NONE, LmsParse.statusOf("No submissions have been made yet"))
        assertEquals(SubStatus.NONE, LmsParse.statusOf("No attempt"))
        assertEquals(SubStatus.NONE, LmsParse.statusOf("Not submitted"))
        assertEquals(SubStatus.OTHER, LmsParse.statusOf("Reopened"))
        assertEquals(SubStatus.OTHER, LmsParse.statusOf("Something nobody has seen before"))
        assertNull(LmsParse.statusOf(""))
        assertNull(LmsParse.statusOf(null))
    }

    @Test
    fun onlyMoodlesOwnSubmittedClassCanConfirmASubmissionBesidesItsWords() {
        assertEquals(SubStatus.SUBMITTED, LmsParse.statusOf("Handed in", "cell c1 submissionstatussubmitted"))
        assertEquals(SubStatus.OTHER, LmsParse.statusOf("Handed in", "cell c1"))
        assertEquals(SubStatus.DRAFT, LmsParse.statusOf("Draft (not submitted)", "submissionstatussubmitted")) // the words still win
    }

    // ---------------------------------------------------------------- dates

    @Test
    fun readsMoodlesDateAsIndianTime() {
        val ms = LmsParse.dateMs("Wednesday, 14 October 2026, 2:00 PM")!!
        assertEquals(java.time.ZonedDateTime.of(2026, 10, 14, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli(), ms)
        assertEquals(ms, LmsParse.dateMs("Wednesday, 14 October 2026, 2:00 PM"))
        assertEquals(ms, LmsParse.dateMs("wednesday, 14 october 2026, 2:00 pm"))
        assertNull(LmsParse.dateMs("tomorrow-ish"))
        assertNull(LmsParse.dateMs(null))
    }

    // ---------------------------------------------------------------- a course page

    private val coursePage = """
      <ul class="topics">
      <li id="section-0" class="section course-section main clearfix" data-id="72523" data-for="section" data-number="0" data-sectionid="0">
        <div class="section-item"><div class="course-section-header d-flex" data-id="72523" data-for="section_title" data-number="0">
          <div class="bulkselect d-none" data-for="sectionBulkSelect"><label class="sr-only">Select section General</label></div><div class="d-flex"><a role="button" data-for="sectiontoggler"><span class="sr-only">Collapse</span><span class="sr-only">Expand</span></a><h3 class="h4 sectionname d-flex" id="sectionid-1-title" data-for="section_title"><a href="#">General</a></h3></div><a id="collapsesections"><span class="collapseall">Collapse all</span></a></div>
        <div class="content"><div class="my-3" data-for="sectioninfo"></div><ul class="section" data-for="cmlist">
          <li class="activity activity-wrapper forum modtype_forum" id="module-1" data-id="1" data-for="cmitem">
            <div class="activity-item" data-activityname="Announcements"><div class="activityname"><a class="aalink" href="https://h/moodle_26odd/mod/forum/view.php?id=1"><span class="instancename">Announcements <span class="accesshide "> Forum</span></span></a></div></div>
          </li></ul></div></div></li>
      <li id="section-1" class="section course-section main" data-id="72524" data-for="section" data-number="1" data-sectionid="1">
        <div class="section-item"><div class="course-section-header" data-for="section_title" data-number="1"><h3 class="sectionname"><a href="#">Unit 1 &amp; Basics</a></h3></div>
        <div class="content"><ul data-for="cmlist">
          <li class="activity activity-wrapper resource modtype_resource hasinfo" id="module-51464" data-id="51464" data-for="cmitem">
            <div class="activity-item"><div class="activityname"><a class="aalink stretched-link" href="https://h/moodle_26odd/mod/resource/view.php?id=51464"><span class="instancename">Syllabus <span class="accesshide "> File</span></span></a><span class="ms-1"><span class="activitybadge badge rounded-pill badge-none">PDF</span></span></div></div>
          </li>
          <li class="activity activity-wrapper assign modtype_assign hasinfo" id="module-51465" data-id="51465" data-for="cmitem">
            <div class="activity-item"><div class="activityname"><a class="aalink stretched-link" href="https://h/moodle_26odd/mod/assign/view.php?id=51465"><span class="instancename">Assignment 1 <span class="accesshide "> Assignment</span></span></a></div>
              <div class="activity-dates me-sm-2" data-region="activity-dates"><div><strong>Opened:</strong> Wednesday, 7 October 2026, 2:00 PM</div><div><strong>Due:</strong> Wednesday, 14 October 2026, 2:00 PM</div></div></div>
          </li>
          <li class="activity modtype_label" id="module-9" data-id="9" data-for="cmitem"><div class="no-overflow"><p>Just a text label</p></div></li>
          <li class="activity activity-wrapper quiz modtype_quiz" id="module-51466" data-id="51466" data-for="cmitem">
            <div class="activity-item"><div class="activityname"><a class="aalink" href="https://h/moodle_26odd/mod/quiz/view.php?id=51466"><span class="instancename">Quiz 1 <span class="accesshide "> Quiz</span></span></a></div></div>
          </li>
        </ul></div></div></li>
      </ul>"""

    @Test
    fun readsSectionsInOrderWithTheirFilesAssignmentsAndQuizzes() {
        val p = LmsParse.coursePage(coursePage)
        assertEquals(listOf(0, 1), p.sections.map { it.number })
        assertEquals(listOf("General", "Unit 1 & Basics"), p.sections.map { it.title })
        val unit = p.sections[1]
        assertEquals(listOf("resource", "assign", "quiz"), unit.items.map { it.type }) // the label has no link, so it is left out
        assertEquals("Syllabus", unit.items[0].name) // the screen-reader word is gone
        assertEquals("PDF", unit.items[0].badge)
        assertEquals("mod/assign/view.php?id=51465", unit.items[1].path)
        assertEquals("Announcements", p.sections[0].items[0].name)
    }

    @Test
    fun takesAssignmentDatesFromTheCoursePage() {
        val d = LmsParse.coursePage(coursePage).assignDates
        assertEquals(setOf(51465), d.keys)
        assertEquals("Wednesday, 7 October 2026, 2:00 PM", d[51465]!!.first)
        assertEquals("Wednesday, 14 October 2026, 2:00 PM", d[51465]!!.second)
    }

    @Test
    fun aPageWithoutSectionsIsAFormatChangeNotAnEmptyCourse() {
        assertEquals("course-format", assertThrows(LmsFormatException::class.java) { LmsParse.coursePage("<html><body>Something else</body></html>") }.kind)
    }

    // ---------------------------------------------------------------- an assignment page

    private fun assignPage(status: String, statusCls: String = "cell c1 lastcol", grading: String = "Not graded", extra: String = "") = """
      <div id="intro" class="activity-description"><div class="no-overflow"><p>Write 500 words.</p><p>Second &amp; last line.</p></div></div>
      <div data-region="activity-dates" class="activity-dates"><div><strong>Opened:</strong> Wednesday, 7 October 2026, 2:00 PM</div><div><strong>Due:</strong> Wednesday, 14 October 2026, 2:00 PM</div></div>
      <div class="submissionstatustable"><h3>Submission status</h3><div class="box"><div class="table-responsive"><table class="generaltable table-bordered"><tbody>
        <tr><th class="cell c0">Submission status</th><td class="$statusCls">$status</td></tr>
        <tr><th class="cell c0">Grading status</th><td class="submissionnotgraded cell c1 lastcol">$grading</td></tr>
        <tr><th class="cell c0">Time remaining</th><td class="timeremaining cell c1 lastcol">5 days 14 hours remaining</td></tr>
        <tr><th class="cell c0">Last modified</th><td class="cell c1 lastcol">-</td></tr>
        <tr class="lastrow"><th class="cell c0">Submission comments</th><td class="cell c1 lastcol"><div class="commentscontainer"><a href="#">Show comments (3)</a></div></td></tr>
      </tbody></table></div></div></div>$extra"""

    @Test
    fun readsWhatMoodleSaysAboutAnUnsubmittedAssignment() {
        val v = LmsParse.assignView(assignPage("No submissions have been made yet"))
        assertEquals("No submissions have been made yet", v.statusText)
        assertEquals(SubStatus.NONE, v.status)
        assertEquals("Not graded", v.gradingText)
        assertEquals("5 days 14 hours remaining", v.remainingText)
        assertNull(v.modifiedText) // Moodle's "-" means never
        assertEquals(3, v.comments)
        assertEquals("Write 500 words.\nSecond & last line.", v.intro)
        assertEquals("Wednesday, 14 October 2026, 2:00 PM", v.dueText)
        assertTrue(v.feedback.isEmpty())
    }

    @Test
    fun aDraftPageIsADraftAndASubmittedPageIsSubmitted() {
        assertEquals(SubStatus.DRAFT, LmsParse.assignView(assignPage("Draft (not submitted)")).status)
        assertEquals(SubStatus.SUBMITTED, LmsParse.assignView(assignPage("Submitted for grading", "submissionstatussubmitted cell c1 lastcol")).status)
    }

    @Test
    fun readsFeedbackWhenThereIsSome() {
        val fb = """<div class="feedback"><h3>Feedback</h3><table class="generaltable"><tbody>
            <tr><th>Grade</th><td>18.00 / 20.00</td></tr><tr><th>Feedback comments</th><td><p>Well done.</p></td></tr></tbody></table></div>"""
        val v = LmsParse.assignView(assignPage("Submitted for grading", "submissionstatussubmitted", "Graded", fb))
        assertEquals(listOf("Grade" to "18.00 / 20.00", "Feedback comments" to "Well done."), v.feedback)
    }

    @Test
    fun anAssignmentPageWithoutTheStatusTableIsAFormatChange() {
        assertEquals("assign-format", assertThrows(LmsFormatException::class.java) { LmsParse.assignView("<div>nothing here</div>") }.kind)
    }

    // ---------------------------------------------------------------- signed-in checks and the courses answer

    @Test
    fun tellsAGuestFromAStudent() {
        assertEquals(4242, LmsParse.userId("""M.cfg = {"wwwroot":"https://h","userId":4242,"sesskey":"AbC123"}"""))
        assertEquals(0, LmsParse.userId("""M.cfg = {"userId":0}"""))
        assertNull(LmsParse.userId("<html>no config</html>"))
        assertEquals("AbC123", LmsParse.sesskey("""M.cfg = {"sesskey":"AbC123"}"""))
        assertTrue(LmsParse.isLoginPage("<input type=\"hidden\" name=\"logintoken\" value=\"x\">"))
    }

    @Test
    fun readsTheCoursesAnswerAndRecognisesErrors() {
        val ok = """[{"error":false,"data":{"courses":[{"id":13927,"fullname":"Data &amp; Systems","shortname":"DS101","summary":"x"}],"nextoffset":1}}]"""
        assertEquals(listOf(Triple(13927, "Data & Systems", "DS101")), LmsParse.courses(ok))
        assertThrows(LmsFormatException::class.java) { LmsParse.courses("""[{"error":true,"exception":{"errorcode":"x"}}]""") }
        assertThrows(LmsFormatException::class.java) { LmsParse.courses("not json") }
        assertTrue(LmsParse.ajaxSaysSignedOut("""[{"error":true,"exception":{"errorcode":"servicerequireslogin"}}]"""))
        assertTrue(LmsParse.ajaxSaysSignedOut("""[{"error":true,"exception":{"errorcode":"invalidsesskey"}}]"""))
        assertFalse(LmsParse.ajaxSaysSignedOut(ok))
        assertFalse(LmsParse.ajaxSaysSignedOut("garbage"))
    }

    @Test
    fun theSavedSnapshotRoundTrips() {
        val a = LmsAssign(5, 9, "A1", "o", "d", 1L, 2L, "Draft (not submitted)", SubStatus.DRAFT, "Not graded", "5 days", null, 2, "intro\nline", listOf("Grade" to "9/10"), 77L)
        val s = LmsSnapshot("https://h/m", "2026-2027/Odd", 99L, true, listOf(LmsCourse(9, "Course", "C", listOf(LmsSection(0, "General", listOf(LmsItem(5, "assign", "A1", null)))), 3L)), listOf(a))
        assertEquals(s, LmsStore.fromJson(LmsStore.toJson(s)))
        assertNotNull(LmsStore.fromJson(LmsStore.toJson(s.copy(assigns = listOf(a.copy(status = null, statusText = null, dueMs = null, comments = null))))))
    }

    // ---- Moodle's Timeline and notifications (made-up values in the shape of the real AJAX answers)

    private fun event(type: String, cmid: Int, at: Long, event: String = "close") =
        """{"name":"x","activityname":"Quiz &amp; more","modulename":"$type","eventtype":"$event","timesort":$at,"course":{"id":7},
           "url":"https://h/m/mod/$type/view.php?id=$cmid","action":{"name":"Attempt quiz now","actionable":true}}"""

    @Test
    fun timelineGivesEveryActivityWithADeadlineOncePerActivity() {
        val json = """[{"error":false,"data":{"events":[${event("quiz", 5, 100, "open")},${event("quiz", 5, 200)},${event("assign", 6, 300, "due")}]}}]"""
        val t = LmsParse.timeline(json)
        assertEquals(listOf(5, 6), t.map { it.cmid })
        assertEquals(200_000L, t.first { it.cmid == 5 }.dueMs) // the quiz's close, not its open
        assertEquals("Quiz & more", t.first().name)
        assertEquals("Attempt quiz now", t.first().action)
        assertEquals(7, t.first().courseId)
        assertThrows(LmsFormatException::class.java) { LmsParse.timeline("""[{"error":true,"exception":{}}]""") }
    }

    private fun note(id: Long, at: Long, url: String, read: Boolean = false, component: String = "mod_forum", type: String = "posts") =
        """{"id":$id,"timecreated":$at,"component":"$component","eventtype":"$type","subject":"S$id","contexturlname":"Topic","smallmessage":"T posted in C: Announcements: Topic","contexturl":"$url","read":$read,"deleted":false}"""

    @Test
    fun notificationsDropSignInNoticesAndFoldRepeatsOfTheSamePost() {
        val json = """[{"error":false,"data":{"notifications":[
            ${note(1, 10, "https://h/m/mod/forum/discuss.php?d=1#p1", read = true)},
            ${note(2, 30, "https://h/m/mod/forum/discuss.php?d=1#p1")},
            ${note(3, 20, "", component = "moodle", type = "newlogin")},
            ${note(4, 25, "https://h/m/mod/forum/discuss.php?d=2#p2", read = true)}]}}]"""
        val u = LmsParse.notifications(json)
        assertEquals(listOf(2L, 4L), u.map { it.id }) // newest first, sign-in notice gone, the repeat folded
        assertFalse(u[0].read) // one unread copy keeps it unread
        assertTrue(u[1].read)
        assertEquals(30_000L, u[0].at)
        assertEquals("Topic", u[0].title)
        // A submission receipt reads as Moodle's sentence, not as the bare assignment name.
        val receipt = LmsParse.notifications("""[{"error":false,"data":{"notifications":[${note(9, 5, "https://h/m/mod/assign/view.php?id=1", component = "mod_assign", type = "assign_notification")}]}}]""").single()
        assertEquals("S9", receipt.title)
    }

    @Test
    fun aQuizFromTheTimelineIsPendingAndOpensItsOwnPage() {
        val e = LmsParse.timeline("""[{"error":false,"data":{"events":[${event("quiz", 5, 1_792_666_200)}]}}]""").single()
        val q = LmsSync.fromTimeline(e, 1L)
        assertTrue(q.pending)
        assertFalse(q.isAssign)
        assertEquals("mod/quiz/view.php?id=5", q.path)
        assertEquals("Thursday, 22 October 2026, 4:20 PM", q.dueText)
        assertEquals(q.dueMs, LmsParse.dateMs(q.dueText)) // written the way Moodle writes dates, and reads back the same
    }

    @Test
    fun storeKeepsTypesAndUpdatesAndReadsOldSnapshotsWithoutThem() {
        val q = LmsAssign(5, 9, "Quiz", null, "d", null, 1L, "Attempt quiz now", SubStatus.NONE, null, null, null, null, "", emptyList(), 2L, "quiz")
        val s = LmsSnapshot("https://h/m", "sem", 1L, false, emptyList(), listOf(q), listOf(LmsUpdate(1, 2, "mod_forum", "T", "x", null, false)))
        assertEquals(s, LmsStore.fromJson(LmsStore.toJson(s)))
        val old = LmsStore.toJson(s).apply { remove("updates"); getJSONArray("assigns").getJSONObject(0).remove("type") }
        val back = LmsStore.fromJson(old)
        assertEquals("assign", back.assigns.single().type) // snapshots saved by 1.6 had only assignments
        assertTrue(back.updates.isEmpty())
    }

    @Test
    fun wordAndPowerPointFilesPointToTheFreeAppThatOpensThem() {
        assertEquals("Google Docs", viewerFor("DOC")!!.first)
        assertEquals("Google Slides", viewerFor("pptx")!!.first)
        assertEquals("Google Sheets", viewerFor("xlsx")!!.first)
        assertNull(viewerFor("zip"))
    }
}
