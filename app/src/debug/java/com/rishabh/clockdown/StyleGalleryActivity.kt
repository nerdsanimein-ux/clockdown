package com.rishabh.clockdown

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rishabh.clockdown.ui.theme.ClockdownTheme

/**
 * Debug builds only: every style drawn by the real widget code over a chosen wallpaper stand-in, for review screenshots.
 * adb shell am start -n com.rishabh.clockdown/.StyleGalleryActivity --es kind timer|classes|list --es backdrop light|dark|busy [--ei progress 0..3] [--ei page 0..2]
 */
class StyleGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = when (intent.getStringExtra("kind")) { "classes" -> Kind.CLASSES; "list" -> Kind.LIST; else -> Kind.TIMER }
        val backdrop = Backdrop.entries.firstOrNull { it.name.equals(intent.getStringExtra("backdrop"), true) } ?: Backdrop.DARK
        val page = intent.getIntExtra("page", 0)
        val now = System.currentTimeMillis()
        val ps = intent.getIntExtra("progress", 2)
        val a = sampleClass(now).copy(progressStyle = ps)
        val b = sampleClass(now, 200, "Marketing Basics", "MB101")
        val c = sampleClass(now, 320, "Environmental Studies", "ES110")
        val mine = Event(id = 7, name = "Anna's birthday", startMillis = now + 3 * 86_400_000L + 3_600_000L, emoji = "🎂", colorIdx = 3, progressStyle = ps)
        setContent {
            ClockdownTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${kind.name.lowercase()} on ${backdrop.label.lowercase()}", style = MaterialTheme.typography.titleMedium)
                        when (kind) {
                            Kind.TIMER -> WidgetStyle.entries.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEachIndexed { i, s ->
                                        WidgetPreview(backdrop, 198.dp, 148.dp) { ctx -> Widgets.timerView(ctx, if (i == 0) a else mine, s, now, null, backdrop.light) }
                                    }
                                }
                            }
                            // Real widgets of these two are at least 4 columns wide, so one column of realistic tiles; 3 per page.
                            Kind.CLASSES -> WidgetStyle.entries.drop(page * 3).take(3).forEach { s ->
                                WidgetPreview(backdrop, 300.dp, 250.dp) { ctx -> Widgets.classesView(ctx, listOf(a, b, c), s, now, null, backdrop.light) }
                            }
                            Kind.LIST -> WidgetStyle.entries.drop(page * 3).take(3).forEach { s ->
                                WidgetPreview(backdrop, 300.dp, 250.dp) { ctx -> Widgets.listView(ctx, listOf(a, mine, b, c), "Coming up", "", s, now, null, backdrop.light) }
                            }
                        }
                    }
                }
            }
        }
    }
}
