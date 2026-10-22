package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModifiersTest {
    private fun c(kg: Double?, sets: Int, type: SessionType = SessionType.HELD, reason: String = "r") =
        Candidate(kg, sets, type, reason)

    @Test fun theLowestWeightWinsNotAProductOfTheModifiers() {
        val normal = c(62.5, 3, SessionType.NORMAL)
        val deload = c(52.5, 2, SessionType.DELOAD)
        val returning = c(55.0, 3, SessionType.RETURN)
        val hurt = c(57.5, 3)

        assertThat(mostConservative(listOf(normal, deload, returning, hurt))).isEqualTo(deload)
    }

    @Test fun equalWeightsFewerSetsWins() {
        val three = c(60.0, 3, SessionType.NORMAL)
        val two = c(60.0, 2, SessionType.DELOAD)

        assertThat(mostConservative(listOf(three, two))).isEqualTo(two)
    }

    @Test fun assistedMostNegativeIsTheLightestLoad() {
        assertThat(mostConservative(listOf(c(-67.5, 3), c(-77.5, 3))).weightKg).isEqualTo(-77.5)
    }

    @Test fun nullWeightsCompareEqualSoSetsDecide() {
        val four = c(null, 4, SessionType.NORMAL)
        val three = c(null, 3, SessionType.DELOAD)

        assertThat(mostConservative(listOf(four, three))).isEqualTo(three)
    }

    @Test fun aFullTieGoesToTheFirstEvenWhenItIsTheNormalCandidate() {
        val normal = c(60.0, 3, SessionType.NORMAL, "normal")
        val held = c(60.0, 3, SessionType.HELD, "held")

        assertThat(mostConservative(listOf(normal, held))).isEqualTo(normal)
    }

    @Test fun aFullTieBetweenModifiersGoesToTheFirst() {
        val first = c(60.0, 3, SessionType.HELD, "first")
        val second = c(60.0, 3, SessionType.RETURN, "second")

        assertThat(mostConservative(listOf(first, second))).isEqualTo(first)
    }
}
