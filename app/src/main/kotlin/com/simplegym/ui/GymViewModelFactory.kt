package com.simplegym.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.navigation.toRoute
import com.simplegym.AppContainer
import com.simplegym.timer.TimerController
import com.simplegym.ui.exercise.ExerciseViewModel
import com.simplegym.ui.home.HomeViewModel
import com.simplegym.ui.log.LogViewModel
import com.simplegym.ui.nav.DayEditor
import com.simplegym.ui.nav.ExerciseRoute
import com.simplegym.ui.nav.LogRoute
import com.simplegym.ui.nav.Mobility
import com.simplegym.ui.nav.ProgressDetail
import com.simplegym.ui.nav.SlotEditor
import com.simplegym.ui.nav.TimerRoute
import com.simplegym.ui.nav.Workout
import com.simplegym.ui.mobility.MobilityViewModel
import com.simplegym.ui.onboarding.OnboardingViewModel
import com.simplegym.ui.plans.DayEditorViewModel
import com.simplegym.ui.plans.ExerciseEditorViewModel
import com.simplegym.ui.plans.PlansViewModel
import com.simplegym.ui.plans.SlotEditorViewModel
import com.simplegym.ui.progress.ProgressDetailViewModel
import com.simplegym.ui.progress.ProgressViewModel
import com.simplegym.ui.settings.ClubsViewModel
import com.simplegym.ui.settings.SettingsViewModel
import com.simplegym.ui.timer.StandaloneTimerViewModel
import com.simplegym.ui.timer.TimerViewModel
import com.simplegym.ui.workout.WorkoutViewModel
import java.io.IOException

/**
 * Builds every ViewModel from the [AppContainer]; each later screen adds one branch. Route arguments come from the
 * nav entry's saved state ([createSavedStateHandle] + [toRoute]), so they survive process death.
 */
class GymViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(
            db = container.db,
            sessions = container.sessionService,
            blocks = container.blockService,
            checkIns = container.checkInService,
            clubs = container.clubService,
            seeded = container.seeded,
            clock = container.clock,
            today = container::today,
            timerStore = container.timerStore,
            skippedImportIds = container.skippedImportIds,
        )
        modelClass.isAssignableFrom(WorkoutViewModel::class.java) -> WorkoutViewModel(
            db = container.db,
            sessions = container.sessionService,
            targets = container.targetService,
            sessionId = extras.createSavedStateHandle().toRoute<Workout>().sessionId,
            today = container::today,
            timerStore = container.timerStore,
        )
        modelClass.isAssignableFrom(ExerciseViewModel::class.java) -> {
            val route = extras.createSavedStateHandle().toRoute<ExerciseRoute>()
            ExerciseViewModel(
                db = container.db,
                sessions = container.sessionService,
                targets = container.targetService,
                blocks = container.blockService,
                rotations = container.rotationService,
                clubs = container.clubService,
                sessionId = route.sessionId,
                slotId = route.slotId,
                today = container::today,
            )
        }
        modelClass.isAssignableFrom(TimerViewModel::class.java) -> {
            val handle = extras.createSavedStateHandle()
            val route = handle.toRoute<TimerRoute>()
            TimerViewModel(
                appContext = container.appContext,
                db = container.db,
                targets = container.targetService,
                sessionId = route.sessionId,
                slotId = route.slotId,
                exerciseId = route.exerciseId,
                today = container::today,
                handle = handle,
            )
        }
        modelClass.isAssignableFrom(LogViewModel::class.java) -> {
            val handle = extras.createSavedStateHandle()
            val route = handle.toRoute<LogRoute>()
            LogViewModel(
                db = container.db,
                targets = container.targetService,
                logs = container.logService,
                sessionId = route.sessionId,
                slotId = route.slotId,
                exerciseId = route.exerciseId,
                today = container::today,
                timerStore = container.timerStore,
                handle = handle,
            )
        }
        modelClass.isAssignableFrom(MobilityViewModel::class.java) -> MobilityViewModel(
            appContext = container.appContext,
            db = container.db,
            checkIns = container.checkInService,
            recorder = container.mobilityRecorder,
            seeded = container.seeded,
            sessionId = extras.createSavedStateHandle().toRoute<Mobility>().sessionId,
            today = container::today,
        )
        modelClass.isAssignableFrom(StandaloneTimerViewModel::class.java) -> StandaloneTimerViewModel(container.appContext)
        modelClass.isAssignableFrom(ProgressViewModel::class.java) -> ProgressViewModel(container.db)
        modelClass.isAssignableFrom(ProgressDetailViewModel::class.java) -> ProgressDetailViewModel(
            db = container.db,
            exerciseId = extras.createSavedStateHandle().toRoute<ProgressDetail>().exerciseId,
        )
        modelClass.isAssignableFrom(OnboardingViewModel::class.java) -> OnboardingViewModel(
            db = container.db,
            importer = container.startingDataImporter,
            readFile = { uri -> container.appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } },
            seeded = container.seeded,
            today = container::today,
            onSkippedIds = { container.skippedImportIds.value = it },
        )
        modelClass.isAssignableFrom(PlansViewModel::class.java) -> PlansViewModel(container.db, container.planEditor)
        modelClass.isAssignableFrom(DayEditorViewModel::class.java) -> DayEditorViewModel(
            db = container.db,
            editor = container.planEditor,
            dayId = extras.createSavedStateHandle().toRoute<DayEditor>().dayId,
        )
        modelClass.isAssignableFrom(SlotEditorViewModel::class.java) -> {
            val route = extras.createSavedStateHandle().toRoute<SlotEditor>()
            SlotEditorViewModel(container.db, container.planEditor, route.dayId, route.slotId)
        }
        modelClass.isAssignableFrom(ExerciseEditorViewModel::class.java) ->
            ExerciseEditorViewModel(container.db, container.planEditor)
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
            db = container.db,
            settings = container.settingsService,
            importer = container.startingDataImporter,
            backup = container.backupService,
            readFile = { uri -> container.appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } },
            today = container::today,
            writeFile = { uri, text ->
                val stream = container.appContext.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri")
                stream.bufferedWriter().use { it.write(text) }
            },
            stopTimer = { TimerController.stop(container.appContext) },
            onSkippedIds = { container.skippedImportIds.value = it },
        )
        modelClass.isAssignableFrom(ClubsViewModel::class.java) -> ClubsViewModel(container.db, container.clubService)
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    } as T
}
