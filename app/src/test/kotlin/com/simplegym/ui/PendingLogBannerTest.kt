package com.simplegym.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
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
import com.simplegym.timer.PendingLog
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerRunStore
import com.simplegym.ui.nav.ExerciseRoute
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.LogRoute
import com.simplegym.ui.nav.Workout
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

/** B1(a) and (c): the lifter can always reach the log, from the exercise screen or from a "Log ..." banner. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1200dp")
class PendingLogBannerTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController
    private val today = LocalDate.of(2026, 10, 22)

    private var sessionId = 0L
    private lateinit var slotIds: List<Long>

    private val store: TimerRunStore get() = TimerRunStore.from(context)

    @Before
    fun setUp() = runBlocking<Unit> {
        TimerController.reset()
        store.clearPendingLog()
        db = inMemoryDb()
        container = testContainer(db)
        container.seedOnboarded()
        val chest = db.planDao().getActiveDays().first()
        slotIds = db.planDao().getSlots(chest.id).map { it.id }
        sessionId = container.sessionService.start(chest.id, today, clubId = null)
        // History for both exercises, so the log's weight is pre-filled and Save has nothing to complain about.
        listOf("bench-press", "machine-shoulder-press").forEach { id ->
            db.logDao().insert(
                LogEntity(
                    sessionId = null, slotId = null, exerciseId = id, date = today.minusDays(3),
                    weightKg = 40.0, bestReps = 12, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
                ),
            )
        }
    }

    private fun showApp() {
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun waitForText(text: String) =
        waitFor { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun textGone(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()

    private fun waitForDestination(route: KClass<*>) =
        waitFor { navController.currentBackStackEntry?.destination?.hasRoute(route) == true }

    private fun openWorkout() {
        showApp()
        rule.runOnUiThread { navController.navigate(Workout(sessionId)) }
        waitForText("Bench press")
    }

    @Test
    fun theExerciseScreenHasALogButtonThatOpensLogForTheVariantOnScreen() {
        showApp()
        rule.runOnUiThread {
            navController.navigate(Workout(sessionId))
            navController.navigate(ExerciseRoute(sessionId, slotIds[0]))
        }
        waitFor { rule.onAllNodes(hasText("Log") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Log").performClick()

        waitForDestination(LogRoute::class)
        assertThat(navController.currentBackStackEntry!!.toRoute<LogRoute>())
            .isEqualTo(LogRoute(sessionId, slotIds[0], "bench-press"))
    }

    @Test
    fun homeOffersALogBannerForAPendingLogAndOneTapOpensIt() {
        store.savePendingLog(PendingLog(sessionId, slotIds[0], "bench-press"))
        showApp()
        waitForText("Log Bench press")

        rule.onNodeWithText("Log Bench press").performClick()

        waitForDestination(LogRoute::class)
        assertThat(navController.currentBackStackEntry!!.toRoute<LogRoute>())
            .isEqualTo(LogRoute(sessionId, slotIds[0], "bench-press"))
    }

    @Test
    fun dismissingTheHomeBannerClearsThePendingLog() {
        store.savePendingLog(PendingLog(sessionId, slotIds[0], "bench-press"))
        showApp()
        waitForText("Log Bench press")

        rule.onNodeWithContentDescription("Dismiss log reminder").performClick()

        waitFor { textGone("Log Bench press") }
        assertThat(store.pendingLog()).isNull()
    }

    @Test
    fun noPendingLogMeansNoBanner() {
        showApp()
        waitForText("Simple Gym")
        assertThat(rule.onAllNodes(hasText("Log Bench press")).fetchSemanticsNodes()).isEmpty()
        assertThat(rule.onAllNodes(hasContentDescription("Dismiss log reminder")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun theWorkoutShowsTheBannerOfItsOwnSessionAndOpensTheLog() {
        store.savePendingLog(PendingLog(sessionId, slotIds[1], "machine-shoulder-press"))
        openWorkout()
        waitForText("Log Machine shoulder press")

        rule.onNodeWithText("Log Machine shoulder press").performClick()

        waitForDestination(LogRoute::class)
        assertThat(navController.currentBackStackEntry!!.toRoute<LogRoute>())
            .isEqualTo(LogRoute(sessionId, slotIds[1], "machine-shoulder-press"))
    }

    @Test
    fun theWorkoutDoesNotShowAnotherSessionsPendingLog() {
        store.savePendingLog(PendingLog(sessionId + 100, slotIds[1], "machine-shoulder-press"))
        openWorkout()
        waitForText("Bench press")

        assertThat(textGone("Log Machine shoulder press")).isTrue()
    }

    @Test
    fun dismissingTheWorkoutBannerClearsThePendingLog() {
        store.savePendingLog(PendingLog(sessionId, slotIds[0], "bench-press"))
        openWorkout()
        waitForText("Log Bench press")

        rule.onNodeWithContentDescription("Dismiss log reminder").performClick()

        waitFor { textGone("Log Bench press") }
        assertThat(store.pendingLog()).isNull()
    }

    @Test
    fun savingTheLogSettlesThePendingLog() {
        store.savePendingLog(PendingLog(sessionId, slotIds[0], "bench-press"))
        showApp()
        rule.runOnUiThread {
            navController.navigate(Workout(sessionId))
            navController.navigate(LogRoute(sessionId, slotIds[0], "bench-press"))
        }
        waitFor { rule.onAllNodes(hasTestTag("field-reps")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Save").performClick()

        waitForDestination(Workout::class)
        assertThat(store.pendingLog()).isNull()
    }

    @Test
    fun savingADifferentSlotsLogLeavesThePendingLog() {
        store.savePendingLog(PendingLog(sessionId, slotIds[0], "bench-press"))
        showApp()
        rule.runOnUiThread {
            navController.navigate(Workout(sessionId))
            navController.navigate(LogRoute(sessionId, slotIds[1], "machine-shoulder-press"))
        }
        waitFor { rule.onAllNodes(hasTestTag("field-reps")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Save").performClick()

        waitForDestination(Workout::class)
        assertThat(store.pendingLog()).isEqualTo(PendingLog(sessionId, slotIds[0], "bench-press"))
    }
}
