package com.example.awake.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.awake.AppContainer
import com.example.awake.domain.usecase.ImportTimetableUseCase
import com.example.awake.domain.usecase.LoginUseCase
import com.example.awake.domain.usecase.ObserveTimetableUseCase
import com.example.awake.domain.usecase.RefreshTimetableUseCase
import com.example.awake.ui.auth.AuthScreen
import com.example.awake.ui.auth.AuthViewModel
import com.example.awake.ui.auth.AuthViewModelFactory
import com.example.awake.ui.importterm.TermImportScreen
import com.example.awake.ui.importterm.TermImportViewModel
import com.example.awake.ui.importterm.TermImportViewModelFactory
import com.example.awake.ui.settings.SettingsScreen
import com.example.awake.ui.timetable.CourseDetailScreen
import com.example.awake.ui.timetable.CourseDetailViewModel
import com.example.awake.ui.timetable.CourseDetailViewModelFactory
import com.example.awake.ui.timetable.CourseEditorScreen
import com.example.awake.ui.timetable.CourseEditorViewModel
import com.example.awake.ui.timetable.CourseEditorViewModelFactory
import com.example.awake.ui.timetable.TimetableScreen
import com.example.awake.ui.timetable.TimetableViewModel
import com.example.awake.ui.timetable.TimetableViewModelFactory

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val observe = ObserveTimetableUseCase(container.localRepository)
    val refresh = RefreshTimetableUseCase(container.scutRepository)
    val importer = ImportTimetableUseCase(container.localRepository, container.scutRepository)
    val login = LoginUseCase(container.authRepository, container.localRepository)
    val timetableVm: TimetableViewModel = viewModel(factory = TimetableViewModelFactory(
        observe, refresh, container.localRepository, container.reminderCoordinator,
        container.timetableSelectionStore, container.timetableDisplaySettingsStore, container.scutRepository
    ))
    NavHost(navController = navController, startDestination = Routes.TIMETABLE) {
        composable(Routes.TIMETABLE) {
            TimetableScreen(
                viewModel = timetableVm,
                onLogin = { navController.navigate(Routes.LOGIN) },
                onImport = { navController.navigate(Routes.TERM_IMPORT) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onCourse = { navController.navigate(Routes.courseDetail(it)) },
                onAddCourse = { timetableId, dayOfWeek, startPeriod ->
                    navController.navigate(Routes.courseEditor(timetableId, dayOfWeek, startPeriod))
                }
            )
        }
        composable(Routes.LOGIN) {
            val vm: AuthViewModel = viewModel(factory = AuthViewModelFactory(login, container.academicTermsCache))
            AuthScreen(vm, {
                navController.navigate(Routes.TERM_IMPORT) { popUpTo(Routes.LOGIN) { inclusive = true } }
            }, navController::navigateUp)
        }
        composable(Routes.TERM_IMPORT) {
            val vm: TermImportViewModel = viewModel(factory = TermImportViewModelFactory(
                container.localRepository,
                importer,
                container.reminderCoordinator,
                container.timetableSelectionStore,
                 container.scutRepository,
                container.academicTermsCache
            ))
            TermImportScreen(vm, navController::navigateUp) { navController.popBackStack(Routes.TIMETABLE, false) }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                local = container.localRepository,
                auth = container.authRepository,
                reminderCoordinator = container.reminderCoordinator,
                selection = container.timetableSelectionStore,
                displaySettings = container.timetableDisplaySettingsStore,
                remote = container.scutClient,
                onBack = { navController.popBackStack() },
                onLogin = { navController.navigate(Routes.LOGIN) }
            )
        }
        composable(Routes.COURSE_DETAIL, arguments = listOf(navArgument("courseId") { type = NavType.LongType })) { entry ->
            val courseId = entry.arguments?.getLong("courseId") ?: return@composable
            val vm: CourseDetailViewModel = viewModel(factory = CourseDetailViewModelFactory(container.localRepository, container.reminderCoordinator, courseId))
            CourseDetailScreen(vm, navController::navigateUp)
        }
        composable(
            Routes.COURSE_EDITOR,
            arguments = listOf(
                navArgument("timetableId") { type = NavType.LongType },
                navArgument("dayOfWeek") { type = NavType.IntType },
                navArgument("startPeriod") { type = NavType.IntType }
            )
        ) { entry ->
            val timetableId = entry.arguments?.getLong("timetableId") ?: return@composable
            val dayOfWeek = entry.arguments?.getInt("dayOfWeek") ?: 1
            val startPeriod = entry.arguments?.getInt("startPeriod") ?: 1
            val vm: CourseEditorViewModel = viewModel(
                factory = CourseEditorViewModelFactory(
                    container.localRepository,
                    container.reminderCoordinator,
                    timetableId,
                    dayOfWeek,
                    startPeriod
                )
            )
            CourseEditorScreen(
                viewModel = vm,
                onBack = navController::navigateUp,
                onDone = { navController.popBackStack(Routes.TIMETABLE, false) }
            )
        }
    }
}

