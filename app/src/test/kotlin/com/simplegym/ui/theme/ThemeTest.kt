package com.simplegym.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    @get:Rule
    val rule = createComposeRule()

    private fun scheme(): ColorScheme {
        lateinit var captured: ColorScheme
        rule.setContent { SimpleGymTheme { Capture { captured = it } } }
        rule.waitForIdle()
        return captured
    }

    @Composable
    private fun Capture(onScheme: (ColorScheme) -> Unit) = onScheme(MaterialTheme.colorScheme)

    @Test
    fun usesTheGlobalConstraintPalette() {
        val colors = scheme()

        assertThat(colors.background).isEqualTo(Color(0xFF0F1115))
        assertThat(colors.surface).isEqualTo(Color(0xFF1A1D23))
        assertThat(colors.primary).isEqualTo(Color(0xFFFF7A1A))
        assertThat(colors.tertiary).isEqualTo(Color(0xFFFFB020))
    }

    @Test
    fun containerRolesAreOnThePaletteNotTheMaterialBaseline() {
        val colors = scheme()
        val baseline = androidx.compose.material3.darkColorScheme()

        // FilledTonalButton draws on secondaryContainer; the baseline is a purple-grey.
        listOf(
            colors.primaryContainer to baseline.primaryContainer,
            colors.secondaryContainer to baseline.secondaryContainer,
            colors.tertiaryContainer to baseline.tertiaryContainer,
            colors.onSecondaryContainer to baseline.onSecondaryContainer,
            colors.errorContainer to baseline.errorContainer,
        ).forEach { (actual, material) -> assertThat(actual).isNotEqualTo(material) }
    }
}
