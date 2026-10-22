package com.simplegym.timer

import com.simplegym.engine.TimerState

/**
 * The state a service's first tick compares against to decide its first cue (`cueBetween(seed, now)`), from the cues
 * the run has already played rather than from how old it is:
 *  - no cue has played yet (a genuine first start, however slow the start-up was): null, so the first tick gives
 *    ROUND_START;
 *  - the current round and phase are the ones already cued (the service restarted mid-round): the current state, so
 *    nothing is replayed, countdown beeps included;
 *  - the run has moved on since the last cue (a boundary passed while no service was ticking): the cued state, so the
 *    first tick announces the new round.
 */
fun seedPrevState(run: TimerRun, nowElapsedMs: Long): TimerState? {
    val phase = run.lastCuedPhase
    if (run.lastCuedRound <= 0 || phase == null) return null
    val current = run.stateAt(run.elapsedAt(nowElapsedMs))
    val sameAsCued = !current.finished && current.round == run.lastCuedRound && current.phase == phase
    return if (sameAsCued) current else TimerState(run.lastCuedRound, secondsLeft = 0, phase = phase, finished = false)
}
