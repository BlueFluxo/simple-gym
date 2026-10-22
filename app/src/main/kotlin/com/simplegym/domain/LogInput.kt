package com.simplegym.domain

import com.simplegym.engine.Exercise
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.formatKg
import java.math.BigDecimal
import kotlin.math.abs

/** What the lifter typed on the Log screen; the numbers are still text so they can be checked and reported on. */
data class LogInput(
    val weightText: String,
    val repsText: String,
    val setsText: String,
    val feel: Feel,
    val hurt: Joint?,
    val note: String?,
)

sealed interface LogValidation {
    /** [weightKg] is null for a bodyweight exercise and negative for an assisted one. */
    data class Ok(val weightKg: Double?, val reps: Int, val sets: Int) : LogValidation

    /** [field] is "weight", "reps" or "sets"; [message] is shown under that field. */
    data class Error(val field: String, val message: String) : LogValidation
}

private const val WEIGHT_MESSAGE = "Enter a weight in kg."
private const val ASSISTANCE_MESSAGE = "Enter the assistance in kg."
private const val REPS_MESSAGE = "Enter reps of 1 or more."
private const val SETS_MESSAGE = "Enter sets of 1 or more."

/** Digits with an optional decimal point and optional minus; nothing Java's parser would also take (NaN, 1e3, 5d). */
private val NUMBER = Regex("""-?(\d+\.?\d*|\.\d+)""")

/** Accepts a comma as the decimal separator and U+2212 as the minus sign; null if it is not a plain number. */
private fun parseDecimal(text: String): BigDecimal? {
    val normalised = text.trim().replace(',', '.').replace('−', '-')
    return if (NUMBER.matches(normalised)) normalised.toBigDecimalOrNull() else null
}

/**
 * Checks [input] for [exercise], the first bad field in screen order (weight, reps, sets) winning.
 *  - Assisted: the assistance is typed as a plain amount ("70" or "-70") and stored negative.
 *  - Bodyweight: whatever is in the weight field is ignored.
 *  - Otherwise the weight must be a number above zero.
 * Reps and sets must be whole numbers of 1 or more.
 */
fun validateLog(input: LogInput, exercise: Exercise): LogValidation {
    val weightKg: Double? = when {
        exercise.isAssisted -> {
            val amount = parseDecimal(input.weightText)?.abs()?.toDouble()?.takeIf { it.isFinite() }
                ?: return LogValidation.Error("weight", ASSISTANCE_MESSAGE)
            if (amount == 0.0) 0.0 else -amount // never -0.0
        }
        exercise.isBodyweight -> null
        else -> parseDecimal(input.weightText)?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }
            ?: return LogValidation.Error("weight", WEIGHT_MESSAGE)
    }
    val reps = input.repsText.trim().toIntOrNull()?.takeIf { it >= 1 }
        ?: return LogValidation.Error("reps", REPS_MESSAGE)
    val sets = input.setsText.trim().toIntOrNull()?.takeIf { it >= 1 }
        ?: return LogValidation.Error("sets", SETS_MESSAGE)
    return LogValidation.Ok(weightKg, reps, sets)
}

/**
 * The text after a stepper tap: [text] plus [delta], not below [min]. Blank or unreadable text counts as zero. Done in
 * decimal so 0.1 + 0.2 reads 0.3, and shown without a trailing ".0".
 */
fun stepNumberText(text: String, delta: Double, min: Double): String {
    val current = parseDecimal(text) ?: BigDecimal.ZERO
    val stepped = current + BigDecimal.valueOf(delta)
    val floor = BigDecimal.valueOf(min)
    val result = if (stepped < floor) floor else stepped
    return if (result.signum() == 0) "0" else result.stripTrailingZeros().toPlainString()
}

/** The number to show in the weight field for a stored weight: an assisted (negative) weight shows as its amount. */
fun weightFieldText(weightKg: Double?): String = weightKg?.let { formatKg(abs(it)) }.orEmpty()
