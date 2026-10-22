package com.simplegym.ui.timer

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.GymDatabase
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.timer.PendingLog
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerRun
import com.simplegym.timer.TimerRunStore
import com.simplegym.timer.TimerUiState
import com.simplegym.ui.nav.ExerciseRoute
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.LogRoute
import com.simplegym.ui.nav.TimerRoute
import com.simplegym.ui.nav.Workout
import com.simplegym.ui.theme.MinTouch
import com.simplegym.ui.theme.SimpleGymTheme
import java.time.Duration
import java.time.LocalDate
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
class TimerScreenTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController
    private val today = LocalDate.of(2026, 10, 22)

    private var sessionId = 0L
    private var benchSlotId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        TimerController.reset()
        db = inMemoryDb()
        container = testContainer(db)
        container.seedOnboarded()
        val chest = db.planDao().getActiveDays().first()
        benchSlotId = db.planDao().getSlots(chest.id).first().id
        sessionId = container.sessionService.start(chest.id, today, clubId = null)
    }

    @After
    fun tearDown() = TimerController.reset()

    private fun showTimerRoute() {
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
        rule.runOnUiThread {
            navController.navigate(Workout(sessionId))
            navController.navigate(ExerciseRoute(sessionId, benchSlotId))
            navController.navigate(TimerRoute(sessionId, benchSlotId, "bench-press"))
        }
        waitForText("Round 1 / 3")
    }

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun waitForDestination(route: KClass<*>) =
        waitFor { navController.currentBackStackEntry?.destination?.hasRoute(route) == true }

    private fun benchSlot() = runBlocking { db.planDao().getSlot(benchSlotId)!! }

    @Test
    fun enteringFromASlotShowsRoundOneWithTheRunAlreadyStarted() {
        showTimerRoute()

        rule.onNodeWithText("Round 1 / 3").assertIsDisplayed()
        val ui = TimerController.state.value
        assertThat(ui.running).isTrue()
        assertThat(ui.run!!.slotId).isEqualTo(benchSlotId)
        assertThat(ui.run.exerciseId).isEqualTo("bench-press")
        assertThat(TimerRunStore.from(context).load()).isEqualTo(ui.run) // persisted, so a kill can be detected
        rule.onNodeWithText("Pause").assertIsDisplayed()
        rule.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun plusOnRoundsShowsFourAndSavesTheSlotRightAway() {
        showTimerRoute()

        rule.onNodeWithContentDescription("More rounds").assertHeightIsAtLeast(MinTouch).assertWidthIsAtLeast(MinTouch)
        rule.onNodeWithContentDescription("More rounds").performClick()

        waitForText("Round 1 / 4")
        rule.onNodeWithText("4").assertIsDisplayed()
        waitFor { benchSlot().timerRounds == 4 }
        assertThat(benchSlot().timerRoundSec).isNull() // only the adjusted field is remembered
        assertThat(TimerRunStore.from(context).load()!!.rounds).isEqualTo(4)
    }

    @Test
    fun adjustingRoundTimeIsRememberedInSteps() {
        showTimerRoute()

        rule.onNodeWithContentDescription("Longer rounds").performClick()

        waitForText("2:15")
        waitFor { benchSlot().timerRoundSec == 135 }
        assertThat(benchSlot().timerRounds).isNull()
    }

    @Test
    fun enteringAgainReusesTheRunInsteadOfRestartingIt() {
        val first = TimerRun(
            kind = TimerRun.EMOM, rounds = 3, roundSec = 120, workSec = 0, restSec = 0,
            startElapsedMs = SystemClock.elapsedRealtime() - 130_000, pausedAtElapsedMs = null, pausedTotalMs = 0,
            sessionId = sessionId, slotId = benchSlotId, exerciseId = "bench-press",
        )
        TimerRunStore.from(context).save(first) // what is left after the process died mid-EMOM

        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
        rule.runOnUiThread { navController.navigate(TimerRoute(sessionId, benchSlotId, "bench-press")) }

        waitForText("Round 2 / 3")
        assertThat(TimerController.state.value.run).isEqualTo(first)
    }

    @Test
    fun aFinishedRunFromASlotOpensTheLogScreen() {
        showTimerRoute()

        ShadowSystemClock.advanceBy(Duration.ofMinutes(7))
        TimerController.refresh(context) // what the service tick does

        waitForDestination(LogRoute::class)
        assertThat(navController.currentBackStackEntry!!.toRoute<LogRoute>())
            .isEqualTo(LogRoute(sessionId, benchSlotId, "bench-press"))
        assertThat(TimerController.state.value.run).isNull() // acknowledged, so it cannot fire twice
    }

    @Test
    fun aFinishedStandaloneRunReturnsWithoutOpeningLog() {
        val viewModel = TimerViewModel(
            appContext = context, db = db, targets = container.targetService,
            sessionId = null, slotId = null, exerciseId = null, today = { today },
        )
        var logged: LogRoute? = null
        var returned = false
        rule.setContent {
            SimpleGymTheme { TimerScreen(viewModel, onBack = { returned = true }, onLog = { logged = it }) }
        }
        waitForText("Round 1 / 3")
        TimerController.adjust(context, rounds = 1, roundSec = 15)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        TimerController.refresh(context)

        waitFor { returned }
        assertThat(logged).isNull()
    }

    @Test
    fun aFinishedRunAcknowledgedByTheScreenLeavesNoPendingLog() {
        showTimerRoute()

        ShadowSystemClock.advanceBy(Duration.ofMinutes(7))
        TimerController.refresh(context)

        waitForDestination(LogRoute::class)
        assertThat(TimerRunStore.from(context).pendingLog()).isNull()
    }

    // B1(b): Stop on a slot's timer ends the run and goes to that slot's log, with the timed variant.
    @Test
    fun stopOnASlotTimerStopsTheRunAndOpensLogForThatSlotAndVariant() {
        showTimerRoute()

        rule.onNodeWithText("Stop").performClick()

        waitForDestination(LogRoute::class)
        assertThat(navController.currentBackStackEntry!!.toRoute<LogRoute>())
            .isEqualTo(LogRoute(sessionId, benchSlotId, "bench-press"))
        assertThat(TimerController.state.value).isEqualTo(TimerUiState.IDLE)
        assertThat(TimerRunStore.from(context).load()).isNull()
        assertThat(TimerRunStore.from(context).pendingLog()).isNull() // Stop is not a finish
    }

    @Test
    fun stopOnAStandaloneRunOnlyStops() {
        val viewModel = TimerViewModel(
            appContext = context, db = db, targets = container.targetService,
            sessionId = null, slotId = null, exerciseId = null, today = { today },
        )
        var logged: LogRoute? = null
        var returned = false
        rule.setContent {
            SimpleGymTheme { TimerScreen(viewModel, onBack = { returned = true }, onLog = { logged = it }) }
        }
        waitForText("Round 1 / 3")

        rule.onNodeWithText("Stop").performClick()

        waitFor { returned }
        assertThat(logged).isNull()
        assertThat(TimerController.state.value.run).isNull()
    }

    private fun restoredViewModel(startNew: Boolean) = TimerViewModel(
        appContext = context, db = db, targets = container.targetService,
        sessionId = sessionId, slotId = benchSlotId, exerciseId = "bench-press", today = { today },
        handle = SavedStateHandle(mapOf(TimerViewModel.KEY_START_NEW to startNew)),
    )

    // B2: a screen restored after process death (its startNew already used up) must not start a new run.
    @Test
    fun aRestoredScreenWithAConsumedStartFlagStartsNoRun() {
        var returned = false
        val viewModel = restoredViewModel(startNew = false)
        rule.setContent {
            SimpleGymTheme { TimerScreen(viewModel, onBack = { returned = true }, onLog = {}) }
        }

        waitFor { returned } // nothing to join and nothing pending: it just leaves
        assertThat(TimerController.state.value.run).isNull()
        assertThat(TimerRunStore.from(context).load()).isNull()
    }

    @Test
    fun aRestoredScreenOpensThePendingLogOfARunThatEndedWhileAway() {
        TimerRunStore.from(context).savePendingLog(PendingLog(sessionId, benchSlotId, "bench-press"))
        var logged: LogRoute? = null
        val viewModel = restoredViewModel(startNew = false)
        rule.setContent {
            SimpleGymTheme { TimerScreen(viewModel, onBack = {}, onLog = { logged = it }) }
        }

        waitFor { logged != null }
        assertThat(logged).isEqualTo(LogRoute(sessionId, benchSlotId, "bench-press"))
        assertThat(TimerController.state.value.run).isNull()
    }

    @Test
    fun aRestoredScreenJoinsTheRunThatIsStillGoing() {
        val going = TimerRun(
            kind = TimerRun.EMOM, rounds = 3, roundSec = 120, workSec = 0, restSec = 0,
            startElapsedMs = SystemClock.elapsedRealtime() - 130_000, pausedAtElapsedMs = null, pausedTotalMs = 0,
            sessionId = sessionId, slotId = benchSlotId, exerciseId = "bench-press",
        )
        TimerRunStore.from(context).save(going)
        val viewModel = restoredViewModel(startNew = false)
        rule.setContent { SimpleGymTheme { TimerScreen(viewModel, onBack = {}, onLog = {}) } }

        waitForText("Round 2 / 3")
        assertThat(TimerController.state.value.run).isEqualTo(going)
    }

    @Test
    fun theFirstScreenStartsARunOnceAndUsesTheFlagUp() {
        val handle = SavedStateHandle()
        val viewModel = TimerViewModel(
            appContext = context, db = db, targets = container.targetService,
            sessionId = sessionId, slotId = benchSlotId, exerciseId = "bench-press", today = { today }, handle = handle,
        )
        rule.setContent { SimpleGymTheme { TimerScreen(viewModel, onBack = {}, onLog = {}) } }

        waitForText("Round 1 / 3")
        assertThat(handle.get<Boolean>(TimerViewModel.KEY_START_NEW)).isFalse() // so a restore would not start again
        assertThat(TimerController.state.value.run).isNotNull()
    }
}
