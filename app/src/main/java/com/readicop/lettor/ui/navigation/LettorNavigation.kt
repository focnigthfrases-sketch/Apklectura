package com.readicop.lettor.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.readicop.lettor.LettorAppContainer
import com.readicop.lettor.ui.bookmarks.BookmarksScreen
import com.readicop.lettor.ui.bookmarks.BookmarksViewModel
import com.readicop.lettor.ui.library.LibraryScreen
import com.readicop.lettor.ui.library.LibraryViewModel
import com.readicop.lettor.ui.reader.ReaderScreen
import com.readicop.lettor.ui.reader.ReaderViewModel
import com.readicop.lettor.ui.settings.SettingsScreen
import com.readicop.lettor.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute

@Serializable
data object BookmarksRoute

@Serializable
data object SettingsRoute

@Serializable
data class ReaderRoute(val bookId: Long, val initialUnit: Int = -1)

private data class TopLevelDestination(
  val route: Any,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String
)

private val TopLevelDestinations = listOf(
  TopLevelDestination(
    route = LibraryRoute,
    label = "Biblioteca",
    selectedIcon = Icons.AutoMirrored.Filled.MenuBook,
    unselectedIcon = Icons.AutoMirrored.Outlined.MenuBook,
    testTag = "nav_library"
  ),
  TopLevelDestination(
    route = BookmarksRoute,
    label = "Marcadores",
    selectedIcon = Icons.Filled.Bookmarks,
    unselectedIcon = Icons.Outlined.Bookmarks,
    testTag = "nav_bookmarks"
  ),
  TopLevelDestination(
    route = SettingsRoute,
    label = "Ajustes",
    selectedIcon = Icons.Filled.Settings,
    unselectedIcon = Icons.Outlined.Settings,
    testTag = "nav_settings"
  )
)

@Composable
fun LettorAppNavigation(
  appContainer: LettorAppContainer,
  pendingDeepLinkBookId: StateFlow<Long?>,
  onConsumeDeepLink: () -> Unit,
  modifier: Modifier = Modifier
) {
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentDestination = navBackStackEntry?.destination
  val isInReader = currentDestination?.hasRoute<ReaderRoute>() == true
  val scope = rememberCoroutineScope()

  val deepLinkBookId by pendingDeepLinkBookId.collectAsStateWithLifecycle()
  var recoveryBookId by remember { mutableStateOf<Long?>(null) }
  var recoveryBookTitle by remember { mutableStateOf("") }

  // Deep link from Home Widget or Reading Reminder notification
  LaunchedEffect(deepLinkBookId) {
    val targetId = deepLinkBookId
    if (targetId != null && targetId > 0L) {
      onConsumeDeepLink()
      navController.navigate(ReaderRoute(bookId = targetId)) {
        launchSingleTop = true
      }
    }
  }

  // Unexpected close position recovery check on startup
  LaunchedEffect(Unit) {
    val prefs = appContainer.preferencesRepository.preferencesFlow.first()
    if (prefs.lastActiveReadingBookId > 0L && deepLinkBookId == null) {
      val book = appContainer.libraryRepository.getBook(prefs.lastActiveReadingBookId)
      if (book != null) {
        recoveryBookTitle = book.title
        recoveryBookId = book.id
      } else {
        appContainer.preferencesRepository.setActiveReadingBookId(-1L)
      }
    }
  }

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val useNavigationRail = maxWidth >= 600.dp

    val navigateToTab: (Any) -> Unit = { route ->
      navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
          saveState = true
        }
        launchSingleTop = true
        restoreState = true
      }
    }

    val isRouteSelected: (Any) -> Boolean = { route ->
      currentDestination?.hierarchy?.any { dest ->
        when (route) {
          is LibraryRoute -> dest.hasRoute<LibraryRoute>()
          is BookmarksRoute -> dest.hasRoute<BookmarksRoute>()
          is SettingsRoute -> dest.hasRoute<SettingsRoute>()
          else -> false
        }
      } == true
    }

    if (useNavigationRail) {
      Row(modifier = Modifier.fillMaxSize()) {
        if (!isInReader) {
          NavigationRail(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
          ) {
            TopLevelDestinations.forEach { dest ->
              val selected = isRouteSelected(dest.route)
              NavigationRailItem(
                selected = selected,
                onClick = { navigateToTab(dest.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = dest.label
                  )
                },
                label = { Text(dest.label) },
                modifier = Modifier.testTag(dest.testTag)
              )
            }
          }
        }
        LettorNavHost(
          navController = navController,
          appContainer = appContainer,
          modifier = Modifier.weight(1f)
        )
      }
    } else {
      Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
          if (!isInReader) {
            NavigationBar(
              windowInsets = WindowInsets.navigationBars,
              containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
              TopLevelDestinations.forEach { dest ->
                val selected = isRouteSelected(dest.route)
                NavigationBarItem(
                  selected = selected,
                  onClick = { navigateToTab(dest.route) },
                  icon = {
                    Icon(
                      imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                      contentDescription = dest.label
                    )
                  },
                  label = { Text(dest.label) },
                  modifier = Modifier.testTag(dest.testTag)
                )
              }
            }
          }
        }
      ) { innerPadding ->
        LettorNavHost(
          navController = navController,
          appContainer = appContainer,
          modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
        )
      }
    }
  }

  val pendingRecovery = recoveryBookId
  if (pendingRecovery != null) {
    AlertDialog(
      onDismissRequest = {
        recoveryBookId = null
        scope.launch { appContainer.preferencesRepository.setActiveReadingBookId(-1L) }
      },
      title = { Text("Retomar lectura anterior") },
      text = {
        Text("La última sesión de «$recoveryBookTitle» se cerró inesperadamente. ¿Quieres volver exactamente al punto donde estabas?")
      },
      confirmButton = {
        Button(
          onClick = {
            recoveryBookId = null
            navController.navigate(ReaderRoute(bookId = pendingRecovery))
          }
        ) {
          Text("Retomar ahora")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            recoveryBookId = null
            scope.launch { appContainer.preferencesRepository.setActiveReadingBookId(-1L) }
          }
        ) {
          Text("Quedarme aquí")
        }
      }
    )
  }
}

@Composable
private fun LettorNavHost(
  navController: androidx.navigation.NavHostController,
  appContainer: LettorAppContainer,
  modifier: Modifier = Modifier
) {
  val appContext = LocalContext.current.applicationContext as Application

  NavHost(
    navController = navController,
    startDestination = LibraryRoute,
    modifier = modifier
  ) {
    composable<LibraryRoute> {
      val vm: LibraryViewModel = viewModel(
        factory = LibraryViewModel.Factory(
          repository = appContainer.libraryRepository,
          preferencesRepository = appContainer.preferencesRepository
        )
      )
      LibraryScreen(
        viewModel = vm,
        adMobManager = appContainer.adMobManager,
        onOpenBook = { bookId ->
          navController.navigate(ReaderRoute(bookId = bookId))
        }
      )
    }

    composable<BookmarksRoute> {
      val vm: BookmarksViewModel = viewModel(
        factory = BookmarksViewModel.Factory(
          repository = appContainer.libraryRepository,
          preferencesRepository = appContainer.preferencesRepository
        )
      )
      BookmarksScreen(
        viewModel = vm,
        onOpenBookmark = { bookId, unitIndex ->
          navController.navigate(ReaderRoute(bookId = bookId, initialUnit = unitIndex))
        }
      )
    }

    composable<SettingsRoute> {
      val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
          appContext = appContext,
          preferencesRepository = appContainer.preferencesRepository,
          repository = appContainer.libraryRepository
        )
      )
      SettingsScreen(
        viewModel = vm,
        adMobManager = appContainer.adMobManager
      )
    }

    composable<ReaderRoute> { backStackEntry ->
      val route = backStackEntry.toRoute<ReaderRoute>()
      val vm: ReaderViewModel = viewModel(
        key = "reader_${route.bookId}_${route.initialUnit}",
        factory = ReaderViewModel.Factory(
          appContext = appContext,
          bookId = route.bookId,
          initialUnit = route.initialUnit,
          repository = appContainer.libraryRepository,
          preferencesRepository = appContainer.preferencesRepository
        )
      )
      ReaderScreen(
        viewModel = vm,
        onNavigateBack = { navController.popBackStack() }
      )
    }
  }
}
