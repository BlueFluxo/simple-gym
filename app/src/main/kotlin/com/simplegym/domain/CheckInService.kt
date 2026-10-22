package com.simplegym.domain

import androidx.room3.withWriteTransaction
import com.simplegym.data.CheckInEntity
import com.simplegym.data.GymDatabase
import com.simplegym.engine.CheckIn
import com.simplegym.engine.Chip
import com.simplegym.engine.Trained
import com.simplegym.engine.typicalStiffDays as engineTypicalStiffDays
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The stiffness check-in (spec §5.1, §6.6): which chips the lifter tapped stiff today, and what that history says. */
class CheckInService(private val db: GymDatabase) {

    /**
     * Stores [chips] as the check-in of [today]. An empty set is kept as a check-in of its own: it says nothing was
     * stiff, which is what ends a recovery sample (see [engineTypicalStiffDays]).
     */
    suspend fun setStiff(today: LocalDate, chips: Set<Chip>) {
        db.sessionDao().upsertCheckIn(CheckInEntity(today, chips))
    }

    /** Taps [chip] on or off in one transaction, so two quick taps never overwrite each other. */
    suspend fun toggle(today: LocalDate, chip: Chip) = db.withWriteTransaction {
        val current = db.sessionDao().getCheckIn(today)?.stiff.orEmpty()
        setStiff(today, if (chip in current) current - chip else current + chip)
    }

    /** The chips tapped stiff on [today]; empty when there is no check-in. */
    fun today(today: LocalDate): Flow<Set<Chip>> = db.sessionDao().observeCheckIn(today).map { it?.stiff.orEmpty() }

    /**
     * How many days after training a chip usually stays stiff ([typicalStiffDays]). A session trained its day's chips
     * when it finished with at least one log that is not a flush; flush-only sessions train nothing, and a day
     * deleted since is no longer known.
     */
    suspend fun typicalStiffDays(): Map<Chip, Int> {
        val trainedIds = db.logDao().getTrainedSessionIds().toSet()
        val trained = db.sessionDao().getFinishedSessions().filter { it.id in trainedIds }.mapNotNull { session ->
            db.planDao().getDay(session.dayId)?.let { Trained(session.date, it.chips) }
        }
        val checkIns = db.sessionDao().getCheckIns().map { CheckIn(it.date, it.stiff) }
        return engineTypicalStiffDays(checkIns, trained)
    }
}
