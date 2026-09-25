package com.rishabh.clockdown

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import androidx.core.graphics.ColorUtils

// Card palette shared by the app and the widgets (colors.xml card0..7, drawables widget_bg_0..7).
private val CARD_COLORS = intArrayOf(R.color.card0, R.color.card1, R.color.card2, R.color.card3, R.color.card4, R.color.card5, R.color.card6, R.color.card7)
val WIDGET_BGS = intArrayOf(
    R.drawable.widget_bg_0, R.drawable.widget_bg_1, R.drawable.widget_bg_2, R.drawable.widget_bg_3,
    R.drawable.widget_bg_4, R.drawable.widget_bg_5, R.drawable.widget_bg_6, R.drawable.widget_bg_7,
)
val WIDGET_GRADS = IntArray(8) { arrayOf(R.drawable.widget_grad_0, R.drawable.widget_grad_1, R.drawable.widget_grad_2, R.drawable.widget_grad_3,
    R.drawable.widget_grad_4, R.drawable.widget_grad_5, R.drawable.widget_grad_6, R.drawable.widget_grad_7)[it] }
val WIDGET_OUTLINES = IntArray(8) { arrayOf(R.drawable.widget_outline_0, R.drawable.widget_outline_1, R.drawable.widget_outline_2, R.drawable.widget_outline_3,
    R.drawable.widget_outline_4, R.drawable.widget_outline_5, R.drawable.widget_outline_6, R.drawable.widget_outline_7)[it] }
val PALETTE_SIZE get() = CARD_COLORS.size

private const val HOUR = 3_600_000L
private const val DAYS = 24 * HOUR

private fun Event.hash() = if (source == AMIZONE) courseCode.hashCode() else id

/**
 * The event's own colour, else (for classes) the user's class default, else a stable pick: a course keeps one colour all
 * week, a timer keeps its own. Reading [Defaults.version] makes composables redraw when the class default changes.
 */
fun Event.colorIndex(): Int {
    Defaults.version
    val chosen = colorIdx ?: if (source == AMIZONE) Defaults.classColor else null
    return chosen?.coerceIn(0, CARD_COLORS.size - 1) ?: Math.floorMod(hash(), CARD_COLORS.size)
}

/** 0 = ring, 1 = dots, 2 = bars, 3 = none. Same rule as [colorIndex]. */
fun Event.styleIndex(): Int {
    Defaults.version
    val chosen = progressStyle ?: if (source == AMIZONE) Defaults.classProgress else null
    return chosen?.coerceIn(0, 3) ?: Math.floorMod(hash() + 1, 3)
}

val EMOJIS = listOf(
    "⏰", "📌", "🎓", "📚", "🧠", "💻", "📊", "🎯", "🚀", "🌱",
    "🎉", "🎂", "🏖️", "✈️", "🏋️", "🏃", "🍕", "🎬", "🎮", "🎵",
    "💼", "🩺", "💊", "🛒", "📞", "❤️", "🏠", "🚗", "⚽", "📷",
    "💡", "🔥", "⭐", "🎁", "🧘", "🍽️", "🌙", "☕", "📝", "🗓️",
)

/**
 * Word beginnings that suggest an emoji for a class, for any course of study (not one person's timetable).
 * Matched at the start of a word, so "ART" would not fire inside "PARTNERSHIP"; the first match wins.
 */
private val SUBJECT_EMOJI: List<Pair<List<String>, String>> = listOf(
    listOf("COMPUT", "PROGRAM", "SOFTWARE", "INTERNET", "WEB", "DATA", "CODING", "ALGORITHM", "INFORMATION", "NETWORK", "CYBER") to "💻",
    listOf("MATH", "CALCUL", "ALGEBRA", "STATISTIC", "PROBABIL", "GEOMETR") to "📐",
    listOf("PHYSIC", "MECHANIC", "THERMO", "OPTICS", "QUANTUM") to "⚛️",
    listOf("CHEM") to "🧪",
    listOf("BIO", "LIFE SCIENCE", "GENETIC", "ZOOLOG", "BOTAN", "MICROBI") to "🧬",
    listOf("ACCOUNT", "FINANC", "ECONOMIC", "BANK", "AUDIT", "TAX", "INVEST") to "📊",
    listOf("MARKET", "BRAND", "ADVERTIS", "SALES", "CONSUMER") to "📣",
    listOf("ENTREPRENEUR", "STARTUP", "INNOVAT", "VENTURE") to "🚀",
    listOf("MANAGEMENT", "LEADERSHIP", "STRATEG", "ORGANI", "HUMAN RESOURCE", "OPERATIONS") to "🧭",
    listOf("COMMUNICATION", "ENGLISH", "LANGUAGE", "LITERATURE", "WRITING", "SPEAKING", "HINDI", "PRESENTATION") to "🗣️",
    listOf("ENVIRON", "ECOLOG", "SUSTAINAB", "CLIMATE") to "🌱",
    listOf("LAW", "LEGAL", "POLIT", "CONSTITUTION", "ETHICS") to "⚖️",
    listOf("HISTORY", "CULTURE", "SOCIOLOG", "GEOGRAPH", "SOCIETY") to "🌍",
    listOf("PSYCHOLOG", "BEHAVIOUR", "BEHAVIOR", "SELF", "GOAL", "PERSONALITY", "MINDFUL", "WELLBEING") to "🧠",
    listOf("DESIGN", "DRAWING", "MEDIA", "FILM", "MUSIC", "PHOTOGRAPH", "CREATIVE") to "🎨",
    listOf("LAB", "PRACTICAL", "WORKSHOP", "EXPERIMENT") to "🔬",
    listOf("SPORT", "YOGA", "FITNESS", "PHYSICAL EDUCATION") to "🏃",
    listOf("MEDICAL", "ANATOM", "HEALTH", "NURSING", "PHARMA") to "🩺",
    listOf("ENGINEERING", "CIRCUIT", "ELECTRO", "ELECTRIC", "ROBOT") to "⚙️",
)

private val FALLBACK_EMOJI = listOf("📘", "📗", "📙", "📕", "📓", "🎓")

/** An emoji for the event: the one chosen in the editor, else (for classes) one suggested by the course title, else a pin. */
fun Event.emojiOrDefault(): String = emoji ?: if (source == AMIZONE) {
    val n = name.uppercase()
    SUBJECT_EMOJI.firstOrNull { (words, _) -> words.any { Regex("\\b" + Regex.escape(it)).containsMatchIn(n) } }?.second
        ?: FALLBACK_EMOJI[Math.floorMod(courseCode.hashCode(), FALLBACK_EMOJI.size)] // stable per course, so it doesn't flicker
} else "📌"

private val SMALL_WORDS = setOf("and", "or", "of", "in", "on", "for", "to", "the", "a", "an", "at", "by", "with")
private val ROMAN = Regex("^(I|II|III|IV|V|VI|VII|VIII|IX|X)$")

/**
 * "GOAL SETTING AND TIME MANAGEMENT-I (VAC-III)" -> "Goal Setting and Time Management-I (VAC-III)".
 * Only ALL-CAPS text is converted (anything already mixed-case is left alone); Roman numerals and short tags
 * inside brackets keep their capitals.
 */
fun titleCase(s: String): String {
    if (s.any { it.isLowerCase() }) return s
    val out = StringBuilder()
    var depth = 0
    var first = true
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '(') depth++ else if (c == ')') depth = maxOf(0, depth - 1)
        if (!c.isLetter()) { out.append(c); i++; continue }
        var j = i
        while (j < s.length && s[j].isLetter()) j++
        val w = s.substring(i, j)
        out.append(
            when {
                ROMAN.matches(w) -> w
                depth > 0 && w.length <= 4 -> w
                !first && w.lowercase() in SMALL_WORDS -> w.lowercase()
                else -> w.lowercase().replaceFirstChar { it.uppercase() }
            },
        )
        first = false
        i = j
    }
    return out.toString()
}

/** Display name: the portal sends course names in ALL CAPS, which is shouted at you on every card. */
fun Event.title() = if (source == AMIZONE) titleCase(name) else name

fun cardColor(ctx: Context, index: Int) = ctx.getColor(CARD_COLORS[index])

@Composable
fun paletteColor(index: Int) = colorResource(CARD_COLORS[index])

private const val DARK_TEXT = 0xFF1B2B34.toInt()

/** Whichever of dark or white text has the higher contrast ratio on [bg] (WCAG), rather than a luminance guess. */
fun onColor(bg: Int) = if (ColorUtils.calculateContrast(DARK_TEXT, bg) >= ColorUtils.calculateContrast(Color.WHITE, bg)) DARK_TEXT else Color.WHITE

fun leftText(ms: Long): String {
    val days = ms / DAYS
    val hours = ms % DAYS / HOUR
    val minutes = ms % HOUR / 60_000
    return when {
        ms <= 0 -> "Now"
        days == 1L -> "1 day left"
        days > 1 -> "$days days left"
        hours > 0 -> "${hours}h ${minutes}m left"
        else -> "${minutes}m left"
    }
}

/** Fills as the event approaches over a 7-day horizon (events don't store a creation time to measure from). */
fun progress(ms: Long) = (1f - ms.toFloat() / (7 * DAYS)).coerceIn(0f, 1f)
