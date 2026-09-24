package com.rishabh.clockdown

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connected: Boolean,
    lastSync: Long,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    var alarmsOn by remember { mutableStateOf(ctx.prefs.getBoolean("classAlarm", true)) }
    var minutes by remember { mutableStateOf(ctx.prefs.getInt("classMinutes", 10).toString()) }
    var titleTaps by remember { mutableIntStateOf(0) }
    var rawJson by remember { mutableStateOf<String?>(null) }

    // Class alarms are computed at scheduling time, so any change here reschedules everything.
    fun apply() = scope.launch(Dispatchers.IO) { Scheduler.rescheduleAll(ctx) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                // Hidden debug switch: tap the title 5 times.
                title = { Text("Settings", Modifier.clickable { titleTaps++ }, style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Group("Amizone") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(
                        Modifier.size(10.dp).clip(CircleShape)
                            .background(if (connected) Color(0xFF57B947) else MaterialTheme.colorScheme.outline),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(if (connected) "Connected" else "Not connected", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Last synced: " + if (lastSync == 0L) "never"
                    else Instant.ofEpochMilli(lastSync).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connected) FilledTonalButton(onClick = onConnect) { Text("Log in again") } else Button(onClick = onConnect) { Text("Connect Amizone") }
                    if (connected) OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }

            Group("Widgets") {
                Text(
                    "Put live countdowns on your home screen. You can add several timer widgets, each showing a different timer, " +
                        "and reconfigure any of them with a long-press.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { pinWidget(ctx, ClockdownWidget::class.java) }) { Text("Timer") }
                    FilledTonalButton(onClick = { pinWidget(ctx, ClassesWidget::class.java) }) { Text("Classes") }
                    FilledTonalButton(onClick = { pinWidget(ctx, TimersWidget::class.java) }) { Text("List") }
                }
            }

            Group("Class alarms") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Alarms for classes", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(alarmsOn, { alarmsOn = it; ctx.prefs.edit().putBoolean("classAlarm", it).apply(); apply() })
                }
                OutlinedTextField(
                    minutes,
                    { text ->
                        minutes = text.filter(Char::isDigit).take(3)
                        minutes.toIntOrNull()?.takeIf { it > 0 }?.let { ctx.prefs.edit().putInt("classMinutes", it).apply(); apply() }
                    },
                    label = { Text("Minutes before class") }, singleLine = true, enabled = alarmsOn,
                    shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            UpdatesGroup()

            if (titleTaps >= 5) {
                Group("Debug") {
                    OutlinedButton(onClick = { rawJson = ctx.prefs.getString("lastJson", null) ?: "(no response saved yet)" }) {
                        Text("View last raw response")
                    }
                }
            }
        }
    }

    rawJson?.let { raw ->
        AlertDialog(
            onDismissRequest = { rawJson = null },
            confirmButton = { TextButton(onClick = { rawJson = null }) { Text("Close") } },
            title = { Text("Last Amizone response") },
            text = {
                SelectionContainer {
                    Text(
                        raw.take(30_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
        )
    }
}

@Composable
internal fun Group(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** Asks the launcher to pin [provider]; Android shows its own confirmation. Not every launcher supports it. */
fun pinWidget(ctx: android.content.Context, provider: Class<*>) {
    val mgr = AppWidgetManager.getInstance(ctx)
    if (!mgr.isRequestPinAppWidgetSupported || !mgr.requestPinAppWidget(ComponentName(ctx, provider), null, null)) {
        Toast.makeText(ctx, "Long-press the home screen, then Widgets, then Clockdown", Toast.LENGTH_LONG).show()
    }
}
