package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LayoffTest {
    @Test fun nullWhenNeverTrained() {
        assertThat(layoffFraction(null)).isNull()
    }

    @Test fun nullBelowTenDays() {
        assertThat(layoffFraction(9)).isNull()
    }

    @Test fun tenDaysIsTenPercent() {
        assertThat(layoffFraction(10)).isEqualTo(0.10)
    }

    @Test fun twentyDaysIsStillTenPercent() {
        assertThat(layoffFraction(20)).isEqualTo(0.10)
    }

    @Test fun twentyOneDaysIsTwentyPercent() {
        assertThat(layoffFraction(21)).isEqualTo(0.20)
    }

    @Test fun fortyTwoDaysIsStillTwentyPercent() {
        assertThat(layoffFraction(42)).isEqualTo(0.20)
    }

    @Test fun fortyThreeDaysIsThirtyPercent() {
        assertThat(layoffFraction(43)).isEqualTo(0.30)
    }
}
