package com.simplegym.domain

import androidx.room3.withWriteTransaction
import com.simplegym.data.GymDatabase
import com.simplegym.data.SlotSwapEntity

/** Answers the "Keep <variant> as default?" chip shown on the first session of the block after a rotation. */
class RotationService(private val db: GymDatabase) {

    /** Either answer clears the slot's rotation. */
    suspend fun clear(slotId: Long) = db.planDao().setRotation(slotId, null, null)

    /**
     * Makes the rotated variant the slot's plan default and clears the rotation. The old default takes the rotated
     * variant's place among the swaps, so it stays one tap away and no variant is listed twice.
     *
     * [exerciseId] is the variant the lifter was asked about. If the slot has since been rotated to something else the
     * answer is stale and nothing changes, so a variant the lifter never chose to keep can never become the default.
     */
    suspend fun keepAsDefault(slotId: Long, exerciseId: String) = db.withWriteTransaction {
        val dao = db.planDao()
        val slot = checkNotNull(dao.getSlot(slotId)) { "No slot $slotId" }
        val rotated = slot.rotationExerciseId
        if (rotated != exerciseId) return@withWriteTransaction
        if (rotated != slot.defaultExerciseId) {
            val swaps = dao.getSwaps(slotId)
            val position = swaps.firstOrNull { it.exerciseId == rotated }?.position ?: ((swaps.maxOfOrNull { it.position } ?: -1) + 1)
            dao.deleteSwaps(slotId)
            dao.insertSwaps(
                swaps.filter { it.exerciseId != rotated } + SlotSwapEntity(slotId, slot.defaultExerciseId, position),
            )
            dao.updateSlot(slot.copy(defaultExerciseId = rotated))
        }
        dao.setRotation(slotId, null, null)
    }
}
