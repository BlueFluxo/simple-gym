package com.simplegym.engine

/** Age-dependent progression rules (spec §6.5). Age sets these and nothing else. */
data class AgeRules(val buildRounds: Int, val alternateRanges: Boolean, val maxStepsPerSession: Int)

fun ageRules(age: Int): AgeRules = when {
    age < 40 -> AgeRules(buildRounds = 5, alternateRanges = false, maxStepsPerSession = 2)
    age < 55 -> AgeRules(buildRounds = 3, alternateRanges = true, maxStepsPerSession = 2)
    else -> AgeRules(buildRounds = 2, alternateRanges = true, maxStepsPerSession = 1)
}
