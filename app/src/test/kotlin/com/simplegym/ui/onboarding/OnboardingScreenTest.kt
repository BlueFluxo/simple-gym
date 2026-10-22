package com.simplegym.ui.onboarding

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.GymDatabase
import com.simplegym.inMemoryDb
import com.simplegym.testContainer
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.Home
import com.simplegym.ui.nav.Onboarding
import com.simplegym.ui.theme.MinTouch
import com.simplegym.ui.theme.SimpleGymTheme
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
@Config(qualifiers = "w411dp-h891dp")
class OnboardingScreenTest {
    private val rule = createComposeRule()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavController

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        container = testContainer(db)
        container.seed() // onboarded stays false
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

    @Test
    fun aFirstLaunchShowsTheWelcomeScreenWithBothPathsAsBigButtons() {
        showApp()

        waitForText("Import starting data")
        waitForDestination(Onboarding::class)
        rule.onNodeWithTag("import-starting-data").assertIsDisplayed().assertHeightIsAtLeast(MinTouch)
        rule.onNodeWithTag("set-up-manually").assertIsDisplayed().assertHeightIsAtLeast(MinTouch)
    }

    @Test
    fun anOnboardedInstallSkipsStraightToHome() {
        runBlocking {
            val settings = db.settingsDao()
            settings.upsert(checkNotNull(settings.get()).copy(onboarded = true))
        }
        showApp()

        waitForDestination(Home::class)
    }

    @Test
    fun manualSetupNeedsAnAgeBetween16And99() {
        showApp()
        waitForText("Set up manually")
        rule.onNodeWithTag("set-up-manually").performClick()

        rule.onNodeWithTag("start").performClick()
        waitForText("Enter an age from 16 to 99.")
        assertThat(navController.currentBackStackEntry?.destination?.hasRoute(Onboarding::class)).isTrue()

        rule.onNodeWithTag("field-age").performTextInput("15")
        rule.onNodeWithTag("start").performClick()
        waitForText("Enter an age from 16 to 99.")
        assertThat(runBlocking { checkNotNull(db.settingsDao().get()).onboarded }).isFalse()
    }

    @Test
    fun manualSetupSavesTheProfileAndTheTypedWeightsThenLandsOnHome() {
        showApp()
        waitForText("Set up manually")
        rule.onNodeWithTag("set-up-manually").performClick()

        rule.onNodeWithTag("field-age").performTextInput("41")
        rule.onNodeWithTag("switch-lower-back").performClick()
        waitForText("Bench press (kg)")
        rule.onNodeWithTag("field-weight-bench-press").performScrollTo().performTextInput("62.5")
        rule.onNodeWithTag("start").performClick()

        waitForDestination(Home::class)
        runBlocking {
            val settings = checkNotNull(db.settingsDao().get())
            assertThat(settings.age).isEqualTo(41)
            assertThat(settings.lowerBackProtection).isTrue()
            assertThat(settings.onboarded).isTrue()
            val log = db.logDao().getForExercise("bench-press").single()
            assertThat(log.weightKg).isEqualTo(62.5)
            assertThat(log.imported).isTrue()
            // Blank weights write nothing.
            assertThat(db.logDao().count()).isEqualTo(1)
        }
    }

    @Test
    fun theManualFormOffersTheBodyweightChinUpWithANoteThatItsWeightIsNotUsed() {
        showApp()
        waitForText("Set up manually")
        rule.onNodeWithTag("set-up-manually").performClick()

        waitForText("Chin-up (kg)")
        rule.onNodeWithTag("field-weight-chin-up").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Bodyweight: any kg you enter here is not used.").assertIsDisplayed()
    }

    @Test
    fun manualSetupWithOnlyAnAgeStillFinishes() {
        showApp()
        waitForText("Set up manually")
        rule.onNodeWithTag("set-up-manually").performClick()

        rule.onNodeWithTag("field-age").performTextInput("30")
        rule.onNodeWithTag("start").performClick()

        waitForDestination(Home::class)
        runBlocking {
            assertThat(checkNotNull(db.settingsDao().get()).lowerBackProtection).isFalse()
            assertThat(db.logDao().count()).isEqualTo(0)
        }
    }

    @Test
    fun aBadWeightIsReportedInlineAndNothingIsSaved() {
        showApp()
        waitForText("Set up manually")
        rule.onNodeWithTag("set-up-manually").performClick()

        rule.onNodeWithTag("field-age").performTextInput("30")
        waitForText("Bench press (kg)")
        rule.onNodeWithTag("field-weight-bench-press").performScrollTo().performTextInput("abc")
        rule.onNodeWithTag("start").performClick()

        waitForText("Enter a weight in kg, or leave it blank.")
        rule.onNodeWithText("Enter a weight in kg, or leave it blank.").assertIsDisplayed()
        assertThat(runBlocking { checkNotNull(db.settingsDao().get()).onboarded }).isFalse()
    }
}
