package com.rishabh.clockdown

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** A page that loaded normally but isn't shaped like we expect: Moodle or Amizone changed. [kind] is a short label, never content. */
class LmsFormatException(val kind: String) : Exception(kind)

/** One of the Amizone Home page's "LMS" buttons: a hidden form that signs the student in to one semester's Moodle. */
class SsoForm(val label: String, val action: String, val fields: List<Pair<String, String>>)

/** What an assignment's own page says. Every text is Moodle's own words; nothing is interpreted except [status]. */
class AssignView(
    val statusText: String?, val status: SubStatus?, val gradingText: String?, val remainingText: String?, val modifiedText: String?,
    val comments: Int?, val intro: String, val feedback: List<Pair<String, String>>, val openText: String?, val dueText: String?,
)

class CoursePage(val sections: List<LmsSection>, val assignDates: Map<Int, Pair<String?, String?>>)

/** One entry of Moodle's Timeline (the dashboard's "what's due" list): something the student still has to do, with its deadline. */
class TimelineEvent(val cmid: Int, val courseId: Int, val type: String, val name: String, val dueMs: Long, val action: String?)

/** Reads Amizone's Home page and Moodle's pages. Pure text in, data out; throws [LmsFormatException] on anything unexpected. */
object LmsParse {
    private val tagRx = Regex("<[^>]*>")
    private val spaceRx = Regex("\\s+")

    /** Visible text of a bit of HTML: tags dropped, entities decoded, whitespace squeezed. */
    fun text(html: String): String = decode(tagRx.replace(html.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n"), " "))
        .lines().joinToString("\n") { it.replace(spaceRx, " ").trim() }.replace(Regex("\n{3,}"), "\n\n").trim()

    /** Same, on one line. */
    fun oneLine(html: String): String = text(html).replace(spaceRx, " ").trim()

    fun decode(s: String): String = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&amp;", "&")

    /** An attribute of one opening tag, quoted either way (Amizone writes 'single', Moodle "double"). */
    fun attr(tag: String, name: String): String? =
        Regex("\\s${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag)
            ?.let { m -> (m.groups[1] ?: m.groups[2] ?: m.groups[3])!!.value }?.let { decode(it) }

    // ------------------------------------------------------------ Amizone Home: the LMS buttons

    private val formRx = Regex("<form\\b([^>]*)>(.*?)</form>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val inputRx = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** Every form on the page that posts to a `.../submit.php` (the LMS buttons), in page order. */
    fun ssoForms(html: String): List<SsoForm> = formRx.findAll(html).mapNotNull { m ->
        val tag = "<form" + m.groupValues[1]
        val action = attr(tag, "action") ?: return@mapNotNull null
        if (!action.substringBefore('?').endsWith("/submit.php")) return@mapNotNull null
        val fields = inputRx.findAll(m.groupValues[2]).mapNotNull { i ->
            val name = attr(i.value, "name") ?: return@mapNotNull null
            if (!(attr(i.value, "type") ?: "text").equals("hidden", true)) null else name to (attr(i.value, "value") ?: "")
        }.toList()
        SsoForm(oneLine(m.groupValues[2]), action, fields)
    }.toList()

    private val semesterRx = Regex("(\\d{4})\\s*-\\s*(\\d{4})\\s*/\\s*(Odd|Even)", RegexOption.IGNORE_CASE)

    /** Within an academic year, Odd (July to December) comes before Even (January to June). Higher = more recent. */
    fun semesterRank(label: String): Int? = semesterRx.find(label)?.let {
        it.groupValues[1].toInt() * 10 + if (it.groupValues[3].equals("odd", true)) 0 else 1
    }

    /** The current semester's form: the most recent labelled one. Never a fixed path. */
    fun currentSemester(forms: List<SsoForm>): SsoForm? = forms.maxByOrNull { semesterRank(it.label) ?: -1 }

    /**
     * Where posting this form is allowed to go: https, an amizone.net host, one folder, `/submit.php`. The Home page decides
     * the folder, but it can never send us to some other site. Returns the folder's address ("https://host/moodle_26odd"), or null.
     */
    fun ssoBase(action: String): String? {
        val u = try { java.net.URI(action) } catch (e: Exception) { return null }
        val host = u.host?.lowercase() ?: return null
        if (u.scheme != "https" || !(host == "amizone.net" || host.endsWith(".amizone.net")) || u.port != -1 || u.rawQuery != null) return null
        val m = Regex("^/([A-Za-z0-9_.-]+)/submit\\.php$").matchEntire(u.rawPath.orEmpty()) ?: return null
        return "https://$host/${m.groupValues[1]}"
    }

    // ------------------------------------------------------------ Moodle: signed-in checks

    fun sesskey(html: String): String? = Regex("\"sesskey\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)

    /** Moodle's own page config says who is signed in: 0 for a guest, null when the page has no such config. */
    fun userId(html: String): Int? = Regex("\"userId\"\\s*:\\s*(\\d+)").find(html)?.groupValues?.get(1)?.toInt()

    fun isLoginPage(html: String) = html.contains("name=\"logintoken\"")

    /** The enrolled-courses answer of Moodle's own AJAX service: (id, full name, short name). */
    fun courses(json: String): List<Triple<Int, String, String>> {
        val root = try { org.json.JSONArray(json).getJSONObject(0) } catch (e: Exception) { throw LmsFormatException("courses-api") }
        if (root.optBoolean("error", true)) throw LmsFormatException("courses-api")
        val arr = root.optJSONObject("data")?.optJSONArray("courses") ?: throw LmsFormatException("courses-api")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { c ->
            Triple(c.getInt("id"), oneLine(c.optString("fullname")).ifBlank { c.optString("shortname") }, oneLine(c.optString("shortname")))
        }
    }

    /** True when Moodle's AJAX answer says the session is gone (or its key is stale), so signing on again is the fix. */
    fun ajaxSaysSignedOut(json: String): Boolean = try {
        val code = JSONObject(json.trim().removePrefix("[").removeSuffix("]")).optJSONObject("exception")?.optString("errorcode").orEmpty()
        code == "servicerequireslogin" || code == "requireloginerror" || code == "invalidsesskey" || code == "sessionerroruser"
    } catch (e: Exception) { false }

    /**
     * Moodle's Timeline answer (core_calendar_get_action_events_by_timesort). Moodle itself drops an entry once it is done (a
     * quiz attempted, an assignment handed in), so being in here means "still to do". One entry per activity: its last deadline.
     */
    fun timeline(json: String): List<TimelineEvent> {
        val root = try { org.json.JSONArray(json).getJSONObject(0) } catch (e: Exception) { throw LmsFormatException("timeline-api") }
        if (root.optBoolean("error", true)) throw LmsFormatException("timeline-api")
        val arr = root.optJSONObject("data")?.optJSONArray("events") ?: throw LmsFormatException("timeline-api")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { e ->
            val type = e.optString("modulename").ifBlank { return@mapNotNull null }
            val cmid = Regex("[?&]id=(\\d+)").find(e.optString("url"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val courseId = e.optJSONObject("course")?.optInt("id") ?: return@mapNotNull null
            val name = oneLine(e.optString("activityname")).ifBlank { oneLine(e.optString("name")) }
            TimelineEvent(cmid, courseId, type, name, e.optLong("timesort") * 1000, e.optJSONObject("action")?.optString("name")?.let { oneLine(it) }?.ifBlank { null })
        }.groupBy { it.cmid }.map { (_, list) -> list.maxBy { it.dueMs } }
    }

    /** Moodle's notifications (message_popup_get_popup_notifications), newest first. "New sign in" notices are left out: they say nothing about a course. */
    fun notifications(json: String): List<LmsUpdate> {
        val root = try { org.json.JSONArray(json).getJSONObject(0) } catch (e: Exception) { throw LmsFormatException("notifications-api") }
        if (root.optBoolean("error", true)) throw LmsFormatException("notifications-api")
        val arr = root.optJSONObject("data")?.optJSONArray("notifications") ?: throw LmsFormatException("notifications-api")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filterNot { n ->
            n.optBoolean("deleted") || (n.optString("component") == "moodle" && n.optString("eventtype") == "newlogin")
        }.map { n ->
            LmsUpdate(
                id = n.getLong("id"), at = n.optLong("timecreated") * 1000, kind = n.optString("component"),
                // A forum post is named by its topic; anything else (a submission receipt, a grade) by Moodle's whole sentence,
                // so "You have submitted ... for Assignment" never reads like an assignment still to do.
                title = (if (n.optString("component") == "mod_forum") oneLine(n.optString("contexturlname")) else "").ifBlank { oneLine(n.optString("subject")) },
                text = oneLine(n.optString("smallmessage")).ifBlank { oneLine(n.optString("subject")) },
                url = n.optString("contexturl").takeIf { it.startsWith("https://") }, read = n.optBoolean("read"),
            )
        }.sortedByDescending { it.at }
            // Moodle re-sends the same post's notification day after day: one row per page, the newest, unread if any copy is.
            .groupBy { it.url ?: "id:${it.id}" }.values.map { same -> same.first().copy(read = same.all { it.read }) }
    }

    private val moodleDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH)

    /** A deadline written the way Moodle writes it ("Thursday, 22 October 2026, 4:20 PM"), so it reads the same as dates taken from pages. */
    fun moodleText(ms: Long, zone: ZoneId = AMIZONE_ZONE): String = java.time.Instant.ofEpochMilli(ms).atZone(zone).format(moodleDate)

    // ------------------------------------------------------------ Moodle: a course page

    private val liRx = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val typeRx = Regex("\\bmodtype_([a-z0-9_]+)")

    fun coursePage(html: String): CoursePage {
        val tags = liRx.findAll(html).filter { attr(it.value, "data-for").let { f -> f == "section" || f == "cmitem" } }.toList()
        if (tags.none { attr(it.value, "data-for") == "section" }) throw LmsFormatException("course-format")

        class Open(val number: Int, val title: String, val items: MutableList<LmsItem> = mutableListOf())
        val done = mutableListOf<Open>()
        var cur: Open? = null
        val dates = mutableMapOf<Int, Pair<String?, String?>>()
        for ((n, t) in tags.withIndex()) {
            val region = html.substring(t.range.last + 1, tags.getOrNull(n + 1)?.range?.first ?: html.length)
            if (attr(t.value, "data-for") == "section") {
                val number = attr(t.value, "data-number")?.toIntOrNull() ?: done.size + (if (cur != null) 1 else 0)
                cur?.let { done += it }
                cur = Open(number, sectionTitle(region))
                continue
            }
            val section = cur ?: continue
            val cmid = attr(t.value, "data-id")?.toIntOrNull() ?: continue
            val type = typeRx.find(t.value)?.groupValues?.get(1) ?: continue
            val name = Regex("class=\"[^\"]*\\binstancename\\b[^\"]*\"[^>]*>(.*?)</span>\\s*</a>", RegexOption.DOT_MATCHES_ALL).find(region)
                ?.groupValues?.get(1)?.let { oneLine(it.replace(Regex("<span class=\"[^\"]*accesshide[^\"]*\".*?</span>", RegexOption.DOT_MATCHES_ALL), "")) }
            // A label or a bare text block has no link and nothing to open: leave it out.
            if (name.isNullOrBlank() || !Regex("href=\"[^\"]*mod/$type/view\\.php").containsMatchIn(region)) continue
            val badge = Regex("class=\"[^\"]*\\bactivitybadge\\b[^\"]*\"[^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL).find(region)?.groupValues?.get(1)?.let { oneLine(it) }?.ifBlank { null }
            section.items += LmsItem(cmid, type, name, badge)
            if (type == "assign") dates[cmid] = activityDates(region).let { it["opened"] to it["due"] }
        }
        cur?.let { done += it }
        return CoursePage(done.map { LmsSection(it.number, it.title, it.items.toList()) }, dates)
    }

    /** The heading of a section: its `sectionname` heading (the rest of the header is screen-reader labels and buttons). */
    private fun sectionTitle(region: String): String {
        val end = listOf("data-for=\"sectioninfo\"", "data-for=\"cmlist\"").map { region.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: region.length
        val h = Regex("<h\\d\\b[^>]*\\bsectionname\\b[^>]*>(.*?)</h\\d>", RegexOption.DOT_MATCHES_ALL).find(region.substring(0, end))
        return h?.let { oneLine(it.groupValues[1]) }.orEmpty().take(120)
    }

    /** `<strong>Due:</strong> Wednesday, 14 October 2026, 2:00 PM` pairs, keyed by lower-case label ("opened", "due"). */
    fun activityDates(region: String): Map<String, String> {
        val at = region.indexOf("data-region=\"activity-dates\"")
        if (at < 0) return emptyMap()
        val block = region.substring(at, minOf(region.length, at + 1500))
        return Regex("<strong>\\s*([^<:]*?)\\s*:\\s*</strong>\\s*([^<]*?)\\s*</div>", RegexOption.DOT_MATCHES_ALL).findAll(block)
            .associate { it.groupValues[1].lowercase() to oneLine(it.groupValues[2]) }.filterValues { it.isNotBlank() }
    }

    // ------------------------------------------------------------ Moodle: an assignment page

    private val dateFmt: DateTimeFormatter = DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendPattern("EEEE, d MMMM yyyy, h:mm a").toFormatter(Locale.ENGLISH)

    /** "Wednesday, 14 October 2026, 2:00 PM" as Moodle prints it (Amity's site runs on Indian time), or null if it isn't that shape. */
    fun dateMs(text: String?, zone: ZoneId = AMIZONE_ZONE): Long? = try {
        text?.replace('\u202F', ' ')?.replace('\u00A0', ' ')?.trim()?.let { LocalDateTime.parse(it, dateFmt).atZone(zone).toInstant().toEpochMilli() }
    } catch (e: Exception) { null }

    /**
     * Moodle's words for the submission, read strictly. "Draft (not submitted)" contains the word "submitted", so a loose
     * match would show a draft as handed in; SUBMITTED needs Moodle's own "Submitted ..." wording (or its own CSS class for it).
     */
    fun statusOf(text: String?, cellClass: String? = null): SubStatus? {
        if (text == null) return null
        val t = text.lowercase().replace(spaceRx, " ").trim()
        return when {
            t.isEmpty() -> null
            "draft" in t -> SubStatus.DRAFT
            "not submitted" in t || "no submission" in t || "no attempt" in t || "have been made yet" in t || "nothing submitted" in t -> SubStatus.NONE
            t.startsWith("submitted") || cellClass?.contains("submissionstatussubmitted") == true -> SubStatus.SUBMITTED
            else -> SubStatus.OTHER
        }
    }

    private val rowRx = Regex("<tr\\b[^>]*>\\s*<th\\b[^>]*>(.*?)</th>\\s*<td\\b([^>]*)>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private fun rows(block: String) = rowRx.findAll(block).map { Triple(oneLine(it.groupValues[1]), it.groupValues[2], it.groupValues[3]) }.toList()

    /** A `<div ...>` block, found by its opening text, balanced over nested divs. */
    private fun divAt(html: String, marker: String): String? {
        val at = html.indexOf(marker)
        if (at < 0) return null
        val start = html.lastIndexOf("<div", at)
        if (start < 0) return null
        var depth = 0
        for (m in Regex("<div\\b|</div>", RegexOption.IGNORE_CASE).findAll(html, start)) {
            depth += if (m.value.startsWith("</")) -1 else 1
            if (depth == 0) return html.substring(start, m.range.last + 1)
        }
        return html.substring(start)
    }

    fun assignView(html: String): AssignView {
        val table = divAt(html, "submissionstatustable") ?: throw LmsFormatException("assign-format")
        val r = rows(table)
        val status = r.firstOrNull { it.first.equals("Submission status", true) } ?: throw LmsFormatException("assign-format")
        val statusText = oneLine(status.third)
        fun row(label: String) = r.firstOrNull { it.first.startsWith(label, true) }?.let { oneLine(it.third) }?.ifBlank { null }
        val comments = r.firstOrNull { it.first.startsWith("Submission comments", true) }?.let { Regex("\\((\\d+)\\)").find(oneLine(it.third))?.groupValues?.get(1)?.toIntOrNull() }
        val feedback = divAt(html, "class=\"feedback")?.let { rows(it).map { (l, _, v) -> l to oneLine(v) }.filter { it.second.isNotBlank() } }.orEmpty()
        val dates = activityDates(html)
        return AssignView(
            statusText = statusText.ifBlank { null }, status = statusOf(statusText, status.second),
            gradingText = row("Grading status"), remainingText = row("Time remaining"), modifiedText = row("Last modified")?.takeUnless { it == "-" },
            comments = comments, intro = divAt(html, "id=\"intro\"")?.let { text(it) }.orEmpty(), feedback = feedback,
            openText = dates["opened"], dueText = dates["due"],
        )
    }
}
