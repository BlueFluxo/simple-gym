package com.simplegym.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.simplegym.engine.Joint
import com.simplegym.ui.theme.Amber

private val BadgeShape = RoundedCornerShape(8.dp)
private const val BADGE_BACKGROUND_ALPHA = 0.16f

/** How a joint is written on screen: "Shoulder", "Lower back". */
fun Joint.displayName(): String = when (this) {
    Joint.SHOULDER -> "Shoulder"
    Joint.ELBOW -> "Elbow"
    Joint.WRIST -> "Wrist"
    Joint.KNEE -> "Knee"
    Joint.HIP -> "Hip"
    Joint.LOWER_BACK -> "Lower back"
    Joint.NECK -> "Neck"
}

/** The text of a hurt badge: "⚠ Shoulder · 2×". */
fun hurtBadgeLabel(joint: Joint, count: Int): String = "⚠ ${joint.displayName()} · $count×"

/** The badge labels of one variant, one per flagged joint in joint order. */
fun hurtBadgeLabels(hurts: Map<Joint, Int>): List<String> =
    hurts.entries.sortedBy { it.key }.map { hurtBadgeLabel(it.key, it.value) }

/** A small amber pill that marks a variant that hurt a joint; [label] comes from [hurtBadgeLabel]. */
@Composable
fun HurtBadge(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = Amber,
        modifier = modifier
            .clip(BadgeShape)
            .background(Amber.copy(alpha = BADGE_BACKGROUND_ALPHA))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
