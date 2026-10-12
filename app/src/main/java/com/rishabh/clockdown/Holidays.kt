package com.rishabh.clockdown

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One holiday as Amizone lists it (its calendar feed marks them sType "H"). */
data class Holiday(val date: LocalDate, val name: String)

private val FEED_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd hh:mm:ss a", Locale.US)

/** The holidays in a GetDiaryEvents array, one per day (a multi-day entry is spread over its days), sorted. Throws on a malformed holiday row. */
fun parseHolidays(arr: JSONArray): List<Holiday> = (0 until arr.length()).map { arr.getJSONObject(it) }
    .filter { it.optString("sType") == "H" }
    .flatMap { o ->
        val name = o.getString("title").replace(Regex("\\s+"), " ").trim()
        val from = LocalDateTime.parse(o.getString("start"), FEED_TIME).toLocalDate()
        val to = LocalDateTime.parse(o.getString("end"), FEED_TIME).toLocalDate().coerceIn(from, from.plusDays(30))
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { Holiday(it, name) }.toList()
    }.distinct().sortedWith(compareBy({ it.date }, { it.name }))

private fun LocalDate.coerceIn(lo: LocalDate, hi: LocalDate) = if (isBefore(lo)) lo else if (isAfter(hi)) hi else this

/** The last synced holidays, kept in the ordinary prefs so they show offline. */
object HolidayStore {
    private const val KEY = "holidays"
    fun load(ctx: Context): List<Holiday> = try {
        val arr = JSONArray(ctx.prefs.getString(KEY, null) ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Holiday(LocalDate.parse(it.getString("d")), it.getString("n")) }
    } catch (e: Exception) { emptyList() }

    fun updatedAt(ctx: Context) = ctx.prefs.getLong("holidaysAt", 0)

    fun save(ctx: Context, list: List<Holiday>) {
        val json = JSONArray(list.map { JSONObject().put("d", it.date.toString()).put("n", it.name) })
        ctx.prefs.edit().putString(KEY, json.toString()).putLong("holidaysAt", System.currentTimeMillis()).apply()
    }

    fun clear(ctx: Context) { ctx.prefs.edit().remove(KEY).remove("holidaysAt").apply() }
}

/** Holidays ride along on the timetable sync: ONE extra request, at most every 6 hours (unless forced). Never touches the timetable. */
object HolidaySync {
    private const val GAP_MS = 6 * 3_600_000L

    /** Quietly keeps what it has on any failure: a dead sign-in is the timetable sync's business, not ours. */
    fun run(ctx: Context, cookies: String, ua: String, force: Boolean) {
        if (!force && System.currentTimeMillis() - HolidayStore.updatedAt(ctx) < GAP_MS) return
        try {
            val first = LocalDate.now(AMIZONE_ZONE).withDayOfMonth(1)
            val r = AmizoneSync.fetch("/Calendar/home/GetDiaryEvents", mapOf("start" to first.toString(), "end" to first.plusMonths(12).toString()), cookies, ua, xhr = true, json = true)
            if (SessionCheck.classify(r.code, r.contentType, r.body) != Verdict.OK) return
            HolidayStore.save(ctx, parseHolidays(JSONArray(SessionCheck.trimBody(r.body))))
        } catch (e: Exception) { /* offline or a changed format: the last synced holidays stay */ }
    }
}

/** "Today", "Tomorrow", "in 12 days". */
fun daysAwayText(from: LocalDate, to: LocalDate): String = when (val d = ChronoUnit.DAYS.between(from, to)) {
    0L -> "Today"; 1L -> "Tomorrow"; else -> "in $d days"
}

/** The next holiday from [today] on, for the Home card's one-liner. */
fun nextHoliday(list: List<Holiday>, today: LocalDate) = list.firstOrNull { !it.date.isBefore(today) }

/** On the home screen: the next holiday at a glance. Opens the Holidays screen. */
@Composable
fun HolidaysCard(list: List<Holiday>, onClick: () -> Unit) {
    val today = LocalDate.now(AMIZONE_ZONE)
    val next = nextHoliday(list, today)
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick).padding(16.dp)) {
        Text("Holidays", style = MaterialTheme.typography.titleMedium)
        Text(
            if (next != null) "${next.name}, ${daysAwayText(today, next.date).replaceFirstChar { it.lowercase() }}" else if (list.isEmpty()) "Tap to see upcoming holidays" else "No more holidays listed",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HolidaysScreen(holidays: List<Holiday>, updatedAt: Long, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val today = LocalDate.now(AMIZONE_ZONE)
    val byDate = remember(holidays) { holidays.groupBy { it.date } }
    val upcoming = remember(holidays) { holidays.filter { !it.date.isBefore(today) } }
    val first = YearMonth.from(today).minusMonths(1) // one month back, so the month just gone can still be looked at
    val pager = rememberPagerState(initialPage = 1) { 14 }
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable { mutableStateOf<String?>(null) } // an ISO date
    LaunchedEffect(pager.currentPage) { picked = null } // the banner belongs to the month it was tapped in

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Holidays", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val month = first.plusMonths(pager.currentPage.toLong())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                    Text(
                        "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                        Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(13)) } }) { Text("›", style = MaterialTheme.typography.headlineSmall) }
                }
            }
            item {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        for (d in 0..6) Text(
                            DayOfWeek.MONDAY.plus(d.toLong()).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalPager(pager, Modifier.fillMaxWidth().height(264.dp)) { page ->
                        MonthGrid(first.plusMonths(page.toLong()), today, byDate.keys, picked) { picked = if (picked == it.toString()) null else it.toString() }
                    }
                }
            }
            item {
                val day = picked?.let { LocalDate.parse(it) }
                val names = day?.let { byDate[it] }.orEmpty()
                if (names.isNotEmpty()) Text(
                    "${day!!.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))}: ${names.joinToString { it.name }}",
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            item { Text("Upcoming", style = MaterialTheme.typography.titleMedium) }
            if (upcoming.isEmpty()) item {
                Text(
                    if (holidays.isEmpty()) "No holidays yet. They load with your timetable once you're signed in to Amizone." else "No more holidays listed by Amizone.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(upcoming) { h ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { picked = h.date.toString() }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(h.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            h.date.format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.getDefault())),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(daysAwayText(today, h.date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                Text(
                    "Holidays from Amizone. Last updated ${AttendanceSync.updatedText(updatedAt)}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, today: LocalDate, holidays: Set<LocalDate>, picked: String?, onTap: (LocalDate) -> Unit) {
    val lead = month.atDay(1).dayOfWeek.value - 1 // Monday first
    Column(Modifier.fillMaxWidth()) {
        for (row in 0 until 6) Row(Modifier.fillMaxWidth().height(44.dp)) {
            for (col in 0..6) {
                val n = row * 7 + col - lead + 1
                Box(Modifier.weight(1f).fillMaxSize().padding(3.dp), contentAlignment = Alignment.Center) {
                    if (n in 1..month.lengthOfMonth()) {
                        val date = month.atDay(n)
                        val holiday = date in holidays
                        val scheme = MaterialTheme.colorScheme
                        Box(
                            Modifier.aspectRatio(1f).fillMaxSize().clip(CircleShape)
                                .then(if (holiday) Modifier.background(scheme.primary) else Modifier)
                                .then(if (holiday && date.toString() == picked) Modifier.border(3.dp, scheme.onSurface, CircleShape) else if (date == today) Modifier.border(1.5.dp, scheme.primary, CircleShape) else Modifier)
                                .then(if (holiday) Modifier.clickable { onTap(date) } else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$n", style = MaterialTheme.typography.bodyMedium,
                                color = if (holiday) scheme.onPrimary else scheme.onSurface,
                                fontWeight = if (holiday) androidx.compose.ui.text.font.FontWeight.Bold else null,
                            )
                        }
                    }
                }
            }
        }
    }
}
