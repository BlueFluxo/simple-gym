package com.simplegym.engine

/** Swap groups that load the neck: they sink to the end of the chips on a stiff-neck day. */
private val NECK_LOADING_GROUPS = setOf("shrug", "upright-row")

/**
 * What decides which swaps are offered and in what order. [unavailable] and [useCounts] are keyed by exercise id;
 * [hurtFlagged] holds ids of exercises with an active hurt flag.
 */
data class SwapContext(
    val lowerBackProtection: Boolean,
    val unavailable: Set<String>,
    val useCounts: Map<String, Int>,
    val hurtFlagged: Set<String>,
    val stiffNeck: Boolean,
    val stiffLowerBack: Boolean,
)

private fun Exercise.isHidden(ctx: SwapContext): Boolean =
    id in ctx.unavailable || (ctx.lowerBackProtection && backLoad == BackLoad.HIGH)

/**
 * Drops swaps that are [SwapContext.unavailable] (or high back-load with protection on), then sorts: not hurt-flagged
 * first, then (on a stiff neck) outside the shrug / upright-row groups first, then most used first, then as listed.
 */
fun orderSwaps(swaps: List<Exercise>, ctx: SwapContext): List<Exercise> =
    swaps.filterNot { it.isHidden(ctx) }
        .sortedWith(
            compareBy<Exercise> { it.id in ctx.hurtFlagged }
                .thenBy { ctx.stiffNeck && it.swapGroup in NECK_LOADING_GROUPS }
                .thenByDescending { ctx.useCounts[it.id] ?: 0 },
        ) // sortedWith is stable, so ties keep their listed position

/**
 * The variant a slot starts on today. A stiff lower back moves a moderate / high back-load default to the ordered
 * swap with the lowest back-load (the first on ties), provided that swap is actually lighter on the back; a default
 * that is hidden is replaced by the first ordered swap; otherwise it is the [default].
 */
fun suggestedVariant(default: Exercise, swaps: List<Exercise>, ctx: SwapContext): Exercise {
    val ordered = orderSwaps(swaps, ctx)
    if (ctx.stiffLowerBack && default.backLoad != BackLoad.LOW) {
        val gentlest = ordered.minByOrNull { it.backLoad } // minBy returns the first of equal minimums
        if (gentlest != null && gentlest.backLoad < default.backLoad) return gentlest
    }
    if (default.isHidden(ctx)) return ordered.firstOrNull() ?: default
    return default
}
