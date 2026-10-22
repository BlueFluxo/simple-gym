package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgeRulesTest {
    @Test fun under40UsesFiveRoundsNoAlternation() {
        assertThat(ageRules(39)).isEqualTo(AgeRules(5, false, 2))
    }

    @Test fun age40StartsAlternation() {
        assertThat(ageRules(40)).isEqualTo(AgeRules(3, true, 2))
    }

    @Test fun age54IsStillInTheMiddleBand() {
        assertThat(ageRules(54)).isEqualTo(AgeRules(3, true, 2))
    }

    @Test fun age55LimitsToOneStep() {
        assertThat(ageRules(55)).isEqualTo(AgeRules(2, true, 1))
    }
}
