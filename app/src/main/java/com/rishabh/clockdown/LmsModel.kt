package com.rishabh.clockdown

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

// The LMS (Amity's Moodle) data lives only on this phone: in the app's private "lms_data" preferences, which backups exclude
// (see backup_rules.xml), wiped on sign-out, never in a log, a crash report or any other network call.

/** What Moodle itself says about a submission, reduced to what the app has to decide on. The words shown are always Moodle's own. */
enum class SubStatus { SUBMITTED, DRAFT, NONE, OTHER }

/** One thing in a course (a file, a link, a quiz, an assignment...). Its page is `mod/<type>/view.php?id=<cmid>`. */
data class LmsItem(val cmid: Int, val type: String, val name: String, val badge: String?) {
    val path get() = "mod/$type/view.php?id=$cmid"
}

data class LmsSection(val number: Int, val title: String, val items: List<LmsItem>)

/** [sections] is empty and [readAt] 0 until the course's own page has been read. */
data class LmsCourse(val id: Int, val name: String, val shortName: String, val sections: List<LmsSection>, val readAt: Long)

/**
 * One assignment. The dates come from the course page; everything under them from the assignment's own page, so those are
 * null until it has been read. [statusText] is Moodle's exact words ("Draft (not submitted)"); [status] is only the
 * app's reading of them, and is SUBMITTED only when Moodle says so.
 */
data class LmsAssign(
    val cmid: Int, val courseId: Int, val name: String,
    val openText: String?, val dueText: String?, val openMs: Long?, val dueMs: Long?,
    val statusText: String?, val status: SubStatus?, val gradingText: String?, val remainingText: String?, val modifiedText: String?,
    val comments: Int?, val intro: String, val feedback: List<Pair<String, String>>, val fetchedAt: Long,
    /** "assign", or another activity with a deadline that only Moodle's Timeline told us about ("quiz", ...). */
    val type: String = "assign",
) {
    /** Not submitted as far as Moodle has told us (an unread page counts as not submitted: better a reminder too many). */
    val pending get() = status != SubStatus.SUBMITTED
    val isAssign get() = type == "assign"
    val path get() = "mod/$type/view.php?id=$cmid"
    val uploadPath get() = "mod/assign/view.php?id=$cmid&action=editsubmission"
}

/**
 * One of Moodle's own notifications (the bell on the LMS website): an announcement or forum post, a grade, feedback, a
 * submission receipt. [text] and [title] are Moodle's words; [url] is the page it points to.
 */
data class LmsUpdate(val id: Long, val at: Long, val kind: String, val title: String, val text: String, val url: String?, val read: Boolean)

/** [updatedAt] is when everything was last read cleanly; [partial] means the latest read could not read some page. */
data class LmsSnapshot(
    val base: String, val semester: String, val updatedAt: Long, val partial: Boolean,
    val courses: List<LmsCourse>, val assigns: List<LmsAssign>, val updates: List<LmsUpdate> = emptyList(),
) {
    fun course(id: Int) = courses.firstOrNull { it.id == id }
    fun assign(cmid: Int) = assigns.firstOrNull { it.cmid == cmid }
    fun courseOf(a: LmsAssign) = course(a.courseId)
    fun pendingIn(courseId: Int) = assigns.count { it.courseId == courseId && it.pending }
    fun itemCount() = courses.sumOf { c -> c.sections.sumOf { it.items.size } }
}

/** One accent colour per course, the same on every screen and the widget. */
fun lmsColor(courseId: Int) = Math.floorMod(courseId, PALETTE_SIZE)

enum class DueGroup(val label: String) { OVERDUE("Overdue"), TODAY("Today"), WEEK("This week"), LATER("Later") }

/** The "Due soon" list: what is pending, grouped by how soon, and what is already handed in. Pure, so every edge can be tested. */
object LmsDue {
    fun group(a: LmsAssign, now: Long, zone: ZoneId): DueGroup {
        val due = a.dueMs ?: return DueGroup.LATER
        if (due < now) return DueGroup.OVERDUE
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
        return when {
            day == today -> DueGroup.TODAY
            day <= today.plusDays(7) -> DueGroup.WEEK
            else -> DueGroup.LATER
        }
    }

    private val order = compareBy<LmsAssign>({ it.dueMs ?: Long.MAX_VALUE }, { it.name })

    fun grouped(assigns: List<LmsAssign>, now: Long, zone: ZoneId): Map<DueGroup, List<LmsAssign>> =
        assigns.filter { it.pending }.sortedWith(order).groupBy { group(it, now, zone) }

    fun submitted(assigns: List<LmsAssign>) = assigns.filter { !it.pending }.sortedWith(order)

    /** The next few not-yet-submitted assignments that are still open to hand in, soonest first (for the widget). */
    fun next(assigns: List<LmsAssign>, now: Long, max: Int) =
        assigns.filter { it.pending && (it.dueMs ?: Long.MAX_VALUE) >= now - OVERDUE_GRACE_MS }.sortedWith(order).take(max)

    /** A just-missed deadline stays visible for a day, so the widget doesn't silently drop what is now overdue. */
    const val OVERDUE_GRACE_MS = 24 * 3_600_000L
}

/** Saving and loading the snapshot. One JSON string; any problem reading it just means "nothing saved". */
object LmsStore {
    private fun p(ctx: Context) = ctx.getSharedPreferences("lms_data", Context.MODE_PRIVATE)

    fun load(ctx: Context): LmsSnapshot? = try { p(ctx).getString("snapshot", null)?.let { fromJson(JSONObject(it)) } } catch (e: Exception) { null }

    fun save(ctx: Context, s: LmsSnapshot) { p(ctx).edit().putString("snapshot", toJson(s).toString()).apply() }

    /** Sign-out: forget everything LMS, including the downloaded files and scans. */
    fun clear(ctx: Context) {
        p(ctx).edit().clear().apply()
        java.io.File(ctx.filesDir, LMS_FILES).deleteRecursively()
        java.io.File(ctx.filesDir, SCANS).deleteRecursively()
        java.io.File(ctx.cacheDir, "scan-pages").deleteRecursively()
    }

    const val LMS_FILES = "lms_files"
    const val SCANS = "scans"

    // ---- little things that live next to the snapshot
    fun failure(ctx: Context): String? = p(ctx).getString("failure", null)
    fun setFailure(ctx: Context, why: String?) { p(ctx).edit().apply { if (why == null) remove("failure") else putString("failure", why) }.apply() }
    fun triedAt(ctx: Context) = p(ctx).getLong("triedAt", 0)
    fun setTriedAt(ctx: Context, at: Long) { p(ctx).edit().putLong("triedAt", at).apply() }
    fun reminderKeys(ctx: Context): Set<String> = p(ctx).getStringSet("reminderKeys", emptySet()).orEmpty()
    fun setReminderKeys(ctx: Context, keys: Set<String>) { p(ctx).edit().putStringSet("reminderKeys", keys).apply() }
    fun reportedAt(ctx: Context, kind: String) = p(ctx).getLong("reported:$kind", 0)
    fun setReportedAt(ctx: Context, kind: String, at: Long) { p(ctx).edit().putLong("reported:$kind", at).apply() }

    fun toJson(s: LmsSnapshot) = JSONObject().put("base", s.base).put("sem", s.semester).put("at", s.updatedAt).put("partial", s.partial)
        .put("courses", JSONArray(s.courses.map { c ->
            JSONObject().put("id", c.id).put("name", c.name).put("short", c.shortName).put("read", c.readAt).put("sections", JSONArray(c.sections.map { sec ->
                JSONObject().put("n", sec.number).put("t", sec.title).put("items", JSONArray(sec.items.map { i ->
                    JSONObject().put("id", i.cmid).put("type", i.type).put("name", i.name).put("badge", i.badge ?: JSONObject.NULL)
                }))
            }))
        }))
        .put("assigns", JSONArray(s.assigns.map { a ->
            JSONObject().put("id", a.cmid).put("course", a.courseId).put("name", a.name)
                .put("openT", a.openText ?: JSONObject.NULL).put("dueT", a.dueText ?: JSONObject.NULL)
                .put("open", a.openMs ?: JSONObject.NULL).put("due", a.dueMs ?: JSONObject.NULL)
                .put("stT", a.statusText ?: JSONObject.NULL).put("st", a.status?.name ?: JSONObject.NULL)
                .put("grT", a.gradingText ?: JSONObject.NULL).put("remT", a.remainingText ?: JSONObject.NULL).put("modT", a.modifiedText ?: JSONObject.NULL)
                .put("cm", a.comments ?: JSONObject.NULL).put("intro", a.intro)
                .put("fb", JSONArray(a.feedback.map { JSONArray().put(it.first).put(it.second) })).put("at", a.fetchedAt).put("type", a.type)
        }))
        .put("updates", JSONArray(s.updates.map { u ->
            JSONObject().put("id", u.id).put("at", u.at).put("kind", u.kind).put("title", u.title).put("text", u.text).put("url", u.url ?: JSONObject.NULL).put("read", u.read)
        }))

    private fun JSONObject.s(k: String) = if (isNull(k)) null else getString(k)
    private fun JSONObject.l(k: String) = if (isNull(k)) null else getLong(k)

    fun fromJson(o: JSONObject): LmsSnapshot {
        fun <T> JSONArray.map2(f: (JSONObject) -> T) = (0 until length()).map { f(getJSONObject(it)) }
        return LmsSnapshot(
            o.getString("base"), o.getString("sem"), o.getLong("at"), o.getBoolean("partial"),
            o.getJSONArray("courses").map2 { c ->
                LmsCourse(c.getInt("id"), c.getString("name"), c.getString("short"), c.getJSONArray("sections").map2 { sec ->
                    LmsSection(sec.getInt("n"), sec.getString("t"), sec.getJSONArray("items").map2 { i ->
                        LmsItem(i.getInt("id"), i.getString("type"), i.getString("name"), i.s("badge"))
                    })
                }, c.getLong("read"))
            },
            o.getJSONArray("assigns").map2 { a ->
                LmsAssign(
                    a.getInt("id"), a.getInt("course"), a.getString("name"), a.s("openT"), a.s("dueT"), a.l("open"), a.l("due"),
                    a.s("stT"), a.s("st")?.let { runCatching { SubStatus.valueOf(it) }.getOrNull() }, a.s("grT"), a.s("remT"), a.s("modT"),
                    if (a.isNull("cm")) null else a.getInt("cm"), a.getString("intro"),
                    a.getJSONArray("fb").let { f -> (0 until f.length()).map { f.getJSONArray(it).let { r -> r.getString(0) to r.getString(1) } } },
                    a.getLong("at"), a.optString("type", "assign").ifBlank { "assign" },
                )
            },
            o.optJSONArray("updates")?.map2 { u ->
                LmsUpdate(u.getLong("id"), u.getLong("at"), u.getString("kind"), u.getString("title"), u.getString("text"), u.s("url"), u.getBoolean("read"))
            }.orEmpty(),
        )
    }
}
