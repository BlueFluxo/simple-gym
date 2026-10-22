package com.simplegym.ui.nav

import kotlinx.serialization.Serializable

@Serializable
data object Home

@Serializable
data class Workout(val sessionId: Long)

@Serializable
data class ExerciseRoute(val sessionId: Long, val slotId: Long)

/**
 * [startNew] is true when the lifter asked for this timer. The first run the screen starts uses it up, so an entry
 * restored after process death joins the run in progress (or its pending log) instead of starting another.
 */
@Serializable
data class TimerRoute(val sessionId: Long, val slotId: Long, val exerciseId: String, val startNew: Boolean = true)

@Serializable
data class LogRoute(val sessionId: Long, val slotId: Long, val exerciseId: String)

@Serializable
data class Mobility(val sessionId: Long?)

@Serializable
data object StandaloneTimer

@Serializable
data object Progress

@Serializable
data class ProgressDetail(val exerciseId: String)

@Serializable
data object Plans

@Serializable
data class DayEditor(val dayId: Long)

/** [slotId] null adds a slot to [dayId]. */
@Serializable
data class SlotEditor(val dayId: Long, val slotId: Long? = null)

@Serializable
data object ExerciseEditor

@Serializable
data object Settings

@Serializable
data object Onboarding

@Serializable
data object Clubs
