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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
        val forList = provider == TimersWidget::class.java.name
        setContent {
            ClockdownTheme {
                ConfigScreen(forList, current = WidgetPrefs.get(this, widgetId), onPick = ::save)
            }
        }
    }

    private fun save(value: String, name: String?) {
        WidgetPrefs.set(this, widgetId, value, name)
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
private fun ConfigScreen(forList: Boolean, current: String?, onPick: (String, String?) -> Unit) {
    val ctx = LocalContext.current
    val events by remember { AppDb.get(ctx).all() }.collectAsState(emptyList())
    val now = System.currentTimeMillis()
    val timers = events.filter { it.source == MANUAL && it.startMillis > now }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text(if (forList) "List widget" else "Timer widget", style = MaterialTheme.typography.displaySmall)
            Text(
                "Choose what this widget shows. You can change it later with a long-press on the widget.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                if (forList) {
                    item { Choice("All events", "Classes and timers together, soonest first", "📋", current in listOf(null, WidgetPrefs.LIST_ALL)) { onPick(WidgetPrefs.LIST_ALL, null) } }
                    item { Choice("Classes only", "Just your next classes", "🎓", current == WidgetPrefs.LIST_CLASSES) { onPick(WidgetPrefs.LIST_CLASSES, null) } }
                    item { Choice("My timers only", "Just the timers you created", "⏳", current == WidgetPrefs.LIST_TIMERS) { onPick(WidgetPrefs.LIST_TIMERS, null) } }
                } else {
                    item { Label("Always the next one") }
                    item { Choice("Next custom timer", "Switches to the following timer when one passes", "⏳", current == WidgetPrefs.TIMER_NEXT_TIMER) { onPick(WidgetPrefs.TIMER_NEXT_TIMER, null) } }
                    item { Choice("Next class", "Switches to the following class when one starts", "🎓", current == WidgetPrefs.TIMER_NEXT_CLASS) { onPick(WidgetPrefs.TIMER_NEXT_CLASS, null) } }
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
                            e.title(), e.subtitle(), e.emojiOrDefault(), current == WidgetPrefs.TIMER_EVENT + e.id,
                            accent = paletteColor(e.colorIndex()),
                        ) { onPick(WidgetPrefs.TIMER_EVENT + e.id, e.title()) }
                    }
                }
            }
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
