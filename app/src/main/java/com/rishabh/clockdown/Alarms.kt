package com.rishabh.clockdown

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.abs
import kotlin.math.sqrt

/** The five alert sounds (original, CC0: see SOUNDS.md). */
enum class AlertSound(val id: String, val label: String, val res: Int) {
    CHIME("chime", "Chime", R.raw.alarm_chime),
    BELL("bell", "Bell", R.raw.alarm_bell),
    PULSE("pulse", "Pulse", R.raw.alarm_pulse),
    MARIMBA("marimba", "Marimba", R.raw.alarm_marimba),
    RISE("rise", "Rise", R.raw.alarm_rise);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: CHIME
    }
}

val Context.alertSound get() = AlertSound.of(prefs.getString("alertSound", null))

/** Timers ring until dismissed instead of the short alert. Off by default, so everyone gets the short alert after the update. */
val Context.longRing get() = prefs.getBoolean("longRing", false)

/** Long ring only: stop when the phone is picked up or turned face down. Sensors are read only while it rings. */
val Context.motionStop get() = prefs.getBoolean("motionStop", false)

object Alarms {
    private const val ALERT_CH = "alert"
    private const val RING_CH = "ring"
    private const val DONT_SKIP_CH = "attendance"

    // Preview in Settings: one player at a time, stopped when another starts.
    private var preview: MediaPlayer? = null

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    fun player(ctx: Context, sound: AlertSound, loop: Boolean): MediaPlayer =
        MediaPlayer.create(ctx, sound.res, attrs, (ctx.getSystemService(android.media.AudioManager::class.java)).generateAudioSessionId()).apply { isLooping = loop }

    /** Plays [sound] once from the start, for the Settings list. */
    @Synchronized
    fun preview(ctx: Context, sound: AlertSound) {
        preview?.release()
        preview = player(ctx.applicationContext, sound, loop = false).also { p -> p.setOnCompletionListener { it.release(); if (preview === p) preview = null }; p.start() }
    }

    @Synchronized
    fun stopPreview() { preview?.release(); preview = null }

    private fun vibrator(ctx: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    /** A short buzz-buzz, once. */
    fun buzz(ctx: Context) = vibrator(ctx).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1), attrs)

    private fun openApp(ctx: Context) = Scheduler.openApp(ctx)

    /**
     * An event's alarm time has come (off the main thread). A class or a timer gets the short alert: a banner, the chosen
     * sound for two seconds and a short buzz, then silence. Only a timer, and only with "Long ring" switched on, keeps ringing.
     * A class in a yellow or red subject also gets a "Don't skip" note.
     */
    fun fire(ctx: Context, id: Int, name: String, room: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("alarm") // the old continuous-ring channel, gone for good
        val event = AppDb.get(ctx).byId(id)
        if (event?.source == MANUAL && ctx.longRing) {
            ctx.startForegroundService(Intent(ctx, RingService::class.java).putExtra("id", id).putExtra("name", name).putExtra("room", room).putExtra("text", text))
        } else {
            nm.createNotificationChannel(NotificationChannel(ALERT_CH, "Event alerts", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
            nm.notify(id, Notification.Builder(ctx, ALERT_CH)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(name).setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(openApp(ctx)).setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "Dismiss", Scheduler.broadcast(ctx, DISMISS, id, name, room)).build())
                .addAction(Notification.Action.Builder(null, "Snooze 5 min", Scheduler.broadcast(ctx, SNOOZE, id, name, room)).build())
                .setTimeoutAfter(10 * 60_000L)
                .build())
            runCatching { buzz(ctx) }
            runCatching {
                val p = player(ctx, ctx.alertSound, loop = false)
                val done = java.util.concurrent.CountDownLatch(1)
                p.setOnCompletionListener { done.countDown() }
                p.start()
                done.await(3, java.util.concurrent.TimeUnit.SECONDS) // the sounds are two seconds; never wait longer
                p.release()
            }
        }
        if (event?.source == AMIZONE) dontSkip(ctx, event)
    }

    /** "Don't skip": a class that is about to start in a subject at risk. Only if the switch is on and it isn't marked already. */
    fun dontSkip(ctx: Context, e: Event) {
        if (!ctx.attNotifyDontSkip) return
        val snap = AttendanceStore.load(ctx) ?: return
        val c = snap.course(e.courseCode)?.takeIf { it.tracked && it.total > 0 } ?: return
        if (ctx.zoneOf(c) == Zone.GREEN || snap.markFor(e) != ClassMark.NOT_MARKED) return
        val time = java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault())).lowercase()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(DONT_SKIP_CH, "Attendance", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(4_000_000 + e.id, Notification.Builder(ctx, DONT_SKIP_CH)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Don't skip: ${e.title()}")
            .setContentText("${e.title()} at $time, you're at ${AttMath.percent(c.attended, c.total)}%")
            .setContentIntent(Scheduler.openAttendance(ctx)).setAutoCancel(true).build())
    }

    /** Dismiss or Snooze: stops a Long ring if one is going. */
    fun stopRinging(ctx: Context) { ctx.stopService(Intent(ctx, RingService::class.java)) }

    fun channelForRing(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel(RING_CH, "Timer ringing", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
    }

    const val RING = RING_CH
}

/**
 * "Long ring": a timer rings until it is dismissed (with a ten-minute safety stop). It runs as a foreground service so
 * Android keeps it alive and playing; it is the only place the motion sensors are ever read, and only while this ring lasts.
 */
class RingService : Service() {
    private var player: MediaPlayer? = null
    private var motion: MotionStop? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra("id", 0) ?: 0
        val name = intent?.getStringExtra("name").orEmpty()
        val room = intent?.getStringExtra("room").orEmpty()
        val text = intent?.getStringExtra("text").orEmpty()
        val nm = getSystemService(NotificationManager::class.java)
        Alarms.channelForRing(nm)
        val screen = PendingIntent.getActivity(
            this, id, Intent(this, AlarmActivity::class.java).putExtra("id", id).putExtra("name", name).putExtra("room", room),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, Alarms.RING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(name).setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM).setContentIntent(screen).setFullScreenIntent(screen, true).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Dismiss", Scheduler.broadcast(this, DISMISS, id, name, room)).build())
            .addAction(Notification.Action.Builder(null, "Snooze 5 min", Scheduler.broadcast(this, SNOOZE, id, name, room)).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(id.coerceAtLeast(1), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(id.coerceAtLeast(1), n)

        player?.release()
        player = runCatching { Alarms.player(this, alertSound, loop = true).also { it.start() } }.getOrNull()
        if (motionStop) {
            motion = MotionStop(getSystemService(SensorManager::class.java)) { stopSelf() }.also { it.start() }
        } else {
            // No sensors in this mode, so the buzz is safe to repeat: every few seconds until dismissed.
            handler.post(object : Runnable { override fun run() { runCatching { Alarms.buzz(this@RingService) }; handler.postDelayed(this, 4000) } })
        }
        handler.postDelayed({ stopSelf() }, 10 * 60_000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        motion?.stop(); motion = null
        player?.release(); player = null
        super.onDestroy()
    }
}


/**
 * Decides, reading by reading, whether the phone has been picked up or turned face down. No sensor code in here: the
 * readings are passed in, so every case can be tested.
 */
class MotionRule {
    private var armedForFlip = false
    private var downSince = 0L
    private var shaking = 0

    /** [sinceStartMs] since the alarm began, [nowMs] any steady clock, [z] gravity along the screen's normal, [total] the whole pull. */
    fun decide(sinceStartMs: Long, nowMs: Long, z: Float, total: Float): Boolean {
        if (sinceStartMs < SETTLE_MS) { armedForFlip = z > -FACE_DOWN; return false } // the phone is still being put down
        // Face down: z is about -9.8 when the screen faces the floor. A phone that was ALREADY face down when the alarm began
        // only counts after it has been turned up and down again.
        if (z > -3f) armedForFlip = true
        if (armedForFlip && z < -FACE_DOWN) { if (downSince == 0L) downSince = nowMs else if (nowMs - downSince > 400) return true } else downSince = 0
        // Picked up: the total pull moves away from plain gravity and stays disturbed.
        if (abs(total - GRAVITY) > MOVE) shaking++ else shaking = (shaking - 1).coerceAtLeast(0)
        return shaking >= 8
    }

    private companion object {
        const val SETTLE_MS = 1500L
        const val FACE_DOWN = 7f
        const val GRAVITY = 9.81f
        const val MOVE = 1.2f
    }
}

/**
 * Watches the accelerometer while an alarm rings, and calls [onStop] when the phone is picked up or turned face down.
 * Reads only gravity-scale numbers: nothing is stored or sent. Created and stopped by [RingService] alone.
 */
class MotionStop(private val sm: SensorManager, private val onStop: () -> Unit) : SensorEventListener {
    private val begun = SystemClock.elapsedRealtime()
    private val rule = MotionRule()
    private var fired = false

    fun start() { sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm.unregisterListener(this) }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (fired) return
        val now = SystemClock.elapsedRealtime()
        val z = e.values[2]
        if (rule.decide(now - begun, now, z, sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + z * z))) { fired = true; onStop() }
    }
}
