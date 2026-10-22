package com.simplegym.domain

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.data.GymDatabase
import com.simplegym.inMemoryDb
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerRun
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
class MobilityRecorderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: GymDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val today = LocalDate.of(2026, 10, 22)

    @Before
    fun setUp() {
        TimerController.reset()
        db = inMemoryDb()
    }

    @After
    fun tearDown() {
        scope.cancel()
        TimerController.reset()
        db.close()
    }

    private fun mobilityRun(area: String) = TimerRun(
        kind = TimerRun.INTERVAL, rounds = 1, roundSec = 0, workSec = 30, restSec = 10,
        startElapsedMs = SystemClock.elapsedRealtime(), pausedAtElapsedMs = null, pausedTotalMs = 0,
        sessionId = null, slotId = null, exerciseId = null, mobilityAreas = listOf(area), moveNames = listOf("Move"),
    )

    private fun rows() = runBlocking { db.sessionDao().observeMobilitySince(today.minusDays(60)).first() }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    private fun runToTheEnd(area: String) {
        TimerController.start(context, mobilityRun(area))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        TimerController.refresh(context) // what the service tick does
    }

    @Test
    fun aFailedRecordIsLoggedAndALaterRunIsStillRecorded() {
        val calls = AtomicInteger()
        // The first record fails (the date lookup throws); the watcher must survive it.
        val recorder = MobilityRecorder(db) { if (calls.getAndIncrement() == 0) error("boom") else today }
        recorder.start(scope)

        runToTheEnd("NECK")
        waitFor("the first record attempt") { calls.get() >= 1 }
        assertThat(rows()).isEmpty()
        TimerController.acknowledgeFinished()

        ShadowSystemClock.advanceBy(Duration.ofMinutes(5)) // a different start instant
        runToTheEnd("HIPS")

        waitFor("the later run to be recorded") { rows().isNotEmpty() }
        assertThat(rows().map { it.area }).containsExactly("HIPS")
        assertThat(rows().single().date).isEqualTo(today)
    }

    @Test
    fun aRunIsRecordedOnceHoweverOftenItIsRecorded() = runBlocking<Unit> {
        val recorder = MobilityRecorder(db) { today }
        val run = mobilityRun("CORE")

        recorder.record(run)
        recorder.record(run)

        assertThat(rows().map { it.area }).containsExactly("CORE")
    }
}
