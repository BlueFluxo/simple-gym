package com.simplegym.domain

import androidx.room3.withWriteTransaction
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SlotStatus
import com.simplegym.engine.SessionType
import com.simplegym.engine.Target
import java.time.LocalDate

/** Saves a set of work from the Log screen. */
class LogService(private val db: GymDatabase, private val sessions: SessionService) {

    /**
     * Writes the log for [slotId] and sets that slot DONE, in one transaction so a killed app never leaves a done slot
     * without its log or the reverse. The log's type is the [target]'s, or FLUSH when the lifter turned the slot's flush
     * on. Returns the new log's id. Throws [IllegalStateException], writing nothing, when the session or the slot is gone.
     */
    suspend fun save(
        sessionId: Long,
        slotId: Long,
        exerciseId: String,
        ok: LogValidation.Ok,
        input: LogInput,
        target: Target,
        today: LocalDate,
    ): Long = db.withWriteTransaction {
        // Checked inside the transaction, so a slot or session deleted since the screen opened is a clean failure.
        checkNotNull(db.sessionDao().getSession(sessionId)) { "No session $sessionId" }
        checkNotNull(db.planDao().getSlot(slotId)) { "No slot $slotId" }
        val flush = db.sessionDao().getSessionSlots(sessionId).firstOrNull { it.slotId == slotId }?.flush == true
        sessions.setStatus(sessionId, slotId, SlotStatus.DONE)
        db.logDao().insert(
            LogEntity(
                sessionId = sessionId,
                slotId = slotId,
                exerciseId = exerciseId,
                date = today,
                weightKg = ok.weightKg,
                bestReps = ok.reps,
                sets = ok.sets,
                feel = input.feel,
                type = if (flush) SessionType.FLUSH else target.type,
                rangeType = target.rangeType,
                hurt = input.hurt,
                note = input.note?.trim()?.ifBlank { null },
                imported = false,
                loggedSlotId = slotId,
            ),
        )
    }
}
