package com.simplegym.domain

import com.simplegym.data.GymDatabase
import com.simplegym.data.SessionLogRow
import com.simplegym.engine.BlockState
import com.simplegym.engine.DaySessionSummary
import com.simplegym.engine.SessionType
import com.simplegym.engine.ageRules
import com.simplegym.engine.blockState
import java.time.LocalDate

/** Where the lifter is in the block: builds the engine's inputs from the database (spec §6.4). */
class BlockService(private val db: GymDatabase) {

    /**
     * The block state as of [today], over the finished sessions only (an open session is not folded until it is
     * finished). Each session brings the slot total and the active days from when it started, and the round still
     * open is judged against the *current* non-archived days, so editing the plan mid-round never jams or jumps it.
     */
    suspend fun current(today: LocalDate): BlockState {
        val activeDayIds = db.planDao().getActiveDays().map { it.id }.toSet()
        val buildRounds = ageRules(db.settingsDao().get()?.age ?: DEFAULT_AGE).buildRounds
        return blockState(activeDayIds, summaries(), buildRounds, today)
    }

    private suspend fun summaries(): List<DaySessionSummary> {
        val logsBySession = db.logDao().getPlanLogsOfFinishedSessions().groupBy { it.sessionId }
        val deletedDoneSlots = db.sessionDao().getDeletedDoneSlotSessionIds().groupingBy { it }.eachCount()
        return db.sessionDao().getFinishedSessions().map { session ->
            val logs = logsBySession[session.id].orEmpty()
            DaySessionSummary(
                dayId = session.dayId,
                date = session.date,
                slotsLogged = loggedSlots(logs, deletedDoneSlots[session.id] ?: 0),
                slotsTotal = session.slotsTotal,
                flushOnly = logs.isNotEmpty() && logs.all { it.type == SessionType.FLUSH },
                activeDayIdsAtStart = session.activeDayIdsAtStart,
            )
        }
    }

    /**
     * The plan slots of one session that have a log other than a flush (a flush log never counts towards the half-slots
     * rule, spec §6.4, live or deleted slot alike). A live slot counts once however often it was logged. A deleted
     * slot's logs lost their slot id, so the slots they came from are told apart by the session's own record: the
     * session_slot rows of deleted slots marked DONE (one per slot that was logged). With none recorded, a deleted
     * slot counts once per exercise; the count never exceeds the number of such logs. A flush log of a deleted slot is
     * not counted at all: the flush finisher slots, removed when their session ends, leave exactly such logs, and a
     * finisher is optional and never part of the plan's slot total.
     */
    private fun loggedSlots(logs: List<SessionLogRow>, deletedDone: Int): Int {
        val live = logs.filter { it.type != SessionType.FLUSH }.mapNotNull { it.slotId }.toSet().size
        val orphans = logs.filter { it.slotId == null && it.type != SessionType.FLUSH }
        if (orphans.isEmpty()) return live
        val byExercise = orphans.map { it.exerciseId }.toSet().size
        return live + minOf(orphans.size, maxOf(deletedDone, byExercise))
    }

    private companion object {
        /** Used until the lifter has entered an age (the 40-54 band). */
        const val DEFAULT_AGE = 45
    }
}
