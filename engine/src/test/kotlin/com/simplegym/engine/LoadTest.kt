package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoadTest {
    @Test fun stepUpAddsOneStep() {
        assertThat(stepUp(60.0, 2.5, assisted = false)).isEqualTo(62.5)
    }

    @Test fun stepUpAddsSeveralSteps() {
        assertThat(stepUp(60.0, 2.5, 2, false)).isEqualTo(65.0)
    }

    @Test fun stepUpAssistedMovesTowardZero() {
        assertThat(stepUp(-70.0, 2.5, assisted = true)).isEqualTo(-67.5)
    }

    @Test fun stepUpAssistedClampsAtZero() {
        assertThat(stepUp(-2.5, 2.5, 2, true)).isEqualTo(0.0)
    }

    @Test fun stepDownSubtractsOneStep() {
        assertThat(stepDown(60.0, 2.5, false)).isEqualTo(57.5)
    }

    @Test fun stepDownAssistedAddsAssistance() {
        assertThat(stepDown(-70.0, 2.5, true)).isEqualTo(-72.5)
    }

    @Test fun stepDownClampsAtZero() {
        assertThat(stepDown(2.5, 5.0, false)).isEqualTo(0.0)
    }

    @Test fun reduceByFloorsTowardLighter() {
        assertThat(reduceBy(60.0, 0.10, 2.5, false)).isEqualTo(52.5)
    }

    @Test fun reduceByAssistedAddsAssistance() {
        assertThat(reduceBy(-70.0, 0.10, 2.5, true)).isEqualTo(-77.5)
    }

    @Test fun reduceByExactMultipleStaysPut() {
        assertThat(reduceBy(62.5, 0.20, 2.5, false)).isEqualTo(50.0)
    }

    @Test fun roundToStepGoesToNearestMultiple() {
        assertThat(roundToStep(54.78, 2.5)).isEqualTo(55.0)
    }

    @Test fun formatKgDropsTrailingZero() {
        assertThat(formatKg(60.0)).isEqualTo("60")
    }

    @Test fun formatKgKeepsHalfSteps() {
        assertThat(formatKg(62.5)).isEqualTo("62.5")
    }

    @Test fun formatKgUsesTypographicMinusForAssisted() {
        assertThat(formatKg(-70.0)).isEqualTo("−70")
        assertThat(formatKg(-67.5)).isEqualTo("−67.5")
    }

    @Test fun formatKgZeroHasNoSign() {
        assertThat(formatKg(0.0)).isEqualTo("0")
        assertThat(formatKg(-0.0)).isEqualTo("0")
    }
}
