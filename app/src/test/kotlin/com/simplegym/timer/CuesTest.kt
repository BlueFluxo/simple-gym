package com.simplegym.timer

import android.content.Context
import android.os.VibrationAttributes
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.engine.Cue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * B5: the cue vibration is an alarm, so "touch feedback" or "do not disturb" settings and a muted ringer do not eat the
 * round-start buzz the lifter feels with the phone in a pocket.
 */
@RunWith(RobolectricTestRunner::class)
class CuesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Robolectric shadows the system Vibrator; on API 31 the vibrator manager's own default vibrator is not shadowed.
    private val vibrator: Vibrator get() = context.getSystemService(Vibrator::class.java)

    private fun vibratorShadow() = shadowOf(vibrator)

    @Test
    @Config(sdk = [35])
    fun everyCueVibratesWithAlarmUsageThroughVibrationAttributesOnApi33Plus() {
        Cue.entries.forEach { cue ->
            Cues(context, vibrator).use { it.play(cue) }

            val attributes = vibratorShadow().vibrationAttributesFromLastVibration as VibrationAttributes
            assertThat(attributes.usage).isEqualTo(VibrationAttributes.USAGE_ALARM)
        }
    }

    @Test
    @Config(sdk = [31])
    fun everyCueVibratesWithAlarmUsageThroughAudioAttributesOnApi31And32() {
        Cue.entries.forEach { cue ->
            Cues(context, vibrator).use { it.play(cue) }

            // The framework turns the AudioAttributes into VibrationAttributes on its way to the vibrator service.
            val attributes = vibratorShadow().vibrationAttributesFromLastVibration as VibrationAttributes
            assertThat(attributes.usage).isEqualTo(VibrationAttributes.USAGE_ALARM)
        }
    }
}
