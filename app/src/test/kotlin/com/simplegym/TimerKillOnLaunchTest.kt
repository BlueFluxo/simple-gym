package com.simplegym

import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.data.GymDatabase
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerRunStore
import com.simplegym.timer.emomRun
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Counts calls to [recordTimerKilled], which MainActivity decides on, synchronously, in onCreate. */
class RecordingContainer(db: GymDatabase) : AppContainer(ApplicationProvider.getApplicationContext(), db, { TEST_NOW }) {
    var timerKilledCalls = 0
        private set

    override suspend fun recordTimerKilled() {
        timerKilledCalls++
        super.recordTimerKilled()
    }
}

class RecordingGymApp : SimpleGymApp() {
    override fun createContainer(): AppContainer = RecordingContainer(inMemoryDb())
}

/** Review Focus 2: the kill is recorded when the app starts, whatever screen it reopens on. */
@RunWith(RobolectricTestRunner::class)
@Config(application = RecordingGymApp::class, qualifiers = "w411dp-h891dp")
class TimerKillOnLaunchTest {
    @Before
    fun setUp() = TimerController.reset()

    private fun containerOf(activity: MainActivity) = (activity.application as SimpleGymApp).container as RecordingContainer

    private fun timerWasKilled(container: AppContainer): Boolean = runBlocking {
        container.db.settingsDao().get()?.timerWasKilled == true
    }

    /**
     * The work being waited for runs on the main looper (the activity's coroutine) and on Room's threads (seeding and
     * the write), so the looper is pumped until [condition] holds; the deadline only turns a hang into a failure.
     */
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in 5 s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
    }

    @Test
    fun launchingWithAStoredRunAndNoServiceRecordsTheKill() {
        TimerRunStore.from(ApplicationProvider.getApplicationContext())
            .save(emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 130_000))

        // Only created, never resumed: Home's own check (on resume) must not be what records it.
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        val container = containerOf(activity)
        assertThat(container.timerKilledCalls).isEqualTo(1)
        waitUntil { timerWasKilled(container) }
    }

    @Test
    fun launchingWhileTheServiceIsRunningDoesNotRecordAKill() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TimerController.start(context, emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 130_000))

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        // The decision is made synchronously in onCreate, so there is nothing to wait for: it was never recorded.
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(containerOf(activity).timerKilledCalls).isEqualTo(0)
        waitUntil { containerOf(activity).seeded.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(timerWasKilled(containerOf(activity))).isFalse()
    }
}
