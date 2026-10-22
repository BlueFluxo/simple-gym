package com.simplegym.ui.timer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.engine.Phase
import com.simplegym.timer.TimerRun
import com.simplegym.timer.TimerRunStore
import com.simplegym.timer.TimerUiState
import com.simplegym.timer.formatClock
import com.simplegym.ui.nav.LogRoute
import com.simplegym.ui.theme.Accent
import com.simplegym.ui.theme.MinTouch

private val RingSize = 260.dp
private val RingStroke = 18.dp

/**
 * The run starts as the screen opens (no Start button) and keeps going in [com.simplegym.timer.TimerService] if the
 * lifter leaves. When it ends, or the lifter taps Stop, an EMOM run from a slot opens Log; anything else just goes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(viewModel: TimerViewModel, onBack: () -> Unit, onLog: (LogRoute) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    AskForNotificationsOnce()
    LaunchedEffect(ui.finishedRun) {
        val run = ui.finishedRun ?: return@LaunchedEffect
        viewModel.finishHandled()
        val route = logRouteFor(run)
        if (route != null) onLog(route) else onBack()
    }
    // Restored with no run to join: back to the log of a run that ended meanwhile, or out.
    LaunchedEffect(ui.exit) {
        val exit = ui.exit ?: return@LaunchedEffect
        if (exit.log != null) onLog(exit.log) else onBack()
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(ui.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val run = ui.state.run
            val timer = ui.state.timer
            if (run != null && timer != null) {
                Ring(run, ui.state)
                if (run.kind == TimerRun.EMOM) {
                    Adjusters(
                        run = run,
                        onRounds = viewModel::changeRounds,
                        onRoundSec = viewModel::changeRoundSec,
                    )
                }
                RunControls(
                    paused = ui.state.paused,
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    onStop = {
                        // Stop on a slot's timer leads to its log: the set was done, whatever the clock says.
                        val route = logRouteFor(run)
                        viewModel.stop()
                        if (route != null) onLog(route) else onBack()
                    },
                )
            }
        }
    }
}

/** Pause or Resume, and Stop: the buttons under every running timer. */
@Composable
internal fun RunControls(paused: Boolean, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        val buttonModifier = Modifier.weight(1f).heightIn(min = MinTouch)
        if (paused) {
            Button(onClick = onResume, modifier = buttonModifier) { Text("Resume") }
        } else {
            Button(onClick = onPause, modifier = buttonModifier) { Text("Pause") }
        }
        FilledTonalButton(onClick = onStop, modifier = buttonModifier) { Text("Stop") }
    }
}

@Composable
internal fun Ring(run: TimerRun, state: TimerUiState) {
    val timer = checkNotNull(state.timer)
    val phaseSec = when {
        run.kind == TimerRun.EMOM -> run.roundSec
        timer.phase == Phase.WORK -> run.workSec
        else -> run.restSec
    }
    val progress = if (phaseSec > 0) (timer.secondsLeft.toFloat() / phaseSec).coerceIn(0f, 1f) else 0f
    val track = MaterialTheme.colorScheme.outlineVariant
    Box(modifier = Modifier.size(RingSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = RingStroke.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
            drawArc(
                Accent, -90f, 360f * progress, useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatClock(timer.secondsLeft), style = MaterialTheme.typography.displayLarge)
            Text("Round ${timer.round} / ${run.rounds}", style = MaterialTheme.typography.titleLarge)
            if (run.kind == TimerRun.INTERVAL) {
                Text(
                    if (timer.phase == Phase.WORK) "Work" else "Rest",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.paused) {
                Text("Paused", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun Adjusters(run: TimerRun, onRounds: (Int) -> Unit, onRoundSec: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Stepper(
            label = "Rounds",
            value = run.rounds.toString(),
            less = "Fewer rounds",
            more = "More rounds",
            onLess = { onRounds(-1) },
            onMore = { onRounds(1) },
        )
        Stepper(
            label = "Round time",
            value = formatClock(run.roundSec),
            less = "Shorter rounds",
            more = "Longer rounds",
            onLess = { onRoundSec(-1) },
            onMore = { onRoundSec(1) },
        )
    }
}

@Composable
internal fun Stepper(
    label: String,
    value: String,
    less: String,
    more: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        FilledTonalButton(
            onClick = onLess,
            modifier = Modifier.size(MinTouch).semantics { contentDescription = less },
            contentPadding = PaddingValues(0.dp),
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(value, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.widthIn(min = 72.dp))
        FilledTonalButton(
            onClick = onMore,
            modifier = Modifier.size(MinTouch).semantics { contentDescription = more },
            contentPadding = PaddingValues(0.dp),
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/**
 * Asks for POST_NOTIFICATIONS the first time a timer starts, so the lock-screen notification can show. The timer is
 * already running and keeps working in the app if the answer is no.
 */
@Composable
internal fun AskForNotificationsOnce() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val store = TimerRunStore.from(context)
        if (!granted && !store.notificationPermissionAsked) {
            store.notificationPermissionAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
