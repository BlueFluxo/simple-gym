package com.simplegym.ui.plans

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.data.DayEntity
import com.simplegym.data.ExerciseEntity
import com.simplegym.ui.components.displayName
import com.simplegym.ui.theme.MinTouch

/** Plans: the days in order (up/down to reorder, a tap opens one), a field to add a day, and the lifter's own exercises. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(
    viewModel: PlansViewModel,
    onOpenDay: (dayId: Long) -> Unit,
    onNewExercise: () -> Unit,
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val newDayError by viewModel.newDayError.collectAsStateWithLifecycle()
    var newDayName by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ExerciseEntity?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Plans") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).testTag("editor-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (notice != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().testTag("plans-notice")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(notice, modifier = Modifier.weight(1f).padding(start = 16.dp))
                            TextButton(onClick = onDismissNotice, modifier = Modifier.heightIn(min = MinTouch)) { Text("✕") }
                        }
                    }
                }
            }
            item { SectionTitle("Days") }
            val days = ui.days.orEmpty()
            items(days, key = { "day-${it.id}" }) { day ->
                DayRow(
                    day = day,
                    isFirst = day.id == days.first().id,
                    isLast = day.id == days.last().id,
                    onOpen = { onOpenDay(day.id) },
                    onMove = { viewModel.moveDay(day.id, it) },
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newDayName,
                        onValueChange = {
                            newDayName = it
                            viewModel.clearNewDayError()
                        },
                        label = { Text("New day name") },
                        singleLine = true,
                        isError = newDayError != null,
                        supportingText = newDayError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth().testTag("new-day-name"),
                    )
                    Button(
                        onClick = {
                            viewModel.addDay(newDayName) {
                                newDayName = ""
                                onOpenDay(it)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("add-day"),
                    ) { Text("Add day") }
                }
            }
            item { SectionTitle("My exercises") }
            items(ui.customExercises.orEmpty(), key = { "exercise-${it.id}" }) { exercise ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            exercise.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { deleting = exercise },
                            modifier = Modifier.heightIn(min = MinTouch).testTag("delete-exercise-${exercise.id}"),
                        ) { Text("Delete") }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = onNewExercise,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("new-exercise"),
                ) { Text("New exercise") }
            }
            if (ui.archivedDays.isNotEmpty() || ui.archivedExercises.isNotEmpty()) {
                item { SectionTitle("Archived") }
                items(ui.archivedDays, key = { "archived-day-${it.id}" }) { day ->
                    ArchivedRow(
                        title = day.name,
                        detail = "Day",
                        restoreTag = "restore-day-${day.id}",
                        restoreLabel = "Restore ${day.name}",
                        onRestore = { viewModel.restoreDay(day.id) },
                    )
                }
                items(ui.archivedExercises, key = { "archived-exercise-${it.id}" }) { exercise ->
                    ArchivedRow(
                        title = exercise.name,
                        detail = "Exercise",
                        restoreTag = "restore-exercise-${exercise.id}",
                        restoreLabel = "Restore ${exercise.name}",
                        onRestore = { viewModel.restoreExercise(exercise.id) },
                    )
                }
            }
        }
    }
    deleting?.let { exercise ->
        ConfirmDialog(
            title = "Delete ${exercise.name}?",
            text = "If it has history or is in a plan it is hidden instead, and its history stays in Progress.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.deleteExercise(exercise.id) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun DayRow(day: DayEntity, isFirst: Boolean, isLast: Boolean, onOpen: () -> Unit, onMove: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("day-${day.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .heightIn(min = MinTouch)
                    .padding(16.dp),
            ) {
                Text(day.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    chipsText(day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoveButtons(label = day.name, canMoveUp = !isFirst, canMoveDown = !isLast, onMove = onMove)
        }
    }
}

@Composable
private fun ArchivedRow(title: String, detail: String, restoreTag: String, restoreLabel: String, onRestore: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(
                onClick = onRestore,
                modifier = Modifier
                    .heightIn(min = MinTouch)
                    .testTag(restoreTag)
                    .semantics { contentDescription = restoreLabel },
            ) { Text("Restore") }
        }
    }
}

private fun chipsText(day: DayEntity): String =
    day.chips.sorted().joinToString(" · ") { it.displayName() }.ifEmpty { "No muscle chips" }

// ---- shared by the plan editor screens ----

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

/** Up and down buttons that reorder a row; "Move <label> up" and "Move <label> down" are their descriptions. */
@Composable
internal fun MoveButtons(label: String, canMoveUp: Boolean, canMoveDown: Boolean, onMove: (Int) -> Unit) {
    Row {
        FilledTonalButton(
            onClick = { onMove(-1) },
            enabled = canMoveUp,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(MinTouch).semantics { contentDescription = "Move $label up" },
        ) { Text("↑", style = MaterialTheme.typography.titleLarge) }
        FilledTonalButton(
            onClick = { onMove(1) },
            enabled = canMoveDown,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(MinTouch).semantics { contentDescription = "Move $label down" },
        ) { Text("↓", style = MaterialTheme.typography.titleLarge) }
    }
}

/** A confirmation for a destructive action: the one kind of dialog the app allows (spec §5.12). */
@Composable
internal fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = MinTouch).testTag("confirm-delete"),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text("Cancel") } },
    )
}
