package com.simplegym.ui.mobility

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.data.GymDatabase
import com.simplegym.domain.CheckInService
import com.simplegym.domain.MobilityRecorder
import com.simplegym.domain.toEngine
import com.simplegym.engine.Chip
import com.simplegym.engine.MobilityArea
import com.simplegym.engine.buildRoutine
import com.simplegym.engine.neglectedOrder
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerRun
import com.simplegym.timer.TimerUiState
import com.simplegym.ui.components.displayName
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A stiff chip that points at a mobility area; the other chips are muscles the add-on does not target. */
private val STIFF_AREAS = mapOf(Chip.LOWER_BACK to MobilityArea.LOWER_BACK, Chip.NECK to MobilityArea.NECK)

/** The hint names the most neglected area unless it was covered in the last few days. */
private const val NEGLECT_DAYS = 3L
private const val HISTORY_DAYS = 30L
private const val UI_TICK_MS = 250L
private const val STOP_TIMEOUT_MS = 5_000L

data class AreaUi(val area: MobilityArea, val name: String, val selected: Boolean, val stiff: Boolean)

/**
 * [areas] are in the order the routine is built: areas tapped stiff today first. [state] is this screen's own run
 * (idle otherwise) and [moveName] / [nextMoveName] name the move being held and the one after it. [finished] is set
 * once a run has ended and its areas are marked done.
 */
data class MobilityUi(
    val areas: List<AreaUi>,
    val hint: String?,
    val state: TimerUiState,
    val moveName: String?,
    val nextMoveName: String?,
    val finished: Boolean,
) {
    val canStart: Boolean get() = areas.any { it.selected }
}

class MobilityViewModel(
    private val appContext: Context,
    private val db: GymDatabase,
    private val checkIns: CheckInService,
    private val recorder: MobilityRecorder,
    private val seeded: Deferred<Unit>,
    private val sessionId: Long?,
    private val today: () -> LocalDate,
) : ViewModel() {

    /** Today's stiff areas, read once: a later check-in change must not undo what the lifter has tapped here. */
    private val stiffAreas = MutableStateFlow<Set<MobilityArea>>(emptySet())

    /** Null until the lifter taps an area; until then the stiff areas are the selection. */
    private val picked = MutableStateFlow<Set<MobilityArea>?>(null)
    private val finished = MutableStateFlow(false)
    private var handledStart: Long? = null

    /** A run that ended before this screen opened: it is already recorded elsewhere, so it must not send the lifter back. */
    private val endedBefore = TimerController.state.value.run
        ?.takeIf { TimerController.state.value.finished && it.isMobility }?.startElapsedMs

    // flow { } so that "today" is read each time the screen starts collecting
    private val lastDone = flow { emitAll(db.sessionDao().observeMobilitySince(today().minusDays(HISTORY_DAYS))) }
        .map { rows ->
            rows.mapNotNull { row -> MobilityArea.entries.firstOrNull { it.name == row.area }?.let { it to row.date } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, dates) -> dates.max() }
        }

    val ui: StateFlow<MobilityUi> =
        combine(stiffAreas, picked, lastDone, TimerController.state, finished) { stiff, chosen, done, timer, over ->
            val selected = chosen ?: stiff
            val order = displayOrder(stiff)
            val mine = timer.run?.takeIf { it.isMobility }
            val round = timer.timer?.round
            MobilityUi(
                areas = order.map { AreaUi(it, it.displayName(), it in selected, it in stiff) },
                hint = hintFor(done),
                state = if (mine != null) timer else TimerUiState.IDLE,
                moveName = if (mine != null && round != null) mine.moveNames.getOrNull(round - 1) else null,
                nextMoveName = if (mine != null && round != null) mine.moveNames.getOrNull(round) else null,
                finished = over,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            MobilityUi(emptyList(), hint = null, state = TimerUiState.IDLE, moveName = null, nextMoveName = null, finished = false),
        )

    init {
        viewModelScope.launch {
            seeded.await()
            stiffAreas.value = checkIns.today(today()).first().mapNotNull { STIFF_AREAS[it] }.toSet()
        }
        viewModelScope.launch {
            TimerController.state.collect { state ->
                val run = state.run
                if (state.finished && run != null && run.isMobility) recordFinished(run)
            }
        }
        viewModelScope.launch {
            // Keeps this screen right even if the service is not ticking.
            while (isActive) {
                delay(UI_TICK_MS)
                if (TimerController.state.value.running) TimerController.refresh(appContext)
            }
        }
    }

    /** Areas tapped stiff today first, the rest in area order. */
    private fun displayOrder(stiff: Set<MobilityArea>) = MobilityArea.entries.sortedBy { it !in stiff }

    private fun hintFor(lastDone: Map<MobilityArea, LocalDate>): String? {
        val now = today()
        val top = neglectedOrder(lastDone, now).first()
        val last = lastDone[top]
        if (last != null && ChronoUnit.DAYS.between(last, now) < NEGLECT_DAYS) return null
        return "Not done lately: ${top.displayName()}"
    }

    fun toggle(area: MobilityArea) {
        val current = picked.value ?: stiffAreas.value
        picked.value = if (area in current) current - area else current + area
    }

    /** Builds the routine from the chosen areas (stiff ones first) and starts it as an interval run. */
    fun start() {
        viewModelScope.launch {
            seeded.await()
            val chosen = picked.value ?: stiffAreas.value
            val areas = displayOrder(stiffAreas.value).filter { it in chosen }
            val library = db.libraryDao().getActiveInGroups(areas.flatMap { it.swapGroups }).map { it.toEngine(emptyMap()) }
            val routine = buildRoutine(areas, library)
            if (routine.moves.isEmpty()) return@launch
            TimerController.start(
                appContext,
                TimerRun(
                    kind = TimerRun.INTERVAL, rounds = routine.moves.size, roundSec = 0,
                    workSec = routine.workSec, restSec = routine.restSec,
                    startElapsedMs = SystemClock.elapsedRealtime(), pausedAtElapsedMs = null, pausedTotalMs = 0,
                    sessionId = sessionId, slotId = null, exerciseId = null,
                    mobilityAreas = areas.filter { a -> routine.moves.any { it.swapGroup in a.swapGroups } }.map { it.name },
                    moveNames = routine.moves.map { it.name },
                ),
            )
        }
    }

    /**
     * The routine ran to its end: its areas are marked done (once per run, see [MobilityRecorder]), then the run is
     * acknowledged. Only a run that ended while this screen was open sends the lifter back.
     */
    private fun recordFinished(run: TimerRun) {
        if (handledStart == run.startElapsedMs) return
        handledStart = run.startElapsedMs
        viewModelScope.launch {
            recorder.record(run)
            TimerController.acknowledgeFinished()
            if (run.startElapsedMs != endedBefore) finished.value = true
        }
    }

    fun pause() = TimerController.pause(appContext)

    fun resume() = TimerController.resume(appContext)

    /** Stopping early marks nothing done. */
    fun stop() = TimerController.stop(appContext)
}
