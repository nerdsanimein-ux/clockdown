package com.rishabh.clockdown

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
    expired: Boolean,
    lastSync: Long,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var helpOpen by remember { mutableStateOf(false) }
    var addWidget by remember { mutableStateOf(false) }
    if (helpOpen) { InstallHelpScreen { helpOpen = false }; return }
    if (addWidget) AddWidgetDialog { addWidget = false }
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
                            .background(if (connected && !expired) Color(0xFF57B947) else if (expired) Color(0xFFE9604A) else MaterialTheme.colorScheme.outline),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(if (!connected) "Not signed in" else if (expired) "Sign-in needed" else "Signed in", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Last confirmed: " + if (lastSync == 0L) "never"
                    else Instant.ofEpochMilli(lastSync).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connected && !expired) FilledTonalButton(onClick = onConnect) { Text("Sign in again") } else Button(onClick = onConnect) { Text(if (connected) "Sign in" else "Sign in to Amizone") }
                    if (connected) OutlinedButton(onClick = onDisconnect) { Text("Sign out") }
                }
            }

            Group("Sign-in details") {
                var status by remember { mutableStateOf(CredentialStore.status(ctx)) }
                // Coming back from the sign-in page can change it (saved fresh details, or Amizone refused the old ones).
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = CredentialStore.status(ctx) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(good = status == CredentialStore.Status.SAVED, warn = status == CredentialStore.Status.REJECTED)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (status) {
                            CredentialStore.Status.SAVED -> "Saved on this phone"
                            CredentialStore.Status.REJECTED -> "Saved, but Amizone refused it"
                            CredentialStore.Status.NONE -> "Not saved"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    when (status) {
                        CredentialStore.Status.SAVED -> "Your Amizone ID and password are stored encrypted on this phone, only to fill in Amizone's own sign-in page. Signing out keeps them, so signing in again is quicker."
                        CredentialStore.Status.REJECTED -> "Your saved password was not accepted, so it won't be tried again. Sign in once more to update it."
                        CredentialStore.Status.NONE -> "After you sign in once, your ID and password are saved (encrypted, on this phone only) so signing in again is quicker."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status != CredentialStore.Status.NONE) {
                    OutlinedButton(onClick = { CredentialStore.clear(ctx); status = CredentialStore.status(ctx) }) { Text("Forget saved login") }
                }
            }

            Group("Battery") {
                var tick by remember { mutableIntStateOf(0) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ } // coming back from Android's own prompt
                val allowed = remember(tick) { Battery.exempt(ctx) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(good = allowed, warn = !allowed)
                    Spacer(Modifier.width(10.dp))
                    Text(if (allowed) "Allowed to run in the background" else "Battery saver may stop Clockdown", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (allowed) "Your alarms and timetable syncing can keep working when the phone is idle."
                    else "Android may quietly stop Clockdown to save battery, which can make alarms late or stop syncing.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (allowed) OutlinedButton(onClick = { ctx.startActivity(Battery.settingsIntent()) }) { Text("Review in Android settings") }
                else Button(onClick = { ctx.startActivity(Battery.requestIntent(ctx)) }) { Text("Allow") }
            }

            Group("Widgets") {
                Text(
                    "Put live countdowns on your home screen. You can add several timer widgets, each showing a different timer, " +
                        "and reconfigure any of them with a long-press.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { addWidget = true }) { Text("Add a widget") }
            }

            Group("Class timers") {
                var style by remember { mutableStateOf(Defaults.classStyle) }
                var colour by remember { mutableStateOf(Defaults.classColor) }
                var progress by remember { mutableStateOf(Defaults.classProgress) }
                var backdrop by remember { mutableStateOf(Backdrop.DARK) }
                Text(
                    "How every class looks, in the app and on widgets. A widget can still have its own look: long-press it and choose Reconfigure.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BackdropChooser(backdrop) { backdrop = it }
                val now = System.currentTimeMillis()
                val events by remember { AppDb.get(ctx).all() }.collectAsState(emptyList()) // not a direct query: this runs on the main thread
                val sample = events.firstOrNull { it.source == AMIZONE && it.startMillis > now } ?: sampleClass(now)
                StylePicker(style, { it?.let { s -> style = s; Defaults.setClassStyle(ctx, s); apply() } }, backdrop) { c, st, light ->
                    Widgets.timerView(c, sample, st, now, null, light)
                }
                Text("Colour", style = MaterialTheme.typography.titleSmall)
                ColourRow(colour, { colour = it; Defaults.setClassColor(ctx, it); apply() }, allowAuto = true)
                Text("Progress", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf<Pair<Int?, String>>(null to "Automatic", 0 to "Ring", 1 to "Dots", 2 to "Bars", 3 to "None").forEach { (v, label) ->
                        FilterChip(progress == v, { progress = v; Defaults.setClassProgress(ctx, v); apply() }, label = { Text(label) })
                    }
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

            Group("Crash reports") {
                var on by remember { mutableStateOf(CrashReporting.enabled(ctx)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Send crash reports", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(on, { on = it; CrashReporting.setEnabled(ctx, it) })
                }
                Text(
                    "If Clockdown crashes, it can send a short report so the problem can be fixed. It contains the error and your phone model, " +
                        "never your Amizone ID or password, your timetable or your name.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Group("Installing Clockdown") {
                Text(
                    "Why Android warns about apps that don't come from the Play Store, and what to tap. Handy to show a friend.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { helpOpen = true }) { Text("How to install") }
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
