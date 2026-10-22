package com.simplegym.ui.progress

import com.google.common.truth.Truth.assertThat
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.LogRecord
import com.simplegym.engine.SessionType
import java.time.LocalDate
import org.junit.Test

class ChartDataTest {
    private fun log(
        day: Int,
        weight: Double? = 60.0,
        reps: Int = 8,
        type: SessionType = SessionType.NORMAL,
        hurt: Joint? = null,
    ) = LogRecord(
        exerciseId = "bench", date = LocalDate.of(2026, 10, day), weightKg = weight, bestReps = reps, sets = 3,
        feel = Feel.SOLID, type = type, hurt = hurt,
    )

    @Test
    fun deloadFlushAndReturnAreFadedWhileNormalAndHeldAreNot() {
        val points = chartPoints(
            SessionType.entries.mapIndexed { i, type -> log(day = i + 1, type = type) },
        )

        assertThat(points.associate { it.date.dayOfMonth to it.faded }).containsExactly(
            1, false, // NORMAL
            2, true, // DELOAD
            3, true, // FLUSH
            4, false, // HELD
            5, true, // RETURN
        )
    }

    @Test
    fun assistedWeightsArePlottedRawSoLessAssistanceRises() {
        val points = chartPoints(listOf(log(1, -70.0), log(2, -65.0), log(3, -60.0)))

        assertThat(points.map { it.weightKg }).containsExactly(-70.0, -65.0, -60.0).inOrder()
        assertThat(points.map { it.weightKg }).isInStrictOrder()
    }

    @Test
    fun aLogWithoutAWeightIsSkipped() {
        val points = chartPoints(listOf(log(1, 40.0), log(2, weight = null, reps = 12), log(3, 42.5)))

        assertThat(points.map { it.weightKg }).containsExactly(40.0, 42.5).inOrder()
    }

    @Test
    fun hurtIsSetOnlyWhenAJointWasFlagged() {
        val points = chartPoints(listOf(log(1), log(2, hurt = Joint.SHOULDER)))

        assertThat(points.map { it.hurt }).containsExactly(false, true).inOrder()
    }

    @Test
    fun pointsCarryRepsAndAreSortedByDateKeepingInputOrderOnTheSameDay() {
        val points = chartPoints(listOf(log(5, 60.0, reps = 6), log(2, 55.0, reps = 9), log(5, 62.5, reps = 4)))

        assertThat(points.map { it.date.dayOfMonth to it.reps }).containsExactly(2 to 9, 5 to 6, 5 to 4).inOrder()
        assertThat(points.map { it.weightKg }).containsExactly(55.0, 60.0, 62.5).inOrder()
    }

    @Test
    fun noLogsMakeNoPoints() {
        assertThat(chartPoints(emptyList())).isEmpty()
    }

    @Test
    fun axisTicksRoundToNiceStepsAndCoverTheData() {
        assertThat(axisTicks(57.5, 72.0)).containsExactly(55.0, 60.0, 65.0, 70.0, 75.0).inOrder()
        assertThat(axisTicks(-70.0, -60.0)).containsExactly(-70.0, -67.5, -65.0, -62.5, -60.0).inOrder()
    }

    @Test
    fun axisTicksForOneWeightStillSpanARange() {
        val ticks = axisTicks(60.0, 60.0)

        assertThat(ticks.first()).isLessThan(60.0)
        assertThat(ticks.last()).isGreaterThan(60.0)
    }

    @Test
    fun everyPointIsLabelledWhenTheyFitAndTheNewestIsKeptWhenThinned() {
        assertThat(labelledIndices(count = 5, plotWidth = 300f, minGap = 40f)).containsExactly(0, 1, 2, 3, 4).inOrder()
        // 21 points over 200 px is 10 px apart; a 40 px gap needs every fourth, counted back from the newest.
        assertThat(labelledIndices(count = 21, plotWidth = 200f, minGap = 40f))
            .containsExactly(0, 4, 8, 12, 16, 20).inOrder()
        assertThat(labelledIndices(count = 1, plotWidth = 200f, minGap = 40f)).containsExactly(0)
        assertThat(labelledIndices(count = 0, plotWidth = 200f, minGap = 40f)).isEmpty()
    }
}
