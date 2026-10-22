package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class CoachTest {
    private val today = LocalDate.of(2026, 10, 22)
    private val rules = ageRules(50)

    private val bench = Exercise(
        id = "bench-press", name = "Bench press", muscle = Muscle.CHEST, equipment = "Barbell", swapGroup = "horizontal-press",
        incrementKg = 2.5, backLoad = BackLoad.LOW, joints = setOf(Joint.SHOULDER, Joint.ELBOW),
        isAssisted = false, isBodyweight = false,
    )
    private val chinUp = bench.copy(
        id = "chin-up", name = "Chin-up", muscle = Muscle.BACK, incrementKg = null, isBodyweight = true,
        joints = setOf(Joint.ELBOW),
    )
    private val assistedPullUp = bench.copy(
        id = "assisted-pull-up", name = "Assisted pull-up", muscle = Muscle.BACK, incrementKg = 2.5, isAssisted = true,
    )

    private val slot = SlotPlan(sets = 3, baseRange = RepRange(8, 12), roundSec = 120)
    private val bwSlot = SlotPlan(sets = 4, baseRange = RepRange(6, 10), roundSec = 90)

    private fun log(
        kg: Double?, reps: Int, daysAgo: Long = 3, rangeType: RangeType = RangeType.BASE, feel: Feel = Feel.SOLID,
        type: SessionType = SessionType.NORMAL, hurt: Joint? = null, ex: Exercise = bench,
    ) = LogRecord(ex.id, today.minusDays(daysAgo), kg, reps, 3, feel, type, rangeType, hurt)

    private fun block(index: Int = 0, deload: Boolean = false) =
        BlockState(index, if (deload) 4 else 1, 4, deload, emptySet())

    private fun input(
        ex: Exercise = bench,
        slot: SlotPlan = this.slot,
        history: List<LogRecord> = listOf(log(60.0, 12)),
        block: BlockState = block(),
        movementLastTrained: LocalDate? = today.minusDays(3),
        stiff: Boolean = false,
        flush: Boolean = false,
        otherHurts: List<HurtEvent> = emptyList(),
        hurtClearedOn: LocalDate? = null,
        hint: WeightHint? = null,
    ) = CoachInput(
        exercise = ex, slot = slot, history = history, hurtClearedOn = hurtClearedOn,
        movementLastTrained = movementLastTrained, otherHurts = otherHurts, sameEquipmentHint = hint,
        block = block, rules = rules, stiffMuscle = stiff, flush = flush, today = today,
    )

    @Test fun plainProgression() {
        val t = coachTarget(input())

        assertThat(t.weightKg).isEqualTo(62.5)
        assertThat(t.type).isEqualTo(SessionType.NORMAL)
        assertThat(t.sets).isEqualTo(3)
        assertThat(t.range).isEqualTo(RepRange(8, 12))
        assertThat(t.rangeType).isEqualTo(RangeType.BASE)
        assertThat(t.roundSec).isEqualTo(120)
    }

    @Test fun deloadRound() {
        val t = coachTarget(input(block = block(deload = true)))

        assertThat(t.weightKg).isEqualTo(52.5)
        assertThat(t.sets).isEqualTo(2)
        assertThat(t.type).isEqualTo(SessionType.DELOAD)
        assertThat(t.repGoal).isNull()
        assertThat(t.reason).isEqualTo("Deload round: lighter so your joints catch up.")
    }

    @Test fun stiffHolds() {
        val t = coachTarget(input(stiff = true))

        assertThat(t.weightKg).isEqualTo(60.0)
        assertThat(t.sets).isEqualTo(3)
        assertThat(t.type).isEqualTo(SessionType.HELD)
        assertThat(t.repGoal).isNull()
        assertThat(t.reason).isEqualTo("Stiff today: holding at 60kg with a longer warm-up.")
    }

    @Test fun layoff21Days() {
        val t = coachTarget(input(movementLastTrained = today.minusDays(21)))

        assertThat(t.weightKg).isEqualTo(47.5) // 60 x 0.8 = 48, floored to a step
        assertThat(t.type).isEqualTo(SessionType.RETURN)
        assertThat(t.reason).isEqualTo("Back after 21 days: easing in at −20%.")
    }

    @Test fun combinedPicksSingleLowest() {
        val t = coachTarget(input(block = block(deload = true), stiff = true, movementLastTrained = today.minusDays(21)))

        assertThat(t.weightKg).isEqualTo(47.5)
        assertThat(t.sets).isEqualTo(3)
        assertThat(t.type).isEqualTo(SessionType.RETURN)
    }

    @Test fun flush() {
        val t = coachTarget(input(flush = true))

        assertThat(t.weightKg).isEqualTo(25.0) // 60 x 0.45 = 27, floored
        assertThat(t.sets).isEqualTo(3)
        assertThat(t.range).isEqualTo(RepRange(15, 20))
        assertThat(t.type).isEqualTo(SessionType.FLUSH)
        assertThat(t.repGoal).isNull()
        assertThat(t.roundSec).isEqualTo(60)
        assertThat(t.reason).isEqualTo("Flush: light weight, high reps to get blood into a stiff muscle.")
    }

    @Test fun flushOfATwoSetSlotKeepsTwoSets() {
        assertThat(coachTarget(input(slot = slot.copy(sets = 2), flush = true)).sets).isEqualTo(2)
    }

    @Test fun highRepBlockConverts() {
        val t = coachTarget(input(block = block(index = 1)))

        assertThat(t.range).isEqualTo(RepRange(12, 16))
        assertThat(t.weightKg).isEqualTo(55.0) // convertWeight(60, 12, 16, 2.5), nearest step
        assertThat(t.repGoal).isEqualTo(13)
        assertThat(t.type).isEqualTo(SessionType.NORMAL)
        assertThat(t.rangeType).isEqualTo(RangeType.HIGH_REP)
    }

    @Test fun plateauResets() {
        val history = (0 until 4L).map { log(60.0, 10, daysAgo = 12 - it * 3) }

        val t = coachTarget(input(history = history))

        assertThat(t.weightKg).isEqualTo(52.5)
        assertThat(t.type).isEqualTo(SessionType.NORMAL)
        assertThat(t.reason).startsWith("Plateau")
        assertThat(t.reason).isEqualTo("Plateau: 3 sessions without progress, so dropping 10% to build back up.")
    }

    @Test fun plateauCountsOnlyTheLogsOfTheCurrentRangeType() {
        // three stalled BASE sessions, then one HIGH_REP session; in a HIGH_REP block the BASE stall must not count
        val history = (0 until 4L).map { log(60.0, 10, daysAgo = 15 - it * 3) } +
            log(55.0, 14, daysAgo = 3, rangeType = RangeType.HIGH_REP)

        val t = coachTarget(input(history = history, block = block(index = 1)))

        assertThat(t.reason).doesNotContain("Plateau")
        assertThat(t.rangeType).isEqualTo(RangeType.HIGH_REP)
        assertThat(t.weightKg).isEqualTo(55.0) // in range 12-16, hold, aim 15
        assertThat(t.repGoal).isEqualTo(15)
    }

    @Test fun coldStart() {
        val hint = WeightHint("Dumbbell bench press", 22.5)

        val t = coachTarget(input(history = emptyList(), hint = hint, movementLastTrained = null))

        assertThat(t.weightKg).isNull()
        assertThat(t.repGoal).isNull()
        assertThat(t.hint).isEqualTo(hint)
        assertThat(t.type).isEqualTo(SessionType.NORMAL)
        assertThat(t.sets).isEqualTo(3)
        assertThat(t.range).isEqualTo(RepRange(8, 12))
        assertThat(t.reason).isEqualTo(COLD_START_REASON)
    }

    @Test fun coldStartInAHighRepBlockHasNoWeightAndTheHighRepRange() {
        val t = coachTarget(input(history = emptyList(), block = block(index = 1), movementLastTrained = null))

        assertThat(rules.alternateRanges).isTrue()
        assertThat(t.weightKg).isNull()
        assertThat(t.rangeType).isEqualTo(RangeType.HIGH_REP)
        assertThat(t.range).isEqualTo(RepRange(12, 16))
        assertThat(t.reason).isEqualTo(COLD_START_REASON)
    }

    @Test fun coldStartIgnoresEverythingElseEvenAFlush() {
        val t = coachTarget(input(history = emptyList(), flush = true, stiff = true, block = block(deload = true)))

        assertThat(t.weightKg).isNull()
        assertThat(t.type).isEqualTo(SessionType.NORMAL)
        assertThat(t.sets).isEqualTo(3)
    }

    @Test fun assistedDeload() {
        val t = coachTarget(
            input(ex = assistedPullUp, history = listOf(log(-70.0, 10, ex = assistedPullUp)), block = block(deload = true)),
        )

        assertThat(t.weightKg).isEqualTo(-77.5)
        assertThat(t.type).isEqualTo(SessionType.DELOAD)
    }

    @Test fun hurtStepsDown() {
        val history = listOf(log(60.0, 12, daysAgo = 10), log(60.0, 8, daysAgo = 3, hurt = Joint.SHOULDER))

        val t = coachTarget(input(history = history))

        assertThat(t.weightKg).isEqualTo(57.5)
        assertThat(t.type).isEqualTo(SessionType.HELD)
        assertThat(t.reason).isEqualTo("This hurt last time: one step lighter.")
    }

    @Test fun aHurtClearedByTheLifterNoLongerSteps() {
        val history = listOf(log(60.0, 12, daysAgo = 10), log(60.0, 8, daysAgo = 3, hurt = Joint.SHOULDER))

        val t = coachTarget(input(history = history, hurtClearedOn = today.minusDays(2)))

        assertThat(t.type).isEqualTo(SessionType.NORMAL)
        assertThat(t.weightKg).isEqualTo(62.5)
    }

    @Test fun jointCautionHoldsAfterAnotherExerciseHurtASharedJoint() {
        val other = HurtEvent("incline-press", Joint.SHOULDER, today.minusDays(4))

        val t = coachTarget(input(otherHurts = listOf(other)))

        assertThat(t.weightKg).isEqualTo(60.0)
        assertThat(t.type).isEqualTo(SessionType.HELD)
        assertThat(t.reason).isEqualTo("Shoulder flagged recently: holding.")
    }

    @Test fun baseHighBaseUsesLatest() {
        val history = listOf(
            log(57.5, 12, daysAgo = 20, rangeType = RangeType.BASE),
            log(55.0, 16, daysAgo = 5, rangeType = RangeType.HIGH_REP),
        )

        val t = coachTarget(input(history = history, block = block(index = 2)))

        assertThat(t.rangeType).isEqualTo(RangeType.BASE)
        assertThat(t.range).isEqualTo(RepRange(8, 12))
        assertThat(t.weightKg).isEqualTo(60.0) // convertWeight(55, 16, 12, 2.5), not the stale 57.5
        assertThat(t.repGoal).isEqualTo(9)
    }

    @Test fun bodyweightDeload() {
        val t = coachTarget(
            input(ex = chinUp, slot = bwSlot, history = listOf(log(null, 8, ex = chinUp)), block = block(deload = true)),
        )

        assertThat(t.weightKg).isNull()
        assertThat(t.sets).isEqualTo(3)
        assertThat(t.type).isEqualTo(SessionType.DELOAD)
    }

    @Test fun bodyweightFlush() {
        val t = coachTarget(input(ex = chinUp, slot = bwSlot, history = listOf(log(null, 8, ex = chinUp)), flush = true))

        assertThat(t.weightKg).isNull()
        assertThat(t.sets).isEqualTo(3)
        assertThat(t.range).isEqualTo(RepRange(15, 20))
        assertThat(t.type).isEqualTo(SessionType.FLUSH)
        assertThat(t.roundSec).isEqualTo(60)
    }

    @Test fun bodyweightStiffHoldsTheRepsAtEqualSets() {
        val t = coachTarget(input(ex = chinUp, slot = bwSlot, history = listOf(log(null, 8, ex = chinUp)), stiff = true))

        assertThat(t.weightKg).isNull()
        assertThat(t.sets).isEqualTo(4)
        assertThat(t.type).isEqualTo(SessionType.HELD)
        assertThat(t.repGoal).isEqualTo(8) // no rep increase
    }

    @Test fun bodyweightLayoffAndHurtAndCautionAreHeldWithoutARepIncrease() {
        val hist = listOf(log(null, 8, daysAgo = 25, ex = chinUp))
        val layoff = coachTarget(input(ex = chinUp, slot = bwSlot, history = hist, movementLastTrained = today.minusDays(25)))
        val hurt = coachTarget(
            input(ex = chinUp, slot = bwSlot, history = hist + log(null, 5, daysAgo = 3, hurt = Joint.ELBOW, ex = chinUp)),
        )
        val caution = coachTarget(
            input(
                ex = chinUp, slot = bwSlot, history = hist,
                otherHurts = listOf(HurtEvent("curl", Joint.ELBOW, today.minusDays(2))), movementLastTrained = today.minusDays(3),
            ),
        )

        for (t in listOf(layoff, hurt, caution)) {
            assertThat(t.weightKg).isNull()
            assertThat(t.type).isEqualTo(SessionType.HELD)
            assertThat(t.repGoal).isEqualTo(8)
        }
    }

    @Test fun bodyweightPlateauHoldsTheReps() {
        val hist = (0 until 4L).map { log(null, 8, daysAgo = 12 - it * 3, ex = chinUp) }

        val t = coachTarget(input(ex = chinUp, slot = bwSlot, history = hist))

        assertThat(t.weightKg).isNull()
        assertThat(t.type).isEqualTo(SessionType.NORMAL)
        assertThat(t.repGoal).isEqualTo(8)
        assertThat(t.reason).startsWith("Plateau")
    }

    @Test fun aBodyweightPlateauResetsOnceTheHoldIsBeaten() {
        val hist = (0 until 4L).map { log(null, 8, daysAgo = 15 - it * 3, ex = chinUp) }
        val held = coachTarget(input(ex = chinUp, slot = bwSlot, history = hist))
        // the lifter logs the held session as the target says (its type), then beats the held reps
        val afterHold = hist + log(null, 8, daysAgo = 3, ex = chinUp, type = held.type)
        assertThat(coachTarget(input(ex = chinUp, slot = bwSlot, history = afterHold)).reason).startsWith("Plateau")

        val beaten = hist + log(null, 9, daysAgo = 3, ex = chinUp, type = held.type)
        val t = coachTarget(input(ex = chinUp, slot = bwSlot, history = beaten))

        assertThat(t.type).isEqualTo(SessionType.NORMAL)
        assertThat(t.repGoal).isEqualTo(10)
        assertThat(t.reason).doesNotContain("Plateau")
    }

    @Test fun bodyweightNormalStillProgressesTheReps() {
        val t = coachTarget(input(ex = chinUp, slot = bwSlot, history = listOf(log(null, 8, ex = chinUp))))

        assertThat(t.type).isEqualTo(SessionType.NORMAL)
        assertThat(t.repGoal).isEqualTo(9)
    }

    @Test fun bodyweightBelowTheRangeSetsNoRepGoal() {
        val t = coachTarget(input(ex = chinUp, slot = bwSlot, history = listOf(log(null, 4, ex = chinUp))))

        assertThat(t.type).isEqualTo(SessionType.NORMAL)
        assertThat(t.repGoal).isNull()
        assertThat(t.reason).contains("aim for 6 reps")
    }

    @Test fun targetCarriesRangeType() {
        assertThat(coachTarget(input(block = block(index = 1))).rangeType).isEqualTo(RangeType.HIGH_REP)
        assertThat(coachTarget(input(block = block(index = 1), flush = true)).rangeType).isEqualTo(RangeType.HIGH_REP)
        assertThat(coachTarget(input(block = block(index = 0))).rangeType).isEqualTo(RangeType.BASE)
    }

    @Test fun youngLiftersNeverAlternate() {
        val young = input(block = block(index = 1)).copy(rules = ageRules(30))

        assertThat(coachTarget(young).rangeType).isEqualTo(RangeType.BASE)
    }

    @Test fun aHeldLogIsNeverTheBaseline() {
        val history = listOf(log(60.0, 12, daysAgo = 10), log(40.0, 5, daysAgo = 3, type = SessionType.HELD))

        assertThat(coachTarget(input(history = history)).weightKg).isEqualTo(62.5)
    }

    @Test fun aReturnLogIsABaseline() {
        val history = listOf(log(60.0, 12, daysAgo = 30), log(47.5, 12, daysAgo = 3, type = SessionType.RETURN))

        assertThat(coachTarget(input(history = history)).weightKg).isEqualTo(50.0)
    }

    @Test fun aModifierThatTiesTheNormalCandidateWinsIt() {
        // 60 x 10 solid: progression holds 60 and aims for 11, which a stiff muscle ties at the same weight and sets
        val t = coachTarget(input(history = listOf(log(60.0, 10)), stiff = true))

        assertThat(t.weightKg).isEqualTo(60.0)
        assertThat(t.sets).isEqualTo(3)
        assertThat(t.type).isEqualTo(SessionType.HELD)
        assertThat(t.repGoal).isNull()
        assertThat(coachTarget(input(history = listOf(log(60.0, 10)))).repGoal).isEqualTo(11)
    }
}
