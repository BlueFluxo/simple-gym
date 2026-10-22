package com.simplegym.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.data.SlotStatus
import com.simplegym.ui.components.HurtBadge
import com.simplegym.ui.components.PendingLogBanner
import com.simplegym.ui.components.displayName
import com.simplegym.ui.nav.LogRoute
import com.simplegym.ui.theme.Accent
import com.simplegym.ui.theme.Amber
import com.simplegym.ui.theme.MinTouch

private const val DONE_ALPHA = 0.45f
private val RowShape = RoundedCornerShape(12.dp)
private val SkippedBorder = 3.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    viewModel: WorkoutViewModel,
    onOpenExercise: (slotId: Long) -> Unit,
    onStartTimer: (slotId: Long, exerciseId: String) -> Unit,
    onOpenMobility: () -> Unit,
    onOpenLog: (LogRoute) -> Unit,
    onFinished: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingLog by viewModel.pendingLog.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text(state.dayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pendingLog?.let { pending ->
                item {
                    PendingLogBanner(pending, onOpen = { onOpenLog(pending.route) }, onDismiss = viewModel::dismissPendingLog)
                }
            }
            item {
                Text(
                    "${state.doneCount} of ${state.total} done",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.rows, key = { it.slotId }) { row ->
                SlotRow(
                    row = row,
                    onOpen = { onOpenExercise(row.slotId) },
                    onStartTimer = { onStartTimer(row.slotId, row.timerExerciseId) },
                )
            }
            items(state.finisherOffers, key = { "finisher-${it.name}" }) { chip ->
                OutlinedButton(
                    onClick = { viewModel.addFinisher(chip) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
                ) { Text("+ Flush finisher: ${chip.displayName()}") }
            }
            item {
                OutlinedButton(
                    onClick = onOpenMobility,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
                ) { Text("Core / mobility add-on") }
            }
            item {
                FilledTonalButton(
                    onClick = { viewModel.finish(onFinished) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
                ) { Text("Finish workout") }
            }
        }
    }
}

@Composable
private fun SlotRow(row: SlotRowUi, onOpen: () -> Unit, onStartTimer: () -> Unit) {
    val done = row.status == SlotStatus.DONE
    val skipped = row.status == SlotStatus.SKIPPED
    val nameColor = MaterialTheme.colorScheme.onSurface.fadedIf(done)
    val summaryColor = (if (skipped) Amber else MaterialTheme.colorScheme.onSurfaceVariant).fadedIf(done)
    val decoration = if (done) TextDecoration.LineThrough else TextDecoration.None
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("slot-${row.slotId}")
            .semantics { stateDescription = row.stateLabel() }
            .clip(RowShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (skipped) Modifier.amberStartBorder() else Modifier)
            .then(if (row.isNext) Modifier.border(2.dp, Accent, RowShape) else Modifier)
            .clickable(enabled = !row.isRemoved, onClick = onOpen)
            .heightIn(min = MinTouch)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(row.exerciseName, style = MaterialTheme.typography.titleMedium, color = nameColor, textDecoration = decoration)
        row.hurtBadges.forEach { HurtBadge(it) }
        Text(row.summary, style = MaterialTheme.typography.bodyLarge, color = summaryColor, textDecoration = decoration)
        if (row.isNext) {
            Button(
                onClick = onStartTimer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = MinTouch),
            ) { Text("▶ Start timer") }
        }
    }
}

private fun SlotRowUi.stateLabel(): String = when {
    isNext -> "Next"
    status == SlotStatus.DONE -> "Done"
    status == SlotStatus.SKIPPED -> "Skipped"
    else -> "To do"
}

private fun Color.fadedIf(faded: Boolean): Color = if (faded) copy(alpha = DONE_ALPHA) else this

/** The 3dp amber bar on the start edge (right edge in right-to-left layouts) that marks a skipped row. */
private fun Modifier.amberStartBorder(): Modifier = drawBehind {
    val width = SkippedBorder.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
    drawRect(Amber, topLeft = Offset(x, 0f), size = Size(width, size.height))
}
