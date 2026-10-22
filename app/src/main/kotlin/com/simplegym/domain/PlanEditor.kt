package com.simplegym.domain

import androidx.room3.withWriteTransaction
import com.simplegym.data.DayEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LibraryExercise
import com.simplegym.data.SlotEntity
import com.simplegym.data.SlotSwapEntity
import com.simplegym.data.slug
import com.simplegym.data.toEntity
import com.simplegym.engine.Chip
import com.simplegym.engine.Muscle

/**
 * Edits the plan: days, slots, swap lists and the lifter's own exercises. Anything that has history is archived
 * rather than deleted, so logs, session rows and the progress chart survive. Invalid input throws
 * [IllegalArgumentException] whose message is the name of the offending field ("name", "sets", "lo", "hi", "roundSec",
 * "exerciseId", "ids"), which the editor screens show next to that field.
 */
/** The id of an exercise [PlanEditor.addCustomExercise] saved, and "Restored <name>" when it brought an archived one back. */
data class AddedExercise(val id: String, val note: String?)

class PlanEditor(private val db: GymDatabase) {

    // ---- days ----

    suspend fun addDay(name: String, chips: Set<Chip>): Long = db.withWriteTransaction {
        val position = (db.planDao().maxDayPosition() ?: -1) + 1
        db.planDao().insertDay(DayEntity(name = validName(name), position = position, chips = chips))
    }

    suspend fun renameDay(dayId: Long, name: String) = db.withWriteTransaction {
        val day = requireDay(dayId)
        db.planDao().updateDay(day.copy(name = validName(name)))
    }

    suspend fun setDayChips(dayId: Long, chips: Set<Chip>) = db.withWriteTransaction {
        db.planDao().updateDay(requireDay(dayId).copy(chips = chips))
    }

    /** [ids] must be exactly the non-archived days, in the new order. */
    suspend fun reorderDays(ids: List<Long>) = db.withWriteTransaction {
        val days = db.planDao().getActiveDays().associateBy { it.id }
        require(ids.size == days.size && ids.toSet() == days.keys) { "ids" }
        ids.forEachIndexed { position, id -> db.planDao().updateDay(days.getValue(id).copy(position = position)) }
    }

    /** Hides the day from Home and from round counting; its sessions and logs stay. */
    suspend fun archiveDay(dayId: Long) = db.withWriteTransaction {
        db.planDao().updateDay(requireDay(dayId).copy(archived = true))
    }

    /** A day that has sessions is archived instead of deleted. Deleting takes its slots and swaps with it. */
    suspend fun deleteDay(dayId: Long) = db.withWriteTransaction {
        val day = db.planDao().getDay(dayId) ?: return@withWriteTransaction
        if (db.sessionDao().countForDay(dayId) > 0) {
            db.planDao().updateDay(day.copy(archived = true))
        } else {
            db.planDao().deleteDay(dayId)
        }
    }

    /** Every chip that trains one of [slotMuscles]: what "Suggest" offers for a day's chips. */
    fun proposeChips(slotMuscles: List<Muscle>): Set<Chip> =
        Chip.entries.filter { chip -> chip.muscles.any { it in slotMuscles } }.toSet()

    // ---- slots ----

    suspend fun addSlot(dayId: Long, exerciseId: String, sets: Int, lo: Int, hi: Int, roundSec: Int): Long =
        db.withWriteTransaction {
            requireDay(dayId)
            validateSlot(exerciseId, sets, lo, hi, roundSec)
            val position = (db.planDao().getSlots(dayId).maxOfOrNull { it.position } ?: -1) + 1
            db.planDao().insertSlot(
                SlotEntity(
                    dayId = dayId, position = position, defaultExerciseId = exerciseId,
                    sets = sets, lo = lo, hi = hi, roundSec = roundSec,
                ),
            )
        }

    /** Changing the default exercise drops the slot's block rotation: it named a variant of the old one. */
    suspend fun updateSlot(slotId: Long, exerciseId: String, sets: Int, lo: Int, hi: Int, roundSec: Int) =
        db.withWriteTransaction {
            val slot = requireSlot(slotId)
            validateSlot(exerciseId, sets, lo, hi, roundSec, current = slot.defaultExerciseId)
            val changed = exerciseId != slot.defaultExerciseId
            db.planDao().updateSlot(
                slot.copy(
                    defaultExerciseId = exerciseId, sets = sets, lo = lo, hi = hi, roundSec = roundSec,
                    rotationExerciseId = if (changed) null else slot.rotationExerciseId,
                    rotationBlockIndex = if (changed) null else slot.rotationBlockIndex,
                ),
            )
        }

    /**
     * Removes the slot from the plan. Its logs stay (their slotId becomes null) and so do the session rows of
     * sessions it was in, which keeps a round that is in progress from jamming or jumping.
     */
    suspend fun deleteSlot(slotId: Long) = db.withWriteTransaction {
        db.planDao().deleteSlot(slotId)
    }

    /** [ids] must be exactly the day's plan slots, in the new order. */
    suspend fun reorderSlots(dayId: Long, ids: List<Long>) = db.withWriteTransaction {
        val slots = db.planDao().getSlots(dayId).associateBy { it.id }
        require(ids.size == slots.size && ids.toSet() == slots.keys) { "ids" }
        ids.forEachIndexed { position, id -> db.planDao().updateSlot(slots.getValue(id).copy(position = position)) }
    }

    /** Replaces the slot's swap list with [ids] in that order; duplicates and the slot's own default are dropped. */
    suspend fun setSwaps(slotId: Long, ids: List<String>) = db.withWriteTransaction {
        val slot = requireSlot(slotId)
        val wanted = ids.distinct().filter { it != slot.defaultExerciseId }
        val known = db.libraryDao().getAll().map { it.id }.toSet()
        require(wanted.all { it in known }) { "exerciseId" }
        db.planDao().deleteSwaps(slotId)
        db.planDao().insertSwaps(wanted.mapIndexed { position, id -> SlotSwapEntity(slotId, id, position) })
    }

    // ---- exercises ----

    /**
     * The id is the slug of the name, whatever [exercise] carries. A name that slugs to an active exercise is refused;
     * one that slugs to an archived exercise restores it, with the fields of [exercise], and says so in the note.
     */
    suspend fun addCustomExercise(exercise: LibraryExercise): AddedExercise = db.withWriteTransaction {
        val name = validName(exercise.name)
        val id = slug(name)
        val existing = db.libraryDao().get(id)
        require(id.isNotEmpty() && (existing == null || existing.archived)) { "name" }
        val entity = exercise.copy(id = id, name = name).toEntity(isCustom = existing?.isCustom ?: true)
        if (existing == null) {
            db.libraryDao().upsert(entity)
            AddedExercise(id, note = null)
        } else {
            db.libraryDao().upsert(
                entity.copy(incrementOverrideKg = existing.incrementOverrideKg, hurtClearedOn = existing.hurtClearedOn),
            )
            AddedExercise(id, note = "Restored $name")
        }
    }

    /** Brings an archived exercise back into chips and search. */
    suspend fun restoreExercise(exerciseId: String) = db.withWriteTransaction {
        db.libraryDao().setArchived(exerciseId, false)
    }

    /** Brings an archived day back, after the active ones. */
    suspend fun restoreDay(dayId: Long) = db.withWriteTransaction {
        val day = requireDay(dayId)
        if (!day.archived) return@withWriteTransaction
        val position = (db.planDao().maxDayPosition() ?: -1) + 1
        db.planDao().updateDay(day.copy(archived = false, position = position))
    }

    /**
     * Deletes a custom exercise nothing refers to. One with logs, in a slot (default, swap or rotation) or from the
     * built-in library is archived instead: hidden from chips and search, kept for history and charts.
     */
    suspend fun deleteExercise(exerciseId: String) = db.withWriteTransaction {
        val exercise = db.libraryDao().get(exerciseId) ?: return@withWriteTransaction
        val used = db.logDao().countForExercise(exerciseId) > 0 ||
            db.planDao().countSlotsUsing(exerciseId) > 0 ||
            db.planDao().countSwapsUsing(exerciseId) > 0
        if (exercise.isCustom && !used) db.libraryDao().delete(exerciseId) else db.libraryDao().setArchived(exerciseId, true)
    }

    // ---- validation ----

    private fun validName(name: String): String = name.trim().also { require(it.isNotEmpty()) { "name" } }

    /** [current] is the slot's present default, which stays valid even if it has been archived since. */
    private suspend fun validateSlot(
        exerciseId: String,
        sets: Int,
        lo: Int,
        hi: Int,
        roundSec: Int,
        current: String? = null,
    ) {
        require(lo >= 1) { "lo" }
        require(hi >= lo) { "hi" }
        require(sets in SETS_RANGE) { "sets" }
        require(roundSec in ROUND_SEC_RANGE && roundSec % ROUND_SEC_STEP == 0) { "roundSec" }
        val exercise = db.libraryDao().get(exerciseId)
        require(exercise != null && (!exercise.archived || exerciseId == current)) { "exerciseId" }
    }

    private suspend fun requireDay(dayId: Long): DayEntity =
        requireNotNull(db.planDao().getDay(dayId)) { "dayId" }

    private suspend fun requireSlot(slotId: Long): SlotEntity =
        requireNotNull(db.planDao().getSlot(slotId)) { "slotId" }

    companion object {
        val SETS_RANGE = 1..10
        val ROUND_SEC_RANGE = 15..600
        const val ROUND_SEC_STEP = 15
    }
}
