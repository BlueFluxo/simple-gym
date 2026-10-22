package com.simplegym.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.engine.formatKg
import com.simplegym.ui.theme.MinTouch
import java.time.format.DateTimeFormatter

private val ChartHeight = 320.dp
private val DateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

/** Progress: every exercise that has been logged, most recent first; a tap opens its chart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(viewModel: ProgressViewModel, onOpenExercise: (exerciseId: String) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Progress") }) }) { padding ->
        val exercises = ui.exercises
        when {
            exercises == null -> Unit
            exercises.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No history yet", style = MaterialTheme.typography.titleLarge)
            }
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(exercises, key = { it.id }) { exercise ->
                    Card(onClick = { onOpenExercise(exercise.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch)) {
                        Text(
                            exercise.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One exercise: its name as the title, the latest session in big numerals, and the weight chart below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressDetailScreen(viewModel: ProgressDetailViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(ui.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val latest = ui.points.lastOrNull()
            when {
                !ui.loaded -> Unit
                latest == null -> Text("No weighted sessions yet", style = MaterialTheme.typography.titleLarge)
                else -> {
                    Column {
                        Text("Latest ${formatKg(latest.weightKg)} kg", style = MaterialTheme.typography.displayMedium)
                        Text(
                            "${latest.reps} reps · ${DateFormat.format(latest.date)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    WeightChart(ui.points, Modifier.fillMaxWidth().height(ChartHeight))
                }
            }
        }
    }
}
