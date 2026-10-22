package com.simplegym.engine

import java.time.LocalDate

enum class MobilityArea(val swapGroups: List<String>) {
    NECK(listOf("mobility-neck")),
    LOWER_BACK(listOf("mobility-lowerback", "stability-lowerback")),
    HIPS(listOf("mobility-hips", "hip-dynamic")),
    SHOULDERS(listOf("mobility-shoulders")),
    CORE(listOf("core-stability", "core-leg-raise")),
}

/** [moves] are done one after another, each held for [workSec] then rested for [restSec]. */
data class Routine(val moves: List<Exercise>, val workSec: Int, val restSec: Int)

private const val MIN_ROUTINE_SEC = 480
private const val MAX_PASS_SEC = 600
private const val MAX_MOVES = 15
private const val MAX_PASSES = 3

/**
 * The add-on routine for [areas] (in the order given), made of the [library] moves in their swap groups, in area order
 * then library order, aiming for about 8 to 10 minutes (spec 5.6). If one pass alone is over [MAX_PASS_SEC], only
 * [MAX_MOVES] moves are taken, round-robin across the areas, so every area is represented. Otherwise the pass is
 * followed by more moves, cycling through the pass again from its start, until the routine reaches [MIN_ROUTINE_SEC],
 * stopping early before it would pass [MAX_PASS_SEC] or after [MAX_PASSES] full passes, whichever comes first. With the
 * default 30 s work and 10 s rest, one area of three moves gives 9 moves (360 s), and two areas of nine moves give 12
 * (480 s, the last three being the first three of the pass).
 */
fun buildRoutine(areas: List<MobilityArea>, library: List<Exercise>, workSec: Int = 30, restSec: Int = 10): Routine {
    val perArea = areas.distinct().map { area -> library.filter { it.swapGroup in area.swapGroups } }
    val pass = perArea.flatten()
    val moveSec = workSec + restSec
    if (pass.isEmpty() || moveSec <= 0) return Routine(emptyList(), workSec, restSec)
    if (pass.size * moveSec > MAX_PASS_SEC) return Routine(roundRobin(perArea, MAX_MOVES), workSec, restSec)
    val maxMoves = minOf(pass.size * MAX_PASSES, MAX_PASS_SEC / moveSec)
    val moves = pass.toMutableList()
    while (moves.size * moveSec < MIN_ROUTINE_SEC && moves.size < maxMoves) moves += pass[moves.size % pass.size]
    return Routine(moves, workSec, restSec)
}

/** One move from each list in turn, skipping lists that have run out, until [limit] moves or all are taken. */
private fun roundRobin(lists: List<List<Exercise>>, limit: Int): List<Exercise> {
    val taken = mutableListOf<Exercise>()
    var index = 0
    while (taken.size < limit && lists.any { index < it.size }) {
        for (list in lists) {
            if (taken.size < limit && index < list.size) taken += list[index]
        }
        index++
    }
    return taken
}

/** Areas ordered most neglected first: never done, then the longest ago; ties keep area order. */
fun neglectedOrder(lastDone: Map<MobilityArea, LocalDate>, today: LocalDate): List<MobilityArea> =
    MobilityArea.entries.sortedByDescending { area ->
        lastDone[area]?.let { today.toEpochDay() - it.toEpochDay() } ?: Long.MAX_VALUE
    }
