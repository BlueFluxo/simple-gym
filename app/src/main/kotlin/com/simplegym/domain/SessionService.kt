package com.simplegym.domain

import androidx.room3.withWriteTransaction
import com.simplegym.data.CheckInEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.SessionEntity
import com.simplegym.data.SessionSlotEntity
import com.simplegym.data.SlotEntity
import com.simplegym.data.SlotStatus
import com.simplegym.engine.BackLoad
import com.simplegym.engine.Chip
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Lifecycle of a workout session: start, resume window, per-slot state and finish. */
class SessionService(private val db: GymDatabase, private val clock: () -> Long) {

    /**
     * Opens a session for [dayId] with every plan slot PENDING; session-only slots are not counted.
     * The non-archived day ids are stored with it, so its round keeps the days it needed if the plan changes later.
     * A session that is still open is finished first (as [finish] would), so only one is ever open.
     */
    suspend fun start(dayId: Long, today: LocalDate, clubId: Long?): Long = db.withWriteTransaction {
        db.sessionDao().getOpenSessions().forEach { finish(it.id) }
        val slots = db.planDao().getSlots(dayId)
        val sessionId = db.sessionDao().insertSession(
            SessionEntity(
                dayId = dayId,
                date = today,
                startedAtMillis = clock(),
                clubId = clubId,
                slotsTotal = slots.size,
                activeDayIdsAtStart = db.planDao().getActiveDays().map { it.id }.toSet(),
            ),
        )
        db.sessionDao().upsertSessionSlots(slots.map { SessionSlotEntity(sessionId, it.id) })
        // Today's check-in row, empty if no chip was tapped: a loose day must be a row of its own, not a missing one,
        // or the recovery estimate (typicalStiffDays) cannot tell it from a day the app was not opened.
        if (db.sessionDao().getCheckIn(today) == null) db.sessionDao().upsertCheckIn(CheckInEntity(today, emptySet()))
        sessionId
    }

    /**
     * Adds the optional flush finisher for [chip] to the open session [sessionId] (spec §6.7): a session-only slot of
     * 2 sets of 15-20 on 1:00 rounds plus a session_slot row with Flush instead on, so its target comes out as a flush
     * through the usual path. The exercise is the chip's most logged one, or the first in the library with that muscle;
     * archived exercises are never picked, nor high back-load ones while lower-back protection is on. The slot hangs
     * off the session's day yet is excluded from the plan, from [SessionEntity.slotsTotal] and from round counting.
     * Returns the new slot's id, the existing finisher's for a chip that already has one, or null when the session is
     * finished or gone, the chip has no muscle (lower back, neck) or no exercise fits.
     */
    suspend fun addFlushFinisher(sessionId: Long, chip: Chip): Long? = db.withWriteTransaction {
        val session = db.sessionDao().getSession(sessionId)?.takeIf { it.finishedAtMillis == null }
            ?: return@withWriteTransaction null
        if (chip.muscles.isEmpty() || db.planDao().getDay(session.dayId) == null) return@withWriteTransaction null
        val library = db.libraryDao().getAll()
        val existing = db.planDao().getSessionOnlySlots(sessionId)
        val covered = existing.firstOrNull { slot ->
            library.firstOrNull { it.id == slot.defaultExerciseId }?.let { it.muscle in chip.muscles } == true
        }
        if (covered != null) return@withWriteTransaction covered.id
        val protectBack = db.settingsDao().get()?.lowerBackProtection == true
        val candidates = library.filter {
            it.muscle in chip.muscles && !it.archived && !(protectBack && it.backLoad == BackLoad.HIGH)
        }
        val uses = db.logDao().useCounts().associate { it.exerciseId to it.uses }
        // maxByOrNull keeps the first of equal maximums, so with no history it is the library's first
        val exercise = candidates.maxByOrNull { uses[it.id] ?: 0 } ?: return@withWriteTransaction null
        val position = (db.planDao().getSlots(session.dayId).maxOfOrNull { it.position } ?: -1) + 1 + existing.size
        val slotId = db.planDao().insertSlot(
            SlotEntity(
                dayId = session.dayId, position = position, defaultExerciseId = exercise.id, sets = FINISHER_SETS,
                lo = FINISHER_LO, hi = FINISHER_HI, roundSec = FINISHER_ROUND_SEC, sessionOnlyFor = sessionId,
            ),
        )
        db.sessionDao().upsertSessionSlot(SessionSlotEntity(sessionId, slotId, flush = true))
        slotId
    }

    /** The newest unfinished session that started less than [STALE_AFTER_MILLIS] ago. */
    suspend fun activeSession(nowMillis: Long): SessionEntity? =
        db.sessionDao().getOpenSessions().firstOrNull { !isStale(it, nowMillis) }

    /**
     * Finishes every unfinished session that is [STALE_AFTER_MILLIS] old or more, at the moment it went stale. Their
     * flush finisher slots are cleaned up as in [finish].
     */
    suspend fun closeStale(nowMillis: Long) = db.withWriteTransaction {
        db.sessionDao().getOpenSessions().filter { isStale(it, nowMillis) }.forEach {
            db.sessionDao().finish(it.id, it.startedAtMillis + STALE_AFTER_MILLIS)
            removeFinishers(it.id)
        }
    }

    suspend fun setStatus(sessionId: Long, slotId: Long, status: SlotStatus) =
        db.sessionDao().setStatus(sessionId, slotId, status)

    suspend fun setFlush(sessionId: Long, slotId: Long, flush: Boolean) =
        db.sessionDao().setFlush(sessionId, slotId, flush)

    /**
     * Ends the session: slots still PENDING become SKIPPED (no logs are written), and its flush finisher slots are
     * removed (see [removeFinishers]). A finished session is left alone.
     */
    suspend fun finish(sessionId: Long) = db.withWriteTransaction {
        val dao = db.sessionDao()
        if (dao.getSession(sessionId)?.finishedAtMillis != null) return@withWriteTransaction
        dao.getSessionSlots(sessionId).filter { it.status == SlotStatus.PENDING }
            .forEach { dao.setStatus(sessionId, it.slotId, SlotStatus.SKIPPED) }
        dao.finish(sessionId, clock())
        removeFinishers(sessionId)
    }

    /**
     * A flush finisher exists for its session only: once that is over the slot and its session_slot row go. The slot's
     * logs stay, their slotId becoming null through the foreign key. Before that they are all marked FLUSH, even those
     * logged after the lifter switched Flush off on the finisher: once the slot is gone the type is all that tells them
     * from the logs of a deleted plan slot, and [BlockService] must not count them as such. A finisher is never
     * training, so it is no baseline or recovery sample either.
     */
    private suspend fun removeFinishers(sessionId: Long) {
        db.planDao().getSessionOnlySlots(sessionId).forEach {
            db.logDao().markSlotLogsAsFlush(it.id)
            db.sessionDao().deleteSessionSlot(sessionId, it.id)
            db.planDao().deleteSlot(it.id)
        }
    }

    fun slotStates(sessionId: Long): Flow<List<SessionSlotEntity>> = db.sessionDao().observeSessionSlots(sessionId)

    companion object {
        const val STALE_AFTER_MILLIS = 12L * 60 * 60 * 1000
        private const val FINISHER_SETS = 2
        private const val FINISHER_LO = 15
        private const val FINISHER_HI = 20
        private const val FINISHER_ROUND_SEC = 60

        fun isStale(session: SessionEntity, nowMillis: Long): Boolean =
            nowMillis - session.startedAtMillis >= STALE_AFTER_MILLIS
    }
}
