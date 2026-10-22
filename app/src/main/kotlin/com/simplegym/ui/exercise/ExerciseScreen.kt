package com.simplegym.ui.exercise

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.ui.components.GymChip
import com.simplegym.ui.components.HurtBadge
import com.simplegym.ui.theme.Amber
import com.simplegym.ui.theme.MinTouch

private val CardShape = RoundedCornerShape(16.dp)
private const val STOP_SHORT_LINE = "Stop 1–2 reps short of failure."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseScreen(
    viewModel: ExerciseViewModel,
    onStartTimer: (exerciseId: String) -> Unit,
    onLog: (exerciseId: String) -> Unit,
    onSkipped: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text(state.variantName, maxLines = 2, overflow = TextOverflow.Ellipsis) }) },
        bottomBar = {
            Column(
                modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onStartTimer(state.variantId) },
                    enabled = state.loaded,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
                ) { Text("▶ Start timer") }
                // Logging never depends on the timer: a set done without it (or after it ended unseen) is logged from here.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(
                        onClick = { onLog(state.variantId) },
                        enabled = state.loaded,
                        modifier = Modifier.weight(1f).heightIn(min = MinTouch),
                    ) { Text("Log") }
                    FilledTonalButton(
                        onClick = { viewModel.skip(onSkipped) },
                        modifier = Modifier.weight(1f).heightIn(min = MinTouch),
                    ) { Text("Skip for now") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.hurtBadges.isNotEmpty()) {
                item { HurtBadges(state.hurtBadges) }
            }
            state.keepOffer?.let { offer ->
                item { KeepOfferRow(offer, onAnswer = viewModel::answerKeepOffer) }
            }
            state.hurtBanner?.let { banner ->
                item { HurtBanner(banner, onSwap = viewModel::pick) }
            }
            state.jointNote?.let { note ->
                item {
                    Text(
                        note,
                        modifier = Modifier.testTag("joint-note"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.target?.let { target -> item { TargetCard(target) } }
            // also while flush is on, so it can always be undone (the check-in may have changed since it was switched on)
            if (state.stiffMuscle || state.flush) {
                item { FlushSwitch(checked = state.flush, onChange = viewModel::setFlush) }
            }
            if (state.plateau) {
                item { PlateauBanner(chips = state.chips, onRotate = viewModel::rotateTo) }
            }
            item {
                SwapChips(
                    chips = state.chips,
                    onPick = viewModel::pick,
                    onLongPress = if (state.canMarkUnavailable) viewModel::askUnavailable else null,
                    searching = state.search != null,
                    onToggleSearch = { if (state.search == null) viewModel.openSearch() else viewModel.closeSearch() },
                )
            }
            state.unavailableOffer?.let { chip ->
                item { UnavailableOffer(chip, onConfirm = viewModel::confirmUnavailable, onCancel = viewModel::cancelUnavailable) }
            }
            state.search?.let { search ->
                item {
                    SearchControls(
                        search = search,
                        onQuery = viewModel::setQuery,
                        onAllGroups = viewModel::setAllGroups,
                    )
                }
                items(search.results, key = { it.exerciseId }) { result ->
                    SearchResultRow(result, onClick = { viewModel.pick(result.exerciseId) })
                }
            }
        }
    }
}

/** The amber hurt badges of the variant on screen (one per flagged joint), just under the bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HurtBadges(labels: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { HurtBadge(it) }
    }
}

/**
 * Inline, not a dialog: today's check-in has this muscle stiff, so the held target stands unless the lifter switches
 * to a light high-rep flush.
 */
@Composable
private fun FlushSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("flush-switch")
            .heightIn(min = MinTouch)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Flush instead", style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Inline, not a dialog: this variant hurt a joint, so the joint-friendly swap (when there is one) is one tap away.
 * Starting the timer without tapping it means "continue anyway".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HurtBanner(banner: HurtBannerUi, onSwap: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hurt-banner")
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            banner.message,
            modifier = Modifier.testTag("hurt-banner-text"),
            style = MaterialTheme.typography.titleMedium,
            color = Amber,
        )
        banner.swap?.let { swap ->
            GymChip(
                label = swap.name,
                detail = swap.weightLabel,
                hurtBadges = swap.hurtBadges,
                onClick = { onSwap(swap.exerciseId) },
                modifier = Modifier.testTag("hurt-swap-${swap.exerciseId}"),
            )
        }
    }
}

/** Inline, not a dialog: the variant has stalled, so the slot's swaps are offered as this block's variation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlateauBanner(chips: List<SwapChipUi>, onRotate: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plateau-banner")
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Rotate to another variation for this block?", style = MaterialTheme.typography.titleMedium, color = Amber)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { chip ->
                GymChip(
                    label = chip.name,
                    detail = chip.weightLabel,
                    onClick = { onRotate(chip.exerciseId) },
                    modifier = Modifier.testTag("rotate-${chip.exerciseId}"),
                )
            }
        }
    }
}

/** First session of the block after a rotation: one tap keeps the rotated variant as the default, "No" does not. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeepOfferRow(offer: KeepOfferUi, onAnswer: (keep: Boolean) -> Unit) {
    FlowRow(
        modifier = Modifier.testTag("keep-offer"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GymChip(
            label = "Keep ${offer.name} as default?",
            onClick = { onAnswer(true) },
            modifier = Modifier.testTag("keep-yes"),
        )
        GymChip(label = "No", onClick = { onAnswer(false) }, modifier = Modifier.testTag("keep-no"))
    }
}

@Composable
private fun TargetCard(target: TargetCardUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("target-card")
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (target.weight != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.weight, style = MaterialTheme.typography.displayLarge)
                Text("kg", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(target.sets, style = MaterialTheme.typography.titleLarge)
            Text(target.repRange, style = MaterialTheme.typography.titleLarge)
            Text(target.roundTime, style = MaterialTheme.typography.titleLarge)
        }
        Text(target.reason, style = MaterialTheme.typography.bodyLarge)
        target.hint?.let {
            Text("Hint: $it", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(STOP_SHORT_LINE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwapChips(
    chips: List<SwapChipUi>,
    onPick: (String) -> Unit,
    onLongPress: ((String) -> Unit)?,
    searching: Boolean,
    onToggleSearch: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            GymChip(
                label = chip.name,
                detail = chip.weightLabel,
                hurtBadges = chip.hurtBadges,
                onClick = { onPick(chip.exerciseId) },
                modifier = Modifier.testTag("chip-${chip.exerciseId}"),
                onLongClick = onLongPress?.let { { it(chip.exerciseId) } },
            )
        }
        GymChip(label = "More…", onClick = onToggleSearch, selected = searching)
    }
}

/** Inline, not a dialog: a long press on a swap chip asks whether the current club lacks that exercise. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnavailableOffer(chip: SwapChipUi, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("unavailable-offer")
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(chip.name, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GymChip(label = "Not at this club", onClick = onConfirm, modifier = Modifier.testTag("not-at-this-club"))
            GymChip(label = "Cancel", onClick = onCancel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchControls(search: SearchUi, onQuery: (String) -> Unit, onAllGroups: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = search.query,
            onValueChange = onQuery,
            label = { Text("Search exercises") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
        )
        GymChip(label = "All", onClick = { onAllGroups(!search.allGroups) }, selected = search.allGroups)
    }
}

@Composable
private fun SearchResultRow(result: SearchResultUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("result-${result.exerciseId}")
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .heightIn(min = MinTouch)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(result.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (result.highBackLoadWarning) {
            Text("⚠ High back load", style = MaterialTheme.typography.bodyMedium, color = Amber)
        }
    }
}
