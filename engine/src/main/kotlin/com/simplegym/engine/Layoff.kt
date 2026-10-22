package com.simplegym.engine

/**
 * Fraction to take off the working weight after a layoff (spec 6.8), or null when no reduction applies.
 * [daysSinceMovementTrained] is null when the movement has never been trained.
 */
fun layoffFraction(daysSinceMovementTrained: Long?): Double? = when {
    daysSinceMovementTrained == null -> null
    daysSinceMovementTrained < 10 -> null
    daysSinceMovementTrained <= 20 -> 0.10
    daysSinceMovementTrained <= 42 -> 0.20
    else -> 0.30
}
