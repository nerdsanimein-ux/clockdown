package com.rishabh.clockdown

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Timer widget: one timer or class you choose (or a dynamic "next"). Reconfigurable from the launcher. */
class ClockdownWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = async { Scheduler.refreshWidget(ctx) }
    override fun onDeleted(ctx: Context, ids: IntArray) = WidgetPrefs.remove(ctx, ids)
}

/** Classes widget: the next class large, then the rest of that day. */
class ClassesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = async { Scheduler.refreshWidget(ctx) }
}

/** List widget: the next few events, filtered per instance (all / classes only / my timers only). */
class TimersWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = async { Scheduler.refreshWidget(ctx) }
    override fun onDeleted(ctx: Context, ids: IntArray) = WidgetPrefs.remove(ctx, ids)
}

/** Each placed widget instance has its own setting, keyed by its appWidgetId. */
object WidgetPrefs {
    // Timer widget
    const val TIMER_ANY = "timer:any" // only for instances placed before configuration existed
    const val TIMER_NEXT_TIMER = "timer:next-timer"
    const val TIMER_NEXT_CLASS = "timer:next-class"
    const val TIMER_EVENT = "timer:event:" // + event id
    // List widget
    const val LIST_ALL = "list:all"
    const val LIST_CLASSES = "list:classes"
    const val LIST_TIMERS = "list:timers"

    private fun p(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun get(ctx: Context, id: Int): String? = p(ctx).getString("w$id", null)
    /** The chosen timer's name, kept so the widget can still say what finished after the event is gone. */
    fun name(ctx: Context, id: Int): String? = p(ctx).getString("w$id.name", null)

    fun set(ctx: Context, id: Int, value: String, name: String? = null) {
        p(ctx).edit().putString("w$id", value).apply { if (name != null) putString("w$id.name", name) else remove("w$id.name") }.apply()
    }

    fun remove(ctx: Context, ids: IntArray) {
        val e = p(ctx).edit()
        ids.forEach { e.remove("w$it").remove("w$it.name") }
        e.apply()
    }
}

/** RemoteViews for all three widgets. Colours match the event's card in the app. */
object Widgets {
    const val ROWS = 4
    /** Sentinel id for the widget picker preview, which has no instance to configure. */
    const val PREVIEW_ID = -1
    private const val CLASS_ROWS = 3

    private class Row(val root: Int, val emoji: Int, val name: Int, val sub: Int, val chrono: Int)

    private val rows = listOf(
        Row(R.id.r0, R.id.r0_emoji, R.id.r0_name, R.id.r0_sub, R.id.r0_chrono),
        Row(R.id.r1, R.id.r1_emoji, R.id.r1_name, R.id.r1_sub, R.id.r1_chrono),
        Row(R.id.r2, R.id.r2_emoji, R.id.r2_name, R.id.r2_sub, R.id.r2_chrono),
        Row(R.id.r3, R.id.r3_emoji, R.id.r3_name, R.id.r3_sub, R.id.r3_chrono),
    )

    private class ClassRow(val root: Int, val bar: Int, val time: Int, val name: Int, val room: Int)

    private val classRows = listOf(
        ClassRow(R.id.c0, R.id.c0_bar, R.id.c0_time, R.id.c0_name, R.id.c0_room),
        ClassRow(R.id.c1, R.id.c1_bar, R.id.c1_time, R.id.c1_name, R.id.c1_room),
        ClassRow(R.id.c2, R.id.c2_bar, R.id.c2_time, R.id.c2_name, R.id.c2_room),
    )

    private fun vis(show: Boolean) = if (show) View.VISIBLE else View.GONE

    /** Counts down to [startMillis] on its own (the launcher ticks it), no updates needed each second. */
    private fun RemoteViews.countdown(id: Int, startMillis: Long, now: Long) {
        setChronometerCountDown(id, true)
        setChronometer(id, SystemClock.elapsedRealtime() + (startMillis - now), null, true)
    }

    private fun RemoteViews.badge(id: Int, fg: Int) =
        setInt(id, "setBackgroundResource", if (fg == Color.WHITE) R.drawable.widget_badge_light else R.drawable.widget_badge_dark)

    /** Opens the configuration screen for this widget instance, e.g. from a "Finished" timer. */
    private fun configure(ctx: Context, id: Int) = PendingIntent.getActivity(
        ctx, id,
        Intent(ctx, WidgetConfigActivity::class.java)
            .setData(Uri.parse("clockdown://widget/$id")) // unique per instance, so PendingIntents don't collapse into one
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The launcher re-applies updates onto the widget's existing views, so anything not set explicitly keeps its previous
     * value (a finished timer would keep the last timer's light background under white text). Set everything.
     */
    private fun emptyLook(v: RemoteViews) {
        v.setInt(R.id.root, "setBackgroundResource", R.drawable.widget_bg)
        v.setTextColor(R.id.empty, Color.WHITE)
        v.setTextColor(R.id.empty_sub, Color.argb(0xB0, 255, 255, 255))
    }

    // ---------------------------------------------------------------- Timer widget

    fun timer(ctx: Context, id: Int, upcoming: List<Event>, now: Long): RemoteViews {
        val saved = WidgetPrefs.get(ctx, id)
        if (saved == null && id != PREVIEW_ID) {
            // Not configured yet. Launchers don't always open the configuration screen (pinned widgets often skip it),
            // so instead of guessing, ask, and open the chooser when tapped.
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setOnClickPendingIntent(R.id.root, configure(ctx, id))
            for (view in listOf(R.id.emoji, R.id.name, R.id.chrono, R.id.room)) v.setViewVisibility(view, View.GONE)
            v.setViewVisibility(R.id.empty, View.VISIBLE)
            v.setViewVisibility(R.id.empty_sub, View.VISIBLE)
            v.setTextViewText(R.id.empty, "Choose a timer")
            v.setTextViewText(R.id.empty_sub, "Tap to pick what this widget shows")
            emptyLook(v)
            return v
        }
        val cfg = saved ?: WidgetPrefs.TIMER_ANY
        var e: Event? = null
        var message = "No upcoming events"
        var sub = ""
        var pick = false // tapping the widget reopens its configuration
        when {
            cfg == WidgetPrefs.TIMER_NEXT_TIMER -> { e = upcoming.firstOrNull { it.source == MANUAL }; message = "No upcoming timers" }
            cfg == WidgetPrefs.TIMER_NEXT_CLASS -> { e = upcoming.firstOrNull { it.source == AMIZONE }; message = "No upcoming classes" }
            cfg.startsWith(WidgetPrefs.TIMER_EVENT) -> {
                val eventId = cfg.removePrefix(WidgetPrefs.TIMER_EVENT).toIntOrNull() ?: -1
                e = upcoming.firstOrNull { it.id == eventId }
                if (e == null) {
                    message = if (AppDb.get(ctx).byId(eventId) == null) "Timer removed" else "Finished"
                    sub = listOfNotNull(WidgetPrefs.name(ctx, id), "Tap to pick another").joinToString("\n")
                    pick = true
                }
            }
            else -> e = upcoming.firstOrNull()
        }

        val v = RemoteViews(ctx.packageName, R.layout.widget)
        v.setOnClickPendingIntent(R.id.root, if (pick) configure(ctx, id) else Scheduler.openApp(ctx))
        val has = e != null
        for (view in listOf(R.id.emoji, R.id.name, R.id.chrono, R.id.room)) v.setViewVisibility(view, vis(has))
        v.setViewVisibility(R.id.empty, vis(!has))
        v.setViewVisibility(R.id.empty_sub, vis(!has && sub.isNotEmpty()))
        if (e == null) {
            v.setTextViewText(R.id.empty, message)
            v.setTextViewText(R.id.empty_sub, sub)
            emptyLook(v)
            return v
        }

        val index = e.colorIndex()
        val fg = onColor(cardColor(ctx, index))
        v.setInt(R.id.root, "setBackgroundResource", WIDGET_BGS[index])
        v.badge(R.id.emoji, fg)
        v.setTextViewText(R.id.emoji, e.emojiOrDefault())
        v.setTextViewText(R.id.name, e.title())
        v.setTextColor(R.id.name, fg)
        v.setTextColor(R.id.chrono, fg)
        v.countdown(R.id.chrono, e.startMillis, now)

        // Under the countdown: how many days away (the chronometer only shows hours), then the room.
        val days = (e.startMillis - now) / DAY_MS
        val info = listOfNotNull(
            if (days >= 1) (if (days == 1L) "1 day left" else "$days days left") else null,
            e.room,
        ).joinToString(" · ")
        v.setViewVisibility(R.id.room, vis(info.isNotEmpty()))
        v.setTextViewText(R.id.room, info)
        v.setTextColor(R.id.room, ColorUtils.setAlphaComponent(fg, 0xC0))
        return v
    }

    // ---------------------------------------------------------------- Classes widget

    private fun dayLabel(day: LocalDate, today: LocalDate) = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }

    private val timeFmt get() = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    fun classes(ctx: Context, upcoming: List<Event>, now: Long): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_classes)
        v.setOnClickPendingIntent(R.id.croot, Scheduler.openApp(ctx))
        val cls = upcoming.filter { it.source == AMIZONE }
        val next = cls.firstOrNull()
        v.setViewVisibility(R.id.cnext, vis(next != null))
        v.setViewVisibility(R.id.crest, vis(next != null))
        v.setViewVisibility(R.id.cempty, vis(next == null))
        if (next == null) return v

        // "That day" is the day of the next class, so it rolls over on its own once today's classes are done.
        val day = next.startMillis.toLocal().toLocalDate()
        val sameDay = cls.drop(1).filter { it.startMillis.toLocal().toLocalDate() == day }
        val index = next.colorIndex()
        val fg = onColor(cardColor(ctx, index))
        v.setInt(R.id.cnext, "setBackgroundResource", WIDGET_BGS[index])
        v.setTextViewText(R.id.chead, "NEXT CLASS · ${dayLabel(day, LocalDate.now(zone)).uppercase()}")
        v.setTextColor(R.id.chead, ColorUtils.setAlphaComponent(fg, 0xC0))
        v.badge(R.id.cemoji, fg)
        v.setTextViewText(R.id.cemoji, next.emojiOrDefault())
        v.setTextViewText(R.id.cname, next.title())
        v.setTextColor(R.id.cname, fg)
        v.setTextColor(R.id.cchrono, fg)
        v.countdown(R.id.cchrono, next.startMillis, now)
        val info = listOfNotNull(next.startMillis.toLocal().format(timeFmt), next.room).joinToString(" · ")
        v.setTextViewText(R.id.croom, info)
        v.setTextColor(R.id.croom, ColorUtils.setAlphaComponent(fg, 0xC0))

        classRows.forEachIndexed { i, row ->
            val e = sameDay.getOrNull(i)
            v.setViewVisibility(row.root, vis(e != null))
            if (e == null) return@forEachIndexed
            v.setInt(row.bar, "setBackgroundResource", WIDGET_BGS[e.colorIndex()])
            v.setTextViewText(row.time, e.startMillis.toLocal().format(timeFmt))
            v.setTextViewText(row.name, e.title())
            v.setTextViewText(row.room, e.room.orEmpty())
            v.setViewVisibility(row.room, vis(e.room != null))
        }
        val more = sameDay.size - CLASS_ROWS
        v.setViewVisibility(R.id.cmore, vis(more > 0))
        v.setTextViewText(R.id.cmore, "+$more more")
        return v
    }

    // ---------------------------------------------------------------- List widget

    private val whenFmt get() = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())

    fun list(ctx: Context, id: Int, upcoming: List<Event>, now: Long): RemoteViews {
        val cfg = WidgetPrefs.get(ctx, id) ?: WidgetPrefs.LIST_ALL
        val events = when (cfg) {
            WidgetPrefs.LIST_CLASSES -> upcoming.filter { it.source == AMIZONE }
            WidgetPrefs.LIST_TIMERS -> upcoming.filter { it.source == MANUAL }
            else -> upcoming
        }.take(ROWS)

        val v = RemoteViews(ctx.packageName, R.layout.widget_timers)
        v.setOnClickPendingIntent(R.id.troot, Scheduler.openApp(ctx))
        v.setViewVisibility(R.id.theader, vis(events.isNotEmpty()))
        v.setTextViewText(
            R.id.theader,
            when (cfg) { WidgetPrefs.LIST_CLASSES -> "Next classes"; WidgetPrefs.LIST_TIMERS -> "My timers"; else -> "Coming up" },
        )
        v.setViewVisibility(R.id.tempty, vis(events.isEmpty()))
        v.setTextViewText(
            R.id.tempty,
            when (cfg) { WidgetPrefs.LIST_CLASSES -> "No upcoming classes"; WidgetPrefs.LIST_TIMERS -> "No upcoming timers"; else -> "No upcoming events" },
        )
        rows.forEachIndexed { i, row ->
            val e = events.getOrNull(i)
            // Unused slots stay INVISIBLE (not GONE): rows share the height equally, so a lone row keeps its normal size
            // instead of stretching over the whole widget. With nothing to show, GONE lets the empty message take the space.
            v.setViewVisibility(row.root, if (e != null) View.VISIBLE else if (events.isEmpty()) View.GONE else View.INVISIBLE)
            if (e == null) return@forEachIndexed
            val index = e.colorIndex()
            val fg = onColor(cardColor(ctx, index))
            v.setInt(row.root, "setBackgroundResource", WIDGET_BGS[index])
            v.badge(row.emoji, fg)
            v.setTextViewText(row.emoji, e.emojiOrDefault())
            v.setTextViewText(row.name, e.title())
            v.setTextColor(row.name, fg)
            v.setTextViewText(row.sub, listOfNotNull(e.startMillis.toLocal().format(whenFmt), e.room).joinToString(" · "))
            v.setTextColor(row.sub, ColorUtils.setAlphaComponent(fg, 0xC0))
            v.setTextColor(row.chrono, fg)
            v.countdown(row.chrono, e.startMillis, now)
        }
        return v
    }

    /** Refreshes every placed instance of every widget. Call from a background thread. */
    fun updateAll(ctx: Context, upcoming: List<Event>, now: Long) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in mgr.getAppWidgetIds(ComponentName(ctx, ClockdownWidget::class.java))) mgr.updateAppWidget(id, timer(ctx, id, upcoming, now))
        for (id in mgr.getAppWidgetIds(ComponentName(ctx, ClassesWidget::class.java))) mgr.updateAppWidget(id, classes(ctx, upcoming, now))
        for (id in mgr.getAppWidgetIds(ComponentName(ctx, TimersWidget::class.java))) mgr.updateAppWidget(id, list(ctx, id, upcoming, now))
    }

    /** Android 15+: show real content (not a grey placeholder) in the widget picker. Rate-limited by the system, so best effort. */
    fun publishPreviews(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        val now = System.currentTimeMillis()
        val upcoming = AppDb.get(ctx).upcoming(now)
        val mgr = AppWidgetManager.getInstance(ctx)
        val home = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
        runCatching {
            mgr.setWidgetPreview(ComponentName(ctx, ClockdownWidget::class.java), home, timer(ctx, PREVIEW_ID, upcoming, now))
            mgr.setWidgetPreview(ComponentName(ctx, ClassesWidget::class.java), home, classes(ctx, upcoming, now))
            mgr.setWidgetPreview(ComponentName(ctx, TimersWidget::class.java), home, list(ctx, PREVIEW_ID, upcoming, now))
        }
    }
}
