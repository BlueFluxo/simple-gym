package com.simplegym.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.simplegym.R
import com.simplegym.engine.Cue
import com.simplegym.engine.Phase
import com.simplegym.engine.TimerState
import com.simplegym.engine.cueBetween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the timer alive with the screen off: a foreground service (type specialUse) with a lock-screen notification,
 * a partial wake lock, and a 200 ms tick that plays the cues. It is deliberately thin. Every position comes from
 * [TimerController.refresh], i.e. from the stored run and `elapsedRealtime()`, so nothing here is state that dying
 * could lose: when the service is killed, Home offers to resume the same run.
 *
 * Everything here runs on the main thread: the system calls the lifecycle methods there, and the tick coroutine runs on
 * [Dispatchers.Main] too (it only delays and makes cheap calls), so the fields below need no locking and the wake lock
 * can be neither double-acquired nor leaked by a race between a tick and [onStartCommand] or [onDestroy].
 */
class TimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cues: Cues? = null
    private var lastNotificationKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TimerController.onServiceCreated()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground first, whatever happens next: a service started with startForegroundService that never
        // calls it is a crash. A null intent is the system restarting a killed service.
        val loaded = TimerController.ensureLoaded(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(loaded), foregroundType())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not enter the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_PAUSE -> TimerController.pause(this)
            ACTION_RESUME -> TimerController.resume(this)
            ACTION_STOP -> TimerController.stop(this)
        }
        val ui = TimerController.refresh(this)
        if (ui.run == null || ui.finished) {
            shutdown()
            return START_NOT_STICKY
        }
        syncWakeLock(ui)
        postNotification(ui)
        if (tickJob?.isActive != true) tickJob = scope.launch { tick() }
        return START_STICKY
    }

    private suspend fun tick() {
        var prev: TimerState? = null
        var first = true
        while (scope.isActive) {
            val ui = TimerController.refresh(this)
            val run = ui.run
            val timer = ui.timer
            if (run == null || timer == null) {
                shutdown()
                return
            }
            if (first) {
                first = false
                // What the run has already cued decides this, never how old it is: see seedPrevState.
                prev = seedPrevState(run, SystemClock.elapsedRealtime())
            }
            cueBetween(prev, timer)?.let { cue ->
                cues().play(cue)
                if (cue == Cue.ROUND_START) TimerController.recordStartCue(this, timer)
            }
            prev = timer
            if (timer.finished) {
                // Let the finish tone sound before the service (and its ToneGenerator) goes away.
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                delay(Cues.FINISH_TONE_MS + FINISH_GRACE_MS)
                shutdown()
                return
            }
            postNotification(ui)
            delay(TICK_MS)
        }
    }

    private fun cues(): Cues = cues ?: Cues(this).also { cues = it }

    /**
     * Held for the run's remaining time + 60 s and not at all while paused. There is one lock for the life of the
     * service, not reference counted: taking it again only moves its timeout (when the run was adjusted), so it can
     * never be held twice, and one release always frees it.
     */
    private fun syncWakeLock(ui: TimerUiState) {
        val run = ui.run ?: return
        if (ui.paused) {
            releaseWakeLock()
            return
        }
        val remainingMs = run.totalMs() - run.elapsedAt(SystemClock.elapsedRealtime())
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "simplegym:timer")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(remainingMs.coerceAtLeast(0) + WAKE_LOCK_SLACK_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun shutdown() {
        tickJob?.cancel()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tickJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        wakeLock = null
        cues?.close()
        cues = null
        TimerController.onServiceDestroyed()
        super.onDestroy()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Timer", NotificationManager.IMPORTANCE_LOW).apply {
            description = "The running workout timer"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Re-posts only when the text changes (once a second), so the notification is not rebuilt on every tick. */
    private fun postNotification(ui: TimerUiState) {
        val key = "${notificationTitle(ui.run, ui.timer)}|${ui.paused}"
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(ui))
    }

    private fun buildNotification(ui: TimerUiState): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(notificationTitle(ui.run, ui.timer))
            .setContentText(notificationDetail(ui))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
        if (ui.run != null) {
            val toggle = if (ui.paused) ACTION_RESUME else ACTION_PAUSE
            builder.addAction(0, if (ui.paused) "Resume" else "Pause", servicePendingIntent(toggle))
            builder.addAction(0, "Stop", servicePendingIntent(ACTION_STOP))
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, TimerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "timer"
        const val ACTION_PAUSE = "com.simplegym.timer.PAUSE"
        const val ACTION_RESUME = "com.simplegym.timer.RESUME"
        const val ACTION_STOP = "com.simplegym.timer.STOP"
        private const val TAG = "TimerService"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 200L
        private const val FINISH_GRACE_MS = 300L
        private const val WAKE_LOCK_SLACK_MS = 60_000L
    }
}

/** "Round 2 / 3 · 1:42": the notification's headline. */
internal fun notificationTitle(run: TimerRun?, timer: TimerState?): String =
    if (run == null || timer == null) {
        "Timer"
    } else {
        "Round ${timer.round} / ${run.rounds} · ${formatClock(timer.secondsLeft)}"
    }

private fun notificationDetail(ui: TimerUiState): String = when {
    ui.run == null -> "Starting"
    ui.paused -> "Paused"
    ui.run.kind == TimerRun.INTERVAL -> if (ui.timer?.phase == Phase.REST) "Rest" else "Work"
    else -> "EMOM"
}
