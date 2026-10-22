package com.simplegym.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplegym.data.ClubEntity
import com.simplegym.engine.Chip
import com.simplegym.timer.TimerRun
import com.simplegym.ui.components.GymChip
import com.simplegym.ui.components.PendingLogBanner
import com.simplegym.ui.components.displayName
import com.simplegym.ui.nav.LogRoute
import com.simplegym.ui.theme.Accent
import com.simplegym.ui.theme.Amber
import com.simplegym.ui.theme.MinTouch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenWorkout: (sessionId: Long) -> Unit,
    onOpenTimer: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenPlans: () -> Unit,
    onOpenSettings: () -> Unit,
    onResumeTimer: (TimerRun) -> Unit,
    onOpenLog: (LogRoute) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val skippedIds by viewModel.importSkippedIds.collectAsStateWithLifecycle()
    val pendingLog by viewModel.pendingLog.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshInterruptedTimer()
        onPauseOrDispose {}
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Simple Gym", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    BarButton(HomeIcons.Timer, "Timer", onOpenTimer)
                    BarButton(HomeIcons.Progress, "Progress", onOpenProgress)
                    BarButton(HomeIcons.Plans, "Plans", onOpenPlans)
                    BarButton(HomeIcons.Settings, "Settings", onOpenSettings)
                },
            )
        },
    ) { padding ->
        // The featured day (suggested, or the open session's) goes first; the rest keep plan order.
        val days = state.days.sortedByDescending { it.dayId == state.suggestedDayId }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (skippedIds.isNotEmpty()) {
                item { ImportSkippedBanner(skippedIds, onDismiss = viewModel::dismissImportNotice) }
            }
            pendingLog?.let { pending ->
                item {
                    PendingLogBanner(pending, onOpen = { onOpenLog(pending.route) }, onDismiss = viewModel::dismissPendingLog)
                }
            }
            state.interruptedTimer?.let { run ->
                item { InterruptedTimerBanner(onResume = { onResumeTimer(run) }) }
            }
            if (state.header.isNotEmpty()) {
                item { Text(state.header, style = MaterialTheme.typography.bodyLarge, color = Amber) }
            }
            if (state.clubs.isNotEmpty()) {
                item { ClubSwitcher(state.clubs, state.currentClubId, onSelect = viewModel::selectClub) }
            }
            item { CheckInRow(stiff = state.stiff, onToggle = viewModel::toggleStiff) }
            if (state.flushPrompt) {
                item { Text(FLUSH_PROMPT, style = MaterialTheme.typography.bodyLarge, color = Amber) }
            }
            items(days, key = { it.dayId }) { day ->
                val featured = day.dayId == state.suggestedDayId
                val resuming = featured && state.activeSessionId != null
                DayCard(
                    day = day,
                    featuredLabel = if (!featured) null else if (resuming) "In progress" else "Suggested today",
                    buttonText = (if (resuming) "Resume " else "Start ") + day.name,
                    onClick = {
                        if (resuming) {
                            onOpenWorkout(checkNotNull(state.activeSessionId))
                        } else {
                            viewModel.start(day.dayId, onOpenWorkout)
                        }
                    },
                )
            }
            if (state.showBatteryCard) {
                item { BatteryCard(onDismiss = viewModel::dismissBatteryCard) }
            }
        }
    }
}

/** Which gym the lifter is at: one tap on a chip. "No club" filters nothing out. Shown only once a club exists. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClubSwitcher(clubs: List<ClubEntity>, currentClubId: Long?, onSelect: (Long?) -> Unit) {
    Column(modifier = Modifier.testTag("club-switcher"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Where are you training?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GymChip(
                label = "No club",
                onClick = { onSelect(null) },
                selected = currentClubId == null,
                modifier = Modifier.testTag("club-none"),
            )
            clubs.forEach { club ->
                GymChip(
                    label = club.name,
                    onClick = { onSelect(club.id) },
                    selected = club.id == currentClubId,
                    modifier = Modifier.testTag("club-${club.id}"),
                )
            }
        }
    }
}

/** The optional stiffness check-in: one tap per stiff chip, any number or none. Start does not wait for it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInRow(stiff: Set<Chip>, onToggle: (Chip) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Anything stiff today?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip.entries.forEach { chip ->
                GymChip(
                    label = chip.displayName(),
                    onClick = { onToggle(chip) },
                    selected = chip in stiff,
                    horizontalPadding = CHIP_PADDING,
                )
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.sizeIn(minWidth = MinTouch, minHeight = MinTouch)) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun DayCard(day: DayCardUi, featuredLabel: String?, buttonText: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (featuredLabel != null) BorderStroke(2.dp, Accent) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (featuredLabel != null) {
                Text(featuredLabel, style = MaterialTheme.typography.labelLarge, color = Accent)
            }
            Text(day.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                day.lastDoneText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            day.statusText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Amber) }
            day.recoveryTexts.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch)
            if (featuredLabel != null) {
                Button(onClick = onClick, modifier = modifier) { Text(buttonText) }
            } else {
                FilledTonalButton(onClick = onClick, modifier = modifier) { Text(buttonText) }
            }
        }
    }
}

/** The timer's service died (e.g. Samsung put the app to sleep); the run itself is intact, so one tap carries on. */
@Composable
private fun InterruptedTimerBanner(onResume: () -> Unit) {
    Card(
        onClick = onResume,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, Amber),
    ) {
        Text(
            "Timer was interrupted — Resume",
            style = MaterialTheme.typography.titleMedium,
            color = Amber,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Some entries of the imported starting file named exercises the library does not have, so their weights were dropped. */
@Composable
private fun ImportSkippedBanner(ids: List<String>, onDismiss: () -> Unit) {
    val count = if (ids.size == 1) "1 entry" else "${ids.size} entries"
    Card(
        modifier = Modifier.fillMaxWidth().testTag("import-skipped-banner"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, Amber),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$count skipped in your starting data: ${ids.joinToString(", ")}",
                style = MaterialTheme.typography.titleMedium,
                color = Amber,
            )
            Text(
                "Those exercises are not in the library, so their weights were not imported and they start at default targets.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch)) {
                Text("Dismiss")
            }
        }
    }
}

private const val FLUSH_PROMPT = "Every day has a stiff muscle: try Flush instead on the stiff exercises."
private val CHIP_PADDING = 10.dp
