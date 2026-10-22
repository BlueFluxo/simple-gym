package com.simplegym.ui.plans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.engine.Chip
import com.simplegym.ui.components.GymChip
import com.simplegym.ui.components.displayName
import com.simplegym.ui.theme.Amber
import com.simplegym.ui.theme.MinTouch

/**
 * One day: its name, its muscle chips (with "Suggest" from the slots), and its slots in order. A slot's up/down buttons
 * reorder it, a tap on it opens the slot editor. Leaves by itself once the day is gone.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DayEditorScreen(
    viewModel: DayEditorViewModel,
    onOpenSlot: (slotId: Long?) -> Unit,
    onClose: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val nameError by viewModel.nameError.collectAsStateWithLifecycle()
    LaunchedEffect(ui.missing) { if (ui.missing) onClose() }
    // Keyed on the stored name, so the field follows a rename that was saved and keeps a half-typed one otherwise.
    var name by remember(ui.name) { mutableStateOf(ui.name) }
    var confirmDeleteDay by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(ui.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).testTag("editor-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Day name") },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = nameError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth().testTag("day-name"),
                    )
                    FilledTonalButton(
                        onClick = { viewModel.rename(name) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("save-day-name"),
                    ) { Text("Save name") }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Muscle chips")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip.entries.forEach { chip ->
                            GymChip(chip.displayName(), onClick = { viewModel.toggleChip(chip) }, selected = chip in ui.chips)
                        }
                    }
                    if (ui.slots.isNotEmpty()) {
                        OutlinedButton(
                            onClick = viewModel::suggestChips,
                            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("suggest-chips"),
                        ) { Text("Suggest from the exercises") }
                    }
                }
            }
            item { SectionTitle("Exercises") }
            items(ui.slots, key = { "slot-${it.slotId}" }) { slot ->
                val index = ui.slots.indexOf(slot)
                Card(modifier = Modifier.fillMaxWidth().testTag("slot-${slot.slotId}")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenSlot(slot.slotId) }
                                .heightIn(min = MinTouch)
                                .padding(16.dp),
                        ) {
                            Text(
                                slot.exerciseName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                slot.detail + if (slot.archived) " · archived" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (slot.archived) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MoveButtons(
                            label = slot.exerciseName,
                            canMoveUp = index > 0,
                            canMoveDown = index < ui.slots.lastIndex,
                            onMove = { viewModel.moveSlot(slot.slotId, it) },
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = { onOpenSlot(null) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("add-slot"),
                ) { Text("Add exercise") }
            }
            item {
                OutlinedButton(
                    onClick = viewModel::archiveDay,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("archive-day"),
                ) { Text("Archive day") }
                Text(
                    "Hides the day from Home. Its history stays, and Plans can restore it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                TextButton(
                    onClick = { confirmDeleteDay = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("delete-day"),
                ) { Text("Delete day", color = Amber) }
            }
        }
    }
    if (confirmDeleteDay) {
        ConfirmDialog(
            title = "Delete ${ui.name}?",
            text = "A day with workouts in your history is archived instead: it leaves Home but its history stays.",
            confirmLabel = "Delete",
            onConfirm = viewModel::deleteDay,
            onDismiss = { confirmDeleteDay = false },
        )
    }
}
