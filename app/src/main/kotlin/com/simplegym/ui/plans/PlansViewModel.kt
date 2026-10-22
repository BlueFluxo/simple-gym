package com.simplegym.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.data.DayEntity
import com.simplegym.data.ExerciseEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LibraryExercise
import com.simplegym.domain.INCREMENT_MESSAGE
import com.simplegym.domain.PlanEditor
import com.simplegym.domain.parseIncrementKg
import com.simplegym.engine.BackLoad
import com.simplegym.engine.Chip
import com.simplegym.engine.Joint
import com.simplegym.engine.Muscle
import com.simplegym.timer.formatClock
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L
private const val MAX_RESULTS = 10

/** Moves the element at [index] by [delta] places; the same list when that falls outside it. */
internal fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in indices || target !in indices) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}

// ---------------------------------------------------------------- Plans list

/**
 * [days] and [customExercises] are null until the first read, so an empty list is not flashed while loading.
 * [archivedDays] and [archivedExercises] fill the Archived section, each with a Restore.
 */
data class PlansUi(
    val days: List<DayEntity>?,
    val customExercises: List<ExerciseEntity>?,
    val archivedDays: List<DayEntity> = emptyList(),
    val archivedExercises: List<ExerciseEntity> = emptyList(),
)

class PlansViewModel(db: GymDatabase, private val editor: PlanEditor) : ViewModel() {
    val ui: StateFlow<PlansUi> = combine(
        db.planDao().observeActiveDays(),
        db.libraryDao().observeActive().map { all -> all.filter { it.isCustom } },
        db.planDao().observeArchivedDays(),
        db.libraryDao().observeArchived(),
        ::PlansUi,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlansUi(null, null))

    private val _newDayError = MutableStateFlow<String?>(null)
    val newDayError: StateFlow<String?> = _newDayError

    /** Adds an empty day and reports its id so the screen can open it; a blank name shows an inline error instead. */
    fun addDay(name: String, onAdded: (dayId: Long) -> Unit) {
        viewModelScope.launch {
            try {
                val id = editor.addDay(name, emptySet())
                _newDayError.value = null
                onAdded(id)
            } catch (e: IllegalArgumentException) {
                _newDayError.value = "Give the day a name"
            }
        }
    }

    fun clearNewDayError() {
        _newDayError.value = null
    }

    fun moveDay(dayId: Long, delta: Int) {
        val ids = ui.value.days.orEmpty().map { it.id }
        val moved = ids.moved(ids.indexOf(dayId), delta)
        if (moved == ids) return
        viewModelScope.launch { editor.reorderDays(moved) }
    }

    fun restoreDay(dayId: Long) {
        viewModelScope.launch { editor.restoreDay(dayId) }
    }

    fun restoreExercise(exerciseId: String) {
        viewModelScope.launch { editor.restoreExercise(exerciseId) }
    }

    /** Archives the exercise when it has history or is in a plan, otherwise deletes it (see [PlanEditor.deleteExercise]). */
    fun deleteExercise(exerciseId: String) {
        viewModelScope.launch { editor.deleteExercise(exerciseId) }
    }
}

// ---------------------------------------------------------------- Day editor

data class DaySlotUi(val slotId: Long, val exerciseName: String, val detail: String, val archived: Boolean)

/** [missing] is set once the day is gone or archived (deleting a day with history archives it), which sends the screen back. */
data class DayEditorUi(
    val loaded: Boolean,
    val missing: Boolean,
    val name: String,
    val chips: Set<Chip>,
    val slots: List<DaySlotUi>,
)

fun slotDetail(sets: Int, lo: Int, hi: Int, roundSec: Int): String =
    "$sets × $lo–$hi · ${formatClock(roundSec)} rounds"

class DayEditorViewModel(
    private val db: GymDatabase,
    private val editor: PlanEditor,
    private val dayId: Long,
) : ViewModel() {
    val ui: StateFlow<DayEditorUi> = combine(
        db.planDao().observeDay(dayId),
        db.planDao().observeSlots(dayId),
        db.libraryDao().observeAll(),
    ) { day, slots, library ->
        val byId = library.associateBy { it.id }
        DayEditorUi(
            loaded = true,
            missing = day == null || day.archived,
            name = day?.name.orEmpty(),
            chips = day?.chips.orEmpty(),
            slots = slots.map { slot ->
                val exercise = byId[slot.defaultExerciseId]
                DaySlotUi(
                    slotId = slot.id,
                    exerciseName = exercise?.name ?: slot.defaultExerciseId,
                    detail = slotDetail(slot.sets, slot.lo, slot.hi, slot.roundSec),
                    archived = exercise?.archived == true,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DayEditorUi(false, false, "", emptySet(), emptyList()))

    private val _nameError = MutableStateFlow<String?>(null)
    val nameError: StateFlow<String?> = _nameError

    fun rename(name: String) {
        viewModelScope.launch {
            try {
                editor.renameDay(dayId, name)
                _nameError.value = null
            } catch (e: IllegalArgumentException) {
                _nameError.value = "Give the day a name"
            }
        }
    }

    fun toggleChip(chip: Chip) {
        val chips = ui.value.chips
        viewModelScope.launch { editor.setDayChips(dayId, if (chip in chips) chips - chip else chips + chip) }
    }

    /** Sets the chips to those that train the muscles of this day's slots (see [PlanEditor.proposeChips]). */
    fun suggestChips() {
        viewModelScope.launch {
            val library = db.libraryDao().getAll().associateBy { it.id }
            val muscles = db.planDao().getSlots(dayId).mapNotNull { library[it.defaultExerciseId]?.muscle }
            val proposed = editor.proposeChips(muscles)
            if (proposed.isNotEmpty()) editor.setDayChips(dayId, proposed)
        }
    }

    fun moveSlot(slotId: Long, delta: Int) {
        val ids = ui.value.slots.map { it.slotId }
        val moved = ids.moved(ids.indexOf(slotId), delta)
        if (moved == ids) return
        viewModelScope.launch { editor.reorderSlots(dayId, moved) }
    }

    /** Archives the day: it leaves Home and round counting, its history stays, and Plans can restore it. */
    fun archiveDay() {
        viewModelScope.launch { editor.archiveDay(dayId) }
    }

    /** Deletes the day, or archives it when it has sessions. */
    fun deleteDay() {
        viewModelScope.launch { editor.deleteDay(dayId) }
    }
}

// ---------------------------------------------------------------- Slot editor

/** What the slot editor's fields hold. Numbers stay text so a half-typed value is not lost. */
data class SlotForm(
    val exerciseId: String? = null,
    val sets: String = "3",
    val lo: String = "8",
    val hi: String = "12",
    val roundSec: String = "120",
    val swaps: List<String> = emptyList(),
)

data class ExerciseOption(val id: String, val name: String, val detail: String, val archived: Boolean = false)

data class SlotEditorUi(
    val loaded: Boolean,
    val isNew: Boolean,
    val form: SlotForm,
    /** Field name ("exerciseId", "sets", "lo", "hi", "roundSec") to the message shown under it. */
    val errors: Map<String, String>,
    val exercise: ExerciseOption?,
    val swaps: List<ExerciseOption>,
    val query: String,
    val results: List<ExerciseOption>,
)

/** The message under a field for the [field] name a [PlanEditor] check threw. */
fun slotErrorText(field: String): String = when (field) {
    "exerciseId" -> "Pick an exercise"
    "sets" -> "Sets must be 1 to 10"
    "lo" -> "Lowest reps must be at least 1"
    "hi" -> "Highest reps can't be below the lowest"
    "roundSec" -> "Round time must be a multiple of 15 s, from 15 to 600"
    else -> "Check this value"
}

class SlotEditorViewModel(
    private val db: GymDatabase,
    private val editor: PlanEditor,
    private val dayId: Long,
    private val slotId: Long?,
) : ViewModel() {
    private val form = MutableStateFlow<SlotForm?>(null)
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            val slot = slotId?.let { db.planDao().getSlot(it) }
            form.value = if (slot == null) {
                SlotForm()
            } else {
                SlotForm(
                    exerciseId = slot.defaultExerciseId,
                    sets = slot.sets.toString(),
                    lo = slot.lo.toString(),
                    hi = slot.hi.toString(),
                    roundSec = slot.roundSec.toString(),
                    swaps = db.planDao().getSwaps(slot.id).map { it.exerciseId },
                )
            }
        }
    }

    val ui: StateFlow<SlotEditorUi> = combine(form, errors, query, db.libraryDao().observeAll()) { form, errors, query, library ->
        val byId = library.associateBy { it.id }
        fun option(id: String) = byId[id]?.let { ExerciseOption(it.id, it.name, exerciseDetail(it), it.archived) }
            ?: ExerciseOption(id, id, "", archived = true)
        val current = form ?: SlotForm()
        val needle = query.trim().lowercase(Locale.ROOT)
        SlotEditorUi(
            loaded = form != null,
            isNew = slotId == null,
            form = current,
            errors = errors,
            exercise = current.exerciseId?.let(::option),
            swaps = current.swaps.map(::option),
            query = query,
            results = if (needle.isEmpty()) {
                emptyList()
            } else {
                library.filter { !it.archived && it.name.lowercase(Locale.ROOT).contains(needle) }.take(MAX_RESULTS).map { option(it.id) }
            },
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        SlotEditorUi(false, slotId == null, SlotForm(), emptyMap(), null, emptyList(), "", emptyList()),
    )

    private fun edit(field: String?, change: (SlotForm) -> SlotForm) {
        form.update { it?.let(change) }
        if (field != null) errors.update { it - field }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun setSets(text: String) = edit("sets") { it.copy(sets = text) }
    fun setLo(text: String) = edit("lo") { it.copy(lo = text) }
    fun setHi(text: String) = edit("hi") { it.copy(hi = text) }
    fun setRoundSec(text: String) = edit("roundSec") { it.copy(roundSec = text) }

    fun useAsDefault(exerciseId: String) = edit("exerciseId") { it.copy(exerciseId = exerciseId, swaps = it.swaps - exerciseId) }

    fun addSwap(exerciseId: String) = edit(null) { if (exerciseId in it.swaps) it else it.copy(swaps = it.swaps + exerciseId) }

    fun removeSwap(exerciseId: String) = edit(null) { it.copy(swaps = it.swaps - exerciseId) }

    fun moveSwap(exerciseId: String, delta: Int) =
        edit(null) { it.copy(swaps = it.swaps.moved(it.swaps.indexOf(exerciseId), delta)) }

    /** Saves the slot and its swap list, then calls [onSaved]; a field that fails its check shows its message instead. */
    fun save(onSaved: () -> Unit) {
        val current = form.value ?: return
        val sets = current.sets.trim().toIntOrNull()
        val lo = current.lo.trim().toIntOrNull()
        val hi = current.hi.trim().toIntOrNull()
        val roundSec = current.roundSec.trim().toIntOrNull()
        val unreadable = listOf("sets" to sets, "lo" to lo, "hi" to hi, "roundSec" to roundSec)
            .filter { it.second == null }.map { it.first }
        val exerciseId = current.exerciseId
        if (exerciseId == null || unreadable.isNotEmpty()) {
            errors.value = (unreadable + listOfNotNull("exerciseId".takeIf { exerciseId == null })).associateWith(::slotErrorText)
            return
        }
        viewModelScope.launch {
            try {
                val id = if (slotId == null) {
                    editor.addSlot(dayId, exerciseId, sets!!, lo!!, hi!!, roundSec!!)
                } else {
                    editor.updateSlot(slotId, exerciseId, sets!!, lo!!, hi!!, roundSec!!)
                    slotId
                }
                editor.setSwaps(id, current.swaps)
                errors.value = emptyMap()
                onSaved()
            } catch (e: IllegalArgumentException) {
                val field = e.message.orEmpty()
                errors.value = mapOf(field to slotErrorText(field))
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = slotId ?: return
        viewModelScope.launch {
            editor.deleteSlot(id)
            onDeleted()
        }
    }
}

private fun exerciseDetail(e: ExerciseEntity): String {
    val muscle = e.muscle.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    return "$muscle · ${e.equipment}" + if (e.archived) " · archived" else ""
}

// ---------------------------------------------------------------- Exercise editor

data class ExerciseForm(
    val name: String = "",
    val muscle: Muscle = Muscle.CHEST,
    val equipment: String = "Other",
    val swapGroup: String? = null,
    val backLoad: BackLoad = BackLoad.LOW,
    val joints: Set<Joint> = emptySet(),
    val bodyweight: Boolean = false,
    val assisted: Boolean = false,
    val incrementKg: String = "2.5",
)

data class ExerciseEditorUi(
    val form: ExerciseForm,
    val nameError: String?,
    val incrementError: String?,
    val equipmentOptions: List<String>,
    /** The swap groups the library already uses for the chosen muscle; the first is what the exercise joins by default. */
    val swapGroupOptions: List<String>,
)

/** Muscles a custom exercise can train: every one but the mobility pseudo-muscle. */
val CustomMuscles: List<Muscle> = Muscle.entries.filter { it != Muscle.MOBILITY }

class ExerciseEditorViewModel(private val db: GymDatabase, private val editor: PlanEditor) : ViewModel() {
    private val form = MutableStateFlow(ExerciseForm())
    private val nameError = MutableStateFlow<String?>(null)
    private val incrementError = MutableStateFlow<String?>(null)

    val ui: StateFlow<ExerciseEditorUi> = combine(
        form, nameError, incrementError, db.libraryDao().observeAll(),
    ) { form, nameError, incrementError, library ->
        ExerciseEditorUi(
            form = form,
            nameError = nameError,
            incrementError = incrementError,
            equipmentOptions = library.map { it.equipment }.distinct().sorted(),
            swapGroupOptions = library.filter { it.muscle == form.muscle }.map { it.swapGroup }.distinct(),
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        ExerciseEditorUi(ExerciseForm(), null, null, emptyList(), emptyList()),
    )

    fun setName(text: String) {
        form.update { it.copy(name = text) }
        nameError.value = null
    }

    fun setMuscle(muscle: Muscle) = form.update { it.copy(muscle = muscle, swapGroup = null) }
    fun setEquipment(equipment: String) = form.update { it.copy(equipment = equipment) }
    fun setSwapGroup(group: String) = form.update { it.copy(swapGroup = group) }
    fun setBackLoad(load: BackLoad) = form.update { it.copy(backLoad = load) }
    fun setBodyweight(on: Boolean) = form.update { it.copy(bodyweight = on, assisted = it.assisted && !on) }
    fun setAssisted(on: Boolean) = form.update { it.copy(assisted = on) }
    fun setIncrement(text: String) {
        form.update { it.copy(incrementKg = text) }
        incrementError.value = null
    }
    fun toggleJoint(joint: Joint) = form.update { it.copy(joints = if (joint in it.joints) it.joints - joint else it.joints + joint) }

    /**
     * Saves the exercise and calls [onSaved] with a note to show ("Restored <name>" when the name brought an archived
     * exercise back). A non-bodyweight exercise needs a step the way Settings reads it; a bad one shows under its field.
     */
    fun save(onSaved: (note: String?) -> Unit) {
        val f = form.value
        val increment = if (f.bodyweight) null else parseIncrementKg(f.incrementKg)
        if (!f.bodyweight && increment == null) {
            incrementError.value = INCREMENT_MESSAGE
            if (f.name.isBlank()) nameError.value = "Give the exercise a name"
            return
        }
        viewModelScope.launch {
            try {
                val groups = db.libraryDao().getAll().filter { it.muscle == f.muscle }.map { it.swapGroup }
                val added = editor.addCustomExercise(
                    LibraryExercise(
                        id = "",
                        name = f.name,
                        primaryMuscle = f.muscle.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) },
                        equipment = f.equipment,
                        swapGroup = f.swapGroup ?: groups.firstOrNull() ?: "custom-${f.muscle.name.lowercase(Locale.ROOT)}",
                        lowerBackLoad = f.backLoad.name.lowercase(Locale.ROOT),
                        jointNotes = "",
                        stressesJoints = f.joints.sorted(),
                        loadIncrementKg = increment,
                        isAssisted = f.assisted,
                        isBodyweight = f.bodyweight,
                    ),
                )
                onSaved(added.note)
            } catch (e: IllegalArgumentException) {
                nameError.value = if (f.name.isBlank()) "Give the exercise a name" else "An exercise with this name already exists"
            }
        }
    }
}
