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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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

/** Carried on the intent that opens a new-timer editor for a specific empty Timer widget (see [WidgetConfigActivity]). */
const val EXTRA_BIND_WIDGET = "bindWidgetId"

/**
 * Opened by the launcher when a widget is placed and on long-press > reconfigure.
 * Timer widgets only ever pick *what* to show here — their look lives on the timer's own editor (see
 * [EXTRA_EDIT_EVENT]); Classes and List widgets have no single timer to edit, so they still choose a look here.
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
                if (kind == Kind.TIMER) {
                    TimerPickerScreen(current = WidgetPrefs.get(this, widgetId), onPick = ::saveTimer, onCreateNew = ::createNew)
                } else {
                    LookConfigScreen(kind, current = WidgetPrefs.get(this, widgetId), currentStyle = WidgetPrefs.style(this, widgetId), onSave = ::saveLook)
                }
            }
        }
    }

    private fun saveTimer(value: String, name: String?) {
        if (value != WidgetPrefs.get(this, widgetId)) WidgetPrefs.set(this, widgetId, value, name)
        finishAfterSave()
    }

    /** Opens the app straight into a new timer's editor; saving it there binds this exact widget to it. */
    private fun createNew() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_BIND_WIDGET, widgetId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // The widget keeps showing "choose a timer" until that editor is saved, exactly as if nothing were picked yet.
        finishAfterSave()
    }

    private fun saveLook(value: String?, name: String?, style: WidgetStyle?) {
        if (value != null && value != WidgetPrefs.get(this, widgetId)) WidgetPrefs.set(this, widgetId, value, name)
        WidgetPrefs.setStyle(this, widgetId, style)
        finishAfterSave()
    }

    /**
     * Closes the screen right away: waiting on the widget to redraw before returning a result made the whole screen
     * hang if that redraw was ever slow (it shares a widget host round-trip with the home screen). The redraw itself
     * still happens, just after, off the UI thread; for a brand-new widget the system also calls onUpdate() once
     * RESULT_OK is returned, so it never depends on this thread alone.
     */
    private fun finishAfterSave() {
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
        thread { Scheduler.refreshWidget(applicationContext) }
    }
}

/** Timer widget setup: which timer, nothing else. Its look is chosen on the timer's own editor. */
@Composable
private fun TimerPickerScreen(current: String?, onPick: (String, String?) -> Unit, onCreateNew: () -> Unit) {
    val ctx = LocalContext.current
    val events by remember { AppDb.get(ctx).all() }.collectAsState(emptyList())
    val now = System.currentTimeMillis()
    val timers = events.filter { it.source == MANUAL && it.startMillis > now }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("Timer widget", style = MaterialTheme.typography.displaySmall)
            Text(
                "Choose what this widget shows. Once it's on your home screen, tap it to open that timer and change how it looks.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    Surface(
                        Modifier.fillMaxWidth().bouncy(onCreateNew),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(14.dp))
                            Text("Create new timer", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                item { Label("Always the next one") }
                item {
                    Choice(
                        "Next custom timer", "Switches to the following timer when one passes", "⏳",
                        current == WidgetPrefs.TIMER_NEXT_TIMER,
                    ) { onPick(WidgetPrefs.TIMER_NEXT_TIMER, null) }
                }
                item {
                    Choice(
                        "Next class", "Switches to the following class when one starts", "🎓",
                        current == WidgetPrefs.TIMER_NEXT_CLASS,
                    ) { onPick(WidgetPrefs.TIMER_NEXT_CLASS, null) }
                }
                item { Label("A specific timer") }
                if (timers.isEmpty()) {
                    item {
                        Text(
                            "You have no upcoming timers yet. Create one above and it'll show up here.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(timers, key = { it.id }) { e ->
                    Choice(
                        e.title(), e.subtitle(), e.emojiOrDefault(), current == WidgetPrefs.TIMER_EVENT + e.id,
                        accent = paletteColor(e.colorIndex()),
                    ) { onPick(WidgetPrefs.TIMER_EVENT + e.id, e.title()) }
                }
            }
        }
    }
}

/** Classes/List widget setup: these have no single timer to edit, so they still choose a look here. */
@Composable
private fun LookConfigScreen(kind: Kind, current: String?, currentStyle: WidgetStyle?, onSave: (String?, String?, WidgetStyle?) -> Unit) {
    val ctx = LocalContext.current
    val events by remember { AppDb.get(ctx).all() }.collectAsState(emptyList())
    val now = System.currentTimeMillis()
    val classes = events.filter { it.source == AMIZONE && it.startMillis > now }

    // Nothing is saved until Save, so a widget can be restyled without re-choosing what it shows.
    var choice by remember { mutableStateOf(current ?: if (kind == Kind.LIST) WidgetPrefs.LIST_ALL else null) }
    var style by remember { mutableStateOf(currentStyle) }
    var backdrop by remember { mutableStateOf(Backdrop.DARK) }

    val sampleClasses = classes.ifEmpty { listOf(sampleClass(now), sampleClass(now, 200, "Marketing Basics", "MB101")) }
    val sampleList = (events.filter { it.startMillis > now }).sortedBy { it.startMillis }.take(3).ifEmpty { sampleClasses }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text(if (kind == Kind.LIST) "List widget" else "Classes widget", style = MaterialTheme.typography.displaySmall)
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
                    StylePicker(style, { style = it }, backdrop, automatic = "Automatic", columns = 1, tileHeight = 250.dp) { c, st, light ->
                        if (kind == Kind.CLASSES) Widgets.classesView(c, sampleClasses, st, now, null, light)
                        else Widgets.listView(c, sampleList, "Coming up", "", st, now, null, light)
                    }
                }
                if (kind == Kind.LIST) {
                    item { Label("Show") }
                    item { Choice("All events", "Classes and timers together, soonest first", "📋", choice == WidgetPrefs.LIST_ALL) { choice = WidgetPrefs.LIST_ALL } }
                    item { Choice("Classes only", "Just your next classes", "🎓", choice == WidgetPrefs.LIST_CLASSES) { choice = WidgetPrefs.LIST_CLASSES } }
                    item { Choice("My timers only", "Just the timers you created", "⏳", choice == WidgetPrefs.LIST_TIMERS) { choice = WidgetPrefs.LIST_TIMERS } }
                }
            }
            Button(
                onClick = { onSave(if (kind == Kind.CLASSES) null else choice, null, style) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) { Text("Save") }
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
