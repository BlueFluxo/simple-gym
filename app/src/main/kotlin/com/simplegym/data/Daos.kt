package com.simplegym.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import com.simplegym.engine.SessionType
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(exercises: List<ExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exercise: ExerciseEntity)

    @Query("SELECT COUNT(*) FROM exercise")
    suspend fun count(): Int

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun get(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercise ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<ExerciseEntity>

    /** Archived exercises stay out of chips and search. */
    @Query("SELECT * FROM exercise WHERE archived = 0 ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<ExerciseEntity>>

    /** Active exercises of [groups] in the order the library lists them (the order they were inserted). */
    @Query("SELECT * FROM exercise WHERE archived = 0 AND swapGroup IN (:groups) ORDER BY rowid")
    suspend fun getActiveInGroups(groups: List<String>): List<ExerciseEntity>

    /** The exercises hidden from chips and search, for the Archived section of Plans. */
    @Query("SELECT * FROM exercise WHERE archived = 1 ORDER BY name COLLATE NOCASE")
    fun observeArchived(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ExerciseEntity>>

    /**
     * Every exercise with at least one log, archived ones included (their history and chart stay), the one logged
     * most recently first.
     */
    @Query(
        """
        SELECT e.* FROM exercise e
        JOIN (SELECT exerciseId, MAX(date) AS lastDate, MAX(id) AS lastId FROM log GROUP BY exerciseId) h
          ON h.exerciseId = e.id
        ORDER BY h.lastDate DESC, h.lastId DESC
        """,
    )
    fun observeWithHistory(): Flow<List<ExerciseEntity>>

    @Query("DELETE FROM exercise WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE exercise SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("UPDATE exercise SET incrementOverrideKg = :kg WHERE id = :id")
    suspend fun setIncrementOverride(id: String, kg: Double?)

    @Query("UPDATE exercise SET hurtClearedOn = :date WHERE id = :id")
    suspend fun setHurtClearedOn(id: String, date: LocalDate?)
}

/** "Clear hurt flag": logs dated after [on] are all that count towards the exercise's hurt badge again. */
suspend fun LibraryDao.clearHurt(exerciseId: String, on: LocalDate) = setHurtClearedOn(exerciseId, on)

/** Every query that lists or counts a day's slots excludes session-only (flush finisher) slots. */
@Dao
interface PlanDao {
    @Insert
    suspend fun insertDay(day: DayEntity): Long

    @Update
    suspend fun updateDay(day: DayEntity)

    @Query("DELETE FROM day WHERE id = :id")
    suspend fun deleteDay(id: Long)

    @Query("SELECT * FROM day WHERE archived = 0 ORDER BY position")
    fun observeActiveDays(): Flow<List<DayEntity>>

    @Query("SELECT * FROM day WHERE archived = 1 ORDER BY position")
    fun observeArchivedDays(): Flow<List<DayEntity>>

    @Query("SELECT * FROM day WHERE archived = 0 ORDER BY position")
    suspend fun getActiveDays(): List<DayEntity>

    @Query("SELECT * FROM day WHERE id = :id")
    suspend fun getDay(id: Long): DayEntity?

    @Query("SELECT * FROM day WHERE id = :id")
    fun observeDay(id: Long): Flow<DayEntity?>

    @Query("SELECT COUNT(*) FROM day")
    suspend fun countDays(): Int

    /** Every day, archived ones included, so a new day always lands after all of them. */
    @Query("SELECT MAX(position) FROM day")
    suspend fun maxDayPosition(): Int?

    @Insert
    suspend fun insertSlot(slot: SlotEntity): Long

    @Update
    suspend fun updateSlot(slot: SlotEntity)

    @Query("DELETE FROM slot WHERE id = :id")
    suspend fun deleteSlot(id: Long)

    @Query("SELECT * FROM slot WHERE id = :id")
    suspend fun getSlot(id: Long): SlotEntity?

    @Query("SELECT * FROM slot WHERE dayId = :dayId AND sessionOnlyFor IS NULL ORDER BY position")
    fun observeSlots(dayId: Long): Flow<List<SlotEntity>>

    @Query("SELECT * FROM slot WHERE dayId = :dayId AND sessionOnlyFor IS NULL ORDER BY position")
    suspend fun getSlots(dayId: Long): List<SlotEntity>

    @Query("SELECT COUNT(*) FROM slot WHERE dayId = :dayId AND sessionOnlyFor IS NULL")
    suspend fun countSlotsForDay(dayId: Long): Int

    @Query("SELECT COUNT(*) FROM slot WHERE sessionOnlyFor IS NULL")
    suspend fun countSlots(): Int

    /** Slots (of any day, session-only ones too) that name [exerciseId] as their default or their block rotation. */
    @Query("SELECT COUNT(*) FROM slot WHERE defaultExerciseId = :exerciseId OR rotationExerciseId = :exerciseId")
    suspend fun countSlotsUsing(exerciseId: String): Int

    @Query("SELECT COUNT(*) FROM slot_swap WHERE exerciseId = :exerciseId")
    suspend fun countSwapsUsing(exerciseId: String): Int

    @Query("SELECT * FROM slot WHERE sessionOnlyFor = :sessionId ORDER BY position")
    suspend fun getSessionOnlySlots(sessionId: Long): List<SlotEntity>

    @Query("SELECT * FROM slot WHERE sessionOnlyFor = :sessionId ORDER BY position")
    fun observeSessionOnlySlots(sessionId: Long): Flow<List<SlotEntity>>

    @Insert
    suspend fun insertSwaps(swaps: List<SlotSwapEntity>)

    @Query("DELETE FROM slot_swap WHERE slotId = :slotId")
    suspend fun deleteSwaps(slotId: Long)

    @Query("SELECT * FROM slot_swap WHERE slotId = :slotId ORDER BY position")
    suspend fun getSwaps(slotId: Long): List<SlotSwapEntity>

    @Query("SELECT COUNT(*) FROM slot_swap")
    suspend fun countSwaps(): Int

    /** Sets the slot's block-scoped default override; both null clears it. One statement, so it never half-applies. */
    @Query("UPDATE slot SET rotationExerciseId = :exerciseId, rotationBlockIndex = :blockIndex WHERE id = :slotId")
    suspend fun setRotation(slotId: Long, exerciseId: String?, blockIndex: Int?)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun getSession(id: Long): SessionEntity?

    @Query("SELECT COUNT(*) FROM session WHERE dayId = :dayId")
    suspend fun countForDay(dayId: Long): Int

    @Query("SELECT * FROM session WHERE finishedAtMillis IS NULL ORDER BY startedAtMillis DESC")
    suspend fun getOpenSessions(): List<SessionEntity>

    @Query("SELECT * FROM session WHERE finishedAtMillis IS NULL ORDER BY startedAtMillis DESC LIMIT 1")
    fun observeOpenSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM session WHERE finishedAtMillis IS NOT NULL ORDER BY date, id")
    suspend fun getFinishedSessions(): List<SessionEntity>

    @Query("SELECT * FROM session WHERE finishedAtMillis IS NOT NULL ORDER BY startedAtMillis DESC")
    fun observeFinishedSessions(): Flow<List<SessionEntity>>

    @Query("UPDATE session SET finishedAtMillis = :finishedAtMillis WHERE id = :id")
    suspend fun finish(id: Long, finishedAtMillis: Long)

    /** The club of every session still open (there is at most one), as the lifter switches club mid-session. */
    @Query("UPDATE session SET clubId = :clubId WHERE finishedAtMillis IS NULL")
    suspend fun setClubOfOpenSessions(clubId: Long?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessionSlot(sessionSlot: SessionSlotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessionSlots(sessionSlots: List<SessionSlotEntity>)

    @Query("SELECT * FROM session_slot WHERE sessionId = :sessionId")
    fun observeSessionSlots(sessionId: Long): Flow<List<SessionSlotEntity>>

    @Query("SELECT * FROM session_slot WHERE sessionId = :sessionId")
    suspend fun getSessionSlots(sessionId: Long): List<SessionSlotEntity>

    /**
     * One entry (the session id) for every DONE session slot of a finished session whose slot has since been deleted:
     * session_slot keeps the id of a slot that is gone, which its logs do not.
     */
    @Query(
        """
        SELECT ss.sessionId FROM session_slot ss
        WHERE ss.status = 'DONE'
          AND NOT EXISTS (SELECT 1 FROM slot s WHERE s.id = ss.slotId)
          AND ss.sessionId IN (SELECT id FROM session WHERE finishedAtMillis IS NOT NULL)
        """,
    )
    suspend fun getDeletedDoneSlotSessionIds(): List<Long>

    @Query("DELETE FROM session_slot WHERE sessionId = :sessionId AND slotId = :slotId")
    suspend fun deleteSessionSlot(sessionId: Long, slotId: Long)

    @Query("UPDATE session_slot SET status = :status WHERE sessionId = :sessionId AND slotId = :slotId")
    suspend fun setStatus(sessionId: Long, slotId: Long, status: SlotStatus)

    @Query("UPDATE session_slot SET flush = :flush WHERE sessionId = :sessionId AND slotId = :slotId")
    suspend fun setFlush(sessionId: Long, slotId: Long, flush: Boolean)

    /** Whether [slotId] has Flush instead switched on in a session that is still open and was started on [date]. */
    @Query(
        """
        SELECT COUNT(*) FROM session_slot ss JOIN session s ON s.id = ss.sessionId
        WHERE ss.slotId = :slotId AND ss.flush = 1 AND s.finishedAtMillis IS NULL AND s.date = :date
        """,
    )
    suspend fun countOpenFlush(slotId: Long, date: LocalDate): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckIn(checkIn: CheckInEntity)

    @Query("SELECT * FROM check_in WHERE date = :date")
    suspend fun getCheckIn(date: LocalDate): CheckInEntity?

    @Query("SELECT * FROM check_in WHERE date = :date")
    fun observeCheckIn(date: LocalDate): Flow<CheckInEntity?>

    @Query("SELECT * FROM check_in ORDER BY date")
    suspend fun getCheckIns(): List<CheckInEntity>

    @Insert
    suspend fun insertMobilityDone(done: MobilityDoneEntity): Long

    @Query("SELECT * FROM mobility_done WHERE date >= :from ORDER BY date DESC")
    fun observeMobilitySince(from: LocalDate): Flow<List<MobilityDoneEntity>>
}

@Dao
interface LogDao {
    @Insert
    suspend fun insert(log: LogEntity): Long

    @Update
    suspend fun update(log: LogEntity)

    @Query("DELETE FROM log WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM log WHERE id = :id")
    suspend fun get(id: Long): LogEntity?

    @Query("SELECT * FROM log WHERE exerciseId = :exerciseId ORDER BY date, id")
    fun observeForExercise(exerciseId: String): Flow<List<LogEntity>>

    @Query("SELECT * FROM log WHERE exerciseId = :exerciseId ORDER BY date, id")
    suspend fun getForExercise(exerciseId: String): List<LogEntity>

    @Query("SELECT * FROM log WHERE exerciseId IN (:exerciseIds) ORDER BY date, id")
    suspend fun getForExercises(exerciseIds: List<String>): List<LogEntity>

    @Query("SELECT * FROM log WHERE sessionId = :sessionId ORDER BY id")
    fun observeForSession(sessionId: Long): Flow<List<LogEntity>>

    /** How many logs each exercise has under [slotId]: what orders a slot's swap chips by use. */
    @Query("SELECT exerciseId, COUNT(*) AS uses FROM log WHERE slotId = :slotId GROUP BY exerciseId")
    suspend fun useCountsForSlot(slotId: Long): List<ExerciseUse>

    /**
     * The logs of finished sessions that belong to the plan, oldest first. A log whose slot is gone (slotId nulled
     * when the slot was deleted) still counts; a log of a session-only flush finisher slot never does.
     */
    @Query(
        """
        SELECT l.sessionId AS sessionId, l.slotId AS slotId, l.exerciseId AS exerciseId, l.type AS type
        FROM log l LEFT JOIN slot s ON s.id = l.slotId
        WHERE l.sessionId IN (SELECT id FROM session WHERE finishedAtMillis IS NOT NULL)
          AND (l.slotId IS NULL OR s.sessionOnlyFor IS NULL)
        ORDER BY l.id
        """,
    )
    suspend fun getPlanLogsOfFinishedSessions(): List<SessionLogRow>

    /**
     * Marks every log of [slotId] as a flush. Once a flush finisher's slot is gone, the type is all that tells its logs
     * from those of a deleted plan slot, so this runs before the slot is deleted (their slotId then becomes null),
     * whatever type the logs were written with.
     */
    @Query("UPDATE log SET type = 'FLUSH' WHERE slotId = :slotId")
    suspend fun markSlotLogsAsFlush(slotId: Long)

    /** How many logs each exercise has in all: what picks the most-logged exercise of a muscle for a flush finisher. */
    @Query("SELECT exerciseId, COUNT(*) AS uses FROM log GROUP BY exerciseId")
    suspend fun useCounts(): List<ExerciseUse>

    /** Ids of the sessions with at least one log that is not a flush: the ones that really trained their muscles. */
    @Query("SELECT DISTINCT sessionId FROM log WHERE sessionId IS NOT NULL AND type != 'FLUSH'")
    suspend fun getTrainedSessionIds(): List<Long>

    /** Every log carrying a hurt flag, oldest first. */
    @Query("SELECT * FROM log WHERE hurt IS NOT NULL ORDER BY date, id")
    suspend fun getHurtLogs(): List<LogEntity>

    @Query("SELECT * FROM log ORDER BY date, id")
    suspend fun getAll(): List<LogEntity>

    @Query("SELECT COUNT(*) FROM log")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM log WHERE exerciseId = :exerciseId")
    suspend fun countForExercise(exerciseId: String): Int
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 0")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 0")
    suspend fun get(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SettingsEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(settings: SettingsEntity)

    /**
     * A timer was killed early: brings the battery card back. One statement, so it never races a read-then-write;
     * an already-set flag matches no row, so repeat calls do not wake the settings observers.
     */
    @Query("UPDATE settings SET timerWasKilled = 1 WHERE id = 0 AND timerWasKilled = 0")
    suspend fun markTimerKilled()

    /** The lifter dismissed the battery card; a pending "timer was killed" no longer needs to bring it back. */
    @Query("UPDATE settings SET batteryCardDismissed = 1, timerWasKilled = 0 WHERE id = 0")
    suspend fun dismissBatteryCard()

    /** The club the lifter trains at now, or null for none. Use [com.simplegym.domain.ClubService.select] to also move the open session. */
    @Query("UPDATE settings SET currentClubId = :id WHERE id = 0")
    suspend fun setCurrentClub(id: Long?)

    // Single statements, so editing a setting never overwrites the flags another writer set in the meantime.
    @Query("UPDATE settings SET age = :age WHERE id = 0")
    suspend fun setAge(age: Int)

    @Query("UPDATE settings SET lowerBackProtection = :on WHERE id = 0")
    suspend fun setLowerBackProtection(on: Boolean)

    @Query("UPDATE settings SET equipmentIncrements = :increments WHERE id = 0")
    suspend fun setEquipmentIncrements(increments: Map<String, Double>)
}

@Dao
interface ClubDao {
    @Insert
    suspend fun insert(club: ClubEntity): Long

    @Query("UPDATE club SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM club WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM club ORDER BY name COLLATE NOCASE")
    fun observeClubs(): Flow<List<ClubEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markUnavailable(entry: ClubUnavailableEntity)

    @Query("DELETE FROM club_unavailable WHERE clubId = :clubId AND exerciseId = :exerciseId")
    suspend fun markAvailable(clubId: Long, exerciseId: String)

    @Query("SELECT exerciseId FROM club_unavailable WHERE clubId = :clubId")
    fun observeUnavailable(clubId: Long): Flow<List<String>>

    @Query("SELECT exerciseId FROM club_unavailable WHERE clubId = :clubId")
    suspend fun getUnavailable(clubId: Long): List<String>

    @Query("SELECT * FROM club_unavailable")
    fun observeAllUnavailable(): Flow<List<ClubUnavailableEntity>>
}

/** Marks [exerciseId] as missing from, or available again at, [clubId]. */
suspend fun ClubDao.setUnavailable(clubId: Long, exerciseId: String, unavailable: Boolean) =
    if (unavailable) markUnavailable(ClubUnavailableEntity(clubId, exerciseId)) else markAvailable(clubId, exerciseId)

/** One row of [LogDao.getPlanLogsOfFinishedSessions]. */
data class SessionLogRow(val sessionId: Long, val slotId: Long?, val exerciseId: String, val type: SessionType)

/** One row of [LogDao.useCountsForSlot]. */
data class ExerciseUse(val exerciseId: String, val uses: Int)
