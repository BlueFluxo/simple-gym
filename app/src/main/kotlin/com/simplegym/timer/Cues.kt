package com.simplegym.timer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.simplegym.engine.Cue

/**
 * Beeps and vibration for [Cue]s. Audio or vibration being unavailable (another app holds the audio resources, no
 * vibrator) must never stop the timer, so every failure is swallowed.
 */
class Cues(
    context: Context,
    private val vibrator: Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator,
) : AutoCloseable {
    private var tone: ToneGenerator? = null

    fun play(cue: Cue) {
        when (cue) {
            Cue.COUNTDOWN -> {
                beep(ToneGenerator.TONE_PROP_BEEP, COUNTDOWN_TONE_MS)
                vibrate(COUNTDOWN_VIBRATION_MS)
            }
            Cue.ROUND_START -> {
                beep(ToneGenerator.TONE_PROP_ACK, ROUND_START_TONE_MS)
                vibrate(ROUND_START_VIBRATION_MS)
            }
            Cue.FINISH -> {
                // A sustained tone: a DTMF tone plays for as long as it is asked to.
                beep(ToneGenerator.TONE_DTMF_S, FINISH_TONE_MS)
                vibrate(FINISH_VIBRATION_MS)
            }
        }
    }

    private fun beep(toneType: Int, durationMs: Int) {
        try {
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_ALARM, VOLUME).also { tone = it }
            generator.startTone(toneType, durationMs)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not play a cue tone", e)
        }
    }

    private fun vibrate(durationMs: Long) {
        try {
            val device = vibrator ?: return
            val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            // Alarm usage: the buzz is the cue, so touch-feedback and do-not-disturb settings must not silence it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION") // the only way to name a usage on API 31-32
                device.vibrate(effect, ALARM_AUDIO_ATTRIBUTES)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not vibrate", e)
        }
    }

    override fun close() {
        tone?.release()
        tone = null
    }

    companion object {
        /** The longest a cue's tone plays: the service waits this long after the finish cue before it goes away. */
        const val FINISH_TONE_MS = 900
        private const val TAG = "Cues"
        private val ALARM_AUDIO_ATTRIBUTES = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        private const val VOLUME = 80
        private const val COUNTDOWN_TONE_MS = 100
        private const val ROUND_START_TONE_MS = 300
        private const val COUNTDOWN_VIBRATION_MS = 60L
        private const val ROUND_START_VIBRATION_MS = 250L
        private const val FINISH_VIBRATION_MS = 600L
    }
}
