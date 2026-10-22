package com.simplegym.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.simplegym.data.GymDatabase
import com.simplegym.timer.PendingLog
import com.simplegym.ui.nav.LogRoute
import com.simplegym.ui.theme.Amber
import com.simplegym.ui.theme.MinTouch

/** A set whose timer ended unseen and is still to be logged, with the name the banner shows. */
data class PendingLogUi(val route: LogRoute, val exerciseName: String)

/** The banner's data: the exercise is named from the library, or by its id when the library no longer has it. */
suspend fun PendingLog.toUi(db: GymDatabase): PendingLogUi =
    PendingLogUi(LogRoute(sessionId, slotId, exerciseId), db.libraryDao().get(exerciseId)?.name ?: exerciseId)

/**
 * Inline, not a dialog: the timer for this set ended while the app was not on screen, so one tap opens its log, and the
 * cross drops the reminder.
 */
@Composable
fun PendingLogBanner(pending: PendingLogUi, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("pending-log-banner"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, Amber),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Log ${pending.exerciseName}",
                style = MaterialTheme.typography.titleMedium,
                color = Amber,
                modifier = Modifier.weight(1f).clickable(onClick = onOpen).heightIn(min = MinTouch).padding(16.dp),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minWidth = MinTouch, minHeight = MinTouch)
                    .semantics { contentDescription = "Dismiss log reminder" },
            ) { Text("✕", style = MaterialTheme.typography.titleMedium) }
        }
    }
}
