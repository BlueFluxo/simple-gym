package com.simplegym.domain

import com.google.common.truth.Truth.assertThat
import com.simplegym.engine.BackLoad
import com.simplegym.engine.Exercise
import com.simplegym.engine.Feel
import com.simplegym.engine.Muscle
import org.junit.Test

class LogInputTest {
    private fun exercise(assisted: Boolean = false, bodyweight: Boolean = false) = Exercise(
        id = "x", name = "X", muscle = Muscle.CHEST, equipment = "machine", swapGroup = "g", incrementKg = 2.5,
        backLoad = BackLoad.LOW, joints = emptySet(), isAssisted = assisted, isBodyweight = bodyweight,
    )

    private fun input(weight: String = "60", reps: String = "10", sets: String = "3") =
        LogInput(weight, reps, sets, Feel.SOLID, hurt = null, note = null)

    @Test
    fun anAssistedWeightIsStoredNegativeWhetherTypedPlainOrWithAMinus() {
        assertThat(validateLog(input(weight = "70"), exercise(assisted = true))).isEqualTo(LogValidation.Ok(-70.0, 10, 3))
        assertThat(validateLog(input(weight = "-70"), exercise(assisted = true))).isEqualTo(LogValidation.Ok(-70.0, 10, 3))
        assertThat(validateLog(input(weight = "−70"), exercise(assisted = true))).isEqualTo(LogValidation.Ok(-70.0, 10, 3))
    }

    @Test
    fun noAssistanceIsAllowedAndStaysPositiveZero() {
        assertThat(validateLog(input(weight = "0"), exercise(assisted = true))).isEqualTo(LogValidation.Ok(0.0, 10, 3))
    }

    @Test
    fun anAssistedWeightCannotBeBlank() {
        assertThat(errorField(validateLog(input(weight = ""), exercise(assisted = true)))).isEqualTo("weight")
    }

    @Test
    fun blankOrZeroRepsAreRejectedOnTheRepsField() {
        assertThat(errorField(validateLog(input(reps = ""), exercise()))).isEqualTo("reps")
        assertThat(errorField(validateLog(input(reps = "0"), exercise()))).isEqualTo("reps")
        assertThat(errorField(validateLog(input(reps = "abc"), exercise()))).isEqualTo("reps")
        assertThat(errorField(validateLog(input(reps = "-3"), exercise()))).isEqualTo("reps")
    }

    @Test
    fun blankOrZeroSetsAreRejectedOnTheSetsField() {
        assertThat(errorField(validateLog(input(sets = "0"), exercise()))).isEqualTo("sets")
        assertThat(errorField(validateLog(input(sets = ""), exercise()))).isEqualTo("sets")
    }

    @Test
    fun anUnreadableWeightIsRejectedOnTheWeightField() {
        assertThat(errorField(validateLog(input(weight = "abc"), exercise()))).isEqualTo("weight")
        assertThat(errorField(validateLog(input(weight = "NaN"), exercise()))).isEqualTo("weight")
        assertThat(errorField(validateLog(input(weight = "Infinity"), exercise()))).isEqualTo("weight")
    }

    @Test
    fun aWeightOnABodyweightExerciseIsIgnored() {
        assertThat(validateLog(input(weight = "20"), exercise(bodyweight = true))).isEqualTo(LogValidation.Ok(null, 10, 3))
        assertThat(validateLog(input(weight = ""), exercise(bodyweight = true))).isEqualTo(LogValidation.Ok(null, 10, 3))
        assertThat(validateLog(input(weight = "abc"), exercise(bodyweight = true))).isEqualTo(LogValidation.Ok(null, 10, 3))
    }

    @Test
    fun aLoadedExerciseNeedsAPositiveWeight() {
        assertThat(errorField(validateLog(input(weight = ""), exercise()))).isEqualTo("weight")
        assertThat(errorField(validateLog(input(weight = "0"), exercise()))).isEqualTo("weight")
        assertThat(errorField(validateLog(input(weight = "-5"), exercise()))).isEqualTo("weight")
    }

    @Test
    fun aCommaIsADecimalSeparator() {
        assertThat(validateLog(input(weight = "62,5"), exercise())).isEqualTo(LogValidation.Ok(62.5, 10, 3))
        assertThat(validateLog(input(weight = " 62.5 "), exercise())).isEqualTo(LogValidation.Ok(62.5, 10, 3))
    }

    @Test
    fun theFirstBadFieldIsReportedInScreenOrder() {
        assertThat(errorField(validateLog(input(weight = "abc", reps = "0", sets = "0"), exercise()))).isEqualTo("weight")
        assertThat(errorField(validateLog(input(reps = "0", sets = "0"), exercise()))).isEqualTo("reps")
    }

    @Test
    fun theStepperAddsAndSubtractsWithoutFloatingPointNoise() {
        assertThat(stepNumberText("62.5", 2.5, min = 0.0)).isEqualTo("65")
        assertThat(stepNumberText("0,1", 0.2, min = 0.0)).isEqualTo("0.3")
        assertThat(stepNumberText("12", -1.0, min = 1.0)).isEqualTo("11")
    }

    @Test
    fun theStepperStartsFromZeroWhenBlankAndNeverGoesBelowItsMinimum() {
        assertThat(stepNumberText("", 2.5, min = 0.0)).isEqualTo("2.5")
        assertThat(stepNumberText("abc", 1.0, min = 1.0)).isEqualTo("1")
        assertThat(stepNumberText("1", -1.0, min = 1.0)).isEqualTo("1")
        assertThat(stepNumberText("1", -2.5, min = 0.0)).isEqualTo("0")
    }

    /** The message is for the lifter; the tests pin the field, and that the message says something. */
    private fun errorField(result: LogValidation): String? = (result as? LogValidation.Error)?.also {
        assertThat(it.message).isNotEmpty()
    }?.field
}
