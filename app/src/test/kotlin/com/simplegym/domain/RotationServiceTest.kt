package com.simplegym.domain

import com.google.common.truth.Truth.assertThat
import com.simplegym.data.GymDatabase
import com.simplegym.inMemoryDb
import com.simplegym.testContainer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RotationServiceTest {
    private lateinit var db: GymDatabase
    private lateinit var service: RotationService
    private var benchSlotId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        testContainer(db).seed()
        service = RotationService(db)
        benchSlotId = db.planDao().getSlots(db.planDao().getActiveDays().first().id).first().id
        db.planDao().setRotation(benchSlotId, "dumbbell-bench-press", 0)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun setRotationStoresAndClearsTheBlockScopedOverride() = runBlocking {
        val slot = checkNotNull(db.planDao().getSlot(benchSlotId))
        assertThat(slot.rotationExerciseId).isEqualTo("dumbbell-bench-press")
        assertThat(slot.rotationBlockIndex).isEqualTo(0)

        db.planDao().setRotation(benchSlotId, null, null)
        val cleared = checkNotNull(db.planDao().getSlot(benchSlotId))
        assertThat(cleared.rotationExerciseId).isNull()
        assertThat(cleared.rotationBlockIndex).isNull()
    }

    @Test
    fun clearDropsTheRotationAndKeepsTheDefault() = runBlocking {
        service.clear(benchSlotId)

        val slot = checkNotNull(db.planDao().getSlot(benchSlotId))
        assertThat(slot.rotationExerciseId).isNull()
        assertThat(slot.rotationBlockIndex).isNull()
        assertThat(slot.defaultExerciseId).isEqualTo("bench-press")
    }

    @Test
    fun keepAsDefaultSwapsTheTwoAndClearsTheRotation() = runBlocking {
        val before = db.planDao().getSwaps(benchSlotId).map { it.exerciseId }
        assertThat(before).contains("dumbbell-bench-press")

        service.keepAsDefault(benchSlotId, "dumbbell-bench-press")

        val slot = checkNotNull(db.planDao().getSlot(benchSlotId))
        assertThat(slot.defaultExerciseId).isEqualTo("dumbbell-bench-press")
        assertThat(slot.rotationExerciseId).isNull()
        assertThat(slot.rotationBlockIndex).isNull()
        // the old default sits where the rotated variant was: same chips, same order, no duplicates
        val after = db.planDao().getSwaps(benchSlotId).map { it.exerciseId }
        assertThat(after).isEqualTo(before.map { if (it == "dumbbell-bench-press") "bench-press" else it })
    }

    @Test
    fun keepAsDefaultWithNoRotationChangesNothing() = runBlocking {
        service.clear(benchSlotId)
        val swaps = db.planDao().getSwaps(benchSlotId)

        service.keepAsDefault(benchSlotId, "dumbbell-bench-press")

        assertThat(checkNotNull(db.planDao().getSlot(benchSlotId)).defaultExerciseId).isEqualTo("bench-press")
        assertThat(db.planDao().getSwaps(benchSlotId)).isEqualTo(swaps)
    }

    @Test
    fun keepAsDefaultForAVariantTheSlotIsNoLongerRotatedToChangesNothing() = runBlocking {
        // the slot was re-rotated after the keep offer for dumbbell-bench-press was shown
        db.planDao().setRotation(benchSlotId, "incline-barbell-bench-press", 1)
        val swaps = db.planDao().getSwaps(benchSlotId)

        service.keepAsDefault(benchSlotId, "dumbbell-bench-press")

        val slot = checkNotNull(db.planDao().getSlot(benchSlotId))
        assertThat(slot.defaultExerciseId).isEqualTo("bench-press")
        assertThat(slot.rotationExerciseId).isEqualTo("incline-barbell-bench-press")
        assertThat(slot.rotationBlockIndex).isEqualTo(1)
        assertThat(db.planDao().getSwaps(benchSlotId)).isEqualTo(swaps)
    }
}
