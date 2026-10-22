package com.simplegym.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

const val COLD_START_REASON = "New: start light, with a weight you could lift about 15 times."

private const val FLUSH_FRACTION = 0.55
private const val DROP_FRACTION = 0.10
private const val FLUSH_ROUND_SEC = 60
private const val FLUSH_FULL_SETS = 3
private const val FLUSH_SHORT_SETS = 2
private const val DELOAD_MIN_SETS = 2
private const val PERCENT = 100
private val FLUSH_RANGE = RepRange(15, 20)

private const val FLUSH_REASON = "Flush: light weight, high reps to get blood into a stiff muscle."
private const val DELOAD_REASON = "Deload round: lighter so your joints catch up."
private const val HURT_REASON = "This hurt last time: one step lighter."

/** A slot as the plan has it: its [sets], its base rep range and the round time of its timer. */
data class SlotPlan(val sets: Int, val baseRange: RepRange, val roundSec: Int)

/**
 * Everything [coachTarget] needs, assembled by the caller. [history] is the exercise's own logs, oldest first, hurt
 * and non-baseline ones included. [otherHurts] are hurt events of other exercises (already without cleared ones),
 * [movementLastTrained] is the latest log date over the exercise's whole swap group.
 */
data class CoachInput(
    val exercise: Exercise,
    val slot: SlotPlan,
    val history: List<LogRecord>,
    val hurtClearedOn: LocalDate?,
    val movementLastTrained: LocalDate?,
    val otherHurts: List<HurtEvent>,
    val sameEquipmentHint: WeightHint?,
    val block: BlockState,
    val rules: AgeRules,
    val stiffMuscle: Boolean,
    val flush: Boolean,
    val today: LocalDate,
)

/**
 * Today's target for one exercise in one slot (spec §6). In order: the range type for the block; the baseline (the
 * latest of either range type, converted to the top of this block's range when it is the other kind); cold start when
 * there is none; flush when the lifter chose it; the normal candidate (progression, or a 10% drop on a plateau of this
 * range type); the modifier candidates (deload round, stiff muscle, layoff, active hurt, joint caution); and the
 * single most conservative of them. A bodyweight lift keeps a null weight throughout: deload changes only the sets,
 * and every other modifier holds the reps.
 */
fun coachTarget(input: CoachInput): Target {
    val ex = input.exercise
    val slot = input.slot
    val rules = input.rules
    val rt = if (rules.alternateRanges && alternationApplies(slot.baseRange, ex)) {
        rangeTypeForBlock(input.block.blockIndex, true)
    } else {
        RangeType.BASE
    }
    val range = effectiveRange(slot.baseRange, rt, ex)

    val baselines = input.history.filter { it.isBaseline }
    val latest = baselines.lastOrNull()
        ?: return Target(
            weightKg = null,
            sets = slot.sets,
            range = range,
            repGoal = null,
            type = SessionType.NORMAL,
            reason = COLD_START_REASON,
            hint = input.sameEquipmentHint,
            rangeType = rt,
            roundSec = slot.roundSec,
        )

    val step = ex.incrementKg.takeIf { !ex.isBodyweight }
    val assisted = ex.isAssisted
    val base = if (latest.rangeType == rt || step == null || latest.weightKg == null) {
        latest
    } else {
        latest.copy(
            weightKg = convertWeight(latest.weightKg, latest.bestReps, range.hi, step),
            bestReps = range.lo,
            feel = Feel.SOLID,
            type = SessionType.NORMAL,
            rangeType = rt,
        )
    }
    // null for a bodyweight lift (or one with no increment): every weight below then stays null
    val w = base.weightKg.takeIf { step != null }

    fun reduced(fraction: Double): Double? = if (w != null && step != null) reduceBy(w, fraction, step, assisted) else null

    if (input.flush) {
        return Target(
            weightKg = reduced(FLUSH_FRACTION),
            sets = if (slot.sets >= FLUSH_FULL_SETS) FLUSH_FULL_SETS else FLUSH_SHORT_SETS,
            range = FLUSH_RANGE,
            repGoal = null,
            type = SessionType.FLUSH,
            reason = FLUSH_REASON,
            rangeType = rt,
            roundSec = FLUSH_ROUND_SEC,
        )
    }

    val plateaued = plateauCount(baselines.filter { it.rangeType == rt }) >= PLATEAU_LIMIT
    val progressed = if (plateaued) null else progress(base, range, ex, rules.maxStepsPerSession)
    val normal = when {
        progressed != null -> Candidate(progressed.weightKg, slot.sets, SessionType.NORMAL, progressed.reason)
        w != null -> Candidate(reduced(DROP_FRACTION), slot.sets, SessionType.NORMAL, plateauReason(true))
        // NORMAL, not HELD: a held log is never a baseline, so the plateau count could never reset
        else -> Candidate(null, slot.sets, SessionType.NORMAL, plateauReason(false))
    }

    val modifiers = buildList {
        if (input.block.isDeloadRound) {
            add(
                Candidate(
                    reduced(DROP_FRACTION), maxOf(DELOAD_MIN_SETS, slot.sets - 1), SessionType.DELOAD, DELOAD_REASON,
                ),
            )
        }
        if (input.stiffMuscle) {
            val reason = if (w != null) {
                "Stiff today: holding at ${formatKg(w)}kg with a longer warm-up."
            } else {
                "Stiff today: holding your reps with a longer warm-up."
            }
            add(Candidate(w, slot.sets, SessionType.HELD, reason))
        }
        input.movementLastTrained?.let { last ->
            val days = ChronoUnit.DAYS.between(last, input.today)
            layoffFraction(days)?.let { fraction ->
                val eased = "${(fraction * PERCENT).roundToInt()}%"
                add(
                    if (w != null) {
                        Candidate(reduced(fraction), slot.sets, SessionType.RETURN, "Back after $days days: easing in at −$eased.")
                    } else {
                        Candidate(null, slot.sets, SessionType.HELD, "Back after $days days: holding your reps, no increase.")
                    },
                )
            }
        }
        if (activeHurts(input.history, input.hurtClearedOn).isNotEmpty()) {
            add(
                if (w != null && step != null) {
                    Candidate(stepDown(w, step, assisted), slot.sets, SessionType.HELD, HURT_REASON)
                } else {
                    Candidate(null, slot.sets, SessionType.HELD, "This hurt last time: holding your reps, no increase.")
                },
            )
        }
        jointCaution(ex, input.otherHurts, input.history, input.today)?.let { joint ->
            add(Candidate(w, slot.sets, SessionType.HELD, "${joint.label()} flagged recently: holding."))
        }
    }

    val best = mostConservative(listOf(normal) + modifiers)
    // a modifier that ties the normal candidate on weight and sets wins: it holds the lifter back, and names why
    val winner = if (best !== normal) {
        best
    } else {
        modifiers.firstOrNull { it.weightKg == normal.weightKg && it.sets == normal.sets } ?: normal
    }
    val repGoal = when {
        // a bodyweight plateau hold has no progression, so it holds the best reps
        winner.type == SessionType.NORMAL -> if (progressed != null) progressed.repGoal else base.bestReps.takeIf { w == null }
        winner.type == SessionType.HELD && w == null -> base.bestReps
        else -> null
    }
    return Target(
        weightKg = winner.weightKg,
        sets = winner.sets,
        range = range,
        repGoal = repGoal,
        type = winner.type,
        reason = winner.reason,
        rangeType = rt,
        roundSec = slot.roundSec,
    )
}

private fun plateauReason(weighted: Boolean): String =
    if (weighted) {
        "Plateau: $PLATEAU_LIMIT sessions without progress, so dropping 10% to build back up."
    } else {
        "Plateau: $PLATEAU_LIMIT sessions without progress, so holding your reps to build back up."
    }

/** "Shoulder", "Lower back". */
private fun Joint.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
