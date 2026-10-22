package com.simplegym.ui.log

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SlotStatus
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.Home
import com.simplegym.ui.nav.LogRoute
import com.simplegym.ui.nav.Workout
import com.simplegym.ui.theme.MinTouch
import com.simplegym.ui.theme.SimpleGymTheme
import java.time.LocalDate
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1200dp")
class LogScreenTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController
    private val today = LocalDate.of(2026, 10, 22)

    private var sessionId = 0L
    private lateinit var slotIds: List<Long>

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        container = testContainer(db)
        container.seedOnboarded()
        val chest = db.planDao().getActiveDays().first()
        slotIds = db.planDao().getSlots(chest.id).map { it.id }
        sessionId = container.sessionService.start(chest.id, today, clubId = null)
        log("bench-press", 60.0, 12) // so bench press targets 62.5
    }

    private fun log(exerciseId: String, weightKg: Double?, reps: Int) = runBlocking {
        db.logDao().insert(
            LogEntity(
                sessionId = null, slotId = null, exerciseId = exerciseId, date = today.minusDays(3),
                weightKg = weightKg, bestReps = reps, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
            ),
        )
    }

    /** Opens Log for slot [slotIndex] with Workout beneath it, as after a finished timer run. */
    private fun showLog(exerciseId: String = "bench-press", slotIndex: Int = 0, withWorkout: Boolean = true) {
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
        rule.runOnUiThread {
            if (withWorkout) navController.navigate(Workout(sessionId))
            navController.navigate(LogRoute(sessionId, slotIds[slotIndex], exerciseId))
        }
        waitForTag("field-reps")
    }

    private fun tagExists(tag: String): Boolean {
        shadowOf(Looper.getMainLooper()).idle()
        return rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun textExists(text: String): Boolean {
        shadowOf(Looper.getMainLooper()).idle()
        return rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = rule.waitUntil(timeoutMillis = 5_000) { tagExists(tag) }

    private fun waitForText(text: String) = rule.waitUntil(timeoutMillis = 5_000) { textExists(text) }

    private fun waitForDestination(route: KClass<*>) {
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            navController.currentBackStackEntry?.destination?.hasRoute(route) == true
        }
    }

    private fun fieldText(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun type(tag: String, text: String) {
        rule.onNodeWithTag(tag).performTextClearance()
        rule.onNodeWithTag(tag).performTextInput(text)
    }

    private fun save() = rule.onNodeWithText("Save").performClick()

    private fun logsForSession() = runBlocking { db.logDao().getAll().filter { it.sessionId == sessionId } }

    private fun slotState(index: Int): String? {
        shadowOf(Looper.getMainLooper()).idle()
        return rule.onNode(hasTestTag("slot-${slotIds[index]}")).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)
    }

    @Test
    fun fieldsArePreFilledFromTheTargetAndSolidIsSelected() {
        showLog()

        assertThat(fieldText("field-weight")).isEqualTo("62.5")
        assertThat(fieldText("field-reps")).isEqualTo("12")
        assertThat(fieldText("field-sets")).isEqualTo("3")
        rule.onNodeWithTag("feel-SOLID").assertIsSelected()
        rule.onNodeWithTag("feel-EASY").assertIsNotSelected()
        rule.onNodeWithTag("feel-GRINDER").assertIsNotSelected()
        rule.onNodeWithText("Weight (kg)").assertIsDisplayed()
    }

    @Test
    fun thisHurtRevealsTheSevenJointChips() {
        showLog()
        assertThat(Joint.entries.filter { tagExists("joint-${it.name}") }).isEmpty()

        rule.onNodeWithText("This hurt").performScrollTo().assertHeightIsAtLeast(MinTouch).performClick()

        assertThat(Joint.entries).hasSize(7)
        Joint.entries.forEach { rule.onNodeWithTag("joint-${it.name}").assertExists() }
    }

    @Test
    fun saveWritesOneLogWithTheChosenJointAndReturnsToWorkoutWithTheNextSlotHighlighted() {
        showLog()
        rule.onNodeWithText("This hurt").performScrollTo().performClick()
        rule.onNodeWithTag("joint-KNEE").performScrollTo().performClick()
        rule.onNodeWithTag("feel-GRINDER").performScrollTo().performClick()

        save()
        waitForDestination(Workout::class)

        val logs = logsForSession()
        assertThat(logs).hasSize(1)
        val saved = logs.single()
        assertThat(saved.hurt).isEqualTo(Joint.KNEE)
        assertThat(saved.feel).isEqualTo(Feel.GRINDER)
        assertThat(saved.exerciseId).isEqualTo("bench-press")
        assertThat(saved.slotId).isEqualTo(slotIds[0])
        assertThat(saved.weightKg).isEqualTo(62.5)
        assertThat(saved.bestReps).isEqualTo(12)
        assertThat(saved.sets).isEqualTo(3)
        assertThat(saved.date).isEqualTo(today)
        assertThat(saved.imported).isFalse()
        val states = runBlocking { db.sessionDao().getSessionSlots(sessionId) }.associateBy { it.slotId }
        assertThat(states.getValue(slotIds[0]).status).isEqualTo(SlotStatus.DONE)

        // Back on the existing Workout entry (popped to, not a second one pushed), with the next slot highlighted.
        assertThat(navController.currentBackStackEntry!!.toRoute<Workout>()).isEqualTo(Workout(sessionId))
        assertThat(navController.previousBackStackEntry!!.destination.hasRoute(Workout::class)).isFalse()
        rule.waitUntil(timeoutMillis = 5_000) { tagExists("slot-${slotIds[1]}") && slotState(1) == "Next" }
        assertThat(slotState(0)).isEqualTo("Done")
    }

    @Test
    fun withNoWorkoutBeneathSaveOpensWorkoutOverHome() {
        showLog(withWorkout = false) // e.g. Log reached from a timer resumed on Home

        save()
        waitForDestination(Workout::class)

        assertThat(navController.currentBackStackEntry!!.toRoute<Workout>()).isEqualTo(Workout(sessionId))
        assertThat(navController.previousBackStackEntry!!.destination.hasRoute(Home::class)).isTrue()
        assertThat(logsForSession()).hasSize(1)
    }

    @Test
    fun editedValuesAndANoteAreSaved() {
        showLog()
        type("field-weight", "65,5")
        type("field-reps", "9")
        type("field-sets", "4")
        type("field-note", "  felt heavy  ")
        rule.onNodeWithTag("feel-EASY").performScrollTo().performClick()

        save()
        waitForDestination(Workout::class)

        val saved = logsForSession().single()
        assertThat(saved.weightKg).isEqualTo(65.5)
        assertThat(saved.bestReps).isEqualTo(9)
        assertThat(saved.sets).isEqualTo(4)
        assertThat(saved.feel).isEqualTo(Feel.EASY)
        assertThat(saved.note).isEqualTo("felt heavy")
        assertThat(saved.hurt).isNull()
        assertThat(saved.type).isEqualTo(SessionType.NORMAL)
    }

    @Test
    fun zeroRepsShowAnInlineErrorAndNothingIsSavedUntilItIsFixed() {
        showLog()
        type("field-reps", "0")

        save()
        waitForText("Enter reps of 1 or more.")

        assertThat(navController.currentBackStackEntry!!.destination.hasRoute(LogRoute::class)).isTrue()
        assertThat(logsForSession()).isEmpty()
        val states = runBlocking { db.sessionDao().getSessionSlots(sessionId) }.associateBy { it.slotId }
        assertThat(states.getValue(slotIds[0]).status).isEqualTo(SlotStatus.PENDING)

        type("field-reps", "10")
        assertThat(textExists("Enter reps of 1 or more.")).isFalse() // editing the field clears its error
        save()
        waitForDestination(Workout::class)
        assertThat(logsForSession().single().bestReps).isEqualTo(10)
    }

    @Test
    fun theStepperButtonsMoveWeightByTheIncrementAndRepsAndSetsByOne() {
        showLog() // the bench press step in the library is 2.5 kg
        rule.onNodeWithTag("step-weight-up").performClick()
        assertThat(fieldText("field-weight")).isEqualTo("65")
        rule.onNodeWithTag("step-weight-down").performClick()
        rule.onNodeWithTag("step-weight-down").performClick()
        assertThat(fieldText("field-weight")).isEqualTo("60")

        rule.onNodeWithTag("step-reps-down").performClick()
        assertThat(fieldText("field-reps")).isEqualTo("11")
        rule.onNodeWithTag("step-sets-up").assertHeightIsAtLeast(MinTouch).performClick()
        assertThat(fieldText("field-sets")).isEqualTo("4")
    }

    @Test
    fun anAssistedExerciseShowsAndAcceptsAnAbsoluteAssistanceAndStoresItNegative() {
        log("assisted-pull-up", -40.0, 10)
        showLog("assisted-pull-up")

        rule.onNodeWithText("Assistance (kg)").assertIsDisplayed()
        assertThat(fieldText("field-weight")).isEqualTo("40")
        type("field-weight", "35")

        save()
        waitForDestination(Workout::class)

        assertThat(logsForSession().single().weightKg).isEqualTo(-35.0)
    }

    @Test
    fun aBodyweightExerciseHasNoWeightFieldAndStoresNoWeight() {
        log("push-ups", null, 20)
        showLog("push-ups")

        assertThat(tagExists("field-weight")).isFalse()

        save()
        waitForDestination(Workout::class)

        val saved = logsForSession().single()
        assertThat(saved.weightKg).isNull()
        assertThat(saved.exerciseId).isEqualTo("push-ups")
    }

    @Test
    fun aNewExerciseStartsWithABlankWeightThatMustBeFilled() {
        showLog("chest-press-machine") // no history: the target has no weight

        assertThat(fieldText("field-weight")).isEmpty()
        save()
        waitForText("Enter a weight in kg.")
        assertThat(logsForSession()).isEmpty()
    }

    // B3: the slot was deleted from the plan (an old banner, a restored back stack): a clear state, never a crash.
    @Test
    fun aDeletedSlotShowsARemovedStateWithBackInsteadOfCrashing() {
        runBlocking { db.planDao().deleteSlot(slotIds[0]) }
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
        rule.runOnUiThread {
            navController.navigate(Workout(sessionId))
            navController.navigate(LogRoute(sessionId, slotIds[0], "bench-press"))
        }

        waitForText("This exercise was removed from the plan")

        assertThat(tagExists("field-reps")).isFalse()
        rule.onNodeWithText("Back").performClick()
        waitForDestination(Workout::class)
        assertThat(logsForSession()).isEmpty()
    }

    @Test
    fun aFailedSaveKeepsTheTypedInputAndShowsAnInlineError() {
        showLog()
        type("field-weight", "70")
        type("field-reps", "9")
        runBlocking { db.planDao().deleteSlot(slotIds[0]) } // the slot disappears under the open screen

        save()
        waitForText("Could not save — try again.")

        assertThat(navController.currentBackStackEntry!!.destination.hasRoute(LogRoute::class)).isTrue()
        assertThat(fieldText("field-weight")).isEqualTo("70")
        assertThat(fieldText("field-reps")).isEqualTo("9")
        assertThat(logsForSession()).isEmpty()
    }

    @Test
    fun theSaveErrorGoesAwayWhenTheLifterEditsAField() {
        showLog()
        runBlocking { db.planDao().deleteSlot(slotIds[0]) }
        save()
        waitForText("Could not save — try again.")

        type("field-reps", "8")

        assertThat(textExists("Could not save — try again.")).isFalse()
    }

    @Test
    fun typedInputRestoredFromSavedStateIsNotOverwrittenByThePreFill() {
        val handle = SavedStateHandle(
            mapOf(
                "weight" to "70", "reps" to "9", "sets" to "4", "note" to "left shoulder tight",
                "feel" to Feel.GRINDER.name, "hurtOpen" to true, "hurt" to Joint.KNEE.name,
            ),
        )
        val viewModel = LogViewModel(
            db = db, targets = container.targetService, logs = container.logService, sessionId = sessionId,
            slotId = slotIds[0], exerciseId = "bench-press", today = { today }, timerStore = container.timerStore,
            handle = handle,
        )
        rule.setContent { SimpleGymTheme { LogScreen(viewModel, onSaved = {}, onBack = {}) } }
        waitForTag("field-reps")

        assertThat(fieldText("field-weight")).isEqualTo("70") // the target would have said 62.5
        assertThat(fieldText("field-reps")).isEqualTo("9")
        assertThat(fieldText("field-sets")).isEqualTo("4")
        assertThat(fieldText("field-note")).isEqualTo("left shoulder tight")
        rule.onNodeWithTag("feel-GRINDER").assertIsSelected()
        rule.onNodeWithTag("joint-KNEE").assertIsSelected()
    }

    @Test
    fun editsAreWrittenToTheSavedState() {
        val handle = SavedStateHandle()
        val viewModel = LogViewModel(
            db = db, targets = container.targetService, logs = container.logService, sessionId = sessionId,
            slotId = slotIds[0], exerciseId = "bench-press", today = { today }, timerStore = container.timerStore,
            handle = handle,
        )
        rule.setContent { SimpleGymTheme { LogScreen(viewModel, onSaved = {}, onBack = {}) } }
        waitForTag("field-reps")

        type("field-reps", "7")
        rule.onNodeWithTag("feel-EASY").performScrollTo().performClick()

        assertThat(handle.get<String>("reps")).isEqualTo("7")
        assertThat(handle.get<String>("feel")).isEqualTo("EASY")
    }

    // B4: "This hurt" open with no joint picked must not save a hurt-less log.
    @Test
    fun thisHurtWithNoJointShowsAnInlineErrorAndSavesNothing() {
        showLog()
        rule.onNodeWithText("This hurt").performScrollTo().performClick()

        save()
        waitForText("Pick the joint, or untick This hurt")

        assertThat(navController.currentBackStackEntry!!.destination.hasRoute(LogRoute::class)).isTrue()
        assertThat(logsForSession()).isEmpty()

        rule.onNodeWithTag("joint-SHOULDER").performScrollTo().performClick()
        assertThat(textExists("Pick the joint, or untick This hurt")).isFalse() // choosing a joint clears it
        save()
        waitForDestination(Workout::class)
        assertThat(logsForSession().single().hurt).isEqualTo(Joint.SHOULDER)
    }

    @Test
    fun untickingThisHurtLetsAHurtLessLogSave() {
        showLog()
        rule.onNodeWithText("This hurt").performScrollTo().performClick()
        save()
        waitForText("Pick the joint, or untick This hurt")

        rule.onNodeWithText("This hurt").performScrollTo().performClick() // untick

        assertThat(textExists("Pick the joint, or untick This hurt")).isFalse()
        save()
        waitForDestination(Workout::class)
        assertThat(logsForSession().single().hurt).isNull()
    }
}
