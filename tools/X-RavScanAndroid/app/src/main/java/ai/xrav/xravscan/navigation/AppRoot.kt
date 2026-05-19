package ai.xrav.xravscan.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.xrav.xravscan.ui.components.AnimatedBackground
import ai.xrav.xravscan.ui.components.NetworkTopBar
import ai.xrav.xravscan.ui.screens.DashboardScreen
import ai.xrav.xravscan.ui.screens.DiscoveryScreen
import ai.xrav.xravscan.ui.screens.ProvidersScreen
import ai.xrav.xravscan.ui.screens.ResultsScreen
import ai.xrav.xravscan.ui.screens.SettingsScreen

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val destinations = remember { Destination.values().toList() }

    AnimatedBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = { NetworkTopBar() },
            bottomBar = {
                GlassBottomBar(
                    items = destinations,
                    navController = navController,
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Destination.Dashboard.route,
                ) {
                    composable(Destination.Dashboard.route) { DashboardScreen() }
                    composable(Destination.Providers.route) { ProvidersScreen() }
                    composable(Destination.Discovery.route) { DiscoveryScreen() }
                    composable(Destination.Results.route) { ResultsScreen() }
                    composable(Destination.Settings.route) { SettingsScreen() }
                }
            }
        }
    }
}

