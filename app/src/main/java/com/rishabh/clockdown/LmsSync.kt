package com.rishabh.clockdown

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException

/** What is on screen is always the last data read cleanly; this says why the latest read didn't add to it (a label, never content). */
val Context.lmsFailure: String? get() = LmsStore.failure(this)

/** Whether a stored assignment page is old enough to be worth asking Moodle about again. Pure, so the "only when needed" rule is testable. */
internal fun assignNeedsRead(a: LmsAssign?, now: Long): Boolean = when {
    a == null || a.fetchedAt == 0L -> true
    a.pending -> now - a.fetchedAt > PENDING_REREAD_MS            // not handed in: its status can change, look every couple of hours
    else -> now - a.fetchedAt > SUBMITTED_REREAD_MS               // handed in: only a grade or feedback can still appear
}
private const val PENDING_REREAD_MS = 2 * 3_600_000L
private const val SUBMITTED_REREAD_MS = 24 * 3_600_000L

/**
 * Reads the LMS: the course list, each course's page (sections, files, dates), and an assignment's own page only when
 * something about it may have changed. Gentle on purpose (a short pause between requests) and never overlapping another
 * sync (it holds the same locks as the timetable and attendance syncs). All-or-nothing PER PAGE: a page that can't be read
 * leaves what was last read for it exactly as it was, and the whole is marked "partial".
 */
object LmsSync {
    private const val PAUSE_MS = 400L

    /** Blocking; off the main thread. [minGapMs] > 0 skips the run if one was tried that recently (background wakeups). */
    fun run(ctx: Context, force: Boolean = false, minGapMs: Long = 0): LmsResult =
        synchronized(AmizoneSync) { synchronized(AttendanceSync) { runLocked(ctx, force, minGapMs) } }

    private fun runLocked(ctx: Context, force: Boolean, minGapMs: Long): LmsResult {
        if (!ctx.amizoneConnected) return LmsResult.NO_SESSION
        // An expired Amizone sign-in is NOT a reason to stop: the LMS has its own session, which often outlives Amizone's.
        // Only a fresh LMS sign-on needs Amizone, and LmsSso refuses that without a network call while Amizone is known dead.
        val now = System.currentTimeMillis()
        if (minGapMs > 0 && now - LmsStore.triedAt(ctx) < minGapMs) return LmsResult.OK
        LmsStore.setTriedAt(ctx, now)
        return try { read(ctx, LmsStore.load(ctx), now) }
        catch (e: LmsStop) { stopped(ctx, e) }
        catch (e: IOException) { stopped(ctx, LmsStop(LmsResult.FAILED, "network")) }
    }

    private fun read(ctx: Context, old: LmsSnapshot?, now: Long): LmsResult {
        val client = LmsClient(ctx)
        val list = try {
            LmsParse.courses(client.ajax("core_course_get_enrolled_courses_by_timeline_classification", """{"classification":"all","limit":0,"offset":0,"sort":"fullname"}"""))
        } catch (e: LmsFormatException) { throw LmsStop(LmsResult.UNREADABLE, e.kind) }

        var partial = false
        var failedKind = ""
        // Moodle's own Timeline: everything with a deadline that is still to do, in every course. Assignments are also found on
        // the course pages; quizzes and the rest only here. Null = it couldn't be read (what was known before is kept).
        val timeline = try {
            Thread.sleep(PAUSE_MS)
            LmsParse.timeline(client.ajax("core_calendar_get_action_events_by_timesort", """{"limitnum":50,"timesortfrom":${now / 1000 - TIMELINE_BACK_S},"limittononsuspendedevents":true}"""))
        } catch (e: LmsFormatException) { partial = true; failedKind = e.kind; null }
        val updates = try {
            Thread.sleep(PAUSE_MS)
            LmsParse.notifications(client.ajax("message_popup_get_popup_notifications", """{"useridto":0,"newestfirst":true,"limit":$MAX_UPDATES,"offset":0}"""))
        } catch (e: LmsFormatException) { partial = true; failedKind = e.kind; old?.updates.orEmpty() }
        val assigns = mutableListOf<LmsAssign>()
        val courses = list.mapIndexed { i, (id, name, short) ->
            if (i > 0) Thread.sleep(PAUSE_MS)
            val prev = old?.course(id)
            val keep = prev?.copy(name = name, shortName = short) ?: LmsCourse(id, name, short, emptyList(), 0)
            val page = try { LmsParse.coursePage(client.page("course/view.php?id=$id")) }
            catch (e: LmsFormatException) { partial = true; failedKind = e.kind; assigns += old?.assigns.orEmpty().filter { it.courseId == id }; return@mapIndexed keep }
            for (item in page.sections.flatMap { it.items }.filter { it.type == "assign" }) {
                val before = old?.assign(item.cmid)
                val (openT, dueT) = page.assignDates[item.cmid] ?: (null to null)
                var a = (before ?: LmsAssign(item.cmid, id, item.name, null, null, null, null, null, null, null, null, null, null, "", emptyList(), 0))
                    .copy(courseId = id, name = item.name, openText = openT ?: before?.openText, dueText = dueT ?: before?.dueText)
                    .let { it.copy(openMs = LmsParse.dateMs(it.openText), dueMs = LmsParse.dateMs(it.dueText)) }
                if (assignNeedsRead(before, now)) {
                    Thread.sleep(PAUSE_MS)
                    try { a = withView(a, LmsParse.assignView(client.page(a.path)), now) } catch (e: LmsFormatException) { partial = true; failedKind = e.kind }
                }
                assigns += a
            }
            LmsCourse(id, name, short, page.sections, now)
        }
        // What only the Timeline knows: an assignment not on its course page, a missing due date, and everything that isn't an assignment.
        val known = assigns.associateBy { it.cmid }
        for (e in timeline.orEmpty()) {
            val a = known[e.cmid]
            if (a != null) { if (a.dueMs == null) assigns[assigns.indexOf(a)] = a.copy(dueText = LmsParse.moodleText(e.dueMs), dueMs = e.dueMs); continue }
            if (e.type == "assign") {
                var n = LmsAssign(e.cmid, e.courseId, e.name, null, LmsParse.moodleText(e.dueMs), null, e.dueMs, null, null, null, null, null, null, "", emptyList(), 0)
                Thread.sleep(PAUSE_MS)
                try { n = withView(n, LmsParse.assignView(client.page(n.path)), now) } catch (ex: LmsFormatException) { partial = true; failedKind = ex.kind }
                assigns += n
            } else assigns += fromTimeline(e, now)
        }
        // Timeline unreadable this time: keep the non-assignments it gave last time rather than dropping them.
        if (timeline == null) assigns += old?.assigns.orEmpty().filter { !it.isAssign && it.cmid !in known }
        val snap = LmsSnapshot(
            client.root, LmsSession.label(ctx), if (partial) old?.updatedAt ?: now else now, partial, courses,
            assigns.distinctBy { it.cmid }.sortedWith(compareBy({ it.dueMs ?: Long.MAX_VALUE }, { it.name })), updates.take(MAX_UPDATES),
        )
        LmsStore.save(ctx, snap)
        LmsStore.setFailure(ctx, if (partial) "format" else null)
        if (partial) report(ctx, failedKind)
        changed(ctx)
        return if (partial) LmsResult.UNREADABLE else LmsResult.OK
    }

    /** A quiz (or any other activity) with a deadline, as the Timeline gives it. Its status is Moodle's own action words ("Attempt quiz now"). */
    internal fun fromTimeline(e: TimelineEvent, now: Long) = LmsAssign(
        e.cmid, e.courseId, e.name, null, LmsParse.moodleText(e.dueMs), null, e.dueMs, e.action, SubStatus.NONE, null, null, null, null, "", emptyList(), now, e.type,
    )

    private const val TIMELINE_BACK_S = 14 * 86_400L // a deadline missed in the last two weeks still shows as overdue
    private const val MAX_UPDATES = 40

    /** An assignment with what its own page says added (and its date, if the page has it and the course page didn't). */
    internal fun withView(a: LmsAssign, v: AssignView, now: Long): LmsAssign {
        val open = a.openText ?: v.openText
        val due = a.dueText ?: v.dueText
        return a.copy(
            openText = open, dueText = due, openMs = LmsParse.dateMs(open), dueMs = LmsParse.dateMs(due),
            statusText = v.statusText, status = v.status, gradingText = v.gradingText, remainingText = v.remainingText,
            modifiedText = v.modifiedText, comments = v.comments, intro = v.intro, feedback = v.feedback, fetchedAt = now,
        )
    }

    /** Re-reads ONE assignment's page (after an upload, or when its screen opens). Never touches the rest. */
    fun refreshAssign(ctx: Context, cmid: Int): LmsResult = synchronized(AmizoneSync) {
        synchronized(AttendanceSync) {
            try {
                val old = LmsStore.load(ctx) ?: return LmsResult.FAILED
                val a = old.assign(cmid)?.takeIf { it.isAssign } ?: return LmsResult.FAILED
                val v = try { LmsParse.assignView(LmsClient(ctx).page(a.path)) } catch (e: LmsFormatException) { throw LmsStop(LmsResult.UNREADABLE, e.kind) }
                val now = System.currentTimeMillis()
                LmsStore.save(ctx, old.copy(assigns = old.assigns.map { if (it.cmid == cmid) withView(it, v, now) else it }))
                changed(ctx)
                LmsResult.OK
            } catch (e: LmsStop) { stopped(ctx, e) }
            catch (e: IOException) { stopped(ctx, LmsStop(LmsResult.FAILED, "network")) }
        }
    }

    /** New data is saved: reminders follow it, and the widgets are drawn again. */
    private fun changed(ctx: Context) {
        LmsReminders.reschedule(ctx)
        Scheduler.refreshWidget(ctx)
    }

    private fun stopped(ctx: Context, e: LmsStop): LmsResult {
        LmsStore.setFailure(ctx, when (e.result) { LmsResult.UNREADABLE -> "format"; LmsResult.EXPIRED, LmsResult.NO_SESSION -> "signin"; LmsResult.NO_LMS -> "nolms"; else -> "network" })
        if (e.result == LmsResult.UNREADABLE || (e.result == LmsResult.FAILED && e.kind != "network")) report(ctx, e.kind)
        Scheduler.refreshWidget(ctx)
        return e.result
    }

    /** Tells the developer the failure TYPE only (at most once every 6 hours per type): never any course, name or page content. */
    private fun report(ctx: Context, kind: String) {
        val now = System.currentTimeMillis()
        if (now - LmsStore.reportedAt(ctx, kind) < 6 * 3_600_000L) return
        LmsStore.setReportedAt(ctx, kind, now)
        CrashReporting.noteLmsFailure(kind)
    }

    fun updatedText(ms: Long) = AttendanceSync.updatedText(ms)
}

/** Runs one LMS read as a one-off job: it waits for a connection and doesn't tie up the caller. */
class LmsWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result { LmsSync.run(applicationContext, minGapMs = inputData.getLong("gap", 0)); return Result.success() }
}

object LmsWakeups {
    /** Rides on the timetable's 6-hourly check (see SyncWorker): the LMS is read at most every 3 hours. */
    fun periodic(ctx: Context) {
        if (!ctx.amizoneConnected) return
        LmsSync.run(ctx, minGapMs = 3 * 3_600_000L)
    }
}

/** Downloaded course files live in the app's own folder, one folder per file's id, wiped on sign-out. */
object LmsFiles {
    fun dir(ctx: Context, cmid: Int) = File(File(ctx.filesDir, LmsStore.LMS_FILES), cmid.toString())

    fun cached(ctx: Context, cmid: Int): File? = dir(ctx, cmid).listFiles()?.firstOrNull { it.isFile && !it.name.endsWith(".part") }

    /** Cleans a name the LMS gave for a file so it can only ever be a plain file name. */
    internal fun safeName(raw: String?, fallback: String): String {
        val n = (raw ?: "").substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001F<>:\"|?*]"), "_").trim().trim('.')
        return n.take(120).ifBlank { fallback }
    }

    /** The file name in a Content-Disposition header ("attachment; filename*=UTF-8''a%20b.pdf" or filename="a b.pdf"). */
    internal fun nameFromDisposition(h: String?): String? {
        if (h == null) return null
        Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(h)?.let { return runCatching { java.net.URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrNull() }
        return Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)?.trim()
    }

    /**
     * Blocking. Downloads one file a Moodle page links to (an attachment), with that page's own cookies, into the app's attachments
     * folder (wiped on sign-out with the rest). Follows redirects only on the same https host. Null if it can't be fetched.
     */
    fun fetchUrl(ctx: Context, url: String, cookie: String, ua: String, name: String): File? {
        val host = java.net.URI(url).host
        var at = url
        repeat(6) {
            val req = okhttp3.Request.Builder().url(at).header("Cookie", cookie).header("User-Agent", ua).build()
            val next: String = LmsHttp.client.newCall(req).execute().use { r ->
                when {
                    r.code in 300..399 -> java.net.URI(at).resolve(r.header("Location") ?: return null).also {
                        if (it.scheme != "https" || !it.host.equals(host, true)) return null
                    }.toString()
                    r.code in 200..299 && !r.header("Content-Type").orEmpty().startsWith("text/html", true) -> {
                        val dir = File(File(ctx.filesDir, LmsStore.LMS_FILES), "attachments").apply { mkdirs() }
                        val part = File(dir, "$name.part")
                        r.body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                        val done = File(dir, name).apply { delete() }
                        return if (part.renameTo(done)) done else null
                    }
                    else -> return null // an error, or a sign-in page instead of the file
                }
            }
            at = next
        }
        return null
    }

    /**
     * Blocking. Downloads a course file with the LMS session and returns it, or null when the page is something only the
     * in-app Moodle page can show (then the caller opens it there). Follows redirects by hand, same https host only.
     */
    fun fetch(ctx: Context, item: LmsItem): File? {
        cached(ctx, item.cmid)?.let { return it }
        val client = LmsClient(ctx)
        var (base, cookie, ua) = client.credentials()
        val host = java.net.URI(base).host
        var url = "$base/${item.path}"
        var reSigned = false
        repeat(6) {
            val req = okhttp3.Request.Builder().url(url).header("Cookie", cookie).header("User-Agent", ua).build()
            val next: String? = LmsHttp.client.newCall(req).execute().use { r ->
                when {
                    r.code in 300..399 -> {
                        val loc = r.header("Location").orEmpty()
                        if ("/login/" in loc) {
                            if (reSigned) throw LmsStop(LmsResult.FAILED, "session")
                            reSigned = true; val c = client.refreshSession(); base = c.first; cookie = c.second; ua = c.third; url = "$base/${item.path}"
                            return@use ""
                        }
                        val u = java.net.URI(url).resolve(loc)
                        if (u.scheme != "https" || !u.host.equals(host, true)) throw LmsStop(LmsResult.FAILED, "redirect-away")
                        u.toString()
                    }
                    r.code in 200..299 -> {
                        val type = r.header("Content-Type").orEmpty()
                        if (type.startsWith("text/html", true)) {
                            // The file shown inside a Moodle page: find its real address; with none, only the Moodle page can show it.
                            val html = r.body.string()
                            val link = Regex("(?:href|data|src)=\"([^\"]*pluginfile\\.php[^\"]*)\"").find(html)?.groupValues?.get(1)?.let { LmsParse.decode(it) } ?: return null
                            val u = java.net.URI(url).resolve(link)
                            if (u.scheme != "https" || !u.host.equals(host, true)) return null
                            u.toString()
                        } else {
                            val name = safeName(nameFromDisposition(r.header("Content-Disposition")) ?: java.net.URLDecoder.decode(java.net.URI(url).path.substringAfterLast('/'), "UTF-8"), "file-${item.cmid}")
                            val dir = dir(ctx, item.cmid).apply { mkdirs() }
                            val part = File(dir, "$name.part")
                            r.body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                            val done = File(dir, name)
                            part.renameTo(done)
                            return done
                        }
                    }
                    else -> throw LmsStop(LmsResult.FAILED, "http-${r.code}")
                }
            }
            if (next != "") url = next ?: return null
        }
        return null
    }
}
