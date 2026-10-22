package com.simplegym.timer

import com.google.common.truth.Truth.assertThat
import com.simplegym.engine.Cue
import com.simplegym.engine.Phase
import com.simplegym.engine.cueBetween
import org.junit.Test

/** What a service's first tick cues, given only the stored run and the clock (a plain JVM test: no Android here). */
class TickSeedTest {
    // 3 rounds of 120 s starting at elapsed 0, so "now" is also the running time.
    private val fresh = emomRun(startElapsedMs = 0)

    private fun firstCue(run: TimerRun, nowMs: Long) =
        cueBetween(seedPrevState(run, nowMs), run.stateAt(run.elapsedAt(nowMs)))

    @Test
    fun aFirstStartAnnouncesRoundOneAtOnce() {
        assertThat(seedPrevState(fresh, nowElapsedMs = 0)).isNull()
        assertThat(firstCue(fresh, 0)).isEqualTo(Cue.ROUND_START)
    }

    @Test
    fun aSlowColdStartStillAnnouncesRoundOne() {
        // The first tick came 2.5 s after the run began: the old "fresh run" window of 1.5 s dropped this cue.
        assertThat(firstCue(fresh, 2_500)).isEqualTo(Cue.ROUND_START)
        assertThat(firstCue(fresh, 30_000)).isEqualTo(Cue.ROUND_START)
    }

    @Test
    fun aRestartMidRoundAfterItsCueWasPlayedStaysSilent() {
        val cued = fresh.copy(lastCuedRound = 2, lastCuedPhase = Phase.WORK)
        // 150 s in: round 2, 90 s left.
        assertThat(firstCue(cued, 150_000)).isNull()
        // Even inside the countdown (118 s into round 2 is 2 s left): a restart does not replay the beeps.
        assertThat(firstCue(cued, 238_000)).isNull()
    }

    @Test
    fun aRestartAfterABoundaryThatWasNeverCuedAnnouncesTheNewRound() {
        val cuedRoundOne = fresh.copy(lastCuedRound = 1, lastCuedPhase = Phase.WORK)
        // The service died in round 1 and came back 130 s in: round 2 began meanwhile without a cue.
        assertThat(firstCue(cuedRoundOne, 130_000)).isEqualTo(Cue.ROUND_START)
    }

    @Test
    fun anIntervalRestartAfterAnUncuedRestPhaseAnnouncesIt() {
        val interval = emomRun().copy(kind = TimerRun.INTERVAL, workSec = 30, restSec = 10, rounds = 4)
        val workCued = interval.copy(lastCuedRound = 1, lastCuedPhase = Phase.WORK)
        assertThat(firstCue(workCued, 35_000)).isEqualTo(Cue.ROUND_START) // 35 s in: round 1's rest
        assertThat(firstCue(workCued.copy(lastCuedPhase = Phase.REST), 35_000)).isNull()
    }

    @Test
    fun aPausedRunIsSeededFromItsFrozenPosition() {
        // Paused 150 s in (round 2) with round 2 cued; a long wall-clock time later it is still round 2.
        val paused = fresh.copy(pausedAtElapsedMs = 150_000, lastCuedRound = 2, lastCuedPhase = Phase.WORK)
        assertThat(firstCue(paused, 9_000_000)).isNull()
    }
}
