package com.simplegym.engine

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

private const val EPSILON = 1e-9

/** Adds [steps] steps. Assisted weights are stored negative, so they clamp at 0.0 (no assistance). */
fun stepUp(weightKg: Double, stepKg: Double, steps: Int = 1, assisted: Boolean): Double {
    val result = weightKg + steps * stepKg
    return if (assisted) minOf(result, 0.0) else result
}

/** Removes one step. Non-assisted weights clamp at 0.0; assisted weights get more negative. */
fun stepDown(weightKg: Double, stepKg: Double, assisted: Boolean): Double {
    val result = weightKg - stepKg
    return if (assisted) result else maxOf(result, 0.0)
}

/**
 * Reduces the load by [fraction], always floored to a multiple of [stepKg] (toward lighter).
 * Non-assisted: w * (1 - f). Assisted (negative w): w * (1 + f), i.e. more assistance.
 */
fun reduceBy(weightKg: Double, fraction: Double, stepKg: Double, assisted: Boolean): Double {
    val raw = if (assisted) weightKg * (1 + fraction) else weightKg * (1 - fraction)
    return floor(raw / stepKg + EPSILON) * stepKg
}

fun roundToStep(weightKg: Double, stepKg: Double): Double =
    (weightKg / stepKg).roundToLong() * stepKg

/** "60", "62.5", "−70" (U+2212 minus for assisted weights). */
fun formatKg(weightKg: Double): String {
    val magnitude = BigDecimal.valueOf(abs(weightKg)).stripTrailingZeros()
    val text = if (magnitude.signum() == 0) "0" else magnitude.toPlainString()
    return if (weightKg < 0) "−$text" else text
}
