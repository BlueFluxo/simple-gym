package com.simplegym.engine

private const val HIGH_REP_SHIFT = 4
private const val HIGH_REP_CAP = 20
private const val ALTERNATION_EXCLUDED_TOP = 15
private const val EPLEY_DIVISOR = 30.0

/** Slots with a base top of 15+ and assisted or bodyweight variants keep their base range (spec §6.4). */
fun alternationApplies(base: RepRange, exercise: Exercise): Boolean =
    base.hi < ALTERNATION_EXCLUDED_TOP && !exercise.isAssisted && !exercise.isBodyweight

/** Block 0 is always BASE; with alternation, odd blocks are HIGH_REP. */
fun rangeTypeForBlock(blockIndex: Int, alternate: Boolean): RangeType =
    if (alternate && blockIndex % 2 == 1) RangeType.HIGH_REP else RangeType.BASE

/** HIGH_REP is base + 4 at both ends with the top capped at 20; excluded slots keep [base]. */
fun effectiveRange(base: RepRange, type: RangeType, exercise: Exercise): RepRange =
    if (type == RangeType.HIGH_REP && alternationApplies(base, exercise)) {
        RepRange(base.lo + HIGH_REP_SHIFT, minOf(base.hi + HIGH_REP_SHIFT, HIGH_REP_CAP))
    } else {
        base
    }

/** Epley e1RM = w * (1 + r/30), converted to [toReps] and rounded to the nearest [stepKg]. */
fun convertWeight(weightKg: Double, fromReps: Int, toReps: Int, stepKg: Double): Double {
    val e1rm = weightKg * (1 + fromReps / EPLEY_DIVISOR)
    return roundToStep(e1rm / (1 + toReps / EPLEY_DIVISOR), stepKg)
}
