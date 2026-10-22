package com.simplegym.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.engine.BackLoad
import com.simplegym.engine.Chip
import com.simplegym.engine.Joint
import com.simplegym.engine.Muscle
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AssetsTest {
    private val json = assetJson

    private fun asset(name: String): String =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .assets.open(name).bufferedReader().use { it.readText() }

    private val library: List<LibraryExercise> by lazy {
        json.decodeFromString<LibraryFile>(asset("exercise-library.json")).exercises
    }
    private val plan: StarterPlanFile by lazy {
        json.decodeFromString<StarterPlanFile>(asset("starter-plan.json"))
    }
    private val byName by lazy { library.associateBy { it.name } }

    private fun libraryExercise(name: String): LibraryExercise =
        requireNotNull(byName[name]) { "No library exercise named $name" }

    @Test
    fun slugLowercasesAndCollapsesPunctuation() {
        assertThat(slug("Hip abductor machine (out)")).isEqualTo("hip-abductor-machine-out")
        assertThat(slug("  45-degree back extension ")).isEqualTo("45-degree-back-extension")
    }

    @Test
    fun libraryHas142ExercisesWithUniqueSlugIds() {
        assertThat(library).hasSize(142)
        assertThat(library.map { it.id }.toSet()).hasSize(142)
        library.forEach { assertThat(it.id).isEqualTo(slug(it.name)) }
    }

    @Test
    fun everyExerciseMapsToEngineEnums() {
        val engine = library.map { it.toEngine() }
        assertThat(engine.map { it.id }).containsExactlyElementsIn(library.map { it.id }).inOrder()
        val bench = libraryExercise("Bench press").toEngine()
        assertThat(bench.muscle).isEqualTo(Muscle.CHEST)
        assertThat(bench.backLoad).isEqualTo(BackLoad.LOW)
        assertThat(bench.incrementKg).isEqualTo(2.5)
        assertThat(libraryExercise("Barbell bent-over row").toEngine().backLoad).isEqualTo(BackLoad.HIGH)
        assertThat(libraryExercise("Barbell overhead press").toEngine().backLoad).isEqualTo(BackLoad.MODERATE)
    }

    @Test
    fun bodyweightAndAssistedFlagsAndMissingIncrementSurvive() {
        val pushUps = libraryExercise("Push-ups").toEngine()
        assertThat(pushUps.isBodyweight).isTrue()
        assertThat(pushUps.incrementKg).isNull()
        val assisted = libraryExercise("Assisted pull-up").toEngine()
        assertThat(assisted.isAssisted).isTrue()
    }

    @Test
    fun unknownMuscleOrBackLoadFailsLoudly() {
        val good = libraryExercise("Bench press")
        assertThrows(IllegalStateException::class.java) { good.copy(primaryMuscle = "Wings").toEngine() }
        assertThrows(IllegalStateException::class.java) { good.copy(lowerBackLoad = "extreme").toEngine() }
    }

    @Test
    fun everyExerciseStatesJointsAndSpotChecksHold() {
        assertThat(libraryExercise("Bench press").stressesJoints)
            .containsAtLeast(Joint.SHOULDER, Joint.ELBOW)
        assertThat(libraryExercise("Barbell back squat").stressesJoints).contains(Joint.KNEE)
        assertThat(libraryExercise("Chin tucks").stressesJoints).contains(Joint.NECK)
        assertThat(libraryExercise("Barbell back squat").toEngine().joints).contains(Joint.LOWER_BACK)
        // Cues that merely mention a body part are not joint stress.
        assertThat(libraryExercise("Chest-supported row (machine)").stressesJoints)
            .doesNotContain(Joint.LOWER_BACK)
    }

    @Test
    fun starterPlanMatchesSpecShape() {
        assertThat(plan.days.map { it.name })
            .containsExactly("Chest / Triceps", "Back / Biceps", "Legs / Calves").inOrder()
        assertThat(plan.days.map { it.slots.size }).containsExactly(5, 7, 8).inOrder()
        assertThat(plan.days[0].chips).containsExactly(Chip.CHEST, Chip.TRICEPS, Chip.SHOULDERS)
        assertThat(plan.days[1].chips).containsExactly(Chip.BACK, Chip.BICEPS)
        assertThat(plan.days[2].chips).containsExactly(Chip.LEGS, Chip.CALVES)
    }

    @Test
    fun starterSlotsMatchSpecRows() {
        val chest = plan.days[0].slots
        assertThat(chest[0]).isEqualTo(
            StarterSlot(
                "Bench press", 3, 8, 12, 120,
                listOf("Chest press machine", "Dumbbell bench press", "Machine press (plate-loaded)", "Push-ups"),
            ),
        )
        val lunges = plan.days[2].slots[1]
        assertThat(lunges.exercise).isEqualTo("Walking lunges")
        assertThat(listOf(lunges.sets, lunges.lo, lunges.hi)).containsExactly(3, 10, 12).inOrder()
        assertThat(plan.days[2].slots.last().exercise).isEqualTo("Standing calf raise machine")
        assertThat(plan.days.flatMap { it.slots }.map { it.roundSec }.toSet()).containsExactly(120)
    }

    @Test
    fun starterNamesAllResolveToLibraryAndAvoidHighBackLoad() {
        val slots = plan.days.flatMap { it.slots }
        for (slot in slots) {
            for (name in listOf(slot.exercise) + slot.swaps) {
                val ex = libraryExercise(name)
                assertThat(ex.toEngine().backLoad).isNotEqualTo(BackLoad.HIGH)
            }
        }
    }
}
