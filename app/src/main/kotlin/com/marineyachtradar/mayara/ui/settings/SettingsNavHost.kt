package com.marineyachtradar.mayara.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.marineyachtradar.mayara.ui.settings.screens.AppInfoScreen
import com.marineyachtradar.mayara.ui.settings.screens.ConnectionSettingsScreen
import com.marineyachtradar.mayara.ui.settings.screens.EmbeddedServerLogsScreen
import com.marineyachtradar.mayara.ui.settings.screens.RadarInfoScreen
import com.marineyachtradar.mayara.ui.settings.screens.SettingsHomeScreen
import com.marineyachtradar.mayara.ui.settings.screens.UnitsScreen

/**
 * Sealed hierarchy representing every destination inside [SettingsActivity].
 *
 * Each subclass carries its [route] string; the companion exposes a convenience
 * list of all routes used by navigation tests.
 */
sealed class SettingsScreen(val route: String) {
    data object Home : SettingsScreen("settings_home")
    data object ConnectionSettings : SettingsScreen("connection_settings")
    data object EmbeddedServerLogs : SettingsScreen("server_logs")
    data object Units : SettingsScreen("units")
    data object RadarInfo : SettingsScreen("radar_info")
    data object AppInfo : SettingsScreen("app_info")

    companion object {
        val all: List<SettingsScreen> by lazy {
            listOf(Home, ConnectionSettings, EmbeddedServerLogs, Units, RadarInfo, AppInfo)
        }
    }
}

/**
 * Root Compose Navigation graph for [SettingsActivity].
 *
 * @param viewModel Shared [SettingsViewModel]; state is hoisted and passed to each screen.
 * @param onFinish  Called when the user navigates back from the home screen (finishes the Activity).
 */
@Composable
fun SettingsNavHost(
    viewModel: SettingsViewModel,
    onFinish: () -> Unit,
) {
    val navController = rememberNavController()

    val connectionState by viewModel.connectionState.collectAsState()
    val distanceUnit by viewModel.distanceUnit.collectAsState()
    val bearingMode by viewModel.bearingMode.collectAsState()
    val serverLogs by viewModel.serverLogs.collectAsState()
    val radarInfo by viewModel.radarInfo.collectAsState()

    NavHost(
        navController = navController,
        startDestination = SettingsScreen.Home.route,
    ) {
        composable(SettingsScreen.Home.route) {
            SettingsHomeScreen(
                onNavigateToConnection = {
                    navController.navigate(SettingsScreen.ConnectionSettings.route)
                },
                onNavigateToLogs = {
                    navController.navigate(SettingsScreen.EmbeddedServerLogs.route)
                },
                onNavigateToUnits = {
                    navController.navigate(SettingsScreen.Units.route)
                },
                onNavigateToRadarInfo = {
                    navController.navigate(SettingsScreen.RadarInfo.route)
                },
                onNavigateToAppInfo = {
                    navController.navigate(SettingsScreen.AppInfo.route)
                },
                onFinish = onFinish,
            )
        }

        composable(SettingsScreen.ConnectionSettings.route) {
            ConnectionSettingsScreen(
                uiState = connectionState,
                onSwitchConnection = { 
                    viewModel.onSwitchConnection() 
                    onFinish()
                },
                onSaveManualConnection = { host, port ->
                    viewModel.onSaveManualConnection(host, port)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(SettingsScreen.EmbeddedServerLogs.route) {
            EmbeddedServerLogsScreen(
                logs = serverLogs,
                onRefresh = { viewModel.refreshLogs() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(SettingsScreen.Units.route) {
            UnitsScreen(
                distanceUnit = distanceUnit,
                bearingMode = bearingMode,
                onDistanceUnitChange = { viewModel.onDistanceUnitChange(it) },
                onBearingModeChange = { viewModel.onBearingModeChange(it) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(SettingsScreen.RadarInfo.route) {
            RadarInfoScreen(
                radarInfo = radarInfo,
                onBack = { navController.popBackStack() },
            )
        }

        composable(SettingsScreen.AppInfo.route) {
            AppInfoScreen(
                appVersion = viewModel.appVersion,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
