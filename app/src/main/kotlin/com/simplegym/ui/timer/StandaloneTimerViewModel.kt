package com.simplegym.ui.timer

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.engine.EmomConfig
import com.simplegym.engine.IntervalConfig
import com.simplegym.engine.adjustRoundSec
import com.simplegym.engine.adjustRounds
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerRun
import com.simplegym.timer.TimerUiState
import com.simplegym.timer.emom
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class StandaloneTab(val label: String) { EMOM("EMOM"), INTERVAL("Interval") }

private const val UI_TICK_MS = 250L
private const val STOP_TIMEOUT_MS = 5_000L
private const val MAX_INTERVAL_SEC = 600
private const val INTERVAL_STEP_SEC = 5
private const val MIN_WORK_SEC = 5
private const val MAX_ROUNDS = 99

/** What the screen opens with: a 3 x 2:00 EMOM, and 8 rounds of 30 s work and 10 s rest (the mobility default). */
internal val DEFAULT_EMOM = EmomConfig(rounds = 3, roundSec = 120)
internal val DEFAULT_INTERVAL = IntervalConfig(workSec = 30, restSec = 10, rounds = 8)

/**
 * [state] is this screen's own run (idle when none is going). [finishedRun] is set once it has ended and not yet been
 * handled. Nothing here is logged.
 */
data class StandaloneUi(
    val tab: StandaloneTab,
    val emom: EmomConfig,
    val interval: IntervalConfig,
    val state: TimerUiState,
    val finishedRun: TimerRun?,
)

/** The timer opened from Home without an exercise: an EMOM or an interval run through [TimerController], nothing logged. */
class StandaloneTimerViewModel(private val appContext: Context) : ViewModel() {

    private val current = TimerController.state.value.run?.takeIf { isMine(it) }

    /** A run that ended before this screen opened: it is only cleared away, it must not send the lifter back. */
    private val endedBefore = current?.takeIf { TimerController.state.value.finished }?.startElapsedMs
    private val tab = MutableStateFlow(if (current?.kind == TimerRun.INTERVAL) StandaloneTab.INTERVAL else StandaloneTab.EMOM)
    private val emomConfig = MutableStateFlow(current?.takeIf { it.kind == TimerRun.EMOM }?.emom() ?: DEFAULT_EMOM)
    private val intervalConfig = MutableStateFlow(
        current?.takeIf { it.kind == TimerRun.INTERVAL }?.let { IntervalConfig(it.workSec, it.restSec, it.rounds) }
            ?: DEFAULT_INTERVAL,
    )

    val ui: StateFlow<StandaloneUi> = combine(tab, emomConfig, intervalConfig, TimerController.state) { t, e, i, state ->
        val mine = state.run?.let { isMine(it) && it.startElapsedMs != endedBefore } == true
        StandaloneUi(
            tab = t,
            emom = e,
            interval = i,
            state = if (mine) state else TimerUiState.IDLE,
            finishedRun = state.run.takeIf { mine && state.finished },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        StandaloneUi(tab.value, emomConfig.value, intervalConfig.value, TimerUiState.IDLE, finishedRun = null),
    )

    init {
        if (endedBefore != null) TimerController.acknowledgeFinished()
        viewModelScope.launch {
            // Keeps this screen right even if the service is not ticking.
            while (isActive) {
                delay(UI_TICK_MS)
                if (TimerController.state.value.running) TimerController.refresh(appContext)
            }
        }
    }

    /** A run with no slot, no exercise and no mobility areas belongs to this screen. */
    private fun isMine(run: TimerRun) =
        run.sessionId == null && run.slotId == null && run.exerciseId == null && !run.isMobility

    fun selectTab(selected: StandaloneTab) {
        tab.value = selected
    }

    fun changeEmomRounds(delta: Int) {
        if (!changeRunning { adjustRounds(it, delta) }) emomConfig.value = adjustRounds(emomConfig.value, delta)
    }

    fun changeEmomRoundSec(steps: Int) {
        if (!changeRunning { adjustRoundSec(it, steps) }) emomConfig.value = adjustRoundSec(emomConfig.value, steps)
    }

    /** Adjusts the EMOM that is running, if there is one, and keeps its new values for the next start. */
    private fun changeRunning(change: (EmomConfig) -> EmomConfig): Boolean {
        val state = TimerController.state.value
        val run = state.run?.takeIf { isMine(it) && it.kind == TimerRun.EMOM && !state.finished } ?: return false
        val wanted = change(run.emom())
        TimerController.adjust(appContext, wanted.rounds, wanted.roundSec)
        TimerController.state.value.run?.let { emomConfig.value = it.emom() }
        return true
    }

    fun changeWork(steps: Int) = editInterval { it.copy(workSec = stepped(it.workSec, steps, MIN_WORK_SEC)) }

    fun changeRest(steps: Int) = editInterval { it.copy(restSec = stepped(it.restSec, steps, 0)) }

    fun changeIntervalRounds(delta: Int) = editInterval { it.copy(rounds = (it.rounds + delta).coerceIn(1, MAX_ROUNDS)) }

    private fun stepped(value: Int, steps: Int, min: Int) =
        (value + steps * INTERVAL_STEP_SEC).coerceIn(min, MAX_INTERVAL_SEC)

    /** An interval run cannot be changed once started, so these only edit the settings for the next start. */
    private fun editInterval(change: (IntervalConfig) -> IntervalConfig) {
        intervalConfig.value = change(intervalConfig.value)
    }

    fun start() {
        val now = SystemClock.elapsedRealtime()
        val run = when (tab.value) {
            StandaloneTab.EMOM -> emomConfig.value.let {
                TimerRun(
                    kind = TimerRun.EMOM, rounds = it.rounds, roundSec = it.roundSec, workSec = 0, restSec = 0,
                    startElapsedMs = now, pausedAtElapsedMs = null, pausedTotalMs = 0,
                    sessionId = null, slotId = null, exerciseId = null,
                )
            }
            StandaloneTab.INTERVAL -> intervalConfig.value.let {
                TimerRun(
                    kind = TimerRun.INTERVAL, rounds = it.rounds, roundSec = 0, workSec = it.workSec, restSec = it.restSec,
                    startElapsedMs = now, pausedAtElapsedMs = null, pausedTotalMs = 0,
                    sessionId = null, slotId = null, exerciseId = null,
                )
            }
        }
        TimerController.start(appContext, run)
    }

    fun pause() = TimerController.pause(appContext)

    fun resume() = TimerController.resume(appContext)

    fun stop() = TimerController.stop(appContext)

    /** The screen has acted on the finished run (went back). */
    fun finishHandled() = TimerController.acknowledgeFinished()
}
