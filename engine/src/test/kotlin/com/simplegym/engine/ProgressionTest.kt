package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class ProgressionTest {
    private val range = RepRange(8, 12)

    private fun exercise(
        assisted: Boolean = false,
        bodyweight: Boolean = false,
        incrementKg: Double? = 2.5,
    ) = Exercise(
        id = "ex",
        name = "Test lift",
        muscle = Muscle.CHEST,
        equipment = "barbell",
        swapGroup = "g",
        incrementKg = incrementKg,
        backLoad = BackLoad.LOW,
        joints = emptySet(),
        isAssisted = assisted,
        isBodyweight = bodyweight,
    )

    private fun log(weight: Double?, reps: Int, feel: Feel) = LogRecord(
        exerciseId = "ex",
        date = LocalDate.of(2026, 10, 1),
        weightKg = weight,
        bestReps = reps,
        sets = 3,
        feel = feel,
        type = SessionType.NORMAL,
    )

    private fun progressed(
        weight: Double? = 60.0,
        reps: Int,
        feel: Feel,
        maxSteps: Int = 2,
        ex: Exercise = exercise(),
    ): Progressed {
        val result = progress(log(weight, reps, feel), range, ex, maxSteps)
        assertThat(result.reason).isNotEmpty()
        return result
    }

    @Test fun hiPlus3EasyJumpsTwoSteps() {
        val p = progressed(reps = 15, feel = Feel.EASY)
        assertThat(p.weightKg).isEqualTo(65.0)
        assertThat(p.repGoal).isNull()
    }

    @Test fun hiPlus3EasyCappedAtOneStepForMax1() {
        val p = progressed(reps = 15, feel = Feel.EASY, maxSteps = 1)
        assertThat(p.weightKg).isEqualTo(62.5)
        assertThat(p.repGoal).isNull()
    }

    @Test fun topOfRangeSolidAddsStep() {
        val p = progressed(reps = 12, feel = Feel.SOLID)
        assertThat(p.weightKg).isEqualTo(62.5)
        assertThat(p.repGoal).isNull()
        assertThat(p.reason).contains("+2.5kg")
        assertThat(p.reason).isEqualTo("+2.5kg: you hit 12 solid last time")
    }

    @Test fun topOfRangeEasyAddsStep() {
        val p = progressed(reps = 13, feel = Feel.EASY)
        assertThat(p.weightKg).isEqualTo(62.5)
        assertThat(p.repGoal).isNull()
    }

    @Test fun topOfRangeGrinderHolds() {
        val p = progressed(reps = 12, feel = Feel.GRINDER)
        assertThat(p.weightKg).isEqualTo(60.0)
        assertThat(p.repGoal).isEqualTo(12)
    }

    @Test fun inRangeHoldsAndAddsRep() {
        val p = progressed(reps = 10, feel = Feel.SOLID)
        assertThat(p.weightKg).isEqualTo(60.0)
        assertThat(p.repGoal).isEqualTo(11)
    }

    @Test fun belowRangeDropsStep() {
        val p = progressed(reps = 7, feel = Feel.SOLID)
        assertThat(p.weightKg).isEqualTo(57.5)
        assertThat(p.repGoal).isNull()
    }

    @Test fun farBelowRangeDropsTenPercent() {
        val p = progressed(reps = 5, feel = Feel.SOLID)
        assertThat(p.weightKg).isEqualTo(52.5)
        assertThat(p.repGoal).isNull()
    }

    @Test fun belowRangeGrinderDropsTenPercent() {
        val p = progressed(reps = 7, feel = Feel.GRINDER)
        assertThat(p.weightKg).isEqualTo(52.5)
        assertThat(p.repGoal).isNull()
    }

    @Test fun assistedTopOfRangeMovesTowardZero() {
        val p = progressed(weight = -70.0, reps = 12, feel = Feel.SOLID, ex = exercise(assisted = true))
        assertThat(p.weightKg).isEqualTo(-67.5)
        assertThat(p.repGoal).isNull()
        assertThat(p.reason).contains("+2.5kg")
    }

    @Test fun assistedBelowRangeAddsAssistance() {
        val p = progressed(weight = -70.0, reps = 7, feel = Feel.SOLID, ex = exercise(assisted = true))
        assertThat(p.weightKg).isEqualTo(-72.5)
        assertThat(p.repGoal).isNull()
    }

    @Test fun bodyweightInRangeAddsRep() {
        val p = progressed(
            weight = null,
            reps = 10,
            feel = Feel.SOLID,
            ex = exercise(bodyweight = true, incrementKg = null),
        )
        assertThat(p.weightKg).isNull()
        assertThat(p.repGoal).isEqualTo(11)
    }

    @Test fun bodyweightTopOfRangeEasySuggestsHarderVariation() {
        val p = progressed(
            weight = null,
            reps = 12,
            feel = Feel.EASY,
            ex = exercise(bodyweight = true, incrementKg = null),
        )
        assertThat(p.weightKg).isNull()
        assertThat(p.repGoal).isEqualTo(12)
        assertThat(p.reason).isEqualTo("Top of the range is easy: try a harder variation or add load.")
    }

    @Test fun bodyweightIgnoresAnyLoggedWeight() {
        val p = progressed(
            weight = 20.0,
            reps = 9,
            feel = Feel.SOLID,
            ex = exercise(bodyweight = true, incrementKg = 2.5),
        )
        assertThat(p.weightKg).isNull()
        assertThat(p.repGoal).isEqualTo(10)
    }

    @Test fun bodyweightBelowRangeHasNoWeightAndNoRepGoal() {
        val p = progressed(
            weight = null,
            reps = 6,
            feel = Feel.SOLID,
            ex = exercise(bodyweight = true, incrementKg = null),
        )
        assertThat(p.weightKg).isNull()
        assertThat(p.repGoal).isNull()
    }

    @Test fun assistedAtZeroAssistanceDoesNotClaimAnIncrease() {
        val p = progressed(weight = 0.0, reps = 12, feel = Feel.SOLID, ex = exercise(assisted = true))
        assertThat(p.weightKg).isEqualTo(0.0)
        assertThat(p.reason).doesNotContain("+0kg")
    }
}
