@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.rishabh.clockdown

import android.Manifest
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.AssistChip
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** True while the class timetable on screen could not be confirmed by the latest sync (see AmizoneSync). */
val LocalUnconfirmed = staticCompositionLocalOf { false }

internal val zone: ZoneId get() = ZoneId.systemDefault()
internal fun Long.toLocal(): LocalDateTime = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val DAY_SHORT get() = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private fun shortLeft(ms: Long) = leftText(ms).removeSuffix(" left")

/** "Fri 25 Sep · 9:30 am – 10:25 am · Block A - 101" (the room only when there is one). */
internal fun Event.subtitle(): String {
    val start = startMillis.toLocal()
    val time = start.format(TIME) + (endMillis?.let { " – " + it.toLocal().format(TIME) } ?: "")
    val extra = when {
        room != null -> " · $room"
        source == MANUAL && alarmMinutes > 0 -> " · 🔔 ${alarmMinutes}m"
        else -> ""
    }
    return "${start.format(DAY_SHORT)} · $time$extra"
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val events by remember { dao.all() }.collectAsState(emptyList())
    // Re-evaluated periodically so past events drop off the lists while the app is open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(15_000); value = System.currentTimeMillis() }
    }
    var tick by remember { mutableIntStateOf(0) } // bump to re-read connection state / last sync from prefs
    var syncing by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Event?>(null) } // kept after closing so the exit animation still has content
    var editorOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var pinEvent by remember { mutableStateOf<Event?>(null) } // a timer that was just saved: offer to put it on the home screen
    var addWidgetOpen by remember { mutableStateOf(false) }
    // null while we check whether this is a first run; nothing is asked of the person until then.
    var onboarding by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { onboarding = withContext(Dispatchers.IO) { Onboarding.needed(ctx) } }
    val connected = remember(tick) { ctx.amizoneConnected }

    // Manual = user pressed the sync button (always runs, always reports); otherwise skip if synced under a minute ago.
    fun sync(manual: Boolean) {
        if (!ctx.amizoneConnected || syncing) return
        if (!manual && System.currentTimeMillis() - ctx.prefs.getLong("lastSync", 0) < 60_000) return
        syncing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { AmizoneSync.run(ctx, force = manual) }
            syncing = false
            tick++
            if (manual) Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        Battery.refresh(ctx) // remembers if the exemption is on, so losing it later can be reported
        tick++; sync(manual = false)
        // Cheap: answers from the cache unless the last check is over 12 hours old.
        // An update we already know about is re-checked every time (cheap, conditional), so a withdrawn release loses its badge.
        scope.launch {
            withContext(Dispatchers.IO) {
                UpdateChecker.check(ctx, if (UpdateChecker.available(ctx) != null) CheckMode.REVALIDATE else CheckMode.AUTO)
            }
            tick++
        }
    }
    val updateReady = remember(tick) { UpdateChecker.available(ctx) != null }

    var batteryDialog by remember { mutableStateOf(false) }
    val askBatteryOnce = { if (Battery.shouldAskNow(ctx)) batteryDialog = true }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askBatteryOnce() }
    LaunchedEffect(onboarding) {
        if (onboarding != false) return@LaunchedEffect // permission prompts wait until the welcome flow is done
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else askBatteryOnce()
        withContext(Dispatchers.IO) { Widgets.publishPreviews(ctx) }
    }
    if (batteryDialog) BatteryDialog { batteryDialog = false; tick++ }
    pinEvent?.let { AddTimerWidgetDialog(it) { pinEvent = null } }
    if (addWidgetOpen) AddWidgetDialog { addWidgetOpen = false }

    fun openEditor(e: Event) { editor = e; editorOpen = true }

    // Surface (not a bare Box) so text defaults to onBackground instead of black.
    val batteryRevoked = remember(tick) { Battery.revoked(ctx) }
    val unconfirmedSince = remember(tick) { ctx.unconfirmedSince }
    val expired = remember(tick) { ctx.sessionExpired }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box {
        CompositionLocalProvider(LocalUnconfirmed provides (unconfirmedSince != null)) { HomeScreen(
            events = events, now = now, connected = connected, syncing = syncing,
            expired = expired, unconfirmedSince = unconfirmedSince,
            batteryRevoked = batteryRevoked,
            onBatteryFix = { ctx.startActivity(Battery.requestIntent(ctx)) },
            onBatteryDismiss = { Battery.dismissWarning(ctx); tick++ },
            onSignIn = { ctx.startActivity(Intent(ctx, LoginActivity::class.java)) },
            onSync = { sync(manual = true) },
            onSettings = { settingsOpen = true },
            onAddWidget = { addWidgetOpen = true },
            updateReady = updateReady,
            onNew = {
                // Default: the top of the hour after next, in local time (not UTC, which is off by 30 min in India).
                val start = LocalDateTime.now().plusHours(2).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                openEditor(Event(name = "", startMillis = start.atZone(zone).toInstant().toEpochMilli()))
            },
            onEdit = ::openEditor,
        ) }

        // Fade content out under the status bar so the clock and icons stay readable while scrolling.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 20.dp)
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.background,
                        0.65f to MaterialTheme.colorScheme.background, // solid behind the clock and icons
                        1f to Color.Transparent,
                    ),
                ),
        )

        if (onboarding == true) {
            OnboardingScreen(
                connected = connected,
                onSignIn = { ctx.startActivity(Intent(ctx, LoginActivity::class.java)) },
                onDone = { Onboarding.finish(ctx); onboarding = false; sync(manual = false) },
            )
        }

        val spatial = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.85f, stiffness = 380f)
        AnimatedVisibility(
            editorOpen,
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it / 3 } + fadeIn(),
            exit = slideOutVertically(spring(stiffness = 500f)) { it / 3 } + fadeOut(),
        ) {
            editor?.let { e ->
                EditScreen(
                    event = e,
                    onClose = { editorOpen = false },
                    onSave = { saved ->
                        editorOpen = false
                        scope.launch(Dispatchers.IO) {
                            // The time or alarm may have changed: drop the old alarms first, rescheduleAll sets the new ones.
                            if (saved.id != 0) Scheduler.cancel(ctx, saved.id)
                            val row = dao.upsert(saved) // the new id for an insert, -1 for an update
                            Scheduler.rescheduleAll(ctx)
                            // Only new timers ask; an edited one has its own "Add to home screen" button in the editor.
                            if (saved.id == 0 && row > 0) withContext(Dispatchers.Main) { pinEvent = saved.copy(id = row.toInt()) }
                        }
                    },
                    onAddToHome = { pinEvent = it },
                    onDelete = if (e.id == 0) null else {
                        {
                            editorOpen = false
                            scope.launch(Dispatchers.IO) { Scheduler.cancel(ctx, e.id); dao.delete(e); Scheduler.rescheduleAll(ctx) }
                        }
                    },
                )
            }
        }

        AnimatedVisibility(
            settingsOpen,
            enter = slideInHorizontally(spatial) { it } + fadeIn(),
            exit = slideOutHorizontally(spatial) { it } + fadeOut(),
        ) {
            val lastSync = remember(tick) { ctx.prefs.getLong("lastSync", 0) }
            SettingsScreen(
                connected = connected,
                lastSync = lastSync,
                onBack = { settingsOpen = false; tick++ }, // tick: re-read the update badge
                expired = expired,
                onConnect = { ctx.startActivity(Intent(ctx, LoginActivity::class.java)) },
                onDisconnect = {
                    AmizoneSync.wipeWebView(ctx) // the browser's own cookies, storage and cache (main thread)
                    scope.launch(Dispatchers.IO) { AmizoneSync.signOut(ctx); withContext(Dispatchers.Main) { tick++ } }
                },
            )
        }
    } }
}

@Composable
private fun HomeScreen(
    events: List<Event>, now: Long, connected: Boolean, syncing: Boolean, updateReady: Boolean,
    expired: Boolean, unconfirmedSince: Long?, onSignIn: () -> Unit,
    batteryRevoked: Boolean, onBatteryFix: () -> Unit, onBatteryDismiss: () -> Unit,
    onSync: () -> Unit, onSettings: () -> Unit, onAddWidget: () -> Unit, onNew: () -> Unit, onEdit: (Event) -> Unit,
) {
    val upcoming = events.filter { it.startMillis > now }
    val hero = upcoming.firstOrNull()
    val today = LocalDate.now(zone)
    fun Event.day() = startMillis.toLocal().toLocalDate()
    // Classes (today's one in progress included) drive the timelines and the weekly agenda; only my own events are timers.
    val classes = events.filter { it.source == AMIZONE && (it.endMillis ?: it.startMillis) > now }
    val timers = upcoming.filter { it.source == MANUAL }
    val week = classes.groupBy { it.day() }.toSortedMap()
    var weekOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        // A real bar, not a floating button: content ends above it, so "New" can never cover a card.
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${timers.size} timer${if (timers.size == 1) "" else "s"} · ${classes.size} upcoming classes",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    ExtendedFloatingActionButton(
                        onClick = onNew,
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("New timer", style = MaterialTheme.typography.titleMedium) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(24.dp),
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    )
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            // The list itself starts below the status bar (padding, not contentPadding), so nothing scrolls underneath it.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
        ) {
            val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
            item(key = "header", span = full) { Header(connected, syncing, updateReady, onSync, onSettings) }
            item(key = "add-widget", span = full) {
                AssistChip(onClick = onAddWidget, label = { Text("Add widget to home screen") }, leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) })
            }

            if (batteryRevoked) item(key = "battery", span = full) { BatteryWarning(onBatteryFix, onBatteryDismiss) }

            if (connected && (expired || unconfirmedSince != null)) {
                item(key = "banner", span = full) { TimetableBanner(expired, unconfirmedSince ?: 0L, onSignIn, onSync) }
            }

            if (hero == null) {
                item(key = "empty", span = full) { EmptyState() }
            } else {
                item(key = "hero", span = full) {
                    HeroCard(hero, Modifier.enter(0), onClick = if (hero.source == MANUAL) ({ onEdit(hero) }) else null)
                }
            }

            timeline("Today", today, classes.filter { it.day() == today }, connected, now, "No more classes today")
            timeline("Tomorrow", today.plusDays(1), classes.filter { it.day() == today.plusDays(1) }, connected, now, "No classes tomorrow")

            // My timers: only my own events, soonest first. Classes never appear here.
            item(key = "h-timers", span = full) { SectionHeader("My timers", if (timers.isEmpty()) "" else "${timers.size}") }
            if (timers.isEmpty()) {
                item(key = "e-timers", span = full) {
                    Text(
                        "⏳  Nothing to count down to yet. Tap New timer to add a trip, a deadline, a birthday.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                }
            }
            items(timers, key = { "m${it.id}" }) { e ->
                EventCard(e, now, Modifier.animateItem().enter(timers.indexOf(e) + 2), onClick = { onEdit(e) })
            }

            // This week: a compact agenda of every upcoming class by day, collapsed by default.
            if (classes.isNotEmpty()) {
                item(key = "h-week", span = full) { WeekHeader(classes.size, weekOpen) { weekOpen = !weekOpen } }
                if (weekOpen) {
                    week.forEach { (day, list) ->
                        item(key = "wd$day", span = full) { DayLabel(day, today) }
                        items(list, key = { "wc${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { e -> WeekRow(e, Modifier.animateItem()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "chevron")
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onToggle).padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("This week", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(10.dp))
        Text("$count classes", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand", modifier = Modifier.rotate(turn).size(32.dp))
    }
}

@Composable
private fun DayLabel(day: LocalDate, today: LocalDate) {
    val label = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DAY_SHORT)
    }
    Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

/** One class in the weekly agenda: a compact row, not a card. */
@Composable
private fun WeekRow(e: Event, modifier: Modifier = Modifier) {
    val accent = paletteColor(e.colorIndex())
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(34.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(12.dp))
            Text(e.startMillis.toLocal().format(TIME), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val room = e.room
                if (room != null) Text(room, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (LocalUnconfirmed.current) Text("\u26A0 Unconfirmed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

private fun LazyGridScope.timeline(
    title: String, date: LocalDate, rows: List<Event>, connected: Boolean, now: Long, emptyText: String,
) {
    if (rows.isEmpty() && !connected) return
    item(key = "h-$title", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(title, date.format(DAY_SHORT)) }
    if (rows.isEmpty()) {
        item(key = "e-$title", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "🎉  $emptyText", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
    }
    items(rows, key = { "t$title${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { e ->
        TimelineRow(e, now, first = e == rows.first(), last = e == rows.last(), modifier = Modifier.animateItem())
    }
}

@Composable
private fun Header(connected: Boolean, syncing: Boolean, updateReady: Boolean, onSync: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Clockdown", style = MaterialTheme.typography.displaySmall)
            Text(
                LocalDate.now(zone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", LocalConfiguration.current.locales[0])),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (connected) {
            FilledTonalIconButton(onClick = onSync, enabled = !syncing, modifier = Modifier.size(48.dp)) {
                if (syncing) LoadingIndicator(Modifier.size(28.dp)) else Icon(Icons.Filled.Refresh, contentDescription = "Sync now")
            }
            Spacer(Modifier.width(8.dp))
        }
        BadgedBox(badge = { if (updateReady) Badge() }) {
            FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = if (updateReady) "Settings, an update is available" else "Settings")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(10.dp))
        Text(detail, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("⏳", fontSize = 64.sp)
        Spacer(Modifier.height(12.dp))
        Text("Nothing coming up", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Tap New to add an event, or connect Amizone in Settings to see your classes.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
        )
    }
}

@Composable
private fun Pill(text: String, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(CircleShape).background(fg.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The next event, big: a live countdown that ticks every second, and a wavy progress ring. */
@Composable
private fun HeroCard(e: Event, modifier: Modifier = Modifier, onClick: (() -> Unit)?) {
    val bg = paletteColor(e.colorIndex())
    val fg = Color(onColor(bg.toArgb()))
    // Aligned to whole seconds so the digits flip exactly when the clock does.
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000 - System.currentTimeMillis() % 1000); value = System.currentTimeMillis() }
    }
    val left = e.startMillis - now
    val p = progress(left)

    Box(modifier.bouncy(onClick).fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(bg)) {
        // A huge, faint copy of the emoji as a watermark.
        Text(e.emojiOrDefault(), fontSize = 150.sp, modifier = Modifier.align(Alignment.BottomEnd).offset(x = 28.dp, y = 34.dp).alpha(0.09f))
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiBadge(e.emojiOrDefault(), fg, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (e.source == AMIZONE) "NEXT CLASS" else "NEXT UP",
                        style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f),
                    )
                    Text(e.title(), style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Box(contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator(
                        progress = { p }, color = fg, trackColor = fg.copy(alpha = 0.25f), modifier = Modifier.size(76.dp),
                        stroke = Stroke(width = with(LocalDensity.current) { 7.dp.toPx() }, cap = StrokeCap.Round),
                        trackStroke = Stroke(width = with(LocalDensity.current) { 7.dp.toPx() }, cap = StrokeCap.Round),
                    )
                    Text("${(p * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = fg)
                }
            }
            Spacer(Modifier.height(18.dp))
            Countdown(left, fg, if (left >= 24 * 3_600_000L) 44.sp else 58.sp)
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val start = e.startMillis.toLocal()
                Pill(start.format(DAY_SHORT), fg)
                Pill("🕘 " + start.format(TIME) + (e.endMillis?.let { " – " + it.toLocal().format(TIME) } ?: ""), fg)
                e.room?.let { Pill("📍 $it", fg) }
                if (e.source == AMIZONE && LocalUnconfirmed.current) Pill("\u26A0 Unconfirmed", fg)
            }
        }
    }
}

/** Timeline entry: time, a rail with a dot, and the class card. */
@Composable
private fun TimelineRow(e: Event, now: Long, first: Boolean, last: Boolean, modifier: Modifier = Modifier) {
    val accent = paletteColor(e.colorIndex())
    val live = now >= e.startMillis
    val rail = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val start = e.startMillis.toLocal()
    Row(modifier.height(IntrinsicSize.Min)) {
        Column(Modifier.width(58.dp).padding(top = 14.dp), horizontalAlignment = Alignment.End) {
            Text(start.format(DateTimeFormatter.ofPattern("h:mm")), style = MaterialTheme.typography.titleMedium)
            Text(start.format(DateTimeFormatter.ofPattern("a")).lowercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.width(30.dp).fillMaxHeight()) {
            val x = size.width / 2
            val dotY = 26.dp.toPx()
            drawLine(rail, Offset(x, if (first) dotY else 0f), Offset(x, if (last) dotY else size.height), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            if (live) drawCircle(accent.copy(alpha = 0.3f), 12.dp.toPx(), Offset(x, dotY))
            drawCircle(rail, 8.5.dp.toPx(), Offset(x, dotY)) // outline, so pale colours stay visible on a pale background
            drawCircle(accent, 7.dp.toPx(), Offset(x, dotY))
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.weight(1f).padding(bottom = 12.dp),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiBadge(e.emojiOrDefault(), accent, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val range = start.format(TIME) + (e.endMillis?.let { " – " + it.toLocal().format(TIME) } ?: "")
                    Text(range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    e.room?.let { Text("📍 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    e.faculty?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (LocalUnconfirmed.current) Text("\u26A0 Unconfirmed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.width(8.dp))
                if (live) {
                    Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White,
                        modifier = Modifier.clip(CircleShape).background(accent).padding(horizontal = 10.dp, vertical = 5.dp))
                } else {
                    Text(shortLeft(e.startMillis - now), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Colourful card: emoji, relative time and a progress visual (ring, dots or bars) chosen per event. */
@Composable
internal fun EventCard(e: Event, now: Long, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val bg = paletteColor(e.colorIndex())
    val fg = Color(onColor(bg.toArgb()))
    val left = e.startMillis - now
    Column(modifier.bouncy(onClick).clip(RoundedCornerShape(32.dp)).background(bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            EmojiBadge(e.emojiOrDefault(), fg, 44.dp)
            Spacer(Modifier.weight(1f))
            if (e.source == AMIZONE) Pill("Class", fg)
        }
        Spacer(Modifier.height(12.dp))
        Text(e.title().ifBlank { "Event name" }, style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(leftText(left), style = MaterialTheme.typography.headlineSmall, color = fg)
        if (e.styleIndex() != 3) {
            Spacer(Modifier.height(12.dp))
            ProgressVisual(e.styleIndex(), progress(left), fg)
        }
        Spacer(Modifier.height(12.dp))
        Text(e.subtitle(), style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        e.faculty?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Shown above the timetable when it can't currently be confirmed: sign in again, or just a failed refresh. */
@Composable
private fun TimetableBanner(expired: Boolean, since: Long, onSignIn: () -> Unit, onRetry: () -> Unit) {
    val date = if (since == 0L) "never" else Instant.ofEpochMilli(since).atZone(zone)
        .format(DateTimeFormatter.ofPattern("d MMM, h:mm a", LocalConfiguration.current.locales[0]))
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.tertiaryContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (expired) "Amizone sign-in needed" else "Couldn't refresh your timetable",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            if (expired) "Your classes below were last confirmed on $date and may be out of date. Your alarms keep working."
            else "Showing your classes as last confirmed on $date. Your alarms keep working.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        androidx.compose.material3.TextButton(onClick = if (expired) onSignIn else onRetry) {
            Text(if (expired) "Sign in" else "Try again", color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelLarge)
        }
    }
}
