package com.simplegym.ui.exercise

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.completeBuildRounds
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.CheckInEntity
import com.simplegym.data.ClubEntity
import com.simplegym.data.ClubUnavailableEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SlotStatus
import com.simplegym.data.SlotSwapEntity
import com.simplegym.engine.Chip
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.RangeType
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.ui.nav.ExerciseRoute
import com.simplegym.ui.nav.GymNavHost
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
@Config(qualifiers = "w411dp-h1200dp") // tall enough that the whole screen and the search results are composed
class ExerciseScreenTest {
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
        log("bench-press", 60.0, 12) // so bench press targets 62.5
    }

    private fun log(exerciseId: String, weightKg: Double?, reps: Int, rangeType: RangeType = RangeType.BASE) = runBlocking {
        db.logDao().insert(
            LogEntity(
                sessionId = null, slotId = null, exerciseId = exerciseId, date = today.minusDays(3),
                weightKg = weightKg, bestReps = reps, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
                rangeType = rangeType,
            ),
        )
    }

    /** Opens the Exercise screen of the first slot (Bench press) with Workout beneath it on the back stack. */
    private fun showExercise(slotIndex: Int = 0) {
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
        rule.runOnUiThread {
            navController.navigate(Workout(sessionId))
            navController.navigate(ExerciseRoute(sessionId, slotIds[slotIndex]))
        }
        waitForText("Stop 1–2 reps short of failure.")
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
            navController.currentBackStackEntry?.destination?.hasRoute(route) == true
        }
    }

    private fun tagExists(tag: String): Boolean {
        shadowOf(Looper.getMainLooper()).idle()
        return rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = rule.waitUntil(timeoutMillis = 5_000) { tagExists(tag) }

    private fun chipTexts(exerciseId: String): List<String> =
        rule.onNodeWithTag("chip-$exerciseId").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private val isSwapChip = SemanticsMatcher("is a swap chip") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("chip-") == true
    }

    private fun chipIds(): List<String> {
        shadowOf(Looper.getMainLooper()).idle()
        return rule.onAllNodes(isSwapChip).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("chip-") }
    }

    private fun typeInSearch(text: String) = rule.onNode(hasSetTextAction()).performTextInput(text)

    @Test
    fun targetCardShowsWeightSetsRepRangeAndTheReason() {
        showExercise()

        val reason = runBlocking { container.targetService.targetFor(slotIds[0], "bench-press", today).reason }
        rule.onNodeWithText("Bench press").assertIsDisplayed() // header: the variant name
        rule.onNodeWithText("62.5").assertIsDisplayed()
        rule.onNodeWithText("3 sets").assertIsDisplayed()
        rule.onNodeWithText("8–12 reps").assertIsDisplayed()
        rule.onNodeWithText("Round 2:00").assertIsDisplayed()
        rule.onNodeWithText(reason).assertIsDisplayed()
        rule.onNodeWithText("Stop 1–2 reps short of failure.").assertIsDisplayed()
    }

    @Test
    fun fourSwapChipsShowTheirRememberedWeightBodyweightOrNew() {
        log("dumbbell-bench-press", 22.5, 10)
        log("push-ups", null, 20) // a bodyweight baseline: "BW", not "new"
        showExercise()

        assertThat(chipIds()).containsExactly(
            "chest-press-machine", "dumbbell-bench-press", "machine-press-plate-loaded", "push-ups",
        ).inOrder()
        assertThat(chipTexts("chest-press-machine")).containsExactly("Chest press machine", "new").inOrder()
        assertThat(chipTexts("dumbbell-bench-press")).containsExactly("Dumbbell bench press", "22.5").inOrder()
        assertThat(chipTexts("machine-press-plate-loaded")).containsExactly("Machine press (plate-loaded)", "new").inOrder()
        assertThat(chipTexts("push-ups")).containsExactly("Push-ups", "BW").inOrder()
        chipIds().forEach { rule.onNodeWithTag("chip-$it").assertHeightIsAtLeast(MinTouch).assertWidthIsAtLeast(MinTouch) }
    }

    @Test
    fun oneTapOnAChipSwitchesTheTargetCardToThatExercise() {
        showExercise()
        rule.onNodeWithText("62.5").assertIsDisplayed()

        rule.onNodeWithText("Chest press machine").performClick()
        waitForText("New: start light, with a weight you could lift about 15 times.")

        rule.onNodeWithText("Chest press machine").assertIsDisplayed() // now the header
        assertThat(rule.onAllNodes(hasText("62.5")).fetchSemanticsNodes()).isEmpty()
        // the default is one tap away again, and the variant just left the chips
        assertThat(chipIds()).contains("bench-press")
        assertThat(chipIds()).doesNotContain("chest-press-machine")
    }

    @Test
    fun startTimerRunsTheVariantOnScreen() {
        showExercise()
        rule.onNodeWithText("Chest press machine").performClick()
        waitForText("New: start light, with a weight you could lift about 15 times.")

        rule.onNodeWithText("▶ Start timer").assertHeightIsAtLeast(MinTouch).performClick()
        waitForDestination(TimerRoute::class)

        val route = navController.currentBackStackEntry!!.toRoute<TimerRoute>()
        assertThat(route).isEqualTo(TimerRoute(sessionId, slotIds[0], "chest-press-machine"))
    }

    @Test
    fun startTimerOnTheUntouchedScreenRunsTheDefault() {
        showExercise()

        rule.onNodeWithText("▶ Start timer").performClick()
        waitForDestination(TimerRoute::class)

        assertThat(navController.currentBackStackEntry!!.toRoute<TimerRoute>())
            .isEqualTo(TimerRoute(sessionId, slotIds[0], "bench-press"))
    }

    @Test
    fun skipForNowMarksTheSlotSkippedAndReturnsToWorkout() {
        showExercise()

        rule.onNodeWithText("Skip for now").assertHeightIsAtLeast(MinTouch).performClick()
        waitForDestination(Workout::class)

        val states = runBlocking { db.sessionDao().getSessionSlots(sessionId) }.associateBy { it.slotId }
        assertThat(states.getValue(slotIds[0]).status).isEqualTo(SlotStatus.SKIPPED)
        assertThat(slotIds.drop(1).map { states.getValue(it).status }.toSet()).containsExactly(SlotStatus.PENDING)
    }

    @Test
    fun skipForNowLeavesADoneSlotDone() {
        runBlocking { container.sessionService.setStatus(sessionId, slotIds[0], SlotStatus.DONE) }
        showExercise()

        rule.onNodeWithText("Skip for now").performClick()
        waitForDestination(Workout::class)

        val states = runBlocking { db.sessionDao().getSessionSlots(sessionId) }.associateBy { it.slotId }
        assertThat(states.getValue(slotIds[0]).status).isEqualTo(SlotStatus.DONE)
    }

    @Test
    fun anUnavailableDefaultOpensOnTheFirstOrderedSwap() {
        runBlocking {
            val club = db.clubDao().insert(ClubEntity(name = "Test club"))
            db.clubDao().markUnavailable(ClubUnavailableEntity(club, "bench-press"))
            sessionId = container.sessionService.start(db.planDao().getActiveDays().first().id, today, club)
        }
        showExercise()

        waitForText("New: start light, with a weight you could lift about 15 times.")
        rule.onNodeWithText("Chest press machine").assertIsDisplayed()
        assertThat(chipIds()).doesNotContain("bench-press") // unavailable here, so not offered as a chip either
        assertThat(chipIds()).containsExactly(
            "dumbbell-bench-press", "machine-press-plate-loaded", "push-ups",
        ).inOrder()
    }

    @Test
    fun moreOpensASearchPreFilteredToTheSlotsSwapGroupAndAllWidensIt() {
        showExercise()
        assertThat(tagExists("result-preacher-curl")).isFalse()

        rule.onNodeWithText("More…").performClick()
        waitForTag("result-dumbbell-bench-press") // same swap group as Bench press
        assertThat(tagExists("result-preacher-curl")).isFalse()

        rule.onNodeWithText("All").performClick()
        typeInSearch("preacher")
        waitForTag("result-preacher-curl")
        rule.onNodeWithText("Preacher curl").assertIsDisplayed()
        assertThat(tagExists("result-dumbbell-bench-press")).isFalse() // typing narrows the list
    }

    @Test
    fun typingNarrowsTheGroupResultsCaseInsensitively() {
        showExercise()
        rule.onNodeWithText("More…").performClick()
        waitForTag("result-dumbbell-bench-press")

        typeInSearch("DUMBBELL")

        waitForTag("result-dumbbell-bench-press")
        assertThat(tagExists("result-push-ups")).isFalse()
    }

    @Test
    fun aSearchResultBecomesTheVariantInOneTapAndClosesTheSearch() {
        showExercise()
        rule.onNodeWithText("More…").performClick()
        rule.onNodeWithText("All").performClick()
        typeInSearch("preacher")
        waitForTag("result-preacher-curl")

        rule.onNodeWithTag("result-preacher-curl").performClick()
        waitForText("New: start light, with a weight you could lift about 15 times.")

        assertThat(tagExists("result-preacher-curl")).isFalse()
        rule.onNodeWithText("Preacher curl").assertIsDisplayed() // the header
    }

    @Test
    fun lowerBackProtectionKeepsHighBackLoadOutOfTheChipsButStillFindableWithAWarning() {
        runBlocking {
            db.settingsDao().upsert(checkNotNull(db.settingsDao().get()).copy(lowerBackProtection = true))
            db.planDao().insertSwaps(listOf(SlotSwapEntity(slotIds[0], "barbell-bent-over-row", 9)))
        }
        showExercise()

        assertThat(chipIds()).doesNotContain("barbell-bent-over-row")
        assertThat(chipIds()).hasSize(4)

        rule.onNodeWithText("More…").performClick()
        rule.onNodeWithText("All").performClick()
        typeInSearch("bent-over")
        waitForTag("result-barbell-bent-over-row")
        rule.onNodeWithTag("result-barbell-bent-over-row").assertTextContains("Barbell bent-over row")
        rule.onNodeWithTag("result-barbell-bent-over-row").assertTextContains("⚠ High back load")
    }

    @Test
    fun withoutProtectionTheHighBackLoadSwapIsAChipAndHasNoWarning() {
        runBlocking { db.planDao().insertSwaps(listOf(SlotSwapEntity(slotIds[0], "barbell-bent-over-row", 9))) }
        showExercise()

        assertThat(chipIds()).contains("barbell-bent-over-row")
        rule.onNodeWithText("More…").performClick()
        rule.onNodeWithText("All").performClick()
        typeInSearch("bent-over")
        waitForTag("result-barbell-bent-over-row")
        assertThat(rule.onAllNodes(hasText("⚠ High back load")).fetchSemanticsNodes()).isEmpty()
    }

    private val bannerText = "Rotate to another variation for this block?"

    private fun slot() = runBlocking { checkNotNull(db.planDao().getSlot(slotIds[0])) }

    @Test
    fun aRotationSetForTheCurrentBlockShowsTheRotatedVariantAndStartTimerRunsIt() {
        runBlocking { db.planDao().setRotation(slotIds[0], "dumbbell-bench-press", 0) }
        showExercise()
        waitForText("New: start light, with a weight you could lift about 15 times.")

        rule.onNodeWithText("Dumbbell bench press").assertIsDisplayed() // the header
        assertThat(chipIds()).containsExactly(
            "bench-press", "chest-press-machine", "machine-press-plate-loaded", "push-ups",
        ).inOrder()

        rule.onNodeWithText("▶ Start timer").performClick()
        waitForDestination(TimerRoute::class)
        assertThat(navController.currentBackStackEntry!!.toRoute<TimerRoute>())
            .isEqualTo(TimerRoute(sessionId, slotIds[0], "dumbbell-bench-press"))
    }

    @Test
    fun noBannerBeforeThreeStalledSessions() {
        repeat(2) { log("bench-press", 60.0, 12) } // 60x12 three times: two plateaus
        showExercise()
        waitForTag("target-card") // the state, plateau count included, has loaded: absence below is not vacuous

        assertThat(tagExists("plateau-banner")).isFalse()
        assertThat(rule.onAllNodes(hasText(bannerText)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun threeStalledSessionsShowTheBannerAndOneTapOnAChipRotatesTheSlot() {
        repeat(3) { log("bench-press", 60.0, 12) } // 60x12 four times: three plateaus
        showExercise()

        waitForTag("plateau-banner")
        rule.onNodeWithText(bannerText).assertIsDisplayed()
        rule.onNodeWithTag("rotate-dumbbell-bench-press").assertHeightIsAtLeast(MinTouch)

        rule.onNodeWithTag("rotate-dumbbell-bench-press").performClick()
        waitForText("New: start light, with a weight you could lift about 15 times.")

        val rotated = slot()
        assertThat(rotated.rotationExerciseId).isEqualTo("dumbbell-bench-press")
        assertThat(rotated.rotationBlockIndex).isEqualTo(0)
        assertThat(rotated.defaultExerciseId).isEqualTo("bench-press") // the plan default is untouched
        rule.onNodeWithText("Dumbbell bench press").assertIsDisplayed() // now the header
        assertThat(tagExists("plateau-banner")).isFalse() // the rotated variant starts its own count
        assertThat(chipIds()).contains("bench-press")
    }

    @Test
    fun theStalledVariantsBannerReturnsWhenYouSwitchBackToIt() {
        repeat(3) { log("bench-press", 60.0, 12) }
        showExercise()
        waitForTag("plateau-banner")
        rule.onNodeWithTag("rotate-dumbbell-bench-press").performClick()
        waitForText("New: start light, with a weight you could lift about 15 times.")

        rule.onNodeWithTag("chip-bench-press").performClick()
        waitForTag("plateau-banner")
    }

    @Test
    fun noKeepChipWhileTheRotationsBlockIsStillTheCurrentOne() {
        runBlocking { db.planDao().setRotation(slotIds[0], "dumbbell-bench-press", 0) }
        showExercise()
        waitForTag("target-card") // loaded: absence below is not vacuous

        assertThat(tagExists("keep-offer")).isFalse()
    }

    /** Block 0 has been played out in January and its deload never was, so on [today] block 1 is under way. */
    private fun startNewBlockWithRotation() {
        runBlocking {
            container.completeBuildRounds(today.minusDays(60))
            db.planDao().setRotation(slotIds[0], "dumbbell-bench-press", 0)
            sessionId = container.sessionService.start(db.planDao().getActiveDays().first().id, today, clubId = null)
        }
    }

    @Test
    fun theFirstSessionOfANewBlockOffersToKeepTheRotatedVariantAndYesMakesItTheDefault() {
        startNewBlockWithRotation()
        showExercise()

        waitForTag("keep-offer")
        rule.onNodeWithText("Bench press").assertIsDisplayed() // the plan default is back
        rule.onNodeWithTag("keep-yes").assertTextContains("Keep Dumbbell bench press as default?")
        rule.onNodeWithTag("keep-yes").assertHeightIsAtLeast(MinTouch).performClick()
        waitForTag("chip-bench-press") // the screen follows the new default: the old one is now a chip

        rule.waitUntil(timeoutMillis = 5_000) { shadowOf(Looper.getMainLooper()).idle(); slot().rotationExerciseId == null }
        val kept = slot()
        assertThat(kept.defaultExerciseId).isEqualTo("dumbbell-bench-press")
        assertThat(kept.rotationBlockIndex).isNull()
        assertThat(tagExists("keep-offer")).isFalse()
        assertThat(chipIds()).doesNotContain("dumbbell-bench-press") // it is the variant on screen now
    }

    @Test
    fun noKeepsThePlanDefaultAndStillClearsTheRotation() {
        startNewBlockWithRotation()
        showExercise()
        waitForTag("keep-offer")

        rule.onNodeWithTag("keep-no").performClick()

        rule.waitUntil(timeoutMillis = 5_000) { shadowOf(Looper.getMainLooper()).idle(); slot().rotationExerciseId == null }
        assertThat(slot().defaultExerciseId).isEqualTo("bench-press")
        assertThat(slot().rotationBlockIndex).isNull()
        assertThat(tagExists("keep-offer")).isFalse()
        rule.onNodeWithText("Bench press").assertIsDisplayed()
    }

    @Test
    fun rotatingWhileTheKeepOfferIsShowingDropsTheOfferSoKeepCannotMakeTheNewVariantTheDefault() {
        // the plan default has stalled in the new block's range type (HIGH_REP), so the banner shows too
        repeat(4) { log("bench-press", 55.0, 14, RangeType.HIGH_REP) }
        startNewBlockWithRotation()
        showExercise()
        waitForTag("keep-offer")
        waitForTag("plateau-banner")

        rule.onNodeWithTag("rotate-chest-press-machine").performClick()
        waitForText("Chest press machine")
        rule.waitUntil(timeoutMillis = 5_000) { shadowOf(Looper.getMainLooper()).idle(); !tagExists("keep-offer") }

        val rotated = slot()
        assertThat(rotated.defaultExerciseId).isEqualTo("bench-press") // never changed by the rotation
        assertThat(rotated.rotationExerciseId).isEqualTo("chest-press-machine")
        assertThat(rotated.rotationBlockIndex).isEqualTo(1)
        assertThat(tagExists("keep-offer")).isFalse()
    }

    // Hurt flags: badges, the inline banner and the softer joint note

    private fun logHurt(exerciseId: String, joint: Joint, daysAgo: Long) = runBlocking {
        db.logDao().insert(
            LogEntity(
                sessionId = null, slotId = null, exerciseId = exerciseId, date = today.minusDays(daysAgo),
                weightKg = 60.0, bestReps = 8, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL, hurt = joint,
            ),
        )
    }

    private fun hurtBannerText() = rule.onNodeWithTag("hurt-banner-text").fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    @Test
    fun aHurtVariantShowsAnAmberBadgeInTheHeaderAndOnItsChipWhichSinksToTheEnd() {
        logHurt("bench-press", Joint.SHOULDER, 6)
        logHurt("bench-press", Joint.SHOULDER, 5)
        logHurt("chest-press-machine", Joint.ELBOW, 4)
        showExercise()

        waitForText("⚠ Shoulder · 2×") // the header
        assertThat(chipTexts("chest-press-machine")).containsExactly("Chest press machine", "new", "⚠ Elbow · 1×").inOrder()
        assertThat(chipTexts("dumbbell-bench-press")).containsExactly("Dumbbell bench press", "new").inOrder()
        assertThat(chipIds().last()).isEqualTo("chest-press-machine") // flagged variants rank lower
    }

    @Test
    fun noBadgeOrBannerWithoutAHurt() {
        showExercise()
        waitForTag("target-card")

        assertThat(rule.onAllNodes(hasText("⚠", substring = true)).fetchSemanticsNodes()).isEmpty()
        assertThat(tagExists("hurt-banner")).isFalse()
        assertThat(tagExists("joint-note")).isFalse()
    }

    @Test
    fun theBannerNamesTheJointAndOffersTheJointFriendlySwapAsOneTapChip() {
        logHurt("bench-press", Joint.WRIST, 6)
        logHurt("bench-press", Joint.WRIST, 5)
        showExercise()

        waitForTag("hurt-banner")
        assertThat(hurtBannerText()).contains("This hurt your wrist last 2 times. Try Chest press machine instead?")
        rule.onNodeWithTag("hurt-swap-chest-press-machine").assertHeightIsAtLeast(MinTouch)

        rule.onNodeWithTag("hurt-swap-chest-press-machine").performClick()
        waitForText("New: start light, with a weight you could lift about 15 times.")

        rule.onNodeWithText("Chest press machine").assertIsDisplayed() // the header: the swap is now the variant
        assertThat(tagExists("hurt-banner")).isFalse() // and it never hurt
        assertThat(slot().rotationExerciseId).isNull() // a swap, not a rotation
    }

    @Test
    fun startingTheTimerWithTheBannerUpMeansContinueAnyway() {
        logHurt("bench-press", Joint.WRIST, 6)
        showExercise()
        waitForTag("hurt-banner")

        rule.onNodeWithText("▶ Start timer").performClick()
        waitForDestination(TimerRoute::class)

        assertThat(navController.currentBackStackEntry!!.toRoute<TimerRoute>())
            .isEqualTo(TimerRoute(sessionId, slotIds[0], "bench-press"))
    }

    @Test
    fun withNoJointFriendlySwapTheBannerNamesTheJointAndOffersNoChip() {
        logHurt("bench-press", Joint.SHOULDER, 6) // every press variant loads the shoulder
        logHurt("bench-press", Joint.SHOULDER, 5)
        showExercise()

        waitForTag("hurt-banner")
        assertThat(hurtBannerText()).contains("This hurt your shoulder last 2 times.")
        assertThat(hurtBannerText().joinToString(" ")).doesNotContain("Try")
        assertThat(rule.onAllNodes(hasTestTag("hurt-swap-chest-press-machine")).fetchSemanticsNodes()).isEmpty()
        assertThat(rule.onAllNodes(SemanticsMatcher("is a hurt swap chip") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("hurt-swap-") == true
        }).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun theBannerFollowsTheVariantOnScreenAndComesBackWhenYouSwitchBack() {
        logHurt("bench-press", Joint.WRIST, 6)
        showExercise()
        waitForTag("hurt-banner")

        rule.onNodeWithTag("chip-dumbbell-bench-press").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { shadowOf(Looper.getMainLooper()).idle(); !tagExists("hurt-banner") }
        // the flagged default is still one tap away, badge and all, but no longer leads the chips
        assertThat(chipIds()).containsExactly(
            "chest-press-machine", "machine-press-plate-loaded", "push-ups", "bench-press",
        ).inOrder()
        assertThat(chipTexts("bench-press")).contains("⚠ Wrist · 1×")

        rule.onNodeWithTag("chip-bench-press").performClick()
        waitForTag("hurt-banner")
    }

    @Test
    fun anotherExercisesRecentHurtOnASharedJointShowsAGreyNoteUntilTwoPainFreeLogsLiftIt() {
        logHurt("chest-press-machine", Joint.ELBOW, 6) // bench press loads the elbow too; it has one pain-free log since
        showExercise()

        waitForTag("joint-note")
        rule.onNodeWithTag("joint-note").assertTextContains("Elbow flagged recently")
        assertThat(tagExists("hurt-banner")).isFalse() // bench press itself did not hurt
        assertThat(
            rule.onNodeWithTag("joint-note").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty()
                .map { it.text },
        ).containsExactly("Elbow flagged recently") // grey text, not an amber badge
    }

    @Test
    fun twoPainFreeLogsOfItsOwnLiftTheNote() {
        logHurt("chest-press-machine", Joint.ELBOW, 6)
        log("bench-press", 60.0, 12) // a second pain-free log after the hurt (the first is in setUp, 3 days ago)
        showExercise()
        waitForTag("target-card")

        assertThat(tagExists("joint-note")).isFalse()
    }

    @Test
    fun aSingleHurtSaysLastTimeNotLastOneTimes() {
        logHurt("bench-press", Joint.WRIST, 6)
        showExercise()

        waitForTag("hurt-banner")
        assertThat(hurtBannerText()).contains("This hurt your wrist last time. Try Chest press machine instead?")
    }

    @Test
    fun theHurtBadgesSitInTheBodyJustUnderTheBarNotInTheTitle() {
        logHurt("bench-press", Joint.SHOULDER, 6)
        showExercise()
        waitForText("⚠ Shoulder · 1×")

        val title = rule.onNodeWithText("Bench press").getBoundsInRoot()
        val badge = rule.onNodeWithText("⚠ Shoulder · 1×").getBoundsInRoot()
        assertThat(badge.top.value).isAtLeast(title.bottom.value) // below the title, so not part of the bar's title slot
        assertThat(badge.top.value).isLessThan(rule.onNodeWithTag("target-card").getBoundsInRoot().top.value)
    }

    // Stiff muscle: the held target and the inline "Flush instead" switch

    private fun markStiff(chip: Chip) = runBlocking { db.sessionDao().upsertCheckIn(CheckInEntity(today, setOf(chip))) }

    private fun slotFlush() = runBlocking {
        db.sessionDao().getSessionSlots(sessionId).first { it.slotId == slotIds[0] }.flush
    }

    @Test
    fun noFlushSwitchWhenTheMuscleIsNotStiff() {
        markStiff(Chip.BACK) // bench press trains the chest
        showExercise()
        waitForTag("target-card")

        assertThat(tagExists("flush-switch")).isFalse()
        rule.onNodeWithText("62.5").assertIsDisplayed()
    }

    @Test
    fun aStiffMuscleHoldsTheWeightAndOffersFlushInsteadInline() {
        markStiff(Chip.CHEST)
        showExercise()

        waitForTag("flush-switch")
        rule.onNodeWithTag("flush-switch").assertHeightIsAtLeast(MinTouch)
        rule.onNodeWithText("Flush instead").assertIsDisplayed()
        rule.onNodeWithText("60").assertIsDisplayed() // held at the last baseline, not 62.5
        rule.onNodeWithText("Stiff today: holding at 60kg with a longer warm-up.").assertIsDisplayed()
        assertThat(slotFlush()).isFalse()
    }

    @Test
    fun flippingFlushInsteadSwitchesTheTargetToTheLightFlushAndBack() {
        markStiff(Chip.CHEST)
        showExercise()
        waitForTag("flush-switch")

        rule.onNodeWithTag("flush-switch").performClick()
        waitForText("Flush: light weight, high reps to get blood into a stiff muscle.")
        rule.onNodeWithText("25").assertIsDisplayed() // 60 x 0.45 = 27, floored to a step
        rule.onNodeWithText("15–20 reps").assertIsDisplayed()
        rule.onNodeWithText("Round 1:00").assertIsDisplayed()
        assertThat(slotFlush()).isTrue()

        rule.onNodeWithTag("flush-switch").performClick()
        waitForText("Stiff today: holding at 60kg with a longer warm-up.")
        assertThat(slotFlush()).isFalse()
    }
}
