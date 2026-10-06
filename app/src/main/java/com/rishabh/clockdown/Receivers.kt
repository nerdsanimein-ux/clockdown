package com.rishabh.clockdown

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rishabh.clockdown.ui.theme.ClockdownTheme

private const val START_CH = "start"

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val name = intent.getStringExtra("name").orEmpty()
        val room = intent.getStringExtra("room").orEmpty()
        fun withRoom(text: String) = if (room.isBlank()) text else "$text · $room"
        val nm = ctx.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            // Notification id: +id for the alarm, -id for the start notification. The alarm is the short alert (see Alarms).
            ALARM -> async { Alarms.fire(ctx, id, name, room, withRoom("Starting soon")) }
            START -> {
                nm.createNotificationChannel(NotificationChannel(START_CH, "Event started", NotificationManager.IMPORTANCE_DEFAULT))
                nm.notify(-id, Notification.Builder(ctx, START_CH)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(name).setContentText(withRoom("Starting now"))
                    .setContentIntent(Scheduler.openApp(ctx)).setAutoCancel(true).build())
                async { Scheduler.refreshWidget(ctx); AttendanceWakeups.classStarted(ctx) }
            }
            REFRESH -> async { Scheduler.refreshWidget(ctx); AttendanceWakeups.refreshTick(ctx) }
            DISMISS -> { nm.cancel(id); Alarms.stopRinging(ctx) }
            SNOOZE -> { nm.cancel(id); Alarms.stopRinging(ctx); Scheduler.snooze(ctx, id, name, room) }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    // Boot, app update, and clock/timezone changes all invalidate or shift scheduled alarms.
    override fun onReceive(ctx: Context, intent: Intent) = async { Scheduler.rescheduleAll(ctx) }
}

/** Shown over the lock screen by the alarm notification's full-screen intent. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getIntExtra("id", 0)
        val name = intent.getStringExtra("name").orEmpty()
        val room = intent.getStringExtra("room").orEmpty()
        // Reuse the notification's own DISMISS/SNOOZE handling so the sound stops the same way.
        fun act(action: String) { Scheduler.broadcast(this, action, id, name, room).send(); finish() }
        setContent {
            ClockdownTheme {
                // Surface supplies the readable text colour; a bare Column would draw black text on the dark background.
                Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Starting soon", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    if (room.isNotBlank()) Text(room, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(48.dp))
                    Button(onClick = { act(DISMISS) }, Modifier.fillMaxWidth().height(64.dp)) { Text("Dismiss", style = MaterialTheme.typography.titleLarge) }
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { act(SNOOZE) }, Modifier.fillMaxWidth().height(64.dp)) { Text("Snooze 5 min", style = MaterialTheme.typography.titleLarge) }
                }
                }
            }
        }
    }
}
