package com.simplegym.io

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.simplegym.data.CheckInEntity
import com.simplegym.data.ClubEntity
import com.simplegym.data.ClubUnavailableEntity
import com.simplegym.data.DayEntity
import com.simplegym.data.ExerciseEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.MobilityDoneEntity
import com.simplegym.data.SessionEntity
import com.simplegym.data.SessionSlotEntity
import com.simplegym.data.SettingsEntity
import com.simplegym.data.SlotEntity
import com.simplegym.data.SlotSwapEntity
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Newest backup format this build reads. */
const val BACKUP_VERSION = 1

/**
 * A whole-database backup: one list per table, rows exactly as stored (ids included, so every reference holds).
 * The lists have no defaults on purpose: a file missing one is not a backup, and must not wipe that table on restore.
 */
@Serializable
data class BackupV1(
    val version: Int = BACKUP_VERSION,
    val exportedOn: String,
    val exercises: List<ExerciseEntity>,
    val days: List<DayEntity>,
    val slots: List<SlotEntity>,
    val slotSwaps: List<SlotSwapEntity>,
    val sessions: List<SessionEntity>,
    val sessionSlots: List<SessionSlotEntity>,
    val logs: List<LogEntity>,
    val checkIns: List<CheckInEntity>,
    val clubs: List<ClubEntity>,
    val clubUnavailable: List<ClubUnavailableEntity>,
    val mobilityDone: List<MobilityDoneEntity>,
    val settings: List<SettingsEntity>,
)

sealed interface RestoreResult {
    /** [rows] is the number of rows now in the database, all tables together. */
    data class Restored(val rows: Int) : RestoreResult

    /** Nothing was changed; [message] is fit to show the lifter. */
    data class Failed(val message: String) : RestoreResult
}

class BackupService(
    private val db: GymDatabase,
    private val today: () -> LocalDate = LocalDate::now,
) {

    /** Every table as JSON, read in one transaction so the file is a consistent snapshot. */
    suspend fun export(): String {
        val dao = db.backupDao()
        val backup = db.withReadTransaction {
            BackupV1(
                exportedOn = today().toString(),
                exercises = dao.exercises(),
                days = dao.days(),
                slots = dao.slots(),
                slotSwaps = dao.slotSwaps(),
                sessions = dao.sessions(),
                sessionSlots = dao.sessionSlots(),
                logs = dao.logs(),
                checkIns = dao.checkIns(),
                clubs = dao.clubs(),
                clubUnavailable = dao.clubUnavailable(),
                mobilityDone = dao.mobilityDone(),
                settings = dao.settings(),
            )
        }
        return backupJson.encodeToString(BackupV1.serializer(), backup)
    }

    /**
     * Replaces every table with the backup's, in one write transaction. The text is parsed and checked completely
     * first; if that fails, or a write does, the database is left as it was.
     * [beforeApply] runs once the backup is known to be readable, just before the data is replaced: the caller stops
     * whatever runs against the old data (the timer) there.
     */
    suspend fun restore(json: String, beforeApply: suspend () -> Unit = {}): RestoreResult {
        val backup = when (val parsed = parse(json)) {
            is Parsed.Ok -> parsed.backup
            is Parsed.Bad -> return RestoreResult.Failed(parsed.message)
        }
        return try {
            beforeApply()
            apply(backup)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RestoreResult.Failed(APPLY_FAILED)
        }
    }

    private suspend fun apply(backup: BackupV1): RestoreResult = db.withWriteTransaction {
        val dao = db.backupDao()
        // Children before parents.
        dao.deleteLogs()
        dao.deleteSessionSlots()
        dao.deleteSessions()
        dao.deleteSlotSwaps()
        dao.deleteSlots()
        dao.deleteDays()
        dao.deleteClubUnavailable()
        dao.deleteClubs()
        dao.deleteCheckIns()
        dao.deleteMobilityDone()
        dao.deleteExercises()
        dao.deleteSettings()

        dao.insertExercises(backup.exercises)
        dao.insertDays(backup.days)
        dao.insertSlots(backup.slots)
        dao.insertSlotSwaps(backup.slotSwaps)
        dao.insertClubs(backup.clubs)
        dao.insertClubUnavailable(backup.clubUnavailable)
        dao.insertSessions(backup.sessions)
        dao.insertSessionSlots(backup.sessionSlots)
        dao.insertLogs(backup.logs)
        dao.insertCheckIns(backup.checkIns)
        dao.insertMobilityDone(backup.mobilityDone)
        // The app expects its one settings row to exist.
        dao.insertSettings(backup.settings.ifEmpty { listOf(SettingsEntity()) })
        RestoreResult.Restored(backup.rowCount())
    }

    private sealed interface Parsed {
        class Ok(val backup: BackupV1) : Parsed
        class Bad(val message: String) : Parsed
    }

    private fun parse(json: String): Parsed {
        val root = try {
            backupJson.parseToJsonElement(json) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return Parsed.Bad(NOT_A_BACKUP)
        // Checked before decoding: a newer file may have a shape this version cannot decode at all.
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
        if (version == null || version < 1) return Parsed.Bad(NOT_A_BACKUP)
        if (version > BACKUP_VERSION) return Parsed.Bad(NEWER_VERSION)
        return try {
            Parsed.Ok(backupJson.decodeFromJsonElement(BackupV1.serializer(), root))
        } catch (e: IllegalArgumentException) {
            // SerializationException and a bad date are both IllegalArgumentExceptions.
            Parsed.Bad(NOT_A_BACKUP)
        }
    }

    private companion object {
        const val NEWER_VERSION = "This backup was made by a newer version of Simple Gym."
        const val NOT_A_BACKUP = "This is not a Simple Gym backup file."
        const val APPLY_FAILED = "Could not restore this backup. Your data was not changed."
    }
}

private fun BackupV1.rowCount(): Int = exercises.size + days.size + slots.size + slotSwaps.size + sessions.size +
    sessionSlots.size + logs.size + checkIns.size + clubs.size + clubUnavailable.size + mobilityDone.size + settings.size

/**
 * Unknown fields are ignored on read (a backup written by a later 1.x build still restores), and defaults are written
 * out so a file does not depend on this build's defaults when it is read back.
 */
private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
