package com.simplegym.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.data.ExerciseEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SessionSlotEntity
import com.simplegym.data.SlotEntity
import com.simplegym.data.SlotStatus
import com.simplegym.domain.HurtService
import com.simplegym.domain.SessionService
import com.simplegym.domain.TargetService
import com.simplegym.engine.Chip
import com.simplegym.engine.formatKg
import com.simplegym.timer.TimerRunStore
import com.simplegym.ui.components.PendingLogUi
import com.simplegym.ui.components.hurtBadgeLabels
import com.simplegym.ui.components.toUi
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [summary] is "60" once done, "3 × 8–12 @ 62.5" (or "@ new") while pending and "skipped · come back" when skipped. */
data class SlotRowUi(
    val slotId: Long,
    val exerciseName: String,
    val status: SlotStatus,
    val summary: String,
    val isNext: Boolean,
    /** The variant "▶ Start timer" runs, and the one named and targeted in the row: [TargetService.resolveVariant]. */
    val timerExerciseId: String,
    /** Amber labels when the exercise shown has an active hurt flag, e.g. "⚠ Shoulder · 2×". */
    val hurtBadges: List<String> = emptyList(),
    /** An optional flush finisher: listed with the plan rows but counted in none of the totals. */
    val isFinisher: Boolean = false,
    /** The slot was deleted from the plan after this session started: the row stays but there is nothing to open. */
    val isRemoved: Boolean = false,
)

/**
 * [doneCount] counts the plan's DONE rows and [total] is the slot count the session started with, so deleting or adding
 * a plan slot mid-session never moves them. [finisherOffers] are today's stiff chips this day does not train
 * and that have no finisher yet: each is an optional "+ Flush finisher" row.
 */
data class WorkoutState(
    val dayName: String,
    val rows: List<SlotRowUi>,
    val nextSlotId: Long?,
    val doneCount: Int,
    val total: Int,
    val finisherOffers: List<Chip> = emptyList(),
)

private data class Inputs(
    val plan: List<SlotEntity>,
    val finishers: List<SlotEntity>,
    val states: List<SessionSlotEntity>,
    val logs: List<LogEntity>,
    val exercises: List<ExerciseEntity>,
)

/**
 * Everything shown is derived from the database (session slots, logs, plan), so a session killed by process death
 * comes back exactly as it was left.
 */
class WorkoutViewModel(
    private val db: GymDatabase,
    private val sessions: SessionService,
    private val targets: TargetService,
    private val sessionId: Long,
    private val today: () -> LocalDate,
    private val timerStore: TimerRunStore,
) : ViewModel() {

    private val hurts = HurtService(db)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<WorkoutState> = flow { emit(db.sessionDao().getSession(sessionId)) }
        .flatMapLatest { session ->
            if (session == null) return@flatMapLatest flowOf(EMPTY)
            val day = db.planDao().getDay(session.dayId)
            val open = session.finishedAtMillis == null
            combine(
                combine(
                    db.planDao().observeSlots(session.dayId),
                    db.planDao().observeSessionOnlySlots(sessionId),
                    sessions.slotStates(sessionId),
                    db.logDao().observeForSession(sessionId),
                    db.libraryDao().observeAll(),
                    ::Inputs,
                ),
                // flow { } so that "today" is read when collecting starts
                flow { emitAll(db.sessionDao().observeCheckIn(today())) },
            ) { (plan, finishers, states, logs, exercises), checkIn ->
                // The session's own slot list, not the plan's: a slot added to the plan mid-session is not in it.
                val statusBySlot = states.associate { it.slotId to it.status }
                val library = exercises.associateBy { it.id }
                val hurtCounts = hurts.activeHurts()
                val planned = plan.filter { it.id in statusBySlot }
                val extras = finishers.filter { it.id in statusBySlot }
                val nextSlotId = nextSlot((planned + extras).map { it.id }, statusBySlot)
                val liveRows = (planned + extras).map { slot ->
                    val status = statusBySlot.getValue(slot.id)
                    val log = logs.filter { it.slotId == slot.id }.maxByOrNull { it.id }
                    // What the row shows is what opens: a done row its logged variant, any other the resolved one.
                    val shown = if (status == SlotStatus.DONE && log != null) {
                        log.exerciseId
                    } else {
                        targets.resolveVariant(sessionId, slot.id, today())
                    }
                    SlotRowUi(
                        slotId = slot.id,
                        exerciseName = library[shown]?.name ?: shown,
                        status = status,
                        summary = when (status) {
                            SlotStatus.DONE -> loggedWeight(log)
                            SlotStatus.SKIPPED -> SKIPPED_SUMMARY
                            SlotStatus.PENDING -> {
                                val target = targets.targetFor(slot.id, shown, today())
                                val weight = target.weightKg?.let(::formatKg) ?: "new"
                                "${target.sets} × ${target.range.lo}–${target.range.hi} @ $weight"
                            }
                        },
                        isNext = slot.id == nextSlotId,
                        timerExerciseId = shown,
                        hurtBadges = hurtBadgeLabels(hurtCounts[shown].orEmpty()),
                        isFinisher = slot.sessionOnlyFor != null,
                    )
                }
                // Slots deleted from the plan since the session started: their rows (and the count) stay.
                val removedRows = removedRows(states, (plan + finishers).map { it.id }.toSet(), logs, library)
                // Plan rows (live, then removed) before the finishers, which stay last.
                val rows = liveRows.filterNot { it.isFinisher } + removedRows + liveRows.filter { it.isFinisher }
                val planRows = rows.filterNot { it.isFinisher }
                val trained = day?.chips.orEmpty()
                val finishedMuscles = extras.mapNotNull { library[it.defaultExerciseId]?.muscle }.toSet()
                WorkoutState(
                    dayName = day?.name.orEmpty(),
                    rows = rows,
                    nextSlotId = nextSlotId,
                    doneCount = planRows.count { it.status == SlotStatus.DONE },
                    total = session.slotsTotal,
                    finisherOffers = if (!open || day == null) {
                        emptyList()
                    } else {
                        checkIn?.stiff.orEmpty().filter { chip ->
                            chip.muscles.isNotEmpty() && chip !in trained && chip.muscles.none { it in finishedMuscles }
                        }.sorted()
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EMPTY)

    /** A set of this session whose timer ended unseen and is still to be logged: "Log <exercise>" until saved or dismissed. */
    val pendingLog: StateFlow<PendingLogUi?> = timerStore.observePendingLog()
        .map { pending -> pending?.takeIf { it.sessionId == sessionId }?.toUi(db) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun dismissPendingLog() = timerStore.clearPendingLog()

    private var finishing = false

    /** Finishes the session (leftover PENDING slots become SKIPPED), then reports back on the main thread. */
    fun finish(onFinished: () -> Unit) {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            try {
                sessions.finish(sessionId)
                onFinished()
            } finally {
                finishing = false
            }
        }
    }

    /** One tap on "+ Flush finisher: <chip>": adds the optional slot (see [SessionService.addFlushFinisher]). */
    fun addFinisher(chip: Chip) {
        viewModelScope.launch { sessions.addFlushFinisher(sessionId, chip) }
    }

    /** The first PENDING slot in position order; with none pending, the first SKIPPED one. */
    private fun nextSlot(orderedSlotIds: List<Long>, status: Map<Long, SlotStatus>): Long? =
        orderedSlotIds.firstOrNull { status[it] == SlotStatus.PENDING }
            ?: orderedSlotIds.firstOrNull { status[it] == SlotStatus.SKIPPED }

    /**
     * Rows for the session's slots whose plan slot is gone. A deleted slot's logs lost their slot id, but each keeps
     * [LogEntity.loggedSlotId], so a DONE row is named and weighted from its own latest log whatever the order the slots
     * were done or deleted in. A slot with no log to name it is "Removed from plan". None can be next, timed or opened.
     */
    private fun removedRows(
        states: List<SessionSlotEntity>,
        liveIds: Set<Long>,
        logs: List<LogEntity>,
        library: Map<String, ExerciseEntity>,
    ): List<SlotRowUi> {
        val removed = states.filter { it.slotId !in liveIds }.sortedBy { it.slotId }
        val logsForDone = removed.filter { it.status == SlotStatus.DONE }.mapNotNull { state ->
            logs.filter { it.loggedSlotId == state.slotId }.maxByOrNull { it.id }?.let { state.slotId to it }
        }.toMap()
        return removed.map { state ->
            val log = logsForDone[state.slotId]
            SlotRowUi(
                slotId = state.slotId,
                exerciseName = log?.let { library[it.exerciseId]?.name ?: it.exerciseId } ?: REMOVED_NAME,
                status = state.status,
                summary = when (state.status) {
                    SlotStatus.DONE -> log?.let(::loggedWeight) ?: REMOVED_NAME.lowercase()
                    else -> REMOVED_NAME.lowercase()
                },
                isNext = false,
                timerExerciseId = log?.exerciseId.orEmpty(),
                isRemoved = true,
            )
        }
    }

    private fun loggedWeight(log: LogEntity?): String = log?.weightKg?.let(::formatKg) ?: "BW"

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val REMOVED_NAME = "Removed from plan"
        const val SKIPPED_SUMMARY = "skipped · come back"
        val EMPTY = WorkoutState(dayName = "", rows = emptyList(), nextSlotId = null, doneCount = 0, total = 0)
    }
}
