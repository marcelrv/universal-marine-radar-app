package com.marineyachtradar.mayara.ui.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Settings home screen — top-level menu listing all settings categories (spec §3.5).
 *
 * Each row navigates to the corresponding sub-screen. [onFinish] is called when the user
 * presses back from this root screen (finishing the Activity).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    onNavigateToConnection: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToUnits: () -> Unit,
    onNavigateToRadarInfo: () -> Unit,
    onNavigateToAppInfo: () -> Unit,
    onFinish: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onFinish) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            item {
                SettingsNavRow(
                    title = "Connection Manager",
                    subtitle = "Active connection, manual IP/port override",
                    onClick = onNavigateToConnection,
                )
                HorizontalDivider()
            }
            item {
                SettingsNavRow(
                    title = "Server Logs",
                    subtitle = "Embedded mayara-server diagnostic output",
                    onClick = onNavigateToLogs,
                )
                HorizontalDivider()
            }
            item {
                SettingsNavRow(
                    title = "Units & Formats",
                    subtitle = "Distance units, bearing reference",
                    onClick = onNavigateToUnits,
                )
                HorizontalDivider()
            }
            item {
                SettingsNavRow(
                    title = "Radar Info",
                    subtitle = "Connected radar details, operating time",
                    onClick = onNavigateToRadarInfo,
                )
                HorizontalDivider()
            }
            item {
                SettingsNavRow(
                    title = "App Info",
                    subtitle = "Version, license, about",
                    onClick = onNavigateToAppInfo,
                )
            }
        }
    }
}

@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
