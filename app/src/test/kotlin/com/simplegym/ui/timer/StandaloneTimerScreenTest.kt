package com.simplegym.ui.timer

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.GymDatabase
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerRun
import com.simplegym.timer.TimerRunStore
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.Home
import com.simplegym.ui.nav.LogRoute
import com.simplegym.ui.nav.StandaloneTimer
import com.simplegym.ui.theme.MinTouch
import com.simplegym.ui.theme.SimpleGymTheme
import java.time.Duration
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1200dp")
class StandaloneTimerScreenTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController

    @Before
    fun setUp() = runBlocking<Unit> {
        TimerController.reset()
        db = inMemoryDb()
        container = testContainer(db)
        container.seedOnboarded()
    }

    @After
    fun tearDown() = TimerController.reset()

    private fun showTimer() {
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
        waitForText("Simple Gym")
        rule.onNodeWithContentDescription("Timer").performClick()
        waitForDestination(StandaloneTimer::class)
    }

    private fun waitForText(text: String) = waitFor { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun waitForDestination(route: KClass<*>) =
        waitFor { navController.currentBackStackEntry?.destination?.hasRoute(route) == true }

    private fun runNow() = TimerController.state.value.run

    @Test
    fun homesTimerButtonOpensTheEmomTabWithDefaultsAndNothingRunningYet() {
        showTimer()

        waitForText("Round time")
        rule.onNodeWithText("EMOM").assertIsSelected()
        rule.onNodeWithText("2:00").assertExists()
        rule.onNodeWithText("3").assertExists()
        assertThat(runNow()).isNull()
    }

    @Test
    fun startOnTheEmomTabRunsAnEmomWithNoSlotAndTheAdjustersStillWork() {
        showTimer()
        waitForText("Round time")
        rule.onNodeWithContentDescription("More rounds").assertHeightIsAtLeast(MinTouch).performClick() // 4 rounds

        rule.onNodeWithText("Start").assertHeightIsAtLeast(MinTouch).performClick()

        waitForText("Round 1 / 4")
        val run = runNow()!!
        assertThat(run.kind).isEqualTo(TimerRun.EMOM)
        assertThat(run.roundSec).isEqualTo(120)
        assertThat(listOf(run.sessionId, run.slotId, run.exerciseId)).containsExactly(null, null, null)
        assertThat(TimerRunStore.from(context).load()).isEqualTo(run)

        rule.onNodeWithContentDescription("Longer rounds").performClick()
        waitForText("Round 1 / 4")
        waitFor { runNow()!!.roundSec == 135 }
    }

    @Test
    fun theIntervalTabStartsAnIntervalRunWithItsOwnWorkAndRest() {
        showTimer()
        waitForText("Round time")
        rule.onNodeWithText("Interval").performClick()
        waitForText("Work")
        rule.onNodeWithContentDescription("Longer work").performClick() // 30 -> 35 s
        rule.onNodeWithContentDescription("Shorter rest").performClick() // 10 -> 5 s
        rule.onNodeWithContentDescription("Fewer rounds").performClick() // 8 -> 7

        rule.onNodeWithText("Start").performClick()

        waitFor { runNow() != null }
        val run = runNow()!!
        assertThat(run.kind).isEqualTo(TimerRun.INTERVAL)
        assertThat(listOf(run.workSec, run.restSec, run.rounds)).containsExactly(35, 5, 7).inOrder()
        assertThat(listOf(run.sessionId, run.slotId, run.exerciseId)).containsExactly(null, null, null)
        assertThat(run.isMobility).isFalse()
        waitForText("Round 1 / 7")
        rule.onNodeWithText("Work").assertExists()
    }

    @Test
    fun aFinishedIntervalRunReturnsHomeWithoutOpeningLog() {
        showTimer()
        waitForText("Round time")
        rule.onNodeWithText("Interval").performClick()
        waitForText("Work")
        rule.onNodeWithText("Start").performClick()
        waitFor { runNow() != null }

        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        TimerController.refresh(context)

        waitForDestination(Home::class)
        assertThat(navController.currentBackStackEntry!!.destination.hasRoute<LogRoute>()).isFalse()
        assertThat(runNow()).isNull() // acknowledged
    }

    @Test
    fun aFinishedEmomRunReturnsHomeWithoutOpeningLog() {
        showTimer()
        waitForText("Round time")
        rule.onNodeWithText("Start").performClick()
        waitFor { runNow() != null }

        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        TimerController.refresh(context)

        waitForDestination(Home::class)
        assertThat(runNow()).isNull()
    }

    @Test
    fun reopeningWhileAnIntervalRunIsGoingShowsItOnTheIntervalTab() {
        showTimer()
        waitForText("Round time")
        rule.onNodeWithText("Interval").performClick()
        waitForText("Work")
        rule.onNodeWithText("Start").performClick()
        waitForText("Round 1 / 8")

        rule.runOnUiThread { navController.popBackStack() }
        waitForDestination(Home::class)
        rule.onNodeWithContentDescription("Timer").performClick()
        waitForDestination(StandaloneTimer::class)

        waitForText("Round 1 / 8")
        rule.onNodeWithText("Pause").assertExists()
        assertThat(runNow()!!.kind).isEqualTo(TimerRun.INTERVAL)
    }

    @Test
    fun reopeningAfterAnIntervalRunEndedWhileAwayStaysOnTheTimerScreen() {
        showTimer()
        waitForText("Round time")
        rule.onNodeWithText("Interval").performClick()
        waitForText("Work")
        rule.onNodeWithText("Start").performClick()
        waitForText("Round 1 / 8")
        rule.runOnUiThread { navController.popBackStack() }
        waitForDestination(Home::class)
        // The screen has left the composition, so nothing there is watching the run.
        waitFor { rule.onAllNodes(hasText("Round 1 / 8")).fetchSemanticsNodes().isEmpty() }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        TimerController.refresh(context)
        assertThat(TimerController.state.value.finished).isTrue()

        rule.onNodeWithContentDescription("Timer").performClick()
        waitForDestination(StandaloneTimer::class)

        waitFor { runNow() == null } // the stale finished run is cleared away
        waitForText("Work") // the Interval tab's settings, ready for a new run
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(navController.currentBackStackEntry!!.destination.hasRoute<StandaloneTimer>()).isTrue() // not sent back
        rule.onNodeWithText("Start").assertExists()
    }

    @Test
    fun stopEndsTheRunAndGoesBack() {
        showTimer()
        waitForText("Round time")
        rule.onNodeWithText("Start").performClick()
        waitForText("Round 1 / 3")

        rule.onNodeWithText("Stop").performClick()

        waitForDestination(Home::class)
        assertThat(runNow()).isNull()
        assertThat(TimerRunStore.from(context).load()).isNull()
    }
}
