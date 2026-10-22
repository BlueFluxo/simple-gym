package com.simplegym.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.ui.theme.Amber
import com.simplegym.ui.theme.MinTouch

/** Document types offered by the picker: JSON first, anything else as a fallback for providers that mislabel it. */
private val PICKER_TYPES = arrayOf("application/json", "*/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }
    BackHandler(enabled = state.manual) { viewModel.showWelcome() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (state.manual) "Set up manually" else "Welcome to Simple Gym") }) },
        bottomBar = {
            if (state.manual) {
                Button(
                    onClick = viewModel::submitManual,
                    enabled = !state.busy,
                    modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp).heightIn(min = MinTouch).testTag("start"),
                ) { Text("Start") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.manual) {
                ManualForm(state, viewModel)
            } else {
                Welcome(state, onImport = { picker.launch(PICKER_TYPES) }, onManual = viewModel::showManual)
            }
        }
    }
}

@Composable
private fun Welcome(state: OnboardingUiState, onImport: () -> Unit, onManual: () -> Unit) {
    Text(
        "Bring your starting data from a file, or enter the basics yourself. Either way the starter plan and the " +
            "exercise library are ready to use.",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
    Button(
        onClick = onImport,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("import-starting-data"),
    ) { Text("Import starting data") }
    OutlinedButton(
        onClick = onManual,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("set-up-manually"),
    ) { Text("Set up manually") }
    state.importError?.let { Text(it, color = Amber, modifier = Modifier.testTag("import-error")) }
}

@Composable
private fun ManualForm(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    OutlinedTextField(
        value = state.age,
        onValueChange = viewModel::setAge,
        label = { Text("Age") },
        singleLine = true,
        isError = "age" in state.errors,
        supportingText = state.errors["age"]?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("field-age"),
    )
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Protect my lower back", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = state.lowerBackProtection,
            onCheckedChange = viewModel::setLowerBackProtection,
            modifier = Modifier.testTag("switch-lower-back"),
        )
    }
    if (state.rows.isNotEmpty()) {
        Text("Current working weights (optional)", style = MaterialTheme.typography.titleMedium)
        state.rows.forEach { row ->
            OutlinedTextField(
                value = state.weights[row.exerciseId].orEmpty(),
                onValueChange = { viewModel.setWeight(row.exerciseId, it) },
                label = { Text("${row.name} (kg)") },
                singleLine = true,
                isError = row.exerciseId in state.errors,
                supportingText = (state.errors[row.exerciseId] ?: if (row.bodyweight) BODYWEIGHT_NOTE else null)?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("field-weight-${row.exerciseId}"),
            )
        }
    }
    state.importError?.let { Text(it, color = Amber, modifier = Modifier.testTag("import-error")) }
}

private const val BODYWEIGHT_NOTE = "Bodyweight: any kg you enter here is not used."
