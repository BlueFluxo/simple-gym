package com.simplegym.engine

import kotlin.math.abs

/** Next-session target from [progress]. A null [repGoal] means "work within the slot's range". */
data class Progressed(val weightKg: Double?, val repGoal: Int?, val reason: String)

private const val DROP_FRACTION = 0.10
private const val EASY_JUMP_OVER_HI = 3
private const val BODYWEIGHT_TOP_EASY =
    "Top of the range is easy: try a harder variation or add load."

private fun Feel.word(): String = name.lowercase()

/**
 * Spec §6.2. Rows are evaluated top-down and the first match wins:
 *
 * 1. R >= hi + 3 and Easy: W + 2 steps (capped by [maxStepsPerSession])
 * 2. R >= hi and Easy or Solid: W + 1 step
 * 3. R >= hi and Grinder: hold W, aim for hi cleanly
 * 4. lo <= R < hi: hold W, aim for R + 1 reps
 * 5. R < lo and (Grinder or R <= lo - 3): W - 10%, floored to a step
 * 6. R < lo otherwise: W - 1 step
 *
 * Bodyweight exercises (no increment, flagged bodyweight, or no logged weight) keep a null weight
 * and move the rep goal instead, capped at hi. Below the range they have no lighter option, so the
 * rep goal is null (work within the range).
 */
fun progress(base: LogRecord, range: RepRange, exercise: Exercise, maxStepsPerSession: Int): Progressed {
    val reps = base.bestReps
    val feel = base.feel
    val atTop = reps >= range.hi
    val wayAbove = reps >= range.hi + EASY_JUMP_OVER_HI && feel == Feel.EASY
    val wayBelow = reps < range.lo && (feel == Feel.GRINDER || reps <= range.lo - EASY_JUMP_OVER_HI)
    val hit = "you hit $reps ${feel.word()} last time"
    val weight = base.weightKg
    val step = exercise.incrementKg

    if (exercise.isBodyweight || step == null || weight == null) {
        return when {
            atTop && feel == Feel.EASY -> Progressed(null, range.hi, BODYWEIGHT_TOP_EASY)
            atTop && feel == Feel.SOLID ->
                Progressed(null, range.hi, "Top of the range: hold it and aim for ${range.hi} reps again")
            atTop -> Progressed(null, range.hi, "Top of the range was a grinder: aim for ${range.hi} reps cleanly")
            reps >= range.lo ->
                Progressed(null, reps + 1, "In the range: aim for one more rep (${reps + 1})")
            else -> Progressed(null, null, "Below the range: aim for ${range.lo} reps first")
        }
    }

    val assisted = exercise.isAssisted
    return when {
        wayAbove -> increase(weight, step, maxStepsPerSession.coerceIn(1, 2), assisted, hit)
        atTop && feel != Feel.GRINDER -> increase(weight, step, 1, assisted, hit)
        atTop -> Progressed(
            weight,
            range.hi,
            "Top of the range was a grinder: hold ${formatKg(weight)}kg and aim for ${range.hi} reps cleanly",
        )
        reps >= range.lo -> Progressed(
            weight,
            reps + 1,
            "In the range: hold ${formatKg(weight)}kg and aim for ${reps + 1} reps",
        )
        wayBelow -> {
            val lighter = reduceBy(weight, DROP_FRACTION, step, assisted)
            Progressed(
                lighter,
                null,
                "−${formatKg(abs(lighter - weight))}kg: ${belowWhy(reps, range, feel)}, so take off 10%",
            )
        }
        else -> {
            val lighter = stepDown(weight, step, assisted)
            Progressed(
                lighter,
                null,
                "−${formatKg(abs(lighter - weight))}kg: ${belowWhy(reps, range, feel)}, so drop one step",
            )
        }
    }
}

private fun belowWhy(reps: Int, range: RepRange, feel: Feel): String =
    "you got $reps (${feel.word()}), below the ${range.lo}–${range.hi} range"

private fun increase(weight: Double, step: Double, steps: Int, assisted: Boolean, hit: String): Progressed {
    val heavier = stepUp(weight, step, steps, assisted)
    val delta = abs(heavier - weight)
    val reason = if (delta == 0.0) {
        "Already at the lightest load: $hit, keep going"
    } else {
        "+${formatKg(delta)}kg: $hit"
    }
    return Progressed(heavier, null, reason)
}
