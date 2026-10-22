package com.simplegym.ui.settings

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.ClubEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SlotSwapEntity
import com.simplegym.domain.HurtService
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.ui.nav.Clubs
import com.simplegym.ui.nav.ExerciseRoute
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.Home
import com.simplegym.ui.nav.Settings
import com.simplegym.ui.nav.Workout
import com.simplegym.ui.theme.SimpleGymTheme
import java.time.LocalDate
import kotlinx.coroutines.flow.first
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
class SettingsTest {
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

    private fun show(route: Any = Home, firstText: String) {
        rule.setContent {
            SimpleGymTheme {
                val controller = rememberNavController()
                navController = controller
                GymNavHost(container, controller)
            }
        }
        if (route != Home) rule.runOnUiThread { navController.navigate(route) }
        waitForText(firstText)
        // the form shows at once but its lists fill in as Room answers; settle before a test scrolls or types
        if (route == Settings) waitUntil { runCatching { setting("field-eq-Dumbbell") }.isSuccess }
    }

    private fun waitUntil(condition: () -> Boolean) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun waitForText(text: String) = waitUntil { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun textExists(text: String): Boolean {
        shadowOf(Looper.getMainLooper()).idle()
        return rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tagExists(tag: String): Boolean {
        shadowOf(Looper.getMainLooper()).idle()
        return rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = waitUntil { tagExists(tag) }

    /** The tagged node of a lazy list, scrolled into view: what is off screen is not composed yet. */
    private fun node(list: String, tag: String) =
        rule.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)).let { rule.onNodeWithTag(tag) }

    private fun setting(tag: String) = node("settings-list", tag)

    private fun clubNames(): List<String> = runBlocking { db.clubDao().observeClubs().first() }.map { it.name }

    private fun settings() = runBlocking { checkNotNull(db.settingsDao().get()) }

    private fun exercise(id: String) = runBlocking { checkNotNull(db.libraryDao().get(id)) }

    private val isSwapChip = SemanticsMatcher("is a swap chip") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("chip-") == true
    }

    private fun chipIds(): List<String> {
        shadowOf(Looper.getMainLooper()).idle()
        return rule.onAllNodes(isSwapChip).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("chip-") }
    }

    private fun log(exerciseId: String, weightKg: Double, reps: Int, hurt: Joint? = null) = runBlocking {
        db.logDao().insert(
            LogEntity(
                sessionId = null, slotId = null, exerciseId = exerciseId, date = today.minusDays(3),
                weightKg = weightKg, bestReps = reps, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL, hurt = hurt,
            ),
        )
    }

    /** Opens the Exercise screen of [slotId] with Workout beneath it. */
    private fun openExercise(sessionId: Long, slotId: Long) {
        rule.runOnUiThread {
            navController.navigate(Workout(sessionId))
            navController.navigate(ExerciseRoute(sessionId, slotId))
        }
        waitForText("Stop 1–2 reps short of failure.")
    }

    private fun benchSlot() = runBlocking { db.planDao().getSlots(db.planDao().getActiveDays().first().id).first() }

    private fun startBenchSession(clubId: Long?) =
        runBlocking { container.sessionService.start(db.planDao().getActiveDays().first().id, today, clubId) }

    // ---------------------------------------------------------------- lower-back protection

    @Test
    fun turningLowerBackProtectionOnHidesAHighBackLoadSwapFromTheChipsButSearchStillFindsItWithAWarning() {
        val rowSlot = runBlocking {
            val slot = db.planDao().getActiveDays().flatMap { db.planDao().getSlots(it.id) }
                .first { it.defaultExerciseId == "chest-supported-row-machine" }
            db.planDao().insertSwaps(listOf(SlotSwapEntity(slot.id, "barbell-bent-over-row", 9)))
            slot
        }
        val sessionId = runBlocking { container.sessionService.start(rowSlot.dayId, today, clubId = null) }
        // with protection off the bent-over row is one of the slot's chips
        assertThat(runBlocking { container.targetService.swapPlan(sessionId, rowSlot.id, today) }.ordered.map { it.id })
            .contains("barbell-bent-over-row")

        show(Settings, "Protect my lower back")
        setting("switch-lower-back").performClick()
        waitUntil { settings().lowerBackProtection }

        rule.runOnUiThread {
            navController.navigate(Workout(sessionId))
            navController.navigate(ExerciseRoute(sessionId, rowSlot.id))
        }
        waitForText("Stop 1–2 reps short of failure.")
        assertThat(chipIds()).isNotEmpty()
        assertThat(chipIds()).doesNotContain("barbell-bent-over-row")

        rule.onNodeWithText("More…").performClick()
        rule.onNodeWithText("All").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("bent-over")
        waitForTag("result-barbell-bent-over-row")
        assertThat(textExists("⚠ High back load")).isTrue()
    }

    // ---------------------------------------------------------------- clubs: switcher and "Not at this club"

    @Test
    fun theHomeClubSwitcherIsShownOnlyOnceAClubExists() {
        show(Home, "Anything stiff today?")
        assertThat(tagExists("club-switcher")).isFalse()

        val clubId = runBlocking { container.clubService.add("Club A") }

        waitForTag("club-switcher")
        waitForText("Club A")
        // clubs present but none chosen: "No club", and nothing is filtered out
        assertThat(textExists("No club")).isTrue()
        assertThat(settings().currentClubId).isNull()

        rule.onNodeWithTag("club-$clubId").performClick()
        waitUntil { settings().currentClubId == clubId }
        rule.onNodeWithTag("club-none").performClick()
        waitUntil { settings().currentClubId == null }
    }

    @Test
    fun choosingAClubAlsoMovesTheOpenSessionToIt() {
        val clubId = runBlocking { container.clubService.add("Club A") }
        val sessionId = startBenchSession(clubId = null)
        show(Home, "Club A")

        rule.onNodeWithTag("club-$clubId").performClick()

        waitUntil { runBlocking { db.sessionDao().getSession(sessionId) }?.clubId == clubId }
    }

    @Test
    fun longPressingAChipOffersNotAtThisClubAndTheChipIsGoneThereButBackAtAnotherClub() {
        val (clubA, clubB) = runBlocking {
            val a = container.clubService.add("Club A")
            val b = container.clubService.add("Club B")
            container.clubService.select(a)
            a to b
        }
        val sessionId = startBenchSession(clubA)
        show(Home, "Club A")
        openExercise(sessionId, benchSlot().id)
        assertThat(chipIds()).contains("chest-press-machine")

        rule.onNodeWithTag("chip-chest-press-machine").performTouchInput { longClick() }
        waitForText("Not at this club")
        rule.onNodeWithTag("not-at-this-club").performClick()

        waitUntil { "chest-press-machine" !in chipIds() }
        assertThat(runBlocking { db.clubDao().getUnavailable(clubA) }).containsExactly("chest-press-machine")
        assertThat(runBlocking { db.clubDao().getUnavailable(clubB) }).isEmpty()
        assertThat(tagExists("unavailable-offer")).isFalse()

        // switch to the other club on Home: the chip is back on the next open of the exercise
        rule.runOnUiThread { navController.popBackStack(Home, inclusive = false) }
        waitForText("Club B")
        rule.onNodeWithTag("club-$clubB").performClick()
        waitUntil { runBlocking { db.sessionDao().getSession(sessionId) }?.clubId == clubB }
        openExercise(sessionId, benchSlot().id)
        assertThat(chipIds()).contains("chest-press-machine")
    }

    @Test
    fun longPressingAChipDoesNothingWhileNoClubIsSet() {
        val sessionId = startBenchSession(clubId = null)
        show(Home, "Anything stiff today?")
        openExercise(sessionId, benchSlot().id)

        rule.onNodeWithTag("chip-chest-press-machine").performTouchInput { longClick() }
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(tagExists("unavailable-offer")).isFalse()
        assertThat(textExists("Not at this club")).isFalse()
    }

    @Test
    fun cancellingTheOfferLeavesTheChipAlone() {
        val club = runBlocking { container.clubService.add("Club A") }
        val sessionId = startBenchSession(club)
        show(Home, "Anything stiff today?")
        openExercise(sessionId, benchSlot().id)

        rule.onNodeWithTag("chip-chest-press-machine").performTouchInput { longClick() }
        waitForTag("unavailable-offer")
        rule.onAllNodes(hasText("Cancel"))[0].performClick()

        waitUntil { !tagExists("unavailable-offer") }
        assertThat(chipIds()).contains("chest-press-machine")
        assertThat(runBlocking { db.clubDao().getUnavailable(club) }).isEmpty()
    }

    @Test
    fun theClubsScreenAddsRenamesGivesAnExerciseBackAndDeletesAfterConfirming() {
        val clubId = runBlocking {
            val id = container.clubService.add("Club A")
            container.clubService.setUnavailable(id, "chest-press-machine", true)
            container.clubService.select(id)
            id
        }
        val sessionId = startBenchSession(clubId)
        show(Clubs, "Chest press machine")

        node("clubs-list", "new-club-name").performTextInput("Club B")
        node("clubs-list", "add-club").performClick()
        waitUntil { clubNames() == listOf("Club A", "Club B") }

        node("clubs-list", "club-name-$clubId").performTextReplacement("Club Z")
        waitUntil { clubNames() == listOf("Club B", "Club Z") }

        node("clubs-list", "available-$clubId-chest-press-machine").performClick()
        waitUntil { runBlocking { db.clubDao().getUnavailable(clubId) }.isEmpty() }

        node("clubs-list", "delete-club-$clubId").performClick()
        rule.onNodeWithTag("confirm-delete").performClick()
        waitUntil { clubNames() == listOf("Club B") }
        // neither the chosen club nor the open session keeps pointing at a club that is gone
        assertThat(settings().currentClubId).isNull()
        assertThat(runBlocking { db.sessionDao().getSession(sessionId) }?.clubId).isNull()
    }

    @Test
    fun aBlankClubNameIsRejectedInline() {
        show(Clubs, "New club")

        node("clubs-list", "add-club").performClick()

        waitForText("Give the club a name")
        assertThat(clubNames()).isEmpty()
    }

    // ---------------------------------------------------------------- increments

    @Test
    fun settingTheDumbbellIncrementTo2MakesTheNextDumbbellTargetStepBy2() {
        log("dumbbell-bench-press", weightKg = 20.0, reps = 12) // the top of its 8-12 range: time to go up
        val slot = benchSlot()
        val sessionId = startBenchSession(null)
        fun nextTarget() = runBlocking { container.targetService.targetFor(slot.id, "dumbbell-bench-press", today) }.weightKg
        assertThat(nextTarget()).isEqualTo(22.5) // the library's 2.5

        show(Settings, "Protect my lower back")
        setting("field-eq-Dumbbell").performTextReplacement("2.0")

        waitUntil { settings().equipmentIncrements["Dumbbell"] == 2.0 }
        assertThat(nextTarget()).isEqualTo(22.0)
        assertThat(sessionId).isNotEqualTo(0L)
    }

    @Test
    fun anInvalidIncrementShowsAnErrorAndSavesNothing() {
        show(Settings, "Protect my lower back")

        setting("field-eq-Dumbbell").performTextReplacement("0")

        waitForText("Enter a step in kg above 0.")
        assertThat(settings().equipmentIncrements).isEmpty()
    }

    @Test
    fun useTheLibraryDefaultRemovesTheEquipmentStep() {
        runBlocking { container.settingsService.setEquipmentIncrement("Dumbbell", 2.0) }
        show(Settings, "Protect my lower back")

        setting("reset-eq-Dumbbell").performClick()

        waitUntil { settings().equipmentIncrements.isEmpty() }
    }

    @Test
    fun anExerciseCanHaveItsOwnIncrementSetFromTheSearchAndCleared() {
        log("dumbbell-bench-press", weightKg = 20.0, reps = 12)
        val slot = benchSlot()
        show(Settings, "Protect my lower back")

        setting("increment-search").performTextInput("dumbbell bench")
        setting("field-ex-dumbbell-bench-press").performTextReplacement("1.5")
        waitUntil { exercise("dumbbell-bench-press").incrementOverrideKg == 1.5 }
        assertThat(runBlocking { container.targetService.targetFor(slot.id, "dumbbell-bench-press", today) }.weightKg)
            .isEqualTo(21.5)

        setting("clear-ex-dumbbell-bench-press").performClick()
        waitUntil { exercise("dumbbell-bench-press").incrementOverrideKg == null }
        assertThat(runBlocking { container.targetService.targetFor(slot.id, "dumbbell-bench-press", today) }.weightKg)
            .isEqualTo(22.5)
    }

    // ---------------------------------------------------------------- hurt flags

    @Test
    fun clearHurtFlagRemovesTheBadge() {
        log("bench-press", weightKg = 60.0, reps = 8, hurt = Joint.SHOULDER)
        show(Settings, "Protect my lower back")
        setting("hurt-bench-press")
        waitForText("⚠ Shoulder · 1×")

        setting("clear-hurt-bench-press").performClick()

        waitUntil { !textExists("⚠ Shoulder · 1×") }
        assertThat(exercise("bench-press").hurtClearedOn).isEqualTo(today)
        assertThat(runBlocking { HurtService(db).activeHurts() }).isEmpty()
        waitForText("Nothing flagged.")
    }

    // ---------------------------------------------------------------- data

    @Test
    fun importWarnsFirstWhenTheLifterAlreadyHasLogs() {
        log("bench-press", weightKg = 60.0, reps = 8)
        show(Settings, "Protect my lower back")

        setting("import-starting-data").performClick()

        waitForText("Import again?")
        rule.onNodeWithText("Cancel").performClick()
        waitUntil { !textExists("Import again?") }
        assertThat(runBlocking { db.logDao().count() }).isEqualTo(1)
    }

    @Test
    fun importWarnsFirstWhenTheLifterAlreadyHasClubs() {
        runBlocking { db.clubDao().insert(ClubEntity(name = "Club A")) }
        show(Settings, "Protect my lower back")

        setting("import-starting-data").performClick()

        waitForText("Import again?")
        assertThat(clubNames()).containsExactly("Club A")
    }

    @Test
    fun restoreBackupAsksBeforeReplacingAnything() {
        log("bench-press", weightKg = 60.0, reps = 8)
        show(Settings, "Protect my lower back")

        setting("restore-backup").performClick()

        waitForText("Restore backup?")
        rule.onNodeWithText("Cancel").performClick()
        waitUntil { !textExists("Restore backup?") }
        assertThat(runBlocking { db.logDao().count() }).isEqualTo(1)
    }

    @Test
    fun saveAndRestoreAreOfferedInTheDataSection() {
        show(Settings, "Protect my lower back")

        setting("save-backup").assertTextContains("Save backup")
        setting("restore-backup").assertTextContains("Restore backup")
    }

    // ---------------------------------------------------------------- profile

    @Test
    fun ageMustBeFrom16To99() {
        runBlocking { db.settingsDao().setAge(45) }
        show(Settings, "Protect my lower back")

        for (bad in listOf("15", "100", "")) {
            setting("field-age").performTextReplacement(bad)
            waitForText("Enter an age from 16 to 99.")
            assertThat(settings().age).isEqualTo(45)
        }
        setting("field-age").performTextReplacement("42")

        waitUntil { settings().age == 42 }
        assertThat(textExists("Enter an age from 16 to 99.")).isFalse()
    }

    @Test
    fun theBoundsOfTheAgeRangeAreAccepted() {
        show(Settings, "Protect my lower back")

        setting("field-age").performTextReplacement("16")
        waitUntil { settings().age == 16 }
        setting("field-age").performTextReplacement("99")
        waitUntil { settings().age == 99 }
    }

    @Test
    fun theClubsSectionOpensTheClubsScreen() {
        show(Settings, "Protect my lower back")

        setting("open-clubs").performClick()

        waitForText("New club")
        assertThat(navController.currentDestination?.hasRoute<Clubs>()).isTrue()
    }
}
