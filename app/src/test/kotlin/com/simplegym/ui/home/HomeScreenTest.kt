package com.simplegym.ui.home

import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.TEST_NOW
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.ClubEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SessionEntity
import com.simplegym.engine.Feel
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerUiState
import com.simplegym.timer.TimerRunStore
import com.simplegym.timer.emomRun
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.Home
import com.simplegym.ui.nav.Plans
import com.simplegym.ui.nav.Progress
import com.simplegym.ui.nav.Settings
import com.simplegym.ui.nav.StandaloneTimer
import com.simplegym.ui.nav.TimerRoute
import com.simplegym.ui.nav.Workout
import com.simplegym.ui.theme.MinTouch
import com.simplegym.ui.theme.SimpleGymTheme
import java.time.Duration
import java.time.LocalDate
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp") // a phone-sized screen, so all three day cards are on screen
class HomeScreenTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

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

    private fun showApp() {
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Room results reach the main thread through its looper, which Robolectric only runs when told to. */
    private fun waitForDestination(route: KClass<*>) {
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            destinationIs(route)
        }
    }

    private fun dayId(name: String): Long = runBlocking { db.planDao().getActiveDays().first { it.name == name }.id }

    /** A finished session of 5 slots with the day's first [logged] slots logged. */
    private fun finishedSession(dayName: String, daysAgo: Long, logged: Int = 0) {
        runBlocking {
            val started = TEST_NOW - daysAgo * DAY_MS
            val sessionId = db.sessionDao().insertSession(
                SessionEntity(
                    dayId = dayId(dayName),
                    date = today.minusDays(daysAgo),
                    startedAtMillis = started,
                    finishedAtMillis = started + HOUR_MS,
                    slotsTotal = 5,
                ),
            )
            db.planDao().getSlots(dayId(dayName)).take(logged).forEach { slot ->
                db.logDao().insert(
                    LogEntity(
                        sessionId = sessionId, slotId = slot.id, exerciseId = slot.defaultExerciseId,
                        date = today.minusDays(daysAgo), weightKg = 40.0, bestReps = 8, sets = 3,
                        feel = Feel.SOLID, type = SessionType.NORMAL,
                    ),
                )
            }
        }
    }

    /**
     * The "Suggested today" label must sit in the [featured] day's own card (exactly one label on screen),
     * and that card must be listed above every one of the [others].
     */
    private fun assertFeaturedFirst(featured: String, others: List<String>) {
        rule.onAllNodesWithText("Suggested today").assertCountEquals(1)
        rule.onNode(hasText("Suggested today") and hasParent(hasAnyChild(hasText(featured)))).assertIsDisplayed()
        val featuredTop = rule.onNodeWithText(featured).getUnclippedBoundsInRoot().top
        others.forEach { other ->
            assertThat(featuredTop).isLessThan(rule.onNodeWithText(other).getUnclippedBoundsInRoot().top)
        }
    }

    private fun destinationIs(route: KClass<*>) =
        navController.currentBackStackEntry?.destination?.hasRoute(route) == true

    @Test
    fun showsTheThreeDayNames() {
        showApp()

        waitForText("Chest / Triceps")
        rule.onNodeWithText("Back / Biceps").assertIsDisplayed()
        rule.onNodeWithText("Legs / Calves").assertIsDisplayed()
    }

    @Test
    fun headerShowsTheBlockAndRound() {
        showApp()

        waitForText("Block 1 · Round 1 of 4")
    }

    @Test
    fun headerMovesToTheNextRoundOnceEveryDayHasHalfItsSlotsLogged() {
        listOf("Chest / Triceps", "Back / Biceps", "Legs / Calves").forEachIndexed { i, day ->
            finishedSession(day, daysAgo = 3L - i, logged = 3)
        }
        showApp()

        waitForText("Block 1 · Round 2 of 4")
    }

    @Test
    fun suggestsTheFirstDayWhenNothingIsDoneAndEveryDayHasItsOwnStart() {
        showApp()

        waitForText("Start Chest / Triceps")
        assertFeaturedFirst("Chest / Triceps", others = listOf("Back / Biceps", "Legs / Calves"))
        rule.onNodeWithText("Start Back / Biceps").assertIsDisplayed()
        rule.onNodeWithText("Start Legs / Calves").assertIsDisplayed()
        rule.onAllNodesWithText("Not done yet").assertCountEquals(3)
    }

    @Test
    fun suggestsTheDayWithTheOldestLastDoneAndShowsHowLongAgo() {
        finishedSession("Chest / Triceps", daysAgo = 2, logged = 1)
        finishedSession("Back / Biceps", daysAgo = 5, logged = 1)
        finishedSession("Legs / Calves", daysAgo = 1, logged = 1)
        showApp()

        waitForText("Last done 5 days ago")
        rule.onNodeWithText("Last done 2 days ago").assertIsDisplayed()
        rule.onNodeWithText("Last done yesterday").assertIsDisplayed()
        assertFeaturedFirst("Back / Biceps", others = listOf("Chest / Triceps", "Legs / Calves"))
        rule.onNodeWithText("Start Back / Biceps").assertIsDisplayed()
        rule.onNodeWithText("Start Chest / Triceps").assertIsDisplayed()
    }

    @Test
    fun appBarButtonsAreAtLeastMinTouchSquare() {
        showApp()
        waitForText("Chest / Triceps")

        listOf("Timer", "Progress", "Plans", "Settings").forEach { label ->
            rule.onNodeWithContentDescription(label)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(MinTouch)
                .assertHeightIsAtLeast(MinTouch)
        }
        rule.onNodeWithText("Simple Gym").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp")
    fun appBarStillShowsTitleAndAllFourButtonsOnA320dpScreen() {
        showApp()
        waitForText("Chest / Triceps")

        listOf("Timer", "Progress", "Plans", "Settings").forEach { label ->
            rule.onNodeWithContentDescription(label).assertIsDisplayed().assertWidthIsAtLeast(MinTouch)
        }
        rule.onNodeWithText("Simple Gym").assertIsDisplayed()
    }

    @Test
    fun resumeReplacesStartWhenASessionIsOpen() {
        val sessionId = runBlocking {
            container.sessionService.start(dayId("Chest / Triceps"), today, clubId = null)
        }
        showApp()

        waitForText("Resume Chest / Triceps")
        rule.onAllNodes(hasText("Start Chest / Triceps")).assertCountEquals(0)
        rule.onNodeWithText("Resume Chest / Triceps").performClick()
        waitForDestination(Workout::class)

        assertThat(destinationIs(Workout::class)).isTrue()
        assertThat(navController.currentBackStackEntry!!.toRoute<Workout>().sessionId).isEqualTo(sessionId)
        // Let the workout screen finish loading too, so the test does not end halfway through its first composition.
        waitForText("0 of ${runBlocking { db.sessionDao().observeOpenSession().first() }!!.slotsTotal} done")
    }

    @Test
    fun tappingAnotherDaysStartStartsThatDayAndOpensTheWorkout() {
        showApp()

        waitForText("Start Legs / Calves")
        rule.onNodeWithText("Start Legs / Calves").performClick()
        waitForDestination(Workout::class)

        val session = checkNotNull(runBlocking { db.sessionDao().observeOpenSession().first() })
        assertThat(session.dayId).isEqualTo(dayId("Legs / Calves"))
        assertThat(session.slotsTotal).isEqualTo(runBlocking { db.planDao().countSlotsForDay(session.dayId) })
        assertThat(navController.currentBackStackEntry!!.toRoute<Workout>().sessionId).isEqualTo(session.id)
        waitForText("0 of ${session.slotsTotal} done")
    }

    @Test
    fun startPassesTheCurrentClubToTheSession() {
        val clubId = runBlocking {
            val id = db.clubDao().insert(ClubEntity(name = "Club A"))
            db.settingsDao().upsert(checkNotNull(db.settingsDao().get()).copy(currentClubId = id))
            id
        }
        showApp()

        waitForText("Start Back / Biceps")
        rule.onNodeWithText("Start Back / Biceps").performClick()
        waitForDestination(Workout::class)

        val session = checkNotNull(runBlocking { db.sessionDao().observeOpenSession().first() })
        assertThat(session.clubId).isEqualTo(clubId)
    }

    @Test
    fun appBarButtonsNavigateToTheirScreens() {
        showApp()
        waitForText("Chest / Triceps")

        rule.onNodeWithContentDescription("Timer").performClick()
        rule.waitForIdle()
        assertThat(destinationIs(StandaloneTimer::class)).isTrue()
        rule.runOnUiThread { navController.popBackStack() }
        rule.waitForIdle()
        assertThat(destinationIs(Home::class)).isTrue()

        rule.onNodeWithContentDescription("Progress").performClick()
        rule.waitForIdle()
        assertThat(destinationIs(Progress::class)).isTrue()
        waitForText("No history yet")
        rule.runOnUiThread { navController.popBackStack() }
        rule.waitForIdle()
        assertThat(destinationIs(Home::class)).isTrue()

        rule.onNodeWithContentDescription("Plans").performClick()
        rule.waitForIdle()
        assertThat(destinationIs(Plans::class)).isTrue()
        waitForText("My exercises")
        rule.runOnUiThread { navController.popBackStack() }
        rule.waitForIdle()
        assertThat(destinationIs(Home::class)).isTrue()

        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitForIdle()
        assertThat(destinationIs(Settings::class)).isTrue()
        waitForText("Protect my lower back")
    }

    // ---- starting-data import: skipped ids ----

    @Test
    fun anImportThatSkippedUnknownIdsShowsABannerUntilDismissed() {
        container.skippedImportIds.value = listOf("benchpress", "nope")
        showApp()

        waitForText("2 entries skipped in your starting data: benchpress, nope")
        rule.onNode(hasText("Dismiss") and hasAnyAncestor(hasTestTag("import-skipped-banner"))).assertHeightIsAtLeast(MinTouch).performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasTestTag("import-skipped-banner")).fetchSemanticsNodes().isEmpty()
        }
        assertThat(container.skippedImportIds.value).isEmpty()
    }

    @Test
    fun noBannerWhenNothingWasSkipped() {
        showApp()

        waitForText("Simple Gym")
        rule.onAllNodes(hasTestTag("import-skipped-banner")).assertCountEquals(0)
    }

    // ---- timer: battery card and interrupted-timer banner ----

    private fun settings() = checkNotNull(runBlocking { db.settingsDao().get() })

    private fun interruptedRun(msAgo: Long = 130_000, rounds: Int = 3, roundSec: Int = 120) = emomRun(
        startElapsedMs = SystemClock.elapsedRealtime() - msAgo, rounds = rounds, roundSec = roundSec,
    ).copy(sessionId = 5, slotId = 6, exerciseId = "bench-press")

    @Test
    @Config(qualifiers = "w411dp-h1200dp") // the check-in chips take two rows, so the card needs a taller screen to be composed
    fun batteryCardShowsOnFirstLaunchBelowTheDayCardsAndOpensTheAppSettings() {
        showApp()

        waitForText("Keep the timer running with the screen off: Settings → Battery → Unrestricted.")
        rule.onNodeWithText("Start Chest / Triceps").assertIsDisplayed() // the 2-tap rule: Start stays on screen
        rule.onNodeWithText("Start Legs / Calves").assertIsDisplayed()
        rule.onNodeWithText("Open battery settings").assertHeightIsAtLeast(MinTouch).performClick()

        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        assertThat(started.action).isEqualTo(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(started.data.toString()).isEqualTo("package:com.simplegym")
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp") // as above: the check-in row pushes the card down
    fun dismissingTheBatteryCardHidesItForGood() {
        showApp()
        waitForText("Keep the timer running with the screen off: Settings → Battery → Unrestricted.")

        rule.onNodeWithText("Dismiss").assertHeightIsAtLeast(MinTouch).performClick()

        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodes(hasTestTag("battery-card")).fetchSemanticsNodes().isEmpty()
        }
        assertThat(settings().batteryCardDismissed).isTrue()
        assertThat(settings().timerWasKilled).isFalse()
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp") // tall enough that the card below the banner and the day cards is on screen
    fun aDismissedBatteryCardComesBackAfterATimerWasKilled() {
        runBlocking { db.settingsDao().upsert(settings().copy(batteryCardDismissed = true)) }
        TimerRunStore.from(ApplicationProvider.getApplicationContext()).save(interruptedRun())
        showApp()

        waitForText("Timer was interrupted — Resume")
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodes(hasTestTag("battery-card")).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(settings().timerWasKilled).isTrue()

        rule.onNodeWithText("Dismiss").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodes(hasTestTag("battery-card")).fetchSemanticsNodes().isEmpty()
        }
        assertThat(settings().timerWasKilled).isFalse()
    }

    @Test
    fun noBannerWhenNothingWasRunning() {
        showApp()
        waitForText("Start Chest / Triceps")
        rule.onAllNodes(hasText("Timer was interrupted — Resume")).assertCountEquals(0)
    }

    @Test
    fun noBannerWhileTheTimerServiceIsRunning() {
        TimerController.start(ApplicationProvider.getApplicationContext(), interruptedRun())
        showApp()

        waitForText("Start Chest / Triceps")
        shadowOf(Looper.getMainLooper()).idle()
        rule.onAllNodes(hasText("Timer was interrupted — Resume")).assertCountEquals(0)
    }

    @Test
    fun aRunThatFinishedWhileKilledShowsNoBannerAndIsCleared() {
        val store = TimerRunStore.from(ApplicationProvider.getApplicationContext())
        store.save(interruptedRun(msAgo = 400_000))
        showApp()

        waitForText("Start Chest / Triceps")
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            store.load() == null
        }
        rule.onAllNodes(hasText("Timer was interrupted — Resume")).assertCountEquals(0)
        assertThat(settings().timerWasKilled).isFalse()
    }

    @Test
    fun resumingAnInterruptedTimerRestartsTheSameRunAtTheRightRound() {
        val run = interruptedRun()
        TimerRunStore.from(ApplicationProvider.getApplicationContext()).save(run)
        showApp()
        waitForText("Timer was interrupted — Resume")

        rule.onNodeWithText("Timer was interrupted — Resume").assertHeightIsAtLeast(MinTouch).performClick()
        waitForDestination(TimerRoute::class)

        waitForText("Round 2 / 3")
        assertThat(TimerController.state.value.run).isEqualTo(run) // the stored run, untouched: time stays correct
        assertThat(TimerController.isServiceRunning()).isTrue()
    }

    @Test
    fun resumingARunThatExpiredAfterTheBannerWasShownStartsNothingAndClearsTheBanner() {
        val store = TimerRunStore.from(ApplicationProvider.getApplicationContext())
        store.save(interruptedRun()) // 3 x 120 s, 130 s in: still going when Home shows the banner
        showApp()
        waitForText("Timer was interrupted — Resume")

        // Home is not resumed again, so the banner stays up while the run's time runs out.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(400))
        rule.onNodeWithText("Timer was interrupted — Resume").performClick()

        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodes(hasText("Timer was interrupted — Resume")).fetchSemanticsNodes().isEmpty()
        }
        assertThat(destinationIs(TimerRoute::class)).isFalse()
        assertThat(store.load()).isNull()
        assertThat(TimerController.state.value).isEqualTo(TimerUiState.IDLE)
        assertThat(TimerController.isServiceRunning()).isFalse()
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
