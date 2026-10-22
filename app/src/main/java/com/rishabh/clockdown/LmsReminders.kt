package com.rishabh.clockdown

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Carried by anything that should land on the LMS tab (the reminder notifications, the Due soon widget). */
const val EXTRA_OPEN_LMS = "openLms"
const val EXTRA_LMS_CMID = "lmsCmid"

private const val HOUR = 3_600_000L

/** The Settings switch for the deadline reminders (on by default). */
val Context.lmsReminders get() = prefs.getBoolean("lmsReminders", true)

/**
 * "Assignment due tomorrow, 2:00 PM": a notification 24 hours and another 3 hours before each deadline, for assignments
 * Moodle has NOT said are submitted. They follow the data: every read of the LMS (and every restart) replaces them, so one
 * that was just handed in is cancelled the moment a read sees "Submitted". The switch is in Settings.
 */
object LmsReminders {
    private const val CH = "lms"
    private const val ACTION = "com.rishabh.clockdown.LMS_REMIND"
    private const val H24 = 24
    private const val H3 = 3

    class Reminder(val cmid: Int, val hours: Int, val at: Long) { val key get() = "$cmid:$hours" }

    /** Which alarms should exist now: both reminders of every pending assignment with a deadline, those still in the future. Pure. */
    internal fun plan(assigns: List<LmsAssign>, now: Long): List<Reminder> = assigns.filter { it.pending }.flatMap { a ->
        val due = a.dueMs ?: return@flatMap emptyList()
        listOf(H24, H3).map { Reminder(a.cmid, it, due - it * HOUR) }.filter { it.at > now }
    }

    private fun id(cmid: Int, hours: Int) = 4_000_000 + cmid * 2 + if (hours == H3) 1 else 0

    private fun pending(ctx: Context, cmid: Int, hours: Int) = PendingIntent.getBroadcast(
        ctx, id(cmid, hours),
        Intent(ctx, LmsReminderReceiver::class.java).setAction(ACTION).setData(Uri.parse("clockdown://lms/remind/$cmid/$hours")).putExtra("cmid", cmid).putExtra("hours", hours),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Call from a background thread. Makes the alarms match the saved data and the switch; cancels any that no longer belong. */
    fun reschedule(ctx: Context) {
        val now = System.currentTimeMillis()
        val snap = LmsStore.load(ctx)
        val on = ctx.lmsReminders && ctx.amizoneConnected
        val wanted = if (on && snap != null) plan(snap.assigns, now) else emptyList()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val keys = wanted.map { it.key }.toSet()
        for (k in LmsStore.reminderKeys(ctx) - keys) {
            val (cmid, hours) = k.split(':').map { it.toInt() }
            am.cancel(pending(ctx, cmid, hours))
        }
        wanted.forEach { Scheduler.exact(ctx, it.at, pending(ctx, it.cmid, it.hours)) }
        LmsStore.setReminderKeys(ctx, keys)
        // A reminder already showing for something that is now handed in (or switched off) goes away too.
        snap?.assigns?.filter { !on || !it.pending }?.forEach { a -> nm.cancel(id(a.cmid, H24)); nm.cancel(id(a.cmid, H3)) }
    }

    /** "today at 2:00 PM", "tomorrow at 2:00 PM", or "on Wed 14 Oct at 2:00 PM". */
    internal fun whenText(due: Long, now: Long, zone: java.time.ZoneId): String {
        val d = Instant.ofEpochMilli(due).atZone(zone)
        val time = d.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (d.toLocalDate()) {
            today -> "today at $time"
            today.plusDays(1) -> "tomorrow at $time"
            else -> "on " + d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) + " at $time"
        }
    }

    fun fire(ctx: Context, cmid: Int, hours: Int) {
        if (!ctx.lmsReminders) return
        val now = System.currentTimeMillis()
        val snap = LmsStore.load(ctx) ?: return
        val a = snap.assign(cmid)?.takeIf { it.pending } ?: return // handed in since this was set: say nothing
        val due = a.dueMs?.takeIf { it > now } ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Assignment deadlines", NotificationManager.IMPORTANCE_DEFAULT))
        val what = when (a.type) { "assign" -> "Assignment due"; "quiz" -> "Quiz closes"; else -> "Due" }
        val title = if (hours == H3) "$what in ${((due - now + 30 * 60_000L) / HOUR).coerceAtLeast(1)} hours" else "$what ${whenText(due, now, zone)}"
        val open = PendingIntent.getActivity(
            ctx, 5_000_000 + cmid,
            Intent(ctx, MainActivity::class.java).setData(Uri.parse("clockdown://lms/open/$cmid")).putExtra(EXTRA_OPEN_LMS, true).putExtra(EXTRA_LMS_CMID, cmid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(id(cmid, hours), Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title)
            .setContentText(listOfNotNull(a.name, snap.courseOf(a)?.let { titleCase(it.shortName.ifBlank { it.name }) }).joinToString(" · "))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

class LmsReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val cmid = intent.getIntExtra("cmid", -1)
        val hours = intent.getIntExtra("hours", 0)
        if (cmid >= 0 && hours > 0) async { LmsReminders.fire(ctx, cmid, hours) }
    }
}
