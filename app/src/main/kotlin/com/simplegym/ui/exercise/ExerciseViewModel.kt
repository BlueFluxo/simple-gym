package com.simplegym.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.data.ExerciseEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.SlotStatus
import com.simplegym.domain.BlockService
import com.simplegym.domain.ClubService
import com.simplegym.domain.HurtService
import com.simplegym.domain.RotationService
import com.simplegym.domain.SessionService
import com.simplegym.domain.SwapPlan
import com.simplegym.domain.TargetService
import com.simplegym.engine.BackLoad
import com.simplegym.engine.Exercise
import com.simplegym.engine.Joint
import com.simplegym.engine.PLATEAU_LIMIT
import com.simplegym.engine.Target
import com.simplegym.engine.formatKg
import com.simplegym.ui.components.displayName
import com.simplegym.ui.components.hurtBadgeLabels
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [weight] is null for a variant with no baseline yet: the card then shows [hint] (if any) instead of a number. */
data class TargetCardUi(
    val weight: String?,
    val sets: String,
    val repRange: String,
    val roundTime: String,
    val reason: String,
    val hint: String?,
)

/** [hurtBadges] are the amber "⚠ Shoulder · 2×" labels of a variant that hurt. */
data class SwapChipUi(
    val exerciseId: String,
    val name: String,
    val weightLabel: String,
    val hurtBadges: List<String> = emptyList(),
)

/** The inline warning on a variant that hurt: [message] names the joint, [swap] (if any) is the joint-friendly chip. */
data class HurtBannerUi(val message: String, val swap: SwapChipUi?)

data class SearchResultUi(val exerciseId: String, val name: String, val highBackLoadWarning: Boolean)

/** [allGroups] false means the results are narrowed to the slot's swap group. */
data class SearchUi(val query: String, val allGroups: Boolean, val results: List<SearchResultUi>)

/** The first session of a new block after a rotation: offers to make [exerciseId] ([name]) the slot's default. */
data class KeepOfferUi(val exerciseId: String, val name: String)

data class ExerciseState(
    val loaded: Boolean,
    val variantId: String,
    val variantName: String,
    val target: TargetCardUi?,
    val chips: List<SwapChipUi>,
    /** Null while the search is closed. */
    val search: SearchUi?,
    /** True once this variant has stalled [PLATEAU_LIMIT] sessions in a row: the banner offers [chips] as a rotation. */
    val plateau: Boolean = false,
    /** Non-null on the first session of a new block after a rotation. */
    val keepOffer: KeepOfferUi? = null,
    /** Amber labels for the variant on screen, e.g. "⚠ Shoulder · 2×". */
    val hurtBadges: List<String> = emptyList(),
    /** Non-null while the variant on screen has an active hurt flag. */
    val hurtBanner: HurtBannerUi? = null,
    /** The softer grey note: another exercise hurt a joint this one loads recently, e.g. "Shoulder flagged recently". */
    val jointNote: String? = null,
    /**
     * Today's check-in has a stiff chip for the muscle this variant trains: the "Flush instead" switch shows. It also
     * shows while [flush] is on, so a flush can always be switched off again.
     */
    val stiffMuscle: Boolean = false,
    /** The slot's "Flush instead" switch in this session. */
    val flush: Boolean = false,
    /** The session has a club, so a long press on a swap chip may mark it "Not at this club". */
    val canMarkUnavailable: Boolean = false,
    /** Set after a long press on that chip: the inline "Not at this club" choice for it is showing. */
    val unavailableOffer: SwapChipUi? = null,
)

/** [hurts] holds the active hurt counts per joint of every flagged exercise, by exercise id; [clubId] is the session's. */
private data class Loaded(
    val plan: SwapPlan,
    val labels: Map<String, String>,
    val hurts: Map<String, Map<Joint, Int>>,
    val clubId: Long?,
)

private data class VariantView(
    val id: String,
    val name: String,
    val target: TargetCardUi,
    val plateauCount: Int,
    val hurtBadges: List<String>,
    val hurtBanner: HurtBannerUi?,
    val jointNote: String?,
    val stiffMuscle: Boolean,
    val flush: Boolean,
)

/** What the target depends on besides the plan: the variant on screen, the slot's flush switch and today's check-in. */
private data class VariantKey(val id: String, val loaded: Loaded, val flush: Boolean)

private data class Inputs(val loaded: Loaded, val variant: VariantView, val search: SearchInput?, val library: List<ExerciseEntity>)

private data class Offers(val rotatedId: String?, val keepOffer: KeepOfferUi?, val unavailableId: String?)

private data class SearchInput(val query: String, val allGroups: Boolean)

/**
 * One slot of one session. The variant shown starts at [TargetService.resolveVariant] (the same call the Workout
 * row's "▶ Start timer" makes); a swap or a search pick only changes which variant is on screen.
 */
class ExerciseViewModel(
    private val db: GymDatabase,
    private val sessions: SessionService,
    private val targets: TargetService,
    private val blocks: BlockService,
    private val rotations: RotationService,
    private val clubs: ClubService,
    private val sessionId: Long,
    private val slotId: Long,
    private val today: () -> LocalDate,
) : ViewModel() {

    private val hurts = HurtService(db)
    private val loaded = MutableStateFlow<Loaded?>(null)
    private val variantId = MutableStateFlow<String?>(null)
    private val search = MutableStateFlow<SearchInput?>(null)

    /** The variant the slot is rotated to for the current block, if any: it is not offered another rotation. */
    private val rotatedId = MutableStateFlow<String?>(null)
    private val keepOffer = MutableStateFlow<KeepOfferUi?>(null)

    /** The swap chip a long press is asking about ("Not at this club"). */
    private val unavailableOffer = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val slot = checkNotNull(db.planDao().getSlot(slotId)) { "No slot $slotId" }
            val blockIndex = blocks.current(today()).blockIndex
            val rotationBlock = slot.rotationBlockIndex
            val rotationExercise = slot.rotationExerciseId
            if (rotationBlock != null && rotationExercise != null) {
                if (rotationBlock == blockIndex) {
                    rotatedId.value = rotationExercise
                } else if (rotationBlock < blockIndex) {
                    db.libraryDao().get(rotationExercise)?.takeIf { !it.archived }?.let {
                        keepOffer.value = KeepOfferUi(it.id, it.name)
                    }
                }
            }
            reloadPlan()
            variantId.value = targets.resolveVariant(sessionId, slotId, today())
        }
    }

    private suspend fun reloadPlan() {
        val plan = targets.swapPlan(sessionId, slotId, today())
        loaded.value = Loaded(
            plan = plan,
            labels = targets.swapChipLabels(chipCandidates(plan).map { it.id }),
            hurts = hurts.activeHurts(),
            clubId = db.sessionDao().getSession(sessionId)?.clubId,
        )
    }

    /** This slot's "Flush instead" switch in this session. */
    private val flushOn = sessions.slotStates(sessionId)
        .map { rows -> rows.firstOrNull { it.slotId == slotId }?.flush == true }
        .distinctUntilChanged()

    /** Today's stiff chips: a change (the check-in is edited) recomputes the target. */
    private val stiffChips = db.sessionDao().observeCheckIn(today()).map { it?.stiff.orEmpty() }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val variant = combine(variantId.filterNotNull(), loaded.filterNotNull(), flushOn, stiffChips) { id, loaded, flush, _ ->
        VariantKey(id, loaded, flush)
    }.mapLatest { (id, loaded, flush) ->
        val name = db.libraryDao().get(id)?.name ?: id
        val target = targets.targetFor(slotId, id, today())
        val counts = loaded.hurts[id].orEmpty()
        VariantView(
            id = id,
            name = name,
            target = targetCard(target),
            plateauCount = targets.plateauFor(id, target.rangeType),
            hurtBadges = hurtBadgeLabels(counts),
            hurtBanner = hurtBanner(loaded, id, counts),
            jointNote = hurts.cautionFor(id, today())?.let { "${it.displayName()} flagged recently" },
            stiffMuscle = targets.stiffMuscle(id, today()),
            flush = flush,
        )
    }

    /** The joint that hurt most (the first in joint order on a tie) names the banner; its swap is one tap away. */
    private suspend fun hurtBanner(loaded: Loaded, variantId: String, counts: Map<Joint, Int>): HurtBannerUi? {
        val (joint, times) = counts.entries.sortedBy { it.key }.maxByOrNull { it.value } ?: return null
        val swap = hurts.friendlySwap(loaded.plan, variantId, joint)
        val lastTimes = if (times == 1) "last time" else "last $times times"
        val message = "This hurt your ${joint.displayName().lowercase(Locale.ROOT)} $lastTimes." +
            swap?.let { " Try ${it.name} instead?" }.orEmpty()
        val swapChip = swap?.let {
            val label = targets.swapChipLabels(listOf(it.id)).getValue(it.id)
            SwapChipUi(it.id, it.name, label, hurtBadgeLabels(loaded.hurts[it.id].orEmpty()))
        }
        return HurtBannerUi(message, swapChip)
    }

    val state: StateFlow<ExerciseState> = combine(
        combine(loaded.filterNotNull(), variant, search, db.libraryDao().observeAll(), ::Inputs),
        combine(rotatedId, keepOffer, unavailableOffer, ::Offers),
    ) { (loaded, variant, search, library), (rotatedId, keepOffer, unavailableId) ->
        val plan = loaded.plan
        val chips = chipCandidates(plan).filter { it.id != variant.id }.map {
            SwapChipUi(it.id, it.name, loaded.labels[it.id].orEmpty(), hurtBadgeLabels(loaded.hurts[it.id].orEmpty()))
        }
        ExerciseState(
            loaded = true,
            variantId = variant.id,
            variantName = variant.name,
            target = variant.target,
            plateau = variant.plateauCount >= PLATEAU_LIMIT && variant.id != rotatedId,
            keepOffer = keepOffer,
            hurtBadges = variant.hurtBadges,
            hurtBanner = variant.hurtBanner,
            jointNote = variant.jointNote,
            stiffMuscle = variant.stiffMuscle,
            flush = variant.flush,
            canMarkUnavailable = loaded.clubId != null,
            chips = chips,
            unavailableOffer = chips.firstOrNull { it.exerciseId == unavailableId },
            search = search?.let { input ->
                val needle = input.query.trim().lowercase(Locale.ROOT)
                SearchUi(
                    query = input.query,
                    allGroups = input.allGroups,
                    results = library
                        .filter { !it.archived }
                        .filter { input.allGroups || it.swapGroup == plan.default.swapGroup }
                        .filter { needle.isEmpty() || it.name.lowercase(Locale.ROOT).contains(needle) }
                        .map { SearchResultUi(it.id, it.name, plan.ctx.lowerBackProtection && it.backLoad == BackLoad.HIGH) },
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EMPTY)

    /**
     * One tap on a banner chip: [exerciseId] becomes the slot's default for this block (a rotation) and the variant
     * shown. The Workout row's "Start timer" follows, since it resolves through the same rotation.
     */
    fun rotateTo(exerciseId: String) {
        viewModelScope.launch {
            db.planDao().setRotation(slotId, exerciseId, blocks.current(today()).blockIndex)
            rotatedId.value = exerciseId
            // a new rotation is an implicit "No" to any pending keep offer for the previous one
            keepOffer.value = null
            reloadPlan()
            variantId.value = exerciseId
            search.value = null
        }
    }

    /** The "Keep <variant> as default?" chip: yes makes it the plan default; either answer clears the rotation. */
    fun answerKeepOffer(keep: Boolean) {
        val offer = keepOffer.value ?: return
        keepOffer.value = null
        viewModelScope.launch {
            if (keep) {
                rotations.keepAsDefault(slotId, offer.exerciseId)
                reloadPlan()
                variantId.value = offer.exerciseId
            } else {
                rotations.clear(slotId)
            }
        }
    }

    /** A long press on a swap chip: offers "Not at this club" for it, when the session has a club. */
    fun askUnavailable(exerciseId: String) {
        if (loaded.value?.clubId != null) unavailableOffer.value = exerciseId
    }

    fun cancelUnavailable() {
        unavailableOffer.value = null
    }

    /** "Not at this club": the chip leaves this session's swaps at once, and every later session at this club. */
    fun confirmUnavailable() {
        val exerciseId = unavailableOffer.value ?: return
        val clubId = loaded.value?.clubId ?: return
        viewModelScope.launch {
            clubs.setUnavailable(clubId, exerciseId, true)
            unavailableOffer.value = null
            reloadPlan()
        }
    }

    /** The "Flush instead" switch: a light high-rep target for this slot in this session (see [coachTarget]). */
    fun setFlush(on: Boolean) {
        viewModelScope.launch { sessions.setFlush(sessionId, slotId, on) }
    }

    /** One tap: this variant becomes the one shown (and the one "Start timer" runs). Closes the search. */
    fun pick(exerciseId: String) {
        variantId.value = exerciseId
        search.value = null
    }

    fun openSearch() {
        search.value = SearchInput(query = "", allGroups = false)
    }

    fun closeSearch() {
        search.value = null
    }

    fun setQuery(query: String) {
        search.value = search.value?.copy(query = query)
    }

    fun setAllGroups(all: Boolean) {
        search.value = search.value?.copy(allGroups = all)
    }

    private var skipping = false

    /** Marks the slot SKIPPED (a DONE slot stays DONE), then reports back on the main thread. */
    fun skip(onSkipped: () -> Unit) {
        if (skipping) return
        skipping = true
        viewModelScope.launch {
            try {
                val current = db.sessionDao().getSessionSlots(sessionId).firstOrNull { it.slotId == slotId }
                if (current?.status != SlotStatus.DONE) sessions.setStatus(sessionId, slotId, SlotStatus.SKIPPED)
                onSkipped()
            } finally {
                skipping = false
            }
        }
    }

    /**
     * The default (when it is allowed) followed by the ordered swaps: every variant that can be one tap away. A
     * hurt-flagged default does not lead: it ranks with the flagged swaps, after the clean ones.
     */
    private fun chipCandidates(plan: SwapPlan): List<Exercise> {
        val default = listOf(plan.default).filter { plan.defaultVisible }
        if (plan.default.id !in plan.ctx.hurtFlagged) return default + plan.ordered
        val (clean, flagged) = plan.ordered.partition { it.id !in plan.ctx.hurtFlagged }
        return clean + default + flagged
    }

    private fun targetCard(target: Target) = TargetCardUi(
        weight = target.weightKg?.let(::formatKg),
        sets = "${target.sets} sets",
        repRange = "${target.range.lo}–${target.range.hi} reps",
        roundTime = String.format(
            Locale.ROOT, "Round %d:%02d", target.roundSec / SECONDS_PER_MINUTE, target.roundSec % SECONDS_PER_MINUTE,
        ),
        reason = target.reason,
        hint = target.hint?.let { "${it.exerciseName}: ${formatKg(it.weightKg)} kg" },
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val SECONDS_PER_MINUTE = 60
        val EMPTY = ExerciseState(
            loaded = false, variantId = "", variantName = "", target = null, chips = emptyList(), search = null,
        )
    }
}
