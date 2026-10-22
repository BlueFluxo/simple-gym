package com.simplegym.timer

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.ui.timer.StandaloneTimerViewModel
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/** B1(c) and (d): a slot run that ends unseen leaves a pending log, and a new run never overwrites a finished one. */
@RunWith(RobolectricTestRunner::class)
class PendingLogTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: TimerRunStore

    @Before
    fun setUp() {
        TimerController.reset()
        store = TimerRunStore.from(context)
        store.clearPendingLog()
    }

    private fun startedLongAgo() = emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 1_000_000)

    private fun freshRun(slotId: Long, exerciseId: String) =
        emomRun(startElapsedMs = SystemClock.elapsedRealtime(), slotId = slotId).copy(exerciseId = exerciseId)

    private fun oneRoundRun() = emomRun(startElapsedMs = SystemClock.elapsedRealtime(), rounds = 1, roundSec = 15)

    private fun finishUnseen(run: TimerRun) {
        TimerController.start(context, run)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        TimerController.refresh(context) // the service tick that sees the end
    }

    @Test
    fun aSlotRunFinishingUnseenRecordsAPendingLog() {
        finishUnseen(oneRoundRun())

        assertThat(store.pendingLog()).isEqualTo(PendingLog(sessionId = 1, slotId = 7, exerciseId = "bench-press"))
        assertThat(store.load()).isNull()
    }

    @Test
    fun aRunStillGoingHasNoPendingLog() {
        TimerController.start(context, freshRun(7, "bench-press"))
        TimerController.refresh(context)
        assertThat(store.pendingLog()).isNull()
    }

    @Test
    fun aStandaloneOrIntervalRunRecordsNoPendingLog() {
        finishUnseen(emomRun(startElapsedMs = SystemClock.elapsedRealtime(), rounds = 1, roundSec = 15, slotId = null))
        assertThat(store.pendingLog()).isNull()

        TimerController.reset()
        val interval = TimerRun(
            kind = TimerRun.INTERVAL, rounds = 1, roundSec = 0, workSec = 5, restSec = 5,
            startElapsedMs = SystemClock.elapsedRealtime(), pausedAtElapsedMs = null, pausedTotalMs = 0,
            sessionId = 1, slotId = 7, exerciseId = "plank", mobilityAreas = listOf("NECK"),
        )
        finishUnseen(interval)
        assertThat(store.pendingLog()).isNull()
    }

    @Test
    fun detectKilledRunOnAFinishedStoredRunRecordsAPendingLogBeforeClearingIt() {
        store.save(emomRun(startElapsedMs = 0))

        val killed = detectKilledRun(store, isRunning = false, nowElapsed = 1_000_000)

        assertThat(killed).isNull()
        assertThat(store.load()).isNull()
        assertThat(store.pendingLog()).isEqualTo(PendingLog(1, 7, "bench-press"))
    }

    @Test
    fun aStoredRunFromBeforeARebootLeavesNoPendingLog() {
        store.save(emomRun(startElapsedMs = 500_000))

        assertThat(detectKilledRun(store, isRunning = false, nowElapsed = 1_000)).isNull()

        assertThat(store.pendingLog()).isNull()
    }

    @Test
    fun aNewerPendingLogReplacesTheOlderOne() {
        store.savePendingLog(PendingLog(1, 7, "bench-press"))
        store.savePendingLog(PendingLog(1, 8, "row"))
        assertThat(store.pendingLog()).isEqualTo(PendingLog(1, 8, "row"))
    }

    @Test
    fun clearingOnlyRemovesAMatchingPendingLog() {
        store.savePendingLog(PendingLog(1, 7, "bench-press"))
        store.clearPendingLog(sessionId = 1, slotId = 8)
        assertThat(store.pendingLog()).isNotNull()
        store.clearPendingLog(sessionId = 1, slotId = 7)
        assertThat(store.pendingLog()).isNull()
    }

    @Test
    fun theRunStoreClearAndStopLeaveThePendingLogAlone() {
        store.savePendingLog(PendingLog(1, 7, "bench-press"))
        store.save(emomRun())
        store.clear()
        TimerController.stop(context)
        assertThat(store.pendingLog()).isEqualTo(PendingLog(1, 7, "bench-press"))
    }

    @Test
    fun stoppingARunByHandRecordsNoPendingLog() {
        TimerController.start(context, freshRun(7, "bench-press"))
        TimerController.stop(context)
        assertThat(store.pendingLog()).isNull()
    }

    @Test
    fun theControllersAcknowledgeOnlyResetsTheStateAndLeavesThePendingLog() {
        finishUnseen(oneRoundRun())
        assertThat(store.pendingLog()).isNotNull()

        TimerController.acknowledgeFinished()

        assertThat(TimerController.state.value).isEqualTo(TimerUiState.IDLE)
        assertThat(store.pendingLog()).isEqualTo(PendingLog(1, 7, "bench-press"))
    }

    // The standalone and mobility screens acknowledge finished runs too; a slot run's pending log is not theirs to settle.
    @Test
    fun openingTheStandaloneTimerAfterASlotRunFinishedUnseenKeepsThePendingLog() {
        finishUnseen(oneRoundRun())

        val viewModel = StandaloneTimerViewModel(context)

        assertThat(store.pendingLog()).isEqualTo(PendingLog(1, 7, "bench-press"))
        assertThat(TimerController.state.value.finished).isTrue() // the slot run is not this screen's to acknowledge
        viewModel.finishHandled()
        assertThat(store.pendingLog()).isEqualTo(PendingLog(1, 7, "bench-press"))
    }

    @Test
    fun startOrAttachNeverOverwritesAnUnacknowledgedFinishedRunOfTheSameWorkout() {
        val a = oneRoundRun()
        finishUnseen(a)

        val result = TimerController.startOrAttach(context, a.copy(startElapsedMs = SystemClock.elapsedRealtime()))

        assertThat(result).isEqualTo(a) // the finished run, not a new one
        assertThat(TimerController.state.value.finished).isTrue()
        assertThat(store.load()).isNull() // nothing new was started
    }

    @Test
    fun startingAnotherWorkoutAfterAnUnseenFinishKeepsTheFinishedOnesPendingLog() {
        finishUnseen(oneRoundRun())

        val b = freshRun(slotId = 8, exerciseId = "row")
        assertThat(TimerController.startOrAttach(context, b)).isEqualTo(b)

        assertThat(store.pendingLog()).isEqualTo(PendingLog(1, 7, "bench-press"))
        assertThat(store.load()).isEqualTo(b)
    }

    @Test
    fun aFinishedStoredRunIsNotOverwrittenByStartOrAttachEither() {
        store.save(startedLongAgo())

        val b = freshRun(slotId = 8, exerciseId = "row")
        TimerController.startOrAttach(context, b)

        assertThat(store.pendingLog()).isEqualTo(PendingLog(1, 7, "bench-press"))
    }

    @Test
    fun attachJoinsARunInProgressButNeverStartsOne() {
        val wanted = freshRun(7, "bench-press")
        assertThat(TimerController.attach(context, wanted)).isNull()
        assertThat(store.load()).isNull()

        store.save(wanted) // left by a killed process
        assertThat(TimerController.attach(context, wanted)).isEqualTo(wanted)
        assertThat(TimerController.attach(context, freshRun(8, "row"))).isNull()
    }

    @Test
    fun observingThePendingLogEmitsTheCurrentValue() = runBlocking {
        assertThat(store.observePendingLog().first()).isNull()
        store.savePendingLog(PendingLog(1, 7, "bench-press"))
        assertThat(store.observePendingLog().first()).isEqualTo(PendingLog(1, 7, "bench-press"))
    }
}
