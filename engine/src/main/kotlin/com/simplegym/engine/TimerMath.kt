package com.simplegym.engine

enum class Phase { WORK, REST }

enum class Cue { COUNTDOWN, ROUND_START, FINISH }

/** [rounds] is 1..99; [roundSec] is 15..600 in steps of 15. */
data class EmomConfig(val rounds: Int, val roundSec: Int)

/** Each round is [workSec] of work then [restSec] of rest; the last round's rest still runs before finishing. */
data class IntervalConfig(val workSec: Int, val restSec: Int, val rounds: Int)

/** When [finished], [round] stays on the last round and [secondsLeft] is 0. */
data class TimerState(val round: Int, val secondsLeft: Int, val phase: Phase, val finished: Boolean)

private const val MIN_ROUNDS = 1
private const val MAX_ROUNDS = 99
private const val MIN_ROUND_SEC = 15
private const val MAX_ROUND_SEC = 600
private const val ROUND_SEC_STEP = 15

private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

fun emomState(cfg: EmomConfig, elapsedMs: Long): TimerState {
    val elapsed = elapsedMs.coerceAtLeast(0)
    val roundMs = cfg.roundSec * 1000L
    if (elapsed >= cfg.rounds * roundMs) return TimerState(cfg.rounds, 0, Phase.WORK, finished = true)
    return TimerState(
        round = (elapsed / roundMs).toInt() + 1,
        secondsLeft = ceilSeconds(roundMs - elapsed % roundMs),
        phase = Phase.WORK,
        finished = false,
    )
}

fun intervalState(cfg: IntervalConfig, elapsedMs: Long): TimerState {
    val elapsed = elapsedMs.coerceAtLeast(0)
    val workMs = cfg.workSec * 1000L
    val roundMs = workMs + cfg.restSec * 1000L
    if (elapsed >= cfg.rounds * roundMs) return TimerState(cfg.rounds, 0, Phase.WORK, finished = true)
    val intoRound = elapsed % roundMs
    val working = intoRound < workMs
    return TimerState(
        round = (elapsed / roundMs).toInt() + 1,
        secondsLeft = ceilSeconds(if (working) workMs - intoRound else roundMs - intoRound),
        phase = if (working) Phase.WORK else Phase.REST,
        finished = false,
    )
}

/** The cue to play when the timer moves from [prev] (null before the first tick) to [cur]. */
fun cueBetween(prev: TimerState?, cur: TimerState): Cue? = when {
    cur.finished -> if (prev?.finished == true) null else Cue.FINISH
    prev == null || prev.finished || prev.round != cur.round || prev.phase != cur.phase -> Cue.ROUND_START
    cur.secondsLeft != prev.secondsLeft && cur.secondsLeft in 1..3 -> Cue.COUNTDOWN
    else -> null
}

fun adjustRounds(cfg: EmomConfig, delta: Int): EmomConfig =
    cfg.copy(rounds = (cfg.rounds + delta).coerceIn(MIN_ROUNDS, MAX_ROUNDS))

fun adjustRoundSec(cfg: EmomConfig, deltaSteps: Int): EmomConfig =
    cfg.copy(roundSec = (cfg.roundSec + deltaSteps * ROUND_SEC_STEP).coerceIn(MIN_ROUND_SEC, MAX_ROUND_SEC))

/**
 * The elapsed time under [new] that keeps the lifter's place from [old]: the same completed rounds and the same time
 * into the current round, held to the last second of a shorter round. [new].rounds does not change the result; callers
 * raise it to at least the current round first so started rounds are never removed.
 */
fun rebaseEmom(old: EmomConfig, new: EmomConfig, elapsedMs: Long): Long {
    val elapsed = elapsedMs.coerceAtLeast(0)
    val oldRoundMs = old.roundSec * 1000L
    val newRoundMs = new.roundSec * 1000L
    val completed = elapsed / oldRoundMs
    val intoRound = elapsed % oldRoundMs
    return completed * newRoundMs + minOf(intoRound, newRoundMs - 1000)
}
