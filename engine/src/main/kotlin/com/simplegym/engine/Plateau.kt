package com.simplegym.engine

/** The lifter is offered a rotation after this many stalled sessions in a row (spec §6.3). */
const val PLATEAU_LIMIT = 3

/**
 * How many sessions in a row have stalled at the end of [baselines]: chronological baseline logs of one exercise in
 * one range type. Each consecutive pair counts +1 when the weight is the same and the reps did not go up; more weight,
 * or the same weight with more reps, is progress; less weight is a reset (a deliberate step back), and both start the
 * count again from 0. A bodyweight lift (no weight) is compared by reps alone.
 */
fun plateauCount(baselines: List<LogRecord>): Int =
    baselines.zipWithNext().fold(0) { count, (before, after) ->
        val weightChange = (after.weightKg ?: 0.0).compareTo(before.weightKg ?: 0.0)
        when {
            weightChange != 0 -> 0
            after.bestReps > before.bestReps -> 0
            else -> count + 1
        }
    }
