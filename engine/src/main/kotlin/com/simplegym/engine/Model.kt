package com.simplegym.engine

import java.time.LocalDate

enum class Feel { EASY, SOLID, GRINDER }

enum class SessionType { NORMAL, DELOAD, FLUSH, HELD, RETURN }

enum class RangeType { BASE, HIGH_REP }

enum class Joint { SHOULDER, ELBOW, WRIST, KNEE, HIP, LOWER_BACK, NECK }

enum class BackLoad { LOW, MODERATE, HIGH }

enum class Muscle {
    CHEST, BACK, SHOULDERS, BICEPS, TRICEPS, QUADS, HAMSTRINGS, GLUTES, CALVES, CORE, FOREARMS, MOBILITY
}

enum class Chip(val muscles: Set<Muscle>) {
    BACK(setOf(Muscle.BACK)),
    BICEPS(setOf(Muscle.BICEPS, Muscle.FOREARMS)),
    CHEST(setOf(Muscle.CHEST)),
    TRICEPS(setOf(Muscle.TRICEPS)),
    SHOULDERS(setOf(Muscle.SHOULDERS)),
    LEGS(setOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES)),
    CALVES(setOf(Muscle.CALVES)),
    LOWER_BACK(emptySet()),
    NECK(emptySet()),
}

data class RepRange(val lo: Int, val hi: Int)

data class Exercise(
    val id: String,
    val name: String,
    val muscle: Muscle,
    val equipment: String,
    val swapGroup: String,
    val incrementKg: Double?,
    val backLoad: BackLoad,
    val joints: Set<Joint>,
    val isAssisted: Boolean,
    val isBodyweight: Boolean,
)

data class LogRecord(
    val exerciseId: String,
    val date: LocalDate,
    val weightKg: Double?,
    val bestReps: Int,
    val sets: Int,
    val feel: Feel,
    val type: SessionType,
    val rangeType: RangeType = RangeType.BASE,
    val hurt: Joint? = null,
)

val LogRecord.isBaseline: Boolean
    get() = (type == SessionType.NORMAL || type == SessionType.RETURN) && hurt == null

data class WeightHint(val exerciseName: String, val weightKg: Double)

data class Target(
    val weightKg: Double?,
    val sets: Int,
    val range: RepRange,
    val repGoal: Int?,
    val type: SessionType,
    val reason: String,
    val hint: WeightHint? = null,
    val rangeType: RangeType = RangeType.BASE,
    val roundSec: Int = 120,
)
