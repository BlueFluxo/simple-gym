package com.simplegym.ui.timer

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.data.GymDatabase
import com.simplegym.data.SlotEntity
import com.simplegym.domain.TargetService
import com.simplegym.engine.EmomConfig
import com.simplegym.engine.SessionType
import com.simplegym.engine.Target
import com.simplegym.engine.adjustRoundSec
import com.simplegym.engine.adjustRounds
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerRun
import com.simplegym.timer.TimerRunStore
import com.simplegym.timer.TimerUiState
import com.simplegym.timer.emom
import com.simplegym.timer.pendingLog
import com.simplegym.ui.nav.LogRoute
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val STANDALONE_ROUNDS = 3
private const val STANDALONE_ROUND_SEC = 120
private const val FLUSH_ROUND_SEC = 60
private const val UI_TICK_MS = 250L

/**
 * The EMOM a slot starts with. Only a NORMAL session remembers the lifter's last adjustment: DELOAD, HELD and RETURN
 * use the target's own sets, and FLUSH is always 60 s rounds.
 */
fun timerDefaults(slot: SlotEntity, target: Target): EmomConfig {
    val rounds = if (target.type == SessionType.NORMAL) slot.timerRounds ?: target.sets else target.sets
    val roundSec = if (target.type == SessionType.FLUSH) FLUSH_ROUND_SEC else slot.timerRoundSec ?: target.roundSec
    return EmomConfig(rounds, roundSec)
}

/**
 * Finishing (or Stop) opens Log only for an EMOM run that belongs to a slot; interval (mobility) and standalone runs
 * just return.
 */
fun logRouteFor(run: TimerRun): LogRoute? = run.pendingLog()?.let { LogRoute(it.sessionId, it.slotId, it.exerciseId) }

/**
 * What a timer screen that was only restored (process death) does instead of starting a run: open the log of the run
 * that ended while the app was away ([log]), or just leave when there is none.
 */
data class RestoredExit(val log: LogRoute?)

/**
 * [finishedRun] is set once this screen's own run has ended and not yet been handled. [exit] is set when the screen
 * was restored with no run to attach to.
 */
data class TimerScreenUi(
    val title: String,
    val started: Boolean,
    val state: TimerUiState,
    val finishedRun: TimerRun?,
    val exit: RestoredExit? = null,
)

class TimerViewModel(
    private val appContext: Context,
    private val db: GymDatabase,
    private val targets: TargetService,
    private val sessionId: Long?,
    private val slotId: Long?,
    private val exerciseId: String?,
    private val today: () -> LocalDate,
    /** The nav entry's saved state: its `startNew` argument is used up by the first run this screen starts. */
    private val handle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val title = MutableStateFlow("Timer")
    private val started = MutableStateFlow(false)
    private val exit = MutableStateFlow<RestoredExit?>(null)
    private val slotWrites = Mutex()

    /** Only a NORMAL-type slot run writes its adjustments back to the slot. */
    private var targetType: SessionType? = null

    val ui: StateFlow<TimerScreenUi> = combine(title, started, exit, TimerController.state) { name, isStarted, leaving, state ->
        val mine = isStarted && state.run?.let(::isMine) == true
        TimerScreenUi(
            title = name,
            started = isStarted,
            state = if (mine) state else TimerUiState.IDLE,
            finishedRun = state.run.takeIf { mine && state.finished },
            exit = leaving,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        TimerScreenUi("Timer", started = false, state = TimerUiState.IDLE, finishedRun = null),
    )

    init {
        // A new run only on explicit intent (the route's startNew), and only once: a nav entry restored after process
        // death has its handle back with the flag already used up, so it joins what is running (or leaves) instead.
        val startNew = handle.get<Boolean>(KEY_START_NEW) ?: true
        handle[KEY_START_NEW] = false
        viewModelScope.launch {
            begin(startNew)
            // Keeps this screen right even if the service is not ticking (notifications are not needed for it to run).
            while (isActive) {
                delay(UI_TICK_MS)
                if (TimerController.state.value.running) TimerController.refresh(appContext)
            }
        }
    }

    private fun isMine(run: TimerRun) =
        run.kind == TimerRun.EMOM && run.sessionId == sessionId && run.slotId == slotId && run.exerciseId == exerciseId

    /**
     * Starts the run at once with the slot's defaults, or carries on the one that is already going for this slot.
     * Without [startNew] it only carries on: with nothing to carry on the screen is left (to the log, if the run for
     * this slot ended while the app was away).
     */
    private suspend fun begin(startNew: Boolean) {
        var config = EmomConfig(STANDALONE_ROUNDS, STANDALONE_ROUND_SEC)
        if (slotId != null && exerciseId != null) {
            title.value = db.libraryDao().get(exerciseId)?.name ?: "Timer"
            val slot = db.planDao().getSlot(slotId)
            if (slot != null) {
                val target = targets.targetFor(slotId, exerciseId, today())
                targetType = target.type
                config = timerDefaults(slot, target)
            }
        }
        val wanted = TimerRun(
            kind = TimerRun.EMOM, rounds = config.rounds, roundSec = config.roundSec, workSec = 0, restSec = 0,
            startElapsedMs = SystemClock.elapsedRealtime(), pausedAtElapsedMs = null, pausedTotalMs = 0,
            sessionId = sessionId, slotId = slotId, exerciseId = exerciseId,
        )
        if (startNew) {
            TimerController.startOrAttach(appContext, wanted)
        } else if (TimerController.attach(appContext, wanted) == null) {
            val pending = TimerRunStore.from(appContext).pendingLog()
                ?.takeIf { it.sessionId == sessionId && it.slotId == slotId }
            exit.value = RestoredExit(pending?.let { LogRoute(it.sessionId, it.slotId, it.exerciseId) })
            return
        }
        started.value = true
    }

    fun changeRounds(delta: Int) = adjust { adjustRounds(it, delta) }

    fun changeRoundSec(steps: Int) = adjust { adjustRoundSec(it, steps) }

    private fun adjust(change: (EmomConfig) -> EmomConfig) {
        val current = TimerController.state.value
        val run = current.run?.takeIf { isMine(it) && !current.finished } ?: return
        val before = run.emom()
        val wanted = change(before)
        TimerController.adjust(appContext, wanted.rounds, wanted.roundSec)
        val after = TimerController.state.value.run?.emom() ?: return
        if (after != before) {
            remember(
                roundsChanged = after.rounds != before.rounds,
                roundSecChanged = after.roundSec != before.roundSec,
                config = after,
            )
        }
    }

    /** A NORMAL-type slot run keeps every adjustment for next time, written at once, only for the field changed. */
    private fun remember(roundsChanged: Boolean, roundSecChanged: Boolean, config: EmomConfig) {
        val slot = slotId ?: return
        if (targetType != SessionType.NORMAL) return
        viewModelScope.launch {
            slotWrites.withLock {
                val current = db.planDao().getSlot(slot) ?: return@withLock
                db.planDao().updateSlot(
                    current.copy(
                        timerRounds = if (roundsChanged) config.rounds else current.timerRounds,
                        timerRoundSec = if (roundSecChanged) config.roundSec else current.timerRoundSec,
                    ),
                )
            }
        }
    }

    fun pause() = TimerController.pause(appContext)

    fun resume() = TimerController.resume(appContext)

    fun stop() = TimerController.stop(appContext)

    /**
     * The screen has acted on the finished run (opened Log or returned). It saw the run end, so the lifter is being
     * taken to its log: that slot's pending log is settled. Only this screen does that; no other timer screen does.
     */
    fun finishHandled() {
        val session = sessionId
        val slot = slotId
        if (session != null && slot != null) TimerRunStore.from(appContext).clearPendingLog(session, slot)
        TimerController.acknowledgeFinished()
    }

    internal companion object {
        const val KEY_START_NEW = "startNew"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
