package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class BlocksTest {
    private val start = LocalDate.of(2026, 1, 5)
    private val days = setOf(1L, 2L, 3L)
    private val rounds = 3

    private fun full(dayId: Long, date: LocalDate, snapshot: Set<Long>? = null) =
        DaySessionSummary(dayId, date, 5, 5, false, snapshot)

    /** [n] full sessions rotating A, B, C, one per day starting on [start]. */
    private fun sessions(n: Int, snapshot: Set<Long>? = null): List<DaySessionSummary> =
        (0 until n).map { full(days.toList()[it % 3], start.plusDays(it.toLong()), snapshot) }

    private fun state(sessions: List<DaySessionSummary>, today: LocalDate = start.plusDays(20), active: Set<Long> = days) =
        blockState(active, sessions, rounds, today)

    @Test fun aFullRoundOfAllDaysStartsRoundTwo() {
        val s = state(sessions(3))
        assertThat(s.blockIndex).isEqualTo(0)
        assertThat(s.roundInBlock).isEqualTo(2)
        assertThat(s.roundsPerBlock).isEqualTo(4)
        assertThat(s.doneThisRound).isEmpty()
    }

    @Test fun doingADayTwiceDoesNotCompleteTheRoundFaster() {
        val s = state(listOf(full(1, start), full(1, start.plusDays(1)), full(2, start.plusDays(2))))
        assertThat(s.roundInBlock).isEqualTo(1)
        assertThat(s.doneThisRound).containsExactly(1L, 2L)
    }

    @Test fun halfTheSlotsLoggedCountsAsDone() {
        fun done(logged: Int, total: Int) = countsAsDone(DaySessionSummary(1, start, logged, total, false))
        assertThat(done(2, 5)).isFalse()
        assertThat(done(3, 5)).isTrue()
        assertThat(done(2, 4)).isTrue()
    }

    @Test fun aSessionWithNothingLoggedIsNotDone() {
        assertThat(countsAsDone(DaySessionSummary(1, start, 0, 0, false))).isFalse()
    }

    @Test fun flushOnlySessionsDoNotCount() {
        assertThat(countsAsDone(DaySessionSummary(1, start, 5, 5, true))).isFalse()
        val s = state(listOf(DaySessionSummary(1, start, 5, 5, true)))
        assertThat(s.doneThisRound).isEmpty()
    }

    @Test fun anUnderHalfSessionLeavesTheDayNotDone() {
        val s = state(listOf(DaySessionSummary(1, start, 2, 5, false)))
        assertThat(s.doneThisRound).isEmpty()
    }

    @Test fun theRoundAfterTheBuildRoundsIsTheDeloadRound() {
        val s = state(sessions(9))
        assertThat(s.isDeloadRound).isTrue()
        assertThat(s.roundInBlock).isEqualTo(4)
        assertThat(s.blockIndex).isEqualTo(0)
    }

    @Test fun aFinishedDeloadRoundStartsTheNextBlock() {
        val s = state(sessions(12))
        assertThat(s.blockIndex).isEqualTo(1)
        assertThat(s.roundInBlock).isEqualTo(1)
        assertThat(s.isDeloadRound).isFalse()
    }

    @Test fun aPartlyDoneDeloadRoundCountsTheDaysDoneSoFar() {
        val s = state(sessions(10))
        assertThat(s.isDeloadRound).isTrue()
        assertThat(s.doneThisRound).containsExactly(1L)
    }

    @Test fun aDeloadRoundLeftMoreThan14DaysClosesOnToday() {
        // Nine sessions: the deload round starts on the day the ninth was done.
        val d = start.plusDays(8)
        val sessions = sessions(9)
        assertThat(state(sessions, today = d.plusDays(14)).isDeloadRound).isTrue()
        val s = state(sessions, today = d.plusDays(15))
        assertThat(s.blockIndex).isEqualTo(1)
        assertThat(s.roundInBlock).isEqualTo(1)
        assertThat(s.isDeloadRound).isFalse()
        assertThat(s.doneThisRound).isEmpty()
    }

    @Test fun aDeloadSessionMoreThan14DaysLateStartsTheNextBlock() {
        val d = start.plusDays(8)
        val late = full(1, d.plusDays(15))
        val s = state(sessions(9) + late, today = d.plusDays(15))
        assertThat(s.blockIndex).isEqualTo(1)
        assertThat(s.roundInBlock).isEqualTo(1)
        assertThat(s.doneThisRound).containsExactly(1L)
    }

    @Test fun aDeloadSessionExactly14DaysLaterStillBelongsToTheDeloadRound() {
        val d = start.plusDays(8)
        val s = state(sessions(9) + full(1, d.plusDays(14)), today = d.plusDays(14))
        assertThat(s.blockIndex).isEqualTo(0)
        assertThat(s.isDeloadRound).isTrue()
        assertThat(s.doneThisRound).containsExactly(1L)
    }

    @Test fun aBuildRoundNeverAutoCloses() {
        val s = state(sessions(3), today = start.plusDays(200))
        assertThat(s.blockIndex).isEqualTo(0)
        assertThat(s.roundInBlock).isEqualTo(2)
    }

    @Test fun noActiveDaysGivesBlockZeroRoundOne() {
        val s = state(sessions(5), active = emptySet())
        assertThat(s.blockIndex).isEqualTo(0)
        assertThat(s.roundInBlock).isEqualTo(1)
        assertThat(s.isDeloadRound).isFalse()
        assertThat(s.doneThisRound).isEmpty()
    }

    @Test fun deletingTheLastUndoneDayCompletesTheRoundAtOnce() {
        val s = state(listOf(full(1, start), full(2, start.plusDays(1))), active = setOf(1L, 2L))
        assertThat(s.roundInBlock).isEqualTo(2)
        assertThat(s.doneThisRound).isEmpty()
    }

    @Test fun sessionsOfADeletedDayAreIgnored() {
        val s = state(listOf(full(3, start), full(1, start.plusDays(1))), active = setOf(1L, 2L))
        assertThat(s.roundInBlock).isEqualTo(1)
        assertThat(s.doneThisRound).containsExactly(1L)
    }

    @Test fun addingADayMidRoundKeepsTheRoundOpenUntilItIsDone() {
        val s = state(listOf(full(1, start), full(2, start.plusDays(1))), active = setOf(1L, 2L, 3L))
        assertThat(s.roundInBlock).isEqualTo(1)
        assertThat(s.doneThisRound).containsExactly(1L, 2L)
    }

    @Test fun sessionsAreFoldedByDateNotByListOrder() {
        val s = state(sessions(9).reversed())
        assertThat(s.isDeloadRound).isTrue()
        assertThat(s.roundInBlock).isEqualTo(4)
    }

    @Test fun headerTextShowsBlockAndRoundOneBased() {
        assertThat(headerText(BlockState(1, 2, 4, false, emptySet()))).isEqualTo("Block 2 · Round 2 of 4")
        assertThat(headerText(BlockState(0, 4, 4, true, emptySet()))).isEqualTo("Block 1 · Deload round")
    }

    // Snapshots: a session brings the days its round needed, so plan edits never re-judge rounds already played.

    /** Sessions of [order] (day ids), one per day from [start], each started with the days [snapshot]. */
    private fun inOrder(vararg order: Long, snapshot: Set<Long>?) =
        order.mapIndexed { i, d -> full(d, start.plusDays(i.toLong()), snapshot) }

    @Test fun addingADayAfterTenSessionsLeavesThePlayedRoundsAlone() {
        val played = sessions(10, snapshot = days) // rounds 1-3, then A in the deload round
        val s = state(played, active = days + 4L)
        assertThat(s.blockIndex).isEqualTo(0)
        assertThat(s.isDeloadRound).isTrue()
        assertThat(s.roundInBlock).isEqualTo(4)
        assertThat(s.doneThisRound).containsExactly(1L)
    }

    @Test fun theAddedDayIsOutstandingForTheOpenRoundOnly() {
        val played = sessions(10, snapshot = days)
        val all = days + 4L
        val b = full(2, start.plusDays(10), all)
        val c = full(3, start.plusDays(11), all)
        assertThat(state(played + b + c, active = all).isDeloadRound).isTrue()
        assertThat(state(played + b + c, active = all).doneThisRound).containsExactly(1L, 2L, 3L)

        val s = state(played + b + c + full(4, start.plusDays(12), all), active = all)
        assertThat(s.blockIndex).isEqualTo(1)
        assertThat(s.roundInBlock).isEqualTo(1)
    }

    @Test fun removingADayNeverReplaysPastRoundsAgainstTheSmallerPlan() {
        // A, B, A, B, C: one round played. Against {A, B} alone it would read as two.
        val s = state(inOrder(1, 2, 1, 2, 3, snapshot = days), active = setOf(1L, 2L))
        assertThat(s.blockIndex).isEqualTo(0)
        assertThat(s.roundInBlock).isEqualTo(2)
        assertThat(s.doneThisRound).isEmpty()
    }

    @Test fun removingADayCompletesTheOpenRoundOnceAndNeverMore() {
        val s = state(inOrder(1, 2, 3, 1, 2, snapshot = days), active = setOf(1L, 2L))
        assertThat(s.blockIndex).isEqualTo(0)
        assertThat(s.roundInBlock).isEqualTo(3)
        assertThat(s.doneThisRound).isEmpty()
    }

    @Test fun removingADayOnTheLastRoundOfABlockStartsTheNextBlockOnce() {
        val s = state(inOrder(1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 2, snapshot = days), active = setOf(1L, 2L))
        assertThat(s.blockIndex).isEqualTo(1)
        assertThat(s.roundInBlock).isEqualTo(1)
    }

    @Test fun aDayRemovedBetweenSessionsCompletesTheRoundBeforeTheNextSessionStartsOne() {
        // A and B done, then C was deleted: round 1 was complete, so the next A and B are round 2.
        val before = inOrder(1, 2, snapshot = days)
        val after = listOf(full(1, start.plusDays(2), setOf(1L, 2L)), full(2, start.plusDays(3), setOf(1L, 2L)))
        val s = state(before + after, active = setOf(1L, 2L))
        assertThat(s.blockIndex).isEqualTo(0)
        assertThat(s.roundInBlock).isEqualTo(3)
        assertThat(s.doneThisRound).isEmpty()
    }

    @Test fun sessionsWithoutASnapshotFallBackToTheCurrentDays() {
        val legacy = sessions(3) // snapshot null
        val withNewDay = state(legacy, active = days + 4L)
        assertThat(withNewDay.roundInBlock).isEqualTo(1)
        assertThat(withNewDay.doneThisRound).containsExactly(1L, 2L, 3L)

        val withDayRemoved = state(inOrder(1, 2, 1, 2, snapshot = null), active = setOf(1L, 2L))
        assertThat(withDayRemoved.roundInBlock).isEqualTo(3)
    }

    @Test fun aSnapshotSessionAndALegacyOneFoldTogether() {
        val s = state(inOrder(1, snapshot = null) + full(2, start.plusDays(1), days) + full(3, start.plusDays(2), days))
        assertThat(s.roundInBlock).isEqualTo(2)
    }

    // The deload auto-close is time-based: any session more than 14 days after the deload began closes it, counted or not.

    private fun deloadStart() = start.plusDays(8) // the ninth session of sessions(9)

    @Test fun anUnderHalfSessionMoreThan14DaysAfterTheDeloadStartedClosesIt() {
        val late = DaySessionSummary(1, deloadStart().plusDays(15), 2, 5, false)
        val s = state(sessions(9) + late, today = deloadStart().plusDays(15))
        assertThat(s.blockIndex).isEqualTo(1)
        assertThat(s.roundInBlock).isEqualTo(1)
        assertThat(s.isDeloadRound).isFalse()
        assertThat(s.doneThisRound).isEmpty() // the late session started block 2 but did not count
    }

    @Test fun aFlushOnlySessionMoreThan14DaysAfterTheDeloadStartedClosesIt() {
        val late = DaySessionSummary(1, deloadStart().plusDays(15), 5, 5, true)
        val s = state(sessions(9) + late, today = deloadStart().plusDays(15))
        assertThat(s.blockIndex).isEqualTo(1)
        assertThat(s.roundInBlock).isEqualTo(1)
        assertThat(s.doneThisRound).isEmpty()
    }

    @Test fun anUnderHalfSessionExactly14DaysAfterTheDeloadStartedLeavesItOpen() {
        val late = DaySessionSummary(1, deloadStart().plusDays(14), 2, 5, false)
        val s = state(sessions(9) + late, today = deloadStart().plusDays(14))
        assertThat(s.blockIndex).isEqualTo(0)
        assertThat(s.isDeloadRound).isTrue()
    }
}
