package com.rishabh.clockdown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private fun ago(ms: Long): String {
    if (ms == 0L) return "never"
    val min = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - ms)
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min minutes ago"
        min < 60 * 24 -> "${min / 60} hours ago"
        else -> "${min / (60 * 24)} days ago"
    }
}

/** Settings > "About and updates": the current version, a manual check, and the update dialog. */
@Composable
fun UpdatesGroup() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var dialogFor by remember { mutableStateOf<UpdateInfo?>(null) }
    val update = remember(tick) { UpdateChecker.available(ctx) }

    // revalidate = true when a cached update is about to be offered: ask GitHub first, so a release that has since
    // been deleted is never offered (and its badge goes away).
    fun check(revalidate: Boolean = false) {
        checking = true; message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { UpdateChecker.check(ctx, if (revalidate) CheckMode.REVALIDATE else CheckMode.MANUAL) }
            checking = false; tick++
            when (r) {
                CheckResult.UPDATE -> dialogFor = UpdateChecker.available(ctx)
                CheckResult.CURRENT -> message = "You're on the latest version."
                CheckResult.OFFLINE -> message = "Couldn't check right now. Please check your internet connection and try again."
                CheckResult.LIMITED -> message = "Too many checks in a short time. Please try again in a little while."
            }
        }
    }

    Group("About and updates") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Version", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(UpdateChecker.installedName(ctx), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .clickable(enabled = !checking) { check(revalidate = update != null) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Check for updates", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        checking -> "Checking…"
                        update != null -> "Version ${update.versionName} is ready to install"
                        message != null -> message!!
                        else -> "Last checked ${ago(UpdateChecker.lastChecked(ctx))}"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (update != null) {
                Text(
                    "New", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }

    dialogFor?.let { UpdateDialog(it) { dialogFor = null; tick++ } }
}

private sealed interface Phase {
    data object Offer : Phase
    data object NeedPermission : Phase
    data object Downloading : Phase
    data class Problem(val text: String) : Phase
}

@Composable
fun UpdateDialog(info: UpdateInfo, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Offer) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        if (!ApkInstaller.allowed(ctx)) { phase = Phase.NeedPermission; return }
        phase = Phase.Downloading; progress = 0f
        job = scope.launch {
            when (val r = ApkDownloader.download(ctx, info) { progress = it }) {
                is Download.Done -> { ApkInstaller.install(ctx, r.file); onClose() }
                Download.NoInternet -> phase = Phase.Problem("Couldn't reach the internet. Please check your connection and try again.")
                Download.Interrupted -> phase = Phase.Problem("The download stopped before it finished. Please try again.")
                Download.Gone -> {
                    // The release was withdrawn after we last looked. Forget it so the badge goes away too.
                    UpdateChecker.forget(ctx)
                    phase = Phase.Problem("This update isn't available any more, so there's nothing to install. You're on the latest version.")
                }
                Download.Corrupt -> phase = Phase.Problem("The downloaded file looks damaged. Please try again.")
                Download.WrongPublisher -> phase = Phase.Problem("This update can't be installed over the version you have. Please download the latest Clockdown from its download page instead.")
                Download.Invalid -> phase = Phase.Problem("This update doesn't match your app, so it wasn't installed.")
            }
        }
    }

    // Coming back from Android's settings page with the permission switched on: carry on where we left off.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (phase == Phase.NeedPermission && ApkInstaller.allowed(ctx)) start() }

    when (val p = phase) {
        Phase.Offer -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Update available") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Version ${info.versionName}", style = MaterialTheme.typography.titleMedium)
                    val notes = cleanNotes(info.notes)
                    if (notes.isNotBlank()) {
                        Text("What's new", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { start() }) { Text("Download") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Later") } },
        )

        Phase.NeedPermission -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Allow installing updates") },
            text = {
                Text(
                    "Android needs your permission before Clockdown can install its own updates. " +
                        "On the next screen, switch on \"Allow from this source\" for Clockdown, then come back here.",
                )
            },
            confirmButton = { TextButton(onClick = { ctx.startActivity(ApkInstaller.permissionScreen(ctx)) }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Not now") } },
        )

        Phase.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { job?.cancel(); onClose() }) { Text("Cancel") } },
        )

        is Phase.Problem -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Update didn't finish") },
            text = { Text(p.text) },
            confirmButton = { if (!p.text.startsWith("This update")) TextButton(onClick = { start() }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        )
    }
}
