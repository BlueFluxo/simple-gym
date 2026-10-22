package com.simplegym.ui.progress

import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.engine.Feel
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.Progress
import com.simplegym.ui.nav.ProgressDetail
import com.simplegym.ui.theme.SimpleGymTheme
import java.time.LocalDate
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
class ProgressScreenTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        container = testContainer(db)
        container.seedOnboarded()
    }

    private fun logOf(exerciseId: String, date: LocalDate, weight: Double?, reps: Int = 8) = runBlocking {
        db.logDao().insert(
            LogEntity(
                sessionId = null, slotId = null, exerciseId = exerciseId, date = date, weightKg = weight, bestReps = reps,
                sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
            ),
        )
    }

    private fun nameOf(exerciseId: String) = runBlocking { db.libraryDao().get(exerciseId)!!.name }

    private fun someExerciseIds(): List<String> = runBlocking { db.libraryDao().getAll().map { it.id }.take(3) }

    private fun show() {
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
        rule.runOnUiThread { navController.navigate(Progress) }
        waitFor { navController.currentBackStackEntry?.destination?.hasRoute(Progress::class) == true }
    }

    private fun waitForText(text: String) = waitFor { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    @Test
    fun listsOnlyExercisesWithHistoryMostRecentFirstAndOpensTheChart() {
        val (a, b, c) = someExerciseIds()
        logOf(a, LocalDate.of(2026, 10, 1), 40.0)
        logOf(b, LocalDate.of(2026, 10, 20), 60.0)
        logOf(b, LocalDate.of(2026, 10, 5), 55.0)
        show()
        waitForText(nameOf(a))

        rule.onNodeWithText(nameOf(b)).assertExists()
        assertThat(rule.onAllNodes(hasText(nameOf(c))).fetchSemanticsNodes()).isEmpty()
        fun top(id: String) = rule.onNodeWithText(nameOf(id)).fetchSemanticsNode().positionInRoot.y
        assertThat(top(b)).isLessThan(top(a))

        rule.onNodeWithText(nameOf(b)).performClick()

        waitFor { navController.currentBackStackEntry?.destination?.hasRoute(ProgressDetail::class) == true }
        waitForText("Latest 60 kg")
        rule.onNodeWithText(nameOf(b)).assertExists() // the chart is titled with the exercise name
        rule.onNodeWithTag("weight-chart").assertExists()
    }

    @Test
    fun anArchivedExerciseWithHistoryIsStillListed() {
        val (a) = someExerciseIds()
        logOf(a, LocalDate.of(2026, 10, 1), 40.0)
        runBlocking { db.libraryDao().setArchived(a, true) }
        show()

        waitForText(nameOf(a))
    }

    @Test
    fun withNoHistoryAtAllItSaysSo() {
        show()

        waitForText("No history yet")
    }

    @Test
    fun anExerciseWhoseLogsHaveNoWeightShowsAMessageInsteadOfAChart() {
        val (a) = someExerciseIds()
        logOf(a, LocalDate.of(2026, 10, 1), weight = null, reps = 12)
        show()
        waitForText(nameOf(a))

        rule.onNodeWithText(nameOf(a)).performClick()

        waitForText("No weighted sessions yet")
    }
}
