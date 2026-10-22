package com.simplegym.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.ui.components.GymChip
import com.simplegym.ui.components.NumberField
import com.simplegym.ui.theme.MinTouch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: LogViewModel, onSaved: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // imePadding lifts the whole screen above the keyboard, so Save stays reachable while a field is being typed in.
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(title = { Text(state.exerciseName, maxLines = 2, overflow = TextOverflow.Ellipsis) }) },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp)) {
                state.saveError?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(bottom = 8.dp).testTag("save-error"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.removed) {
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch)) { Text("Back") }
                } else {
                    Button(
                        onClick = { viewModel.save(onSaved) },
                        enabled = state.loaded,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
                    ) { Text("Save") }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.removed) {
                Text(REMOVED_MESSAGE, style = MaterialTheme.typography.titleMedium)
            }
            // Nothing to edit until the target has set the defaults, so an early tap can't be overwritten by them.
            if (state.loaded && !state.removed) {
                if (state.showWeight) {
                    NumberField(
                        name = "weight",
                        label = state.weightLabel,
                        value = state.weight,
                        onValueChange = viewModel::setWeight,
                        step = state.weightStep,
                        min = 0.0,
                        error = state.errors["weight"],
                    )
                }
                NumberField(
                    name = "reps",
                    label = "Reps",
                    value = state.reps,
                    onValueChange = viewModel::setReps,
                    step = 1.0,
                    min = 1.0,
                    error = state.errors["reps"],
                    keyboardType = KeyboardType.Number,
                )
                NumberField(
                    name = "sets",
                    label = "Sets",
                    value = state.sets,
                    onValueChange = viewModel::setSets,
                    step = 1.0,
                    min = 1.0,
                    error = state.errors["sets"],
                    keyboardType = KeyboardType.Number,
                )
                FeelChips(selected = state.feel, onPick = viewModel::setFeel)
                HurtSection(
                    open = state.hurtOpen,
                    hurt = state.hurt,
                    error = state.errors["hurt"],
                    onToggle = viewModel::toggleHurt,
                    onPick = viewModel::setHurt,
                )
                OutlinedTextField(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("field-note"),
                )
            }
        }
    }
}

@Composable
private fun FeelChips(selected: Feel, onPick: (Feel) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Feel.entries.forEach { feel ->
            GymChip(
                label = feel.label(),
                onClick = { onPick(feel) },
                selected = feel == selected,
                modifier = Modifier.weight(1f).testTag("feel-${feel.name}"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HurtSection(open: Boolean, hurt: Joint?, error: String?, onToggle: () -> Unit, onPick: (Joint) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GymChip(label = "This hurt", onClick = onToggle, selected = open)
        if (open) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Joint.entries.forEach { joint ->
                    GymChip(
                        label = joint.label(),
                        onClick = { onPick(joint) },
                        selected = joint == hurt,
                        modifier = Modifier.testTag("joint-${joint.name}"),
                    )
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun Feel.label(): String = name.lowercase().replaceFirstChar(Char::uppercase)

private fun Joint.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
