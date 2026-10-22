package com.simplegym.ui.exercise

import android.os.Looper
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.CheckInEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SlotEntity
import com.simplegym.data.SlotSwapEntity
import com.simplegym.engine.Chip
import com.simplegym.engine.Feel
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.ui.nav.ExerciseRoute
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

/** The Exercise screen reacting to today's check-in beyond the held target: the flush switch and the swap context. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1200dp")
class ExerciseStiffTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController
    private val today = LocalDate.of(2026, 10, 22)

    private var dayId = 0L
    private var sessionId = 0L
    private lateinit var slotIds: List<Long>

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        container = testContainer(db)
        container.seedOnboarded()
        dayId = db.planDao().getActiveDays().first().id
        slotIds = db.planDao().getSlots(dayId).map { it.id }
        sessionId = container.sessionService.start(dayId, today, clubId = null)
    }

    private fun stiff(vararg chips: Chip) = runBlocking { db.sessionDao().upsertCheckIn(CheckInEntity(today, chips.toSet())) }

    private fun logBench() = runBlocking {
        db.logDao().insert(
            LogEntity(
                sessionId = null, slotId = null, exerciseId = "bench-press", date = today.minusDays(3), weightKg = 60.0,
                bestReps = 12, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
            ),
        )
    }

    private fun showExercise(slotId: Long) {
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
        rule.runOnUiThread {
            navController.navigate(Workout(sessionId))
            navController.navigate(ExerciseRoute(sessionId, slotId))
        }
        waitForText("Stop 1–2 reps short of failure.")
    }

    private fun waitForText(text: String) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tagExists(tag: String): Boolean {
        shadowOf(Looper.getMainLooper()).idle()
        return rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = rule.waitUntil(timeoutMillis = 5_000) { tagExists(tag) }

    private fun waitForNoTag(tag: String) = rule.waitUntil(timeoutMillis = 5_000) { !tagExists(tag) }

    private fun slotFlush(slotId: Long) = runBlocking {
        db.sessionDao().getSessionSlots(sessionId).first { it.slotId == slotId }.flush
    }

    /** A slot added to the chest day whose default is a high lower-back load row with two lighter swaps. */
    private fun rowSlot(): Long = runBlocking {
        val id = db.planDao().insertSlot(
            SlotEntity(dayId = dayId, position = 50, defaultExerciseId = "barbell-bent-over-row", sets = 3, lo = 8, hi = 12),
        )
        db.planDao().insertSwaps(listOf(SlotSwapEntity(id, "row-machine", 0), SlotSwapEntity(id, "dumbbell-row", 1)))
        sessionId = container.sessionService.start(dayId, today, clubId = null)
        id
    }

    // ---- ruling: the Flush instead switch can always be undone ----

    @Test
    fun aFlushThatIsOnKeepsItsSwitchEvenWhenNothingIsStiffAnyMoreAndItCanBeTurnedOff() {
        runBlocking { container.sessionService.setFlush(sessionId, slotIds[0], true) } // chest was stiff earlier, since cleared
        showExercise(slotIds[0])

        waitForTag("flush-switch")
        rule.onNodeWithTag("flush-switch").assertIsDisplayed().assertHeightIsAtLeast(MinTouch)
        rule.onNodeWithText("Flush instead").assertIsDisplayed()

        rule.onNodeWithTag("flush-switch").performClick()

        waitForNoTag("flush-switch") // off and not stiff: nothing left to offer
        assertThat(slotFlush(slotIds[0])).isFalse()
    }

    @Test
    fun noSwitchWhenNothingIsStiffAndFlushIsOff() {
        showExercise(slotIds[0])

        waitForTag("target-card")
        assertThat(tagExists("flush-switch")).isFalse()
    }

    @Test
    fun theSwitchStaysForAStiffMuscleWhetherFlushIsOnOrOff() {
        logBench() // a cold start ignores both the hold and the flush
        stiff(Chip.CHEST)
        showExercise(slotIds[0])
        waitForTag("flush-switch")

        rule.onNodeWithTag("flush-switch").performClick()
        waitForText("Flush: light weight, high reps to get blood into a stiff muscle.")
        assertThat(tagExists("flush-switch")).isTrue()

        rule.onNodeWithTag("flush-switch").performClick()
        waitForText("Stiff today: holding at 60kg with a longer warm-up.")
        assertThat(tagExists("flush-switch")).isTrue()
    }

    // ---- stiff lower back / neck reach the swap context ----

    @Test
    fun aStiffLowerBackOpensAHighLoadSlotOnItsLightestSwapWithTheDefaultOneTapAway() {
        stiff(Chip.LOWER_BACK)
        val slotId = rowSlot()
        showExercise(slotId)

        waitForTag("chip-barbell-bent-over-row")
        assertThat(tagExists("chip-row-machine")).isFalse() // it is the variant on screen, not a chip
        rule.onNodeWithText("Row machine").assertIsDisplayed()
    }

    @Test
    fun withoutAStiffLowerBackTheSlotOpensOnItsDefault() {
        val slotId = rowSlot()
        showExercise(slotId)

        waitForTag("chip-row-machine")
        assertThat(tagExists("chip-barbell-bent-over-row")).isFalse()
    }
}
