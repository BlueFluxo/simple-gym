package com.simplegym.ui.components

import com.simplegym.engine.Chip
import com.simplegym.engine.MobilityArea

/** How a check-in chip is written on screen: "Legs", "Lower back". */
fun Chip.displayName(): String = when (this) {
    Chip.BACK -> "Back"
    Chip.BICEPS -> "Biceps"
    Chip.CHEST -> "Chest"
    Chip.TRICEPS -> "Triceps"
    Chip.SHOULDERS -> "Shoulders"
    Chip.LEGS -> "Legs"
    Chip.CALVES -> "Calves"
    Chip.LOWER_BACK -> "Lower back"
    Chip.NECK -> "Neck"
}

/** How a mobility area is written on screen. */
fun MobilityArea.displayName(): String = when (this) {
    MobilityArea.NECK -> "Neck"
    MobilityArea.LOWER_BACK -> "Lower back"
    MobilityArea.HIPS -> "Hips"
    MobilityArea.SHOULDERS -> "Shoulders"
    MobilityArea.CORE -> "Core"
}
