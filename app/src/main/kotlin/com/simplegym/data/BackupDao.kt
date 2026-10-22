package com.simplegym.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

/**
 * Whole-table reads, wipes and inserts for backup and restore. Inserts keep the rows' own ids (a non-zero id is not
 * regenerated), so every reference between rows holds after a restore. Run the wipes and inserts in one write transaction.
 */
@Dao
interface BackupDao {
    @Query("SELECT * FROM exercise ORDER BY rowid")
    suspend fun exercises(): List<ExerciseEntity>

    @Query("SELECT * FROM day ORDER BY id")
    suspend fun days(): List<DayEntity>

    @Query("SELECT * FROM slot ORDER BY id")
    suspend fun slots(): List<SlotEntity>

    @Query("SELECT * FROM slot_swap ORDER BY slotId, position")
    suspend fun slotSwaps(): List<SlotSwapEntity>

    @Query("SELECT * FROM session ORDER BY id")
    suspend fun sessions(): List<SessionEntity>

    @Query("SELECT * FROM session_slot ORDER BY sessionId, slotId")
    suspend fun sessionSlots(): List<SessionSlotEntity>

    @Query("SELECT * FROM log ORDER BY id")
    suspend fun logs(): List<LogEntity>

    @Query("SELECT * FROM check_in ORDER BY date")
    suspend fun checkIns(): List<CheckInEntity>

    @Query("SELECT * FROM club ORDER BY id")
    suspend fun clubs(): List<ClubEntity>

    @Query("SELECT * FROM club_unavailable ORDER BY clubId, exerciseId")
    suspend fun clubUnavailable(): List<ClubUnavailableEntity>

    @Query("SELECT * FROM mobility_done ORDER BY id")
    suspend fun mobilityDone(): List<MobilityDoneEntity>

    @Query("SELECT * FROM settings ORDER BY id")
    suspend fun settings(): List<SettingsEntity>

    /** Children before parents, so no foreign key is ever violated half way. */
    @Query("DELETE FROM log")
    suspend fun deleteLogs()

    @Query("DELETE FROM session_slot")
    suspend fun deleteSessionSlots()

    @Query("DELETE FROM session")
    suspend fun deleteSessions()

    @Query("DELETE FROM slot_swap")
    suspend fun deleteSlotSwaps()

    @Query("DELETE FROM slot")
    suspend fun deleteSlots()

    @Query("DELETE FROM day")
    suspend fun deleteDays()

    @Query("DELETE FROM club_unavailable")
    suspend fun deleteClubUnavailable()

    @Query("DELETE FROM club")
    suspend fun deleteClubs()

    @Query("DELETE FROM check_in")
    suspend fun deleteCheckIns()

    @Query("DELETE FROM mobility_done")
    suspend fun deleteMobilityDone()

    @Query("DELETE FROM exercise")
    suspend fun deleteExercises()

    @Query("DELETE FROM settings")
    suspend fun deleteSettings()

    @Insert
    suspend fun insertExercises(rows: List<ExerciseEntity>)

    @Insert
    suspend fun insertDays(rows: List<DayEntity>)

    @Insert
    suspend fun insertSlots(rows: List<SlotEntity>)

    @Insert
    suspend fun insertSlotSwaps(rows: List<SlotSwapEntity>)

    @Insert
    suspend fun insertSessions(rows: List<SessionEntity>)

    @Insert
    suspend fun insertSessionSlots(rows: List<SessionSlotEntity>)

    @Insert
    suspend fun insertLogs(rows: List<LogEntity>)

    @Insert
    suspend fun insertCheckIns(rows: List<CheckInEntity>)

    @Insert
    suspend fun insertClubs(rows: List<ClubEntity>)

    @Insert
    suspend fun insertClubUnavailable(rows: List<ClubUnavailableEntity>)

    @Insert
    suspend fun insertMobilityDone(rows: List<MobilityDoneEntity>)

    @Insert
    suspend fun insertSettings(rows: List<SettingsEntity>)
}
