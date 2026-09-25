package com.rishabh.clockdown

import android.content.Context
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid

/**
 * Crash reports, so a crash on a friend's phone can be fixed. Sent to Sentry, only if a DSN was built in and the user
 * hasn't switched it off in Settings.
 *
 * What goes out: the error, where in the code it happened, the app version and the phone model and Android version.
 * What never does: your Amizone ID or password, cookies, your timetable, your name or phone name, or any trail of what
 * you tapped. Everything else the SDK could attach is turned off, and [scrub] blanks anything that looks like a secret.
 */
object CrashReporting {
    private const val KEY = "crashReports"

    fun enabled(ctx: Context) = ctx.prefs.getBoolean(KEY, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.prefs.edit().putBoolean(KEY, on).apply()
        apply(ctx)
    }

    /** Starts or stops reporting to match the setting. Safe to call any time; does nothing when no DSN was built in. */
    fun apply(ctx: Context) {
        if (BuildConfig.SENTRY_DSN.isBlank()) return
        if (enabled(ctx)) start(ctx.applicationContext) else if (Sentry.isEnabled()) Sentry.close()
    }

    private fun start(app: Context) {
        if (Sentry.isEnabled()) return
        SentryAndroid.init(app) { o ->
            o.dsn = BuildConfig.SENTRY_DSN
            o.release = "clockdown@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            o.environment = if (BuildConfig.DEBUG) "debug" else "release"
            o.isDebug = BuildConfig.DEBUG // the SDK logs what it sends to logcat, in debug builds only
            o.isSendDefaultPii = false
            o.isEnableAutoSessionTracking = false
            o.tracesSampleRate = 0.0
            // No trail of what the person did or which apps ran: breadcrumbs are all off.
            o.isEnableUserInteractionBreadcrumbs = false
            o.isEnableActivityLifecycleBreadcrumbs = false
            o.isEnableAppComponentBreadcrumbs = false
            o.isEnableSystemEventBreadcrumbs = false
            o.isEnableNetworkEventBreadcrumbs = false
            o.isEnableAppLifecycleBreadcrumbs = false
            o.beforeBreadcrumb = io.sentry.SentryOptions.BeforeBreadcrumbCallback { _, _ -> null }
            o.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ -> clean(event) }
        }
    }

    /** A problem that isn't a crash but is worth knowing about (for example a timetable we couldn't read). */
    fun note(what: String) {
        if (Sentry.isEnabled()) Sentry.captureMessage(scrub(what).orEmpty(), SentryLevel.WARNING)
    }

    private fun clean(event: SentryEvent): SentryEvent {
        event.user = null
        event.request = null
        event.serverName = null
        event.breadcrumbs = null
        event.contexts.device?.name = null // the name the person gave their phone
        event.exceptions?.forEach { it.value = scrub(it.value) }
        event.message?.let { m -> m.formatted = scrub(m.formatted); m.message = scrub(m.message) }
        return event
    }

    /**
     * Cuts a message short and blanks anything that could be a secret: long token-like strings, runs of digits (IDs,
     * numbers) and anything after the words cookie, password or token.
     */
    fun scrub(text: String?): String? {
        if (text == null) return null
        var t = text.take(300)
        t = t.replace(Regex("(?i)(cookie|password|passwd|pwd|token|authorization|session)\\s*[:=].*"), "$1=[removed]")
        t = t.replace(Regex("[A-Za-z0-9+/_=.-]{24,}"), "[removed]")
        t = t.replace(Regex("\\d{5,}"), "#")
        return t
    }
}
