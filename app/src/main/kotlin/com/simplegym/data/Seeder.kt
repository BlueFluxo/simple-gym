package com.simplegym.data

import android.content.res.AssetManager
import androidx.room3.withWriteTransaction

/**
 * First-launch seeding: the exercise library, the starter plan and default settings. The library is the
 * "is this a fresh install" marker, so a lifter who later deletes every day is not handed the plan back.
 */
class Seeder(private val db: GymDatabase, private val assets: AssetManager) {

    suspend fun seedIfEmpty() {
        db.withWriteTransaction {
            db.settingsDao().insertIfAbsent(SettingsEntity())
            if (db.libraryDao().count() > 0) return@withWriteTransaction

            val library = readAsset<LibraryFile>(LIBRARY_ASSET).exercises
            db.libraryDao().insertAll(library.map { it.toEntity() })
            val knownIds = library.map { it.id }.toSet()
            seedPlan(readAsset<StarterPlanFile>(PLAN_ASSET), knownIds)
        }
    }

    private suspend fun seedPlan(plan: StarterPlanFile, knownIds: Set<String>) {
        fun idFor(name: String): String =
            slug(name).also { check(it in knownIds) { "Starter plan names unknown exercise '$name'" } }

        val planDao = db.planDao()
        plan.days.forEachIndexed { dayIndex, day ->
            val dayId = planDao.insertDay(
                DayEntity(name = day.name, position = dayIndex, chips = day.chips.toSet()),
            )
            day.slots.forEachIndexed { slotIndex, slot ->
                val slotId = planDao.insertSlot(
                    SlotEntity(
                        dayId = dayId,
                        position = slotIndex,
                        defaultExerciseId = idFor(slot.exercise),
                        sets = slot.sets,
                        lo = slot.lo,
                        hi = slot.hi,
                        roundSec = slot.roundSec,
                    ),
                )
                planDao.insertSwaps(
                    slot.swaps.mapIndexed { swapIndex, name -> SlotSwapEntity(slotId, idFor(name), swapIndex) },
                )
            }
        }
    }

    private inline fun <reified T> readAsset(name: String): T =
        assets.open(name).bufferedReader().use { assetJson.decodeFromString<T>(it.readText()) }

    private companion object {
        const val LIBRARY_ASSET = "exercise-library.json"
        const val PLAN_ASSET = "starter-plan.json"
    }
}
