package com.simplegym.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.ui.plans.ConfirmDialog
import com.simplegym.ui.plans.SectionTitle
import com.simplegym.ui.theme.MinTouch

/**
 * Clubs: add, rename and delete a gym, and give back an exercise that was marked "Not at this club". Which club the
 * lifter is at is chosen on Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClubsScreen(viewModel: ClubsViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var newName by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ClubRowUi?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Clubs") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).testTag("clubs-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(ui.clubs.orEmpty(), key = { "club-${it.id}" }) { club ->
                ClubCard(
                    club = club,
                    onRename = { viewModel.rename(club.id, it) },
                    onAvailable = { viewModel.markAvailable(club.id, it) },
                    onDelete = { deleting = club },
                )
            }
            item { SectionTitle("New club") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = it
                            viewModel.clearNewNameError()
                        },
                        label = { Text("Club name") },
                        singleLine = true,
                        isError = ui.newNameError != null,
                        supportingText = ui.newNameError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth().testTag("new-club-name"),
                    )
                    Button(
                        onClick = { viewModel.addClub(newName) { newName = "" } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("add-club"),
                    ) { Text("Add club") }
                }
            }
        }
    }
    deleting?.let { club ->
        ConfirmDialog(
            title = "Delete ${club.name}?",
            text = "The exercises you marked as missing from it are forgotten too.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.delete(club.id) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ClubCard(club: ClubRowUi, onRename: (String) -> Unit, onAvailable: (String) -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("club-card-${club.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = club.name,
                onValueChange = onRename,
                label = { Text("Name") },
                singleLine = true,
                isError = club.nameError != null,
                supportingText = club.nameError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().testTag("club-name-${club.id}"),
            )
            if (club.missing.isEmpty()) {
                Text(
                    "Nothing is marked missing here. Long-press a swap chip on the Exercise screen to mark one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Missing from this club", style = MaterialTheme.typography.titleMedium)
                club.missing.forEach { missing ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(missing.name, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { onAvailable(missing.exerciseId) },
                            modifier = Modifier.heightIn(min = MinTouch).testTag("available-${club.id}-${missing.exerciseId}"),
                        ) { Text("Available again") }
                    }
                }
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.heightIn(min = MinTouch).testTag("delete-club-${club.id}"),
            ) { Text("Delete club") }
        }
    }
}
