package com.simplegym.domain

import com.simplegym.data.GymDatabase
import com.simplegym.data.toRecord
import com.simplegym.engine.Exercise
import com.simplegym.engine.HurtEvent
import com.simplegym.engine.Joint
import com.simplegym.engine.activeHurts
import com.simplegym.engine.jointCaution
import com.simplegym.engine.jointFriendlySwap
import com.simplegym.engine.orderSwaps
import java.time.LocalDate

/** Hurt flags (spec 6.9) read from the logs: the per-variant counts, the joint caution and the swap to suggest. */
class HurtService(private val db: GymDatabase) {

    /** The active hurt counts per joint of every exercise that has any, by exercise id. */
    suspend fun activeHurts(): Map<String, Map<Joint, Int>> {
        val ids = db.logDao().getHurtLogs().map { it.exerciseId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val cleared = clearedOn()
        val logs = db.logDao().getForExercises(ids).groupBy { it.exerciseId }
        return ids.associateWith { id -> activeHurts(logs[id].orEmpty().map { it.toRecord() }, cleared[id]) }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * The joint [exerciseId] is flagged for on [today] because a different exercise hurt it in the last 14 days, or
     * null. A hurt flag the lifter has cleared on its own exercise no longer counts.
     */
    suspend fun cautionFor(exerciseId: String, today: LocalDate): Joint? {
        val exercise = db.libraryDao().get(exerciseId)?.toEngine(emptyMap()) ?: return null
        val events = otherHurts(exerciseId)
        if (events.none { it.joint in exercise.joints }) return null
        val own = db.logDao().getForExercise(exerciseId).map { it.toRecord() }
        return jointCaution(exercise, events, own, today)
    }

    /** The hurt flags of every exercise but [exerciseId], oldest first, without the ones the lifter has cleared. */
    suspend fun otherHurts(exerciseId: String): List<HurtEvent> {
        val cleared = clearedOn()
        return db.logDao().getHurtLogs()
            .filter { it.exerciseId != exerciseId && it.date.isAfter(cleared[it.exerciseId] ?: LocalDate.MIN) }
            .map { HurtEvent(it.exerciseId, checkNotNull(it.hurt), it.date) }
    }

    /**
     * The joint-friendly alternative to [variantId], which hurt [joint]. Both lists are cleaned the same way before
     * [jointFriendlySwap] chooses (lower-back protection, club availability, archived, hurt-flagged last): the slot's
     * default and swaps, and the rest of the slot's swap group.
     */
    suspend fun friendlySwap(plan: SwapPlan, variantId: String, joint: Joint): Exercise? {
        val swaps = orderSwaps((listOf(plan.default) + plan.swaps).filter { it.id != variantId }, plan.ctx)
        val increments = db.settingsDao().get()?.equipmentIncrements ?: emptyMap()
        val group = orderSwaps(
            db.libraryDao().getAll()
                .filter { !it.archived && it.swapGroup == plan.default.swapGroup && it.id != variantId }
                .map { it.toEngine(increments) },
            plan.ctx,
        )
        return jointFriendlySwap(joint, swaps, group)
    }

    private suspend fun clearedOn(): Map<String, LocalDate> =
        db.libraryDao().getAll().mapNotNull { e -> e.hurtClearedOn?.let { e.id to it } }.toMap()
}
