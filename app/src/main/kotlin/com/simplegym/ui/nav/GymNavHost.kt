package com.simplegym.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.simplegym.AppContainer
import com.simplegym.timer.TimerController
import com.simplegym.ui.GymViewModelFactory
import com.simplegym.ui.exercise.ExerciseScreen
import com.simplegym.ui.exercise.ExerciseViewModel
import com.simplegym.ui.home.HomeScreen
import com.simplegym.ui.home.HomeViewModel
import com.simplegym.ui.log.LogScreen
import com.simplegym.ui.log.LogViewModel
import com.simplegym.ui.mobility.MobilityScreen
import com.simplegym.ui.mobility.MobilityViewModel
import com.simplegym.ui.onboarding.OnboardingScreen
import com.simplegym.ui.onboarding.OnboardingViewModel
import com.simplegym.ui.plans.DayEditorScreen
import com.simplegym.ui.plans.DayEditorViewModel
import com.simplegym.ui.plans.ExerciseEditorScreen
import com.simplegym.ui.plans.ExerciseEditorViewModel
import com.simplegym.ui.plans.PlansScreen
import com.simplegym.ui.plans.PlansViewModel
import com.simplegym.ui.plans.SlotEditorScreen
import com.simplegym.ui.plans.SlotEditorViewModel
import com.simplegym.ui.progress.ProgressDetailScreen
import com.simplegym.ui.progress.ProgressDetailViewModel
import com.simplegym.ui.progress.ProgressScreen
import com.simplegym.ui.progress.ProgressViewModel
import com.simplegym.ui.settings.ClubsScreen
import com.simplegym.ui.settings.ClubsViewModel
import com.simplegym.ui.settings.SettingsScreen
import com.simplegym.ui.settings.SettingsViewModel
import com.simplegym.ui.theme.Background
import com.simplegym.ui.timer.StandaloneTimerScreen
import com.simplegym.ui.timer.StandaloneTimerViewModel
import com.simplegym.ui.timer.TimerScreen
import com.simplegym.ui.timer.TimerViewModel
import com.simplegym.ui.workout.WorkoutScreen
import com.simplegym.ui.workout.WorkoutViewModel

/** SavedStateHandle key through which the exercise editor hands Plans a note to show. */
private const val PLANS_NOTICE = "plans-notice"

@Composable
fun GymNavHost(container: AppContainer, navController: NavHostController = rememberNavController()) {
    val factory = remember(container) { GymViewModelFactory(container) }
    // Null until the database is seeded and settings are read.
    val onboarded by produceState<Boolean?>(null, container) {
        container.seeded.await()
        container.db.settingsDao().observe().collect { value = it?.onboarded }
    }
    // Onboarding is shown while the lifter is not onboarded, by redirecting from the start destination. The import (or
    // the manual form) sets onboarded; that, not a callback, is what moves the lifter on to Home.
    LaunchedEffect(onboarded) {
        val onOnboarding = navController.currentDestination?.hasRoute<Onboarding>() == true
        when {
            onboarded == false && !onOnboarding ->
                navController.navigate(Onboarding) { popUpTo<Home> { inclusive = true } }
            onboarded == true && onOnboarding ->
                navController.navigate(Home) { popUpTo<Onboarding> { inclusive = true } }
        }
    }
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = Home) {
            composable<Home> {
                val viewModel: HomeViewModel = viewModel(factory = factory)
                HomeScreen(
                    viewModel = viewModel,
                    onOpenWorkout = { navController.navigate(Workout(it)) },
                    onOpenTimer = { navController.navigate(StandaloneTimer) },
                    onOpenProgress = { navController.navigate(Progress) },
                    onOpenPlans = { navController.navigate(Plans) },
                    onOpenSettings = { navController.navigate(Settings) },
                    onOpenLog = { navController.navigate(it) },
                    onResumeTimer = { run ->
                        // The stored run is restarted exactly as it was, so the time on screen is the real time. If it
                        // ran out since the banner was shown there is nothing to resume: clear the banner instead.
                        if (!TimerController.resumeInterrupted(container.appContext)) {
                            viewModel.refreshInterruptedTimer()
                            return@HomeScreen
                        }
                        val sessionId = run.sessionId
                        val slotId = run.slotId
                        val exerciseId = run.exerciseId
                        when {
                            run.isMobility -> navController.navigate(Mobility(sessionId))
                            sessionId != null && slotId != null && exerciseId != null ->
                                navController.navigate(TimerRoute(sessionId, slotId, exerciseId))
                            else -> navController.navigate(StandaloneTimer)
                        }
                    },
                )
            }
            composable<Workout> { entry ->
                val sessionId = entry.toRoute<Workout>().sessionId
                val viewModel: WorkoutViewModel = viewModel(factory = factory)
                WorkoutScreen(
                    viewModel = viewModel,
                    onOpenExercise = { navController.navigate(ExerciseRoute(sessionId, it)) },
                    onStartTimer = { slotId, exerciseId -> navController.navigate(TimerRoute(sessionId, slotId, exerciseId)) },
                    onOpenMobility = { navController.navigate(Mobility(sessionId)) },
                    onOpenLog = { navController.navigate(it) },
                    onFinished = { if (!navController.popBackStack<Home>(inclusive = false)) navController.navigate(Home) },
                )
            }
            composable<ExerciseRoute> { entry ->
                val route = entry.toRoute<ExerciseRoute>()
                val viewModel: ExerciseViewModel = viewModel(factory = factory)
                ExerciseScreen(
                    viewModel = viewModel,
                    onStartTimer = { exerciseId -> navController.navigate(TimerRoute(route.sessionId, route.slotId, exerciseId)) },
                    onLog = { exerciseId -> navController.navigate(LogRoute(route.sessionId, route.slotId, exerciseId)) },
                    onSkipped = { navController.popBackStack() },
                )
            }
            composable<TimerRoute> {
                val viewModel: TimerViewModel = viewModel(factory = factory)
                TimerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onLog = { route -> navController.navigate(route) { popUpTo<TimerRoute> { inclusive = true } } },
                )
            }
            composable<LogRoute> { entry ->
                val sessionId = entry.toRoute<LogRoute>().sessionId
                val viewModel: LogViewModel = viewModel(factory = factory)
                LogScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = {
                        // Back to the Workout already beneath, so its next slot shows; push one only if it is not there
                        // (Log opened from a resumed timer), keeping Home as the single entry below it.
                        if (!navController.popBackStack(Workout(sessionId), inclusive = false)) {
                            navController.navigate(Workout(sessionId)) { popUpTo<Home>() }
                        }
                    },
                )
            }
            composable<Mobility> {
                val viewModel: MobilityViewModel = viewModel(factory = factory)
                MobilityScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable<StandaloneTimer> {
                val viewModel: StandaloneTimerViewModel = viewModel(factory = factory)
                StandaloneTimerScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable<Progress> {
                val viewModel: ProgressViewModel = viewModel(factory = factory)
                ProgressScreen(viewModel = viewModel, onOpenExercise = { navController.navigate(ProgressDetail(it)) })
            }
            composable<ProgressDetail> {
                val viewModel: ProgressDetailViewModel = viewModel(factory = factory)
                ProgressDetailScreen(viewModel)
            }
            composable<Plans> { entry ->
                val viewModel: PlansViewModel = viewModel(factory = factory)
                // The exercise editor leaves a note here ("Restored <name>") for this screen to show.
                val notice by entry.savedStateHandle.getStateFlow<String?>(PLANS_NOTICE, null).collectAsStateWithLifecycle()
                PlansScreen(
                    viewModel = viewModel,
                    notice = notice,
                    onDismissNotice = { entry.savedStateHandle[PLANS_NOTICE] = null },
                    onOpenDay = { navController.navigate(DayEditor(it)) },
                    onNewExercise = { navController.navigate(ExerciseEditor) },
                )
            }
            composable<DayEditor> { entry ->
                val dayId = entry.toRoute<DayEditor>().dayId
                val viewModel: DayEditorViewModel = viewModel(factory = factory)
                DayEditorScreen(
                    viewModel = viewModel,
                    onOpenSlot = { navController.navigate(SlotEditor(dayId, it)) },
                    onClose = { navController.popBackStack() },
                )
            }
            composable<SlotEditor> {
                val viewModel: SlotEditorViewModel = viewModel(factory = factory)
                SlotEditorScreen(viewModel = viewModel, onClose = { navController.popBackStack() })
            }
            composable<ExerciseEditor> {
                val viewModel: ExerciseEditorViewModel = viewModel(factory = factory)
                ExerciseEditorScreen(
                    viewModel = viewModel,
                    onClose = { note ->
                        navController.previousBackStackEntry?.savedStateHandle?.set(PLANS_NOTICE, note)
                        navController.popBackStack()
                    },
                )
            }
            composable<Settings> {
                val viewModel: SettingsViewModel = viewModel(factory = factory)
                SettingsScreen(viewModel = viewModel, onOpenClubs = { navController.navigate(Clubs) })
            }
            composable<Clubs> {
                val viewModel: ClubsViewModel = viewModel(factory = factory)
                ClubsScreen(viewModel)
            }
            composable<Onboarding> {
                val viewModel: OnboardingViewModel = viewModel(factory = factory)
                OnboardingScreen(viewModel)
            }
        }
        // Covers Home until the first settings read says whether to redirect, so a fresh install never flashes it.
        if (onboarded == null) Box(Modifier.fillMaxSize().background(Background))
    }
}
