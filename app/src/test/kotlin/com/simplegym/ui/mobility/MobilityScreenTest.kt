package com.simplegym.ui.mobility

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.simplegym.data.CheckInEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.MobilityDoneEntity
import com.simplegym.engine.Chip
import com.simplegym.engine.MobilityArea
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerRun
import com.simplegym.timer.TimerRunStore
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.Home
import com.simplegym.ui.nav.Mobility
import com.simplegym.ui.nav.Workout
import com.simplegym.ui.theme.SimpleGymTheme
import java.time.Duration
import java.time.LocalDate
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
class MobilityScreenTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController
    private val today = LocalDate.of(2026, 10, 22)

    @Before
    fun setUp() = runBlocking<Unit> {
        TimerController.reset()
        db = inMemoryDb()
        container = testContainer(db)
        container.seedOnboarded()
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        appScope.cancel()
        TimerController.reset()
    }

    private fun stiffToday(vararg chips: Chip) = runBlocking {
        db.sessionDao().upsertCheckIn(CheckInEntity(today, chips.toSet()))
    }

    private fun doneOn(area: MobilityArea, date: LocalDate) = runBlocking {
        db.sessionDao().insertMobilityDone(MobilityDoneEntity(area = area.name, date = date))
    }

    private fun doneRows() = runBlocking { db.sessionDao().observeMobilitySince(today.minusDays(60)).first() }

    private fun setApp() {
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
    }

    private fun showMobility(sessionId: Long? = null) {
        setApp()
        rule.runOnUiThread { navController.navigate(Mobility(sessionId)) }
        waitForText("Choose areas")
    }

    private fun waitForText(text: String) = waitFor { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun waitForDestination(route: KClass<*>) =
        waitFor { navController.currentBackStackEntry?.destination?.hasRoute(route) == true }

    private fun waitForSelected(area: MobilityArea) =
        waitFor { runCatching { rule.onNodeWithTag("area-${area.name}").assertIsSelected() }.isSuccess }

    private fun left(area: MobilityArea) = rule.onNodeWithTag("area-${area.name}").getBoundsInRoot().left

    private fun top(area: MobilityArea) = rule.onNodeWithTag("area-${area.name}").getBoundsInRoot().top

    private fun hintCount() = rule.onAllNodes(hasText("Not done lately", substring = true)).fetchSemanticsNodes().size

    @Test
    fun lowerBackTappedStiffIsPreselectedAndListedFirst() {
        stiffToday(Chip.LOWER_BACK)
        showMobility()

        waitForSelected(MobilityArea.LOWER_BACK)
        rule.onNodeWithTag("area-NECK").assertIsNotSelected()
        rule.onNodeWithTag("area-HIPS").assertIsNotSelected()
        val reading = MobilityArea.entries.sortedWith(compareBy({ top(it) }, { left(it) }))
        assertThat(reading.first()).isEqualTo(MobilityArea.LOWER_BACK)
        assertThat(reading.drop(1)).isEqualTo(MobilityArea.entries - MobilityArea.LOWER_BACK) // the rest keep area order
        rule.onNodeWithText("Stiff today").assertExists()
    }

    @Test
    fun aStiffNeckAndLowerBackAreBothPreselectedAndOtherChipsPointAtNoArea() {
        stiffToday(Chip.NECK, Chip.LOWER_BACK, Chip.CHEST)
        showMobility()

        waitForSelected(MobilityArea.NECK)
        rule.onNodeWithTag("area-LOWER_BACK").assertIsSelected()
        rule.onNodeWithTag("area-SHOULDERS").assertIsNotSelected()
        assertThat(left(MobilityArea.NECK)).isLessThan(left(MobilityArea.LOWER_BACK))
        assertThat(left(MobilityArea.LOWER_BACK)).isLessThan(left(MobilityArea.HIPS))
    }

    @Test
    fun tappingAnAreaTogglesItAndAStartIsNeededToBuildTheRoutine() {
        showMobility()

        waitFor { runCatching { rule.onNodeWithTag("area-HIPS").assertIsNotSelected() }.isSuccess }
        rule.onNodeWithTag("area-HIPS").performClick()
        waitForSelected(MobilityArea.HIPS)
        rule.onNodeWithTag("area-HIPS").performClick()
        waitFor { runCatching { rule.onNodeWithTag("area-HIPS").assertIsNotSelected() }.isSuccess }
        assertThat(TimerController.state.value.run).isNull()
    }

    @Test
    fun startLaunchesTheIntervalTimerAndNamesTheCurrentMove() {
        stiffToday(Chip.LOWER_BACK)
        showMobility()
        waitForSelected(MobilityArea.LOWER_BACK)

        rule.onNodeWithText("Start").performClick()

        waitFor { TimerController.state.value.run != null }
        val run = TimerController.state.value.run!!
        assertThat(run.kind).isEqualTo(TimerRun.INTERVAL)
        assertThat(run.workSec).isEqualTo(30)
        assertThat(run.restSec).isEqualTo(10)
        assertThat(run.mobilityAreas).containsExactly("LOWER_BACK")
        assertThat(run.rounds).isEqualTo(12) // 6 lower-back moves, two passes to pass 8 minutes
        assertThat(run.moveNames).hasSize(run.rounds)
        assertThat(run.slotId).isNull()
        assertThat(TimerRunStore.from(context).load()).isEqualTo(run)
        waitForText("Round 1 / 12")
        rule.onNodeWithTag("move-name").assertTextEquals("Cat-cow")
        rule.onNodeWithText("Next: Child's pose").assertExists()
    }

    @Test
    fun theRoutineFollowsLibraryOrderAndSkipsArchivedMoves() {
        runBlocking { db.libraryDao().setArchived("cat-cow", true) }
        stiffToday(Chip.LOWER_BACK)
        showMobility()
        waitForSelected(MobilityArea.LOWER_BACK)

        rule.onNodeWithText("Start").performClick()

        waitFor { TimerController.state.value.run != null }
        assertThat(TimerController.state.value.run!!.moveNames.take(5))
            .containsExactly("Child's pose", "Pelvic tilt", "Knee-to-chest stretch", "Bird-dog", "Dead bug").inOrder()
    }

    @Test
    fun finishingMarksEveryAreaDoneOnceAndGoesBack() {
        stiffToday(Chip.NECK, Chip.LOWER_BACK)
        showMobility()
        waitForSelected(MobilityArea.NECK)
        rule.onNodeWithText("Start").performClick()
        waitFor { TimerController.state.value.run != null }

        ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
        TimerController.refresh(context) // what the service tick does

        waitFor { doneRows().size == 2 }
        assertThat(doneRows().map { it.area }).containsExactly("NECK", "LOWER_BACK")
        assertThat(doneRows().map { it.date }.toSet()).containsExactly(today)
        // Acknowledged (just after the rows are written), so it cannot be recorded twice.
        waitFor { TimerController.state.value.run == null }
        waitFor { navController.currentBackStackEntry?.destination?.hasRoute<Mobility>() == false }
        assertThat(doneRows()).hasSize(2)
    }

    private fun startNeckRoutineThenLeave() {
        stiffToday(Chip.NECK)
        showMobility()
        waitForSelected(MobilityArea.NECK)
        rule.onNodeWithText("Start").performClick()
        waitFor { TimerController.state.value.run != null }
        rule.runOnUiThread { navController.popBackStack() }
        waitForDestination(Home::class)
        // The screen has left the composition, so its view model is cleared and nothing there is watching the run.
        waitFor { rule.onAllNodes(hasTestTag("move-name")).fetchSemanticsNodes().isEmpty() }
    }

    private fun endTheRunWhileAway() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
        TimerController.refresh(context) // the service tick, with no Mobility screen open
    }

    @Test
    fun leavingMidRoutineStillMarksTheAreasDoneWhenTheRunEnds() {
        container.mobilityRecorder.start(appScope) // what SimpleGymApp does for the whole process
        startNeckRoutineThenLeave()

        endTheRunWhileAway()

        waitFor { doneRows().size == 1 }
        assertThat(doneRows().single().area).isEqualTo("NECK")
    }

    @Test
    fun reopeningAfterTheRunEndedWhileAwayRecordsItOnceAndStaysOnTheScreen() {
        startNeckRoutineThenLeave()
        endTheRunWhileAway()
        assertThat(doneRows()).isEmpty() // nothing was watching

        rule.runOnUiThread { navController.navigate(Mobility(null)) }

        waitFor { doneRows().size == 1 }
        waitFor { TimerController.state.value.run == null }
        waitForText("Choose areas")
        rule.waitForIdle()
        assertThat(navController.currentBackStackEntry!!.destination.hasRoute<Mobility>()).isTrue() // not sent back
        assertThat(doneRows()).hasSize(1)
    }

    @Test
    fun stoppingEarlyMarksNothingDone() {
        stiffToday(Chip.NECK)
        showMobility()
        waitForSelected(MobilityArea.NECK)
        rule.onNodeWithText("Start").performClick()
        waitFor { TimerController.state.value.run != null }

        rule.onNodeWithText("Stop").performClick()

        waitFor { TimerController.state.value.run == null }
        assertThat(doneRows()).isEmpty()
    }

    @Test
    fun theHintNamesTheMostNeglectedArea() {
        MobilityArea.entries.filter { it != MobilityArea.SHOULDERS }.forEach { doneOn(it, today.minusDays(1)) }
        showMobility()

        waitForText("Not done lately: Shoulders")
        assertThat(hintCount()).isEqualTo(1) // only the top one
    }

    @Test
    fun theHintPicksTheOldestWhenEveryAreaHasBeenDone() {
        // Latest row per area: Neck 3 days ago, Lower back 4, Hips 4, Shoulders 4, Core 6. Older rows must not count.
        doneOn(MobilityArea.NECK, today.minusDays(3))
        doneOn(MobilityArea.NECK, today.minusDays(12))
        doneOn(MobilityArea.LOWER_BACK, today.minusDays(4))
        doneOn(MobilityArea.HIPS, today.minusDays(4))
        doneOn(MobilityArea.HIPS, today.minusDays(9))
        doneOn(MobilityArea.SHOULDERS, today.minusDays(4))
        doneOn(MobilityArea.CORE, today.minusDays(6))
        showMobility()

        waitForText("Not done lately: Core")
    }

    @Test
    fun noHintWhenEveryAreaWasDoneInTheLastFewDays() {
        MobilityArea.entries.filter { it != MobilityArea.SHOULDERS }.forEach { doneOn(it, today) }
        showMobility()
        // Positive signal that the Room data has loaded: the screen first shows the hint for the one area never done.
        waitForText("Not done lately: Shoulders")

        doneOn(MobilityArea.SHOULDERS, today.minusDays(2)) // now the oldest area was done less than 3 days ago

        waitFor { hintCount() == 0 }
        rule.waitForIdle()
        assertThat(hintCount()).isEqualTo(0)
    }

    @Test
    fun aResumedMobilityRunFromHomeOpensTheMobilityScreenAtItsMove() {
        val run = TimerRun(
            kind = TimerRun.INTERVAL, rounds = 3, roundSec = 0, workSec = 30, restSec = 10,
            startElapsedMs = SystemClock.elapsedRealtime() - 45_000, pausedAtElapsedMs = null, pausedTotalMs = 0,
            sessionId = null, slotId = null, exerciseId = null,
            mobilityAreas = listOf("NECK"), moveNames = listOf("Chin tucks", "Neck rotations", "Neck side stretch"),
        )
        TimerRunStore.from(context).save(run)
        setApp()
        waitForText("Timer was interrupted — Resume")

        rule.onNodeWithText("Timer was interrupted — Resume").performClick()

        waitForDestination(Mobility::class)
        waitForText("Round 2 / 3")
        rule.onNodeWithTag("move-name").assertTextEquals("Neck rotations")
        assertThat(TimerController.state.value.run).isEqualTo(run)
    }

    @Test
    fun theWorkoutAddOnRowOpensMobilityForThatSession() {
        val chest = runBlocking { db.planDao().getActiveDays().first() }
        val sessionId = runBlocking { container.sessionService.start(chest.id, today, clubId = null) }
        setApp()
        rule.runOnUiThread { navController.navigate(Workout(sessionId)) }
        waitForText("Core / mobility add-on")

        rule.onNodeWithText("Core / mobility add-on").performClick()

        waitForDestination(Mobility::class)
        assertThat(navController.currentBackStackEntry!!.toRoute<Mobility>()).isEqualTo(Mobility(sessionId))
        waitForText("Choose areas")
        assertThat(navController.previousBackStackEntry!!.destination.hasRoute<Workout>()).isTrue()
        assertThat(navController.previousBackStackEntry!!.destination.hasRoute<Home>()).isFalse()
    }
}
