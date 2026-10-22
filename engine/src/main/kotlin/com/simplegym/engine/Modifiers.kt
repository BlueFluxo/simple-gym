package com.simplegym.engine

/** One way today's target could be set: [weightKg] (null for bodyweight), [sets], the [type] it logs as and the [reason] shown. */
data class Candidate(val weightKg: Double?, val sets: Int, val type: SessionType, val reason: String)

/**
 * The single most conservative of [candidates] (spec §6.9): lowest weight (assisted weights are negative, so the most
 * assistance is the lowest; null weights compare equal), then fewest sets, then the first. Modifiers are never
 * multiplied together.
 */
fun mostConservative(candidates: List<Candidate>): Candidate =
    candidates.minWith(compareBy<Candidate> { it.weightKg ?: 0.0 }.thenBy { it.sets })
