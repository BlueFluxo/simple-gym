package com.simplegym.ui

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.AppContainer
import com.simplegym.MainActivity
import com.simplegym.composeRuleThenCloseDb
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.engine.Feel
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.seedOnboarded
import com.simplegym.testContainer
import com.simplegym.ui.log.LogScreen
import com.simplegym.ui.nav.ExerciseRoute
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.nav.Workout
import com.simplegym.ui.log.LogViewModel
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

/**
 * B6: the bottom buttons clear the navigation bar and the keyboard. The real insets are device-verified; here the
 * window is handed a navigation bar (and then a keyboard) and the Save button must sit above it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class InsetsTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules = composeRuleThenCloseDb(rule) { db }

    private lateinit var db: GymDatabase
    private lateinit var container: AppContainer
    private val today = LocalDate.of(2026, 10, 22)
    private var sessionId = 0L
    private var slotId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        container = testContainer(db)
        container.seedOnboarded()
        val chest = db.planDao().getActiveDays().first()
        slotId = db.planDao().getSlots(chest.id).first().id
        sessionId = container.sessionService.start(chest.id, today, clubId = null)
        db.logDao().insert(
            LogEntity(
                sessionId = null, slotId = null, exerciseId = "bench-press", date = today.minusDays(3),
                weightKg = 60.0, bestReps = 12, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
            ),
        )
    }

    @Test
    fun theActivityResizesForTheKeyboard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)

        assertThat(info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
            .isEqualTo(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun showLog() {
        val viewModel = LogViewModel(
            db = db, targets = container.targetService, logs = container.logService, sessionId = sessionId,
            slotId = slotId, exerciseId = "bench-press", today = { today }, timerStore = container.timerStore,
        )
        rule.setContent { SimpleGymTheme { LogScreen(viewModel, onSaved = {}, onBack = {}) } }
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodes(hasTestTag("field-reps")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun giveWindow(navBarPx: Int, imePx: Int = 0) {
        rule.runOnUiThread {
            val content = rule.activity.findViewById<ViewGroup>(android.R.id.content)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarPx))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .setVisible(WindowInsetsCompat.Type.ime(), imePx > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private fun saveBottomGap(label: String = "Save"): Dp {
        val root = rule.onRoot().getBoundsInRoot().bottom
        return root - rule.onNodeWithText(label).getBoundsInRoot().bottom
    }

    @Test
    fun theSaveButtonSitsAboveTheNavigationBar() {
        showLog()
        val before = saveBottomGap()

        giveWindow(navBarPx = 200)

        val navBar = with(rule.density) { 200.toDp() }
        assertThat(saveBottomGap().value).isAtLeast(before.value + navBar.value - 0.5f)
    }

    @Test
    fun theSaveButtonRidesAboveTheKeyboard() {
        showLog()
        val before = saveBottomGap()

        giveWindow(navBarPx = 100, imePx = 600)

        val keyboard = with(rule.density) { 600.toDp() }
        assertThat(saveBottomGap().value).isAtLeast(before.value + keyboard.value - 0.5f)
    }

    @Test
    fun theExerciseScreenButtonsSitAboveTheNavigationBar() {
        lateinit var navController: NavController
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
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodes(hasText("Skip for now")).fetchSemanticsNodes().isNotEmpty()
        }
        val before = saveBottomGap("Skip for now")

        giveWindow(navBarPx = 200)

        val navBar = with(rule.density) { 200.toDp() }
        assertThat(saveBottomGap("Skip for now").value).isAtLeast(before.value + navBar.value - 0.5f)
    }
}
