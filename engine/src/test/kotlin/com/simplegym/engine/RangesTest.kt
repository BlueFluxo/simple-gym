package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RangesTest {
    private fun exercise(assisted: Boolean = false, bodyweight: Boolean = false) = Exercise(
        id = "x", name = "X", muscle = Muscle.CHEST, equipment = "barbell", swapGroup = "g",
        incrementKg = 2.5, backLoad = BackLoad.LOW, joints = emptySet(),
        isAssisted = assisted, isBodyweight = bodyweight,
    )

    @Test fun highRepShiftsBothEndsByFour() {
        assertThat(effectiveRange(RepRange(8, 12), RangeType.HIGH_REP, exercise())).isEqualTo(RepRange(12, 16))
    }

    @Test fun baseTypeKeepsBaseRange() {
        assertThat(effectiveRange(RepRange(8, 12), RangeType.BASE, exercise())).isEqualTo(RepRange(8, 12))
    }

    @Test fun baseTopOf15IsExcluded() {
        assertThat(effectiveRange(RepRange(12, 15), RangeType.HIGH_REP, exercise())).isEqualTo(RepRange(12, 15))
    }

    @Test fun assistedStaysOnBaseRange() {
        assertThat(effectiveRange(RepRange(6, 10), RangeType.HIGH_REP, exercise(assisted = true)))
            .isEqualTo(RepRange(6, 10))
    }

    @Test fun bodyweightStaysOnBaseRange() {
        assertThat(effectiveRange(RepRange(6, 10), RangeType.HIGH_REP, exercise(bodyweight = true)))
            .isEqualTo(RepRange(6, 10))
    }

    @Test fun highRepTopCapsAtTwenty() {
        assertThat(effectiveRange(RepRange(10, 14), RangeType.HIGH_REP, exercise())).isEqualTo(RepRange(14, 18))
        assertThat(effectiveRange(RepRange(13, 14), RangeType.HIGH_REP, exercise())).isEqualTo(RepRange(17, 18))
    }

    @Test fun alternationAppliesBelowTopOf15() {
        assertThat(alternationApplies(RepRange(8, 12), exercise())).isTrue()
        assertThat(alternationApplies(RepRange(10, 14), exercise())).isTrue()
    }

    @Test fun alternationDoesNotApplyAtTopOf15OrAbove() {
        assertThat(alternationApplies(RepRange(12, 15), exercise())).isFalse()
        assertThat(alternationApplies(RepRange(15, 20), exercise())).isFalse()
    }

    @Test fun alternationDoesNotApplyToAssistedOrBodyweight() {
        assertThat(alternationApplies(RepRange(6, 10), exercise(assisted = true))).isFalse()
        assertThat(alternationApplies(RepRange(6, 10), exercise(bodyweight = true))).isFalse()
    }

    @Test fun blockZeroIsAlwaysBase() {
        assertThat(rangeTypeForBlock(0, true)).isEqualTo(RangeType.BASE)
    }

    @Test fun oddBlocksAreHighRepWhenAlternating() {
        assertThat(rangeTypeForBlock(1, true)).isEqualTo(RangeType.HIGH_REP)
        assertThat(rangeTypeForBlock(3, true)).isEqualTo(RangeType.HIGH_REP)
    }

    @Test fun evenBlocksReturnToBase() {
        assertThat(rangeTypeForBlock(2, true)).isEqualTo(RangeType.BASE)
    }

    @Test fun notAlternatingIsAlwaysBase() {
        assertThat(rangeTypeForBlock(1, false)).isEqualTo(RangeType.BASE)
        assertThat(rangeTypeForBlock(3, false)).isEqualTo(RangeType.BASE)
    }

    @Test fun convertToHigherRepsLowersWeight() {
        assertThat(convertWeight(60.0, 12, 16, 2.5)).isEqualTo(55.0)
    }

    @Test fun convertBackToLowerRepsRaisesWeight() {
        assertThat(convertWeight(55.0, 16, 12, 2.5)).isEqualTo(60.0)
    }

    @Test fun convertToSameRepsKeepsWeight() {
        assertThat(convertWeight(62.5, 12, 12, 2.5)).isEqualTo(62.5)
    }
}
