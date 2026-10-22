package com.simplegym.ui.progress

import com.simplegym.engine.LogRecord
import com.simplegym.engine.SessionType
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * One session of one exercise on the chart. [weightKg] is the raw logged weight, so an assisted exercise (stored
 * negative) rises as the assistance drops. [faded] marks the sessions that are not the lifter's normal working weight.
 */
data class ChartPoint(val date: LocalDate, val weightKg: Double, val reps: Int, val faded: Boolean, val hurt: Boolean)

private val FADED_TYPES = setOf(SessionType.DELOAD, SessionType.FLUSH, SessionType.RETURN)

/** Logs without a weight (bodyweight work) have nothing to plot; the rest become points in date order. */
fun chartPoints(logs: List<LogRecord>): List<ChartPoint> =
    logs.mapNotNull { log ->
        log.weightKg?.let { ChartPoint(log.date, it, log.bestReps, log.type in FADED_TYPES, log.hurt != null) }
    }.sortedBy { it.date } // stable: two sessions on one day keep the order they were logged in

private val NICE_STEPS = listOf(1.0, 2.0, 2.5, 5.0, 10.0)
private const val ROUND_TO = 1_000_000.0

/**
 * Evenly spaced axis values, round numbers, running from at or below [min] to at or above [max]: about [target]
 * intervals. A single value (no spread) gets a one-step margin either side.
 */
fun axisTicks(min: Double, max: Double, target: Int = 4): List<Double> {
    val span = max - min
    val rawStep = if (span <= 0.0) 1.0 else span / target
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val step = NICE_STEPS.map { it * magnitude }.first { it >= rawStep * (1 - 1e-9) }
    val first = floor(min / step + 1e-9).toLong().let { if (span <= 0.0) it - 1 else it }
    var last = ceil(max / step - 1e-9).toLong().let { if (span <= 0.0) it + 1 else it }
    if (last == first) last = first + 1
    // Multiplying the index (not adding the step repeatedly) keeps 2.5 steps from drifting into 7.500000000000001.
    return (first..last).map { (it * step * ROUND_TO).roundToLong() / ROUND_TO }
}

/**
 * Which of [count] evenly spaced points get a label. Every point when neighbours are at least [minGap] apart across
 * [plotWidth]; otherwise every n-th one, counted back from the newest so the latest session is always labelled.
 */
fun labelledIndices(count: Int, plotWidth: Float, minGap: Float): List<Int> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(0)
    val spacing = plotWidth / (count - 1)
    val every = if (spacing >= minGap) 1 else ceil(minGap / spacing).toInt()
    return (0 until count).filter { (count - 1 - it) % every == 0 }
}
