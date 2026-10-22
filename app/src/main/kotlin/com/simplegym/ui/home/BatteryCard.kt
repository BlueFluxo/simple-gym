package com.simplegym.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.simplegym.ui.theme.MinTouch

const val BATTERY_CARD_TEXT = "Keep the timer running with the screen off: Settings → Battery → Unrestricted."

/**
 * One Samsung (One UI) fix for the timer being put to sleep: set the app's battery use to Unrestricted. Shown until
 * dismissed, and again after a timer was killed early.
 */
@Composable
fun BatteryCard(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().testTag("battery-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(BATTERY_CARD_TEXT, style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = { context.startActivity(appDetailsIntent(context)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
            ) { Text("Open battery settings") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch)) {
                Text("Dismiss")
            }
        }
    }
}

/** The app's own settings page, where Battery lives. */
internal fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
