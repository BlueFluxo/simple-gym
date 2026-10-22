package com.simplegym.ui.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.ui.components.NumberField
import com.simplegym.ui.theme.Amber
import com.simplegym.ui.theme.MinTouch

/**
 * One slot: its default exercise (picked from the library search), sets, rep range, round time and swap list.
 * Nothing is saved until "Save"; a value that fails its check is explained under its own field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotEditorScreen(viewModel: SlotEditorViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(if (ui.isNew) "New exercise slot" else "Edit slot") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).testTag("editor-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle("Exercise")
                    val exercise = ui.exercise
                    Text(
                        exercise?.name ?: "None picked yet",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("slot-exercise"),
                    )
                    if (exercise?.archived == true) {
                        Text("This exercise is archived: pick another to replace it.", color = Amber)
                    }
                    ui.errors["exerciseId"]?.let { Text(it, color = Amber, modifier = Modifier.testTag("error-exerciseId")) }
                }
            }
            item {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Search the library") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("exercise-search"),
                )
            }
            items(ui.results, key = { "result-${it.id}" }) { option ->
                ResultRow(
                    option = option,
                    canUse = option.id != ui.form.exerciseId,
                    canSwap = option.id != ui.form.exerciseId && option.id !in ui.form.swaps,
                    onUse = { viewModel.useAsDefault(option.id) },
                    onSwap = { viewModel.addSwap(option.id) },
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("sets", "Sets", ui.form.sets, viewModel::setSets, step = 1.0, min = 1.0, error = ui.errors["sets"], keyboardType = KeyboardType.Number)
                    NumberField("lo", "Lowest reps", ui.form.lo, viewModel::setLo, step = 1.0, min = 1.0, error = ui.errors["lo"], keyboardType = KeyboardType.Number)
                    NumberField("hi", "Highest reps", ui.form.hi, viewModel::setHi, step = 1.0, min = 1.0, error = ui.errors["hi"], keyboardType = KeyboardType.Number)
                    NumberField(
                        "roundSec", "Round time (seconds)", ui.form.roundSec, viewModel::setRoundSec, step = 15.0, min = 15.0,
                        error = ui.errors["roundSec"], keyboardType = KeyboardType.Number,
                    )
                }
            }
            item { SectionTitle("Swaps") }
            if (ui.swaps.isEmpty()) item { Text("No swaps. Use + Swap on a search result.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(ui.swaps, key = { "swap-${it.id}" }) { swap ->
                val index = ui.swaps.indexOf(swap)
                Card(modifier = Modifier.fillMaxWidth().testTag("swap-${swap.id}")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(swap.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (swap.archived) Text("archived", color = Amber, style = MaterialTheme.typography.bodyMedium)
                        }
                        MoveButtons(swap.name, canMoveUp = index > 0, canMoveDown = index < ui.swaps.lastIndex) {
                            viewModel.moveSwap(swap.id, it)
                        }
                        FilledTonalButton(
                            onClick = { viewModel.removeSwap(swap.id) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(MinTouch).semantics { contentDescription = "Remove ${swap.name}" },
                        ) { Text("✕", style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
            item {
                Button(
                    onClick = { viewModel.save(onClose) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("save-slot"),
                ) { Text("Save") }
            }
            if (!ui.isNew) {
                item {
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("delete-slot"),
                    ) { Text("Remove from day", color = Amber) }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Remove ${ui.exercise?.name ?: "this exercise"}?",
            text = "Its logged sets stay in your history.",
            confirmLabel = "Remove",
            onConfirm = { viewModel.delete(onClose) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ResultRow(option: ExerciseOption, canUse: Boolean, canSwap: Boolean, onUse: () -> Unit, onSwap: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("result-${option.id}")) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)) {
            Text(option.name, style = MaterialTheme.typography.titleMedium)
            Text(option.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canUse) {
                    FilledTonalButton(onClick = onUse, modifier = Modifier.heightIn(min = MinTouch).testTag("use-${option.id}")) {
                        Text("Use")
                    }
                }
                if (canSwap) {
                    OutlinedButton(onClick = onSwap, modifier = Modifier.heightIn(min = MinTouch).testTag("swap-add-${option.id}")) {
                        Text("+ Swap")
                    }
                }
            }
        }
    }
}
