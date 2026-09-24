package com.rishabh.clockdown

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlin.concurrent.thread

const val ALARM = "com.rishabh.clockdown.ALARM"
const val START = "com.rishabh.clockdown.START"
const val REFRESH = "com.rishabh.clockdown.REFRESH"
const val SNOOZE = "com.rishabh.clockdown.SNOOZE"
const val DISMISS = "com.rishabh.clockdown.DISMISS"

private const val MIN = 60_000L
private const val DAY = 24 * 60 * MIN

/** Run [block] off the main thread while keeping the receiver alive (Room forbids main-thread queries). */
fun BroadcastReceiver.async(block: () -> Unit) {
    val pending = goAsync()
    thread { try { block() } finally { pending.finish() } }
}

object Scheduler {
    // Request code = event id for every PendingIntent; the action string keeps them distinct.
    // REFRESH uses -1 (ids start at 1).
    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun broadcast(ctx: Context, action: String, id: Int, name: String = "", room: String = "") =
        PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, AlarmReceiver::class.java).setAction(action)
                .putExtra("id", id).putExtra("name", name).putExtra("room", room),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun exact(ctx: Context, at: Long, pi: PendingIntent) {
        val am = am(ctx)
        // USE_EXACT_ALARM is auto-granted on 33+; on 31-32 fall back to inexact rather than crash.
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun alarmClock(ctx: Context, at: Long, id: Int, name: String, room: String) =
        am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(ctx)), broadcast(ctx, ALARM, id, name, room))

    fun cancel(ctx: Context, id: Int) {
        am(ctx).cancel(broadcast(ctx, ALARM, id))
        am(ctx).cancel(broadcast(ctx, START, id))
    }

    /**
     * Idempotent: setting an alarm replaces the previous one with the same PendingIntent. Deliberately does NOT
     * cancel when the alarm time has already passed, because a pending snooze shares the alarm's PendingIntent
     * and syncs call this often. Callers that change an existing event's time must [cancel] first.
     */
    fun schedule(ctx: Context, e: Event) {
        val now = System.currentTimeMillis()
        val minutes = if (e.source == AMIZONE) ctx.classAlarmMinutes else e.alarmMinutes
        val alarmAt = e.startMillis - minutes * MIN
        val room = e.room.orEmpty()
        if (minutes == 0) am(ctx).cancel(broadcast(ctx, ALARM, e.id))
        else if (alarmAt > now) alarmClock(ctx, alarmAt, e.id, e.title(), room)
        if (e.startMillis > now) exact(ctx, e.startMillis, broadcast(ctx, START, e.id, e.title(), room))
    }

    fun snooze(ctx: Context, id: Int, name: String, room: String) =
        alarmClock(ctx, System.currentTimeMillis() + 5 * MIN, id, name, room)

    /** Call from a background thread. Reschedules every upcoming event's alarms and refreshes the widget. */
    fun rescheduleAll(ctx: Context) {
        AppDb.get(ctx).upcoming(System.currentTimeMillis()).forEach { schedule(ctx, it) }
        refreshWidget(ctx)
    }

    /** Call from a background thread. Points every widget at the next upcoming event. */
    fun refreshWidget(ctx: Context) {
        val now = System.currentTimeMillis()
        val upcoming = AppDb.get(ctx).upcoming(now)
        Widgets.updateAll(ctx, upcoming, now)

        // Countdowns tick by themselves and each event's START alarm refreshes when it begins. Two things still need a
        // refresh: a "N days left" text changing, and midnight (the Classes widget's "Today"/"Tomorrow" label and day).
        val am = am(ctx)
        val pi = broadcast(ctx, REFRESH, -1)
        am.cancel(pi)
        val midnight = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 1000
        val dayFlips = upcoming.take(8).filter { it.startMillis - now >= DAY }.map { it.startMillis - ((it.startMillis - now) / DAY) * DAY + 1000 }
        exact(ctx, (dayFlips + midnight).min(), pi)
    }
}
