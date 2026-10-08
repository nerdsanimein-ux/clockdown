@file:OptIn(ExperimentalMaterial3Api::class)

package com.rishabh.clockdown

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun courseColor(id: Int) = lmsColor(id)

/**
 * The LMS tab: what is due, the courses, a course's files and links, and an assignment. Read-only: it shows what Moodle says
 * and never changes anything on Moodle (uploading happens on Moodle's own page, see [LmsWebActivity]).
 * Routes are small strings: "due", "courses", "c:<course id>", "a:<assignment id>", stacked with commas for Back.
 */
@Composable
fun LmsTab(
    snap: LmsSnapshot?, connected: Boolean, expired: Boolean, failure: String?, refreshing: Boolean,
    onRefresh: () -> Unit, onReconnect: () -> Unit, onChanged: () -> Unit, openCmid: Int?,
) {
    var route by rememberSaveable { mutableStateOf(if (openCmid != null) "due,a:$openCmid" else "due") }
    val stack = route.split(',')
    val top = stack.last()
    fun push(r: String) { route = "$route,$r" }
    fun pop() { route = stack.dropLast(1).joinToString(",").ifEmpty { "due" } }
    BackHandler(enabled = stack.size > 1) { pop() }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }

    when {
        top.startsWith("a:") && snap != null && snap.assign(top.drop(2).toIntOrNull() ?: -1) != null ->
            AssignmentScreen(snap, snap.assign(top.drop(2).toInt())!!, now, ::pop, onChanged)
        top.startsWith("c:") && snap != null && snap.course(top.drop(2).toIntOrNull() ?: -1) != null ->
            CourseScreen(snap, snap.course(top.drop(2).toInt())!!, ::pop) { push("a:$it") }
        else -> LmsHome(
            snap, connected, expired, failure, refreshing, now, tab = if (top == "courses") "courses" else "due",
            onTab = { route = it }, onRefresh = onRefresh, onReconnect = onReconnect,
            onAssign = { push("a:$it") }, onCourse = { push("c:$it") },
        )
    }
}

@Composable
private fun LmsHome(
    snap: LmsSnapshot?, connected: Boolean, expired: Boolean, failure: String?, refreshing: Boolean, now: Long, tab: String,
    onTab: (String) -> Unit, onRefresh: () -> Unit, onReconnect: () -> Unit, onAssign: (Int) -> Unit, onCourse: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    var submittedOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("LMS", style = MaterialTheme.typography.headlineMedium) },
                actions = { if (connected && snap != null) TextButton(onClick = { LmsWebActivity.open(ctx, "my/courses.php") }) { Text("Open in browser") } },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                problem(connected, expired, failure, snap, onReconnect)
                if (snap == null) { item { Empty(connected, refreshing, failure) }; return@LazyColumn }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(tab == "due", { onTab("due") }, label = { Text("Due soon") })
                        FilterChip(tab == "courses", { onTab("courses") }, label = { Text("Courses") })
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (snap.updatedAt == 0L) "Not read yet" else "Updated ${LmsSync.updatedText(snap.updatedAt)}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (tab == "courses") courses(snap, onCourse) else dueSoon(snap, now, submittedOpen, { submittedOpen = !submittedOpen }, onAssign)
            }
        }
    }
}

/** The notices at the top, in plain words. The data below them always stays: the last that was read cleanly. */
private fun LazyListScope.problem(connected: Boolean, expired: Boolean, failure: String?, snap: LmsSnapshot?, onReconnect: () -> Unit) {
    val last = snap?.updatedAt?.takeIf { it > 0 }?.let { LmsSync.updatedText(it) }
    when {
        !connected -> item { Notice("Sign in to Amizone", "Your LMS comes through your Amizone sign-in. There is no separate LMS password.", "Sign in to Amizone", onReconnect) }
        expired -> item { Notice("Amizone sign-in needed", "Showing your last LMS data${last?.let { ", read $it" } ?: ""}. Tap to reconnect Amizone.", "Reconnect Amizone", onReconnect) }
        failure == "format" -> item { Notice("LMS couldn't be read right now", "Amity's LMS may have changed. Showing the last data that was read cleanly${last?.let { " ($it)" } ?: ""}. Try again later.", null, null) }
        failure == "nolms" -> item { Notice("No LMS found", "Amizone didn't offer an LMS button for this account.", null, null) }
        failure == "network" && snap != null -> item { Notice("Couldn't refresh", "Showing the last data from ${last ?: "earlier"}.", null, null) }
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
private fun Empty(connected: Boolean, refreshing: Boolean, failure: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📝", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            when { !connected -> "Sign in to see your courses and assignments"; refreshing -> "Loading your LMS…"; failure != null -> "Nothing to show yet"; else -> "Pull down to load your LMS" },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

// ---------------------------------------------------------------- Due soon

private fun LazyListScope.dueSoon(snap: LmsSnapshot, now: Long, submittedOpen: Boolean, toggleSubmitted: () -> Unit, onAssign: (Int) -> Unit) {
    val groups = LmsDue.grouped(snap.assigns, now, zone)
    val submitted = LmsDue.submitted(snap.assigns)
    if (groups.isEmpty()) item {
        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (snap.assigns.isEmpty()) "🗒️" else "🎉", style = MaterialTheme.typography.displayMedium)
            Text(if (snap.assigns.isEmpty()) "No assignments yet" else "Nothing left to hand in", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
    for (g in DueGroup.entries) {
        val list = groups[g] ?: continue
        item(key = "h-${g.name}") { GroupLabel(g.label, list.size, if (g == DueGroup.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        items(list, key = { it.cmid }) { a -> AssignRow(a, snap.courseOf(a), now, onClick = { onAssign(a.cmid) }) }
    }
    if (submitted.isNotEmpty()) {
        item(key = "h-submitted") {
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = toggleSubmitted).padding(start = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Submitted", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text("${submitted.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(if (submittedOpen) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (submittedOpen) items(submitted, key = { "s${it.cmid}" }) { a -> AssignRow(a, snap.courseOf(a), now, onClick = { onAssign(a.cmid) }) }
    }
}

@Composable
private fun GroupLabel(title: String, count: Int, color: Color) {
    Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = color)
        Spacer(Modifier.width(8.dp))
        Text("$count", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 1.dp))
    }
}

@Composable
private fun AssignRow(a: LmsAssign, course: LmsCourse?, now: Long, onClick: () -> Unit) {
    val accent = paletteColor(courseColor(a.courseId))
    val left = a.dueMs?.minus(now)
    val late = a.pending && left != null && left < 0
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            EmojiBadge("📝", accent, 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(titleCase(course?.shortName?.ifBlank { null } ?: course?.name ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                StatusChip(a)
            }
            Spacer(Modifier.width(10.dp))
            if (a.dueMs != null && left != null) Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (a.pending) LmsText.countdown(left) else "Done", style = MaterialTheme.typography.titleMedium,
                    color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(LmsText.dueShort(a.dueMs, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Moodle's own words for the submission, coloured by what they mean. Nothing is shown as handed in unless Moodle says so. */
@Composable
private fun StatusChip(a: LmsAssign, modifier: Modifier = Modifier) {
    val (fg, bg) = when (a.status) {
        SubStatus.SUBMITTED -> GREEN to GREEN.copy(alpha = 0.16f)
        SubStatus.DRAFT -> YELLOW to YELLOW.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    }
    Text(
        a.statusText ?: "Checking with Moodle…", style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(MaterialTheme.shapes.small).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

// ---------------------------------------------------------------- Courses

private fun LazyListScope.courses(snap: LmsSnapshot, onCourse: (Int) -> Unit) {
    if (snap.courses.isEmpty()) item { Text("You aren't enrolled in any LMS courses yet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(snap.courses, key = { it.id }) { c ->
        val pending = snap.pendingIn(c.id)
        val items = c.sections.sumOf { it.items.size }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onCourse(c.id) }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiBadge(emojiForCourse(c), paletteColor(courseColor(c.id)), 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(titleCase(c.name), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (c.readAt == 0L) "Not read yet" else "$items item${if (items == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pending > 0) Text(
                    "$pending to do", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun emojiForCourse(c: LmsCourse) = if (c.sections.any { s -> s.items.any { it.type == "assign" } }) "📘" else "🎓"

// ---------------------------------------------------------------- A course

@Composable
private fun CourseScreen(snap: LmsSnapshot, course: LmsCourse, onBack: () -> Unit, onAssign: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(setOf<Int>()) }

    /** A course file: downloaded with the LMS session, then opened (or shared). If only Moodle's own page can show it, it opens there. */
    fun file(item: LmsItem, share: Boolean) {
        if (item.cmid in busy) return
        busy = busy + item.cmid
        scope.launch {
            val f = withContext(Dispatchers.IO) { try { LmsFiles.fetch(ctx, item) } catch (e: Exception) { null } }
            busy = busy - item.cmid
            if (f == null) { Toast.makeText(ctx, "Opening it in Moodle", Toast.LENGTH_SHORT).show(); LmsWebActivity.open(ctx, item.path) }
            else if (share) shareFile(ctx, f) else viewFile(ctx, f)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(titleCase(course.name), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = { LmsWebActivity.open(ctx, "course/view.php?id=${course.id}") }) { Text("Open in browser") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val sections = course.sections.filter { it.items.isNotEmpty() }
            if (course.readAt == 0L) item { Text("This course hasn't been read yet. Go back and pull down to refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else if (sections.isEmpty()) item { Text("Nothing in this course yet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            for (s in sections) {
                item(key = "s${s.number}") { Text(s.title.ifBlank { "Section ${s.number}" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 10.dp)) }
                items(s.items, key = { "i${it.cmid}" }) { item ->
                    val a = snap.assign(item.cmid)
                    ItemRow(item, a, item.cmid in busy,
                        onClick = { when (item.type) { "resource" -> file(item, share = false); "assign" -> if (a != null) onAssign(item.cmid) else LmsWebActivity.open(ctx, item.path); else -> LmsWebActivity.open(ctx, item.path) } },
                        onShare = if (item.type == "resource") ({ file(item, share = true) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: LmsItem, a: LmsAssign?, busy: Boolean, onClick: () -> Unit, onShare: (() -> Unit)?) {
    val kind = when (item.type) {
        "resource" -> "File" + (item.badge?.let { " · $it" } ?: ""); "url" -> "Link · opens in Moodle"; "quiz" -> "Quiz · opens in Moodle"; "wiki" -> "Wiki · opens in Moodle"
        "assign" -> "Assignment"; "forum" -> "Forum · opens in Moodle"; "page" -> "Page · opens in Moodle"; "folder" -> "Folder · opens in Moodle"; else -> "Opens in Moodle"
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(LmsText.emoji(item.type), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (a != null) StatusChip(a, Modifier.padding(top = 4.dp))
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else if (onShare != null) IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share ${item.name}") }
        }
    }
}

private fun mimeOf(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

private fun viewFile(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    catch (e: ActivityNotFoundException) { Toast.makeText(ctx, "No app on this phone can open this file. Try Share.", Toast.LENGTH_LONG).show() }
}

private fun shareFile(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}"))
}

// ---------------------------------------------------------------- An assignment

@Composable
private fun AssignmentScreen(snap: LmsSnapshot, a: LmsAssign, now: Long, onBack: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LmsResult?>(null) }
    var recheck by rememberSaveable { mutableStateOf(false) } // set when the upload page opens: on coming back, ask Moodle again

    fun refresh() {
        if (checking) return
        checking = true
        scope.launch {
            result = withContext(Dispatchers.IO) { LmsSync.refreshAssign(ctx, a.cmid) }
            checking = false
            onChanged()
        }
    }
    LaunchedEffect(a.cmid) { if (System.currentTimeMillis() - a.fetchedAt > 60_000) refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (recheck) { recheck = false; refresh() } }

    val course = snap.courseOf(a)
    val left = a.dueMs?.minus(now)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Assignment", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = { LmsWebActivity.open(ctx, a.path) }) { Text("Open in browser") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(a.name, style = MaterialTheme.typography.headlineSmall)
                course?.let { Text(titleCase(it.name), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Due", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (left != null && a.dueMs != null) {
                        Text(
                            if (!a.pending) "Handed in" else if (left < 0) "${LmsText.countdown(left)}" else "${LmsText.countdown(left)} left",
                            style = MaterialTheme.typography.displaySmall, color = if (a.pending && left < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(a.dueText ?: "No due date", style = MaterialTheme.typography.titleMedium)
                    a.openText?.let { Text("Opened: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What Moodle says", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Fact("Submission status") { if (checking && a.fetchedAt == 0L) Text("Checking with Moodle…", style = MaterialTheme.typography.bodyLarge) else StatusChip(a) }
                    Fact("Grading status") { Text(a.gradingText ?: "–", style = MaterialTheme.typography.bodyLarge) }
                    Fact("Time remaining") { Text(a.remainingText ?: "–", style = MaterialTheme.typography.bodyLarge) }
                    Fact("Last modified") { Text(a.modifiedText ?: "Never", style = MaterialTheme.typography.bodyLarge) }
                    a.comments?.let { Fact("Comments") { Text("$it", style = MaterialTheme.typography.bodyLarge) } }
                    if (a.status == SubStatus.DRAFT) Text(
                        "Moodle shows this as a draft. It is not handed in until you submit it for grading on Moodle.",
                        style = MaterialTheme.typography.bodyMedium, color = YELLOW,
                    )
                    Text(
                        when {
                            checking -> "Checking with Moodle…"
                            result != null && result != LmsResult.OK -> "Couldn't check just now. This is what Moodle said ${if (a.fetchedAt == 0L) "earlier" else "at " + LmsSync.updatedText(a.fetchedAt)}."
                            a.fetchedAt == 0L -> "Not read yet."
                            else -> "As Moodle said at ${LmsSync.updatedText(a.fetchedAt)}."
                        },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (a.intro.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Description", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(a.intro, style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (a.feedback.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Feedback", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    a.feedback.forEach { (label, value) -> Fact(label) { Text(value, style = MaterialTheme.typography.bodyLarge) } }
                }
            }

            Button(onClick = { recheck = true; LmsWebActivity.open(ctx, a.uploadPath) }, Modifier.fillMaxWidth()) { Text("Upload your work") }
            Text(
                "This opens Moodle's own upload page. Nothing is handed in until you press Moodle's Save or Submit button there.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { refresh() }, Modifier.fillMaxWidth(), enabled = !checking) { Text(if (checking) "Checking…" else "Check Moodle again") }
        }
    }
}

@Composable
private fun Fact(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}
