package com.simplegym

import android.graphics.Color
import android.os.SystemClock
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.simplegym.timer.TimerController
import com.simplegym.timer.detectKilledRun
import com.simplegym.ui.nav.GymNavHost
import com.simplegym.ui.theme.SimpleGymTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark-only, so the system bars are always the dark style.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as SimpleGymApp).container
        lifecycleScope.launch { container.sessionService.closeStale(container.clock()) }
        // Before anything can restart the timer: a stored run with no service is a killed timer, whichever screen the
        // app reopens on. (Home also checks whenever it resumes, to show the Resume banner.)
        val killed = detectKilledRun(container.timerStore, TimerController.isServiceRunning(), SystemClock.elapsedRealtime())
        if (killed != null) lifecycleScope.launch { container.recordTimerKilled() }
        setContent { SimpleGymTheme { GymNavHost(container) } }
    }
}
