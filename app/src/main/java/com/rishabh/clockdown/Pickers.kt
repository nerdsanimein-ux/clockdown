@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.rishabh.clockdown

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Made-up class for previews when there is nothing real to show (no timetable yet, or the class defaults screen). */
fun sampleClass(now: Long = System.currentTimeMillis(), plusMinutes: Int = 95, name: String = "Data Structures", code: String = "CS201") = Event(
    id = 0, name = name, startMillis = now + plusMinutes * 60_000L, source = AMIZONE, courseCode = code, room = "Block A - 101",
    endMillis = now + (plusMinutes + 55) * 60_000L,
)

/** The three pretend wallpapers a style is previewed on, so you can see it is readable on a light and a dark one. */
enum class Backdrop(val label: String, val light: Boolean, val brush: Brush) {
    LIGHT("Light wallpaper", true, Brush.linearGradient(listOf(Color(0xFFF4EBDD), Color(0xFFDCE8F2), Color(0xFFFFF7E6)))),
    DARK("Dark wallpaper", false, Brush.linearGradient(listOf(Color(0xFF0B0F1A), Color(0xFF1A2238), Color(0xFF0E1A17)))),
    BUSY("Busy wallpaper", false, Brush.linearGradient(listOf(Color(0xFFE95B4E), Color(0xFFF6D24A), Color(0xFF3FA96B), Color(0xFF3D6BE0)))),
}

@Composable
fun BackdropChooser(selected: Backdrop, onSelect: (Backdrop) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Backdrop.entries.forEach { FilterChip(selected == it, { onSelect(it) }, label = { Text(it.label) }) }
    }
}

/**
 * The real widget drawn by the real widget code ([Widgets]), placed over a wallpaper stand-in. Anything here is exactly
 * what the home screen will show, because it is the same RemoteViews.
 */
@Composable
fun WidgetPreview(backdrop: Backdrop, width: Dp?, height: Dp, modifier: Modifier = Modifier, build: (Context) -> android.widget.RemoteViews) {
    val ctx = LocalContext.current
    val size = if (width != null) Modifier.size(width, height) else Modifier.fillMaxWidth().height(height)
    Box(modifier.then(size).clip(MaterialTheme.shapes.large).background(backdrop.brush).padding(6.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { FrameLayout(it) },
            update = { frame ->
                frame.removeAllViews()
                frame.addView(build(ctx).apply(ctx, frame))
            },
        )
    }
}

/**
 * Every style, each drawn with [sample] as it will look, in a grid that grows down the page so none is hidden.
 * [automatic] adds a first "follow the default" choice (null). [columns] is 2 for the small timer widget, 1 for big ones.
 */
@Composable
fun StylePicker(
    selected: WidgetStyle?, onSelect: (WidgetStyle?) -> Unit, backdrop: Backdrop, automatic: String? = null,
    columns: Int = 2, tileHeight: Dp = 148.dp,
    sample: (Context, WidgetStyle, Boolean) -> android.widget.RemoteViews,
) {
    val choices: List<WidgetStyle?> = (if (automatic != null) listOf(null) else emptyList()) + WidgetStyle.entries
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        choices.chunked(columns).forEach { rowChoices ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowChoices.forEach { s ->
                    Box(Modifier.weight(1f)) {
                        if (s == null) {
                            StyleTile(automatic ?: "", selected == null, { onSelect(null) }) {
                                Box(Modifier.fillMaxWidth().height(tileHeight).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                                    Text("Follows the\ndefault", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            StyleTile(s.label, selected == s, { onSelect(s) }) { WidgetPreview(backdrop, null, tileHeight) { sample(it, s, backdrop.light) } }
                        }
                    }
                }
                repeat(columns - rowChoices.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StyleTile(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().bouncy(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.clip(MaterialTheme.shapes.large)
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary).padding(3.dp) else Modifier.padding(3.dp)),
        ) { content() }
        Text(
            label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Palette swatches, with an optional first "automatic" choice for the class default. */
@Composable
fun ColourRow(selected: Int?, onSelect: (Int?) -> Unit, allowAuto: Boolean) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (allowAuto) FilterChip(selected == null, { onSelect(null) }, label = { Text("Automatic") })
        repeat(PALETTE_SIZE) { i ->
            val c = paletteColor(i)
            Box(Modifier.size(36.dp).clip(CircleShape).background(c).bouncy { onSelect(i) }, contentAlignment = Alignment.Center) {
                if (selected == i) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color(onColor(c.toArgb())), modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ Putting a widget on the home screen

/** Asks the launcher to place a widget for us. Kept in one object so every "Add widget" button behaves the same. */
object Pinner {
    const val EXTRA_EVENT = "eventId"
    const val EXTRA_NAME = "eventName"

    fun supported(ctx: Context) = AppWidgetManager.getInstance(ctx).isRequestPinAppWidgetSupported

    /**
     * Pins a Timer widget already set to [event]. The launcher tells [PinReceiver] which widget it created, so the widget
     * is bound to the timer straight away. If it doesn't tell us, the timer is also remembered so the next Timer widget
     * placed picks it up. Returns false when the launcher can't place widgets for us.
     */
    fun pinTimer(ctx: Context, event: Event): Boolean {
        if (!supported(ctx)) { WidgetPrefs.setPending(ctx, event.id, event.title()); return false }
        WidgetPrefs.setPending(ctx, event.id, event.title())
        val callback = PendingIntent.getBroadcast(
            ctx, event.id,
            Intent(ctx, PinReceiver::class.java).putExtra(EXTRA_EVENT, event.id).putExtra(EXTRA_NAME, event.title()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the launcher adds the new widget's id
        )
        return AppWidgetManager.getInstance(ctx).requestPinAppWidget(ComponentName(ctx, ClockdownWidget::class.java), null, callback)
    }

    fun pin(ctx: Context, provider: Class<*>): Boolean =
        supported(ctx) && AppWidgetManager.getInstance(ctx).requestPinAppWidget(ComponentName(ctx, provider), null, null)
}

/** Called by the launcher once it has created the widget we asked for. */
class PinReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val eventId = intent.getIntExtra(Pinner.EXTRA_EVENT, 0)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || eventId == 0) return
        WidgetPrefs.set(ctx, id, WidgetPrefs.TIMER_EVENT + eventId, intent.getStringExtra(Pinner.EXTRA_NAME))
        WidgetPrefs.clearPending(ctx)
        async { Scheduler.refreshWidget(ctx) }
    }
}

/** What to do when the launcher can't add the widget itself: plain steps, and the timer is picked up automatically. */
@Composable
private fun ManualSteps(what: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("Add it from your home screen") },
        text = {
            Text(
                "Your phone's home screen can't add it with one tap, so it takes four quick steps:\n\n" +
                    "1. Go to your home screen.\n" +
                    "2. Touch and hold an empty spot until a menu appears.\n" +
                    "3. Tap Widgets, then find Clockdown.\n" +
                    "4. Drag the widget you want onto the screen.\n\n$what",
            )
        },
    )
}

/** Shown right after a timer is saved. */
@Composable
fun AddTimerWidgetDialog(event: Event, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var manual by remember { mutableStateOf(false) }
    if (manual) { ManualSteps("Choose the Timer widget. It will show \"${event.title()}\" by itself.", onDismiss); return }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timer saved") },
        text = { Text("Want \"${event.title()}\" on your home screen?") },
        confirmButton = {
            Button(onClick = { if (Pinner.pinTimer(ctx, event)) onDismiss() else manual = true }) { Text("Add to home screen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** The "Add widget" entry: pick which kind, and the launcher places it. */
@Composable
fun AddWidgetDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var manual by remember { mutableStateOf(false) }
    if (manual) { ManualSteps("Choose Timer, Classes or List, whichever you picked.", onDismiss); return }
    fun go(provider: Class<*>) { if (Pinner.pin(ctx, provider)) onDismiss() else manual = true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a widget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WidgetOption("Timer", "One countdown you choose, or always the next one", "⏳") { go(ClockdownWidget::class.java) }
                WidgetOption("Classes", "Your next class, then the rest of that day", "🎓") { go(ClassesWidget::class.java) }
                WidgetOption("List", "What's coming up, soonest first", "📋") { go(TimersWidget::class.java) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WidgetOption(title: String, blurb: String, emoji: String, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().bouncy(onClick), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
