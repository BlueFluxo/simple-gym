package com.simplegym.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Lower back and neck have no library muscle, so a stiff one never blocks a day (spec §6.6). */
private val NEVER_BLOCKING = setOf(Chip.LOWER_BACK, Chip.NECK)

private const val MIN_SAMPLES = 2

/** A plan day as the suggestion sees it: the [chips] it trains and the date it was last [lastDone] (null: never). */
data class DayCard(val dayId: Long, val chips: Set<Chip>, val lastDone: LocalDate?)

/**
 * [dayId] is the day to suggest (null when there are no days). [blocked] are the days with a stiff chip today.
 * [flushPrompt] is set when every day is blocked: the suggested day then wants a flush rather than a plain session.
 */
data class DaySuggestion(val dayId: Long?, val blocked: Set<Long>, val flushPrompt: Boolean)

/** The chips the lifter tapped stiff on [date]. An empty set is a real check-in: nothing was stiff. */
data class CheckIn(val date: LocalDate, val stiff: Set<Chip>)

/** A training session on [date] that worked the [chips] of its day. */
data class Trained(val date: LocalDate, val chips: Set<Chip>)

/**
 * The day to start today (spec §6.6). A day is blocked when it trains a chip tapped stiff (lower back and neck never
 * block). Among the unblocked days the one done longest ago wins, a day never done counting as longest; ties go to a
 * day not in [doneThisRound], then to the lowest sum of [typicalStiffDays] over the day's chips (a chip with no
 * estimate counts 0), then to the earlier day in [days]. When every day is blocked the same order picks among all of
 * them and [DaySuggestion.flushPrompt] is set.
 */
fun suggestDay(
    days: List<DayCard>,
    stiff: Set<Chip>,
    doneThisRound: Set<Long>,
    typicalStiffDays: Map<Chip, Int>,
    today: LocalDate,
): DaySuggestion {
    if (days.isEmpty()) return DaySuggestion(null, emptySet(), false)
    val blocking = stiff - NEVER_BLOCKING
    val blocked = days.filter { day -> day.chips.any { it in blocking } }
    val unblocked = days - blocked.toSet()
    val order = compareByDescending<DayCard> { day ->
        day.lastDone?.let { ChronoUnit.DAYS.between(it, today) } ?: Long.MAX_VALUE
    }
        .thenBy { it.dayId in doneThisRound }
        .thenBy { day -> day.chips.sumOf { typicalStiffDays[it] ?: 0 } }
    // minWithOrNull keeps the first of equal minimums, which is the earlier day in the plan
    val pick = unblocked.minWithOrNull(order) ?: days.minWith(order)
    return DaySuggestion(pick.dayId, blocked.map { it.dayId }.toSet(), flushPrompt = unblocked.isEmpty())
}

/**
 * How many days after a session each chip usually stays stiff. One sample per (training date d, chip c): the check-ins
 * after d are walked oldest first while they have c tapped, and the date of the last of them minus d is the sample; the
 * first check-in without c ends the run, and a training whose next check-in lacks c gives no sample. A run also ends at
 * a calendar gap of more than one day between check-ins (a day with no check-in is a loose day) and never reaches
 * past the next training date of c. A chip with at least two samples gets their median, rounded up.
 */
fun typicalStiffDays(checkIns: List<CheckIn>, trained: List<Trained>): Map<Chip, Int> {
    val ordered = checkIns.sortedBy { it.date }
    val samples = mutableMapOf<Chip, MutableList<Int>>()
    for (session in trained.groupBy { it.date }) {
        val date = session.key
        val after = ordered.filter { it.date.isAfter(date) }
        for (chip in session.value.flatMap { it.chips }.toSet()) {
            // a run cannot reach past the next time this chip was trained: from there on it belongs to that session
            val nextTrained = trained.filter { chip in it.chips && it.date.isAfter(date) }.minOfOrNull { it.date }
            val run = mutableListOf<CheckIn>()
            for (checkIn in after) {
                if (chip !in checkIn.stiff) break
                if (nextTrained != null && checkIn.date.isAfter(nextTrained)) break
                // a calendar day with no check-in is a loose day: it ends the run
                val previous = run.lastOrNull()
                if (previous != null && ChronoUnit.DAYS.between(previous.date, checkIn.date) > 1) break
                run.add(checkIn)
            }
            val last = run.lastOrNull() ?: continue
            samples.getOrPut(chip) { mutableListOf() }.add(ChronoUnit.DAYS.between(date, last.date).toInt())
        }
    }
    return samples.filterValues { it.size >= MIN_SAMPLES }.mapValues { (_, days) -> medianRoundedUp(days) }
}

private fun medianRoundedUp(values: List<Int>): Int {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid] + 1) / 2
}
