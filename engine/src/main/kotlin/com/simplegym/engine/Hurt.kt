package com.simplegym.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Days after a hurt flag during which other exercises loading the same joint get a caution (spec 6.9). */
private const val CAUTION_DAYS = 13L

/** Pain-free logs in a row that clear a variant's hurt counts. */
private const val CLEAR_AFTER_PAIN_FREE = 3

/** Pain-free own logs after a hurt event that lift the joint caution it caused. */
private const val LIFT_AFTER_PAIN_FREE = 2

/** A hurt flag as the joint-caution rule sees it: [exerciseId] hurt [joint] on [date]. */
data class HurtEvent(val exerciseId: String, val joint: Joint, val date: LocalDate)

/**
 * The hurt counts per joint that are still active for one variant. [history] is that variant's own logs, oldest
 * first; only logs dated after [clearedOn] count. Every hurt log adds to its joint and restarts the pain-free run; the
 * third pain-free log in a row clears every count.
 */
fun activeHurts(history: List<LogRecord>, clearedOn: LocalDate?): Map<Joint, Int> {
    val counts = linkedMapOf<Joint, Int>()
    var painFree = 0
    for (log in history) {
        if (clearedOn != null && !log.date.isAfter(clearedOn)) continue
        val joint = log.hurt
        if (joint != null) {
            counts[joint] = (counts[joint] ?: 0) + 1
            painFree = 0
        } else if (++painFree >= CLEAR_AFTER_PAIN_FREE) {
            counts.clear()
            painFree = 0
        }
    }
    return counts
}

/**
 * The joint [exercise] should be handled gently on [today], or null. A hurt on another exercise loading a joint that
 * [exercise] also loads counts for 14 days (today minus its date at most 13); it is lifted early once [exercise]
 * has two pain-free logs of its own dated after that hurt. The most recent unlifted event decides the joint.
 */
fun jointCaution(exercise: Exercise, otherHurts: List<HurtEvent>, ownLogs: List<LogRecord>, today: LocalDate): Joint? =
    otherHurts
        .filter { it.exerciseId != exercise.id && it.joint in exercise.joints }
        .filter { ChronoUnit.DAYS.between(it.date, today) in 0..CAUTION_DAYS }
        .sortedByDescending { it.date }
        .firstOrNull { event ->
            ownLogs.count { it.hurt == null && it.date.isAfter(event.date) } < LIFT_AFTER_PAIN_FREE
        }
        ?.joint

/**
 * What to suggest instead of an exercise that hurt [joint]: the first of [swaps] that does not load it; failing that
 * the member of [group] that does not load it and loads the fewest joints (the first on ties); failing that null.
 * Callers pass lists already ordered and filtered (availability, archive, back protection).
 */
fun jointFriendlySwap(joint: Joint, swaps: List<Exercise>, group: List<Exercise>): Exercise? =
    swaps.firstOrNull { joint !in it.joints }
        ?: group.filter { joint !in it.joints }.minByOrNull { it.joints.size }
