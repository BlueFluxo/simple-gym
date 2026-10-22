package com.simplegym.ui.home

import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.TEST_NOW
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.CheckInEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SessionEntity
import com.simplegym.engine.Chip
import com.simplegym.engine.Feel
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.Workout
import com.simplegym.ui.theme.MinTouch
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

private const val CHEST = "Chest / Triceps"
private const val BACK = "Back / Biceps"
private const val LEGS = "Legs / Calves"
private const val DAY_MS = 86_400_000L

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1200dp") // tall enough that the check-in row and all three day cards are composed
class CheckInTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController
    private val today = LocalDate.of(2026, 10, 22)

    @Before
    fun setUp() = runBlocking<Unit> {
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

    private fun waitUntil(condition: () -> Boolean) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun waitForText(text: String) = waitUntil { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun textCount(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

    private fun dayId(name: String): Long = runBlocking { db.planDao().getActiveDays().first { it.name == name }.id }

    /** A finished session of [dayName] [daysAgo] days ago with its first [logged] slots logged. */
    private fun trained(dayName: String, daysAgo: Long, logged: Int = 1) = runBlocking {
        val started = TEST_NOW - daysAgo * DAY_MS
        val sessionId = db.sessionDao().insertSession(
            SessionEntity(
                dayId = dayId(dayName), date = today.minusDays(daysAgo), startedAtMillis = started,
                finishedAtMillis = started + 3_600_000L, slotsTotal = 5,
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

    private fun checkIn(daysAgo: Long, vararg chips: Chip) = runBlocking {
        db.sessionDao().upsertCheckIn(CheckInEntity(today.minusDays(daysAgo), chips.toSet()))
    }

    private fun storedToday(): Set<Chip>? = runBlocking { db.sessionDao().getCheckIn(today)?.stiff }

    private fun tap(chip: String) {
        rule.onNodeWithText(chip).performClick()
    }

    /** The "Suggested today" label sits in the card of [day] (and nowhere else). */
    private fun assertSuggested(day: String) {
        rule.onAllNodesWithText("Suggested today").assertCountEquals(1)
        rule.onNode(hasText("Suggested today") and hasParent(hasAnyChild(hasText(day)))).assertIsDisplayed()
    }

    private fun waitForSuggested(day: String) = waitUntil {
        rule.onAllNodes(hasText("Suggested today") and hasParent(hasAnyChild(hasText(day)))).fetchSemanticsNodes().size == 1
    }

    /** Legs is the day done longest ago, so it is suggested until it is blocked. */
    private fun legsOldest() {
        trained(LEGS, daysAgo = 9)
        trained(CHEST, daysAgo = 2)
        trained(BACK, daysAgo = 1)
    }

    @Test
    fun theCheckInRowHasTheNineChipsWithTapTargets() {
        showApp()
        waitForText(CHEST)

        listOf("Back", "Biceps", "Chest", "Triceps", "Shoulders", "Legs", "Calves", "Lower back", "Neck").forEach {
            rule.onNodeWithText(it).assertIsDisplayed().assertHeightIsAtLeast(MinTouch).assertWidthIsAtLeast(MinTouch)
        }
    }

    @Test
    fun tappingLegsPersistsTodaysCheckInAndMovesSuggestedTodayOffLegsAndCalves() {
        legsOldest()
        showApp()
        waitForSuggested(LEGS)

        tap("Legs")

        waitForSuggested(CHEST)
        assertSuggested(CHEST)
        assertThat(storedToday()).containsExactly(Chip.LEGS)
        rule.onNodeWithText("Legs").assertIsSelected()
        rule.onNodeWithText("Calves").assertIsNotSelected()
    }

    @Test
    fun tappingAChipTwiceClearsIt() {
        legsOldest()
        showApp()
        waitForSuggested(LEGS)

        tap("Legs")
        waitForSuggested(CHEST)
        tap("Legs")

        waitForSuggested(LEGS)
        assertThat(storedToday()).isEmpty()
        rule.onNodeWithText("Legs").assertIsNotSelected()
    }

    @Test
    fun aCheckInAlreadyTappedTodayIsShownSelectedOnLaunch() {
        legsOldest()
        runBlocking { db.sessionDao().upsertCheckIn(CheckInEntity(today, setOf(Chip.LEGS, Chip.NECK))) }
        showApp()

        waitForSuggested(CHEST)
        rule.onNodeWithText("Legs").assertIsSelected()
        rule.onNodeWithText("Neck").assertIsSelected()
        rule.onNodeWithText("Back").assertIsNotSelected()
    }

    @Test
    fun theLegsCardShowsStiffFlushOptionOnlyWhileLegsAreStiff() {
        legsOldest()
        showApp()
        waitForSuggested(LEGS)
        rule.onAllNodesWithText("Stiff · flush option").assertCountEquals(0)

        tap("Legs")

        waitForText("Stiff · flush option")
        rule.onAllNodesWithText("Stiff · flush option").assertCountEquals(1)
        rule.onNode(hasText("Stiff · flush option") and hasParent(hasAnyChild(hasText(LEGS)))).assertIsDisplayed()
    }

    @Test
    fun calvesBlockTheSameDayAsLegs() {
        legsOldest()
        showApp()
        waitForSuggested(LEGS)

        tap("Calves")

        waitForSuggested(CHEST)
        rule.onNode(hasText("Stiff · flush option") and hasParent(hasAnyChild(hasText(LEGS)))).assertIsDisplayed()
    }

    @Test
    fun lowerBackAndNeckBlockNothing() {
        legsOldest()
        showApp()
        waitForSuggested(LEGS)

        tap("Lower back")
        tap("Neck")

        waitUntil { storedToday() == setOf(Chip.LOWER_BACK, Chip.NECK) }
        waitForSuggested(LEGS)
        rule.onAllNodesWithText("Stiff · flush option").assertCountEquals(0)
    }

    @Test
    fun whenEveryDayIsBlockedTheOldestIsSuggestedWithAFlushPrompt() {
        legsOldest()
        showApp()
        waitForSuggested(LEGS)

        tap("Chest")
        tap("Back")
        tap("Legs")

        waitForText(FLUSH_PROMPT)
        assertSuggested(LEGS)
        rule.onAllNodesWithText("Stiff · flush option").assertCountEquals(3)
        rule.onAllNodesWithText(FLUSH_PROMPT).assertCountEquals(1)
    }

    @Test
    fun noFlushPromptWhileSomeDayIsFree() {
        legsOldest()
        showApp()
        waitForSuggested(LEGS)

        tap("Legs")

        waitForSuggested(CHEST)
        rule.onAllNodesWithText(FLUSH_PROMPT).assertCountEquals(0)
    }

    @Test
    fun withTypicalStiffDaysOfThreeTheLegsCardSaysUsuallyLooseAfterThreeDays() {
        trained(LEGS, daysAgo = 30)
        trained(LEGS, daysAgo = 20)
        checkIn(27, Chip.LEGS)
        checkIn(26)
        checkIn(17, Chip.LEGS)
        checkIn(16)
        showApp()

        waitForText("Legs: usually loose after 3 days")
        rule.onAllNodesWithText("Legs: usually loose after 3 days").assertCountEquals(1)
        rule.onNode(hasText("Legs: usually loose after 3 days") and hasParent(hasAnyChild(hasText(LEGS)))).assertIsDisplayed()
        assertThat(textCount("usually loose", substring = true)).isEqualTo(1) // no other card has an estimate
    }

    @Test
    fun noRecoveryLineWithoutAnEstimate() {
        trained(LEGS, daysAgo = 30)
        checkIn(27, Chip.LEGS)
        checkIn(26)
        showApp()

        waitForText(LEGS)
        waitForText("Last done 30 days ago")
        assertThat(textCount("usually loose", substring = true)).isEqualTo(0)
    }

    @Test
    fun aFinishedSessionWithNoLogsIsNotLastDone() {
        legsOldest()
        trained(LEGS, daysAgo = 0, logged = 0) // started and abandoned today: it trained nothing
        showApp()

        waitForSuggested(LEGS)
        assertThat(textCount("Last done today")).isEqualTo(0)
        waitForText("Last done 9 days ago")
    }

    @Test
    fun startStaysOneTapWhateverIsStiff() {
        legsOldest()
        showApp()
        waitForSuggested(LEGS)
        tap("Legs")
        waitForSuggested(CHEST)

        tap("Start $CHEST")

        waitUntil { navController.currentBackStackEntry?.destination?.hasRoute(Workout::class) == true }
        assertThat(runBlocking { db.sessionDao().getOpenSessions() }.map { it.dayId }).containsExactly(dayId(CHEST))
    }

    private companion object {
        const val FLUSH_PROMPT = "Every day has a stiff muscle: try Flush instead on the stiff exercises."
    }
}
