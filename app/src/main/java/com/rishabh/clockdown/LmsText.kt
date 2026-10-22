package com.rishabh.clockdown

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words the LMS screens use for time. Pure, so they can be tested. */
object LmsText {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /**
     * "5d 14h", "1d 6h", "5h 12m", "42m", and for something already past "2h late" / "2d late".
     * [ms] is the deadline minus now (negative = past).
     */
    fun countdown(ms: Long): String {
        if (ms < 0) {
            val late = -ms
            return when {
                late >= DAY -> "${late / DAY}d late"
                late >= HOUR -> "${late / HOUR}h late"
                else -> "${maxOf(1, late / 60_000)}m late"
            }
        }
        val d = ms / DAY
        val h = ms % DAY / HOUR
        val m = ms % HOUR / 60_000
        return when {
            d >= 1 -> "${d}d ${h}h"
            h >= 1 -> "${h}h ${m}m"
            m >= 1 -> "${m}m"
            else -> "now"
        }
    }

    /** "Wed 14 Oct, 2:00 pm" */
    fun dueShort(ms: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.getDefault())).replace("AM", "am").replace("PM", "pm")

    /** The emoji for a kind of course item. */
    fun updateEmoji(kind: String) = when (kind) {
        "mod_forum" -> "📣"; "mod_assign" -> "📝"; "mod_quiz" -> "❓"; "moodle", "core" -> "🔔"; else -> if ("grade" in kind) "🏅" else "🔔"
    }

    /** Moodle names a course by its long code ("Gr- BBA .../ EESU1117/2026-2027/Odd/409573"); say its real name instead. */
    fun updateText(text: String, courses: List<LmsCourse>): String =
        courses.filter { it.shortName.isNotBlank() }.fold(text) { t, c -> t.replace(c.shortName, titleCase(c.name)) }

    /** "just now", "25 min ago", "3 h ago", "2 days ago" */
    fun ago(ms: Long): String {
        val m = ms / 60_000
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 24 * 60 -> "${m / 60} h ago"; m < 48 * 60 -> "yesterday"; else -> "${m / (24 * 60)} days ago" }
    }

    fun emoji(type: String) = when (type) {
        "resource" -> "📄"; "url" -> "🔗"; "assign" -> "📝"; "quiz" -> "❓"; "wiki" -> "📚"; "forum" -> "💬"
        "page" -> "📃"; "folder" -> "📁"; else -> "📎"
    }
}
