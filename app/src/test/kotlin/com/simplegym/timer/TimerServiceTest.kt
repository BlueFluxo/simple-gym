package com.simplegym.timer

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.ToneGenerator
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.engine.Phase
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowToneGenerator

@RunWith(RobolectricTestRunner::class)
class TimerServiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var controller: org.robolectric.android.controller.ServiceController<TimerService>? = null

    @Before
    fun setUp() {
        TimerController.reset()
        ShadowPowerManager.clearWakeLocks()
        ShadowToneGenerator.reset()
    }

    @After
    fun tearDown() {
        controller?.destroy()
    }

    private fun startService(action: String? = null): TimerService {
        val intent = Intent(context, TimerService::class.java).apply { this.action = action }
        val built = Robolectric.buildService(TimerService::class.java, intent).create()
        controller = built
        built.startCommand(0, 1)
        return built.get()
    }

    /** Delivers another start command (a notification action, or the controller waking the service). */
    private fun deliver(service: TimerService, action: String? = null) {
        service.onStartCommand(Intent(context, TimerService::class.java).apply { this.action = action }, 0, 2)
    }

    private fun idleFor(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun playedTones() = ShadowToneGenerator.getPlayedTones().map { it.type() }

    private fun title(notification: Notification) = notification.extras.getString(Notification.EXTRA_TITLE)

    @Test
    fun goesForegroundWithRoundAndTimeOnAPublicTimerChannelNotification() {
        TimerController.start(context, emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 130_000))

        val service = startService()

        val notification = shadowOf(service).lastForegroundNotification!!
        assertThat(title(notification)).isEqualTo("Round 2 / 3 · 1:50")
        assertThat(notification.channelId).isEqualTo("timer")
        assertThat(notification.visibility).isEqualTo(Notification.VISIBILITY_PUBLIC)
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Pause", "Stop").inOrder()
        assertThat(TimerController.isServiceRunning()).isTrue()
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("timer")
        assertThat(channel.lockscreenVisibility).isEqualTo(Notification.VISIBILITY_PUBLIC)
    }

    @Test
    fun aRestartedServiceWithNoIntentPicksTheStoredRunBackUp() {
        // A killed process: only the store survives.
        TimerRunStore.from(context).save(emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 250_000))
        assertThat(TimerController.state.value.run).isNull()

        val service = startService()

        assertThat(title(shadowOf(service).lastForegroundNotification!!)).isEqualTo("Round 3 / 3 · 1:50")
        assertThat(TimerController.state.value.run).isNotNull()
    }

    @Test
    fun pauseFromTheNotificationPausesTheRunAndOffersResume() {
        TimerController.start(context, emomRun(startElapsedMs = SystemClock.elapsedRealtime()))

        val service = startService(TimerService.ACTION_PAUSE)

        assertThat(TimerController.state.value.paused).isTrue()
        val shown = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.last()
        assertThat(shown.actions.map { it.title.toString() }).containsExactly("Resume", "Stop").inOrder()
        assertThat(service).isNotNull()
    }

    @Test
    fun stopFromTheNotificationClearsTheRunAndEndsTheService() {
        TimerController.start(context, emomRun(startElapsedMs = SystemClock.elapsedRealtime()))

        val service = startService(TimerService.ACTION_STOP)

        assertThat(TimerRunStore.from(context).load()).isNull()
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    @Test
    fun withNothingToRunItStillGoesForegroundThenStops() {
        val service = startService()

        assertThat(shadowOf(service).isForegroundStopped).isTrue() // it went foreground first, then left again
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    // Review Focus 2 / Task 11c: the wake lock is the one resource the service must never leak or double-acquire.
    @Test
    fun theWakeLockIsHeldWhileRunningReleasedWhilePausedAndTakenAgainOnResume() {
        TimerController.start(context, emomRun(startElapsedMs = SystemClock.elapsedRealtime()))

        val service = startService()
        val lock = checkNotNull(ShadowPowerManager.getLatestWakeLock())
        assertThat(lock.isHeld).isTrue()
        assertThat(shadowOf(lock).tag).isEqualTo("simplegym:timer")
        assertThat(shadowOf(lock).isReferenceCounted).isFalse()

        deliver(service) // the controller waking the service again: still the one lock, still held
        assertThat(ShadowPowerManager.getLatestWakeLock()).isSameInstanceAs(lock)
        assertThat(lock.isHeld).isTrue()

        deliver(service, TimerService.ACTION_PAUSE)
        assertThat(lock.isHeld).isFalse()

        deliver(service, TimerService.ACTION_RESUME)
        assertThat(ShadowPowerManager.getLatestWakeLock()).isSameInstanceAs(lock) // no second lock was created
        assertThat(lock.isHeld).isTrue()

        deliver(service, TimerService.ACTION_STOP)
        assertThat(lock.isHeld).isFalse()
    }

    @Test
    fun stoppingFromTheNotificationReleasesTheWakeLock() {
        TimerController.start(context, emomRun(startElapsedMs = SystemClock.elapsedRealtime()))
        val service = startService()
        val lock = checkNotNull(ShadowPowerManager.getLatestWakeLock())
        assertThat(lock.isHeld).isTrue()

        deliver(service, TimerService.ACTION_STOP)

        assertThat(lock.isHeld).isFalse()
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    @Test
    fun aRunThatFinishesWhileTheServiceTicksReleasesTheWakeLockAndStopsTheService() {
        // The tick coroutine lives on the main looper, so idling the looper is what moves it forward.
        TimerController.start(context, emomRun(startElapsedMs = SystemClock.elapsedRealtime(), rounds = 1, roundSec = 15))
        val service = startService()
        val lock = checkNotNull(ShadowPowerManager.getLatestWakeLock())
        assertThat(lock.isHeld).isTrue()

        idleFor(17)

        assertThat(TimerController.state.value.finished).isTrue()
        assertThat(lock.isHeld).isFalse()
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    @Test
    fun destroyingTheServiceReleasesTheWakeLock() {
        TimerController.start(context, emomRun(startElapsedMs = SystemClock.elapsedRealtime()))
        startService()
        val lock = checkNotNull(ShadowPowerManager.getLatestWakeLock())

        controller!!.destroy()
        controller = null

        assertThat(lock.isHeld).isFalse()
    }

    @Test
    fun theFirstTickPlaysAndRecordsRoundOnesStartCueNoMatterHowLateItRuns() {
        // The run began 2.5 s before the service got to tick: still a genuine first start.
        val run = emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 2_500)
        TimerController.start(context, run)
        startService()

        idleFor(1)

        assertThat(playedTones()).containsExactly(ToneGenerator.TONE_PROP_ACK) // the round-start tone, once
        assertThat(TimerRunStore.from(context).load()).isEqualTo(run.copy(lastCuedRound = 1, lastCuedPhase = Phase.WORK))
    }

    @Test
    fun aRestartedServiceDoesNotReplayTheCueTheStoredRunAlreadyPlayed() {
        val run = emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 150_000)
            .copy(lastCuedRound = 2, lastCuedPhase = Phase.WORK)
        TimerRunStore.from(context).save(run)
        startService()

        idleFor(1)

        assertThat(playedTones()).isEmpty()
        assertThat(TimerRunStore.from(context).load()).isEqualTo(run)

        idleFor(95) // 246 s in: round 3 began, and only that start is announced
        assertThat(playedTones().count { it == ToneGenerator.TONE_PROP_ACK }).isEqualTo(1)
        assertThat(TimerRunStore.from(context).load()!!.lastCuedRound).isEqualTo(3)
    }

    @Test
    fun aRestartedServiceAnnouncesARoundThatBeganWhileNoServiceWasTicking() {
        val run = emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 130_000)
            .copy(lastCuedRound = 1, lastCuedPhase = Phase.WORK)
        TimerRunStore.from(context).save(run)
        startService()

        idleFor(1)

        assertThat(playedTones()).containsExactly(ToneGenerator.TONE_PROP_ACK)
        assertThat(TimerRunStore.from(context).load()!!.lastCuedRound).isEqualTo(2)
    }

    @Test
    fun theManifestDeclaresASpecialUseForegroundServiceWithItsSubtype() {
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, TimerService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertThat(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE).isNotEqualTo(0)
        assertThat(info.exported).isFalse()

        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertThat(requested).containsAtLeast(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WAKE_LOCK",
            "android.permission.VIBRATE",
        )
        assertThat(requested).doesNotContain("android.permission.INTERNET")
    }

    @Test
    fun theNotificationTitleReadsRoundAndTime() {
        val run = emomRun()
        assertThat(notificationTitle(run, run.stateAt(0))).isEqualTo("Round 1 / 3 · 2:00")
        assertThat(notificationTitle(null, null)).isEqualTo("Timer")
    }
}
