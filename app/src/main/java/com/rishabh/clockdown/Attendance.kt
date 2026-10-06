package com.rishabh.clockdown

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Attendance data lives only on this phone: in the app's private preferences (excluded from backups), never in a log,
// a crash report or any other network call. The only thing that ever leaves is a failure TYPE (see AttendanceSync).

enum class Zone { GREEN, YELLOW, RED }

/** What one class (or a group of back-to-back periods) is marked as. */
enum class Mark { PRESENT, ABSENT, OD }

/** What a timetable class shows on screen: marked, or not marked yet. */
enum class ClassMark { PRESENT, ABSENT, OD, NOT_MARKED }

/**
 * One row of Amizone's attendance history. Back-to-back periods of one course come as ONE row with several time slots and
 * a count above 1 (a double period is present = 2), so counts always come from [present] and [absent], never from rows.
 */
data class ClassRecord(val date: LocalDate, val slots: List<String>, val present: Int, val absent: Int, val od: Boolean) {
    val mark: Mark? get() = when {
        present > 0 -> if (od) Mark.OD else Mark.PRESENT
        absent > 0 -> Mark.ABSENT
        else -> null
    }
    val key get() = "$date ${slots.joinToString(",")}"
}

/** [tracked] is false for a course Amizone shows "NA" for. [cid]/[sgid] are what the history page is asked for. */
data class CourseAtt(
    val code: String, val name: String, val cid: String?, val sgid: String?,
    val tracked: Boolean, val attended: Int, val total: Int, val records: List<ClassRecord>,
)

data class AttSnapshot(val courses: List<CourseAtt>, val updatedAt: Long) {
    val counted get() = courses.filter { it.tracked && it.total > 0 }
    val attended get() = counted.sumOf { it.attended }
    val total get() = counted.sumOf { it.total }

    fun course(code: String?) = courses.firstOrNull { it.code.equals(code, ignoreCase = true) }

    /** null = nothing to say (course not in the list, or not tracked). Today's class with no row yet is [ClassMark.NOT_MARKED]. */
    fun markFor(e: Event): ClassMark? {
        val c = course(e.courseCode)?.takeIf { it.tracked } ?: return null
        val at = java.time.Instant.ofEpochMilli(e.startMillis).atZone(AMIZONE_ZONE)
        val hhmm = at.toLocalTime().format(HHMM)
        val r = c.records.firstOrNull { it.date == at.toLocalDate() && it.slots.any { s -> s.startsWith(hhmm) } } ?: return ClassMark.NOT_MARKED
        return when (r.mark) { Mark.PRESENT -> ClassMark.PRESENT; Mark.OD -> ClassMark.OD; Mark.ABSENT -> ClassMark.ABSENT; null -> ClassMark.NOT_MARKED }
    }
}

val AMIZONE_ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Kolkata")
private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

/** The arithmetic. Whole numbers only, so a course exactly on a threshold lands on the right side of it. */
object AttMath {
    /** Amizone's own chart: green is ABOVE [green], yellow is [yellow] up to and including [green], red is below [yellow]. */
    fun zone(attended: Int, total: Int, green: Int, yellow: Int): Zone = when {
        attended * 100 > green * total -> Zone.GREEN
        attended * 100 >= yellow * total -> Zone.YELLOW
        else -> Zone.RED
    }

    /** Classes to attend in a row to reach [pct]%. 0 when already there. [pct] must be below 100. */
    fun toReach(attended: Int, total: Int, pct: Int): Int {
        val gap = pct * total - 100 * attended
        return if (gap <= 0) 0 else (gap + (100 - pct) - 1) / (100 - pct)
    }

    /** Classes that can be missed while staying at or above [pct]%. */
    fun canMiss(attended: Int, total: Int, pct: Int): Int = ((100 * attended - pct * total) / pct).coerceAtLeast(0)

    /** Percent to one decimal, rounded DOWN so a course just under a line is never shown as being on it. */
    fun percent(attended: Int, total: Int): String =
        if (total == 0) "–" else String.format(Locale.US, "%.1f", Math.floorDiv(attended * 1000, total) / 10.0)
}

/** The minimum to stay above (75 by default; 80 and 85 are one tap away). Every "classes needed" and "can miss" number uses it. */
val Context.attTarget get() = prefs.getInt("attTarget", 75)
/** The colour lines, as on Amizone's own chart: green above 85, yellow from 75, red below. */
val Context.attGreen get() = prefs.getInt("attGreen", 85)
val Context.attYellow get() = prefs.getInt("attYellow", 75)
val Context.attNotifyDontSkip get() = prefs.getBoolean("attNotifyDontSkip", true)
val Context.attNotifyMarks get() = prefs.getBoolean("attNotifyMarks", true)
val Context.attNotifyZone get() = prefs.getBoolean("attNotifyZone", true)
val Context.attUpdatedAt get() = prefs.getLong("attUpdated", 0)

/** The reason the last attendance refresh didn't work ("network", "format"), or null. Never any content. */
val Context.attFailure: String? get() = prefs.getString("attFailure", null)

fun Context.zoneOf(c: CourseAtt) = AttMath.zone(c.attended, c.total, attGreen, attYellow)

/** The one line that matters for a subject: what to attend to get back to the target, or how many can be missed. Short enough for a widget. */
fun attendanceAdvice(ctx: Context, c: CourseAtt): String {
    val t = ctx.attTarget
    return if (c.attended * 100 < t * c.total) AttMath.toReach(c.attended, c.total, t).let { "attend the next $it to reach $t%" }
    else AttMath.canMiss(c.attended, c.total, t).let { if (it == 0) "can't miss a class" else "can miss $it" }
}

/** Reads Amizone's two attendance pages. Pure text in, data out; throws [AttendanceFormatException] on anything unexpected. */
object AttendanceParse {
    class AttendanceFormatException(val kind: String) : Exception(kind)

    const val LIST_MARKER = "data-title=\"Course Code\""
    const val HISTORY_MARKER = "Attendance Details"
    const val NO_RECORD = "Record Not Exit" // Amizone's own spelling: a course with no history at all

    private val rowRx = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val cellRx = Regex("<td[^>]*data-title=\"([^\"]*)\"[^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val tagRx = Regex("<[^>]*>")
    private val fraction = Regex("(\\d+)\\s*/\\s*(\\d+)")
    private val ids = Regex("Fnattendance2\\('(\\d+)'\\s*,\\s*'(\\d+)'\\)")
    private val slotRx = Regex("\\[(\\d\\d:\\d\\d)-(\\d\\d:\\d\\d)\\]")
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    private fun text(html: String) = tagRx.replace(html, " ").replace("&nbsp;", " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
    private fun cells(row: String) = cellRx.findAll(row).associate { it.groupValues[1].trim().lowercase() to it.groupValues[2] }

    /** The My Courses page: one entry per course, history left empty for the caller to fill. */
    fun courses(html: String): List<CourseAtt> {
        val out = rowRx.findAll(html).map { it.groupValues[1] }.filter { it.contains(LIST_MARKER) }.map { row ->
            val c = cells(row)
            val code = text(c["course code"] ?: throw AttendanceFormatException("list-format"))
            val name = text(c["course name"] ?: throw AttendanceFormatException("list-format"))
            val cell = c["attendance"] ?: throw AttendanceFormatException("list-format")
            val shown = text(cell)
            val f = fraction.find(shown)
            when {
                code.isEmpty() -> throw AttendanceFormatException("list-format")
                f != null -> {
                    val id = ids.find(cell)
                    CourseAtt(code, name, id?.groupValues?.get(1), id?.groupValues?.get(2), true, f.groupValues[1].toInt(), f.groupValues[2].toInt(), emptyList())
                }
                // Amizone doesn't track this course ("NA"): shown as "Not tracked".
                shown.isEmpty() || shown.equals("NA", true) || shown.equals("N/A", true) || shown == "-" ->
                    CourseAtt(code, name, null, null, false, 0, 0, emptyList())
                else -> throw AttendanceFormatException("list-format")
            }
        }.toList()
        if (out.isEmpty() || out.any { it.tracked && it.attended > it.total }) throw AttendanceFormatException("list-format")
        return out
    }

    /** One course's history fragment. Newest first. */
    fun history(html: String): List<ClassRecord> {
        if (html.contains(NO_RECORD)) return emptyList()
        if (!html.contains(HISTORY_MARKER)) throw AttendanceFormatException("history-format")
        return try {
            rowRx.findAll(html).map { it.groupValues[1] }.filter { it.contains("data-title=\"Sno\"", true) }.map { row ->
                val c = cells(row)
                fun n(k: String) = text(c[k] ?: throw AttendanceFormatException("history-format")).toInt()
                ClassRecord(
                    date = LocalDate.parse(text(c["date of class"] ?: throw AttendanceFormatException("history-format")), DATE),
                    slots = slotRx.findAll(text(c["timings of class"].orEmpty())).map { it.groupValues[1] + "-" + it.groupValues[2] }.toList(),
                    present = n("present"), absent = n("absent"),
                    od = Regex("\\bOD\\b").containsMatchIn(text(c["remarks"].orEmpty())),
                )
            }.toList().sortedByDescending { it.date }
        } catch (e: AttendanceFormatException) { throw e } catch (e: Exception) { throw AttendanceFormatException("history-format") }
    }
}

/** Reading and writing the saved snapshot. */
object AttendanceStore {
    private const val KEY = "attJson"

    fun load(ctx: Context): AttSnapshot? = try {
        ctx.prefs.getString(KEY, null)?.let { fromJson(JSONObject(it)) }
    } catch (e: Exception) { null }

    fun save(ctx: Context, s: AttSnapshot) { ctx.prefs.edit().putString(KEY, toJson(s).toString()).putLong("attUpdated", s.updatedAt).apply() }

    fun clear(ctx: Context) {
        ctx.prefs.edit().remove(KEY).remove("attUpdated").remove("attFailure").remove("attTriedAt").remove("attRefreshing").apply()
    }

    fun toJson(s: AttSnapshot) = JSONObject().put("at", s.updatedAt).put("courses", JSONArray(s.courses.map { c ->
        JSONObject().put("code", c.code).put("name", c.name).put("cid", c.cid).put("sgid", c.sgid).put("tracked", c.tracked)
            .put("a", c.attended).put("t", c.total).put("records", JSONArray(c.records.map { r ->
                JSONObject().put("d", r.date.toString()).put("s", JSONArray(r.slots)).put("p", r.present).put("x", r.absent).put("od", r.od)
            }))
    }))

    fun fromJson(o: JSONObject) = AttSnapshot(
        (0 until o.getJSONArray("courses").length()).map { o.getJSONArray("courses").getJSONObject(it) }.map { c ->
            CourseAtt(
                c.getString("code"), c.getString("name"), c.optString("cid").ifBlank { null }.takeIf { !c.isNull("cid") },
                c.optString("sgid").ifBlank { null }.takeIf { !c.isNull("sgid") }, c.getBoolean("tracked"), c.getInt("a"), c.getInt("t"),
                c.getJSONArray("records").let { rs ->
                    (0 until rs.length()).map { rs.getJSONObject(it) }.map { r ->
                        ClassRecord(LocalDate.parse(r.getString("d")), r.getJSONArray("s").let { a -> (0 until a.length()).map(a::getString) }, r.getInt("p"), r.getInt("x"), r.getBoolean("od"))
                    }
                },
            )
        },
        o.getLong("at"),
    )
}

/** What changed between two snapshots, for the two notifications. Pure. */
object AttendanceChanges {
    class MarkNote(val course: CourseAtt, val mark: Mark)
    class ZoneNote(val course: CourseAtt, val to: Zone)

    /** Rows that are new or changed, dated today or yesterday (a long-offline phone doesn't spray a week of old news). */
    fun marks(old: AttSnapshot, new: AttSnapshot, today: LocalDate): List<MarkNote> = new.courses.filter { it.tracked }.flatMap { c ->
        val before = old.course(c.code)?.records?.associate { it.key to it.mark }.orEmpty()
        c.records.filter { it.mark != null && !it.date.isBefore(today.minusDays(1)) && before[it.key] != it.mark }.map { MarkNote(c, it.mark!!) }
    }

    /** Only drops: a subject that moved to a worse zone. */
    fun drops(old: AttSnapshot, new: AttSnapshot, target: Int, yellow: Int): List<ZoneNote> = new.courses.filter { it.tracked && it.total > 0 }.mapNotNull { c ->
        val o = old.course(c.code)?.takeIf { it.tracked && it.total > 0 } ?: return@mapNotNull null
        val zOld = AttMath.zone(o.attended, o.total, target, yellow)
        val zNew = AttMath.zone(c.attended, c.total, target, yellow)
        if (zNew.ordinal > zOld.ordinal) ZoneNote(c, zNew) else null
    }
}

enum class AttResult(val message: String) {
    OK("Attendance updated"),
    NO_SESSION("Not signed in to Amizone"),
    EXPIRED("Amizone sign-in needed"),
    FAILED("Couldn't reach Amizone"),
    UNREADABLE("Attendance couldn't be read right now"),
}

object AttendanceSync {
    private const val PAUSE_MS = 400L // between a course's history requests: gentle on Amizone

    /**
     * Blocking; off the main thread. Gentle by design: ONE request for the course list, then a course's history only when
     * its counts changed since last time. All or nothing: if any step fails, what was last saved stays exactly as it was.
     * [minGapMs] > 0 skips the run if one was tried that recently (background wakeups); user actions pass 0.
     */
    @Synchronized
    fun run(ctx: Context, minGapMs: Long = 0, force: Boolean = false): AttResult {
        val p = ctx.prefs
        val now = System.currentTimeMillis()
        val cookies = Session.cookies(ctx) ?: return AttResult.NO_SESSION
        if (!force && ctx.sessionExpired) return AttResult.EXPIRED
        if (minGapMs > 0 && now - p.getLong("attTriedAt", 0) < minGapMs) return AttResult.OK
        p.edit().putLong("attTriedAt", now).apply()
        val ua = Session.userAgent(ctx) ?: android.webkit.WebSettings.getDefaultUserAgent(ctx)
        val old = AttendanceStore.load(ctx)

        try {
            val page = AmizoneSync.fetch("/Academics/MyCourses", emptyMap(), cookies, ua, xhr = false)
            when (page.verdict(AttendanceParse.LIST_MARKER)) {
                Verdict.EXPIRED -> return AmizoneSync.expired(ctx).let { AttResult.EXPIRED }
                Verdict.PROBLEM -> return failed(ctx, "network")
                Verdict.OK -> Session.renew(ctx, page.setCookies)
                null -> return unreadable(ctx, "list-format")
            }
            val fresh = try { AttendanceParse.courses(page.body) } catch (e: AttendanceParse.AttendanceFormatException) { return unreadable(ctx, e.kind) }

            val courses = fresh.mapIndexed { i, c ->
                val same = old?.course(c.code)
                // History is only fetched when it could have changed: a different count, or nothing saved for a course that has classes.
                val unchanged = same != null && same.tracked && same.attended == c.attended && same.total == c.total && (same.records.isNotEmpty() || c.total == 0)
                when {
                    !c.tracked || c.cid == null || c.sgid == null -> c
                    unchanged -> c.copy(records = same!!.records)
                    else -> {
                        if (i > 0) Thread.sleep(PAUSE_MS)
                        val h = AmizoneSync.fetch("/Academics/MyCourses/_Attendancefoo", mapOf("cid" to c.cid, "sgid" to c.sgid), cookies, ua, xhr = true)
                        when (h.verdict(AttendanceParse.HISTORY_MARKER, AttendanceParse.NO_RECORD)) {
                            Verdict.EXPIRED -> return AmizoneSync.expired(ctx).let { AttResult.EXPIRED }
                            Verdict.PROBLEM -> return failed(ctx, "network")
                            Verdict.OK -> {}
                            null -> return unreadable(ctx, "history-format")
                        }
                        c.copy(records = try { AttendanceParse.history(h.body) } catch (e: AttendanceParse.AttendanceFormatException) { return unreadable(ctx, e.kind) })
                    }
                }
            }
            val snap = AttSnapshot(courses, System.currentTimeMillis())
            AttendanceStore.save(ctx, snap)
            p.edit().remove("attFailure").apply()
            if (old != null) notify(ctx, old, snap)
            Scheduler.refreshWidget(ctx)
            return AttResult.OK
        } catch (e: IOException) {
            return failed(ctx, "network")
        }
    }

    private fun failed(ctx: Context, why: String): AttResult {
        ctx.prefs.edit().putString("attFailure", why).apply()
        Scheduler.refreshWidget(ctx)
        return AttResult.FAILED
    }

    /** The page changed shape. Shows "couldn't be read" and tells the developer the failure TYPE only (at most once every 6 hours). */
    private fun unreadable(ctx: Context, kind: String): AttResult {
        val p = ctx.prefs
        p.edit().putString("attFailure", "format").apply()
        val now = System.currentTimeMillis()
        if (now - p.getLong("attReported:$kind", 0) > 6 * 3_600_000L) {
            p.edit().putLong("attReported:$kind", now).apply()
            CrashReporting.noteAttendanceFailure(kind)
        }
        Scheduler.refreshWidget(ctx)
        return AttResult.UNREADABLE
    }

    // ------------------------------------------------------------ notifications (each has its own switch in Settings)

    private const val CH = "attendance"
    private val title = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    private fun open(ctx: Context) = PendingIntent.getActivity(
        ctx, 77, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_ATTENDANCE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(ctx: Context, old: AttSnapshot, new: AttSnapshot) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Attendance", NotificationManager.IMPORTANCE_DEFAULT))
        fun post(id: Int, heading: String, text: String) = nm.notify(id, Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(heading).setContentText(text)
            .setContentIntent(open(ctx)).setAutoCancel(true).build())
        val name = { c: CourseAtt -> titleCase(c.name) }
        if (ctx.attNotifyMarks) AttendanceChanges.marks(old, new, LocalDate.now(AMIZONE_ZONE)).take(4).forEach { n ->
            val what = when (n.mark) { Mark.PRESENT -> "present"; Mark.OD -> "present (OD)"; Mark.ABSENT -> "absent" }
            post(2_000_000 + Math.floorMod(n.course.code.hashCode(), 100_000), "Marked $what", "Marked $what in ${name(n.course)}")
        }
        if (ctx.attNotifyZone) AttendanceChanges.drops(old, new, ctx.attGreen, ctx.attYellow).forEach { n ->
            val z = if (n.to == Zone.RED) "red" else "yellow"
            post(3_000_000 + Math.floorMod(n.course.code.hashCode(), 100_000), "Attendance dropped to $z",
                "${name(n.course)} dropped to $z (${AttMath.percent(n.course.attended, n.course.total)}%)")
        }
    }

    /** "11:41 am" or "yesterday, 6:02 pm": when the numbers on screen were last confirmed. */
    fun updatedText(millis: Long): String {
        if (millis == 0L) return "never"
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val day = if (at.toLocalDate() == LocalDate.now(zone)) "" else at.format(DateTimeFormatter.ofPattern("d MMM, ", Locale.getDefault()))
        return day + at.toLocalTime().format(title).lowercase()
    }
}

/** Carried by anything that should land on the Attendance screen: the widget, a notification. */
const val EXTRA_OPEN_ATTENDANCE = "openAttendance"

/** Runs one attendance refresh as a one-off job (never periodic): it waits for a connection and doesn't tie up a broadcast. */
class AttendanceWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try { AttendanceSync.run(applicationContext, minGapMs = inputData.getLong("gap", 0)) }
        finally { applicationContext.prefs.edit().remove("attRefreshing").apply(); Scheduler.refreshWidget(applicationContext) }
        return Result.success()
    }
}

/**
 * The only moments attendance is refreshed without you opening the app. All of them ride on wakeups that already exist:
 * the class-start alarm, the alarm that refreshes the widgets (which also fires a few minutes after a class ends), and the
 * timetable's own 6-hourly check. Android may delay any of them on a phone with a strict battery saver; opening the app
 * or tapping the widget always works.
 */
object AttendanceWakeups {
    private const val MIN = 60_000L

    fun enqueue(ctx: Context, gapMs: Long, name: String = "attendance") {
        if (!ctx.amizoneConnected || ctx.sessionExpired) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            name, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AttendanceWorker>().setInputData(Data.Builder().putLong("gap", gapMs).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    /** A class just started: teachers often mark at the start. */
    fun classStarted(ctx: Context) = enqueue(ctx, 5 * MIN)

    /** The widget-refresh alarm fired: a class ended in the last few minutes (look again soon), or it is just a day/midnight tick (rarely). */
    fun refreshTick(ctx: Context) {
        val now = System.currentTimeMillis()
        val justEnded = AppDb.get(ctx).classesBetween(now - 3 * 3_600_000L, now).any { (it.endMillis ?: 0) > now - 10 * MIN }
        enqueue(ctx, if (justEnded) 2 * MIN else 3 * 3_600_000L)
    }

    /** Tapping the widget's refresh: always runs (a short guard against a double tap), and the widget shows "Refreshing…" meanwhile. */
    fun widgetTap(ctx: Context) {
        val p = ctx.prefs
        if (!ctx.amizoneConnected) return
        if (System.currentTimeMillis() - p.getLong("attTriedAt", 0) < 5_000) return
        p.edit().putBoolean("attRefreshing", true).apply()
        Scheduler.refreshWidget(ctx)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "attendance-tap", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AttendanceWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }
}
