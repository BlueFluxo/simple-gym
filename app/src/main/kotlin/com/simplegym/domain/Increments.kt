package com.simplegym.domain

import com.simplegym.data.ExerciseEntity
import com.simplegym.engine.Exercise

/** Step size for an exercise: its own override, else the lifter's per-equipment step, else the library default. */
fun effectiveIncrement(e: ExerciseEntity, equipmentIncrements: Map<String, Double>): Double? =
    e.incrementOverrideKg ?: equipmentIncrements[e.equipment] ?: e.incrementKg

/** The single entity-to-engine mapper: every app-side call into the engine goes through it. */
fun ExerciseEntity.toEngine(equipmentIncrements: Map<String, Double>): Exercise = Exercise(
    id = id,
    name = name,
    muscle = muscle,
    equipment = equipment,
    swapGroup = swapGroup,
    incrementKg = effectiveIncrement(this, equipmentIncrements),
    backLoad = backLoad,
    joints = joints,
    isAssisted = isAssisted,
    isBodyweight = isBodyweight,
)

/** The biggest weight step the lifter may set, in kg; anything above is a typo. */
const val MAX_INCREMENT_KG = 50.0

/** What a field shows when its step is not a number above 0 and at most [MAX_INCREMENT_KG]. */
const val INCREMENT_MESSAGE = "Enter a step in kg above 0."

/** A digit string with an optional decimal point; nothing Java's parser would also take (NaN, 1e3, 5d). */
private val INCREMENT_NUMBER = Regex("""\d+\.?\d*|\.\d+""")

/** The step the lifter typed (a comma may be the decimal separator), or null unless it is above 0 and at most [MAX_INCREMENT_KG]. */
fun parseIncrementKg(text: String): Double? {
    val normalised = text.trim().replace(',', '.')
    if (!INCREMENT_NUMBER.matches(normalised)) return null
    return normalised.toDoubleOrNull()?.takeIf { it > 0.0 && it <= MAX_INCREMENT_KG }
}
