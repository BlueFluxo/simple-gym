package com.simplegym.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.ui.components.HurtBadge
import com.simplegym.ui.components.NumberField
import com.simplegym.ui.plans.ConfirmDialog
import com.simplegym.ui.plans.SectionTitle
import com.simplegym.ui.theme.Amber
import com.simplegym.ui.theme.MinTouch

/** Document types offered by the picker: JSON first, anything else as a fallback for providers that mislabel it. */
private val PICKER_TYPES = arrayOf("application/json", "*/*")
private const val STEP_KG = 0.5
private const val BACKUP_MIME = "application/json"

/** Settings: profile, clubs, weight steps, hurt flags and data. Each section edits in place; nothing here is a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpenClubs: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }
    val scope = rememberCoroutineScope()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
        if (uri != null) viewModel.saveBackupTo(uri)
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.restoreFrom(uri)
    }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    if (confirmRestore) {
        ConfirmDialog(
            title = "Restore backup?",
            text = "This replaces everything in the app with the backup: your plan, history, clubs and settings. " +
                "What is in the app now is lost unless you saved a backup of it first.",
            confirmLabel = "Replace everything",
            onConfirm = { restorePicker.launch(PICKER_TYPES) },
            onDismiss = { confirmRestore = false },
        )
    }
    var confirmImport by rememberSaveable { mutableStateOf(false) }
    if (confirmImport) {
        ConfirmDialog(
            title = "Import again?",
            text = "You already have clubs or logged workouts. Importing adds this file's clubs and weights on top of " +
                "them, so you may get duplicate clubs and doubled history. Import anyway?",
            confirmLabel = "Import anyway",
            onConfirm = { picker.launch(PICKER_TYPES) },
            onDismiss = { confirmImport = false },
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).testTag("settings-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("Profile") }
            item {
                OutlinedTextField(
                    value = ui.age,
                    onValueChange = viewModel::setAge,
                    label = { Text("Age") },
                    singleLine = true,
                    isError = ui.ageError != null,
                    supportingText = ui.ageError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("field-age"),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Protect my lower back", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = ui.lowerBackProtection,
                        onCheckedChange = viewModel::setLowerBackProtection,
                        modifier = Modifier.testTag("switch-lower-back"),
                    )
                }
            }

            item { SectionTitle("Clubs") }
            item {
                OutlinedButton(
                    onClick = onOpenClubs,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("open-clubs"),
                ) { Text(if (ui.clubCount == 0) "Add a club" else "Manage clubs (${ui.clubCount})") }
            }

            item { SectionTitle("Increments") }
            item {
                Text(
                    "The smallest weight step for each kind of equipment. Targets move in whole steps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ui.equipment, key = { "eq-${it.equipment}" }) { row ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NumberField(
                        name = "eq-${row.equipment}",
                        label = "${row.equipment} (kg)",
                        value = row.text,
                        onValueChange = { viewModel.setEquipmentIncrement(row.equipment, it) },
                        step = STEP_KG,
                        min = STEP_KG,
                        error = row.error,
                    )
                    if (row.custom) {
                        TextButton(
                            onClick = { viewModel.resetEquipmentIncrement(row.equipment) },
                            modifier = Modifier.heightIn(min = MinTouch).testTag("reset-eq-${row.equipment}"),
                        ) { Text("Use the library default") }
                    }
                }
            }
            item {
                Text(
                    "A step for one exercise",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Search exercises") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("increment-search"),
                )
            }
            items(ui.overrides, key = { "ex-${it.exerciseId}" }) { row ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NumberField(
                        name = "ex-${row.exerciseId}",
                        label = "${row.name} (kg)",
                        value = row.text,
                        onValueChange = { viewModel.setExerciseIncrement(row.exerciseId, it) },
                        step = STEP_KG,
                        min = STEP_KG,
                        error = row.error,
                    )
                    if (row.custom) {
                        TextButton(
                            onClick = { viewModel.clearExerciseIncrement(row.exerciseId) },
                            modifier = Modifier.heightIn(min = MinTouch).testTag("clear-ex-${row.exerciseId}"),
                        ) { Text("Use the equipment step") }
                    }
                }
            }

            item { SectionTitle("Hurt flags") }
            if (ui.hurts.isEmpty()) {
                item {
                    Text(
                        "Nothing flagged.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ui.hurts, key = { "hurt-${it.exerciseId}" }) { row ->
                Card(modifier = Modifier.fillMaxWidth().testTag("hurt-${row.exerciseId}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        row.badges.forEach { HurtBadge(it) }
                        OutlinedButton(
                            onClick = { viewModel.clearHurt(row.exerciseId) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("clear-hurt-${row.exerciseId}"),
                        ) { Text("Clear hurt flag") }
                    }
                }
            }

            item { SectionTitle("Data") }
            item {
                Button(
                    onClick = {
                        scope.launch { if (viewModel.hasExistingData()) confirmImport = true else picker.launch(PICKER_TYPES) }
                    },
                    enabled = !ui.import.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("import-starting-data"),
                ) { Text("Import starting data") }
            }
            item {
                Button(
                    onClick = { saver.launch(viewModel.backupFileName()) },
                    enabled = !ui.import.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("save-backup"),
                ) { Text("Save backup") }
            }
            item {
                OutlinedButton(
                    onClick = { confirmRestore = true },
                    enabled = !ui.import.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("restore-backup"),
                ) { Text("Restore backup") }
            }
            ui.import.message?.let { message ->
                item {
                    Text(
                        message,
                        color = if (ui.import.error) Amber else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("import-message"),
                    )
                }
            }
        }
    }
}
