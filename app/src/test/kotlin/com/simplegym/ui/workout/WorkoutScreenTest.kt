package com.simplegym.ui.workout

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.ClubEntity
import com.simplegym.data.ClubUnavailableEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SlotStatus
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.ui.nav.ExerciseRoute
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.Home
import com.simplegym.ui.nav.TimerRoute
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
@Config(qualifiers = "w411dp-h1200dp") // tall enough that all five rows and the Finish button are composed
class WorkoutScreenTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController
    private val today = LocalDate.of(2026, 10, 22)

    private var sessionId = 0L
    private lateinit var slotIds: List<Long>
    private val names = listOf(
        "Bench press", "Machine shoulder press", "Upright row (dumbbell)", "Triceps machine", "Triceps pushdown (cable)",
    )

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        container = testContainer(db)
        container.seedOnboarded()
        val chest = db.planDao().getActiveDays().first()
        slotIds = db.planDao().getSlots(chest.id).map { it.id }
        sessionId = container.sessionService.start(chest.id, today, clubId = null)
    }

    /** A fresh container (as after process death) over the same database, opened on the workout of [sessionId]. */
    private fun showWorkout(firstRow: String = names.first()) {
        val fresh = testContainer(db)
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(fresh, controller)
            }
        }
        rule.runOnUiThread { navController.navigate(Workout(sessionId)) }
        waitForText(firstRow)
    }

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForDestination(route: KClass<*>) {
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            destinationIs(route)
        }
    }

    private fun destinationIs(route: KClass<*>) =
        navController.currentBackStackEntry?.destination?.hasRoute(route) == true

    private fun slotState(index: Int): String? =
        rule.onNodeWithTag("slot-${slotIds[index]}").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)

    private fun hasLineThrough(text: String): Boolean {
        val results = mutableListOf<TextLayoutResult>()
        val node = rule.onNodeWithText(text).fetchSemanticsNode()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single().layoutInput.style.textDecoration?.contains(TextDecoration.LineThrough) == true
    }

    private fun logFor(slotIndex: Int, exerciseId: String, weightKg: Double?) = runBlocking {
        db.logDao().insert(
            LogEntity(
                sessionId = sessionId, slotId = slotIds[slotIndex], exerciseId = exerciseId, date = today,
                weightKg = weightKg, bestReps = 10, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
                loggedSlotId = slotIds[slotIndex],
            ),
        )
    }

    private fun inSlot(slotIndex: Int) = hasAnyAncestor(
        SemanticsMatcher("is slot-${slotIds[slotIndex]}") {
            it.config.getOrNull(SemanticsProperties.TestTag) == "slot-${slotIds[slotIndex]}"
        },
    )

    @Test
    fun showsAllFiveRowsAndMarksTheFirstAsNextWithAStartTimerButton() {
        showWorkout()

        names.forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        assertThat(slotState(0)).isEqualTo("Next")
        (1..4).forEach { assertThat(slotState(it)).isEqualTo("To do") }
        rule.onAllNodesWithText("▶ Start timer").assertCountEquals(1)
        rule.onNode(hasText("▶ Start timer") and inSlot(0)).assertIsDisplayed().assertHeightIsAtLeast(MinTouch)
        rule.onNodeWithText("Finish workout").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(MinTouch)
        rule.onNodeWithText("0 of 5 done").assertIsDisplayed()
    }

    @Test
    fun pendingRowsShowSetsRangeAndTheTargetWeightOrNew() {
        runBlocking {
            db.logDao().insert(
                LogEntity(
                    sessionId = null, slotId = null, exerciseId = "bench-press", date = today.minusDays(3),
                    weightKg = 60.0, bestReps = 12, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
                ),
            )
        }
        showWorkout()

        waitForText("3 × 8–12 @ 62.5")
        rule.onNodeWithText("3 × 8–12 @ new").assertIsDisplayed() // machine shoulder press: no history yet
        rule.onAllNodesWithText("3 × 10–12 @ new").assertCountEquals(3)
    }

    @Test
    fun tappingANonNextRowOpensThatSlotsExercise() {
        showWorkout()

        rule.onNodeWithText(names[2]).performClick()
        waitForDestination(ExerciseRoute::class)

        val route = navController.currentBackStackEntry!!.toRoute<ExerciseRoute>()
        assertThat(route.sessionId).isEqualTo(sessionId)
        assertThat(route.slotId).isEqualTo(slotIds[2])
    }

    @Test
    fun startTimerOnTheNextRowGoesStraightToTheTimerWithTheSlotsDefaultExercise() {
        showWorkout()

        rule.onNodeWithText("▶ Start timer").performClick()
        waitForDestination(TimerRoute::class)

        val route = navController.currentBackStackEntry!!.toRoute<TimerRoute>()
        assertThat(route).isEqualTo(TimerRoute(sessionId, slotIds[0], "bench-press"))
    }

    @Test
    fun startTimerRunsTheVariantTheSlotIsRotatedToForThisBlock() {
        runBlocking { db.planDao().setRotation(slotIds[0], "dumbbell-bench-press", 0) }
        showWorkout(firstRow = "Dumbbell bench press") // the next row names the variant that runs

        rule.onNodeWithText("▶ Start timer").performClick()
        waitForDestination(TimerRoute::class)

        val route = navController.currentBackStackEntry!!.toRoute<TimerRoute>()
        assertThat(route).isEqualTo(TimerRoute(sessionId, slotIds[0], "dumbbell-bench-press"))
    }

    @Test
    fun startTimerRunsTheFirstOrderedSwapWhenTheDefaultIsUnavailableAtTheSessionsClub() {
        runBlocking {
            val club = db.clubDao().insert(ClubEntity(name = "Test club"))
            db.clubDao().markUnavailable(ClubUnavailableEntity(club, "bench-press"))
            sessionId = container.sessionService.start(db.planDao().getActiveDays().first().id, today, club)
        }
        showWorkout(firstRow = "Chest press machine") // the next row names the variant that runs

        rule.onNodeWithText("▶ Start timer").performClick()
        waitForDestination(TimerRoute::class)

        val route = navController.currentBackStackEntry!!.toRoute<TimerRoute>()
        assertThat(route).isEqualTo(TimerRoute(sessionId, slotIds[0], "chest-press-machine"))
    }

    @Test
    fun aKilledSessionComesBackWithSkippedDoneAndNextIntact() {
        runBlocking {
            container.sessionService.setStatus(sessionId, slotIds[0], SlotStatus.SKIPPED)
            container.sessionService.setStatus(sessionId, slotIds[1], SlotStatus.DONE)
        }
        logFor(1, "machine-shoulder-press", 45.0)

        showWorkout() // a new container and a new ViewModel over the same database

        waitForText("skipped · come back")
        assertThat(slotState(0)).isEqualTo("Skipped")
        assertThat(slotState(1)).isEqualTo("Done")
        assertThat(slotState(2)).isEqualTo("Next")
        assertThat(slotState(3)).isEqualTo("To do")
        rule.onNodeWithText("45").assertIsDisplayed()
        assertThat(hasLineThrough(names[1])).isTrue()
        assertThat(hasLineThrough("45")).isTrue()
        assertThat(hasLineThrough(names[0])).isFalse()
        assertThat(hasLineThrough(names[2])).isFalse()
        rule.onAllNodesWithText("▶ Start timer").assertCountEquals(1)
        rule.onNode(hasText("▶ Start timer") and inSlot(2)).assertIsDisplayed()
        rule.onNodeWithText("1 of 5 done").assertIsDisplayed()
    }

    @Test
    fun whenNothingIsPendingTheFirstSkippedRowIsNext() {
        runBlocking {
            slotIds.forEachIndexed { i, id ->
                val status = if (i == 1 || i == 3) SlotStatus.SKIPPED else SlotStatus.DONE
                container.sessionService.setStatus(sessionId, id, status)
            }
        }
        slotIds.indices.filter { it != 1 && it != 3 }.forEach { logFor(it, "bench-press", 50.0) }

        showWorkout()

        waitForText("skipped · come back")
        assertThat(slotState(1)).isEqualTo("Next")
        assertThat(slotState(3)).isEqualTo("Skipped")
        rule.onNode(hasText("▶ Start timer") and inSlot(1)).assertIsDisplayed()
    }

    @Test
    fun doneRowShowsTheLoggedWeightWithoutTrailingZero() {
        runBlocking { container.sessionService.setStatus(sessionId, slotIds[0], SlotStatus.DONE) }
        logFor(0, "bench-press", 62.5)

        showWorkout()

        waitForText("62.5")
        assertThat(slotState(0)).isEqualTo("Done")
        assertThat(slotState(1)).isEqualTo("Next")
    }

    @Test
    fun finishWorkoutSkipsLeftoversKeepsDoneWritesNoLogsAndReturnsHome() {
        runBlocking { container.sessionService.setStatus(sessionId, slotIds[0], SlotStatus.DONE) }
        logFor(0, "bench-press", 60.0)
        showWorkout()

        rule.onNodeWithText("Finish workout").performScrollTo().performClick()
        waitForDestination(Home::class)

        val session = checkNotNull(runBlocking { db.sessionDao().getSession(sessionId) })
        assertThat(session.finishedAtMillis).isNotNull()
        val states = runBlocking { db.sessionDao().getSessionSlots(sessionId) }.associateBy { it.slotId }
        assertThat(states.getValue(slotIds[0]).status).isEqualTo(SlotStatus.DONE)
        assertThat(slotIds.drop(1).map { states.getValue(it).status }.toSet()).containsExactly(SlotStatus.SKIPPED)
        assertThat(runBlocking { db.logDao().count() }).isEqualTo(1)
        waitForText("Start Chest / Triceps") // Home loads its state (days, block header) asynchronously
        rule.onNodeWithText("Start Chest / Triceps").assertIsDisplayed()
    }

    @Test
    fun aRowShowsTheAmberHurtBadgeOfItsExerciseOnly() {
        runBlocking {
            db.logDao().insert(
                LogEntity(
                    sessionId = null, slotId = null, exerciseId = "bench-press", date = today.minusDays(4),
                    weightKg = 60.0, bestReps = 8, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL, hurt = Joint.SHOULDER,
                ),
            )
        }
        showWorkout()

        waitForText("⚠ Shoulder · 1×")
        // the row is one merged node (it is clickable), so the badge is part of slot 0's own text
        rule.onNode(hasTestTag("slot-${slotIds[0]}") and hasText("⚠ Shoulder · 1×")).assertIsDisplayed()
        assertThat(rule.onAllNodes(hasText("⚠", substring = true)).fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun aDoneSlotDeletedFromThePlanMidSessionKeepsItsRowAndTheCount() {
        runBlocking {
            container.sessionService.setStatus(sessionId, slotIds[1], SlotStatus.DONE)
        }
        logFor(1, "machine-shoulder-press", 45.0)
        runBlocking { container.planEditor.deleteSlot(slotIds[1]) } // its log's slotId becomes null

        showWorkout()

        waitForText("45")
        names.forEach { rule.onNodeWithText(it).assertIsDisplayed() } // still five rows, the deleted one named from its log
        assertThat(slotState(1)).isEqualTo("Done")
        assertThat(hasLineThrough(names[1])).isTrue()
        assertThat(slotState(0)).isEqualTo("Next")
        rule.onNodeWithText("1 of 5 done").assertIsDisplayed()
        rule.onAllNodesWithText("▶ Start timer").assertCountEquals(1)

        rule.onNodeWithText(names[1]).performClick() // nothing to open any more
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(destinationIs(Workout::class)).isTrue()
    }

    @Test
    fun severalDoneSlotsDeletedMidSessionEachKeepTheirOwnNameAndWeightWhateverTheOrder() {
        // Done out of order, two of them with the same exercise, then all three removed from the plan.
        runBlocking {
            listOf(3, 1, 2).forEach { container.sessionService.setStatus(sessionId, slotIds[it], SlotStatus.DONE) }
        }
        logFor(3, "triceps-pushdown-cable", 30.0)
        logFor(1, "machine-shoulder-press", 45.0)
        logFor(2, "machine-shoulder-press", 50.0)
        runBlocking { listOf(1, 2, 3).forEach { container.planEditor.deleteSlot(slotIds[it]) } }

        showWorkout()

        waitForText("45")
        rule.onNode(hasTestTag("slot-${slotIds[1]}") and hasText("Machine shoulder press")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasTestTag("slot-${slotIds[1]}") and hasText("45")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasTestTag("slot-${slotIds[2]}") and hasText("Machine shoulder press")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasTestTag("slot-${slotIds[2]}") and hasText("50")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasTestTag("slot-${slotIds[3]}") and hasText("Triceps pushdown (cable)")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasTestTag("slot-${slotIds[3]}") and hasText("30")).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Removed from plan").assertCountEquals(0)
        (1..3).forEach { assertThat(slotState(it)).isEqualTo("Done") }
        rule.onNodeWithText("3 of 5 done").assertIsDisplayed()
    }

    @Test
    fun aPendingSlotDeletedFromThePlanMidSessionStaysCountedAndNeverBecomesNext() {
        runBlocking {
            container.sessionService.setStatus(sessionId, slotIds[0], SlotStatus.DONE)
            container.planEditor.deleteSlot(slotIds[1]) // the next slot, still pending
        }
        logFor(0, "bench-press", 60.0)

        showWorkout()

        waitForText("Removed from plan")
        assertThat(slotState(1)).isEqualTo("To do")
        assertThat(slotState(2)).isEqualTo("Next") // the first live pending slot
        rule.onNodeWithText("1 of 5 done").assertIsDisplayed()
        rule.onAllNodesWithText("▶ Start timer").assertCountEquals(1)
        rule.onNode(hasText("▶ Start timer") and inSlot(2)).assertIsDisplayed()
    }

    @Test
    fun aSlotAddedToThePlanMidSessionDoesNotChangeTheCount() {
        runBlocking {
            container.planEditor.addSlot(db.planDao().getActiveDays().first().id, "bench-press", 3, 8, 12, 120)
        }

        showWorkout()

        rule.onNodeWithText("0 of 5 done").assertIsDisplayed()
        assertThat(rule.onAllNodesWithText("▶ Start timer").fetchSemanticsNodes()).hasSize(1)
    }
}
