@file:UseSerializers(LocalDateSerializer::class)

package com.simplegym.data

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.simplegym.engine.BackLoad
import com.simplegym.engine.Chip
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.LogRecord
import com.simplegym.engine.Muscle
import com.simplegym.engine.RangeType
import com.simplegym.engine.SessionType
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

enum class SlotStatus { PENDING, DONE, SKIPPED }

/** Library entry. [id] is the slug of the name; custom exercises get their own slug. */
@Serializable
@Entity(tableName = "exercise")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val muscle: Muscle,
    val equipment: String,
    val swapGroup: String,
    val incrementKg: Double?,
    val incrementOverrideKg: Double? = null,
    val backLoad: BackLoad,
    val joints: Set<Joint>,
    val isAssisted: Boolean,
    val isBodyweight: Boolean,
    val isCustom: Boolean = false,
    /** Hidden from chips and search but kept so history and charts survive. */
    val archived: Boolean = false,
    val hurtClearedOn: LocalDate? = null,
)

@Serializable
@Entity(tableName = "day")
data class DayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int,
    val chips: Set<Chip>,
    val archived: Boolean = false,
)

@Serializable
@Entity(
    tableName = "slot",
    foreignKeys = [
        ForeignKey(
            entity = DayEntity::class,
            parentColumns = ["id"],
            childColumns = ["dayId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dayId")],
)
data class SlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    val position: Int,
    val defaultExerciseId: String,
    val sets: Int,
    val lo: Int,
    val hi: Int,
    val roundSec: Int = 120,
    val timerRounds: Int? = null,
    val timerRoundSec: Int? = null,
    val rotationExerciseId: String? = null,
    val rotationBlockIndex: Int? = null,
    /**
     * Non-null marks a flush-finisher slot that exists for that session only: it is excluded from plans,
     * from [SessionEntity.slotsTotal] and from round counting.
     */
    val sessionOnlyFor: Long? = null,
)

@Serializable
@Entity(
    tableName = "slot_swap",
    primaryKeys = ["slotId", "exerciseId"],
    foreignKeys = [
        ForeignKey(
            entity = SlotEntity::class,
            parentColumns = ["id"],
            childColumns = ["slotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SlotSwapEntity(
    val slotId: Long,
    val exerciseId: String,
    val position: Int,
)

@Serializable
@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    val date: LocalDate,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val clubId: Long? = null,
    val slotsTotal: Int,
    /**
     * The non-archived day ids when the session started: the days its round needed. Null on sessions from before
     * schema 2; the block fold then falls back to the current days.
     */
    val activeDayIdsAtStart: Set<Long>? = null,
)

/** A slot's state inside one session. [slotId] is deliberately not a foreign key: deleting a slot mid-round must not touch it. */
@Serializable
@Entity(
    tableName = "session_slot",
    primaryKeys = ["sessionId", "slotId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionSlotEntity(
    val sessionId: Long,
    val slotId: Long,
    val status: SlotStatus = SlotStatus.PENDING,
    val flush: Boolean = false,
)

@Serializable
@Entity(
    tableName = "log",
    foreignKeys = [
        ForeignKey(
            entity = SlotEntity::class,
            parentColumns = ["id"],
            childColumns = ["slotId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("slotId"), Index("sessionId"), Index("exerciseId", "date")],
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long?,
    val slotId: Long?,
    val exerciseId: String,
    val date: LocalDate,
    val weightKg: Double?,
    val bestReps: Int,
    val sets: Int,
    val feel: Feel,
    val type: SessionType,
    val rangeType: RangeType = RangeType.BASE,
    val hurt: Joint? = null,
    val note: String? = null,
    val imported: Boolean = false,
    /**
     * The plan slot this log was recorded for. Unlike [slotId] it has no foreign key, so it survives that slot's
     * deletion and lets a session's "removed from plan" row find its own log. Null for imported and pre-v3 logs.
     */
    val loggedSlotId: Long? = null,
)

fun LogEntity.toRecord(): LogRecord = LogRecord(
    exerciseId = exerciseId,
    date = date,
    weightKg = weightKg,
    bestReps = bestReps,
    sets = sets,
    feel = feel,
    type = type,
    rangeType = rangeType,
    hurt = hurt,
)

@Serializable
@Entity(tableName = "check_in")
data class CheckInEntity(
    @PrimaryKey val date: LocalDate,
    val stiff: Set<Chip>,
)

@Serializable
@Entity(tableName = "club")
data class ClubEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Serializable
@Entity(
    tableName = "club_unavailable",
    primaryKeys = ["clubId", "exerciseId"],
    foreignKeys = [
        ForeignKey(
            entity = ClubEntity::class,
            parentColumns = ["id"],
            childColumns = ["clubId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ClubUnavailableEntity(
    val clubId: Long,
    val exerciseId: String,
)

@Serializable
@Entity(tableName = "mobility_done")
data class MobilityDoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val area: String,
    val date: LocalDate,
)

@Serializable
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 0,
    val age: Int? = null,
    val lowerBackProtection: Boolean = false,
    val currentClubId: Long? = null,
    val equipmentIncrements: Map<String, Double> = emptyMap(),
    val onboarded: Boolean = false,
    val batteryCardDismissed: Boolean = false,
    val timerWasKilled: Boolean = false,
)
