@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.rishabh.clockdown

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The attendance on screen, for the class cards that show whether today's classes were marked. Null before the first load. */
val LocalAttendance = staticCompositionLocalOf<AttSnapshot?> { null }

internal val GREEN = Color(0xFF57B947)
internal val YELLOW = Color(0xFFE9A92E)
internal val RED = Color(0xFFE9604A)

internal fun zoneColor(z: Zone) = when (z) { Zone.GREEN -> GREEN; Zone.YELLOW -> YELLOW; Zone.RED -> RED }
private fun zoneWord(z: Zone) = when (z) { Zone.GREEN -> "green"; Zone.YELLOW -> "yellow"; Zone.RED -> "red" }

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

private fun Event.local() = Instant.ofEpochMilli(startMillis).atZone(zone)
private fun Event.at() = local().format(TIME).lowercase()

/** "Today 2:01 pm", "Tomorrow 9:30 am", "Fri 9:30 am". */
private fun whenText(e: Event, today: LocalDate): String = e.local().toLocalDate().let { d ->
    when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> d.format(DAY) } + " " + e.at()
}

/** The line that matters for a subject: how many to attend to reach the target, or how many can be missed. */
internal fun message(ctx: Context, c: CourseAtt): String {
    if (!c.tracked) return "Not tracked by Amizone"
    if (c.total == 0) return "No classes marked yet"
    val t = ctx.attTarget
    return if (c.attended * 100 < t * c.total) AttMath.toReach(c.attended, c.total, t).let { "Attend the next $it class${if (it == 1) "" else "es"} to reach $t%" }
    else AttMath.canMiss(c.attended, c.total, t).let { if (it == 0) "You can't miss a class and stay above $t%" else "You can miss $it class${if (it == 1) "" else "es"} and stay above $t%" }
}

@Composable
fun AttendanceTab(
    snap: AttSnapshot?, events: List<Event>, connected: Boolean, expired: Boolean, failure: String?,
    refreshing: Boolean, onRefresh: () -> Unit, onReconnect: () -> Unit,
) {
    val ctx = LocalContext.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var leaveOpen by rememberSaveable { mutableStateOf(false) }
    var whatIf by rememberSaveable { mutableStateOf(false) }
    val chosen = snap?.courses?.firstOrNull { it.code == selected }
    val now = System.currentTimeMillis()
    val today = LocalDate.now(zone)
    val classes = events.filter { it.source == AMIZONE }

    if (snap != null && leaveOpen) { BackHandler { leaveOpen = false }; LeavePlanner(snap, classes, now, today) { leaveOpen = false }; return }
    if (snap != null && chosen != null) { BackHandler { selected = null }; SubjectScreen(chosen, snap, classes, now, today) { selected = null }; return }

    // "If I skip today's classes": today's classes not marked yet count as missed. Marked ones already count.
    val todays = classes.filter { it.local().toLocalDate() == today }
    val skipped = if (whatIf && snap != null) todays.filter { snap.markFor(it) == ClassMark.NOT_MARKED }.groupingBy { it.courseCode?.lowercase() }.eachCount() else emptyMap()
    fun extra(c: CourseAtt) = skipped[c.code.lowercase()] ?: 0

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text("Attendance", style = MaterialTheme.typography.headlineMedium) }) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Problems first, in plain words. The numbers below always stay: the last ones we confirmed.
                if (expired) item {
                    Notice("Amizone sign-in needed", "Showing your last known attendance. Tap to reconnect Amizone.", "Reconnect Amizone", onReconnect)
                } else if (failure == "format") item {
                    Notice("Attendance couldn't be read right now", "Amizone's page may have changed. Showing the last numbers we could read. Try again later.", null, null)
                } else if (failure != null && snap != null) item {
                    Notice("Couldn't refresh", "Showing the last numbers from ${AttendanceSync.updatedText(snap.updatedAt)}.", null, null)
                }

                if (snap == null) {
                    item { EmptyAttendance(connected, expired, failure, onRefresh) }
                    return@LazyColumn
                }

                item { Overview(snap, extra = snap.counted.sumOf { extra(it) }) }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Stay above", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TargetChips(ctx.attTarget) { ctx.prefs.edit().putInt("attTarget", it).apply(); refreshWidgets(ctx) }
                    }
                }

                item {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("If I skip today's classes", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (!whatIf) "See how each subject would change"
                                        else if (skipped.isEmpty()) "Nothing left to skip: every class today is already marked."
                                        else "Counting ${skipped.values.sum()} class${if (skipped.values.sum() == 1) "" else "es"} not marked yet as missed.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(whatIf, { whatIf = it })
                            }
                            Row(Modifier.fillMaxWidth().clickable { leaveOpen = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Leave planner", style = MaterialTheme.typography.titleMedium)
                                    Text("Going away? See what each subject would look like", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                val tracked = snap.courses.filter { it.tracked }
                val watch = tracked.filter { it.total > 0 && ctx.zoneOf(it) != Zone.GREEN }
                    .sortedWith(compareByDescending<CourseAtt> { ctx.zoneOf(it).ordinal }.thenBy { it.attended.toDouble() / it.total })
                val rest = tracked.filter { it !in watch }.sortedBy { it.name }

                if (watch.isNotEmpty()) {
                    item(key = "h-watch") { SectionLabel("Needs attention", "${watch.size}") }
                    items(watch, key = { it.code }) { c -> CourseCard(c, classes, now, today, extra(c), whatIf) { selected = c.code } }
                }
                if (rest.isNotEmpty()) {
                    item(key = "h-rest") { SectionLabel(if (watch.isEmpty()) "Subjects" else "The rest", "${rest.size}") }
                    items(rest, key = { it.code }) { c -> CourseCard(c, classes, now, today, extra(c), whatIf) { selected = c.code } }
                }

                val untracked = snap.courses.filter { !it.tracked }
                if (untracked.isNotEmpty()) {
                    item(key = "h-untracked") { SectionLabel("Not tracked", "${untracked.size}") }
                    items(untracked, key = { "u" + it.code }) { c ->
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(titleCase(c.name), style = MaterialTheme.typography.titleSmall)
                                Text("Amizone doesn't track attendance for this course, so it isn't counted anywhere above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun refreshWidgets(ctx: Context) { kotlin.concurrent.thread { Scheduler.refreshWidget(ctx) } }

@Composable
internal fun TargetChips(current: Int, onPick: (Int) -> Unit) {
    var picked by remember(current) { mutableStateOf(current) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(75, 80, 85).forEach { v -> FilterChip(picked == v, { picked = v; onPick(v) }, label = { Text("$v%") }) }
    }
}

@Composable
private fun SectionLabel(title: String, count: String) {
    Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(8.dp))
        Text(count, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 1.dp))
    }
}

@Composable
private fun Notice(title: String, body: String, action: String?, onAction: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.tertiaryContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        if (action != null && onAction != null) Button(onClick = onAction, Modifier.padding(top = 6.dp)) { Text(action) }
    }
}

@Composable
private fun EmptyAttendance(connected: Boolean, expired: Boolean, failure: String?, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📋", fontSize = 56.sp)
        Text(
            if (!connected) "Sign in to Amizone to see your attendance" else if (failure == "format") "Attendance couldn't be read right now" else "No attendance yet",
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp),
        )
        if (connected && !expired) {
            Text("Pull down to load it from Amizone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Button(onClick = onRefresh) { Text("Load attendance") }
        }
    }
}

@Composable
private fun Overview(snap: AttSnapshot, extra: Int) {
    val ctx = LocalContext.current
    val a = snap.attended
    val t = snap.total
    val z = AttMath.zone(a, t, ctx.attGreen, ctx.attYellow)
    val counts = snap.counted.groupingBy { ctx.zoneOf(it) }.eachCount()
    Surface(shape = MaterialTheme.shapes.extraLarge, color = zoneColor(z).copy(alpha = 0.16f)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("OVERALL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (t == 0) "–" else AttMath.percent(a, t) + "%", style = MaterialTheme.typography.displayMedium, color = zoneColor(z))
                if (extra > 0 && t > 0) {
                    val z2 = AttMath.zone(a, t + extra, ctx.attGreen, ctx.attYellow)
                    Text("  →  ${AttMath.percent(a, t + extra)}%", style = MaterialTheme.typography.headlineSmall, color = zoneColor(z2), modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Text(if (t == 0) "No classes marked yet" else "$a of $t classes attended", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Zone.entries.forEach { zz -> ZoneCount(zz, counts[zz] ?: 0) }
            }
            Text("Updated ${AttendanceSync.updatedText(snap.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun ZoneCount(z: Zone, n: Int) {
    Row(
        Modifier.clip(CircleShape).background(zoneColor(z).copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(9.dp).clip(CircleShape).background(zoneColor(z)))
        Spacer(Modifier.width(6.dp))
        Text("$n ${zoneWord(z)}", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun CourseCard(c: CourseAtt, events: List<Event>, now: Long, today: LocalDate, extra: Int, whatIf: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val z = ctx.zoneOf(c)
    val next = events.filter { it.courseCode.equals(c.code, true) && it.startMillis > now }.minByOrNull { it.startMillis }
    Surface(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c.total > 0) Spacer(Modifier.size(10.dp).clip(CircleShape).background(zoneColor(z)))
                    if (c.total > 0) Spacer(Modifier.width(8.dp))
                    Text(titleCase(c.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Text(c.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(6.dp))
                Text(message(ctx, c), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Next class: " + (next?.let { whenText(it, today) } ?: "none in your timetable this week"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
                if (whatIf && c.total > 0) {
                    val z2 = AttMath.zone(c.attended, c.total + extra, ctx.attGreen, ctx.attYellow)
                    Text(
                        if (extra == 0) "Skipping today changes nothing here" else "If you skip today: ${AttMath.percent(c.attended, c.total)}% → ${AttMath.percent(c.attended, c.total + extra)}%" +
                            if (z2 != z) " (${zoneWord(z2)})" else "",
                        style = MaterialTheme.typography.bodyMedium, color = if (extra == 0) MaterialTheme.colorScheme.onSurfaceVariant else zoneColor(z2),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(if (c.total == 0) "–" else AttMath.percent(c.attended, c.total) + "%", style = MaterialTheme.typography.headlineSmall, color = if (c.total == 0) MaterialTheme.colorScheme.onSurfaceVariant else zoneColor(z))
                Text("${c.attended}/${c.total}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One subject: a recovery plan if it is below the target, otherwise how much room there is; then its session history. */
@Composable
private fun SubjectScreen(c: CourseAtt, snap: AttSnapshot, events: List<Event>, now: Long, today: LocalDate, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val target = ctx.attTarget
    val below = c.total > 0 && c.attended * 100 < target * c.total
    val plan = if (below) AttendancePlan.recovery(c, target, AttendancePlan.attendable(snap, c, events, now)) else null
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(titleCase(c.name), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = zoneColor(ctx.zoneOf(c)).copy(alpha = 0.16f)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text(AttMath.percent(c.attended, c.total) + "%", style = MaterialTheme.typography.displaySmall, color = zoneColor(ctx.zoneOf(c)))
                        Text("${c.attended} of ${c.total} classes attended", style = MaterialTheme.typography.bodyMedium)
                        Text(message(ctx, c), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            if (plan != null) item { RecoveryPlan(plan, target, today) }
            item { SectionLabel("Session history", "${c.records.size}") }
            if (c.records.isEmpty()) item {
                Text(
                    if (c.cid == null) "Amizone doesn't give a session list for this course." else "No sessions marked yet.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(c.records, key = { it.key }) { r ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.date.format(DAY), style = MaterialTheme.typography.titleSmall)
                            if (r.slots.isNotEmpty()) Text(r.slots.joinToString(" · ") { it.replace("-", "–") }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val n = maxOf(r.present, r.absent)
                        val (label, color) = when (r.mark) {
                            Mark.PRESENT -> "Present" to GREEN
                            Mark.OD -> "OD" to GREEN
                            Mark.ABSENT -> "Absent" to RED
                            null -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(label + if (n > 1) " ×$n" else "", style = MaterialTheme.typography.labelLarge, color = color)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryPlan(plan: AttendancePlan.Recovery, target: Int, today: LocalDate) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recovery plan", style = MaterialTheme.typography.titleLarge)
            Text("Attend the next ${plan.needed} class${if (plan.needed == 1) "" else "es"} in a row to reach $target%.", style = MaterialTheme.typography.bodyMedium)
            if (plan.classes.isEmpty()) {
                Text("Your timetable has no more classes for this subject in the coming week, so there's nothing to plan with yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    plan.classes.forEach { e ->
                        Text(whenText(e, today), style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                if (plan.reachedOn != null) {
                    Text("Attend all of them and you reach $target% on ${plan.reachedOn.format(DAY)}.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "Your timetable only has ${plan.classes.size} more class${if (plan.classes.size == 1) "" else "es"} for this subject (it covers about a week), " +
                            "so you can't get back to $target% from it alone. Attending all ${plan.classes.size} takes you to ${plan.percentAfterAll}%; you'll need ${plan.shortBy} more after that, once the timetable shows them.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Pick days off; see which classes that misses and where each subject ends up. */
@Composable
private fun LeavePlanner(snap: AttSnapshot, events: List<Event>, now: Long, today: LocalDate, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var picked by rememberSaveable { mutableStateOf("") } // epoch days, comma separated (saveable on rotation)
    val days = picked.split(",").mapNotNull { it.toLongOrNull() }.map { LocalDate.ofEpochDay(it) }.toSet()
    val target = ctx.attTarget
    val result = if (days.isEmpty()) emptyList() else AttendancePlan.leave(snap, events, days, now, today, target, ctx.attGreen, ctx.attYellow)
    val horizon = today.plusDays(TIMETABLE_DAYS.toLong())
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Leave planner", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Which days will you miss?", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0L..27L).map { today.plusDays(it) }.forEach { d ->
                        FilterChip(
                            d in days,
                            { picked = (if (d in days) days - d else days + d).joinToString(",") { it.toEpochDay().toString() } },
                            label = { Text((if (d.isAfter(horizon)) "~ " else "") + d.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))) },
                        )
                    }
                }
            }
            if (days.any { it.isAfter(horizon) }) item {
                Text(
                    "Days marked ~ are beyond your synced timetable (it covers until ${horizon.format(DAY)}). Those are estimated from the same weekday this week, so they can be wrong around holidays.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (days.isEmpty()) item { Text("Tap one or more days to see what you'd miss.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else if (result.isEmpty()) item { Text("No classes on those days, so attendance wouldn't change.", style = MaterialTheme.typography.bodyLarge) }
            items(result, key = { it.course.code }) { m ->
                val c = m.course
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(titleCase(c.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text("${AttMath.percent(c.attended, c.total)}% → ${m.percentAfter}%", style = MaterialTheme.typography.titleMedium, color = zoneColor(m.after))
                        }
                        Text("You'd miss ${m.classes.size} class${if (m.classes.size == 1) "" else "es"}: " + m.classes.joinToString(", ") { whenText(it, today) }, style = MaterialTheme.typography.bodyMedium)
                        Text("${zoneWord(m.before).replaceFirstChar { it.uppercase() }} → ${zoneWord(m.after)}" + if (m.estimated) " (estimated)" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (m.dropsBelowTarget) Text("⚠ Drops below $target%", style = MaterialTheme.typography.titleSmall, color = RED)
                        else if (m.staysBelowTarget) Text("Already below $target%, and this takes it lower", style = MaterialTheme.typography.titleSmall, color = RED)
                    }
                }
            }
        }
    }
}

/** Present / Absent / Not marked yet under a class. Shown only once attendance is known for that course. */
@Composable
fun MarkLabel(mark: ClassMark?, modifier: Modifier = Modifier) {
    if (mark == null) return
    val (text, color) = when (mark) {
        ClassMark.PRESENT -> "✓ Present" to GREEN
        ClassMark.OD -> "✓ Present (OD)" to GREEN
        ClassMark.ABSENT -> "✗ Absent" to RED
        ClassMark.NOT_MARKED -> "Not marked yet" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier)
}
