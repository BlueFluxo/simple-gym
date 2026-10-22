package com.simplegym.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A deload round left partly done for longer than this closes by itself (spec §6.4). */
private const val DELOAD_AUTO_CLOSE_DAYS = 14L

/**
 * One finished session as the block fold sees it. [slotsLogged] counts the plan slots that have a log, [slotsTotal] is
 * the slot count stored when the session started, [flushOnly] marks a session whose logs are all flush, and
 * [activeDayIdsAtStart] is the active days when it started (null for sessions from before that was stored).
 */
data class DaySessionSummary(
    val dayId: Long,
    val date: LocalDate,
    val slotsLogged: Int,
    val slotsTotal: Int,
    val flushOnly: Boolean,
    val activeDayIdsAtStart: Set<Long>? = null,
)

/** [blockIndex] is 0-based; [roundInBlock] is 1-based. The last round of a block ([roundsPerBlock]) is the deload. */
data class BlockState(
    val blockIndex: Int,
    val roundInBlock: Int,
    val roundsPerBlock: Int,
    val isDeloadRound: Boolean,
    val doneThisRound: Set<Long>,
)

/** A day counts for its round at half its slots logged or more; flush-only sessions never count (spec §6.4). */
fun countsAsDone(s: DaySessionSummary): Boolean = s.slotsLogged > 0 && s.slotsLogged * 2 >= s.slotsTotal && !s.flushOnly

/**
 * Folds [sessions] oldest first. A round completes when the days done in it cover the days it needed: for a session,
 * the [DaySessionSummary.activeDayIdsAtStart] it was started with (the current [activeDayIds] when that is null). So
 * adding a day later never re-opens rounds already played, and only the round still open is judged against the
 * *current* [activeDayIds]: deleting or archiving a day can complete it at once. Sessions of days a round did not need
 * are ignored. [buildRounds] build rounds are followed by one deload round. A deload round whose start (the date the
 * previous round completed) is more than 14 days before the next session, whatever that session is, or before
 * [today], is closed and the next block starts. Callers pass only finished sessions.
 */
fun blockState(activeDayIds: Set<Long>, sessions: List<DaySessionSummary>, buildRounds: Int, today: LocalDate): BlockState {
    val roundsPerBlock = buildRounds + 1
    var block = 0
    var round = 1
    var done = emptySet<Long>()
    var roundStart: LocalDate? = null
    var lastDoneDate: LocalDate? = null

    fun overdue(until: LocalDate): Boolean =
        round == roundsPerBlock && roundStart != null && ChronoUnit.DAYS.between(roundStart, until) > DELOAD_AUTO_CLOSE_DAYS

    fun nextBlock() {
        block++
        round = 1
        done = emptySet()
        roundStart = null
    }

    fun completeRound(on: LocalDate?) {
        done = emptySet()
        roundStart = on
        if (round == roundsPerBlock) {
            block++
            round = 1
        } else {
            round++
        }
    }

    if (activeDayIds.isNotEmpty()) {
        for (s in sessions.sortedBy { it.date }) {
            val needed = s.activeDayIdsAtStart ?: activeDayIds
            // The plan shrank since the last session: the open round already had every day this session's round needs.
            if (needed.isNotEmpty() && done.containsAll(needed)) completeRound(lastDoneDate)
            if (overdue(s.date)) nextBlock()
            if (s.dayId !in needed || !countsAsDone(s)) continue
            done = done + s.dayId
            lastDoneDate = s.date
            if (done.containsAll(needed)) completeRound(s.date)
        }
        if (done.containsAll(activeDayIds)) completeRound(lastDoneDate)
        if (overdue(today)) nextBlock()
    }
    return BlockState(block, round, roundsPerBlock, round == roundsPerBlock, done.intersect(activeDayIds))
}


/** "Block 2 · Round 2 of 4", or "Block 2 · Deload round" in the deload round. */
fun headerText(s: BlockState): String {
    val block = "Block ${s.blockIndex + 1}"
    return if (s.isDeloadRound) "$block · Deload round" else "$block · Round ${s.roundInBlock} of ${s.roundsPerBlock}"
}
