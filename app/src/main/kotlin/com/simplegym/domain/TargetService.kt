package com.simplegym.domain

import com.simplegym.data.ExerciseEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SlotEntity
import com.simplegym.data.toRecord
import com.simplegym.engine.Chip
import com.simplegym.engine.CoachInput
import com.simplegym.engine.Exercise
import com.simplegym.engine.RangeType
import com.simplegym.engine.RepRange
import com.simplegym.engine.SlotPlan
import com.simplegym.engine.SwapContext
import com.simplegym.engine.Target
import com.simplegym.engine.WeightHint
import com.simplegym.engine.ageRules
import com.simplegym.engine.coachTarget
import com.simplegym.engine.formatKg
import com.simplegym.engine.isBaseline
import com.simplegym.engine.orderSwaps
import com.simplegym.engine.plateauCount
import com.simplegym.engine.suggestedVariant
import java.time.LocalDate

private const val NEW_LABEL = "new"
private const val BODYWEIGHT_LABEL = "BW"

/** Used until the lifter has entered an age (the 40-54 band). */
private const val DEFAULT_AGE = 45

/** Targets (spec §6): gathers one exercise's history, block, hurts, check-in and settings for the engine's coach. */
class TargetService(private val db: GymDatabase) {
    private val blocks = BlockService(db)
    private val hurts = HurtService(db)

    /**
     * Today's target for [exerciseId] in [slotId]: the slot's plan and the exercise's logs, the swap group's last
     * training date (imported logs included), the block, active hurts, today's stiff chips and the slot's flush
     * switch in the open session, all handed to [coachTarget].
     */
    suspend fun targetFor(slotId: Long, exerciseId: String, today: LocalDate): Target {
        val slot = checkNotNull(db.planDao().getSlot(slotId)) { "No slot $slotId" }
        val entity = checkNotNull(db.libraryDao().get(exerciseId)) { "No exercise $exerciseId" }
        val history = db.logDao().getForExercise(exerciseId).map { it.toRecord() }
        val settings = db.settingsDao().get()
        return coachTarget(
            CoachInput(
                exercise = entity.toEngine(settings?.equipmentIncrements ?: emptyMap()),
                slot = SlotPlan(slot.sets, RepRange(slot.lo, slot.hi), slot.roundSec),
                history = history,
                hurtClearedOn = entity.hurtClearedOn,
                movementLastTrained = movementLastTrained(entity),
                otherHurts = hurts.otherHurts(exerciseId),
                sameEquipmentHint = if (history.any { it.isBaseline }) null else coldStartHint(entity),
                block = blocks.current(today),
                rules = ageRules(settings?.age ?: DEFAULT_AGE),
                stiffMuscle = isStiff(entity, today),
                flush = db.sessionDao().countOpenFlush(slotId, today) > 0,
                today = today,
            ),
        )
    }

    /** Whether today's check-in has a stiff chip covering the muscle [exerciseId] trains. */
    suspend fun stiffMuscle(exerciseId: String, today: LocalDate): Boolean =
        db.libraryDao().get(exerciseId)?.let { isStiff(it, today) } == true

    private suspend fun isStiff(entity: ExerciseEntity, today: LocalDate): Boolean =
        db.sessionDao().getCheckIn(today)?.stiff?.any { entity.muscle in it.muscles } == true

    /** The latest log date over every exercise in [entity]'s swap group (the movement), imported logs included. */
    private suspend fun movementLastTrained(entity: ExerciseEntity): LocalDate? {
        val ids = db.libraryDao().getAll().filter { it.swapGroup == entity.swapGroup }.map { it.id }
        return db.logDao().getForExercises(ids).maxOfOrNull { it.date }
    }

    /** Last baseline weight per exercise for the swap chips; null means "new" (no usable history). */
    suspend fun swapChipWeights(exerciseIds: List<String>): Map<String, Double?> =
        lastBaselines(exerciseIds).mapValues { it.value?.weightKg }

    /**
     * What each swap chip shows: the last baseline weight ("62.5", "−70"), "BW" for a bodyweight variant that has a
     * baseline but no weight, and "new" only when there is no baseline at all ([swapChipWeights] can't tell those apart).
     */
    suspend fun swapChipLabels(exerciseIds: List<String>): Map<String, String> =
        lastBaselines(exerciseIds).mapValues { (_, baseline) ->
            when {
                baseline == null -> NEW_LABEL
                baseline.weightKg == null -> BODYWEIGHT_LABEL
                else -> formatKg(baseline.weightKg)
            }
        }

    /**
     * The slot's default and swaps (archived ones left out, as listed in the plan) with the context that decides what
     * is offered in [sessionId]: the session's club, lower-back protection and how often each swap was used here.
     * Today's check-in feeds stiffNeck and stiffLowerBack, so Workout's ▶ and the Exercise screen agree; exercises with
     * an active hurt flag sort last. A slot
     * rotated for the block that [today] falls in starts from the rotated variant (see [resolveVariant]).
     */
    suspend fun swapPlan(sessionId: Long, slotId: Long, today: LocalDate): SwapPlan {
        val slot = checkNotNull(db.planDao().getSlot(slotId)) { "No slot $slotId" }
        val session = checkNotNull(db.sessionDao().getSession(sessionId)) { "No session $sessionId" }
        val library = db.libraryDao().getAll().associateBy { it.id }
        val planDefault = checkNotNull(library[slot.defaultExerciseId]) { "No exercise ${slot.defaultExerciseId}" }
        val planSwaps = db.planDao().getSwaps(slotId).mapNotNull { library[it.exerciseId] }.filterNot { it.archived }
        val rotated = activeRotation(slot, today)?.let { library[it] }?.takeIf { !it.archived && it.id != planDefault.id }
        // A rotation makes its variant the slot's default for the block; the plan default becomes a swap in its place.
        val default = rotated ?: planDefault
        val swaps = if (rotated == null) planSwaps else listOf(planDefault) + planSwaps.filter { it.id != rotated.id }
        val increments = equipmentIncrements()
        val atClub = session.clubId?.let { db.clubDao().getUnavailable(it) }.orEmpty()
        val archivedDefault = listOf(default.id).filter { default.archived }
        val stiff = db.sessionDao().getCheckIn(today)?.stiff.orEmpty()
        val ctx = SwapContext(
            lowerBackProtection = db.settingsDao().get()?.lowerBackProtection == true,
            unavailable = (atClub + archivedDefault).toSet(),
            useCounts = db.logDao().useCountsForSlot(slotId).associate { it.exerciseId to it.uses },
            hurtFlagged = hurts.activeHurts().keys,
            stiffNeck = Chip.NECK in stiff,
            stiffLowerBack = Chip.LOWER_BACK in stiff,
        )
        return SwapPlan(default.toEngine(increments), swaps.map { it.toEngine(increments) }, ctx)
    }

    /**
     * The variant a slot runs today: [suggestedVariant] over the slot's swaps, starting from the block's rotated
     * variant when the slot has one. The Workout row's "▶ Start timer" and the Exercise screen's initial variant both
     * come from here, so they always agree.
     */
    suspend fun resolveVariant(sessionId: Long, slotId: Long, today: LocalDate): String =
        swapPlan(sessionId, slotId, today).suggested.id

    /**
     * How many sessions in a row [exerciseId] has stalled in [rangeType] ([plateauCount] over its baseline logs).
     * Each variant and range type counts on its own, so a rotated variant starts from its own history.
     */
    suspend fun plateauFor(exerciseId: String, rangeType: RangeType): Int =
        plateauCount(
            db.logDao().getForExercise(exerciseId).map { it.toRecord() }.filter { it.isBaseline && it.rangeType == rangeType },
        )

    /** The rotated variant while its block is the current one; a rotation from an earlier block no longer applies. */
    private suspend fun activeRotation(slot: SlotEntity, today: LocalDate): String? {
        val index = slot.rotationBlockIndex ?: return null
        val exerciseId = slot.rotationExerciseId ?: return null
        return exerciseId.takeIf { blocks.current(today).blockIndex == index }
    }

    private suspend fun lastBaselines(exerciseIds: List<String>): Map<String, LogEntity?> {
        if (exerciseIds.isEmpty()) return emptyMap()
        val byExercise = db.logDao().getForExercises(exerciseIds).groupBy { it.exerciseId }
        return exerciseIds.associateWith { id -> byExercise[id]?.let(::lastBaseline) }
    }

    private suspend fun coldStartHint(entity: ExerciseEntity): WeightHint? {
        val siblings = db.libraryDao().getAll()
            .filter { it.id != entity.id && it.swapGroup == entity.swapGroup && it.equipment == entity.equipment }
        if (siblings.isEmpty()) return null
        val names = siblings.associate { it.id to it.name }
        val last = db.logDao().getForExercises(siblings.map { it.id })
            .lastOrNull { it.toRecord().isBaseline && it.weightKg != null } ?: return null
        return WeightHint(names.getValue(last.exerciseId), checkNotNull(last.weightKg))
    }

    private suspend fun equipmentIncrements(): Map<String, Double> =
        db.settingsDao().get()?.equipmentIncrements ?: emptyMap()

    /** [logs] must be ordered by date then id (insertion), as the DAO queries return them. */
    private fun lastBaseline(logs: List<LogEntity>): LogEntity? = logs.lastOrNull { it.toRecord().isBaseline }
}

/** A slot's [default] and its [swaps] (plan order, not yet filtered or sorted) under the swap context [ctx]. */
class SwapPlan(val default: Exercise, val swaps: List<Exercise>, val ctx: SwapContext) {
    /** The swaps worth offering: unavailable and (with protection on) high back-load ones dropped, best first. */
    val ordered: List<Exercise> by lazy { orderSwaps(swaps, ctx) }

    /** Where the slot starts today. */
    val suggested: Exercise get() = suggestedVariant(default, swaps, ctx)

    /** Whether the default itself may be offered (it is not unavailable, archived or filtered out for the back). */
    val defaultVisible: Boolean get() = orderSwaps(listOf(default), ctx).isNotEmpty()
}
