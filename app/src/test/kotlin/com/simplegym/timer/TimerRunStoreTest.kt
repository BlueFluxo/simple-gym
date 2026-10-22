package com.simplegym.timer

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.engine.Phase
import com.simplegym.engine.emomState
import java.time.Duration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

fun emomRun(
    startElapsedMs: Long = 0,
    rounds: Int = 3,
    roundSec: Int = 120,
    slotId: Long? = 7,
    pausedAt: Long? = null,
    pausedTotal: Long = 0,
) = TimerRun(
    kind = TimerRun.EMOM, rounds = rounds, roundSec = roundSec, workSec = 0, restSec = 0,
    startElapsedMs = startElapsedMs, pausedAtElapsedMs = pausedAt, pausedTotalMs = pausedTotal,
    sessionId = if (slotId == null) null else 1L, slotId = slotId,
    exerciseId = if (slotId == null) null else "bench-press",
)

@RunWith(RobolectricTestRunner::class)
class TimerRunStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: TimerRunStore

    @Before
    fun setUp() {
        TimerController.reset()
        store = TimerRunStore.from(context)
    }

    @Test
    fun saveLoadRoundTripsEveryField() {
        val run = TimerRun(
            kind = TimerRun.INTERVAL, rounds = 8, roundSec = 0, workSec = 30, restSec = 10,
            startElapsedMs = 5_000, pausedAtElapsedMs = 9_000, pausedTotalMs = 1_500,
            sessionId = 3, slotId = 4, exerciseId = "plank",
        )
        store.save(run)
        assertThat(store.load()).isEqualTo(run)
        assertThat(store.load()!!.interval().workSec).isEqualTo(30)
        store.save(emomRun(slotId = null))
        assertThat(store.load()).isEqualTo(emomRun(slotId = null))
        assertThat(store.load()!!.emom().roundSec).isEqualTo(120)
    }

    @Test
    fun theLastCuedRoundAndPhaseSurviveASaveAndLoad() {
        val run = emomRun().copy(lastCuedRound = 2, lastCuedPhase = Phase.REST)
        store.save(run)
        assertThat(store.load()).isEqualTo(run)
    }

    @Test
    fun aRunStoredBeforeTheCueSeedExistedStillLoadsWithNoCueRecorded() {
        val old = """{"kind":"EMOM","rounds":3,"roundSec":120,"workSec":0,"restSec":0,"startElapsedMs":5000,""" +
            """"pausedAtElapsedMs":null,"pausedTotalMs":0,"sessionId":1,"slotId":7,"exerciseId":"bench-press"}"""
        context.getSharedPreferences("timer_run", Context.MODE_PRIVATE).edit().putString("run", old).commit()

        val loaded = store.load()!!

        assertThat(loaded.startElapsedMs).isEqualTo(5_000)
        assertThat(loaded.lastCuedRound).isEqualTo(0)
        assertThat(loaded.lastCuedPhase).isNull()
        assertThat(loaded.isMobility).isFalse() // nor a mobility routine
        assertThat(loaded.moveNames).isEmpty()
    }

    @Test
    fun aMobilityRunKeepsItsAreasAndMoveNamesThroughASaveAndLoad() {
        val run = emomRun(slotId = null).copy(
            kind = TimerRun.INTERVAL, workSec = 30, restSec = 10, rounds = 2,
            mobilityAreas = listOf("NECK", "HIPS"), moveNames = listOf("Chin tucks", "90/90 hip stretch"),
        )
        store.save(run)

        assertThat(store.load()).isEqualTo(run)
        assertThat(store.load()!!.isMobility).isTrue()
    }

    @Test
    fun recordingAStartCueStoresItWithTheRunAndPublishesIt() {
        val run = emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 130_000)
        TimerController.start(context, run)
        val timer = TimerController.state.value.timer!!

        TimerController.recordStartCue(context, timer)

        val expected = run.copy(lastCuedRound = 2, lastCuedPhase = Phase.WORK)
        assertThat(store.load()).isEqualTo(expected)
        assertThat(TimerController.state.value.run).isEqualTo(expected)
        // A later pause saves the in-memory run, so it must carry the cue too.
        TimerController.pause(context)
        assertThat(store.load()!!.lastCuedRound).isEqualTo(2)
    }

    @Test
    fun aFinishedOrStoppedRunIsNeverBroughtBackByRecordingACue() {
        val run = emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 130_000)
        TimerController.start(context, run)
        val timer = TimerController.state.value.timer!!
        TimerController.stop(context)

        TimerController.recordStartCue(context, timer)

        assertThat(store.load()).isNull()
        assertThat(TimerController.state.value).isEqualTo(TimerUiState.IDLE)
    }

    @Test
    fun loadIsNullWhenNothingIsSavedOrAfterClear() {
        assertThat(store.load()).isNull()
        store.save(emomRun())
        store.clear()
        assertThat(store.load()).isNull()
    }

    @Test
    fun aCorruptedRecordLoadsAsNull() {
        context.getSharedPreferences("timer_run", Context.MODE_PRIVATE).edit().putString("run", "{not json").commit()
        assertThat(store.load()).isNull()
    }

    @Test
    fun elapsedExcludesPausedTime() {
        assertThat(store.elapsedMs(emomRun(startElapsedMs = 1_000, pausedAt = 61_000), nowElapsedMs = 100_000))
            .isEqualTo(60_000)
        assertThat(store.elapsedMs(emomRun(startElapsedMs = 1_000, pausedTotal = 20_000), nowElapsedMs = 141_000))
            .isEqualTo(120_000)
    }

    @Test
    fun adjustingRoundTimeKeepsThePlaceInTheCurrentRound() {
        val run = emomRun(startElapsedMs = 0, rounds = 3, roundSec = 120)
        val adjusted = run.adjusted(rounds = 3, roundSec = 135, nowElapsedMs = 150_000)

        assertThat(adjusted.roundSec).isEqualTo(135)
        assertThat(store.elapsedMs(adjusted, 150_000)).isEqualTo(165_000) // 1 round of 135 s + the same 30 s in
        assertThat(emomState(adjusted.emom(), store.elapsedMs(adjusted, 150_000)).round).isEqualTo(2)
    }

    @Test
    fun adjustingNeverRemovesARoundThatHasStarted() {
        val run = emomRun(rounds = 4, roundSec = 120)
        val adjusted = run.adjusted(rounds = 1, roundSec = 120, nowElapsedMs = 250_000) // in round 3
        assertThat(adjusted.rounds).isEqualTo(3)
        assertThat(store.elapsedMs(adjusted, 250_000)).isEqualTo(250_000)
    }

    @Test
    fun adjustingAPausedRunUsesThePauseInstantAndKeepsItPaused() {
        val run = emomRun(startElapsedMs = 0, pausedAt = 150_000)
        val adjusted = run.adjusted(rounds = 3, roundSec = 135, nowElapsedMs = 900_000)
        assertThat(adjusted.pausedAtElapsedMs).isEqualTo(150_000)
        assertThat(store.elapsedMs(adjusted, 900_000)).isEqualTo(165_000)
    }

    @Test
    fun anIntervalRunIsNotAdjustable() {
        val run = TimerRun(TimerRun.INTERVAL, 4, 0, 30, 10, 0, null, 0, null, null, null)
        assertThat(run.adjusted(rounds = 9, roundSec = 60, nowElapsedMs = 5_000)).isEqualTo(run)
    }

    // Review Focus 2: the timer service is killed mid-EMOM.
    @Test
    fun aKilledRunIsRecomputedAtTheRightRoundAndDetected() {
        store.save(emomRun(startElapsedMs = 0))
        val now = 130_000L

        val run = store.load()!!
        assertThat(emomState(run.emom(), store.elapsedMs(run, now)).round).isEqualTo(2)

        val killed = detectKilledRun(store, isRunning = false, nowElapsed = now)
        assertThat(killed).isNotNull()
        assertThat(killed!!.timer.round).isEqualTo(2)
        assertThat(killed.timer.secondsLeft).isEqualTo(110)
        assertThat(killed.timer.phase).isEqualTo(Phase.WORK)
        assertThat(killed.run).isEqualTo(run)
        assertThat(store.load()).isEqualTo(run) // kept, so Resume restarts the same run
    }

    @Test
    fun aRunWhoseServiceIsStillRunningIsNotKilled() {
        store.save(emomRun())
        assertThat(detectKilledRun(store, isRunning = true, nowElapsed = 130_000)).isNull()
        assertThat(store.load()).isNotNull()
    }

    @Test
    fun noStoredRunMeansNothingWasKilled() {
        assertThat(detectKilledRun(store, isRunning = false, nowElapsed = 130_000)).isNull()
    }

    @Test
    fun aRunThatFinishedWhileKilledIsClearedNotOffered() {
        store.save(emomRun(rounds = 3, roundSec = 120))
        assertThat(detectKilledRun(store, isRunning = false, nowElapsed = 360_000)).isNull()
        assertThat(store.load()).isNull()
    }

    @Test
    fun aRunFromBeforeARebootIsDiscarded() {
        // elapsedRealtime restarts at boot, so the stored start instant is in the future.
        store.save(emomRun(startElapsedMs = 5_000_000))
        assertThat(detectKilledRun(store, isRunning = false, nowElapsed = 40_000)).isNull()
        assertThat(store.load()).isNull()
    }

    @Test
    fun formatsClockAsMinutesAndSeconds() {
        assertThat(formatClock(120)).isEqualTo("2:00")
        assertThat(formatClock(59)).isEqualTo("0:59")
        assertThat(formatClock(0)).isEqualTo("0:00")
        assertThat(formatClock(605)).isEqualTo("10:05")
    }

    // ---- TimerController ----

    private fun advance(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    @Test
    fun startPublishesTheStateAndSavesTheRunUnchanged() {
        val run = emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 130_000)
        TimerController.start(context, run)

        val ui = TimerController.state.value
        assertThat(ui.running).isTrue()
        assertThat(ui.paused).isFalse()
        assertThat(ui.run).isEqualTo(run) // Resume after a kill must not rewrite the start instant
        assertThat(ui.timer!!.round).isEqualTo(2)
        assertThat(store.load()).isEqualTo(run)
        assertThat(TimerController.isServiceRunning()).isTrue()
    }

    @Test
    fun pauseFreezesTheRunAndResumeContinuesFromThere() {
        TimerController.start(context, emomRun(startElapsedMs = SystemClock.elapsedRealtime()))
        advance(30_000)
        TimerController.pause(context)
        val paused = TimerController.state.value
        assertThat(paused.paused).isTrue()
        assertThat(paused.timer!!.secondsLeft).isEqualTo(90)

        advance(500_000)
        assertThat(TimerController.refresh(context).timer!!.secondsLeft).isEqualTo(90)
        assertThat(store.load()!!.pausedAtElapsedMs).isNotNull()

        TimerController.resume(context)
        assertThat(TimerController.state.value.paused).isFalse()
        advance(10_000)
        assertThat(TimerController.refresh(context).timer!!.secondsLeft).isEqualTo(80)
    }

    @Test
    fun adjustRewritesAndSavesTheRunningConfig() {
        TimerController.start(context, emomRun(startElapsedMs = SystemClock.elapsedRealtime()))
        advance(150_000)
        TimerController.adjust(context, rounds = 4, roundSec = 135)

        val saved = store.load()!!
        assertThat(saved.rounds).isEqualTo(4)
        assertThat(saved.roundSec).isEqualTo(135)
        assertThat(store.elapsedMs(saved, SystemClock.elapsedRealtime())).isEqualTo(165_000)
        assertThat(TimerController.state.value.run).isEqualTo(saved)
        assertThat(TimerController.state.value.timer!!.round).isEqualTo(2)
    }

    @Test
    fun stopClearsTheStoreAndGoesIdle() {
        TimerController.start(context, emomRun(startElapsedMs = SystemClock.elapsedRealtime()))
        TimerController.stop(context)
        assertThat(store.load()).isNull()
        assertThat(TimerController.state.value).isEqualTo(TimerUiState.IDLE)
    }

    @Test
    fun finishingClearsTheStoreButKeepsTheFinishedStateUntilAcknowledged() {
        val run = emomRun(startElapsedMs = SystemClock.elapsedRealtime(), rounds = 1, roundSec = 15)
        TimerController.start(context, run)
        advance(16_000)

        val ui = TimerController.refresh(context)
        assertThat(ui.timer!!.finished).isTrue()
        assertThat(ui.running).isFalse()
        assertThat(ui.run).isEqualTo(run)
        assertThat(store.load()).isNull()

        TimerController.acknowledgeFinished()
        assertThat(TimerController.state.value).isEqualTo(TimerUiState.IDLE)
    }

    @Test
    fun ensureLoadedRestoresTheStoredRunAfterProcessDeath() {
        val run = emomRun(startElapsedMs = SystemClock.elapsedRealtime() - 130_000)
        store.save(run) // a fresh process: the store has a run, the controller knows nothing
        assertThat(TimerController.state.value.run).isNull()

        val ui = TimerController.ensureLoaded(context)
        assertThat(ui.run).isEqualTo(run)
        assertThat(ui.timer!!.round).isEqualTo(2)
    }
}
