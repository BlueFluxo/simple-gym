package com.simplegym.ui.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.timer.TimerRun
import com.simplegym.timer.formatClock
import com.simplegym.ui.theme.MinTouch

/**
 * The timer without an exercise (spec 5.7): an EMOM tab and an Interval tab, each with its own settings and Start.
 * A running one shows the ring and controls, and an EMOM can still be adjusted. Nothing is logged: when a run ends
 * the screen just goes back, never to Log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandaloneTimerScreen(viewModel: StandaloneTimerViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    AskForNotificationsOnce()
    LaunchedEffect(ui.finishedRun) {
        if (ui.finishedRun == null) return@LaunchedEffect
        viewModel.finishHandled()
        onBack()
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Timer") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            val run = ui.state.run
            val timer = ui.state.timer
            if (run == null || timer == null) {
                PrimaryTabRow(selectedTabIndex = ui.tab.ordinal) {
                    StandaloneTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == ui.tab,
                            onClick = { viewModel.selectTab(tab) },
                            modifier = Modifier.heightIn(min = MinTouch),
                            text = { Text(tab.label) },
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (run != null && timer != null) {
                    Ring(run, ui.state)
                    if (run.kind == TimerRun.EMOM) {
                        Adjusters(run, onRounds = viewModel::changeEmomRounds, onRoundSec = viewModel::changeEmomRoundSec)
                    }
                    RunControls(
                        paused = ui.state.paused,
                        onPause = viewModel::pause,
                        onResume = viewModel::resume,
                        onStop = {
                            viewModel.stop()
                            onBack()
                        },
                    )
                } else {
                    when (ui.tab) {
                        StandaloneTab.EMOM -> {
                            Stepper(
                                label = "Rounds", value = ui.emom.rounds.toString(), less = "Fewer rounds",
                                more = "More rounds", onLess = { viewModel.changeEmomRounds(-1) },
                                onMore = { viewModel.changeEmomRounds(1) },
                            )
                            Stepper(
                                label = "Round time", value = formatClock(ui.emom.roundSec), less = "Shorter rounds",
                                more = "Longer rounds", onLess = { viewModel.changeEmomRoundSec(-1) },
                                onMore = { viewModel.changeEmomRoundSec(1) },
                            )
                        }
                        StandaloneTab.INTERVAL -> {
                            Stepper(
                                label = "Rounds", value = ui.interval.rounds.toString(), less = "Fewer rounds",
                                more = "More rounds", onLess = { viewModel.changeIntervalRounds(-1) },
                                onMore = { viewModel.changeIntervalRounds(1) },
                            )
                            Stepper(
                                label = "Work", value = formatClock(ui.interval.workSec), less = "Shorter work",
                                more = "Longer work", onLess = { viewModel.changeWork(-1) }, onMore = { viewModel.changeWork(1) },
                            )
                            Stepper(
                                label = "Rest", value = formatClock(ui.interval.restSec), less = "Shorter rest",
                                more = "Longer rest", onLess = { viewModel.changeRest(-1) }, onMore = { viewModel.changeRest(1) },
                            )
                        }
                    }
                    Button(
                        onClick = viewModel::start,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
                    ) { Text("Start") }
                }
            }
        }
    }
}
