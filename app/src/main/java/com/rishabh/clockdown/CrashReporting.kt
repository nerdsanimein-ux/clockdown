package com.rishabh.clockdown

import android.content.Context
import android.webkit.WebView
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Message

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
        if (BuildConfig.SENTRY_DSN.isBlank() || isTestLab(ctx)) return
        if (enabled(ctx)) start(ctx.applicationContext) else if (Sentry.isEnabled()) Sentry.close()
    }

    /** Google Play tests every uploaded build on its own robot phones (Firebase Test Lab); their reports aren't students'. */
    private fun isTestLab(ctx: Context) =
        runCatching { android.provider.Settings.System.getString(ctx.contentResolver, "firebase.test.lab") == "true" }.getOrDefault(false)

    private fun start(app: Context) {
        if (Sentry.isEnabled()) return
        SentryAndroid.init(app) { o ->
            o.dsn = BuildConfig.SENTRY_DSN
            o.release = "clockdown@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            o.environment = if (BuildConfig.DEBUG) "debug" else "release"
            o.isDebug = BuildConfig.DEBUG // the SDK logs what it sends to logcat, in debug builds only
            o.isSendDefaultPii = false
            o.isEnableAutoSessionTracking = true // Release Health: crash-free session/user rates, no extra data collected
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

    /**
     * The Amizone sign-in page failed to load (net/http/ssl). Only structured, non-secret facts go out: what kind of
     * failure, the HTTP status or SSL/net error code, and the WebView version - never the URL (it can carry a session
     * token or other identifiers) and never anything from the page itself. Device model and Android version are
     * attached automatically by the SDK's default context.
     */
    fun noteLoginFailure(kind: String, httpStatus: Int? = null, code: String? = null) {
        if (!Sentry.isEnabled()) return
        val event = SentryEvent().apply {
            level = SentryLevel.WARNING
            message = Message().apply { formatted = "Amizone sign-in page failed to load"; message = formatted }
        }
        event.setTag("login.failureKind", kind) // "net", "http" or "ssl"
        httpStatus?.let { event.setTag("login.httpStatus", it.toString()) }
        code?.let { event.setTag("login.errorCode", it) }
        WebView.getCurrentWebViewPackage()?.versionName?.let { event.setTag("login.webviewVersion", it) }
        Sentry.captureEvent(event)
    }

    /**
     * Amizone's sign-in page came back after Login was pressed: the attempt was refused. Only non-secret facts go out:
     * who pressed Login (the person or the app), seconds spent on the page, Wi-Fi or mobile data, the WebView version,
     * and Amizone's own error text if it showed one (scrubbed). Never the ID, password, captcha token or the URL.
     */
    fun noteLoginRefused(auto: Boolean, secondsOnPage: Long, network: String, amizoneMessage: String?) {
        if (!Sentry.isEnabled()) return
        val event = SentryEvent().apply {
            level = SentryLevel.WARNING
            message = Message().apply { formatted = "Amizone refused the sign-in"; message = formatted }
        }
        event.setTag("login.pressedBy", if (auto) "app" else "person")
        event.setTag("login.secondsOnPage", secondsOnPage.toString())
        event.setTag("login.network", network)
        WebView.getCurrentWebViewPackage()?.versionName?.let { event.setTag("login.webviewVersion", it) }
        scrub(amizoneMessage)?.takeIf { it.isNotBlank() }?.let { event.setExtra("amizoneMessage", it.take(200)) }
        Sentry.captureEvent(event)
    }

    /** The sign-in page's captcha didn't finish within 45 seconds. Wi-Fi or mobile data and the WebView version only. */
    fun noteCaptchaStuck(network: String) {
        if (!Sentry.isEnabled()) return
        val event = SentryEvent().apply {
            level = SentryLevel.WARNING
            message = Message().apply { formatted = "Amizone captcha didn't finish"; message = formatted }
        }
        event.setTag("login.network", network)
        WebView.getCurrentWebViewPackage()?.versionName?.let { event.setTag("login.webviewVersion", it) }
        Sentry.captureEvent(event)
    }

    /**
     * Amizone's attendance page changed shape and couldn't be read. Only the failure TYPE goes out ("list-format" or
     * "history-format") - never any attendance, course, name or page content. Device model and Android version ride along.
     */
    fun noteAttendanceFailure(kind: String) {
        if (!Sentry.isEnabled()) return
        val event = SentryEvent().apply {
            level = SentryLevel.WARNING
            message = Message().apply { formatted = "Attendance couldn't be read"; message = formatted }
        }
        event.setTag("attendance.failureKind", kind)
        Sentry.captureEvent(event)
    }

    /**
     * The Amizone sign-in lapsed. Only how long it had been since Amizone last accepted it (in bands), and the phone's
     * own settings that decide whether the keep-alive can run: battery optimisation, exact alarms, Android's standby
     * bucket. A long gap means the phone stopped the keep-alive; a short one means Amizone ended the sign-in itself.
     */
    fun noteSessionExpired(ctx: Context, lastOkAt: Long) {
        if (!Sentry.isEnabled()) return
        val event = SentryEvent().apply {
            level = SentryLevel.WARNING
            message = Message().apply { formatted = "Amizone sign-in lapsed"; message = formatted }
        }
        event.setTag("session.sinceOk", sinceOkBand(lastOkAt, System.currentTimeMillis()))
        event.setTag("session.batteryExempt", Battery.exempt(ctx).toString())
        event.setTag("session.exactAlarms", (android.os.Build.VERSION.SDK_INT < 31 || ctx.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()).toString())
        if (android.os.Build.VERSION.SDK_INT >= 28) event.setTag("session.standbyBucket", ctx.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket.toString())
        Sentry.captureEvent(event)
    }

    /** The LMS couldn't be read or signed in to. Only the failure TYPE goes out ("course-format", "sso-network"...), never any course, name or page content. */
    fun noteLmsFailure(kind: String) {
        if (!Sentry.isEnabled()) return
        val event = SentryEvent().apply {
            level = SentryLevel.WARNING
            message = Message().apply { formatted = "LMS couldn't be read"; message = formatted }
        }
        event.setTag("lms.failureKind", kind.take(40))
        Sentry.captureEvent(event)
    }

    /**
     * A quiz page sent answers to the LMS and the next page hadn't finished loading 20 seconds later. Only how long it waited,
     * the LMS's HTTP status if it sent one, whether any page appeared, the load progress, the names of other websites the page
     * was loading from (host names only, e.g. "cdn.example.com"), Wi-Fi or mobile data and the WebView version. Never an
     * address, the course or anything from the quiz.
     */
    fun noteQuizSendStuck(ctx: Context, seconds: Long, httpStatus: Int?, pageShown: Boolean, progress: Int, otherHosts: List<String>) {
        if (!Sentry.isEnabled()) return
        val event = SentryEvent().apply {
            level = SentryLevel.WARNING
            message = Message().apply { formatted = "LMS quiz send got stuck"; message = formatted }
        }
        event.setTag("quiz.secondsWaiting", seconds.toString())
        event.setTag("quiz.httpStatus", httpStatus?.toString() ?: "none")
        event.setTag("quiz.pageShown", pageShown.toString())
        event.setTag("quiz.progress", progress.toString())
        event.setExtra("otherHosts", otherHosts.take(20).joinToString(", ").ifEmpty { "none" })
        event.setTag("quiz.network", network(ctx))
        WebView.getCurrentWebViewPackage()?.versionName?.let { event.setTag("quiz.webviewVersion", it) }
        Sentry.captureEvent(event)
    }

    /** "wifi", "mobile", "other" or "none": the kind of connection, nothing more. */
    fun network(ctx: Context): String {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
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

/** How long since Amizone last accepted the sign-in, in coarse bands (never an exact time). 0 = not known. */
fun sinceOkBand(lastOkAt: Long, now: Long): String {
    if (lastOkAt <= 0L || now < lastOkAt) return "unknown"
    val min = (now - lastOkAt) / 60_000L
    return when {
        min < 90 -> "under 1.5h"
        min < 150 -> "1.5-2.5h"
        min < 240 -> "2.5-4h"
        min < 480 -> "4-8h"
        min < 1440 -> "8-24h"
        else -> "over 24h"
    }
}
