@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.rishabh.clockdown

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** First-run flow for someone who has never seen the app. People who already use it never see it. */
object Onboarding {
    private const val KEY = "onboarded"

    /**
     * Call off the main thread. True only for a genuinely fresh install: anyone who is signed in, has timers or
     * classes, or has been through the permission prompts before is an existing user and is marked done.
     */
    fun needed(ctx: Context): Boolean {
        val p = ctx.prefs
        if (p.getBoolean(KEY, false)) return false
        val existing = ctx.amizoneConnected || p.contains("batteryAsked") || p.contains("lastSync") || AppDb.get(ctx).upcoming(0).isNotEmpty()
        if (existing) { finish(ctx); return false }
        return true
    }

    fun finish(ctx: Context) = ctx.prefs.edit().putBoolean(KEY, true).apply()
}

@Composable
fun OnboardingScreen(connected: Boolean, onSignIn: () -> Unit, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    // Coming back from the sign-in page signed in: that was the goal, straight to the timetable.
    androidx.compose.runtime.LaunchedEffect(connected) { if (connected) onDone() }
    BackHandler(enabled = step > 0) { step-- }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            AnimatedContent(step, Modifier.weight(1f), label = "onboarding") { s ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    if (s == 0) Welcome() else SignInStep()
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                repeat(2) { i ->
                    Text(if (i == step) "●" else "○", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            if (step == 0) {
                Button(onClick = { step = 1 }, Modifier.fillMaxWidth().height(56.dp)) { Text("Get started", style = MaterialTheme.typography.titleMedium) }
            } else {
                Button(onClick = onSignIn, Modifier.fillMaxWidth().height(56.dp)) { Text("Sign in to Amizone", style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = onDone, Modifier.fillMaxWidth()) { Text("Skip for now, I'll just use timers") }
            }
        }
    }
}

@Composable
private fun Welcome() {
    EmojiBadge("⏳", MaterialTheme.colorScheme.primary, 96.dp)
    Spacer(Modifier.height(20.dp))
    Text("Welcome to Clockdown", style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(8.dp))
    Text(
        "Countdowns for everything you don't want to miss, so you can stop checking the clock.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    Point("🎓", "Your classes, automatically", "Connect your Amizone account and your timetable appears with live countdowns and an alarm before each class.")
    Point("⏰", "Timers for anything", "Make your own countdowns with a name, date, colour and an alarm, for exams, trips or birthdays.")
    Point("📱", "On your home screen", "Add widgets that tick down by themselves, in the look you like.")
}

@Composable
private fun SignInStep() {
    EmojiBadge("🎓", MaterialTheme.colorScheme.primary, 96.dp)
    Spacer(Modifier.height(20.dp))
    Text("Connect your timetable", style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(8.dp))
    Text(
        "You'll sign in on Amizone's own page, with your own Amizone ID. You may be asked to complete a short check there.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    Point("🔒", "Your details stay on your phone", "Your ID and password are kept encrypted on this phone, only to fill in Amizone's sign-in page next time. You can delete them any time in Settings.")
    Point("🔔", "Then it just works", "Once you're in, your classes show up here and your alarms are set. Clockdown will ask to send notifications so alarms can reach you.")
    Spacer(Modifier.height(8.dp))
    Text(
        "If the app ever crashes it can send a short report (no ID, password or timetable in it). You can switch that off in Settings.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Point(emoji: String, title: String, body: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(emoji, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
