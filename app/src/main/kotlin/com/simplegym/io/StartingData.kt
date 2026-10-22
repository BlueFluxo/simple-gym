package com.simplegym.io

import androidx.room3.withWriteTransaction
import com.simplegym.data.ClubEntity
import com.simplegym.data.ClubUnavailableEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LibraryExercise
import com.simplegym.data.LogEntity
import com.simplegym.data.SettingsEntity
import com.simplegym.data.SlotEntity
import com.simplegym.data.assetJson
import com.simplegym.data.toEntity
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.SessionType
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Newest starting-data format this build reads. */
const val STARTING_DATA_VERSION = 1

/**
 * The starting-data file (spec §8): profile, clubs, current working weights and optional hurt flags and custom
 * exercises, all matched to library exercises by stable id. Unknown keys are ignored, so a file may carry extras.
 */
@Serializable
data class StartingDataV1(
    val version: Int = 1,
    val profile: ProfileDto,
    val clubs: List<ClubDto> = emptyList(),
    val weights: List<WeightDto> = emptyList(),
    val hurt: List<HurtDto> = emptyList(),
    val customExercises: List<LibraryExercise> = emptyList(),
)

@Serializable
data class ProfileDto(val age: Int, val lowerBackProtection: Boolean)

@Serializable
data class ClubDto(val name: String, val unavailable: List<String> = emptyList())

@Serializable
data class WeightDto(val exerciseId: String, val weightKg: Double, val reps: Int? = null)

@Serializable
data class HurtDto(val exerciseId: String, val joint: Joint)

/** [imported] is the number of logs written (weights and hurt entries); ids that match no exercise are listed once each. */
data class ImportResult(val imported: Int, val skippedUnknownIds: List<String>)

/** A file that cannot be imported; [message] is fit to show the lifter. */
class StartingDataException(message: String) : Exception(message)

class StartingDataImporter(private val db: GymDatabase) {

    suspend fun import(json: String, today: LocalDate): ImportResult = import(parse(json), today)

    /**
     * Writes [data] in one transaction, so a failure leaves nothing behind: custom exercises first (so weights and
     * hurt entries may name them), then the profile (which also ends onboarding), clubs, weights and hurt flags.
     * Weights become imported NORMAL/Solid baseline logs dated [today]; hurt entries become HELD logs carrying the joint.
     */
    suspend fun import(data: StartingDataV1, today: LocalDate): ImportResult = db.withWriteTransaction {
        val skipped = LinkedHashSet<String>()
        val libraryDao = db.libraryDao()

        for (custom in data.customExercises) {
            if (libraryDao.get(custom.id) != null) continue
            val entity = try {
                custom.toEntity(isCustom = true)
            } catch (e: IllegalStateException) {
                throw StartingDataException(e.message ?: "A custom exercise in this file is not valid.")
            }
            libraryDao.upsert(entity)
        }

        val settingsDao = db.settingsDao()
        settingsDao.upsert(
            (settingsDao.get() ?: SettingsEntity()).copy(
                age = data.profile.age,
                lowerBackProtection = data.profile.lowerBackProtection,
                onboarded = true,
            ),
        )

        for (club in data.clubs) {
            val clubId = db.clubDao().insert(ClubEntity(name = club.name))
            for (id in club.unavailable.distinct()) {
                if (libraryDao.get(id) == null) skipped += id else db.clubDao().markUnavailable(ClubUnavailableEntity(clubId, id))
            }
        }

        val slotFor = firstSlotUsingEachExercise()
        var imported = 0
        suspend fun log(exerciseId: String, weightKg: Double?, reps: Int?, type: SessionType, hurt: Joint?) {
            val slot = slotFor[exerciseId]
            db.logDao().insert(
                LogEntity(
                    sessionId = null,
                    slotId = null,
                    exerciseId = exerciseId,
                    date = today,
                    weightKg = weightKg,
                    bestReps = reps?.takeIf { it > 0 } ?: slot?.lo ?: DEFAULT_REPS,
                    sets = slot?.sets ?: DEFAULT_SETS,
                    feel = Feel.SOLID,
                    type = type,
                    hurt = hurt,
                    imported = true,
                ),
            )
            imported++
        }

        for (weight in data.weights) {
            val exercise = libraryDao.get(weight.exerciseId)
            if (exercise == null) {
                skipped += weight.exerciseId
                continue
            }
            val kg = when {
                exercise.isBodyweight -> null
                exercise.isAssisted -> -abs(weight.weightKg)
                else -> weight.weightKg
            }
            log(exercise.id, kg, weight.reps, SessionType.NORMAL, hurt = null)
        }

        for (hurt in data.hurt) {
            if (libraryDao.get(hurt.exerciseId) == null) {
                skipped += hurt.exerciseId
                continue
            }
            log(hurt.exerciseId, weightKg = null, reps = null, type = SessionType.HELD, hurt = hurt.joint)
        }

        ImportResult(imported, skipped.toList())
    }

    /** The first plan slot (plan order) that has each exercise as its default or as a swap: its sets and reps seed imported logs. */
    private suspend fun firstSlotUsingEachExercise(): Map<String, SlotEntity> {
        val firstSlot = LinkedHashMap<String, SlotEntity>()
        for (day in db.planDao().getActiveDays()) {
            for (slot in db.planDao().getSlots(day.id)) {
                val ids = listOf(slot.defaultExerciseId) + db.planDao().getSwaps(slot.id).map { it.exerciseId }
                for (id in ids) firstSlot.putIfAbsent(id, slot)
            }
        }
        return firstSlot
    }

    private fun parse(json: String): StartingDataV1 {
        val root = try {
            assetJson.parseToJsonElement(json) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw StartingDataException(NOT_STARTING_DATA)
        // Checked before decoding: a newer file may have a shape this version cannot decode at all.
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: STARTING_DATA_VERSION
        if (version > STARTING_DATA_VERSION) throw StartingDataException(NEWER_VERSION)
        return try {
            assetJson.decodeFromJsonElement(StartingDataV1.serializer(), root)
        } catch (e: IllegalArgumentException) {
            throw StartingDataException(NOT_STARTING_DATA)
        }
    }

    private companion object {
        const val DEFAULT_SETS = 3
        const val DEFAULT_REPS = 8
        const val NEWER_VERSION = "This file was made by a newer version of Simple Gym."
        const val NOT_STARTING_DATA = "This is not a Simple Gym starting-data file."
    }
}
