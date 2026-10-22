package com.simplegym.domain

import com.google.common.truth.Truth.assertThat
import com.simplegym.completeBuildRounds
import com.simplegym.data.CheckInEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.SlotEntity
import com.simplegym.data.SlotSwapEntity
import com.simplegym.engine.Chip
import com.simplegym.engine.RangeType
import com.simplegym.engine.RepRange
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.testContainer
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Today's check-in reaching the swap context (stiff neck, stiff lower back) and the cold start in a high-rep block. */
@RunWith(RobolectricTestRunner::class)
class TargetServiceStiffTest {
    private val today = LocalDate.of(2026, 10, 22)

    private lateinit var db: GymDatabase
    private lateinit var service: TargetService
    private var dayId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        testContainer(db).seed()
        service = TargetService(db)
        dayId = db.planDao().getActiveDays().first().id
    }

    @After
    fun tearDown() = db.close()

    private suspend fun stiff(vararg chips: Chip, date: LocalDate = today) =
        db.sessionDao().upsertCheckIn(CheckInEntity(date, chips.toSet()))

    private suspend fun slotWith(default: String, vararg swaps: String): Long {
        val slotId = db.planDao().insertSlot(
            SlotEntity(dayId = dayId, position = 50, defaultExerciseId = default, sets = 3, lo = 8, hi = 12),
        )
        db.planDao().insertSwaps(swaps.mapIndexed { i, id -> SlotSwapEntity(slotId, id, i) })
        return slotId
    }

    private suspend fun session() = SessionService(db) { 0L }.start(dayId, today, clubId = null)

    @Test
    fun aStiffLowerBackMovesAHighBackLoadDefaultToTheLightestSwapInBothWorkoutAndExercise() = runBlocking<Unit> {
        val slot = slotWith("barbell-bent-over-row", "row-machine", "dumbbell-row")
        val sessionId = session()
        assertThat(service.resolveVariant(sessionId, slot, today)).isEqualTo("barbell-bent-over-row")

        stiff(Chip.LOWER_BACK)

        val plan = service.swapPlan(sessionId, slot, today)
        assertThat(plan.ctx.stiffLowerBack).isTrue()
        assertThat(plan.ctx.stiffNeck).isFalse()
        assertThat(plan.suggested.id).isEqualTo("row-machine")
        assertThat(service.resolveVariant(sessionId, slot, today)).isEqualTo("row-machine")
    }

    @Test
    fun aStiffNeckPutsShrugAndUprightRowSwapsAfterTheOthers() = runBlocking<Unit> {
        val slot = slotWith("bench-press", "dumbbell-shrug", "upright-row-dumbbell", "dumbbell-row")
        val sessionId = session()
        assertThat(service.swapPlan(sessionId, slot, today).ordered.map { it.id })
            .containsExactly("dumbbell-shrug", "upright-row-dumbbell", "dumbbell-row").inOrder()

        stiff(Chip.NECK)

        val plan = service.swapPlan(sessionId, slot, today)
        assertThat(plan.ctx.stiffNeck).isTrue()
        assertThat(plan.ctx.stiffLowerBack).isFalse()
        assertThat(plan.ordered.map { it.id }).containsExactly("dumbbell-row", "dumbbell-shrug", "upright-row-dumbbell").inOrder()
    }

    @Test
    fun aMuscleChipOrAnotherDaysCheckInChangesNothingInTheSwapContext() = runBlocking<Unit> {
        val slot = slotWith("barbell-bent-over-row", "row-machine")
        val sessionId = session()
        stiff(Chip.BACK, Chip.LEGS)
        stiff(Chip.LOWER_BACK, Chip.NECK, date = today.minusDays(1))

        val plan = service.swapPlan(sessionId, slot, today)

        assertThat(plan.ctx.stiffLowerBack).isFalse()
        assertThat(plan.ctx.stiffNeck).isFalse()
        assertThat(plan.suggested.id).isEqualTo("barbell-bent-over-row")
    }

    @Test
    fun aColdStartInAHighRepBlockHasNoWeightAndTheHighRepRange() = runBlocking<Unit> {
        testContainer(db).completeBuildRounds(today.minusDays(60)) // block 1 has started; it logged the first 3 slots only
        val lastSlot = db.planDao().getSlots(dayId).last() // Triceps pushdown (cable), 10-12, never logged

        val target = service.targetFor(lastSlot.id, lastSlot.defaultExerciseId, today)

        assertThat(target.weightKg).isNull()
        assertThat(target.rangeType).isEqualTo(RangeType.HIGH_REP)
        assertThat(target.range).isEqualTo(RepRange(14, 16))
        assertThat(target.type).isEqualTo(SessionType.NORMAL)
    }
}
