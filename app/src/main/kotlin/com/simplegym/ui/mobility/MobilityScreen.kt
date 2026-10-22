package com.simplegym.ui.mobility

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.engine.Phase
import com.simplegym.ui.components.GymChip
import com.simplegym.ui.theme.MinTouch
import com.simplegym.ui.timer.AskForNotificationsOnce
import com.simplegym.ui.timer.Ring
import com.simplegym.ui.timer.RunControls

/**
 * The core / mobility add-on: pick areas (today's stiff ones are ready-picked and first), start, and an interval run
 * steps through the moves. When it ends every area it covered is marked done and the screen goes back.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MobilityScreen(viewModel: MobilityViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    AskForNotificationsOnce()
    LaunchedEffect(ui.finished) { if (ui.finished) onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text("Core / mobility") }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val run = ui.state.run
            val timer = ui.state.timer
            if (run != null && timer != null) {
                val resting = timer.phase == Phase.REST
                Text(
                    if (resting) "Rest" else ui.moveName.orEmpty(),
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("move-name"),
                )
                Text(
                    ui.nextMoveName?.let { "Next: $it" } ?: "Last move",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Ring(run, ui.state)
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
                ui.hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Choose areas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ui.areas.forEach { area ->
                        GymChip(
                            label = area.name,
                            onClick = { viewModel.toggle(area.area) },
                            selected = area.selected,
                            detail = if (area.stiff) "Stiff today" else null,
                            modifier = Modifier.testTag("area-${area.area.name}"),
                        )
                    }
                }
                Button(
                    onClick = viewModel::start,
                    enabled = ui.canStart,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
                ) { Text("Start") }
            }
        }
    }
}
