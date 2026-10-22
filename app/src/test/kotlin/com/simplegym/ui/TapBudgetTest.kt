package com.simplegym.ui

import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.simplegym.MainActivity
import com.simplegym.SimpleGymApp
import com.simplegym.TestGymApp
import com.simplegym.data.CheckInEntity
import com.simplegym.engine.Chip
import com.simplegym.seedOnboarded
import com.simplegym.timer.TimerController
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the tap rule (spec 5.12) on the real app at a phone viewport: from a fresh launch, a running timer is 2 taps away
 * with no dialog in between, and a swap is 1 tap. If this fails, fix the screen that adds the tap or the scroll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGymApp::class, qualifiers = "w411dp-h891dp")
class TapBudgetTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (rule.activity.application as SimpleGymApp).container

    @Before
    fun setUp() = TimerController.reset()

    @After
    fun tearDown() = TimerController.reset()

    /** Seeded, onboarded, with Legs stiff today, so Home shows the check-in row in its busiest state. */
    private fun launchSeededWithAStiffChip() {
        runBlocking {
            container.seeded.await()
            container.seedOnboarded()
            container.db.sessionDao().upsertCheckIn(CheckInEntity(LocalDate.of(2026, 10, 22), setOf(Chip.LEGS)))
        }
        waitForText("Start $SUGGESTED_DAY")
    }

    private fun waitForText(text: String) = rule.waitUntil(timeoutMillis = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun twoTapsFromALaunchReachARunningTimerWithNoDialog() {
        launchSeededWithAStiffChip()
        rule.onNodeWithText("Legs").assertIsDisplayed() // the stiff chip is on screen, above the day cards
        assertThat(TimerController.state.value.running).isFalse()

        // Tap 1: the suggested day's card, on screen without scrolling.
        rule.onNodeWithText("Start $SUGGESTED_DAY").assertIsDisplayed().performClick()
        waitForText("▶ Start timer")
        rule.onAllNodes(isDialog()).assertCountEquals(0)

        // Tap 2: the next row's timer button, also on screen without scrolling.
        rule.onNodeWithText("▶ Start timer").assertIsDisplayed().performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            TimerController.state.value.running
        }

        assertThat(TimerController.state.value.running).isTrue()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun oneTapOnASwapChipChangesTheTargetCard() {
        launchSeededWithAStiffChip()
        rule.onNodeWithText("Start $SUGGESTED_DAY").performClick()
        waitForText(FIRST_EXERCISE)
        rule.onNodeWithText(FIRST_EXERCISE).performClick() // jumping to an exercise is one tap
        waitForText("Stop 1–2 reps short of failure.")
        // Bench press is the target card, so the machine press is a swap chip and Bench press is not.
        assertThat(hasChip("chest-press-machine")).isTrue()
        assertThat(hasChip("bench-press")).isFalse()

        rule.onNodeWithTag("chip-chest-press-machine").assertIsDisplayed().performClick()

        // The target card is now the machine press: Bench press became a swap chip, and no dialog got in the way.
        rule.waitUntil(timeoutMillis = 5_000) { hasChip("bench-press") }
        assertThat(hasChip("chest-press-machine")).isFalse()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
    }

    private fun hasChip(exerciseId: String): Boolean {
        shadowOf(Looper.getMainLooper()).idle()
        return rule.onAllNodes(hasTestTag("chip-$exerciseId")).fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        const val SUGGESTED_DAY = "Chest / Triceps"
        const val FIRST_EXERCISE = "Bench press"
    }
}
