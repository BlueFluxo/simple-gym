package com.simplegym.ui.log

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.data.GymDatabase
import com.simplegym.domain.LogInput
import com.simplegym.domain.LogService
import com.simplegym.domain.LogValidation
import com.simplegym.domain.TargetService
import com.simplegym.domain.toEngine
import com.simplegym.domain.validateLog
import com.simplegym.domain.weightFieldText
import com.simplegym.engine.Exercise
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.Target
import com.simplegym.timer.TimerRunStore
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_WEIGHT_STEP = 1.0

/**
 * [errors] maps a field ("weight", "reps", "sets", "hurt") to its inline message. [showWeight] is false for a
 * bodyweight exercise, where a weight would be ignored anyway; [weightLabel] is "Assistance (kg)" for an assisted one.
 * [removed] is set when the slot (or its exercise) is gone from the plan: there is nothing to log. [saveError] is
 * shown when a save failed; the typed input is kept.
 */
data class LogUiState(
    val loaded: Boolean = false,
    val exerciseName: String = "",
    val showWeight: Boolean = true,
    val weightLabel: String = "Weight (kg)",
    val weightStep: Double = DEFAULT_WEIGHT_STEP,
    val weight: String = "",
    val reps: String = "",
    val sets: String = "",
    val feel: Feel = Feel.SOLID,
    val hurtOpen: Boolean = false,
    val hurt: Joint? = null,
    val note: String = "",
    val errors: Map<String, String> = emptyMap(),
    val removed: Boolean = false,
    val saveError: String? = null,
)

const val REMOVED_MESSAGE = "This exercise was removed from the plan"
const val SAVE_ERROR_MESSAGE = "Could not save — try again."
const val HURT_JOINT_MESSAGE = "Pick the joint, or untick This hurt"

private const val KEY_WEIGHT = "weight"
private const val KEY_REPS = "reps"
private const val KEY_SETS = "sets"
private const val KEY_FEEL = "feel"
private const val KEY_HURT_OPEN = "hurtOpen"
private const val KEY_HURT = "hurt"
private const val KEY_NOTE = "note"

class LogViewModel(
    private val db: GymDatabase,
    private val targets: TargetService,
    private val logs: LogService,
    private val sessionId: Long,
    private val slotId: Long,
    private val exerciseId: String,
    private val today: () -> LocalDate,
    /** Settled when this slot's log is saved: the "Log <exercise>" banner for it goes away. */
    private val timerStore: TimerRunStore,
    /** What the lifter typed lives here too, so it survives process death; see [restoredOr]. */
    private val handle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val _state = MutableStateFlow(LogUiState())
    val state: StateFlow<LogUiState> = _state.asStateFlow()

    private var exercise: Exercise? = null
    private var target: Target? = null
    private var saving = false

    init {
        viewModelScope.launch {
            val entity = db.libraryDao().get(exerciseId)
            // A slot deleted from the plan has nothing to target: say so instead of failing.
            val goal = entity?.let {
                try {
                    targets.targetFor(slotId, exerciseId, today())
                } catch (e: IllegalStateException) {
                    null
                }
            }
            if (entity == null || goal == null) {
                _state.value = LogUiState(loaded = true, removed = true, exerciseName = entity?.name.orEmpty())
                timerStore.clearPendingLog(sessionId, slotId) // a reminder for a set that can no longer be logged
                return@launch
            }
            val increments = db.settingsDao().get()?.equipmentIncrements.orEmpty()
            val engine = entity.toEngine(increments)
            exercise = engine
            target = goal
            // Input restored from saved state wins over the pre-fill: a restore must not undo what was typed.
            _state.value = LogUiState(
                loaded = true,
                exerciseName = engine.name,
                showWeight = engine.isAssisted || !engine.isBodyweight,
                weightLabel = if (engine.isAssisted) "Assistance (kg)" else "Weight (kg)",
                weightStep = engine.incrementKg ?: DEFAULT_WEIGHT_STEP,
                weight = restoredOr(KEY_WEIGHT) { weightFieldText(goal.weightKg) },
                reps = restoredOr(KEY_REPS) { (goal.repGoal ?: goal.range.hi).toString() },
                sets = restoredOr(KEY_SETS) { goal.sets.toString() },
                feel = handle.get<String>(KEY_FEEL)?.let { name -> Feel.entries.firstOrNull { it.name == name } } ?: Feel.SOLID,
                hurtOpen = handle.get<Boolean>(KEY_HURT_OPEN) ?: false,
                hurt = handle.get<String>(KEY_HURT)?.let { name -> Joint.entries.firstOrNull { it.name == name } },
                note = handle.get<String>(KEY_NOTE).orEmpty(),
            )
        }
    }

    private inline fun restoredOr(key: String, prefill: () -> String): String = handle.get<String>(key) ?: prefill()

    fun setWeight(text: String) = edit(KEY_WEIGHT) { handle[KEY_WEIGHT] = text; it.copy(weight = text) }

    fun setReps(text: String) = edit(KEY_REPS) { handle[KEY_REPS] = text; it.copy(reps = text) }

    fun setSets(text: String) = edit(KEY_SETS) { handle[KEY_SETS] = text; it.copy(sets = text) }

    fun setFeel(feel: Feel) = edit(null) { handle[KEY_FEEL] = feel.name; it.copy(feel = feel) }

    fun setNote(text: String) = edit(null) { handle[KEY_NOTE] = text; it.copy(note = text) }

    /** "This hurt" is a toggle: opening shows the joints, closing forgets the choice. */
    fun toggleHurt() = edit(KEY_HURT) {
        val open = !it.hurtOpen
        handle[KEY_HURT_OPEN] = open
        handle.remove<String>(KEY_HURT)
        it.copy(hurtOpen = open, hurt = null)
    }

    /** Tapping the chosen joint again clears it. */
    fun setHurt(joint: Joint) = edit(KEY_HURT) {
        val chosen = if (it.hurt == joint) null else joint
        if (chosen == null) handle.remove<String>(KEY_HURT) else handle[KEY_HURT] = chosen.name
        it.copy(hurt = chosen)
    }

    /** Editing clears that field's error and a stale "could not save". */
    private fun edit(field: String?, change: (LogUiState) -> LogUiState) =
        _state.update { change(it).copy(errors = if (field == null) it.errors else it.errors - field, saveError = null) }

    /** Validates, then writes the log and sets the slot done; [onSaved] runs on success. A bad field shows its message instead. */
    fun save(onSaved: () -> Unit) {
        val exercise = exercise ?: return
        val target = target ?: return
        if (saving) return
        val current = _state.value
        _state.update { it.copy(saveError = null) }
        val input = LogInput(
            weightText = current.weight,
            repsText = current.reps,
            setsText = current.sets,
            feel = current.feel,
            hurt = current.hurt,
            note = current.note,
        )
        val validation = validateLog(input, exercise)
        // Open with no joint picked would save a log that never says what hurt.
        val hurtMissing = current.hurtOpen && current.hurt == null
        if (validation is LogValidation.Error || hurtMissing) {
            _state.update {
                it.copy(
                    errors = buildMap {
                        if (hurtMissing) put(KEY_HURT, HURT_JOINT_MESSAGE)
                        if (validation is LogValidation.Error) put(validation.field, validation.message)
                    },
                )
            }
            return
        }
        validation as LogValidation.Ok
        saving = true
        viewModelScope.launch {
            try {
                logs.save(sessionId, slotId, exerciseId, validation, input, target, today())
                timerStore.clearPendingLog(sessionId, slotId)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The typed input stays where it is; only the message is added.
                _state.update { it.copy(saveError = SAVE_ERROR_MESSAGE) }
            } finally {
                saving = false
            }
        }
    }
}
