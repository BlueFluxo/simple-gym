package com.simplegym.ui.workout

import android.os.Looper
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
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.CheckInEntity
import com.simplegym.data.ClubEntity
import com.simplegym.data.ClubUnavailableEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SlotStatus
import com.simplegym.engine.Chip
import com.simplegym.engine.Feel
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

private const val LEGS_FINISHER = "+ Flush finisher: Legs"

/** The optional flush finisher row on the Workout screen, and the rows following the variant that actually runs. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1200dp") // tall enough that every row and the buttons are composed
class WorkoutFinisherTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController
    private val today = LocalDate.of(2026, 10, 22)

    private var sessionId = 0L
    private lateinit var slotIds: List<Long>

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        container = testContainer(db)
        container.seedOnboarded()
        val chest = db.planDao().getActiveDays().first()
        slotIds = db.planDao().getSlots(chest.id).map { it.id }
        sessionId = container.sessionService.start(chest.id, today, clubId = null)
    }

    private fun stiff(vararg chips: Chip) = runBlocking { db.sessionDao().upsertCheckIn(CheckInEntity(today, chips.toSet())) }

    private fun showWorkout(waitFor: String = "Bench press") {
        val fresh = testContainer(db)
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(fresh, controller)
            }
        }
        rule.runOnUiThread { navController.navigate(Workout(sessionId)) }
        waitForText(waitFor)
    }

    private fun waitForText(text: String) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForNoText(text: String) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()
    }

    private fun waitForDestination(route: KClass<*>) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        navController.currentBackStackEntry?.destination?.hasRoute(route) == true
    }

    private fun inSlot(slotId: Long) = hasAnyAncestor(
        SemanticsMatcher("is slot-$slotId") { it.config.getOrNull(SemanticsProperties.TestTag) == "slot-$slotId" },
    )

    private fun logEarlier(exerciseId: String, weightKg: Double) = runBlocking {
        db.logDao().insert(
            LogEntity(
                sessionId = null, slotId = null, exerciseId = exerciseId, date = today.minusDays(3), weightKg = weightKg,
                bestReps = 12, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
            ),
        )
    }

    private fun finisherSlotId() = runBlocking { db.planDao().getSessionOnlySlots(sessionId) }.single().id

    private fun finisherExerciseName(): String = runBlocking {
        val slot = db.planDao().getSessionOnlySlots(sessionId).single()
        checkNotNull(db.libraryDao().get(slot.defaultExerciseId)).name
    }

    // ---- the finisher row ----

    @Test
    fun aStiffMuscleThisDayDoesNotTrainOffersAFlushFinisher() {
        stiff(Chip.LEGS)
        showWorkout()

        waitForText(LEGS_FINISHER)
        rule.onNodeWithText(LEGS_FINISHER).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(MinTouch)
        rule.onAllNodesWithText("+ Flush finisher:", substring = true).assertCountEquals(1)
    }

    @Test
    fun noFinisherOfferWithoutAStiffChipOrForTheDaysOwnChipsOrLowerBackAndNeck() {
        stiff(Chip.CHEST, Chip.TRICEPS, Chip.SHOULDERS, Chip.LOWER_BACK, Chip.NECK) // all on this day, or with no muscle
        showWorkout()

        rule.onNodeWithText("Finish workout").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("+ Flush finisher:", substring = true).assertCountEquals(0)
    }

    @Test
    fun oneOfferPerStiffChipThisDayDoesNotTrain() {
        stiff(Chip.LEGS, Chip.CALVES, Chip.CHEST)
        showWorkout()

        waitForText(LEGS_FINISHER)
        rule.onNodeWithText("+ Flush finisher: Calves").assertIsDisplayed()
        rule.onAllNodesWithText("+ Flush finisher:", substring = true).assertCountEquals(2)
    }

    @Test
    fun tappingTheOfferAddsAFlushRowThatCountsNowhere() {
        stiff(Chip.LEGS)
        showWorkout()
        waitForText(LEGS_FINISHER)

        rule.onNodeWithText(LEGS_FINISHER).performClick()

        waitForNoText(LEGS_FINISHER)
        val slotId = finisherSlotId()
        waitForText("2 × 15–20 @ new")
        rule.onNode(hasTestTag("slot-$slotId") and hasText(finisherExerciseName())).assertIsDisplayed()
        rule.onNodeWithText("0 of 5 done").assertIsDisplayed() // the optional extra is not part of the count
        val session = checkNotNull(runBlocking { db.sessionDao().getSession(sessionId) })
        assertThat(session.slotsTotal).isEqualTo(5)
        assertThat(runBlocking { db.planDao().getSlots(session.dayId) }.map { it.id }).doesNotContain(slotId)
        val state = runBlocking { db.sessionDao().getSessionSlots(sessionId) }.single { it.slotId == slotId }
        assertThat(state.flush).isTrue()
        assertThat(state.status).isEqualTo(SlotStatus.PENDING)
    }

    @Test
    fun theFinisherRowComesAfterThePlanRows() {
        stiff(Chip.LEGS)
        showWorkout()
        waitForText(LEGS_FINISHER)
        rule.onNodeWithText(LEGS_FINISHER).performClick()
        waitForText("2 × 15–20 @ new")

        val finisherTop = rule.onNodeWithTag("slot-${finisherSlotId()}").getBoundsInRoot().top
        assertThat(finisherTop).isGreaterThan(rule.onNodeWithTag("slot-${slotIds.last()}").getBoundsInRoot().top)
        assertThat(finisherTop).isLessThan(rule.onNodeWithText("Finish workout").getBoundsInRoot().top)
    }

    @Test
    fun anExistingFinisherIsListedOnOpeningTheWorkout() {
        stiff(Chip.LEGS)
        runBlocking { container.sessionService.addFlushFinisher(sessionId, Chip.LEGS) }
        showWorkout()

        waitForText("2 × 15–20 @ new")
        rule.onAllNodesWithText("+ Flush finisher:", substring = true).assertCountEquals(0)
        rule.onNodeWithText("0 of 5 done").assertIsDisplayed()
    }

    @Test
    fun theFinisherOpensItsExerciseScreenOnTheFlushTarget() {
        stiff(Chip.LEGS)
        logEarlier("leg-extensions", 40.0) // a cold start ignores flush, so the exercise needs a baseline
        val slotId = runBlocking { container.sessionService.addFlushFinisher(sessionId, Chip.LEGS) }
        showWorkout()
        waitForText("2 × 15–20 @ 17.5") // 40 x 0.45 = 18, down to the lighter 2.5 step

        rule.onNode(hasTestTag("slot-$slotId") and hasText(finisherExerciseName())).performScrollTo().performClick()
        waitForDestination(ExerciseRoute::class)

        assertThat(navController.currentBackStackEntry!!.toRoute<ExerciseRoute>().slotId).isEqualTo(slotId)
        waitForText("Flush: light weight, high reps to get blood into a stiff muscle.")
        waitForText("15–20 reps")
        waitForText("Round 1:00")
    }

    @Test
    fun onceThePlanIsDoneTheFinisherIsNextAndStartsItsTimer() {
        stiff(Chip.LEGS)
        val slotId = checkNotNull(runBlocking { container.sessionService.addFlushFinisher(sessionId, Chip.LEGS) })
        runBlocking {
            slotIds.forEach { container.sessionService.setStatus(sessionId, it, SlotStatus.DONE) }
            slotIds.forEach {
                db.logDao().insert(
                    LogEntity(
                        sessionId = sessionId, slotId = it, exerciseId = "bench-press", date = today, weightKg = 50.0,
                        bestReps = 10, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
                    ),
                )
            }
        }
        showWorkout()
        waitForText("5 of 5 done")

        rule.onNode(hasText("▶ Start timer") and inSlot(slotId)).performScrollTo().performClick()
        waitForDestination(TimerRoute::class)

        val route = navController.currentBackStackEntry!!.toRoute<TimerRoute>()
        assertThat(route.slotId).isEqualTo(slotId)
        assertThat(route.sessionId).isEqualTo(sessionId)
        assertThat(route.exerciseId).isEqualTo(runBlocking { db.planDao().getSlot(slotId) }!!.defaultExerciseId)
    }

    @Test
    fun aLoggedFinisherShowsDoneWithoutChangingTheCount() {
        stiff(Chip.LEGS)
        val slotId = checkNotNull(runBlocking { container.sessionService.addFlushFinisher(sessionId, Chip.LEGS) })
        runBlocking {
            container.sessionService.setStatus(sessionId, slotId, SlotStatus.DONE)
            db.logDao().insert(
                LogEntity(
                    sessionId = sessionId, slotId = slotId, exerciseId = "leg-extensions", date = today, weightKg = 27.5,
                    bestReps = 18, sets = 2, feel = Feel.EASY, type = SessionType.FLUSH,
                ),
            )
        }
        showWorkout()

        waitForText("27.5")
        rule.onNodeWithText("0 of 5 done").assertIsDisplayed()
        rule.onNodeWithText("Leg extensions").assertIsDisplayed()
    }

    @Test
    fun finishingTheWorkoutRemovesTheFinisherButKeepsItsLog() {
        stiff(Chip.LEGS)
        val slotId = checkNotNull(runBlocking { container.sessionService.addFlushFinisher(sessionId, Chip.LEGS) })
        runBlocking {
            container.sessionService.setStatus(sessionId, slotId, SlotStatus.DONE)
            db.logDao().insert(
                LogEntity(
                    sessionId = sessionId, slotId = slotId, exerciseId = "leg-extensions", date = today, weightKg = 27.5,
                    bestReps = 18, sets = 2, feel = Feel.EASY, type = SessionType.FLUSH,
                ),
            )
        }
        showWorkout()
        waitForText("27.5")

        rule.onNodeWithText("Finish workout").performScrollTo().performClick()
        waitForDestination(Home::class)

        assertThat(runBlocking { db.planDao().getSlot(slotId) }).isNull()
        assertThat(runBlocking { db.sessionDao().getSessionSlots(sessionId) }.map { it.slotId }).doesNotContain(slotId)
        val logs = runBlocking { db.logDao().getAll() }.single()
        assertThat(logs.slotId).isNull()
        assertThat(logs.exerciseId).isEqualTo("leg-extensions")
    }

    // ---- the next row follows the variant that runs ----

    @Test
    fun theNextRowNamesAndTargetsTheVariantTheSlotIsRotatedTo() {
        runBlocking { db.planDao().setRotation(slotIds[0], "dumbbell-bench-press", 0) }
        showWorkout(waitFor = "Dumbbell bench press")

        rule.onAllNodesWithText("Bench press").assertCountEquals(0)
        rule.onNode(hasTestTag("slot-${slotIds[0]}") and hasText("Dumbbell bench press")).assertIsDisplayed()
        rule.onNode(hasText("▶ Start timer") and inSlot(slotIds[0])).assertIsDisplayed()
    }

    @Test
    fun theNextRowTargetIsTheResolvedVariantsNotTheDefaults() {
        runBlocking {
            // 60 kg on the dumbbell variant only: the default (bench press) has no history and would read "@ new"
            db.logDao().insert(
                LogEntity(
                    sessionId = null, slotId = null, exerciseId = "dumbbell-bench-press", date = today.minusDays(3),
                    weightKg = 30.0, bestReps = 12, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
                ),
            )
            db.planDao().setRotation(slotIds[0], "dumbbell-bench-press", 0)
        }
        showWorkout(waitFor = "Dumbbell bench press")

        waitForText("3 × 8–12 @ 32.5")
    }

    @Test
    fun theNextRowFollowsTheFirstOrderedSwapWhenTheDefaultIsUnavailableAtTheClub() {
        runBlocking {
            val club = db.clubDao().insert(ClubEntity(name = "Test club"))
            db.clubDao().markUnavailable(ClubUnavailableEntity(club, "bench-press"))
            sessionId = container.sessionService.start(db.planDao().getActiveDays().first().id, today, club)
        }
        showWorkout(waitFor = "Chest press machine")

        rule.onAllNodesWithText("Bench press").assertCountEquals(0)
        rule.onNode(hasTestTag("slot-${slotIds[0]}") and hasText("Chest press machine")).assertIsDisplayed()
    }
}
