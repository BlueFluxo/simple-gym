package com.simplegym.ui.timer

import com.google.common.truth.Truth.assertThat
import com.simplegym.data.SlotEntity
import com.simplegym.engine.EmomConfig
import com.simplegym.engine.RangeType
import com.simplegym.engine.RepRange
import com.simplegym.engine.SessionType
import com.simplegym.engine.Target
import com.simplegym.timer.TimerRun
import com.simplegym.ui.nav.LogRoute
import org.junit.Test

class TimerDefaultsTest {
    private fun slot(timerRounds: Int? = null, timerRoundSec: Int? = null) = SlotEntity(
        id = 1, dayId = 1, position = 0, defaultExerciseId = "bench-press", sets = 3, lo = 8, hi = 12,
        roundSec = 120, timerRounds = timerRounds, timerRoundSec = timerRoundSec,
    )

    private fun target(type: SessionType, sets: Int = 3, roundSec: Int = 120) = Target(
        weightKg = 60.0, sets = sets, range = RepRange(8, 12), repGoal = null, type = type, reason = "",
        rangeType = RangeType.BASE, roundSec = roundSec,
    )

    @Test
    fun aNormalTargetUsesTheRememberedAdjustmentsThenTheTarget() {
        assertThat(timerDefaults(slot(), target(SessionType.NORMAL))).isEqualTo(EmomConfig(3, 120))
        assertThat(timerDefaults(slot(timerRounds = 4, timerRoundSec = 135), target(SessionType.NORMAL)))
            .isEqualTo(EmomConfig(4, 135))
    }

    @Test
    fun aDeloadTargetUsesItsOwnSetsEvenWhenTheSlotRemembersMoreRounds() {
        assertThat(timerDefaults(slot(timerRounds = 4), target(SessionType.DELOAD, sets = 2)))
            .isEqualTo(EmomConfig(2, 120))
        // only FLUSH forces the round time
        assertThat(timerDefaults(slot(timerRoundSec = 90), target(SessionType.DELOAD, sets = 2, roundSec = 100)))
            .isEqualTo(EmomConfig(2, 90))
    }

    @Test
    fun aFlushTargetAlwaysStartsWith60SecondRounds() {
        assertThat(timerDefaults(slot(timerRounds = 5, timerRoundSec = 135), target(SessionType.FLUSH, sets = 2)))
            .isEqualTo(EmomConfig(2, 60))
    }

    @Test
    fun onlyAnEmomRunFromASlotOpensTheLogScreen() {
        fun run(kind: String = TimerRun.EMOM, sessionId: Long? = 1, slotId: Long? = 2, exerciseId: String? = "x") =
            TimerRun(kind, 3, 120, 0, 0, 0, null, 0, sessionId, slotId, exerciseId)

        assertThat(logRouteFor(run())).isEqualTo(LogRoute(1, 2, "x"))
        assertThat(logRouteFor(run(slotId = null, exerciseId = null, sessionId = null))).isNull() // standalone
        assertThat(logRouteFor(run(kind = TimerRun.INTERVAL))).isNull() // mobility
        assertThat(logRouteFor(run(exerciseId = null))).isNull()
        assertThat(logRouteFor(run(sessionId = null))).isNull()
    }
}
