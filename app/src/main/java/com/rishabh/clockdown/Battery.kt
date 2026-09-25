package com.rishabh.clockdown

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Android may stop apps it thinks are idle to save battery, and that can silence alarms and stop timetable syncing.
 * Being "exempt" lets Clockdown keep working in the background. We ask once, explain why, and let the user say no.
 */
object Battery {
    fun exempt(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** The standard Android request: a one-tap system dialog for this app. */
    fun requestIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    /** Android's list of all apps and their battery setting, for reviewing the choice later. */
    fun settingsIntent() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** First run only, and only if it isn't already allowed. Recorded the moment we ask, so we never ask twice. */
    fun shouldAskNow(ctx: Context): Boolean {
        if (ctx.prefs.getBoolean("batteryAsked", false) || exempt(ctx)) return false
        ctx.prefs.edit().putBoolean("batteryAsked", true).apply()
        return true
    }

    /**
     * Keeps track of whether the exemption has ever been on, so that losing it later can be noticed and reported.
     * Call whenever the app comes to the front.
     */
    fun refresh(ctx: Context) {
        if (exempt(ctx)) ctx.prefs.edit().putBoolean("batteryWasExempt", true).putBoolean("batteryWarnDismissed", false).apply()
    }

    /** True if it was on before and has since been switched off (by the user, or by Android resetting it). */
    fun revoked(ctx: Context) =
        ctx.prefs.getBoolean("batteryWasExempt", false) && !exempt(ctx) && !ctx.prefs.getBoolean("batteryWarnDismissed", false)

    fun dismissWarning(ctx: Context) { ctx.prefs.edit().putBoolean("batteryWarnDismissed", true).apply() }
}

/** The one-time explanation, in plain words. "Continue" opens Android's own one-tap prompt. */
@Composable
fun BatteryDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Keep your alarms working") },
        text = {
            Text(
                "To ring your alarms on time and keep your timetable up to date, Clockdown needs Android's permission to run in the " +
                    "background. If you don't allow it, Android may quietly stop Clockdown to save battery, and then alarms and syncing can stop.\n\n" +
                    "On the next screen, tap Allow. Clockdown uses very little battery.",
            )
        },
        confirmButton = { TextButton(onClick = { ctx.startActivity(Battery.requestIntent(ctx)); onDone() }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Not now") } },
    )
}

/** Shown on the main screen when the permission was given once and has since been taken away. */
@Composable
fun BatteryWarning(onFix: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.tertiaryContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Battery saver is on for Clockdown", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(
            "Android may now stop Clockdown in the background, which can make alarms late or stop syncing. Allow it again to be safe.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onFix) { Text("Allow again", color = MaterialTheme.colorScheme.onTertiaryContainer) }
            TextButton(onClick = onDismiss) { Text("Dismiss", color = MaterialTheme.colorScheme.onTertiaryContainer) }
        }
    }
}

@Composable
internal fun StatusDot(good: Boolean, warn: Boolean = false, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Spacer(
        modifier.size(10.dp).clip(CircleShape).background(
            when { good -> androidx.compose.ui.graphics.Color(0xFF57B947); warn -> androidx.compose.ui.graphics.Color(0xFFE9604A); else -> MaterialTheme.colorScheme.outline },
        ),
    )
}
