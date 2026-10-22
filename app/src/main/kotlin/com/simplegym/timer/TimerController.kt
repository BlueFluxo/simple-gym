package com.simplegym.timer

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.simplegym.engine.TimerState
import com.simplegym.engine.rebaseEmom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the screen and the service show. [run] stays set after the run finishes (with [timer] finished) until
 * [TimerController.acknowledgeFinished], so a screen that was off when the last round ended still sees it end.
 */
data class TimerUiState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val run: TimerRun? = null,
    val timer: TimerState? = null,
) {
    val finished: Boolean get() = timer?.finished == true

    companion object {
        val IDLE = TimerUiState()
    }
}

/**
 * The in-process side of the timer. Every position is recomputed from [TimerRunStore] data and
 * `SystemClock.elapsedRealtime()`, never counted, so it does not matter whether [TimerService] ticked, was frozen
 * with the screen off, or died: the next [refresh] or a restore from the store lands on the right round.
 * Each mutation is saved before it is published.
 */
object TimerController {
    private val _state = MutableStateFlow(TimerUiState.IDLE)
    val state: StateFlow<TimerUiState> = _state.asStateFlow()

    @Volatile
    private var serviceRunning = false

    private fun now() = SystemClock.elapsedRealtime()

    fun isServiceRunning(): Boolean = serviceRunning

    internal fun onServiceCreated() {
        serviceRunning = true
    }

    internal fun onServiceDestroyed() {
        serviceRunning = false
    }

    /** Starts [run] exactly as given: restoring a killed run passes its stored copy so its start instant is kept. */
    @Synchronized
    fun start(context: Context, run: TimerRun) {
        TimerRunStore.from(context).save(run)
        _state.value = uiOf(run, now())
        launchService(context)
    }

    /**
     * The lifter tapped Home's "Timer was interrupted - Resume". The banner can be minutes old, so the stored run is
     * checked against the clock again right now: if it ran out in the meantime it is cleared and nothing starts
     * (false), otherwise it is restarted exactly as stored (true).
     */
    @Synchronized
    fun resumeInterrupted(context: Context): Boolean {
        val killed = detectKilledRun(TimerRunStore.from(context), isRunning = false, nowElapsed = now()) ?: return false
        start(context, killed.run)
        return true
    }

    /**
     * Entering the timer screen with the intent to time [wanted]. A run for the same workout that is already going
     * carries on (still in memory, or left in the store by a killed process); an unacknowledged finished run for the
     * same workout is returned as it is, so the screen leads to its log instead of starting over it. Otherwise
     * [wanted] starts fresh. Returns the run that is now current.
     */
    @Synchronized
    fun startOrAttach(context: Context, wanted: TimerRun): TimerRun {
        attach(context, wanted)?.let { return it }
        // A finished run of another workout is already a pending log (see [onFinished]), so it can be let go.
        if (_state.value.finished) _state.value = TimerUiState.IDLE
        start(context, wanted)
        return wanted
    }

    /**
     * Joins the run for [wanted] if there is one (going, or finished and not yet acknowledged) and never starts
     * anything: a timer screen that was only restored after process death must not begin a new run. Null when there
     * is nothing to join.
     */
    @Synchronized
    fun attach(context: Context, wanted: TimerRun): TimerRun? {
        val store = TimerRunStore.from(context)
        if (_state.value.run == null) {
            // A fresh process: pick up what the store holds.
            store.load()?.let { stored ->
                val ui = uiOf(stored, now())
                // Ended while the process was away: its log is pending rather than lost to a new run.
                if (ui.finished) onFinished(store, stored)
                _state.value = ui
            }
        } else {
            refresh(context) // a run that ran out unseen becomes a pending log before anything can replace it
        }
        val current = _state.value
        val run = current.run?.takeIf { it.sameWorkout(wanted) } ?: return null
        if (!current.finished && !serviceRunning) launchService(context)
        return run
    }

    @Synchronized
    fun pause(context: Context) = mutate(context) { run, now ->
        if (run.pausedAtElapsedMs == null) run.copy(pausedAtElapsedMs = now) else run
    }

    @Synchronized
    fun resume(context: Context) = mutate(context) { run, now ->
        val pausedAt = run.pausedAtElapsedMs ?: return@mutate run
        run.copy(pausedAtElapsedMs = null, pausedTotalMs = run.pausedTotalMs + (now - pausedAt))
    }

    /** Sets the running EMOM's rounds and round time; see [adjusted]. The result is saved before it is published. */
    @Synchronized
    fun adjust(context: Context, rounds: Int, roundSec: Int) =
        mutate(context) { run, now -> run.adjusted(rounds, roundSec, now) }

    @Synchronized
    fun stop(context: Context) {
        TimerRunStore.from(context).clear()
        _state.value = TimerUiState.IDLE
        serviceRunning = false
        context.stopService(Intent(context, TimerService::class.java))
    }

    /**
     * Recomputes the position from the clock and publishes it; the service calls this every tick and the screen on
     * its own ticker, so both stay right with or without the other. A run that has ended is cleared from the store.
     */
    @Synchronized
    fun refresh(context: Context): TimerUiState {
        val current = _state.value
        val run = current.run ?: return current
        if (current.finished) return current
        val next = uiOf(run, now())
        if (next.finished) onFinished(TimerRunStore.from(context), run)
        _state.value = next
        return next
    }

    /**
     * The service played the start cue for [timer]'s round and phase. It is saved with the run, and published so a
     * later pause or adjust saves it again, which lets a restarted service tell what it must not replay ([seedPrevState]).
     * A finished or stopped run is left alone: it must not be written back into the store.
     */
    @Synchronized
    fun recordStartCue(context: Context, timer: TimerState) {
        val current = _state.value
        val run = current.run ?: return
        if (current.finished || timer.finished) return
        if (run.lastCuedRound == timer.round && run.lastCuedPhase == timer.phase) return
        val next = run.copy(lastCuedRound = timer.round, lastCuedPhase = timer.phase)
        TimerRunStore.from(context).save(next)
        _state.value = current.copy(run = next)
    }

    /** After a process restart the controller knows nothing: pick the stored run up. */
    @Synchronized
    fun ensureLoaded(context: Context): TimerUiState {
        if (_state.value.run == null) {
            val store = TimerRunStore.from(context)
            store.load()?.let {
                _state.value = uiOf(it, now())
                if (_state.value.finished) onFinished(store, it)
            }
        }
        return _state.value
    }

    /**
     * The screen has handled a finished run (opened Log or returned): go back to idle. This leaves the run's pending
     * log alone: only the slot timer screen that took the lifter to that log settles it (see
     * [com.simplegym.ui.timer.TimerViewModel.finishHandled]); the standalone and mobility screens also acknowledge.
     */
    @Synchronized
    fun acknowledgeFinished() {
        if (_state.value.finished) _state.value = TimerUiState.IDLE
    }

    /**
     * [run] has ended and is no longer stored. If it belonged to a slot, nobody may have seen it end, so its log is
     * kept as the one pending log until the lifter saves it or dismisses it (the slot timer screen settles it when it
     * did see the run end and took the lifter to Log).
     */
    private fun onFinished(store: TimerRunStore, run: TimerRun) {
        run.pendingLog()?.let(store::savePendingLog)
        store.clear()
    }

    /** Test hook: forget everything in memory (the store lives in SharedPreferences, which tests reset themselves). */
    @Synchronized
    internal fun reset() {
        _state.value = TimerUiState.IDLE
        serviceRunning = false
    }

    private fun mutate(context: Context, transform: (run: TimerRun, now: Long) -> TimerRun) {
        val current = _state.value
        val run = current.run ?: return
        if (current.finished) return
        val now = now()
        val next = transform(run, now)
        if (next == run) return
        TimerRunStore.from(context).save(next)
        _state.value = uiOf(next, now)
        launchService(context) // wakes it to re-sync the notification and the wake-lock timeout
    }

    private fun uiOf(run: TimerRun, nowElapsed: Long): TimerUiState {
        val timer = run.stateAt(run.elapsedAt(nowElapsed))
        return TimerUiState(
            running = !timer.finished,
            paused = !timer.finished && run.pausedAtElapsedMs != null,
            run = run,
            timer = timer,
        )
    }

    /** The service may fail to start (permission or background limits); the in-app timer works regardless. */
    private fun launchService(context: Context) {
        serviceRunning = true
        try {
            ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
        } catch (e: RuntimeException) {
            serviceRunning = false
            Log.w("TimerController", "Could not start the timer service", e)
        }
    }
}

private fun TimerRun.sameWorkout(other: TimerRun) =
    kind == other.kind && sessionId == other.sessionId && slotId == other.slotId && exerciseId == other.exerciseId

/**
 * This EMOM run with new [rounds] / [roundSec], keeping the lifter's place: [rebaseEmom] picks the new elapsed time
 * and the start instant is moved so that [elapsedAt] gives it. Rounds already started are never removed. A paused
 * run stays paused at the same instant. Interval and finished runs are returned unchanged.
 */
internal fun TimerRun.adjusted(rounds: Int, roundSec: Int, nowElapsedMs: Long): TimerRun {
    if (kind != TimerRun.EMOM) return this
    val elapsed = elapsedAt(nowElapsedMs)
    if (stateAt(elapsed).finished) return this
    val old = emom()
    val completedRounds = (elapsed / (old.roundSec * 1000L)).toInt()
    val new = old.copy(rounds = maxOf(rounds, completedRounds + 1), roundSec = roundSec)
    val newElapsed = rebaseEmom(old, new, elapsed)
    val reference = pausedAtElapsedMs ?: nowElapsedMs
    return copy(
        rounds = new.rounds,
        roundSec = new.roundSec,
        startElapsedMs = reference - pausedTotalMs - newElapsed,
    )
}
