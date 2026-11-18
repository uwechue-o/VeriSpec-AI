package ai.verispec.app.ui.navigation

import ai.verispec.app.ui.bundles.BundleDetailScreen
import ai.verispec.app.ui.bundles.BundleListScreen
import ai.verispec.app.ui.clauses.ClauseCoverageScreen
import ai.verispec.app.ui.fleet.FleetScreen
import ai.verispec.app.ui.run.LiveRunScreen
import ai.verispec.app.ui.run.RunComposerScreen
import ai.verispec.app.ui.run.RunEffect
import ai.verispec.app.ui.run.RunIntent
import ai.verispec.app.ui.run.RunUiState
import ai.verispec.app.ui.run.RunViewModel
import ai.verispec.app.ui.verify.VerifyScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

object Routes {
    const val COMPOSER = "composer"
    const val LIVE = "live"
    const val BUNDLES = "bundles"
    const val BUNDLE_DETAIL = "bundle/{bundleId}"
    const val CLAUSES = "clauses/{bundleId}"
    const val FLEET = "fleet"
    const val VERIFY = "verify"

    fun bundleDetail(id: String) = "bundle/$id"
    fun clauses(id: String) = "clauses/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.COMPOSER, "Run", Icons.Filled.PlayArrow),
    Tab(Routes.BUNDLES, "Bundles", Icons.Filled.Folder),
    Tab(Routes.FLEET, "Fleet", Icons.Filled.Analytics),
    Tab(Routes.VERIFY, "Verify", Icons.Filled.VerifiedUser),
)

@Composable
fun VerispecApp() {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // One RunViewModel for the whole app so the composer and the live screen
    // share a single run state machine.
    val runViewModel: RunViewModel = hiltViewModel()

    LaunchedEffect(Unit) {
        runViewModel.effects.collect { effect ->
            when (effect) {
                is RunEffect.ShowError -> snackbar.showSnackbar(effect.message)
                is RunEffect.NavigateToLive -> navController.navigate(Routes.LIVE)
                is RunEffect.NavigateToBundle -> navController.navigate(Routes.bundleDetail(effect.id.value))
            }
        }
    }

    Scaffold(
        bottomBar = { BottomBar(navController) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val runState by runViewModel.state.collectAsStateWithLifecycle()

        NavHost(navController = navController, startDestination = Routes.COMPOSER, modifier = Modifier.padding(padding)) {
            composable(Routes.COMPOSER) {
                when (val s = runState) {
                    is RunUiState.Configuring -> RunComposerScreen(s, runViewModel::onIntent)
                    else -> LiveRunScreen(
                        state = runState,
                        onIntent = runViewModel::onIntent,
                        onOpenBundle = { navController.navigate(Routes.bundleDetail(it)) },
                        onNewRun = { runViewModel.onIntent(RunIntent.NewRun) },
                    )
                }
            }
            composable(Routes.LIVE) {
                LiveRunScreen(
                    state = runState,
                    onIntent = runViewModel::onIntent,
                    onOpenBundle = { navController.navigate(Routes.bundleDetail(it)) },
                    onNewRun = {
                        runViewModel.onIntent(RunIntent.NewRun)
                        navController.navigate(Routes.COMPOSER)
                    },
                )
            }
            composable(Routes.BUNDLES) {
                BundleListScreen(onOpen = { navController.navigate(Routes.bundleDetail(it)) })
            }
            composable(Routes.BUNDLE_DETAIL) {
                BundleDetailScreen(
                    onOpenClauses = { navController.navigate(Routes.clauses(it)) },
                    onDeleted = { navController.popBackStack() },
                    onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                )
            }
            composable(Routes.CLAUSES) { ClauseCoverageScreen() }
            composable(Routes.FLEET) {
                FleetScreen(onOpen = { navController.navigate(Routes.bundleDetail(it)) })
            }
            composable(Routes.VERIFY) { VerifyScreen() }
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController) {
    val entry by navController.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    NavigationBar {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = current == tab.route || (tab.route == Routes.COMPOSER && current == Routes.LIVE),
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}
