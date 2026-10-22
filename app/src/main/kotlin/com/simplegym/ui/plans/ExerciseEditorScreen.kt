package com.simplegym.ui.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.engine.BackLoad
import com.simplegym.engine.Joint
import com.simplegym.ui.components.GymChip
import com.simplegym.ui.components.NumberField
import com.simplegym.ui.components.displayName
import com.simplegym.ui.theme.MinTouch
import java.util.Locale

/** A new exercise of the lifter's own: the few fields the coaching engine needs. Saved under the slug of its name. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExerciseEditorScreen(viewModel: ExerciseEditorViewModel, onClose: (note: String?) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val form = ui.form
    Scaffold(topBar = { TopAppBar(title = { Text("New exercise") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).testTag("editor-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = viewModel::setName,
                    label = { Text("Name") },
                    singleLine = true,
                    isError = ui.nameError != null,
                    supportingText = ui.nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().testTag("exercise-name"),
                )
            }
            item {
                Options("Main muscle") {
                    CustomMuscles.forEach { muscle ->
                        GymChip(muscle.label(), onClick = { viewModel.setMuscle(muscle) }, selected = muscle == form.muscle)
                    }
                }
            }
            item {
                Options("Equipment") {
                    ui.equipmentOptions.forEach { equipment ->
                        GymChip(equipment, onClick = { viewModel.setEquipment(equipment) }, selected = equipment == form.equipment)
                    }
                }
            }
            if (ui.swapGroupOptions.isNotEmpty()) {
                item {
                    Options("Swap group") {
                        val selected = form.swapGroup ?: ui.swapGroupOptions.first()
                        ui.swapGroupOptions.forEach { group ->
                            GymChip(group, onClick = { viewModel.setSwapGroup(group) }, selected = group == selected)
                        }
                    }
                }
            }
            item {
                Options("Load on the lower back") {
                    BackLoad.entries.forEach { load ->
                        GymChip(
                            load.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) },
                            onClick = { viewModel.setBackLoad(load) },
                            selected = load == form.backLoad,
                        )
                    }
                }
            }
            item {
                Options("Joints it stresses") {
                    Joint.entries.forEach { joint ->
                        GymChip(joint.displayName(), onClick = { viewModel.toggleJoint(joint) }, selected = joint in form.joints)
                    }
                }
            }
            item { SwitchRow("Bodyweight", form.bodyweight, viewModel::setBodyweight, "bodyweight-switch") }
            item { SwitchRow("Assisted (weight helps you)", form.assisted, viewModel::setAssisted, "assisted-switch") }
            if (!form.bodyweight) {
                item {
                    NumberField(
                        "increment", "Smallest weight step (kg)", form.incrementKg, viewModel::setIncrement,
                        step = 0.5, min = 0.5, error = ui.incrementError, keyboardType = KeyboardType.Decimal,
                    )
                }
            }
            item {
                Button(
                    onClick = { viewModel.save(onClose) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("save-exercise"),
                ) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Options(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .heightIn(min = MinTouch)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun com.simplegym.engine.Muscle.label(): String =
    name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
