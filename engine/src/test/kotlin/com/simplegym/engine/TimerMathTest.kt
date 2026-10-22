package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimerMathTest {
    private val emom = EmomConfig(rounds = 3, roundSec = 120)
    private val interval = IntervalConfig(workSec = 30, restSec = 10, rounds = 3)

    private fun st(round: Int, left: Int, phase: Phase = Phase.WORK, finished: Boolean = false) =
        TimerState(round, left, phase, finished)

    @Test
    fun emomStartsAtRoundOneWithFullRound() {
        assertThat(emomState(emom, 0)).isEqualTo(st(1, 120))
    }

    @Test
    fun emomSecondsLeftIsCeiling() {
        assertThat(emomState(emom, 119_500)).isEqualTo(st(1, 1))
    }

    @Test
    fun emomRoundBoundaryStartsNextRound() {
        assertThat(emomState(emom, 120_000)).isEqualTo(st(2, 120))
    }

    @Test
    fun emomLastMillisecondIsStillRunning() {
        assertThat(emomState(emom, 359_999)).isEqualTo(st(3, 1))
    }

    @Test
    fun emomFinishesAtTotalDuration() {
        val s = emomState(emom, 360_000)
        assertThat(s.finished).isTrue()
        assertThat(s.secondsLeft).isEqualTo(0)
        assertThat(emomState(emom, 500_000).finished).isTrue()
    }

    @Test
    fun emomNegativeElapsedIsTreatedAsStart() {
        assertThat(emomState(emom, -5)).isEqualTo(st(1, 120))
    }

    @Test
    fun intervalWorkThenRestThenNextWork() {
        assertThat(intervalState(interval, 0)).isEqualTo(st(1, 30, Phase.WORK))
        assertThat(intervalState(interval, 31_000)).isEqualTo(st(1, 9, Phase.REST))
        assertThat(intervalState(interval, 40_000)).isEqualTo(st(2, 30, Phase.WORK))
    }

    @Test
    fun intervalLastRoundRestStillRunsBeforeFinish() {
        assertThat(intervalState(interval, 119_999)).isEqualTo(st(3, 1, Phase.REST))
        assertThat(intervalState(interval, 120_000).finished).isTrue()
    }

    @Test
    fun cueFirstStateIsRoundStart() {
        assertThat(cueBetween(null, st(1, 120))).isEqualTo(Cue.ROUND_START)
    }

    @Test
    fun cueCountdownAtThreeTwoOneWithinSameRound() {
        assertThat(cueBetween(st(1, 4), st(1, 3))).isEqualTo(Cue.COUNTDOWN)
        assertThat(cueBetween(st(1, 3), st(1, 2))).isEqualTo(Cue.COUNTDOWN)
        assertThat(cueBetween(st(1, 2), st(1, 1))).isEqualTo(Cue.COUNTDOWN)
    }

    @Test
    fun cueRoundChangeIsRoundStart() {
        assertThat(cueBetween(st(1, 1), st(2, 120))).isEqualTo(Cue.ROUND_START)
    }

    @Test
    fun cuePhaseChangeIsRoundStart() {
        assertThat(cueBetween(st(1, 1, Phase.WORK), st(1, 10, Phase.REST))).isEqualTo(Cue.ROUND_START)
    }

    @Test
    fun cueFinishWhenFinishedBecomesTrue() {
        assertThat(cueBetween(st(3, 1), st(3, 0, finished = true))).isEqualTo(Cue.FINISH)
    }

    @Test
    fun cueNoneForOrdinaryTickOrRepeatedState() {
        assertThat(cueBetween(st(1, 10), st(1, 9))).isNull()
        assertThat(cueBetween(st(1, 3), st(1, 3))).isNull()
        assertThat(cueBetween(st(3, 0, finished = true), st(3, 0, finished = true))).isNull()
    }

    @Test
    fun adjustRoundsClamps() {
        assertThat(adjustRounds(emom, -5).rounds).isEqualTo(1)
        assertThat(adjustRounds(emom, 200).rounds).isEqualTo(99)
        assertThat(adjustRounds(emom, 2).rounds).isEqualTo(5)
    }

    @Test
    fun adjustRoundSecStepsOfFifteenAndClamps() {
        assertThat(adjustRoundSec(emom, 1).roundSec).isEqualTo(135)
        assertThat(adjustRoundSec(EmomConfig(1, 15), -1).roundSec).isEqualTo(15)
        assertThat(adjustRoundSec(EmomConfig(1, 600), 1).roundSec).isEqualTo(600)
    }

    @Test
    fun rebaseKeepsRoundAndTimeIntoRound() {
        assertThat(rebaseEmom(emom, EmomConfig(3, 135), 150_000)).isEqualTo(165_000)
    }

    @Test
    fun rebaseClampsTimeIntoRoundToShorterRound() {
        assertThat(rebaseEmom(emom, EmomConfig(3, 60), 200_000)).isEqualTo(119_000)
    }
}
