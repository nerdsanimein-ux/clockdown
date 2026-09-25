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
import android.util.TypedValue
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
    override fun onDeleted(ctx: Context, ids: IntArray) = WidgetPrefs.remove(ctx, ids)
}

/** List widget: the next few events, filtered per instance (all / classes only / my timers only). */
class TimersWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = async { Scheduler.refreshWidget(ctx) }
    override fun onDeleted(ctx: Context, ids: IntArray) = WidgetPrefs.remove(ctx, ids)
}

/** Each placed widget instance has its own settings, keyed by its appWidgetId. */
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

    /** This widget's own style, or null to follow the timer's/default style (see [resolveStyle]). */
    fun style(ctx: Context, id: Int): WidgetStyle? = WidgetStyle.of(p(ctx).getString("s$id", null))

    fun set(ctx: Context, id: Int, value: String, name: String? = null) {
        p(ctx).edit().putString("w$id", value).apply { if (name != null) putString("w$id.name", name) else remove("w$id.name") }.apply()
    }

    fun setStyle(ctx: Context, id: Int, style: WidgetStyle?) {
        p(ctx).edit().apply { if (style == null) remove("s$id") else putString("s$id", style.id) }.apply()
    }

    fun remove(ctx: Context, ids: IntArray) {
        val e = p(ctx).edit()
        ids.forEach { e.remove("w$it").remove("w$it.name").remove("s$it") }
        e.apply()
    }

    /**
     * For launchers that can't tell us which widget they created: the timer just saved is remembered for ten minutes, and
     * the next Timer widget that appears (one that wasn't on the screen yet) picks it up instead of asking which to show.
     */
    fun setPending(ctx: Context, eventId: Int, name: String) {
        val existing = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ClockdownWidget::class.java)).map { it.toString() }.toSet()
        p(ctx).edit().putInt("pendingId", eventId).putString("pendingName", name).putLong("pendingAt", System.currentTimeMillis())
            .putStringSet("pendingExisting", existing).apply()
    }

    fun takePending(ctx: Context, widgetId: Int): Pair<Int, String>? {
        val s = p(ctx)
        if (System.currentTimeMillis() - s.getLong("pendingAt", 0) > 10 * 60_000 || !s.contains("pendingId")) return null
        if (widgetId.toString() in s.getStringSet("pendingExisting", emptySet()).orEmpty()) return null // an older, unconfigured widget
        val out = s.getInt("pendingId", 0) to s.getString("pendingName", "").orEmpty()
        clearPending(ctx)
        return out
    }

    fun clearPending(ctx: Context) {
        p(ctx).edit().remove("pendingId").remove("pendingName").remove("pendingAt").remove("pendingExisting").apply()
    }
}

/**
 * RemoteViews for all three widgets. Every look comes from [Looks], so a widget, a preview inside the app, and a class
 * timer or your own timer all go through the same code.
 */
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

    /** "24 Sep, 7:11 pm": when the timetable was last confirmed, for the "Unconfirmed" notes. */
    private fun since(millis: Long) = if (millis == 0L) "never"
    else java.time.Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault()))

    /** Counts down to [startMillis] on its own (the launcher ticks it), no updates needed each second. */
    private fun RemoteViews.countdown(id: Int, startMillis: Long, now: Long) {
        setChronometerCountDown(id, true)
        setChronometer(id, SystemClock.elapsedRealtime() + (startMillis - now), null, true)
    }

    private fun RemoteViews.badge(id: Int, light: Boolean) =
        setInt(id, "setBackgroundResource", if (light) R.drawable.widget_badge_light else R.drawable.widget_badge_dark)

    private fun RemoteViews.bg(id: Int, res: Int) = setInt(id, "setBackgroundResource", res) // 0 clears the background

    private fun RemoteViews.size(id: Int, sp: Float) = setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp)

    private fun soft(fg: Int) = ColorUtils.setAlphaComponent(fg, 0xC8)

    /** Opens the configuration screen for this widget instance, e.g. from a "Finished" timer. */
    private fun configure(ctx: Context, id: Int) = PendingIntent.getActivity(
        ctx, id,
        Intent(ctx, WidgetConfigActivity::class.java)
            .setData(Uri.parse("clockdown://widget/$id")) // unique per instance, so PendingIntents don't collapse into one
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ---------------------------------------------------------------- Timer widget

    /** One event in one style. The real widget, the in-app style previews and the widget picker all use this. */
    fun timerView(
        ctx: Context, e: Event, style: WidgetStyle, now: Long, click: PendingIntent?, light: Boolean = Wallpaper.isLight(ctx),
        stripDp: Int = 150,
    ): RemoteViews {
        val look = Looks.of(ctx, style, e.colorIndex(), light)
        val v = RemoteViews(ctx.packageName, Looks.layout(Kind.TIMER, look.variant))
        if (click != null) v.setOnClickPendingIntent(R.id.root, click)
        v.bg(R.id.root, look.cardBg)
        for (view in listOf(R.id.emoji, R.id.name, R.id.chrono)) v.setViewVisibility(view, View.VISIBLE)
        v.setViewVisibility(R.id.empty, View.GONE)
        v.setViewVisibility(R.id.empty_sub, View.GONE)
        v.badge(R.id.emoji, look.lightBadge)
        v.setTextViewText(R.id.emoji, e.emojiOrDefault())
        v.setTextViewText(R.id.name, e.title())
        v.setTextColor(R.id.name, look.fg)
        v.setTextColor(R.id.chrono, look.fg)
        v.countdown(R.id.chrono, e.startMillis, now)

        // Under the countdown: how many days away (the chronometer only shows hours), then the room.
        val days = (e.startMillis - now) / DAY_MS
        val unconfirmed = ctx.unconfirmedSince?.takeIf { e.source == AMIZONE }
        val info = listOfNotNull(
            if (days >= 1) (if (days == 1L) "1 day left" else "$days days left") else null,
            e.room,
        ).joinToString(" · ").let { line ->
            if (unconfirmed == null) line else (if (line.isEmpty()) "" else "$line\n") + "⚠ Unconfirmed · synced ${since(unconfirmed)}"
        }
        v.setViewVisibility(R.id.room, vis(info.isNotEmpty()))
        v.setTextViewText(R.id.room, info)
        v.setTextColor(R.id.room, look.soft)

        // Progress (ring, dots or bars, or none), drawn in the style's own text colour so it reads on any background.
        val ps = e.styleIndex()
        val p = progress(e.startMillis - now)
        val halo = WidgetProgress.halo(look.variant)
        v.setViewVisibility(R.id.progress_ring, vis(ps == 0))
        if (ps == 0) v.setImageViewBitmap(R.id.progress_ring, WidgetProgress.ring(ctx, p, look.fg, halo))
        v.setViewVisibility(R.id.progress_strip, vis(ps == 1 || ps == 2))
        if (ps == 1 || ps == 2) v.setImageViewBitmap(R.id.progress_strip, WidgetProgress.strip(ctx, ps == 1, p, look.fg, halo, stripDp))
        return v
    }

    /** A widget with nothing to show (finished, not yet configured, no events), still in the chosen style. */
    private fun emptyTimer(ctx: Context, style: WidgetStyle, message: String, sub: String, click: PendingIntent?): RemoteViews {
        val look = Looks.of(ctx, style, 0)
        val v = RemoteViews(ctx.packageName, Looks.layout(Kind.TIMER, look.variant))
        if (click != null) v.setOnClickPendingIntent(R.id.root, click)
        // Card-like styles use the standard dark card here; the see-through ones stay see-through.
        v.bg(R.id.root, if (look.cardBg == 0 || style == WidgetStyle.OUTLINE) look.cardBg.takeIf { it != 0 } ?: 0 else emptyBg(style))
        for (view in listOf(R.id.emoji, R.id.name, R.id.chrono, R.id.room, R.id.progress_ring, R.id.progress_strip)) v.setViewVisibility(view, View.GONE)
        v.setViewVisibility(R.id.empty, View.VISIBLE)
        v.setViewVisibility(R.id.empty_sub, vis(sub.isNotEmpty()))
        v.setTextViewText(R.id.empty, message)
        v.setTextViewText(R.id.empty_sub, sub)
        val fg = if (style == WidgetStyle.CARD || style == WidgetStyle.GRADIENT) Color.WHITE else look.fg
        v.setTextColor(R.id.empty, fg)
        v.setTextColor(R.id.empty_sub, soft(fg))
        return v
    }

    private fun emptyBg(style: WidgetStyle) = when (style) {
        WidgetStyle.BOLD -> R.drawable.widget_black
        WidgetStyle.PAPER -> R.drawable.widget_paper
        else -> R.drawable.widget_bg
    }

    fun timer(ctx: Context, id: Int, upcoming: List<Event>, now: Long): RemoteViews {
        val override = WidgetPrefs.style(ctx, id)
        var saved = WidgetPrefs.get(ctx, id)
        if (saved == null && id != PREVIEW_ID) {
            // A timer that was just saved on a launcher that can't add widgets for us: use it, so nothing has to be chosen.
            WidgetPrefs.takePending(ctx, id)?.let { (eventId, name) ->
                saved = WidgetPrefs.TIMER_EVENT + eventId
                WidgetPrefs.set(ctx, id, saved!!, name)
            }
        }
        if (saved == null && id != PREVIEW_ID) {
            // Not configured yet. Launchers don't always open the configuration screen (pinned widgets often skip it),
            // so instead of guessing, ask, and open the chooser when tapped.
            return emptyTimer(ctx, override ?: WidgetStyle.CARD, "Choose a timer", "Tap to pick what this widget shows", configure(ctx, id))
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
        val click = if (pick) configure(ctx, id) else Scheduler.openApp(ctx)
        if (e == null) return emptyTimer(ctx, override ?: WidgetStyle.CARD, message, sub, click)
        // The strip is drawn to the widget's width so dots and bars aren't stretched (less the widget's own padding).
        val width = if (id == PREVIEW_ID) 0 else AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        return timerView(ctx, e, resolveStyle(override, e, Defaults.classStyle), now, click, stripDp = if (width > 0) (width - 24).coerceIn(100, 400) else 150)
    }

    // ---------------------------------------------------------------- Classes widget

    private fun dayLabel(day: LocalDate, today: LocalDate) = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }

    private val timeFmt get() = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    /** The next class large, then the rest of that day, in [style]. [cls] is upcoming classes, soonest first. */
    fun classesView(ctx: Context, cls: List<Event>, style: WidgetStyle, now: Long, click: PendingIntent?, light: Boolean = Wallpaper.isLight(ctx)): RemoteViews {
        val next = cls.firstOrNull()
        val look = Looks.of(ctx, style, next?.colorIndex() ?: 0, light)
        val v = RemoteViews(ctx.packageName, Looks.layout(Kind.CLASSES, look.variant))
        if (click != null) v.setOnClickPendingIntent(R.id.croot, click)
        v.bg(R.id.croot, look.containerBg)
        v.setViewVisibility(R.id.cnext, vis(next != null))
        v.setViewVisibility(R.id.crest, vis(next != null))
        v.setViewVisibility(R.id.cempty, vis(next == null))
        v.setTextColor(R.id.cempty, look.containerFg)
        if (next == null) return v

        // "That day" is the day of the next class, so it rolls over on its own once today's classes are done.
        val day = next.startMillis.toLocal().toLocalDate()
        val sameDay = cls.drop(1).filter { it.startMillis.toLocal().toLocalDate() == day }
        v.bg(R.id.cnext, look.rowBg(next.colorIndex()))
        val unconfirmed = ctx.unconfirmedSince
        v.setTextViewText(R.id.chead, "NEXT CLASS · ${dayLabel(day, LocalDate.now(zone)).uppercase()}" + if (unconfirmed != null) " · UNCONFIRMED" else "")
        v.setTextColor(R.id.chead, look.soft)
        v.badge(R.id.cemoji, look.lightBadge)
        v.setTextViewText(R.id.cemoji, next.emojiOrDefault())
        v.setTextViewText(R.id.cname, next.title())
        v.setTextColor(R.id.cname, look.fg)
        v.setTextColor(R.id.cchrono, look.fg)
        v.countdown(R.id.cchrono, next.startMillis, now)
        v.setTextViewText(R.id.croom, listOfNotNull(next.startMillis.toLocal().format(timeFmt), next.room).joinToString(" · "))
        v.setTextColor(R.id.croom, look.soft)

        classRows.forEachIndexed { i, row ->
            val e = sameDay.getOrNull(i)
            v.setViewVisibility(row.root, vis(e != null))
            if (e == null) return@forEachIndexed
            v.bg(row.bar, WIDGET_BGS[e.colorIndex()])
            v.setTextViewText(row.time, e.startMillis.toLocal().format(timeFmt))
            v.setTextViewText(row.name, e.title())
            v.setTextViewText(row.room, e.room.orEmpty())
            v.setViewVisibility(row.room, vis(e.room != null))
            v.setTextColor(row.time, look.containerFg)
            v.setTextColor(row.name, look.containerFg)
            v.setTextColor(row.room, soft(look.containerFg))
        }
        val more = sameDay.size - CLASS_ROWS
        v.setViewVisibility(R.id.cmore, vis(more > 0))
        v.setTextViewText(R.id.cmore, "+$more more")
        v.setTextColor(R.id.cmore, soft(look.containerFg))
        v.setViewVisibility(R.id.cnote, vis(unconfirmed != null))
        if (unconfirmed != null) v.setTextViewText(R.id.cnote, "⚠ Unconfirmed · last synced ${since(unconfirmed)}")
        v.setTextColor(R.id.cnote, soft(look.containerFg))
        return v
    }

    fun classes(ctx: Context, id: Int, upcoming: List<Event>, now: Long): RemoteViews =
        classesView(ctx, upcoming.filter { it.source == AMIZONE }, WidgetPrefs.style(ctx, id) ?: Defaults.classStyle, now, Scheduler.openApp(ctx))

    // ---------------------------------------------------------------- List widget

    private val whenFmt get() = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())

    fun listView(ctx: Context, events: List<Event>, header: String, empty: String, style: WidgetStyle, now: Long, click: PendingIntent?, light: Boolean = Wallpaper.isLight(ctx)): RemoteViews {
        val look = Looks.of(ctx, style, 0, light)
        val v = RemoteViews(ctx.packageName, Looks.layout(Kind.LIST, look.variant))
        if (click != null) v.setOnClickPendingIntent(R.id.troot, click)
        v.bg(R.id.troot, look.containerBg)
        v.setViewVisibility(R.id.theader, vis(events.isNotEmpty()))
        v.setTextViewText(R.id.theader, header)
        v.setTextColor(R.id.theader, look.containerFg)
        v.setViewVisibility(R.id.tempty, vis(events.isEmpty()))
        v.setTextViewText(R.id.tempty, empty)
        v.setTextColor(R.id.tempty, look.containerFg)
        rows.forEachIndexed { i, row ->
            val e = events.getOrNull(i)
            // Unused slots stay INVISIBLE (not GONE): rows share the height equally, so a lone row keeps its normal size
            // instead of stretching over the whole widget. With nothing to show, GONE lets the empty message take the space.
            v.setViewVisibility(row.root, if (e != null) View.VISIBLE else if (events.isEmpty()) View.GONE else View.INVISIBLE)
            if (e == null) return@forEachIndexed
            val rowLook = Looks.of(ctx, style, e.colorIndex(), light)
            val fg = Looks.rowFg(ctx, style, e.colorIndex(), light)
            v.bg(row.root, rowLook.rowBg(e.colorIndex()))
            v.badge(row.emoji, fg == Color.WHITE)
            v.setTextViewText(row.emoji, e.emojiOrDefault())
            v.setTextViewText(row.name, e.title())
            v.setTextColor(row.name, fg)
            v.setTextViewText(row.sub, listOfNotNull(e.startMillis.toLocal().format(whenFmt), e.room).joinToString(" · "))
            v.setTextColor(row.sub, soft(fg))
            v.setTextColor(row.chrono, fg)
            v.size(row.chrono, 17f * minOf(look.numeralScale, 1.2f)) // rows are short: big numerals would push the names out
            v.countdown(row.chrono, e.startMillis, now)
        }
        return v
    }

    fun list(ctx: Context, id: Int, upcoming: List<Event>, now: Long): RemoteViews {
        val cfg = WidgetPrefs.get(ctx, id) ?: WidgetPrefs.LIST_ALL
        val events = when (cfg) {
            WidgetPrefs.LIST_CLASSES -> upcoming.filter { it.source == AMIZONE }
            WidgetPrefs.LIST_TIMERS -> upcoming.filter { it.source == MANUAL }
            else -> upcoming
        }.take(ROWS)
        val header = (when (cfg) { WidgetPrefs.LIST_CLASSES -> "Next classes"; WidgetPrefs.LIST_TIMERS -> "My timers"; else -> "Coming up" }) +
            if (ctx.unconfirmedSince != null && events.any { it.source == AMIZONE }) " · classes unconfirmed" else ""
        val empty = when (cfg) { WidgetPrefs.LIST_CLASSES -> "No upcoming classes"; WidgetPrefs.LIST_TIMERS -> "No upcoming timers"; else -> "No upcoming events" }
        // A list of classes follows the class default; anything else follows Card unless this widget has its own style.
        val style = WidgetPrefs.style(ctx, id) ?: if (cfg == WidgetPrefs.LIST_CLASSES) Defaults.classStyle else WidgetStyle.CARD
        return listView(ctx, events, header, empty, style, now, Scheduler.openApp(ctx))
    }

    /** Refreshes every placed instance of every widget. Call from a background thread. */
    fun updateAll(ctx: Context, upcoming: List<Event>, now: Long) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in mgr.getAppWidgetIds(ComponentName(ctx, ClockdownWidget::class.java))) mgr.updateAppWidget(id, timer(ctx, id, upcoming, now))
        for (id in mgr.getAppWidgetIds(ComponentName(ctx, ClassesWidget::class.java))) mgr.updateAppWidget(id, classes(ctx, id, upcoming, now))
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
            mgr.setWidgetPreview(ComponentName(ctx, ClassesWidget::class.java), home, classes(ctx, PREVIEW_ID, upcoming, now))
            mgr.setWidgetPreview(ComponentName(ctx, TimersWidget::class.java), home, list(ctx, PREVIEW_ID, upcoming, now))
        }
    }
}
