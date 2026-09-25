@file:OptIn(ExperimentalMaterial3Api::class)

package com.rishabh.clockdown

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Plain-language help for the Android warning that appears for any app installed from outside the Play Store. */
@Composable
fun InstallHelpScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("How to install", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Group("Why does Android show a warning?") {
                Body(
                    "Clockdown is shared directly, not through the Play Store. Android shows a warning for every app that " +
                        "doesn't come from the Play Store, however harmless. It's about where the app came from, not about what it does.",
                )
                Body(
                    "Clockdown has no ads and no tracking. It doesn't read your messages, contacts, photos or location. " +
                        "It uses the internet only to fetch your Amizone timetable (if you connect it) and to check for new versions.",
                )
            }
            Group("What to tap") {
                Step("1", "Open the downloaded file and tap Install. If Android says your browser or Files app isn't allowed to install apps, tap Settings and switch on Allow from this source, then go back.")
                Step("2", "If you see \"Blocked by Play Protect\" or \"Harmful app\", tap More details (or the small arrow), then Install anyway.")
                Step("3", "If Android offers to scan the app first, you can tap Scan. It's harmless and the app stays the same. Or tap Install without scanning.")
                Step("4", "Open Clockdown and allow notifications when asked, so your alarms can reach you.")
                Body("The exact words differ between phones and Android versions, but it's always the same idea: you're allowing an app from outside the Play Store.")
            }
            Group("Updates") {
                Body("Clockdown checks for new versions itself. When one is ready, a dot appears on the Settings button. The same warning may appear again for each update; the same taps apply.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Step(n: String, text: String) {
    Text("$n.  $text", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
}
