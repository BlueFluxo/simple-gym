package com.simplegym.data

import com.simplegym.engine.BackLoad
import com.simplegym.engine.Chip
import com.simplegym.engine.Exercise
import com.simplegym.engine.Joint
import com.simplegym.engine.Muscle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reads the bundled assets; the library file also carries an `equipmentSummary` we do not model. */
val assetJson: Json = Json { ignoreUnknownKeys = true }

@Serializable
data class LibraryFile(val exercises: List<LibraryExercise>)

/** One entry of `exercise-library.json`. Muscle and back load stay as the file's strings; see [toEngine]. */
@Serializable
data class LibraryExercise(
    val id: String,
    val name: String,
    val primaryMuscle: String,
    val equipment: String,
    val swapGroup: String,
    val lowerBackLoad: String,
    val jointNotes: String,
    val stressesJoints: List<Joint>,
    val loadIncrementKg: Double? = null,
    val isAssisted: Boolean,
    val isBodyweight: Boolean,
)

/** Maps the file's title-case muscle and lower-case back load to the engine enums; unknown values throw. */
fun LibraryExercise.toEngine(): Exercise = Exercise(
    id = id,
    name = name,
    muscle = Muscle.entries.firstOrNull { it.name.equals(primaryMuscle, ignoreCase = true) }
        ?: error("Unknown primaryMuscle '$primaryMuscle' for '$name'"),
    equipment = equipment,
    swapGroup = swapGroup,
    incrementKg = loadIncrementKg,
    backLoad = BackLoad.entries.firstOrNull { it.name.equals(lowerBackLoad, ignoreCase = true) }
        ?: error("Unknown lowerBackLoad '$lowerBackLoad' for '$name'"),
    joints = stressesJoints.toSet(),
    isAssisted = isAssisted,
    isBodyweight = isBodyweight,
)

/** The Room row for a library entry; [isCustom] marks the lifter's own additions (starting-data import). */
fun LibraryExercise.toEntity(isCustom: Boolean = false): ExerciseEntity {
    val engine = toEngine()
    return ExerciseEntity(
        id = engine.id,
        name = engine.name,
        muscle = engine.muscle,
        equipment = engine.equipment,
        swapGroup = engine.swapGroup,
        incrementKg = engine.incrementKg,
        backLoad = engine.backLoad,
        joints = engine.joints,
        isAssisted = engine.isAssisted,
        isBodyweight = engine.isBodyweight,
        isCustom = isCustom,
    )
}

@Serializable
data class StarterPlanFile(val days: List<StarterDay>)

@Serializable
data class StarterDay(val name: String, val chips: List<Chip>, val slots: List<StarterSlot>)

@Serializable
data class StarterSlot(
    val exercise: String,
    val sets: Int,
    val lo: Int,
    val hi: Int,
    val roundSec: Int = 120,
    val swaps: List<String>,
)
