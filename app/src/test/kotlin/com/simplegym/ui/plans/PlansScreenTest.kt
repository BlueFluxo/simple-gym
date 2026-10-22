package com.simplegym.ui.plans

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.GymDatabase
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.ui.nav.DayEditor
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.Plans
import com.simplegym.ui.nav.SlotEditor
import com.simplegym.ui.theme.SimpleGymTheme
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
class PlansScreenTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController
    private var chestDayId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        container = testContainer(db)
        container.seedOnboarded()
        chestDayId = db.planDao().getActiveDays().first().id
    }

    private fun show(route: Any, firstText: String) {
        val fresh = testContainer(db)
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(fresh, controller)
            }
        }
        rule.runOnUiThread { navController.navigate(route) }
        waitForText(firstText)
    }

    /** The tagged node, scrolled into view: the lists are lazy, so what is off screen is not composed yet. */
    private fun node(tag: String) = rule.onNodeWithTag("editor-list").performScrollToNode(hasTestTag(tag))
        .let { rule.onNodeWithTag(tag) }

    private fun waitForText(text: String) = waitUntil { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntil(condition: () -> Boolean) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun waitForDestination(route: KClass<*>) =
        waitUntil { navController.currentBackStackEntry?.destination?.hasRoute(route) == true }

    @Test
    fun theDownButtonMovesADayAndTheOrderIsSaved() {
        show(Plans, "Chest / Triceps")
        val before = runBlocking { db.planDao().getActiveDays().map { it.id } }

        rule.onNodeWithContentDescription("Move Chest / Triceps down").performClick()

        waitUntil { runBlocking { db.planDao().getActiveDays().map { it.id } } == listOf(before[1], before[0]) + before.drop(2) }
    }

    @Test
    fun addingADayOpensItAndAnExerciseCanBePickedAndSavedWithItsSwaps() {
        show(Plans, "Chest / Triceps")

        node("new-day-name").performTextInput("Arms")
        node("add-day").performClick()
        waitForDestination(DayEditor::class)
        waitForText("Add exercise")
        node("add-slot").performClick()
        waitForDestination(SlotEditor::class)

        node("exercise-search").performTextInput("ez-bar curl")
        waitForText("EZ-bar curl")
        node("use-ez-bar-curl").performClick()
        node("exercise-search").performTextReplacement("hammer curl")
        node("swap-add-hammer-curl").performClick()
        node("save-slot").performClick()
        waitForDestination(DayEditor::class)

        val day = runBlocking { db.planDao().getActiveDays().last() }
        assertThat(day.name).isEqualTo("Arms")
        val slot = runBlocking { db.planDao().getSlots(day.id) }.single()
        assertThat(slot.defaultExerciseId).isEqualTo("ez-bar-curl")
        assertThat(runBlocking { db.planDao().getSwaps(slot.id) }.map { it.exerciseId }).containsExactly("hammer-curl")
        waitForText("EZ-bar curl")
    }

    @Test
    fun aRepRangeWithTheHighBelowTheLowShowsTheMessageUnderItsFieldAndSavesNothing() {
        val slot = runBlocking { db.planDao().getSlots(chestDayId).first() }
        show(SlotEditor(chestDayId, slot.id), "Bench press")

        node("field-lo").performTextReplacement("10")
        node("field-hi").performTextReplacement("8")
        node("save-slot").performClick()

        waitForText("Highest reps can't be below the lowest")
        rule.onNodeWithText("Highest reps can't be below the lowest").assertIsDisplayed()
        assertThat(runBlocking { db.planDao().getSlot(slot.id) }).isEqualTo(slot)

        node("field-hi").performTextReplacement("12")
        node("save-slot").performClick()
        waitUntil { runBlocking { db.planDao().getSlot(slot.id) }?.lo == 10 }
        assertThat(runBlocking { db.planDao().getSlot(slot.id) }?.hi).isEqualTo(12)
    }

    @Test
    fun aBlankSetsFieldAndAnOffStepRoundTimeAreEachExplainedUnderTheirField() {
        val slot = runBlocking { db.planDao().getSlots(chestDayId).first() }
        show(SlotEditor(chestDayId, slot.id), "Bench press")

        node("field-sets").performTextClearance()
        node("save-slot").performClick()

        waitForText("Sets must be 1 to 10")
        rule.onNodeWithText("Sets must be 1 to 10").assertIsDisplayed()
        assertThat(runBlocking { db.planDao().getSlot(slot.id) }).isEqualTo(slot)

        node("field-sets").performTextReplacement("4")
        node("field-roundSec").performTextReplacement("100")
        node("save-slot").performClick()
        waitForText("Round time must be a multiple of 15 s, from 15 to 600")
    }

    @Test
    fun removingASlotAsksFirstAndKeepsAnyLogsOfIt() {
        val slot = runBlocking { db.planDao().getSlots(chestDayId).first() }
        show(SlotEditor(chestDayId, slot.id), "Bench press")

        node("delete-slot").performClick()
        waitForText("Its logged sets stay in your history.")
        assertThat(runBlocking { db.planDao().getSlot(slot.id) }).isNotNull() // nothing happens until it is confirmed
        rule.onNodeWithTag("confirm-delete").performClick()

        waitUntil { runBlocking { db.planDao().getSlot(slot.id) } == null }
    }

    @Test
    fun deletingADayAsksFirstThenRemovesIt() {
        show(DayEditor(chestDayId), "Delete day")

        node("delete-day").performClick()
        waitForText("A day with workouts in your history is archived instead: it leaves Home but its history stays.")
        assertThat(runBlocking { db.planDao().getDay(chestDayId) }).isNotNull()
        rule.onNodeWithTag("confirm-delete").performClick()

        waitUntil { runBlocking { db.planDao().getDay(chestDayId) } == null }
        waitUntil { navController.currentBackStackEntry?.destination?.hasRoute(DayEditor::class) == false }
    }

    @Test
    fun deletingADayWithHistoryArchivesItAndClosesTheEditor() {
        runBlocking {
            val sessionId = container.sessionService.start(chestDayId, java.time.LocalDate.of(2026, 10, 22), clubId = null)
            container.sessionService.finish(sessionId)
        }
        show(DayEditor(chestDayId), "Delete day")
        waitForDestination(DayEditor::class)

        node("delete-day").performClick()
        waitForText("A day with workouts in your history is archived instead: it leaves Home but its history stays.")
        rule.onNodeWithTag("confirm-delete").performClick()

        waitUntil { navController.currentBackStackEntry?.destination?.hasRoute(DayEditor::class) == false }
        val day = runBlocking { db.planDao().getDay(chestDayId) }
        assertThat(day).isNotNull()
        assertThat(day!!.archived).isTrue()
        assertThat(runBlocking { db.planDao().getActiveDays().map { it.id } }).doesNotContain(chestDayId)
    }

    @Test
    fun suggestFillsTheChipsFromTheMusclesOfTheSlots() {
        runBlocking { container.planEditor.setDayChips(chestDayId, emptySet()) }
        show(DayEditor(chestDayId), "Suggest from the exercises")

        node("suggest-chips").performClick()

        waitUntil { runBlocking { db.planDao().getDay(chestDayId) }?.chips?.isNotEmpty() == true }
        assertThat(runBlocking { db.planDao().getDay(chestDayId) }?.chips?.map { it.name })
            .containsExactly("CHEST", "SHOULDERS", "TRICEPS")
    }

    @Test
    fun aCustomExerciseIsCreatedFromTheFormAndDeletedAfterConfirmation() {
        show(Plans, "Chest / Triceps")

        node("new-exercise").performClick()
        waitForText("Main muscle")
        node("save-exercise").performClick()
        waitForText("Give the exercise a name")
        node("exercise-name").performTextInput("Cable crossover")
        node("save-exercise").performClick()
        waitForDestination(Plans::class)

        waitForText("Cable crossover")
        val row = runBlocking { db.libraryDao().get("cable-crossover") }
        assertThat(row?.isCustom).isTrue()
        node("delete-exercise-cable-crossover").performClick()
        waitForText("Delete Cable crossover?")
        rule.onNodeWithTag("confirm-delete").performClick()
        waitUntil { runBlocking { db.libraryDao().get("cable-crossover") } == null }
    }

    private fun openExerciseEditor() {
        show(Plans, "Chest / Triceps")
        node("new-exercise").performClick()
        waitForText("Main muscle")
    }

    @Test
    fun archiveDayLeavesHomeAndClosesTheEditorAndPlansRestoresIt() {
        show(DayEditor(chestDayId), "Archive day")

        node("archive-day").performClick()

        waitUntil { runBlocking { db.planDao().getDay(chestDayId) }?.archived == true }
        waitUntil { navController.currentBackStackEntry?.destination?.hasRoute(DayEditor::class) == false }
        assertThat(runBlocking { db.planDao().getActiveDays().map { it.id } }).doesNotContain(chestDayId)

        rule.runOnUiThread { navController.navigate(Plans) }
        waitForText("Archived")
        node("restore-day-$chestDayId").performClick()

        waitUntil { runBlocking { db.planDao().getDay(chestDayId) }?.archived == false }
        assertThat(runBlocking { db.planDao().getActiveDays().map { it.id } }).contains(chestDayId)
    }

    @Test
    fun anArchivedExerciseIsListedUnderArchivedAndRestoreBringsItBack() {
        runBlocking { container.planEditor.deleteExercise("push-ups") }
        show(Plans, "Archived")

        node("restore-exercise-push-ups").performClick()

        waitUntil { runBlocking { db.libraryDao().get("push-ups") }?.archived == false }
        waitUntil { rule.onAllNodes(hasText("Archived")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun savingAnExerciseWithTheNameOfAnArchivedOneRestoresItAndSaysSo() {
        runBlocking { container.planEditor.deleteExercise("push-ups") }
        openExerciseEditor()

        node("exercise-name").performTextInput("Push-ups")
        node("save-exercise").performClick()

        waitForDestination(Plans::class)
        waitForText("Restored Push-ups")
        assertThat(runBlocking { db.libraryDao().get("push-ups") }?.archived).isFalse()
    }

    @Test
    fun aBadOrMissingIncrementIsExplainedUnderItsFieldAndNothingIsSaved() {
        openExerciseEditor()
        node("exercise-name").performTextInput("Cable crossover")

        node("field-increment").performTextReplacement("abc")
        node("save-exercise").performClick()
        waitForText("Enter a step in kg above 0.")
        assertThat(runBlocking { db.libraryDao().get("cable-crossover") }).isNull()

        node("field-increment").performTextClearance()
        node("save-exercise").performClick()
        waitForText("Enter a step in kg above 0.")
        assertThat(runBlocking { db.libraryDao().get("cable-crossover") }).isNull()

        node("field-increment").performTextReplacement("1,25")
        node("save-exercise").performClick()
        waitForDestination(Plans::class)
        assertThat(runBlocking { db.libraryDao().get("cable-crossover") }?.incrementKg).isEqualTo(1.25)
    }

    @Test
    fun aBodyweightExerciseNeedsNoIncrement() {
        openExerciseEditor()
        node("exercise-name").performTextInput("Dips on rings")
        node("bodyweight-switch").performClick()
        node("save-exercise").performClick()

        waitForDestination(Plans::class)
        val row = runBlocking { db.libraryDao().get("dips-on-rings") }
        assertThat(row?.isBodyweight).isTrue()
        assertThat(row?.incrementKg).isNull()
    }
}
