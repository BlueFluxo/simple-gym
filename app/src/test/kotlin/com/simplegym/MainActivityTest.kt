package com.simplegym

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import com.simplegym.data.SettingsEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGymApp::class, qualifiers = "w411dp-h891dp")
class MainActivityTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (rule.activity.application as SimpleGymApp).container

    @Test
    fun showsTheHomeScreenWithTheSeededPlan() {
        runBlocking {
            container.seeded.await()
            val settings = container.db.settingsDao()
            settings.upsert((settings.get() ?: SettingsEntity()).copy(onboarded = true))
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText("Start Chest / Triceps")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Simple Gym").assertIsDisplayed()
        rule.onNodeWithText("Suggested today").assertIsDisplayed()
    }

    @Test
    fun aFreshInstallOpensOnTheWelcomeScreenNotHome() {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText("Import starting data")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Set up manually").assertIsDisplayed()
        assertThat(rule.onAllNodes(hasText("Suggested today")).fetchSemanticsNodes()).isEmpty()
    }
}
