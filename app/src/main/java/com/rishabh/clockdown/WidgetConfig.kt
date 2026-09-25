@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.rishabh.clockdown

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rishabh.clockdown.ui.theme.ClockdownTheme
import kotlin.concurrent.thread

/**
 * Opened by the launcher when a widget is placed and on long-press > reconfigure, or by tapping a finished Timer widget.
 * Each widget instance keeps its own choice (see [WidgetPrefs]).
 */
class WidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Cancelled unless something is chosen: the launcher then discards a widget that was only just being placed.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className
        val kind = when (provider) { TimersWidget::class.java.name -> Kind.LIST; ClassesWidget::class.java.name -> Kind.CLASSES; else -> Kind.TIMER }
        setContent {
            ClockdownTheme {
                ConfigScreen(kind, current = WidgetPrefs.get(this, widgetId), currentStyle = WidgetPrefs.style(this, widgetId), onSave = ::save)
            }
        }
    }

    private fun save(value: String?, name: String?, style: WidgetStyle?) {
        if (value != null) WidgetPrefs.set(this, widgetId, value, name)
        WidgetPrefs.setStyle(this, widgetId, style)
        // Draw the widget before reporting success, so it never shows a stale or empty layout after placement.
        thread {
            Scheduler.refreshWidget(applicationContext)
            runOnUiThread {
                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
            }
        }
    }
}

@Composable
private fun ConfigScreen(kind: Kind, current: String?, currentStyle: WidgetStyle?, onSave: (String?, String?, WidgetStyle?) -> Unit) {
    val ctx = LocalContext.current
    val events by remember { AppDb.get(ctx).all() }.collectAsState(emptyList())
    val now = System.currentTimeMillis()
    val timers = events.filter { it.source == MANUAL && it.startMillis > now }
    val classes = events.filter { it.source == AMIZONE && it.startMillis > now }

    // Nothing is saved until Save, so a widget can be restyled without re-choosing what it shows.
    var choice by remember { mutableStateOf(current ?: if (kind == Kind.LIST) WidgetPrefs.LIST_ALL else null) }
    var choiceName by remember { mutableStateOf<String?>(null) }
    var style by remember { mutableStateOf(currentStyle) }
    var backdrop by remember { mutableStateOf(Backdrop.DARK) }
    val canSave = kind != Kind.TIMER || choice != null

    // What the previews show: the timer picked, else the next real one, else a made-up class.
    val shown = when {
        kind == Kind.TIMER && choice?.startsWith(WidgetPrefs.TIMER_EVENT) == true ->
            events.firstOrNull { it.id == choice!!.removePrefix(WidgetPrefs.TIMER_EVENT).toIntOrNull() }
        kind == Kind.TIMER && choice == WidgetPrefs.TIMER_NEXT_CLASS -> classes.firstOrNull()
        kind == Kind.TIMER && choice == WidgetPrefs.TIMER_NEXT_TIMER -> timers.firstOrNull()
        else -> null
    } ?: (timers + classes).minByOrNull { it.startMillis } ?: sampleClass(now)
    val sampleClasses = classes.ifEmpty { listOf(sampleClass(now), sampleClass(now, 200, "Marketing Basics", "MB101")) }
    val sampleList = (timers + classes).sortedBy { it.startMillis }.take(3).ifEmpty { sampleClasses }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text(when (kind) { Kind.LIST -> "List widget"; Kind.CLASSES -> "Classes widget"; Kind.TIMER -> "Timer widget" }, style = MaterialTheme.typography.displaySmall)
            Text(
                if (kind == Kind.CLASSES) "Choose how this widget looks. You can change it later with a long-press on the widget."
                else "Choose what this widget shows and how it looks. You can change it later with a long-press on the widget.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item { Label("Look") }
                item { BackdropChooser(backdrop) { backdrop = it } }
                item {
                    StylePicker(
                        style, { style = it }, backdrop, automatic = "Automatic",
                        columns = if (kind == Kind.TIMER) 2 else 1, tileHeight = if (kind == Kind.TIMER) 148.dp else 250.dp,
                    ) { c, st, light ->
                        when (kind) {
                            Kind.TIMER -> Widgets.timerView(c, shown, st, now, null, light)
                            Kind.CLASSES -> Widgets.classesView(c, sampleClasses, st, now, null, light)
                            Kind.LIST -> Widgets.listView(c, sampleList, "Coming up", "", st, now, null, light)
                        }
                    }
                }
                if (kind == Kind.LIST) {
                    item { Label("Show") }
                    item { Choice("All events", "Classes and timers together, soonest first", "\uD83D\uDCCB", choice == WidgetPrefs.LIST_ALL) { choice = WidgetPrefs.LIST_ALL } }
                    item { Choice("Classes only", "Just your next classes", "\uD83C\uDF93", choice == WidgetPrefs.LIST_CLASSES) { choice = WidgetPrefs.LIST_CLASSES } }
                    item { Choice("My timers only", "Just the timers you created", "\u23F3", choice == WidgetPrefs.LIST_TIMERS) { choice = WidgetPrefs.LIST_TIMERS } }
                }
                if (kind == Kind.TIMER) {
                    item { Label("Always the next one") }
                    item { Choice("Next custom timer", "Switches to the following timer when one passes", "\u23F3", choice == WidgetPrefs.TIMER_NEXT_TIMER) { choice = WidgetPrefs.TIMER_NEXT_TIMER; choiceName = null } }
                    item { Choice("Next class", "Switches to the following class when one starts", "\uD83C\uDF93", choice == WidgetPrefs.TIMER_NEXT_CLASS) { choice = WidgetPrefs.TIMER_NEXT_CLASS; choiceName = null } }
                    item { Label("A specific timer") }
                    if (timers.isEmpty()) {
                        item {
                            Text(
                                "You have no upcoming timers yet. Create one in the app and it will show up here.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(timers, key = { it.id }) { e ->
                        Choice(
                            e.title(), e.subtitle(), e.emojiOrDefault(), choice == WidgetPrefs.TIMER_EVENT + e.id,
                            accent = paletteColor(e.colorIndex()),
                        ) { choice = WidgetPrefs.TIMER_EVENT + e.id; choiceName = e.title() }
                    }
                }
            }
            Button(
                onClick = { onSave(if (kind == Kind.CLASSES) null else choice, choiceName, style) }, enabled = canSave,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) { Text(if (canSave) "Save" else "Choose what to show first") }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Choice(title: String, subtitle: String, emoji: String, selected: Boolean, accent: Color? = null, onClick: () -> Unit) {
    val tint = accent ?: MaterialTheme.colorScheme.primary
    Surface(
        Modifier.fillMaxWidth().bouncy(onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            EmojiBadge(emoji, tint, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
