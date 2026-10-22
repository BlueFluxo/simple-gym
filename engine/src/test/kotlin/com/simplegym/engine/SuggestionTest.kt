package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class SuggestionTest {
    private val today = LocalDate.of(2026, 10, 22)
    private fun ago(days: Long): LocalDate = today.minusDays(days)

    private val chest = DayCard(1, setOf(Chip.CHEST, Chip.TRICEPS, Chip.SHOULDERS), ago(2))
    private val back = DayCard(2, setOf(Chip.BACK, Chip.BICEPS), ago(5))
    private val legs = DayCard(3, setOf(Chip.LEGS, Chip.CALVES), ago(1))
    private val days = listOf(chest, back, legs)

    private fun suggest(
        stiff: Set<Chip> = emptySet(),
        done: Set<Long> = emptySet(),
        typical: Map<Chip, Int> = emptyMap(),
        cards: List<DayCard> = days,
    ) = suggestDay(cards, stiff, done, typical, today)

    // ---- suggestDay ----

    @Test fun nothingStiffSuggestsTheDayDoneLongestAgo() {
        val s = suggest()

        assertThat(s.dayId).isEqualTo(2L)
        assertThat(s.blocked).isEmpty()
        assertThat(s.flushPrompt).isFalse()
    }

    @Test fun legsStiffBlocksTheLegsDayAndSuggestsTheUnblockedDayWithTheOldestLastDone() {
        val cards = listOf(chest, back.copy(lastDone = ago(1)), legs.copy(lastDone = ago(9)))

        val s = suggest(stiff = setOf(Chip.LEGS), cards = cards)

        assertThat(s.blocked).containsExactly(3L)
        assertThat(s.dayId).isEqualTo(1L) // legs is the oldest but blocked; chest (2 days) is older than back (1 day)
        assertThat(s.flushPrompt).isFalse()
    }

    @Test fun anyOneOfADaysChipsBeingStiffBlocksIt() {
        val s = suggest(stiff = setOf(Chip.CALVES, Chip.BICEPS))

        assertThat(s.blocked).containsExactly(2L, 3L)
        assertThat(s.dayId).isEqualTo(1L)
    }

    @Test fun everyDayBlockedSuggestsTheOldestWithAFlushPrompt() {
        val s = suggest(stiff = setOf(Chip.CHEST, Chip.BACK, Chip.LEGS))

        assertThat(s.blocked).containsExactly(1L, 2L, 3L)
        assertThat(s.dayId).isEqualTo(2L)
        assertThat(s.flushPrompt).isTrue()
    }

    @Test fun lowerBackAndNeckStiffBlockNothing() {
        val s = suggest(stiff = setOf(Chip.LOWER_BACK, Chip.NECK))

        assertThat(s.blocked).isEmpty()
        assertThat(s.dayId).isEqualTo(2L)
        assertThat(s.flushPrompt).isFalse()
    }

    @Test fun aDayWhoseChipsAreLowerBackOrNeckIsNeverBlockedByThem() {
        val core = DayCard(4, setOf(Chip.LOWER_BACK, Chip.NECK), ago(30))

        val s = suggest(stiff = setOf(Chip.LOWER_BACK, Chip.NECK), cards = days + core)

        assertThat(s.blocked).isEmpty()
        assertThat(s.dayId).isEqualTo(4L)
    }

    @Test fun aDayNeverDoneIsTheLongestAgo() {
        val s = suggest(cards = listOf(chest, back, legs.copy(lastDone = null)))

        assertThat(s.dayId).isEqualTo(3L)
    }

    @Test fun tieOnLastDoneGoesToTheDayNotDoneThisRound() {
        val tied = listOf(chest.copy(lastDone = ago(4)), back.copy(lastDone = ago(4)), legs)

        assertThat(suggest(cards = tied, done = setOf(1L)).dayId).isEqualTo(2L)
        assertThat(suggest(cards = tied, done = setOf(2L)).dayId).isEqualTo(1L)
    }

    @Test fun thenTheLowestSummedTypicalStiffDaysOverTheDaysChips() {
        val tied = listOf(chest.copy(lastDone = ago(4)), back.copy(lastDone = ago(4)), legs)
        val typical = mapOf(Chip.CHEST to 3, Chip.TRICEPS to 2, Chip.BACK to 1, Chip.BICEPS to 1)

        // chest sums to 5, back to 2; a chip with no estimate counts as 0
        assertThat(suggest(cards = tied, typical = typical).dayId).isEqualTo(2L)
        assertThat(suggest(cards = tied, done = setOf(2L), typical = typical).dayId).isEqualTo(1L) // not-done-this-round ranks first
    }

    @Test fun aFullTieKeepsThePlanOrder() {
        val tied = listOf(chest.copy(lastDone = ago(4)), back.copy(lastDone = ago(4)))

        assertThat(suggest(cards = tied).dayId).isEqualTo(1L)
        assertThat(suggest(cards = tied.reversed()).dayId).isEqualTo(2L)
    }

    @Test fun everyDayBlockedTiesFollowTheSameOrderAsTheUnblockedCase() {
        val tied = listOf(chest.copy(lastDone = ago(4)), back.copy(lastDone = ago(4)))

        val s = suggest(stiff = setOf(Chip.CHEST, Chip.BACK), cards = tied, done = setOf(1L))

        assertThat(s.dayId).isEqualTo(2L)
        assertThat(s.flushPrompt).isTrue()
    }

    @Test fun noDaysSuggestsNothing() {
        val s = suggest(cards = emptyList(), stiff = setOf(Chip.LEGS))

        assertThat(s).isEqualTo(DaySuggestion(null, emptySet(), false))
    }

    // ---- typicalStiffDays ----

    private fun d(n: Long): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n)

    @Test fun legsTrainedTwiceAndStiffForTwoThenThreeDaysIsThreeDays() {
        val trained = listOf(Trained(d(0), setOf(Chip.LEGS)), Trained(d(10), setOf(Chip.LEGS)))
        val checkIns = listOf(
            CheckIn(d(2), setOf(Chip.LEGS)), CheckIn(d(3), emptySet()),
            CheckIn(d(13), setOf(Chip.LEGS)), CheckIn(d(14), emptySet()),
        )

        // samples 2 (d0 to d2) and 3 (d10 to d13): median 2.5, rounded up
        assertThat(typicalStiffDays(checkIns, trained)).containsExactly(Chip.LEGS, 3)
    }

    @Test fun aSingleSampleGivesNoEstimate() {
        val trained = listOf(Trained(d(0), setOf(Chip.LEGS)))
        val checkIns = listOf(CheckIn(d(2), setOf(Chip.LEGS)), CheckIn(d(3), emptySet()))

        assertThat(typicalStiffDays(checkIns, trained)).isEmpty()
    }

    @Test fun theSampleRunsToTheLastConsecutiveCheckInWithTheChipAndStopsAtTheFirstWithout() {
        val trained = listOf(Trained(d(0), setOf(Chip.LEGS)), Trained(d(20), setOf(Chip.LEGS)))
        val checkIns = listOf(
            CheckIn(d(1), setOf(Chip.LEGS)), CheckIn(d(2), setOf(Chip.LEGS, Chip.CALVES)), CheckIn(d(3), setOf(Chip.LEGS)),
            CheckIn(d(4), setOf(Chip.CHEST)), CheckIn(d(5), setOf(Chip.LEGS)), // after a gap: not part of d0's run
            CheckIn(d(22), setOf(Chip.LEGS)), CheckIn(d(23), emptySet()),
        )

        // d0: the last consecutive check-in with LEGS is d3 (3 days); d20: d22 (2 days). Median 2.5, rounded up.
        assertThat(typicalStiffDays(checkIns, trained)).containsExactly(Chip.LEGS, 3)
    }

    @Test fun aTrainingWhoseNextCheckInLacksTheChipOrHasNoCheckInGivesNoSample() {
        val trained = listOf(
            Trained(d(0), setOf(Chip.LEGS)), Trained(d(10), setOf(Chip.LEGS)), Trained(d(20), setOf(Chip.LEGS)),
        )
        val checkIns = listOf(
            CheckIn(d(1), emptySet()), CheckIn(d(2), setOf(Chip.LEGS)), // d0: the first check-in lacks LEGS: no sample
            CheckIn(d(12), setOf(Chip.LEGS)), CheckIn(d(13), emptySet()), // d10: 2 days
            // d20: no check-in after it at all
        )

        assertThat(typicalStiffDays(checkIns, trained)).isEmpty() // one sample is not enough
    }

    @Test fun checkInsOnTheTrainingDayItselfDoNotCount() {
        val trained = listOf(Trained(d(0), setOf(Chip.LEGS)), Trained(d(10), setOf(Chip.LEGS)))
        val checkIns = listOf(
            CheckIn(d(0), setOf(Chip.LEGS)), CheckIn(d(1), emptySet()),
            CheckIn(d(10), setOf(Chip.LEGS)), CheckIn(d(11), emptySet()),
        )

        assertThat(typicalStiffDays(checkIns, trained)).isEmpty()
    }

    @Test fun eachChipIsEstimatedOnItsOwnAndAChipWithOneSampleHasNoEstimate() {
        val trained = (0..2).map { Trained(d(it * 10L), setOf(Chip.CHEST, Chip.BACK)) }
        val checkIns = listOf(
            CheckIn(d(1), setOf(Chip.CHEST, Chip.BACK)), CheckIn(d(2), setOf(Chip.CHEST)), CheckIn(d(3), emptySet()), // chest 2, back 1
            CheckIn(d(14), setOf(Chip.CHEST)), CheckIn(d(15), emptySet()), // chest 4 (from d10), back none
            CheckIn(d(23), setOf(Chip.CHEST)), CheckIn(d(24), emptySet()), // chest 3 (from d20), back none
        )

        assertThat(typicalStiffDays(checkIns, trained)).containsExactly(Chip.CHEST, 3) // samples 2, 4, 3: median 3
    }

    @Test fun unsortedInputIsHandled() {
        val trained = listOf(Trained(d(10), setOf(Chip.LEGS)), Trained(d(0), setOf(Chip.LEGS)))
        val checkIns = listOf(
            CheckIn(d(14), emptySet()), CheckIn(d(13), setOf(Chip.LEGS)),
            CheckIn(d(3), emptySet()), CheckIn(d(2), setOf(Chip.LEGS)),
        )

        assertThat(typicalStiffDays(checkIns, trained)).containsExactly(Chip.LEGS, 3)
    }

    @Test fun anEvenCountTakesTheMeanOfTheMiddleTwoRoundedUp() {
        val trained = (0..3).map { Trained(d(it * 10L), setOf(Chip.LEGS)) }
        val checkIns = listOf(
            CheckIn(d(1), setOf(Chip.LEGS)), CheckIn(d(2), emptySet()), // 1
            CheckIn(d(12), setOf(Chip.LEGS)), CheckIn(d(13), emptySet()), // 2
            CheckIn(d(23), setOf(Chip.LEGS)), CheckIn(d(24), emptySet()), // 3
            CheckIn(d(34), setOf(Chip.LEGS)), CheckIn(d(35), emptySet()), // 4
        )

        assertThat(typicalStiffDays(checkIns, trained)).containsExactly(Chip.LEGS, 3) // (2+3)/2 = 2.5, rounded up
    }

    @Test fun aSampleStopsAtTheNextTrainingAndAtADayWithNoCheckIn() {
        val trained = listOf(Trained(d(0), setOf(Chip.LEGS)), Trained(d(5), setOf(Chip.LEGS)))
        val checkIns = listOf(
            CheckIn(d(1), setOf(Chip.LEGS)), CheckIn(d(2), setOf(Chip.LEGS)), // no row on d3 or d4: loose days
            CheckIn(d(6), setOf(Chip.LEGS)), CheckIn(d(7), setOf(Chip.LEGS)),
        )

        // d0: 2 days (the run ends at the gap, it does not reach d7); d5: 2 days
        assertThat(typicalStiffDays(checkIns, trained)).containsExactly(Chip.LEGS, 2)
    }

    @Test fun aRunNeverReachesPastTheNextTrainingOfThatChip() {
        val trained = listOf(Trained(d(0), setOf(Chip.LEGS)), Trained(d(3), setOf(Chip.LEGS)))
        val checkIns = (1..6).map { CheckIn(d(it.toLong()), setOf(Chip.LEGS)) } + CheckIn(d(7), emptySet())

        // d0 is bounded at d3 (3 days, not 6); d3 runs d4 to d6 (3 days)
        assertThat(typicalStiffDays(checkIns, trained)).containsExactly(Chip.LEGS, 3)
    }
}
