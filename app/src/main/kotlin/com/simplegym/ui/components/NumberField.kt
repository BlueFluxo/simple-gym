package com.simplegym.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simplegym.domain.stepNumberText
import com.simplegym.ui.theme.MinTouch

/**
 * A big-numeral text field with a − and a + button either side that move it by [step] (never below [min]).
 * [name] tags the parts for tests: "field-$name", "step-$name-down" and "step-$name-up". [error] shows under the field.
 */
@Composable
fun NumberField(
    name: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    step: Double,
    min: Double,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Decimal,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", "Decrease $label", Modifier.testTag("step-$name-down")) {
            onValueChange(stepNumberText(value, -step, min))
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.displayMedium.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f).heightIn(min = MinTouch).testTag("field-$name"),
        )
        StepButton("+", "Increase $label", Modifier.testTag("step-$name-up")) {
            onValueChange(stepNumberText(value, step, min))
        }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.size(MinTouch).semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp),
    ) { Text(symbol, style = MaterialTheme.typography.headlineMedium) }
}
