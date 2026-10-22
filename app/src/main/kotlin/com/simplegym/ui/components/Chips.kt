package com.simplegym.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simplegym.ui.theme.MinTouch

private val ChipShape = RoundedCornerShape(16.dp)

/**
 * A one-tap chip at least [MinTouch] tall and wide: [label], with an optional smaller [detail] line (a remembered
 * weight). [hurtBadges] are amber hurt labels (see [hurtBadgeLabel]). [selected] fills it with the accent, for toggles
 * such as "All". A narrower [horizontalPadding] lets a long row of short chips fit in fewer lines. A long press runs
 * [onLongClick] when there is one (a second, rarer action such as "Not at this club").
 */
@Composable
fun GymChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    hurtBadges: List<String> = emptyList(),
    selected: Boolean = false,
    horizontalPadding: Dp = 16.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val background = if (selected) colors.primary else colors.surfaceContainerHighest
    val content = if (selected) colors.onPrimary else colors.onSurface
    Column(
        modifier = modifier
            .clip(ChipShape)
            .background(background)
            .semantics { this.selected = selected }
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = MinTouch)
            .widthIn(min = MinTouch)
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = content)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) content else colors.onSurfaceVariant,
            )
        }
        hurtBadges.forEach { HurtBadge(it, modifier = Modifier.padding(top = 4.dp)) }
    }
}
