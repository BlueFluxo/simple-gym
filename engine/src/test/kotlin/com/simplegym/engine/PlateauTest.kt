package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class PlateauTest {
    private val start = LocalDate.of(2026, 1, 5)

    private fun log(weightKg: Double?, reps: Int, day: Long = 0) = LogRecord(
        exerciseId = "bench-press", date = start.plusDays(day), weightKg = weightKg, bestReps = reps, sets = 3,
        feel = Feel.SOLID, type = SessionType.NORMAL,
    )

    private fun history(vararg sets: Pair<Double?, Int>) = sets.mapIndexed { i, (kg, reps) -> log(kg, reps, i.toLong()) }

    @Test fun limitIsThree() {
        assertThat(PLATEAU_LIMIT).isEqualTo(3)
    }

    @Test fun fourIdenticalSessionsIsThreePlateaus() {
        assertThat(plateauCount(history(60.0 to 10, 60.0 to 10, 60.0 to 10, 60.0 to 10))).isEqualTo(3)
    }

    @Test fun moreRepsAtTheSameWeightIsProgress() {
        assertThat(plateauCount(history(60.0 to 10, 60.0 to 11))).isEqualTo(0)
    }

    @Test fun fewerRepsAtTheSameWeightIsAPlateau() {
        assertThat(plateauCount(history(60.0 to 10, 60.0 to 9))).isEqualTo(1)
    }

    @Test fun moreWeightResetsTheCountThenTheNextStallStartsItAgain() {
        assertThat(plateauCount(history(60.0 to 10, 62.5 to 8, 62.5 to 8))).isEqualTo(1)
    }

    @Test fun lessWeightResetsTheCount() {
        assertThat(plateauCount(history(60.0 to 10, 60.0 to 10, 60.0 to 10, 60.0 to 10, 52.5 to 12))).isEqualTo(0)
    }

    @Test fun progressInTheMiddleResetsTheCount() {
        assertThat(plateauCount(history(60.0 to 10, 60.0 to 10, 60.0 to 10, 60.0 to 11, 60.0 to 11))).isEqualTo(1)
    }

    @Test fun emptyOrSingleIsZero() {
        assertThat(plateauCount(emptyList())).isEqualTo(0)
        assertThat(plateauCount(history(60.0 to 10))).isEqualTo(0)
    }

    @Test fun anAssistedLiftProgressesWhenTheAssistanceShrinks() {
        assertThat(plateauCount(history(-70.0 to 8, -65.0 to 8))).isEqualTo(0)
        assertThat(plateauCount(history(-65.0 to 8, -70.0 to 8))).isEqualTo(0)
        assertThat(plateauCount(history(-65.0 to 8, -65.0 to 8))).isEqualTo(1)
    }

    @Test fun bodyweightWithNoWeightGoesByReps() {
        assertThat(plateauCount(history(null to 12, null to 12, null to 12))).isEqualTo(2)
        assertThat(plateauCount(history(null to 12, null to 13))).isEqualTo(0)
    }
}
