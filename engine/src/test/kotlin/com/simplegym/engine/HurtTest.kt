package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class HurtTest {
    private val day0 = LocalDate.of(2026, 10, 1)

    private fun ex(name: String, vararg joints: Joint) = Exercise(
        id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
        name = name,
        muscle = Muscle.CHEST,
        equipment = "Machine",
        swapGroup = "g",
        incrementKg = 2.5,
        backLoad = BackLoad.LOW,
        joints = joints.toSet(),
        isAssisted = false,
        isBodyweight = false,
    )

    private fun log(offset: Long, hurt: Joint? = null, id: String = "bench-press") = LogRecord(
        exerciseId = id,
        date = day0.plusDays(offset),
        weightKg = 60.0,
        bestReps = 8,
        sets = 3,
        feel = Feel.SOLID,
        type = SessionType.NORMAL,
        hurt = hurt,
    )

    private fun hurt(offset: Long) = log(offset, Joint.SHOULDER)
    private fun ok(offset: Long) = log(offset)

    // activeHurts

    @Test
    fun oneHurtFollowedByTwoPainFreeLogsStaysActive() {
        assertThat(activeHurts(listOf(hurt(0), ok(1), ok(2)), null)).containsExactly(Joint.SHOULDER, 1)
    }

    @Test
    fun threePainFreeLogsClearEveryCount() {
        assertThat(activeHurts(listOf(hurt(0), ok(1), ok(2)), null)).isNotEmpty() // two are not enough
        assertThat(activeHurts(listOf(hurt(0), ok(1), ok(2), ok(3)), null)).isEmpty()
    }

    @Test
    fun twoHurtsCountTwice() {
        assertThat(activeHurts(listOf(hurt(0), hurt(1)), null)).containsExactly(Joint.SHOULDER, 2)
    }

    @Test
    fun aHurtResetsThePainFreeStreak() {
        // ok, ok, hurt, ok, ok never reaches three in a row, so the first hurt is still counted
        assertThat(activeHurts(listOf(hurt(0), ok(1), ok(2), hurt(3), ok(4), ok(5)), null))
            .containsExactly(Joint.SHOULDER, 2)
    }

    @Test
    fun countsAreKeptPerJoint() {
        val history = listOf(hurt(0), log(1, Joint.ELBOW), hurt(2))

        assertThat(activeHurts(history, null)).containsExactly(Joint.SHOULDER, 2, Joint.ELBOW, 1)
    }

    @Test
    fun aClearedOnDateDropsEverythingOnOrBeforeIt() {
        val history = listOf(hurt(0), hurt(1))

        assertThat(activeHurts(history, day0.minusDays(1))).containsExactly(Joint.SHOULDER, 2) // cleared before both
        assertThat(activeHurts(history, day0.plusDays(2))).isEmpty() // cleared after both
        assertThat(activeHurts(history, day0.plusDays(1))).isEmpty() // a hurt on the clear day itself is cleared too
        assertThat(activeHurts(history, day0)).containsExactly(Joint.SHOULDER, 1) // only the later one remains
    }

    // jointCaution

    private val benchHurt = HurtEvent("bench-press", Joint.SHOULDER, day0)
    private val machinePress = ex("Machine shoulder press", Joint.SHOULDER, Joint.ELBOW)

    @Test
    fun cautionHoldsThroughDay13AndEndsOnDay14() {
        assertThat(jointCaution(machinePress, listOf(benchHurt), emptyList(), day0.plusDays(13))).isEqualTo(Joint.SHOULDER)
        assertThat(jointCaution(machinePress, listOf(benchHurt), emptyList(), day0.plusDays(14))).isNull()
    }

    @Test
    fun twoPainFreeOwnLogsAfterTheEventLiftTheCaution() {
        val today = day0.plusDays(5)
        val one = listOf(log(1, id = machinePress.id))
        val two = one + log(2, id = machinePress.id)

        assertThat(jointCaution(machinePress, listOf(benchHurt), one, today)).isEqualTo(Joint.SHOULDER)
        assertThat(jointCaution(machinePress, listOf(benchHurt), two, today)).isNull()
    }

    @Test
    fun ownLogsOnOrBeforeTheEventOrWithAHurtDoNotLiftTheCaution() {
        val today = day0.plusDays(5)
        val before = listOf(log(-2, id = machinePress.id), log(0, id = machinePress.id))
        val painful = listOf(log(1, Joint.ELBOW, id = machinePress.id), log(2, Joint.ELBOW, id = machinePress.id))

        assertThat(jointCaution(machinePress, listOf(benchHurt), before, today)).isEqualTo(Joint.SHOULDER)
        assertThat(jointCaution(machinePress, listOf(benchHurt), painful, today)).isEqualTo(Joint.SHOULDER)
    }

    @Test
    fun onlyAJointTheExerciseStressesTriggersACaution() {
        val kneeHurt = HurtEvent("leg-press", Joint.KNEE, day0)

        assertThat(jointCaution(machinePress, listOf(kneeHurt), emptyList(), day0.plusDays(1))).isNull()
        assertThat(jointCaution(machinePress, listOf(kneeHurt, benchHurt), emptyList(), day0.plusDays(1)))
            .isEqualTo(Joint.SHOULDER)
    }

    @Test
    fun theExercisesOwnHurtIsNotACaution() {
        val own = HurtEvent(machinePress.id, Joint.SHOULDER, day0)

        assertThat(jointCaution(machinePress, listOf(own), emptyList(), day0.plusDays(1))).isNull()
    }

    @Test
    fun aLiftedOldEventDoesNotHideALaterOne() {
        val later = HurtEvent("bench-press", Joint.ELBOW, day0.plusDays(4))
        val ownLogs = listOf(log(1, id = machinePress.id), log(2, id = machinePress.id)) // lift the day-0 shoulder event only

        assertThat(jointCaution(machinePress, listOf(benchHurt, later), ownLogs, day0.plusDays(5))).isEqualTo(Joint.ELBOW)
    }

    // jointFriendlySwap

    private val a = ex("A", Joint.SHOULDER, Joint.ELBOW)
    private val b = ex("B", Joint.SHOULDER, Joint.WRIST)
    private val c = ex("C", Joint.ELBOW)
    private val d = ex("D", Joint.ELBOW, Joint.WRIST)

    @Test
    fun theFirstSwapNotStressingTheJointWins() {
        assertThat(jointFriendlySwap(Joint.SHOULDER, listOf(a, b, c, d), listOf(d))).isEqualTo(c)
        assertThat(jointFriendlySwap(Joint.WRIST, listOf(b, a, c), listOf(d))).isEqualTo(a)
    }

    @Test
    fun withNoFriendlySwapTheGroupMemberWithTheFewestJointsWins() {
        assertThat(jointFriendlySwap(Joint.SHOULDER, listOf(a, b), listOf(a, b, c, d))).isEqualTo(c)
        assertThat(jointFriendlySwap(Joint.SHOULDER, listOf(a, b), listOf(a, b, d, c))).isEqualTo(c) // fewest, not first
    }

    @Test
    fun tiesInTheGroupKeepTheListedOrder() {
        val first = ex("First", Joint.ELBOW)
        val second = ex("Second", Joint.WRIST)

        assertThat(jointFriendlySwap(Joint.SHOULDER, emptyList(), listOf(second, first))).isEqualTo(second)
    }

    @Test
    fun noSwapAndNoGroupMemberAvoidingTheJointMeansNull() {
        assertThat(jointFriendlySwap(Joint.SHOULDER, listOf(a, b), listOf(a, b))).isNull()
        assertThat(jointFriendlySwap(Joint.SHOULDER, listOf(a, b), listOf(a, b, c))).isEqualTo(c) // the same setup with a friendly one
    }
}
