package com.simplegym.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.data.ExerciseEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.toRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val STOP_TIMEOUT_MS = 5_000L

/** [exercises] is null until the first read, so "no history yet" is not flashed while it loads. */
data class ProgressListUi(val exercises: List<ExerciseEntity>?)

/** The exercise picker: every exercise with at least one log, most recently logged first. */
class ProgressViewModel(db: GymDatabase) : ViewModel() {
    val ui: StateFlow<ProgressListUi> = db.libraryDao().observeWithHistory()
        .map { ProgressListUi(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProgressListUi(null))
}

/** [name] is null until the exercise is read; [points] are in date order and exclude logs without a weight. */
data class ProgressDetailUi(val name: String?, val points: List<ChartPoint>, val loaded: Boolean)

/** One exercise's chart. Archived exercises are read like any other, so their history stays reachable. */
class ProgressDetailViewModel(db: GymDatabase, exerciseId: String) : ViewModel() {
    val ui: StateFlow<ProgressDetailUi> = db.logDao().observeForExercise(exerciseId)
        .map { logs ->
            ProgressDetailUi(
                name = db.libraryDao().get(exerciseId)?.name,
                points = chartPoints(logs.map { it.toRecord() }),
                loaded = true,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProgressDetailUi(null, emptyList(), loaded = false))
}
